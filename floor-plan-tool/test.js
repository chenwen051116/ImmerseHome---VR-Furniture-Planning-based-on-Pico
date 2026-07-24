"use strict";

const assert = require("node:assert/strict");
const Core = require("./floorplan-core.js");

const plan = Core.createDemoPlan();
const warnings = Core.validatePlan(plan);
assert.deepEqual(warnings, []);

const frontWall = plan.walls[0];
const solids = Core.buildWallSolids(plan, frontWall);
const frontDoor = plan.openings.find((opening) => opening.type === "door");
const doorCenter = frontDoor.position * Core.wallLength(frontWall);
const doorStart = doorCenter - frontDoor.width / 2;
const doorEnd = doorCenter + frontDoor.width / 2;
assert.ok(solids.length >= 3, "door must split the wall into side and header solids");
assert.ok(
    solids.some((solid) => solid.bottom >= 2.09),
    "door opening must leave a wall header"
);
assert.ok(
    !solids.some(
        (solid) =>
            solid.start < doorEnd &&
            solid.end > doorStart &&
            solid.bottom < 2.09 &&
            solid.top > 0.01
    ),
    "door interval must remain open below the header"
);

const windowWall = plan.walls[1];
const windowSolids = Core.buildWallSolids(plan, windowWall);
const sideWindow = plan.openings.find(
    (opening) => opening.type === "window" && opening.wallId === windowWall.id
);
const windowCenter = sideWindow.position * Core.wallLength(windowWall);
assert.ok(
    windowSolids.some(
        (solid) =>
            solid.start < windowCenter &&
            solid.end > windowCenter &&
            solid.bottom === 0 &&
            Math.abs(solid.top - 0.9) < 0.001
    ),
    "window opening must keep the wall below its sill"
);
assert.ok(
    windowSolids.some(
        (solid) =>
            solid.start < windowCenter &&
            solid.end > windowCenter &&
            Math.abs(solid.bottom - 2) < 0.001 &&
            Math.abs(solid.top - 2.8) < 0.001
    ),
    "window opening must keep the wall above its lintel"
);
assert.ok(
    !windowSolids.some(
        (solid) =>
            solid.start < windowCenter &&
            solid.end > windowCenter &&
            solid.bottom < 1.99 &&
            solid.top > 0.91
    ),
    "window interval must remain open between sill and lintel"
);

const normalized = Core.normalizePlan({
    settings: {
        wallHeight: -3,
        doorWidth: "not-a-number",
        snap: 0,
        wallColor: "invalid",
    },
    walls: [
        {
            id: "wall-1",
            start: { x: "bad", y: 0 },
            end: { x: 2, y: 0 },
        },
    ],
    openings: [
        {
            id: "window-1",
            wallId: "wall-1",
            type: "window",
            position: 0.5,
            width: 1,
            height: 1,
            sill: 0,
        },
    ],
});
assert.equal(normalized.settings.wallHeight, 0.2);
assert.equal(normalized.settings.doorWidth, Core.DEFAULTS.doorWidth);
assert.equal(normalized.settings.doorDepth, Core.DEFAULTS.doorDepth);
assert.equal(normalized.settings.snap, 0.01);
assert.equal(normalized.settings.wallColor, Core.DEFAULTS.wallColor);
assert.equal(normalized.walls[0].start.x, 0);
assert.equal(normalized.openings[0].sill, 0);
assert.equal(normalized.openings[0].depth, Core.DEFAULTS.windowDepth);

