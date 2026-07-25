package com.example.testfull.content

import android.util.Log
import com.example.testfull.BuildConfig
import com.pico.spatial.core.math.Vector3
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import kotlin.math.hypot
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

private const val TAG = "AiArranger"
private const val CONNECT_TIMEOUT_MS = 30_000

/** Flagship models (gpt-5) can think for over a minute on a layout; allow it. */
private const val READ_TIMEOUT_MS = 180_000

private const val MIN_AI_SCALE = 0.05f
private const val MAX_AI_SCALE = 5f

/** A library model with measured bounds, offered to the AI as arrangement material. */
internal data class CatalogModel(
    val file: File,
    val displayName: String,
    val center: Vector3,
    val halfExtents: Vector3,
    val bottomOffset: Float,
    /** Raw contents of the optional <name>.json sidecar (color/style/material), if present. */
    val details: String? = null,
    /**
     * Uniform scale bringing the model to its intended real-world size (from the sidecar's
     * geometry vs. the measured bounds; 1 = the authored units are already correct). The AI's
     * placement scale multiplies this.
     */
    val defaultScale: Float = 1f,
)

/** A door/window in navigation-root-local space, part of the AI room description. */
internal data class OpeningDesc(
    val type: OpeningType,
    val wallId: Int,
    val x: Float,
    val z: Float,
    val width: Float,
)

/** One placement requested by the AI, before validation. */
internal data class AiPlacement(
    val modelName: String,
    val x: Float,
    val z: Float,
    val yawDegrees: Float,
    val scale: Float,
)

internal data class AiLayout(
    val placements: List<AiPlacement>,
    val notes: String?,
    /** Optional surface reskins requested by the AI: slot → texture display name. */
    val textures: Map<SurfaceSlot, String> = emptyMap(),
)

/** An AI texture choice that resolved to a real texture from the library. */
internal data class ResolvedAiTextures(
    val resolved: Map<SurfaceSlot, TextureSpec>,
    /** Human-readable reasons for ignored entries ("slot/name (reason)"). */
    val skipped: List<String>,
)

/** An AI placement that passed validation: resolved model, clamped position, no overlaps. */
internal data class ResolvedAiPlacement(
    val model: CatalogModel,
    val x: Float,
    val z: Float,
    val yawDegrees: Float,
    val scale: Float,
)

internal data class ResolvedAiLayout(
    val accepted: List<ResolvedAiPlacement>,
    /** Human-readable reasons for every skipped item ("name (reason)"). */
    val skipped: List<String>,
    /** Items nudged aside because they overlapped an earlier placement. */
    val adjusted: List<String> = emptyList(),
)

/** Failure of the AI request itself (network, HTTP, malformed answer) with a readable message. */
internal class AiArrangeException(message: String) : Exception(message)

/**
 * Measures every library model (and reads its sidecar details) — keeps the readable ones.
 * Measurements go through a disk cache keyed by file mtime: loading the meshes natively is
 * the heaviest thing this app does on the emulator, and the cache makes it a once-per-file
 * cost. The cache is written incrementally so a crash mid-run keeps finished entries.
 */
internal suspend fun buildCatalog(models: List<LibraryModel>): List<CatalogModel> {
    val directory = models.firstOrNull()?.file?.parentFile
    val cache =
        if (directory != null) {
            withContext(Dispatchers.IO) { readBoundsCache(directory) }
        } else {
            mutableMapOf()
        }

    return models.mapNotNull { model ->
        val key = model.file.absolutePath
        val cached = cache[key]
        val bounds =
            if (cached != null && cached.mtimeMs == model.file.lastModified()) {
                cached.toModelBounds()
            } else {
                val measured = measureModelBounds(model.file)
                // Yield between native measurements: back-to-back heavy loads have killed
                // the process on the emulator (memory/CPU pressure). The incremental cache
                // below means a crashed run resumes where it stopped.
                delay(1000)
                if (measured != null && directory != null) {
                    cache[key] =
                        CachedBounds(
                            measured.center,
                            measured.halfExtents,
                            measured.bottomOffset,
                            model.file.lastModified(),
                        )
                    withContext(Dispatchers.IO) { writeBoundsCache(directory, cache) }
                }
                measured
            }
        if (bounds == null) {
            Log.w(TAG, "buildCatalog: no bounds for ${model.displayName}, excluded")
            null
        } else {
            val raw = withContext(Dispatchers.IO) { readModelSidecarRaw(model.file) }
            val defaultScale =
                computeDefaultScale(
                    Vector3(
                        bounds.halfExtents.x * 2f,
                        bounds.halfExtents.y * 2f,
                        bounds.halfExtents.z * 2f,
                    ),
                    parseIntendedSize(raw),
                )
            if (defaultScale != 1f) {
                Log.w(TAG, "buildCatalog: ${model.displayName} defaultScale=$defaultScale")
            }
            CatalogModel(
                file = model.file,
                displayName = model.displayName,
                center = bounds.center,
                halfExtents = bounds.halfExtents,
                bottomOffset = bounds.bottomOffset,
                details = raw?.let { distillModelDetails(it) },
                defaultScale = defaultScale,
            )
        }
    }
}

