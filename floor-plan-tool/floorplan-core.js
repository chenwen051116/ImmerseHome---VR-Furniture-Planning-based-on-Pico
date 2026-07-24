(function (root, factory) {
    const api = factory();
    if (typeof module === "object" && module.exports) {
        module.exports = api;
    } else {
        root.FloorPlanCore = api;
    }
})(typeof globalThis !== "undefined" ? globalThis : this, function () {
    "use strict";

    const FORMAT_VERSION = 2;
    const EPSILON = 0.0001;

    const DEFAULTS = Object.freeze({
        wallHeight: 2.8,
        wallThickness: 0.16,
        doorWidth: 0.9,
        doorHeight: 2.1,
        doorDepth: 0.05,
        windowWidth: 1.4,
        windowHeight: 1.1,
        windowSill: 0.9,
        windowDepth: 0.025,
        snap: 0.5,
        includeFloor: true,
        includeFixtures: true,
        wallColor: "#e2e5e9",
        floorColor: "#8b929a",
        doorColor: "#8b5a35",
        windowColor: "#69a9d0",
    });

    function clamp(value, min, max) {
        return Math.min(max, Math.max(min, value));
    }

    function round(value, places = 4) {
        const factor = 10 ** places;
        const result = Math.round((Number(value) + Number.EPSILON) * factor) / factor;
        return Object.is(result, -0) ? 0 : result;
    }

    function distance(a, b) {
        return Math.hypot(b.x - a.x, b.y - a.y);
    }

    function wallLength(wall) {
        return distance(wall.start, wall.end);
    }

    function finiteOr(value, fallback) {
        const numeric = Number(value);
        return Number.isFinite(numeric) ? numeric : fallback;
    }

    function colorOr(value, fallback) {
        const color = String(value || "");
        return /^#[0-9a-f]{6}$/i.test(color) ? color : fallback;
    }

    function createDemoPlan() {
        return {
            version: FORMAT_VERSION,
            name: "Demo Room",
            settings: { ...DEFAULTS },
            walls: [
                {
                    id: "wall-1",
                    start: { x: -7.5, y: -4 },
                    end: { x: 7.5, y: -4 },
                    height: 2.8,
                    thickness: 0.16,
                },
                {
                    id: "wall-2",
                    start: { x: 7.5, y: -4 },
                    end: { x: 7.5, y: 4 },
                    height: 2.8,
                    thickness: 0.16,
                },
                {
                    id: "wall-3",
                    start: { x: 7.5, y: 4 },
                    end: { x: -7.5, y: 4 },
                    height: 2.8,
                    thickness: 0.16,
                },
                {
                    id: "wall-4",
                    start: { x: -7.5, y: 4 },
                    end: { x: -7.5, y: -4 },
                    height: 2.8,
                    thickness: 0.16,
                },
            ],
            openings: [
                {
                    id: "door-1",
                    wallId: "wall-1",
                    type: "door",
                    position: 0.5,
                    width: 0.9,
                    height: 2.1,
                    sill: 0,
                },
                {
                    id: "window-1",
                    wallId: "wall-2",
                    type: "window",
                    position: 0.5,
                    width: 1.4,
                    height: 1.1,
                    sill: 0.9,
                },
                {
                    id: "window-2",
                    wallId: "wall-4",
                    type: "window",
                    position: 0.5,
                    width: 1.4,
                    height: 1.1,
                    sill: 0.9,
                },
            ],
        };
    }

    function normalizePlan(input) {
        const plan = input && typeof input === "object" ? input : {};
        const rawSettings = { ...DEFAULTS, ...(plan.settings || {}) };
        const settings = {
            wallHeight: Math.max(
                0.2,
                finiteOr(rawSettings.wallHeight, DEFAULTS.wallHeight)
            ),
            wallThickness: Math.max(
                0.02,
                finiteOr(rawSettings.wallThickness, DEFAULTS.wallThickness)
            ),
            doorWidth: Math.max(
                0.1,
                finiteOr(rawSettings.doorWidth, DEFAULTS.doorWidth)
            ),
            doorHeight: Math.max(
                0.1,
                finiteOr(rawSettings.doorHeight, DEFAULTS.doorHeight)
            ),
            doorDepth: Math.max(
                0.005,
                finiteOr(rawSettings.doorDepth, DEFAULTS.doorDepth)
            ),
            windowWidth: Math.max(
                0.1,
                finiteOr(rawSettings.windowWidth, DEFAULTS.windowWidth)
            ),
            windowHeight: Math.max(
                0.1,
                finiteOr(rawSettings.windowHeight, DEFAULTS.windowHeight)
            ),
            windowSill: Math.max(
                0,
                finiteOr(rawSettings.windowSill, DEFAULTS.windowSill)
            ),
            windowDepth: Math.max(
                0.005,
                finiteOr(rawSettings.windowDepth, DEFAULTS.windowDepth)
            ),
            snap: Math.max(0.01, finiteOr(rawSettings.snap, DEFAULTS.snap)),
            includeFloor: Boolean(rawSettings.includeFloor),
            includeFixtures: Boolean(rawSettings.includeFixtures),
            wallColor: colorOr(rawSettings.wallColor, DEFAULTS.wallColor),
            floorColor: colorOr(rawSettings.floorColor, DEFAULTS.floorColor),
            doorColor: colorOr(rawSettings.doorColor, DEFAULTS.doorColor),
            windowColor: colorOr(rawSettings.windowColor, DEFAULTS.windowColor),
        };
        const walls = Array.isArray(plan.walls)
            ? plan.walls.map((wall, index) => ({
                  id: String(wall.id || `wall-${index + 1}`),
                  start: {
                      x: finiteOr(wall.start?.x, 0),
                      y: finiteOr(wall.start?.y, 0),
                  },
                  end: {
                      x: finiteOr(wall.end?.x, 0),
                      y: finiteOr(wall.end?.y, 0),
                  },
                  height: Math.max(
                      0.2,
                      finiteOr(wall.height, settings.wallHeight)
                  ),
                  thickness: Math.max(
                      0.02,
                      finiteOr(wall.thickness, settings.wallThickness)
                  ),
              }))
            : [];

        const wallIds = new Set(walls.map((wall) => wall.id));
        const openings = Array.isArray(plan.openings)
            ? plan.openings
                  .filter((opening) => wallIds.has(String(opening.wallId)))
                  .map((opening, index) => {
                      const type = opening.type === "window" ? "window" : "door";
                      return {
                          id: String(opening.id || `${type}-${index + 1}`),
                          wallId: String(opening.wallId),
                          type,
                          position: clamp(finiteOr(opening.position, 0), 0, 1),
                          width: Math.max(
                              0.1,
                              finiteOr(
                                  opening.width,
                                  (type === "door"
                                      ? settings.doorWidth
                                      : settings.windowWidth)
                              )
                          ),
                          height: Math.max(
                              0.1,
                              finiteOr(
                                  opening.height,
                                  (type === "door"
                                      ? settings.doorHeight
                                      : settings.windowHeight)
                              )
                          ),
                          depth: Math.max(
                              0.005,
                              finiteOr(
                                  opening.depth,
                                  type === "door"
                                      ? settings.doorDepth
                                      : settings.windowDepth
                              )
                          ),
                          sill:
                              type === "door"
                                  ? 0
                                  : Math.max(
                                        0,
                                        finiteOr(
                                            opening.sill,
                                            settings.windowSill
                                        )
                                    ),
                      };
                  })
            : [];

        return {
            version: FORMAT_VERSION,
            name: String(plan.name || "Floor Plan"),
            settings,
            walls,
            openings,
        };
    }

    function planBounds(planInput) {
        const plan = normalizePlan(planInput);
        if (!plan.walls.length) {
            return {
                minX: -1,
                minY: -1,
                maxX: 1,
                maxY: 1,
                width: 2,
                depth: 2,
                centerX: 0,
                centerY: 0,
            };
        }
        const xs = [];
        const ys = [];
        plan.walls.forEach((wall) => {
            xs.push(wall.start.x, wall.end.x);
            ys.push(wall.start.y, wall.end.y);
        });
        const minX = Math.min(...xs);
        const minY = Math.min(...ys);
        const maxX = Math.max(...xs);
        const maxY = Math.max(...ys);
        return {
            minX,
            minY,
            maxX,
            maxY,
            width: Math.max(EPSILON, maxX - minX),
            depth: Math.max(EPSILON, maxY - minY),
            centerX: (minX + maxX) / 2,
            centerY: (minY + maxY) / 2,
        };
    }

    function transformPlan(planInput, options = {}) {
        const plan = normalizePlan(planInput);
        const bounds = planBounds(plan);
        const scaleX = Math.max(0.001, finiteOr(options.scaleX, 1));
        const scaleZ = Math.max(0.001, finiteOr(options.scaleZ, scaleX));
        const scaleConstruction = Boolean(options.scaleConstruction);
        const constructionScale = Math.sqrt(scaleX * scaleZ);
        const oldLengths = new Map(
            plan.walls.map((wall) => [wall.id, wallLength(wall)])
        );

        plan.walls.forEach((wall) => {
            wall.start.x =
                bounds.centerX + (wall.start.x - bounds.centerX) * scaleX;
            wall.end.x =
                bounds.centerX + (wall.end.x - bounds.centerX) * scaleX;
            wall.start.y =
                bounds.centerY + (wall.start.y - bounds.centerY) * scaleZ;
            wall.end.y =
                bounds.centerY + (wall.end.y - bounds.centerY) * scaleZ;
            if (scaleConstruction) {
                wall.height *= constructionScale;
                wall.thickness *= constructionScale;
            }
        });

        plan.openings.forEach((opening) => {
            const wall = plan.walls.find(
                (candidate) => candidate.id === opening.wallId
            );
            const oldLength = oldLengths.get(opening.wallId) || 0;
            const newLength = wall ? wallLength(wall) : 0;
            if (oldLength > EPSILON && newLength > EPSILON) {
                opening.width *= newLength / oldLength;
            }
            if (scaleConstruction) {
                opening.height *= constructionScale;
                opening.depth *= constructionScale;
                opening.sill *= constructionScale;
            }
        });

        if (scaleConstruction) {
            plan.settings.wallHeight *= constructionScale;
            plan.settings.wallThickness *= constructionScale;
            plan.settings.doorWidth *= constructionScale;
            plan.settings.doorHeight *= constructionScale;
            plan.settings.doorDepth *= constructionScale;
            plan.settings.windowWidth *= constructionScale;
            plan.settings.windowHeight *= constructionScale;
            plan.settings.windowSill *= constructionScale;
            plan.settings.windowDepth *= constructionScale;
        }

        return normalizePlan(plan);
    }

    function resizePlanFootprint(planInput, targetWidth, targetDepth) {
        const plan = normalizePlan(planInput);
        const bounds = planBounds(plan);
        if (!plan.walls.length) {
            return plan;
        }
        const width = Math.max(0.1, finiteOr(targetWidth, bounds.width));
        const depth = Math.max(0.1, finiteOr(targetDepth, bounds.depth));
        return transformPlan(plan, {
            scaleX: width / Math.max(EPSILON, bounds.width),
            scaleZ: depth / Math.max(EPSILON, bounds.depth),
        });
    }

    function scalePlanUniformly(planInput, factor) {
        const safeFactor = Math.max(0.01, finiteOr(factor, 1));
        return transformPlan(planInput, {
            scaleX: safeFactor,
            scaleZ: safeFactor,
            scaleConstruction: true,
        });
    }

    function mergeIntervals(intervals, min, max) {
        const prepared = intervals
            .map(([start, end]) => [
                clamp(Math.min(start, end), min, max),
                clamp(Math.max(start, end), min, max),
            ])
            .filter(([start, end]) => end - start > EPSILON)
            .sort((a, b) => a[0] - b[0]);

        const merged = [];
        prepared.forEach((interval) => {
            const last = merged[merged.length - 1];
            if (!last || interval[0] > last[1] + EPSILON) {
                merged.push([...interval]);
            } else {
                last[1] = Math.max(last[1], interval[1]);
            }
        });
        return merged;
    }

    function complementIntervals(holes, min, max) {
        const result = [];
        let cursor = min;
        holes.forEach(([start, end]) => {
            if (start > cursor + EPSILON) {
                result.push([cursor, start]);
            }
            cursor = Math.max(cursor, end);
        });
        if (cursor < max - EPSILON) {
            result.push([cursor, max]);
        }
        return result;
    }

    function wallOpenings(plan, wall) {
        const length = wallLength(wall);
        return plan.openings
            .filter((opening) => opening.wallId === wall.id)
            .map((opening) => {
                const half = Math.min(opening.width / 2, length / 2);
                const center = clamp(opening.position * length, half, length - half);
                return {
                    ...opening,
                    center,
                    start: clamp(center - half, 0, length),
                    end: clamp(center + half, 0, length),
                };
            })
            .filter((opening) => opening.end - opening.start > EPSILON);
    }

    function buildWallSolids(planInput, wallInput) {
        const plan = normalizePlan(planInput);
        const wall =
            plan.walls.find((candidate) => candidate.id === wallInput.id) || wallInput;
        const length = wallLength(wall);
        if (length <= EPSILON) {
            return [];
        }

        const openings = wallOpenings(plan, wall);
        const breakpoints = [0, length];
        openings.forEach((opening) => breakpoints.push(opening.start, opening.end));
        const uniqueBreakpoints = [...new Set(breakpoints.map((value) => round(value, 6)))].sort(
            (a, b) => a - b
        );

        const solids = [];
        for (let index = 0; index < uniqueBreakpoints.length - 1; index += 1) {
            const start = uniqueBreakpoints[index];
            const end = uniqueBreakpoints[index + 1];
            if (end - start <= EPSILON) {
                continue;
            }
            const midpoint = (start + end) / 2;
            const active = openings.filter(
                (opening) =>
                    midpoint >= opening.start - EPSILON &&
                    midpoint <= opening.end + EPSILON
            );
            const verticalHoles = active.map((opening) => {
                const holeStart = opening.type === "door" ? 0 : opening.sill;
                return [holeStart, holeStart + opening.height];
            });
            const holes = mergeIntervals(verticalHoles, 0, wall.height);
            const verticalSolids = complementIntervals(holes, 0, wall.height);
            verticalSolids.forEach(([bottom, top]) => {
                if (top - bottom > EPSILON) {
                    solids.push({ start, end, bottom, top });
                }
            });
        }
        return solids;
    }

    function hexToRgb(color) {
        const match = /^#?([0-9a-f]{6})$/i.exec(String(color || ""));
        if (!match) {
            return [0.8, 0.8, 0.8];
        }
        const value = Number.parseInt(match[1], 16);
        return [
            ((value >> 16) & 255) / 255,
            ((value >> 8) & 255) / 255,
            (value & 255) / 255,
        ];
    }

    function fmt(value) {
        const safe = Math.abs(value) < 0.0000005 ? 0 : value;
        return Number(safe).toFixed(6).replace(/\.?0+$/, "");
    }

    function materialBlock(color, metallic, roughness) {
        const [r, g, b] = hexToRgb(color);
        return `            def SpatialStruct "material"
            {
                token shaderType = "PbrShader"
                color3f albedo = (${fmt(r)}, ${fmt(g)}, ${fmt(b)})
                float metallic = ${fmt(metallic)}
                float roughness = ${fmt(roughness)}
            }`;
    }

    function cubeBlock({
        name,
        x,
        y,
        z,
        length,
        height,
        depth,
        angle,
        color,
        metallic = 0,
        roughness = 0.75,
    }) {
        const halfAngle = -angle / 2;
        return `    def Xform "${name}"
    {
        float3 xformOp:translate = (${fmt(x)}, ${fmt(y)}, ${fmt(z)})
        quatf xformOp:orient = (${fmt(Math.cos(halfAngle))}, 0, ${fmt(
            Math.sin(halfAngle)
        )}, 0)
        float3 xformOp:scale = (${fmt(length)}, ${fmt(height)}, ${fmt(depth)})
        uniform token[] xformOpOrder = ["xformOp:translate", "xformOp:orient", "xformOp:scale"]

        def SpatialComponent "MeshComponent"
        {
            uniform token info:id = "MeshComponent"
            asset mesh = @builtin://Cube@
${materialBlock(color, metallic, roughness)}
        }
    }`;
    }

    function safeIdentifier(value) {
        const cleaned = String(value || "Item")
            .replace(/[^A-Za-z0-9_]+/g, "_")
            .replace(/^_+|_+$/g, "");
        return cleaned || "Item";
    }

    function generatePicoUsda(planInput) {
        const plan = normalizePlan(planInput);
        const bounds = planBounds(plan);
        const blocks = [];
        const wallColor = plan.settings.wallColor;
        let segmentIndex = 1;

        plan.walls.forEach((wall, wallIndex) => {
            const length = wallLength(wall);
            if (length <= EPSILON) {
                return;
            }
            const dx = (wall.end.x - wall.start.x) / length;
            const dz = (wall.end.y - wall.start.y) / length;
            const angle = Math.atan2(dz, dx);
            buildWallSolids(plan, wall).forEach((solid) => {
                const along = (solid.start + solid.end) / 2;
                blocks.push(
                    cubeBlock({
                        name: `Wall_${wallIndex + 1}_Segment_${segmentIndex}`,
                        x: wall.start.x + dx * along - bounds.centerX,
                        y: (solid.bottom + solid.top) / 2,
                        z: wall.start.y + dz * along - bounds.centerY,
                        length: solid.end - solid.start,
                        height: solid.top - solid.bottom,
                        depth: wall.thickness,
                        angle,
                        color: wallColor,
                        roughness: 0.82,
                    })
                );
                segmentIndex += 1;
            });
        });

        if (plan.settings.includeFixtures) {
            plan.openings.forEach((opening, index) => {
                const wall = plan.walls.find(
                    (candidate) => candidate.id === opening.wallId
                );
                if (!wall) {
                    return;
                }
                const length = wallLength(wall);
                if (length <= EPSILON) {
                    return;
                }
                const dx = (wall.end.x - wall.start.x) / length;
                const dz = (wall.end.y - wall.start.y) / length;
                const angle = Math.atan2(dz, dx);
                const halfWidth = Math.min(opening.width / 2, length / 2);
                const along = clamp(
                    opening.position * length,
                    halfWidth,
                    length - halfWidth
                );
                const fixtureHeight = Math.min(
                    opening.height,
                    Math.max(0.1, wall.height - opening.sill)
                );
                const fixtureWidth = Math.max(
                    0.05,
                    Math.min(opening.width, length) - 0.05
                );
                const centerX =
                    wall.start.x + dx * along - bounds.centerX;
                const centerZ =
                    wall.start.y + dz * along - bounds.centerY;
                const halfFixtureWidth = fixtureWidth / 2;
                const isDoor = opening.type === "door";
                blocks.push(
                    cubeBlock({
                        name: `${isDoor ? "Door" : "Window"}_${
                            index + 1
                        }_${safeIdentifier(opening.id)}`,
                        x: isDoor
                            ? centerX -
                              dx * halfFixtureWidth -
                              dz * halfFixtureWidth
                            : centerX,
                        y: opening.sill + fixtureHeight / 2,
                        z: isDoor
                            ? centerZ -
                              dz * halfFixtureWidth +
                              dx * halfFixtureWidth
                            : centerZ,
                        length: fixtureWidth,
                        height: fixtureHeight,
                        depth: opening.depth,
                        angle: isDoor ? angle + Math.PI / 2 : angle,
                        color: isDoor
                            ? plan.settings.doorColor
                            : plan.settings.windowColor,
                        metallic: isDoor ? 0 : 0.15,
                        roughness: isDoor ? 0.68 : 0.22,
                    })
                );
            });
        }

        if (plan.settings.includeFloor && plan.walls.length) {
            blocks.unshift(
                cubeBlock({
                    name: "Floor",
                    x: 0,
                    y: -0.03,
                    z: 0,
                    length: bounds.width + 0.3,
                    height: 0.06,
                    depth: bounds.depth + 0.3,
                    angle: 0,
                    color: plan.settings.floorColor,
                    roughness: 0.9,
                })
            );
        }

        const metadataName = plan.name.replace(/["\\\r\n]/g, " ").trim();
        return `#usda 1.0
(
    customLayerData = {
        string Generator = "PICO Floor Plan Tool"
        string GeneratorVersion = "${FORMAT_VERSION}"
        string PlanName = "${metadataName}"
    }
    defaultPrim = "Root"
    metersPerUnit = 1
    upAxis = "Y"
)

def Xform "Root"
{
${blocks.join("\n\n")}
}
`;
    }

    function buildPreviewScene(planInput) {
        const plan = normalizePlan(planInput);
        const bounds = planBounds(plan);
        const boxes = [];
        const colliders = [];
        const ceilingHeight =
            plan.walls.reduce(
                (height, wall) => Math.max(height, wall.height),
                plan.settings.wallHeight
            ) || 2.8;

        function addBox(box) {
            const prepared = {
                kind: box.kind || "solid",
                x: finiteOr(box.x, 0),
                y: finiteOr(box.y, 0),
                z: finiteOr(box.z, 0),
                width: Math.max(0.001, finiteOr(box.width, 1)),
                height: Math.max(0.001, finiteOr(box.height, 1)),
                depth: Math.max(0.001, finiteOr(box.depth, 1)),
                yaw: finiteOr(box.yaw, 0),
                color: colorOr(box.color, "#cccccc"),
                alpha: clamp(finiteOr(box.alpha, 1), 0.02, 1),
                collidable: Boolean(box.collidable),
            };
            boxes.push(prepared);
            if (prepared.collidable) {
                colliders.push(prepared);
            }
        }

        plan.walls.forEach((wall) => {
            const length = wallLength(wall);
            if (length <= EPSILON) {
                return;
            }
            const dx = (wall.end.x - wall.start.x) / length;
            const dz = (wall.end.y - wall.start.y) / length;
            const angle = Math.atan2(dz, dx);
            buildWallSolids(plan, wall).forEach((solid) => {
                const along = (solid.start + solid.end) / 2;
                addBox({
                    kind: "wall",
                    x: wall.start.x + dx * along - bounds.centerX,
                    y: (solid.bottom + solid.top) / 2,
                    z: wall.start.y + dz * along - bounds.centerY,
                    width: solid.end - solid.start,
                    height: solid.top - solid.bottom,
                    depth: wall.thickness,
                    yaw: -angle,
                    color: plan.settings.wallColor,
                    collidable: solid.bottom < 1.9 && solid.top > 0.15,
                });
            });
        });

        if (plan.settings.includeFixtures) {
            plan.openings.forEach((opening) => {
                const wall = plan.walls.find(
                    (candidate) => candidate.id === opening.wallId
                );
                if (!wall) {
                    return;
                }
                const length = wallLength(wall);
                if (length <= EPSILON) {
                    return;
                }
                const dx = (wall.end.x - wall.start.x) / length;
                const dz = (wall.end.y - wall.start.y) / length;
                const angle = Math.atan2(dz, dx);
                const halfWidth = Math.min(opening.width / 2, length / 2);
                const along = clamp(
                    opening.position * length,
                    halfWidth,
                    length - halfWidth
                );
                const fixtureHeight = Math.min(
                    opening.height,
                    Math.max(0.1, wall.height - opening.sill)
                );
                const fixtureWidth = Math.max(
                    0.05,
                    Math.min(opening.width, length) - 0.05
                );
                const centerX =
                    wall.start.x + dx * along - bounds.centerX;
                const centerZ =
                    wall.start.y + dz * along - bounds.centerY;

                if (opening.type === "door") {
                    // Show the panel opened 90 degrees around its left hinge. This keeps
                    // the doorway usable while making the visible panel a real collider.
                    const halfWidth = fixtureWidth / 2;
                    const hingeX = centerX - dx * halfWidth;
                    const hingeZ = centerZ - dz * halfWidth;
                    const inwardNormalX = -dz;
                    const inwardNormalZ = dx;
                    addBox({
                        kind: "door",
                        x: hingeX + inwardNormalX * halfWidth,
                        y: opening.sill + fixtureHeight / 2,
                        z: hingeZ + inwardNormalZ * halfWidth,
                        width: fixtureWidth,
                        height: fixtureHeight,
                        depth: opening.depth,
                        yaw: -angle - Math.PI / 2,
                        color: plan.settings.doorColor,
                        collidable: true,
                    });
                    return;
                }

                addBox({
                    kind: "window",
                    x: centerX,
                    y: opening.sill + fixtureHeight / 2,
                    z: centerZ,
                    width: fixtureWidth,
                    height: fixtureHeight,
                    depth: opening.depth,
                    yaw: -angle,
                    color: plan.settings.windowColor,
                    alpha: 0.3,
                    collidable: true,
                });
            });
        }

        if (plan.walls.length) {
            addBox({
                kind: "ground",
                x: 0,
                y: -0.11,
                z: 0,
                width: Math.max(80, bounds.width + 20),
                height: 0.1,
                depth: Math.max(80, bounds.depth + 20),
                yaw: 0,
                color: "#252b32",
            });
            addBox({
                kind: "floor",
                x: 0,
                y: -0.03,
                z: 0,
                width: bounds.width + 0.3,
                height: 0.06,
                depth: bounds.depth + 0.3,
                yaw: 0,
                color: plan.settings.floorColor,
            });
            addBox({
                kind: "ceiling",
                x: 0,
                y: ceilingHeight + 0.04,
                z: 0,
                width: bounds.width + 0.3,
                height: 0.08,
                depth: bounds.depth + 0.3,
                yaw: 0,
                color: "#f0f2f4",
            });
        }

        function pointHasClearance(x, z, clearance = 0.35) {
            return !colliders.some((box) => {
                const offsetX = x - box.x;
                const offsetZ = z - box.z;
                const cosine = Math.cos(box.yaw);
                const sine = Math.sin(box.yaw);
                const localX = cosine * offsetX - sine * offsetZ;
                const localZ = sine * offsetX + cosine * offsetZ;
                return (
                    Math.abs(localX) <= box.width / 2 + clearance &&
                    Math.abs(localZ) <= box.depth / 2 + clearance
                );
            });
        }

        function pointInsideBoundary(x, z) {
            const planX = x + bounds.centerX;
            const planZ = z + bounds.centerY;
            let crossings = 0;
            plan.walls.forEach((wall) => {
                const startAbove = wall.start.y > planZ;
                const endAbove = wall.end.y > planZ;
                if (startAbove === endAbove) {
                    return;
                }
                const intersectionX =
                    wall.start.x +
                    ((planZ - wall.start.y) *
                        (wall.end.x - wall.start.x)) /
                        (wall.end.y - wall.start.y);
                if (planX < intersectionX) {
                    crossings += 1;
                }
            });
            return crossings % 2 === 1;
        }

        function chooseSpawn() {
            const preferred = {
                x: 0,
                z: Math.max(0, bounds.depth / 2 - 1),
            };
            const candidates = [preferred, { x: 0, z: 0 }];
            const columns = 20;
            const rows = 20;
            const insetX = Math.min(0.6, bounds.width * 0.2);
            const insetZ = Math.min(0.6, bounds.depth * 0.2);
            const minX = -bounds.width / 2 + insetX;
            const maxX = bounds.width / 2 - insetX;
            const minZ = -bounds.depth / 2 + insetZ;
            const maxZ = bounds.depth / 2 - insetZ;

            for (let row = 0; row <= rows; row += 1) {
                const z =
                    minZ + ((maxZ - minZ) * row) / Math.max(1, rows);
                for (let column = 0; column <= columns; column += 1) {
                    const x =
                        minX +
                        ((maxX - minX) * column) / Math.max(1, columns);
                    candidates.push({ x, z });
                }
            }

            candidates.sort(
                (a, b) =>
                    Math.hypot(a.x - preferred.x, a.z - preferred.z) -
                    Math.hypot(b.x - preferred.x, b.z - preferred.z)
            );

            return (
                candidates.find(
                    (candidate) =>
                        pointInsideBoundary(candidate.x, candidate.z) &&
                        pointHasClearance(candidate.x, candidate.z)
                ) ||
                candidates.find((candidate) =>
                    pointHasClearance(candidate.x, candidate.z)
                ) ||
                preferred
            );
        }

        const spawnPoint = chooseSpawn();
        return {
            plan,
            bounds,
            boxes,
            colliders,
            ceilingHeight,
            spawn: {
                x: spawnPoint.x,
                y: Math.min(1.65, Math.max(0.8, ceilingHeight - 0.35)),
                z: spawnPoint.z,
                yaw: 0,
                pitch: 0,
            },
        };
    }

    function validatePlan(planInput) {
        const plan = normalizePlan(planInput);
        const warnings = [];
        if (!plan.walls.length) {
            warnings.push("Add at least one wall before exporting.");
        }
        plan.walls.forEach((wall, index) => {
            const length = wallLength(wall);
            if (length < 0.2) {
                warnings.push(`Wall ${index + 1} is shorter than 0.2 m.`);
            }
            wallOpenings(plan, wall).forEach((opening) => {
                if (opening.width > length + EPSILON) {
                    warnings.push(
                        `${opening.type} ${opening.id} is wider than its wall.`
                    );
                }
                if (opening.sill + opening.height > wall.height + EPSILON) {
                    warnings.push(
                        `${opening.type} ${opening.id} is taller than its wall space.`
                    );
                }
            });
        });
        return warnings;
    }

    return {
        FORMAT_VERSION,
        DEFAULTS,
        clamp,
        round,
        distance,
        wallLength,
        createDemoPlan,
        normalizePlan,
        planBounds,
        transformPlan,
        resizePlanFootprint,
        scalePlanUniformly,
        wallOpenings,
        buildWallSolids,
        buildPreviewScene,
        generatePicoUsda,
        validatePlan,
    };
});