const usda = Core.generatePicoUsda(plan);
assert.match(usda, /^#usda 1\.0/);
assert.match(usda, /def Xform "Floor"/);
assert.match(usda, /def Xform "Door_1_door_1"/);
assert.match(usda, /def Xform "Window_2_window_1"/);
assert.match(usda, /float3 xformOp:translate = \(0, -0\.03, 0\)/);
assert.doesNotMatch(usda, /NaN|Infinity|undefined/);

const bounds = Core.planBounds(plan);
assert.equal(bounds.centerX, 0);
assert.equal(bounds.centerY, 0);
assert.equal(bounds.width, 15);
assert.equal(bounds.depth, 8);

const resizedPlan = Core.resizePlanFootprint(plan, 30, 4);
const resizedBounds = Core.planBounds(resizedPlan);
assert.ok(Math.abs(resizedBounds.width - 30) < 0.001);
assert.ok(Math.abs(resizedBounds.depth - 4) < 0.001);
assert.ok(
    Math.abs(
        resizedPlan.openings.find((opening) => opening.id === "door-1").width -
            1.8
    ) < 0.001,
    "opening width must follow its wall when the footprint is resized"
);
assert.ok(
    Math.abs(
        resizedPlan.openings.find((opening) => opening.id === "window-1").width -
            0.7
    ) < 0.001,
    "openings on depth walls must follow the depth scale"
);

const uniformlyScaledPlan = Core.scalePlanUniformly(plan, 2);
assert.equal(Core.planBounds(uniformlyScaledPlan).width, 30);
assert.equal(Core.planBounds(uniformlyScaledPlan).depth, 16);
assert.ok(Math.abs(uniformlyScaledPlan.walls[0].height - 5.6) < 0.001);
assert.ok(Math.abs(uniformlyScaledPlan.walls[0].thickness - 0.32) < 0.001);
assert.ok(
    Math.abs(
        uniformlyScaledPlan.openings.find((opening) => opening.id === "door-1")
            .height - 4.2
    ) < 0.001
);

const preview = Core.buildPreviewScene(plan);
assert.ok(preview.boxes.some((box) => box.kind === "floor"));
assert.ok(preview.boxes.some((box) => box.kind === "ceiling"));
assert.ok(
    preview.boxes.some((box) => box.kind === "window" && box.alpha < 1),
    "walkthrough windows must be transparent"
);
assert.ok(preview.colliders.some((box) => box.kind === "wall"));
assert.ok(
    preview.boxes.some(
        (box) =>
            box.kind === "door" &&
            box.collidable &&
            Math.abs(Math.abs(box.yaw) - Math.PI / 2) < 0.001
    ),
    "the open door panel must be visible and physically collidable"
);
assert.equal(preview.spawn.y, 1.65);

const concavePlan = {
    ...Core.createDemoPlan(),
    walls: [
        {
            id: "concave-1",
            start: { x: -3, y: -3 },
            end: { x: 3, y: -3 },
            height: 2.8,
            thickness: 0.16,
        },
        {
            id: "concave-2",
            start: { x: 3, y: -3 },
            end: { x: 3, y: -1 },
            height: 2.8,
            thickness: 0.16,
        },
        {
            id: "concave-3",
            start: { x: 3, y: -1 },
            end: { x: -1, y: -1 },
            height: 2.8,
            thickness: 0.16,
        },
        {
            id: "concave-4",
            start: { x: -1, y: -1 },
            end: { x: -1, y: 3 },
            height: 2.8,
            thickness: 0.16,
        },
        {
            id: "concave-5",
            start: { x: -1, y: 3 },
            end: { x: -3, y: 3 },
            height: 2.8,
            thickness: 0.16,
        },
        {
            id: "concave-6",
            start: { x: -3, y: 3 },
            end: { x: -3, y: -3 },
            height: 2.8,
            thickness: 0.16,
        },
    ],
    openings: [],
};
const concavePreview = Core.buildPreviewScene(concavePlan);
const concaveSpawn = concavePreview.spawn;
const inBottomBar =
    concaveSpawn.x > -3 &&
    concaveSpawn.x < 3 &&
    concaveSpawn.z > -3 &&
    concaveSpawn.z < -1;
const inLeftBar =
    concaveSpawn.x > -3 &&
    concaveSpawn.x < -1 &&
    concaveSpawn.z > -1 &&
    concaveSpawn.z < 3;
assert.ok(
    inBottomBar || inLeftBar,
    "walkthrough spawn must be inside a concave room, not its bounding-box cutout"
);

console.log("Floor-plan core tests passed.");