/**
 * Builds the (system, user) message pair for the chat completion. The system message is the
 * full "preprompt": spatial vocabulary, room semantics, furniture-data rules, and the output
 * contract. The user message carries a structured JSON room descriptor (walls, doors, windows,
 * existing furniture with sizes), the complete library with measured sizes and optional
 * sidecar details, and the user's request. All coordinates are navigation-root-local: the room
 * is centered on the origin and y = 0 is the floor, matching [PlacementController.placeFromAi].
 */
internal fun buildArrangementMessages(
    userPrompt: String,
    walls: List<PlanWall>,
    openings: List<OpeningDesc>,
    catalog: List<CatalogModel>,
    currentPlacements: List<PlacedSummary>,
    textureCatalog: List<TextureSpec> = emptyList(),
): Pair<String, String> {
    val system =
        buildString {
            append(
                "You are an interior-design engine inside a VR room planner. You receive a " +
                    "structured JSON description of a real room (walls, doors, windows, " +
                    "existing furniture) plus a furniture library, and you answer with " +
                    "placement instructions as a single JSON object and nothing else — no " +
                    "prose, no markdown fences.\n\n"
            )
            append("SPATIAL VOCABULARY — use exactly these meanings:\n")
            append("- \"next to / beside\": close but never touching — a 0.05 to 0.3 m gap between bounding boxes. Overlap is never allowed.\n")
            append("- \"against / along the wall\": the item's back within 0.1 m of the wall line, parallel to that wall.\n")
            append("- \"around X\": copies distributed evenly on the sides of X (e.g. 4 chairs: one per side), each one FACING X's center — set yaw so its front points at X.\n")
            append("- \"facing X\": yaw such that the model's front (local +Z) points at X.\n")
            append("- \"opposite X\": across the room or across X, facing X.\n")
            append("- \"corner\": touching both walls of a corner, usually diagonal (yaw 45/135/225/315).\n")
            append("- Clearances: walkways >= 0.6 m wide; keep >= 1.0 m free in front of doors; passage gaps between furniture >= 0.5 m.\n\n")
            append("ROOM SEMANTICS:\n")
            append("- A closed wall loop is one room. If the plan has several closed loops or strong partitions, treat each zone as its own room.\n")
            append("- Typical functions: bedroom (bed, nightstands, wardrobe), bathroom (toilet, sink, tub/shower), living room (sofa/couch, coffee table, chairs, screen), dining area (table with chairs AROUND it), office (desk with a chair facing it).\n")
            append("- Infer each zone's function from the user's request and keep fixtures of one function grouped together.\n\n")
            append("FURNITURE DATA:\n")
            append("- The LIBRARY list in the user message is COMPLETE — you may only use models named there. Sizes there are measured by the app and always accurate.\n")
            append("- A model may include a DETAILS record (asset schema_version 1). Its meaningful fields:\n")
            append("  · identity.name — display name, may be localized (e.g. Chinese); use it when naming the piece in \"notes\".\n")
            append("  · classification.category and classification.room_types — what it is and which room types it belongs to (living_room, dining_room, bedroom, bathroom, office...). Respect them when the plan has zones.\n")
            append("  · appearance.colors / appearance.materials — for matching color or material requests.\n")
            append("  · placement — support_surface, against_wall, front_clearance_m / side_clearance_m: respect these when present.\n")
            append("  · style_assessment.scores — 0..1 per style (e.g. style.nordic, style.wabi_sabi, style.ikea_functional, style.chinese and its variants, style.industrial); higher = stronger match. For style requests, prefer models with high scores in the requested style and say why in \"notes\".\n")
            append("- Fields that are null or absent are UNKNOWN — ignore them; never invent values. If a model has no DETAILS at all, infer its likely category, color and style from its name and dimensions.\n")
            append("- You may use the same model name in several placements when the request implies multiples (\"4 chairs\" = 4 entries).\n\n")
            append("STYLE REQUESTS (e.g. \"modern\", \"cozy\"):\n")
            append("- modern: pieces parallel to walls, aligned axes, symmetry, generous negative space.\n")
            append("- cozy: tighter groupings angled toward a focal point, never below minimum clearances.\n")
            append("- Always explain your interpretation briefly in \"notes\".\n\n")
            append(
                "OUTPUT SCHEMA (strict): {\"placements\": [{\"model\": string, \"x\": number, " +
                    "\"z\": number, \"yaw\": number, \"scale\": number}], \"notes\": string, " +
                    "optional \"textures\": {\"wall|floor|ceiling|door|window\": textureName}}\n" +
                    "Example answer for \"seat two people\": " +
                    "{\"placements\": [{\"model\": \"dining-chair\", \"x\": 1.0, \"z\": 0.0, " +
                    "\"yaw\": 270, \"scale\": 1}, {\"model\": \"dining-chair\", \"x\": -1.0, " +
                    "\"z\": 0.0, \"yaw\": 90, \"scale\": 1}], " +
                    "\"notes\": \"Two chairs facing each other.\"}"
            )
        }

    val user = StringBuilder()
    user.append(
        "ROOM (JSON; meters; origin at the room center; y = 0 is the floor; yaw in degrees, " +
            "0 faces +Z, 90 faces +X):\n{"
    )
    if (walls.isNotEmpty()) {
        val bounds = wallBounds(walls)
        user.append(
            String.format(
                Locale.US,
                "\"bounds\":{\"x\":[%.1f,%.1f],\"z\":[%.1f,%.1f]},",
                bounds.minX,
                bounds.maxX,
                bounds.minZ,
                bounds.maxZ,
            )
        )
        user.append("\"walls\":[")
        walls.forEachIndexed { index, wall ->
            if (index > 0) user.append(",")
            user.append(
                String.format(
                    Locale.US,
                    "{\"id\":%d,\"from\":[%.2f,%.2f],\"to\":[%.2f,%.2f],\"height\":%.2f}",
                    wall.id,
                    wall.start.x,
                    wall.start.z,
                    wall.end.x,
                    wall.end.z,
                    wall.height,
                )
            )
        }
        user.append("],")
        user.append("\"openings\":[")
        openings.forEachIndexed { index, opening ->
            if (index > 0) user.append(",")
            user.append(
                String.format(
                    Locale.US,
                    "{\"type\":\"%s\",\"wall\":%d,\"at\":[%.2f,%.2f],\"width\":%.2f}",
                    opening.type.name.lowercase(Locale.US),
                    opening.wallId,
                    opening.x,
                    opening.z,
                    opening.width,
                )
            )
        }
        user.append("],"
        )
    }
    user.append("\"furniture\":[")
    currentPlacements.forEachIndexed { index, placed ->
        if (index > 0) user.append(",")
        val size = resolveCatalogModel(placed.modelName, catalog)?.halfExtents
        user.append(
            String.format(
                Locale.US,
                "{\"model\":\"%s\",\"x\":%.2f,\"z\":%.2f,\"yaw\":%.0f",
                placed.modelName,
                placed.x,
                placed.z,
                placed.yawDegrees,
            )
        )
        if (size != null) {
            user.append(
                String.format(
                    Locale.US,
                    ",\"size\":[%.2f,%.2f,%.2f]",
                    size.x * 2f,
                    size.y * 2f,
                    size.z * 2f,
                )
            )
        }
        user.append("}")
    }
    user.append("]}\n")
    user.append(
        "ROOM rules: every item fully inside the walls; never covering a door or window; " +
            "items in \"furniture\" are already there — include one in your layout to keep or " +
            "move it, omit it to remove it.\n\n"
    )
    user.append(
        "LIBRARY (complete — nothing else exists; sizes are each model's recommended " +
            "real-world size, and a placement's scale = 1 keeps that size):\n"
    )
    catalog.forEach { model ->
        user.append(
            String.format(
                Locale.US,
                "- %s — footprint %.2f x %.2f m, %.2f m tall.",
                model.displayName,
                model.halfExtents.x * 2f * model.defaultScale,
                model.halfExtents.z * 2f * model.defaultScale,
                model.halfExtents.y * 2f * model.defaultScale,
            )
        )
        if (model.details != null) {
            user.append(" DETAILS: ").append(model.details)
        } else {
            user.append(" (no details file — infer category, color and style from the name)")
        }
        user.append("\n")
    }
    if (textureCatalog.isNotEmpty()) {
        user.append("\nTEXTURES (you may reskin room surfaces; optional):\n")
        textureCatalog.forEach { spec ->
            user.append("- ").append(spec.displayName)
            user.append(" — for surfaces: ")
            user.append(
                if (spec.surfaces.isEmpty()) {
                    "any"
                } else {
                    spec.surfaces.joinToString("/") { it.key }
                }
            )
            if (spec.styles.isNotEmpty()) {
                user.append("; styles: ").append(spec.styles.joinToString(", "))
            }
            spec.details?.let { user.append(" DETAILS: ").append(it) }
            user.append("\n")
        }
        user.append(
            "To reskin a surface, add a \"textures\" object, e.g. {\"wall\": \"<name>\"} — " +
                "only names from this list, and only on surfaces each texture supports. " +
                "Omit the key entirely to leave surfaces unchanged.\n"
        )
    }
    user.append("\nUSER REQUEST: \"").append(userPrompt.trim()).append("\"\n")
    user.append(
        "Rules: only LIBRARY models; everything stands on the floor (no stacking); " +
            "respect the vocabulary gaps — placements must never overlap; keep doors and " +
            "walkways clear. Respond with the placements JSON only."
    )
    return system to user.toString()
}

