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
private const val READ_TIMEOUT_MS = 300_000

/** Read timeouts are usually relay-side congestion — one retry is cheap and often succeeds. */
private const val MAX_HTTP_ATTEMPTS = 2

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
    /** Plan-mode suggestions (analysis without placement). Empty in normal mode. */
    val suggestions: List<String> = emptyList(),
    /**
     * Self-evaluation signal used in ITERATE mode: true when the AI declares the current layout
     * is satisfactory and no further iteration is needed. Always false in non-iterate runs.
     */
    val satisfied: Boolean = false,
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
 * Distills the AdventureX fengshui/国学 JSON config into a concise pre-prompt compatible with
 * the app's output schema. The raw config defines its own (conflicting) system_prompt,
 * input_schema and output_schema (~1300 lines) that confuse the AI into returning an analysis
 * object instead of the {placements, notes, textures} JSON the app expects. This function
 * extracts only the cultural design rules and core concepts, and wraps them with an explicit
 * instruction to keep the app's output schema unchanged.
 */
internal fun distillFengshuiPrompt(rawJson: String): String {
    return runCatching {
        val root = JSONObject(rawJson)
        val sb = StringBuilder()
        sb.append("FENGSHUI / CULTURAL DESIGN GUIDELINES (apply when choosing placements; ")
        sb.append("do NOT change the output schema — still return {placements, notes, textures} only):\n\n")

        // Core concepts from the FORM_SCHOOL_GENERAL source.
        sb.append("Core concepts:\n")
        val sources = root.optJSONArray("reference_sources")
        if (sources != null) {
            for (i in 0 until sources.length()) {
                val src = sources.optJSONObject(i)
                if (src?.optString("source_id") == "FORM_SCHOOL_GENERAL") {
                    val concepts = src.optJSONArray("core_concepts")
                    if (concepts != null) {
                        for (c in 0 until concepts.length()) {
                            val concept = concepts.optJSONObject(c)
                            val name = concept?.optString("concept", "") ?: ""
                            val interp = concept?.optString("project_interpretation", "") ?: ""
                            if (name.isNotEmpty()) {
                                sb.append("- $name: $interp\n")
                            }
                        }
                    }
                }
            }
        }
        sb.append("\n")

        // Key rules — extract id, name, when it applies, and the recommended actions.
        sb.append("Placement rules (respect when feasible; safety and function take priority):\n")
        val rules = root.optJSONArray("rule_library")
        if (rules != null) {
            for (i in 0 until rules.length()) {
                val rule = rules.optJSONObject(i) ?: continue
                val id = rule.optString("rule_id", "")
                val name = rule.optString("name", "")
                val applies = rule.optString("applies_when", "")
                val actions = rule.optJSONArray("recommended_actions")
                val actionStr = if (actions != null && actions.length() > 0) {
                    (0 until actions.length()).joinToString("; ") { actions.opt(it).toString() }
                } else ""
                sb.append("- [$id] $name: when $applies")
                if (actionStr.isNotEmpty()) sb.append(" → $actionStr")
                sb.append("\n")
            }
        }
        sb.append("\n")

        // Conflict resolution priority — makes clear fengshui is below safety/function.
        sb.append("Priority order (highest first): safety & building codes; structure/fireplumbing; ")
        sb.append("function/access/light/ventilation; user's explicit request; fengshui preferences; ")
        sb.append("style/aesthetics. Fengshui must NEVER override safety, function or the user's request.\n")
        sb.toString()
    }.getOrElse {
        // If JSON parsing fails, return a minimal hardcoded summary so the feature still works.
        "FENGSHUI GUIDELINES: sofa/seat should have solid backing (有靠); bed headboard against " +
            "solid wall not window; bed not directly facing door; keep entrance buffered; don't " +
            "block windows with tall furniture; keep walkways >= 0.9m. Still output " +
            "{placements, notes, textures} only."
    }
}

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
    zoneNotes: String? = null,
    prePrompt: String? = null,
    planMode: Boolean = false,
    iterate: Boolean = false,
    iteration: Int = 0,
    maxIterations: Int = 3,
    previousNotes: String? = null,
    rooms: List<DetectedRoom> = emptyList(),
): Pair<String, String> {
    val system =
        buildString {
            // Optional pre-prompt (e.g. the AdventureX fengshui/国学 config) prepended
            // verbatim so the model weighs it as additional context before the standard
            // interior-design instructions. Wrapped with clear delimiters.
            if (!prePrompt.isNullOrBlank()) {
                append("=== ADVANCED CONTEXT (pre-prompt) ===\n")
                append(prePrompt)
                append("\n=== END ADVANCED CONTEXT ===\n\n")
            }
            if (planMode) {
                append(
                    "You are an interior-design consultant inside a VR room planner. The user " +
                        "has enabled PLAN MODE: you must NOT produce any placement " +
                        "instructions. Instead, analyze the room and the user's request, then " +
                        "provide commentary, suggestions, and design rationale as a single JSON " +
                        "object and nothing else — no prose, no markdown fences.\n\n"
                )
            } else if (iterate) {
                append(
                    "You are an interior-design engine inside a VR room planner running in " +
                        "ITERATE mode. The room already contains furniture (described in the " +
                        "USER message under \"furniture\"). You must SELF-EVALUATE the current " +
                        "arrangement against the user's request and the spatial rules, then " +
                        "either confirm it is satisfactory or return a FULLY REVISED layout " +
                        "that improves on it. The revised layout replaces the current one " +
                        "entirely. Answer with a single JSON object and nothing else — no " +
                        "prose, no markdown fences.\n\n"
                )
            } else {
                append(
                    "You are an interior-design engine inside a VR room planner. You receive a " +
                        "structured JSON description of a real room (walls, doors, windows, " +
                        "existing furniture) plus a furniture library, and you answer with " +
                        "placement instructions as a single JSON object and nothing else — no " +
                        "prose, no markdown fences.\n\n"
                )
            }
            append("SPATIAL VOCABULARY (exact meanings):\n")
            append("- \"next to/beside\": 0.05–0.3 m gap between boxes; never overlap.\n")
            append("- \"against/along the wall\": back within 0.1 m of the wall line, parallel to it.\n")
            append("- \"around X\": copies on each side of X, each FACING X's center (yaw toward X).\n")
            append("- \"facing X\"/\"opposite X\": yaw so the model's front (+Z) points at X.\n")
            append("- \"corner\": touching both walls, diagonal (yaw 45/135/225/315).\n")
            append("- Clearances: walkways >= 0.6 m; >= 1.0 m free in front of doors; >= 0.5 m between furniture.\n\n")
            append("ROOM SEMANTICS:\n")
            append("- A room does NOT need to be fully closed — an open passage (a gap in a divider, even 2-3 m wide) still splits the plan into two rooms; the gap is just a doorless doorway.\n")
            append("- Typical functions: bedroom (bed, nightstands, wardrobe), bathroom (toilet, sink, tub), living room (sofa, coffee table, chairs, screen), dining (table with chairs AROUND it), office (desk + chair facing it). Infer each zone's function from the user's request.\n\n")
            append("MULTI-ROOM PLANS (read carefully):\n")
            append("- The USER message has a \"rooms\" array when the plan is segmented. Each entry: id, bounds (X/Z extent), centroid (center), areaSqm, walls (bounding wall ids — cross-ref with the \"walls\"/\"openings\" arrays to find each room's doors/windows).\n")
            append("- When \"rooms\" has 2+ entries, DO NOT use the global bounds/centroid as the placement target — that centroid sits on the divider wall. For each piece: pick the room whose function matches (room with the main entrance + big window = living room; smaller quieter room = bedroom), anchor at THAT room's centroid, and keep coords inside its bounds with >= 0.3 m wall clearance. Keep furniture out of the open passage between rooms.\n")
            append("- Distribute pieces across the relevant rooms when the request spans functions; never pile everything in one zone. When \"rooms\" has 0–1 entries, place using the global bounds/centroid as before.\n\n")
            append("FURNITURE DATA:\n")
            append("- The LIBRARY list is COMPLETE — only use models named there; sizes are app-measured and accurate. Same model may appear in several placements (\"4 chairs\" = 4 entries).\n")
            append("- A model may include DETAILS (asset schema_version 1). Meaningful fields: identity.name (display name, may be localized); classification.category + room_types (respect room_types when the plan has zones); appearance.colors/materials; placement (support_surface, against_wall, clearances); style_assessment.scores (0..1 per style — nordic, wabi_sabi, ikea_functional, chinese variants, industrial; prefer high scores for style requests).\n")
            append("- Null/absent fields are UNKNOWN — never invent them. No DETAILS → infer category/color/style from name and dimensions.\n\n")
            append("STYLE REQUESTS (\"modern\", \"cozy\", …): modern = parallel to walls, aligned axes, symmetry, negative space; cozy = tighter groupings angled toward a focal point. Explain your interpretation in \"notes\".\n\n")
            append("COMPLETENESS — be generous, not timid:\n")
            append("- FULLY FURNISH the room for its function. A mostly-empty room is a failed layout. Aim for 8–14 pieces in a living room (sofa + chairs + coffee table + side tables + TV stand + rug + lamp + plant), 5–8 in a bedroom (bed + 2 nightstands + wardrobe + dresser + desk or chair), 4–6 in a dining area (table + 4–6 chairs + sideboard).\n")
            append("- Use MULTIPLES of the same model when the request implies it: \"4 chairs around the table\" = 4 separate entries; \"pair of nightstands\" = 2 entries. Do not stop at one when the room calls for a set.\n")
            append("- Add accent pieces even when not explicitly requested: a rug anchors a seating group, a lamp fills a dark corner, a plant softens an empty wall, side tables flank a sofa. These make the room feel finished.\n")
            append("- Place furniture FIRST, then verify clearances. If a piece slightly violates a clearance, nudge it rather than dropping it — dropping items leaves the room bare. The app's resolver will clamp positions to the footprint and resolve overlaps.\n")
            append("- NEVER return fewer than 5 placements unless the room is truly tiny (< 8 m²) or the user explicitly asked for a single item. When in doubt, add more.\n\n")
            if (planMode) {
                append(
                    "OUTPUT SCHEMA (strict, PLAN MODE): {\"placements\": [], \"notes\": string, " +
                        "\"suggestions\": [string]}\n" +
                        "In PLAN MODE the \"placements\" array MUST always be empty. Put all " +
                        "analysis, commentary, pros/cons, and design recommendations in " +
                        "\"notes\" (a single string, may use newlines) and list each concrete " +
                        "suggestion as an item in \"suggestions\". Do NOT output \"textures\".\n" +
                        "Example: {\"placements\": [], \"notes\": \"The room is 4x6 m with one " +
                        "window. A cozy layout would place the sofa against the long wall...\", " +
                        "\"suggestions\": [\"Sofa against the south wall for better backlight\", " +
                        "\"Add a rug to anchor the seating zone\"]}"
                )
            } else if (iterate) {
                append(
                    "OUTPUT SCHEMA (strict, ITERATE): {\"satisfied\": boolean, \"placements\": " +
                        "[{\"model\": string, \"x\": number, \"z\": number, \"yaw\": number, " +
                        "\"scale\": number}], \"notes\": string, optional \"textures\": " +
                        "{\"wall|floor|ceiling|door|window\": textureName}}\n" +
                        "SELF-EVALUATION RULES:\n" +
                        "- Set \"satisfied\": true ONLY when the current furniture array already " +
                        "fully satisfies the user's request AND obeys every spatial rule " +
                        "(no overlaps, clear walkways, doors unobstructed, fengshui/style " +
                        "intent met). In that case return \"placements\": [] (the existing " +
                        "furniture stays as-is) and briefly justify in \"notes\".\n" +
                        "- Otherwise set \"satisfied\": false and return a COMPLETE, improved " +
                        "placements array (the existing furniture is fully replaced by it). " +
                        "Use \"notes\" to explain what was wrong and what you changed.\n" +
                        "- Be honest and critical: do not declare satisfaction on the first " +
                        "pass if any obvious improvement exists (e.g. tighter grouping, " +
                        "better focal-point facing, clearer walkway, missing key piece).\n" +
                        "Example: {\"satisfied\": false, \"placements\": [...], " +
                        "\"notes\": \"Sofa was blocking the doorway; rotated and shifted to " +
                        "the long wall.\"}"
                )
            } else {
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
            if (iterate) {
                append(
                    "\n\nITERATE PROTOCOL: You are part of a self-improvement loop (max " +
                        "$maxIterations iterations). The USER message describes the room AS IT " +
                        "CURRENTLY IS, including the furniture already placed. Treat that " +
                        "furniture as the result of your previous turn. Evaluate it critically: " +
                        "check overlaps, door/window clearance, walkway widths, focal-point " +
                        "orientation, style/coherence with the request, and (if Advanced " +
                        "Context is on) the fengshui/国学 rules. If you can improve it, return " +
                        "satisfied=false with a full revised layout. Only when no further " +
                        "improvement is warranted, return satisfied=true with placements=[]."
                )
            }
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
    // Per-room segmentation: when the plan has multiple zones, give the AI each room's
    // bounds, centroid, area and bounding walls so it can place furniture in the correct
    // zone instead of clustering everything at the global center (which often sits on the
    // divider wall between zones). The AI can cross-reference each room's wall ids with
    // the "walls" and "openings" arrays above to see where the room's doors/windows are.
    if (rooms.isNotEmpty()) {
        user.append("\"rooms\":[")
        rooms.forEachIndexed { index, room ->
            if (index > 0) user.append(",")
            user.append(
                String.format(
                    Locale.US,
                    "{\"id\":%d,\"bounds\":{\"x\":[%.2f,%.2f],\"z\":[%.2f,%.2f]},\"centroid\":[%.2f,%.2f],\"areaSqm\":%.1f,\"walls\":[%s]}",
                    room.id,
                    room.bounds.minX,
                    room.bounds.maxX,
                    room.bounds.minZ,
                    room.bounds.maxZ,
                    room.centroid.x,
                    room.centroid.z,
                    room.areaSqm,
                    room.boundingWallIds.joinToString(","),
                )
            )
        }
        user.append("],")
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
    if (!zoneNotes.isNullOrBlank()) {
        user.append("ZONES: ").append(zoneNotes.trim()).append("\n")
    }
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
        "Rules: only LIBRARY models; on the floor (no stacking); never overlap; keep doors/walkways clear. Respond with the placements JSON only."
    )
    if (rooms.size >= 2) {
        user.append(
            "\nMULTI-ROOM: ${rooms.size} rooms detected. Pick the room per piece by function, anchor at THAT room's centroid (not the global center, which sits on the divider), keep inside the room's bounds, and keep out of the open passage."
        )
    }
    if (iterate && iteration > 0) {
        user.append("\n\n=== ITERATION CONTEXT ===\n")
        user.append("This is iteration $iteration of $maxIterations in the self-improvement loop.\n")
        user.append(
            "The \"furniture\" array above is the layout YOU produced on the previous turn, " +
                "now actually placed in the room. Evaluate it against the request and rules; " +
                "return satisfied=true (with placements=[]) if it is already optimal, or " +
                "satisfied=false with a full improved placements array otherwise.\n"
        )
        if (!previousNotes.isNullOrBlank()) {
            user.append("Your previous notes: ").append(previousNotes.trim()).append("\n")
        }
        user.append("=== END ITERATION CONTEXT ===")
    } else if (iterate) {
        user.append("\n\n=== ITERATION CONTEXT ===\n")
        user.append(
            "Iterate mode is ON: produce your best initial layout now. After you answer, " +
                "the system will place it, then re-invoke you to evaluate and improve it " +
                "(up to $maxIterations times). Treat this turn as iteration 0 (the seed).\n" +
                "=== END ITERATION CONTEXT ==="
        )
    }
    return system to user.toString()
}

/**
 * Parses the AI's answer. Entries without a model name or numeric x/z are skipped; yaw defaults
 * to 0 and scale to 1 (clamped to [MIN_AI_SCALE]..[MAX_AI_SCALE]). Throws [AiArrangeException]
 * when the content is not valid JSON. When the JSON is valid but has no recognizable placements
 * array, returns an empty layout with whatever notes the AI provided (so the user sees the AI's
 * explanation instead of an opaque error).
 */
internal fun parseAiLayout(content: String): AiLayout {
    Log.w(TAG, "parseAiLayout: raw response (first 800 chars): ${content.take(800)}")
    // Strip markdown fences if the AI wrapped its JSON in ```json ... ```.
    val cleaned = content.trim().let { s ->
        if (s.startsWith("```")) {
            s.removePrefix("```json").removePrefix("```")
                .removeSuffix("```")
                .trim()
        } else {
            s
        }
    }
    val root =
        runCatching { JSONObject(cleaned) }.getOrElse {
            throw AiArrangeException("AI did not return valid JSON: ${content.take(200)}")
        }
    // Try the standard key, then common alternatives the AI might use.
    val array =
        root.optJSONArray("placements")
            ?: root.optJSONArray("placement")
            ?: root.optJSONArray("items")
            ?: root.optJSONArray("furniture")
            ?: root.optJSONArray("layout")
    val placements = mutableListOf<AiPlacement>()
    if (array != null) {
        for (index in 0 until array.length()) {
            val entry = array.optJSONObject(index) ?: continue
            val model = entry.optString("model", "").trim()
            if (model.isEmpty()) continue
            // Accept both {"x":1,"z":2} and {"position":[x,y,z]} formats.
            var x = entry.optDouble("x", Double.NaN)
            var z = entry.optDouble("z", Double.NaN)
            if (x.isNaN() || z.isNaN()) {
                val pos = entry.optJSONArray("position")
                if (pos != null && pos.length() >= 3) {
                    x = pos.optDouble(0, Double.NaN)
                    z = pos.optDouble(2, Double.NaN)
                }
            }
            if (x.isNaN() || z.isNaN()) continue
            val yaw = entry.optDouble("yaw", 0.0).toFloat()
            val scale =
                entry.optDouble("scale", 1.0).toFloat().coerceIn(MIN_AI_SCALE, MAX_AI_SCALE)
            placements += AiPlacement(model, x.toFloat(), z.toFloat(), yaw, scale)
        }
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
    val suggestions = mutableListOf<String>()
    root.optJSONArray("suggestions")?.let { arr ->
        for (index in 0 until arr.length()) {
            val item = arr.optString(index, "").trim()
            if (item.isNotEmpty()) suggestions += item
        }
    }
    val satisfied = root.optBoolean("satisfied", false)
    if (placements.isEmpty() && notes == null && suggestions.isEmpty()) {
        throw AiArrangeException("AI response had no placements, notes, or suggestions: ${content.take(300)}")
    }
    return AiLayout(placements, notes, textures, suggestions, satisfied)
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
    rooms: List<DetectedRoom> = emptyList(),
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
        current = clampToFootprint(walls, stepped, margin, rooms)
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
    rooms: List<DetectedRoom> = emptyList(),
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
        // Room-aware clamp: when the plan has multiple zones, pull the point toward the
        // nearest room's centroid (not the global centroid) so furniture stays in the zone
        // the AI intended instead of being dragged through a divider wall.
        val clamped = clampToFootprint(walls, PlanPoint(placement.x, placement.z), margin, rooms)
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
                rooms = rooms,
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
    zoneNotes: String? = null,
    prePrompt: String? = null,
    planMode: Boolean = false,
    iterate: Boolean = false,
    iteration: Int = 0,
    maxIterations: Int = 3,
    previousNotes: String? = null,
    rooms: List<DetectedRoom> = emptyList(),
    aiModel: String = "",
): AiLayout {
    val (system, user) =
        buildArrangementMessages(
            userPrompt,
            walls,
            openings,
            catalog,
            currentPlacements,
            textureCatalog,
            zoneNotes,
            prePrompt,
            planMode,
            iterate,
            iteration,
            maxIterations,
            previousNotes,
            rooms,
        )
    Log.w(TAG, "request: system=${system.length} chars, user=${user.length} chars")
    Log.w(TAG, "request user message: ${user.take(1200)}")
    if (rooms.isNotEmpty()) {
        Log.w(TAG, "multi-room plan: ${rooms.size} rooms detected, included in prompt")
    }
    val effectiveModel = aiModel.ifBlank { BuildConfig.AI_API_MODEL }
    Log.w(TAG, "request model: $effectiveModel")
    val content = postChatCompletion(system, user, effectiveModel)
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

private suspend fun postChatCompletion(system: String, user: String, model: String): String {
    var lastTimeout: java.net.SocketTimeoutException? = null
    repeat(MAX_HTTP_ATTEMPTS) { attempt ->
        try {
            return postChatCompletionOnce(system, user, model)
        } catch (error: java.net.SocketTimeoutException) {
            lastTimeout = error
            Log.w(TAG, "request timed out (attempt ${attempt + 1}/$MAX_HTTP_ATTEMPTS)")
        }
    }
    throw AiArrangeException("AI service timed out twice — the relay is congested, try again")
}

private suspend fun postChatCompletionOnce(system: String, user: String, model: String): String =
    withContext(Dispatchers.IO) {
        val key = BuildConfig.AI_API_KEY
        if (key.isBlank()) {
            throw AiArrangeException("AI key missing — set ai.api.key in local.properties")
        }
        val base = BuildConfig.AI_API_BASE.trim().trimEnd('/')
        val payload =
            JSONObject()
                .put("model", model)
                // No "temperature": some upstreams (e.g. gpt-5 reasoning) 400 on any
                // non-default value, and JSON mode carries the determinism we need.
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
        } catch (error: java.net.SocketTimeoutException) {
            // Let the outer retry loop decide; don't wrap it as a generic network error.
            throw error
        } catch (error: Exception) {
            Log.w(TAG, "request failed", error)
            throw AiArrangeException(error.message ?: "network error")
        } finally {
            connection.disconnect()
        }
    }