/**
 * Parses the AI's answer. Entries without a model name or numeric x/z are skipped; yaw defaults
 * to 0 and scale to 1 (clamped to [MIN_AI_SCALE]..[MAX_AI_SCALE]). Throws [AiArrangeException]
 * when the content is not a JSON object with a `placements` array.
 */
internal fun parseAiLayout(content: String): AiLayout {
    val root =
        runCatching { JSONObject(content) }.getOrElse {
            throw AiArrangeException("AI did not return valid JSON")
        }
    val array =
        root.optJSONArray("placements")
            ?: throw AiArrangeException("AI response has no placements array")
    val placements = mutableListOf<AiPlacement>()
    for (index in 0 until array.length()) {
        val entry = array.optJSONObject(index) ?: continue
        val model = entry.optString("model", "").trim()
        if (model.isEmpty()) continue
        val x = entry.optDouble("x", Double.NaN)
        val z = entry.optDouble("z", Double.NaN)
        if (x.isNaN() || z.isNaN()) continue
        val yaw = entry.optDouble("yaw", 0.0).toFloat()
        val scale =
            entry.optDouble("scale", 1.0).toFloat().coerceIn(MIN_AI_SCALE, MAX_AI_SCALE)
        placements += AiPlacement(model, x.toFloat(), z.toFloat(), yaw, scale)
    }
    val notes = root.optString("notes", "").trim().ifEmpty { null }
    val textures = mutableMapOf<SurfaceSlot, String>()
    root.optJSONObject("textures")?.let { entries ->
        entries.keys().forEach { key ->
            val slot = SurfaceSlot.fromKey(key) ?: return@forEach
            val name = entries.optString(key, "").trim()
            if (name.isNotEmpty()) textures[slot] = name
        }
    }
    return AiLayout(placements, notes, textures)
}

/**
 * Resolves the AI's texture choices against the texture library (case-insensitive, exact then
 * substring) and enforces each texture's declared surfaces. Unknown or mismatched entries are
 * skipped with reasons rather than failing the whole layout.
 */
internal fun resolveAiTextures(
    textures: Map<SurfaceSlot, String>,
    catalog: List<TextureSpec>,
): ResolvedAiTextures {
    val resolved = mutableMapOf<SurfaceSlot, TextureSpec>()
    val skipped = mutableListOf<String>()
    textures.forEach { (slot, name) ->
        val query = normalizedName(name)
        val spec =
            catalog.firstOrNull { normalizedName(it.displayName) == query }
                ?: catalog.firstOrNull {
                    val candidate = normalizedName(it.displayName)
                    candidate.contains(query) || query.contains(candidate)
                }
        when {
            spec == null -> skipped += "${slot.key}/\"$name\" (unknown texture)"
            spec.surfaces.isNotEmpty() && slot !in spec.surfaces ->
                skipped +=
                    "${slot.key}/\"$name\" (meant for " +
                        spec.surfaces.joinToString("/") { it.key } + ")"
            else -> resolved[slot] = spec
        }
    }
    return ResolvedAiTextures(resolved, skipped)
}

/** Name matching that ignores case, spaces, hyphens and underscores ("Red Brick"≈"red-brick"). */
private fun normalizedName(value: String): String =
    value.lowercase(Locale.US).replace(Regex("[-_\\s]+"), "")

/** Finds a catalog model by name: exact (normalized) first, then substring either way. */
internal fun resolveCatalogModel(name: String, catalog: List<CatalogModel>): CatalogModel? {
    val query = normalizedName(name)
    if (query.isEmpty()) return null
    return catalog.firstOrNull { normalizedName(it.displayName) == query }
        ?: catalog.firstOrNull {
            val candidate = normalizedName(it.displayName)
            candidate.contains(query) || query.contains(candidate)
        }
}

/** Step size and travel cap for the overlap-separation nudge, in meters. */
private const val SEPARATION_STEP_METERS = 0.05f
private const val SEPARATION_MAX_TRAVEL_METERS = 4f

/**
 * Nudges a candidate placement across the ground (XZ) until its yawed bounding box has zero
 * intersection with every already-accepted box (tolerance 0: mere contact counts as
 * separated). The push points away from the blocking box's center in small steps and the
 * result is re-clamped into the footprint every step. Returns the resolved point — the
 * original one when nothing overlaps — or null when no free spot exists within
 * [SEPARATION_MAX_TRAVEL_METERS] of travel.
 */
internal fun separateFromBoxes(
    point: PlanPoint,
    pivotY: Float,
    yawDegrees: Float,
    scale: Float,
    center: Vector3,
    halfExtents: Vector3,
    others: List<YawBox>,
    walls: List<PlanWall>,
    margin: Float,
): PlanPoint? {
    var current = point
    val maxIterations = (SEPARATION_MAX_TRAVEL_METERS / SEPARATION_STEP_METERS).toInt()
    repeat(maxIterations) {
        val box =
            yawBoxFor(
                Vector3(current.x, pivotY, current.z),
                yawDegrees,
                scale,
                center,
                halfExtents,
            )
        val blocker = others.firstOrNull { yawBoxesOverlap(box, it, 0f) } ?: return current
        var dirX = current.x - blocker.centerX
        var dirZ = current.z - blocker.centerZ
        val length = hypot(dirX.toDouble(), dirZ.toDouble()).toFloat()
        if (length < 0.0001f) {
            // Centers coincide: pick an arbitrary escape direction.
            dirX = 1f
            dirZ = 0f
        } else {
            dirX /= length
            dirZ /= length
        }
        val stepped =
            PlanPoint(
                current.x + dirX * SEPARATION_STEP_METERS,
                current.z + dirZ * SEPARATION_STEP_METERS,
            )
        current = clampToFootprint(walls, stepped, margin)
    }
    return null
}

/**
 * Validates an AI layout against the room's physics constraints, in order:
 * unknown models are skipped; positions are clamped inside the footprint with a margin of
 * [baseMargin] plus the model's scaled half-diagonal (so a 45°-yawed box still clears the
 * walls); and any item whose yawed box would interpenetrate an already-accepted item is
 * nudged away via [separateFromBoxes] until the boxes no longer intersect — skipped only
 * when no free space remains. Validation is sequential, so earlier items keep their spot.
 */
internal fun resolveAiPlacements(
    layout: AiLayout,
    catalog: List<CatalogModel>,
    walls: List<PlanWall>,
    baseMargin: Float,
): ResolvedAiLayout {
    val accepted = mutableListOf<ResolvedAiPlacement>()
    val acceptedBoxes = mutableListOf<YawBox>()
    val skipped = mutableListOf<String>()
    val adjusted = mutableListOf<String>()

    layout.placements.forEach { placement ->
        val model = resolveCatalogModel(placement.modelName, catalog)
        if (model == null) {
            skipped += "\"${placement.modelName}\" (not in the model library)"
            return@forEach
        }
        // The AI's scale multiplies the model's recommended size; everything below uses the
        // effective (final) scale so clamps, pivots and overlap tests match what will spawn.
        val effectiveScale = placement.scale * model.defaultScale
        val halfDiagonal =
            hypot(model.halfExtents.x.toDouble(), model.halfExtents.z.toDouble()).toFloat() *
                effectiveScale
        val margin = baseMargin + halfDiagonal
        val clamped = clampToFootprint(walls, PlanPoint(placement.x, placement.z), margin)
        val pivotY = model.bottomOffset * effectiveScale
        val separated =
            separateFromBoxes(
                point = clamped,
                pivotY = pivotY,
                yawDegrees = placement.yawDegrees,
                scale = effectiveScale,
                center = model.center,
                halfExtents = model.halfExtents,
                others = acceptedBoxes,
                walls = walls,
                margin = margin,
            )
        if (separated == null) {
            skipped += "\"${model.displayName}\" (no free space left)"
            return@forEach
        }
        if (separated != clamped) {
            adjusted += "\"${model.displayName}\" nudged to avoid overlap"
        }
        accepted +=
            ResolvedAiPlacement(
                model = model,
                x = separated.x,
                z = separated.z,
                yawDegrees = placement.yawDegrees,
                scale = effectiveScale,
            )
        acceptedBoxes +=
            yawBoxFor(
                Vector3(separated.x, pivotY, separated.z),
                placement.yawDegrees,
                effectiveScale,
                model.center,
                model.halfExtents,
            )
    }
    return ResolvedAiLayout(accepted, skipped, adjusted)
}

/** Asks the AI for a layout: builds the prompt, POSTs it, parses the JSON answer. */
internal suspend fun requestAiLayout(
    userPrompt: String,
    walls: List<PlanWall>,
    openings: List<OpeningDesc>,
    catalog: List<CatalogModel>,
    currentPlacements: List<PlacedSummary>,
    textureCatalog: List<TextureSpec> = emptyList(),
): AiLayout {
    val (system, user) =
        buildArrangementMessages(
            userPrompt,
            walls,
            openings,
            catalog,
            currentPlacements,
            textureCatalog,
        )
    Log.w(TAG, "request: system=${system.length} chars, user=${user.length} chars")
    Log.w(TAG, "request user message: ${user.take(1200)}")
    val content = postChatCompletion(system, user)
    return parseAiLayout(content)
}

private fun wallBounds(walls: List<PlanWall>): PlanBounds {
    val points = walls.flatMap { listOf(it.start, it.end) }
    if (points.isEmpty()) return PlanBounds(-1f, -1f, 1f, 1f)
    return PlanBounds(
        minX = points.minOf { it.x },
        minZ = points.minOf { it.z },
        maxX = points.maxOf { it.x },
        maxZ = points.maxOf { it.z },
    )
}

private suspend fun postChatCompletion(system: String, user: String): String =
    withContext(Dispatchers.IO) {
        val key = BuildConfig.AI_API_KEY
        if (key.isBlank()) {
            throw AiArrangeException("AI key missing — set ai.api.key in local.properties")
        }
        val base = BuildConfig.AI_API_BASE.trim().trimEnd('/')
        val payload =
            JSONObject()
                .put("model", BuildConfig.AI_API_MODEL)
                .put("temperature", 0.4)
                .put("response_format", JSONObject().put("type", "json_object"))
                .put(
                    "messages",
                    JSONArray()
                        .put(JSONObject().put("role", "system").put("content", system))
                        .put(JSONObject().put("role", "user").put("content", user)),
                )

        val connection =
            (URL("$base/chat/completions").openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = CONNECT_TIMEOUT_MS
                readTimeout = READ_TIMEOUT_MS
                doOutput = true
                setRequestProperty("Content-Type", "application/json")
                setRequestProperty("Authorization", "Bearer $key")
            }
        try {
            connection.outputStream.use { it.write(payload.toString().toByteArray(Charsets.UTF_8)) }
            val code = connection.responseCode
            val body =
                (if (code in 200..299) connection.inputStream else connection.errorStream)
                    ?.use { it.readBytes().toString(Charsets.UTF_8) }
                    .orEmpty()
            if (code !in 200..299) {
                Log.w(TAG, "HTTP $code: ${body.take(300)}")
                throw AiArrangeException("AI service returned HTTP $code")
            }
            val content =
                JSONObject(body)
                    .optJSONArray("choices")
                    ?.optJSONObject(0)
                    ?.optJSONObject("message")
                    ?.optString("content")
                    .orEmpty()
            if (content.isBlank()) throw AiArrangeException("AI returned an empty answer")
            Log.w(TAG, "AI answer: ${content.take(300)}")
            content
        } catch (error: AiArrangeException) {
            throw error
        } catch (error: Exception) {
            Log.w(TAG, "request failed", error)
            throw AiArrangeException(error.message ?: "network error")
        } finally {
            connection.disconnect()
        }
    }
