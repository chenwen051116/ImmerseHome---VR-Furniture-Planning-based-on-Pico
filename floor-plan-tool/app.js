(function () {
    "use strict";

    const Core = window.FloorPlanCore;
    const canvas = document.getElementById("planCanvas");
    const ctx = canvas.getContext("2d");
    const shell = canvas.parentElement;

    const elements = {
        modeLabel: document.getElementById("modeLabel"),
        canvasHint: document.getElementById("canvasHint"),
        coordinateLabel: document.getElementById("coordinateLabel"),
        zoomLabel: document.getElementById("zoomLabel"),
        selectionTitle: document.getElementById("selectionTitle"),
        selectionProperties: document.getElementById("selectionProperties"),
        deleteSelectionButton: document.getElementById("deleteSelectionButton"),
        defaultsForm: document.getElementById("defaultsForm"),
        planWidthInput: document.getElementById("planWidthInput"),
        planDepthInput: document.getElementById("planDepthInput"),
        planScaleInput: document.getElementById("planScaleInput"),
        undoButton: document.getElementById("undoButton"),
        redoButton: document.getElementById("redoButton"),
        walkRoomButton: document.getElementById("walkRoomButton"),
        jsonFileInput: document.getElementById("jsonFileInput"),
        usdaDialog: document.getElementById("usdaDialog"),
        usdaPreview: document.getElementById("usdaPreview"),
        validationMessages: document.getElementById("validationMessages"),
        toast: document.getElementById("toast"),
    };

    const modeCopy = {
        select: {
            label: "Select",
            hint: "Select a wall or opening. Drag wall endpoints or slide openings.",
        },
        wall: {
            label: "Draw wall",
            hint: "Click a start point, then click each endpoint. Esc ends the chain.",
        },
        door: {
            label: "Place door",
            hint: "Click a wall to place a door. Drag it later with Select.",
        },
        window: {
            label: "Place window",
            hint: "Click a wall to place a window. Drag it later with Select.",
        },
    };

    let plan = loadAutosave() || Core.createDemoPlan();
    plan = Core.normalizePlan(plan);
    let mode = "select";
    let selection = null;
    let drawStart = null;
    let hoverPoint = { x: 0, y: 0 };
    let hoverTarget = null;
    let drag = null;
    let pan = null;
    let spacePressed = false;
    let cssWidth = 1;
    let cssHeight = 1;
    let dpr = 1;
    let toastTimer = null;
    const camera = { x: 0, y: 0, zoom: 1 };
    let history = [];
    let historyIndex = -1;

    function planSnapshot() {
        return JSON.stringify(plan);
    }

    function resetHistory() {
        history = [planSnapshot()];
        historyIndex = 0;
        updateHistoryButtons();
    }

    function recordHistory() {
        const snapshot = planSnapshot();
        if (history[historyIndex] === snapshot) {
            return;
        }
        history = history.slice(0, historyIndex + 1);
        history.push(snapshot);
        if (history.length > 80) {
            history.shift();
        }
        historyIndex = history.length - 1;
        updateHistoryButtons();
        autosave();
    }

    function restoreHistory(index) {
        if (index < 0 || index >= history.length) {
            return;
        }
        historyIndex = index;
        plan = Core.normalizePlan(JSON.parse(history[index]));
        selection = null;
        drawStart = null;
        updateAllUi();
        autosave();
    }

    function undo() {
        restoreHistory(historyIndex - 1);
    }

    function redo() {
        restoreHistory(historyIndex + 1);
    }

    function updateHistoryButtons() {
        elements.undoButton.disabled = historyIndex <= 0;
        elements.redoButton.disabled = historyIndex >= history.length - 1;
    }

    function loadAutosave() {
        try {
            const raw = localStorage.getItem("pico-floor-plan-v2");
            return raw ? JSON.parse(raw) : null;
        } catch (error) {
            console.warn("Could not load autosave", error);
            return null;
        }
    }

    function autosave() {
        try {
            localStorage.setItem("pico-floor-plan-v2", JSON.stringify(plan));
        } catch (error) {
            console.warn("Could not save plan", error);
        }
    }

    function showToast(message) {
        window.clearTimeout(toastTimer);
        elements.toast.textContent = message;
        elements.toast.classList.add("visible");
        toastTimer = window.setTimeout(
            () => elements.toast.classList.remove("visible"),
            2400
        );
    }

    function resizeCanvas() {
        const rect = shell.getBoundingClientRect();
        cssWidth = Math.max(1, rect.width);
        cssHeight = Math.max(1, rect.height);
        dpr = Math.min(2, window.devicePixelRatio || 1);
        canvas.width = Math.round(cssWidth * dpr);
        canvas.height = Math.round(cssHeight * dpr);
        draw();
    }

    function pixelsPerMeter() {
        return 72 * camera.zoom;
    }

    function worldToScreen(point) {
        const ppm = pixelsPerMeter();
        return {
            x: cssWidth / 2 + (point.x - camera.x) * ppm,
            y: cssHeight / 2 + (point.y - camera.y) * ppm,
        };
    }

    function screenToWorld(point) {
        const ppm = pixelsPerMeter();
        return {
            x: camera.x + (point.x - cssWidth / 2) / ppm,
            y: camera.y + (point.y - cssHeight / 2) / ppm,
        };
    }

    function eventPoint(event) {
        const rect = canvas.getBoundingClientRect();
        return { x: event.clientX - rect.left, y: event.clientY - rect.top };
    }

    function snapValue(value) {
        const snap = Math.max(0.01, Number(plan.settings.snap) || 0.1);
        return Math.round(value / snap) * snap;
    }

    function nearestSnap(point, includeWallProjection = false) {
        const snapped = { x: snapValue(point.x), y: snapValue(point.y) };
        const threshold = 12 / pixelsPerMeter();
        let result = { point: snapped, type: "grid", distance: Core.distance(point, snapped) };

        plan.walls.forEach((wall) => {
            [wall.start, wall.end].forEach((endpoint) => {
                const value = Core.distance(point, endpoint);
                if (value < threshold && value < result.distance + threshold * 0.4) {
                    result = {
                        point: { ...endpoint },
                        type: "endpoint",
                        distance: value,
                    };
                }
            });
        });

        if (includeWallProjection) {
            const wallHit = nearestWall(point);
            if (wallHit && wallHit.distance < threshold) {
                result = {
                    point: wallHit.point,
                    type: "wall",
                    distance: wallHit.distance,
                    wallId: wallHit.wall.id,
                    position: wallHit.position,
                };
            }
        }
        return result;
    }

    function projectionOnWall(point, wall) {
        const dx = wall.end.x - wall.start.x;
        const dy = wall.end.y - wall.start.y;
        const lengthSquared = dx * dx + dy * dy;
        if (lengthSquared < 0.000001) {
            return {
                point: { ...wall.start },
                position: 0,
                distance: Core.distance(point, wall.start),
            };
        }
        const position = Core.clamp(
            ((point.x - wall.start.x) * dx + (point.y - wall.start.y) * dy) /
                lengthSquared,
            0,
            1
        );
        const projected = {
            x: wall.start.x + dx * position,
            y: wall.start.y + dy * position,
        };
        return {
            point: projected,
            position,
            distance: Core.distance(point, projected),
        };
    }

    function nearestWall(point) {
        let best = null;
        plan.walls.forEach((wall) => {
            const projection = projectionOnWall(point, wall);
            if (!best || projection.distance < best.distance) {
                best = { wall, ...projection };
            }
        });
        return best;
    }

    function openingWorldPosition(opening) {
        const wall = plan.walls.find((candidate) => candidate.id === opening.wallId);
        if (!wall) {
            return null;
        }
        return {
            x: wall.start.x + (wall.end.x - wall.start.x) * opening.position,
            y: wall.start.y + (wall.end.y - wall.start.y) * opening.position,
        };
    }

    function hitOpening(point) {
        const threshold = 13 / pixelsPerMeter();
        let best = null;
        plan.openings.forEach((opening) => {
            const position = openingWorldPosition(opening);
            if (!position) {
                return;
            }
            const distance = Core.distance(point, position);
            if (distance < threshold && (!best || distance < best.distance)) {
                best = { opening, distance };
            }
        });
        return best;
    }

    function hitWallEndpoint(point) {
        const threshold = 11 / pixelsPerMeter();
        let best = null;
        plan.walls.forEach((wall) => {
            ["start", "end"].forEach((end) => {
                const distance = Core.distance(point, wall[end]);
                if (distance < threshold && (!best || distance < best.distance)) {
                    best = { wall, end, distance };
                }
            });
        });
        return best;
    }

    function nextId(prefix, items) {
        let index = 1;
        const ids = new Set(items.map((item) => item.id));
        while (ids.has(`${prefix}-${index}`)) {
            index += 1;
        }
        return `${prefix}-${index}`;
    }

    function setMode(nextMode) {
        if (!modeCopy[nextMode]) {
            return;
        }
        mode = nextMode;
        drawStart = null;
        drag = null;
        document.querySelectorAll("[data-mode]").forEach((button) => {
            button.classList.toggle("active", button.dataset.mode === mode);
        });
        elements.modeLabel.textContent = modeCopy[mode].label;
        elements.canvasHint.textContent = modeCopy[mode].hint;
        canvas.style.cursor = mode === "select" ? "default" : "crosshair";
        draw();
    }

    function select(type, id) {
        selection = type && id ? { type, id } : null;
        updateSelectionPanel();
        draw();
    }

    function selectedItem() {
        if (!selection) {
            return null;
        }
        return selection.type === "wall"
            ? plan.walls.find((wall) => wall.id === selection.id)
            : plan.openings.find((opening) => opening.id === selection.id);
    }

    function addWall(endPoint) {
        if (!drawStart) {
            drawStart = nearestSnap(endPoint).point;
            draw();
            return;
        }
        const end = nearestSnap(endPoint).point;
        if (Core.distance(drawStart, end) < 0.2) {
            showToast("Walls must be at least 0.2 m long.");
            return;
        }
        const wall = {
            id: nextId("wall", plan.walls),
            start: { ...drawStart },
            end: { ...end },
            height: plan.settings.wallHeight,
            thickness: plan.settings.wallThickness,
        };
        plan.walls.push(wall);
        drawStart = { ...end };
        recordHistory();
        select("wall", wall.id);
    }

    function addOpening(type, point) {
        const hit = nearestWall(point);
        if (!hit || hit.distance > 0.35) {
            showToast(`Move closer to a wall to place the ${type}.`);
            return;
        }
        const wallLength = Core.wallLength(hit.wall);
        const defaults =
            type === "door"
                ? {
                      width: plan.settings.doorWidth,
                      height: plan.settings.doorHeight,
                      depth: plan.settings.doorDepth,
                      sill: 0,
                  }
                : {
                      width: plan.settings.windowWidth,
                      height: plan.settings.windowHeight,
                      depth: plan.settings.windowDepth,
                      sill: plan.settings.windowSill,
                  };
        const half = Math.min(defaults.width / 2, wallLength / 2);
        const position =
            wallLength > 0
                ? Core.clamp(hit.position, half / wallLength, 1 - half / wallLength)
                : 0.5;
        const opening = {
            id: nextId(type, plan.openings),
            wallId: hit.wall.id,
            type,
            position,
            ...defaults,
        };
        plan.openings.push(opening);
        recordHistory();
        select("opening", opening.id);
        setMode("select");
    }

    function deleteSelection() {
        if (!selection) {
            return;
        }
        if (selection.type === "wall") {
            plan.walls = plan.walls.filter((wall) => wall.id !== selection.id);
            plan.openings = plan.openings.filter(
                (opening) => opening.wallId !== selection.id
            );
        } else {
            plan.openings = plan.openings.filter(
                (opening) => opening.id !== selection.id
            );
        }
        selection = null;
        recordHistory();
        updateAllUi();
    }

    function pointerDown(event) {
        const screen = eventPoint(event);
        const world = screenToWorld(screen);

        if (event.button === 1 || event.button === 2 || spacePressed) {
            event.preventDefault();
            pan = {
                screen,
                camera: { x: camera.x, y: camera.y },
            };
            canvas.setPointerCapture(event.pointerId);
            canvas.style.cursor = "grabbing";
            return;
        }
        if (event.button !== 0) {
            return;
        }

        if (mode === "wall") {
            addWall(world);
            return;
        }
        if (mode === "door" || mode === "window") {
            addOpening(mode, world);
            return;
        }

        const endpointHit = hitWallEndpoint(world);
        const openingHit = hitOpening(world);
        const wallHit = nearestWall(world);
        const wallThreshold = 10 / pixelsPerMeter();

        if (endpointHit) {
            select("wall", endpointHit.wall.id);
            drag = {
                type: "endpoint",
                wallId: endpointHit.wall.id,
                end: endpointHit.end,
                before: planSnapshot(),
            };
            canvas.setPointerCapture(event.pointerId);
        } else if (openingHit) {
            select("opening", openingHit.opening.id);
            drag = {
                type: "opening",
                openingId: openingHit.opening.id,
                before: planSnapshot(),
            };
            canvas.setPointerCapture(event.pointerId);
        } else if (wallHit && wallHit.distance < wallThreshold) {
            select("wall", wallHit.wall.id);
        } else {
            select(null, null);
        }
    }

    function pointerMove(event) {
        const screen = eventPoint(event);
        const world = screenToWorld(screen);
        hoverPoint = world;
        elements.coordinateLabel.textContent = `x ${world.x.toFixed(
            2
        )} m · z ${world.y.toFixed(2)} m`;

        if (pan) {
            const ppm = pixelsPerMeter();
            camera.x = pan.camera.x - (screen.x - pan.screen.x) / ppm;
            camera.y = pan.camera.y - (screen.y - pan.screen.y) / ppm;
            draw();
            return;
        }

        if (drag?.type === "endpoint") {
            const wall = plan.walls.find((candidate) => candidate.id === drag.wallId);
            if (wall) {
                wall[drag.end] = nearestSnap(world).point;
                updateSelectionPanel();
            }
        } else if (drag?.type === "opening") {
            const opening = plan.openings.find(
                (candidate) => candidate.id === drag.openingId
            );
            const wall = opening
                ? plan.walls.find((candidate) => candidate.id === opening.wallId)
                : null;
            if (opening && wall) {
                const projection = projectionOnWall(world, wall);
                const length = Core.wallLength(wall);
                const half = Math.min(opening.width / 2, length / 2);
                opening.position =
                    length > 0
                        ? Core.clamp(
                              projection.position,
                              half / length,
                              1 - half / length
                          )
                        : 0.5;
                updateSelectionPanel();
            }
        } else {
            hoverTarget =
                mode === "wall"
                    ? nearestSnap(world)
                    : mode === "door" || mode === "window"
                      ? nearestSnap(world, true)
                      : null;
        }
        draw();
    }

    function pointerUp(event) {
        if (pan) {
            pan = null;
            canvas.style.cursor = mode === "select" ? "default" : "crosshair";
        } else if (drag) {
            const activeDrag = drag;
            drag = null;
            const draggedWall =
                activeDrag.type === "endpoint"
                    ? plan.walls.find(
                          (candidate) => candidate.id === activeDrag.wallId
                      )
                    : null;
            if (draggedWall && Core.wallLength(draggedWall) < 0.2) {
                plan = Core.normalizePlan(JSON.parse(activeDrag.before));
                showToast("Walls must be at least 0.2 m long.");
                updateAllUi();
            } else if (activeDrag.before !== planSnapshot()) {
                recordHistory();
            }
        }
        if (canvas.hasPointerCapture(event.pointerId)) {
            canvas.releasePointerCapture(event.pointerId);
        }
    }

    function wheel(event) {
        event.preventDefault();
        const screen = eventPoint(event);
        const before = screenToWorld(screen);
        const factor = event.deltaY < 0 ? 1.12 : 1 / 1.12;
        camera.zoom = Core.clamp(camera.zoom * factor, 0.2, 5);
        const after = screenToWorld(screen);
        camera.x += before.x - after.x;
        camera.y += before.y - after.y;
        updateZoomLabel();
        draw();
    }

    function zoomBy(factor) {
        camera.zoom = Core.clamp(camera.zoom * factor, 0.2, 5);
        updateZoomLabel();
        draw();
    }

    function fitPlan() {
        const bounds = Core.planBounds(plan);
        const horizontal = Math.max(2, bounds.width + 1.6);
        const vertical = Math.max(2, bounds.depth + 1.6);
        camera.x = bounds.centerX;
        camera.y = bounds.centerY;
        camera.zoom = Core.clamp(
            Math.min(cssWidth / (horizontal * 72), cssHeight / (vertical * 72)),
            0.2,
            3.5
        );
        updateZoomLabel();
        draw();
    }

    function updateZoomLabel() {
        elements.zoomLabel.textContent = `${Math.round(camera.zoom * 100)}%`;
    }

    function drawGrid() {
        const ppm = pixelsPerMeter();
        const minorStep =
            ppm >= 180 ? 0.1 : ppm >= 75 ? 0.25 : ppm >= 36 ? 0.5 : 1;
        const left = screenToWorld({ x: 0, y: 0 }).x;
        const right = screenToWorld({ x: cssWidth, y: 0 }).x;
        const top = screenToWorld({ x: 0, y: 0 }).y;
        const bottom = screenToWorld({ x: 0, y: cssHeight }).y;

        ctx.lineWidth = 1;
        for (let x = Math.floor(left / minorStep) * minorStep; x <= right; x += minorStep) {
            const screen = worldToScreen({ x, y: 0 });
            const major = Math.abs(x - Math.round(x)) < 0.001;
            ctx.strokeStyle = major ? "#252c33" : "#171c21";
            ctx.beginPath();
            ctx.moveTo(Math.round(screen.x) + 0.5, 0);
            ctx.lineTo(Math.round(screen.x) + 0.5, cssHeight);
            ctx.stroke();
        }
        for (let y = Math.floor(top / minorStep) * minorStep; y <= bottom; y += minorStep) {
            const screen = worldToScreen({ x: 0, y });
            const major = Math.abs(y - Math.round(y)) < 0.001;
            ctx.strokeStyle = major ? "#252c33" : "#171c21";
            ctx.beginPath();
            ctx.moveTo(0, Math.round(screen.y) + 0.5);
            ctx.lineTo(cssWidth, Math.round(screen.y) + 0.5);
            ctx.stroke();
        }

        const origin = worldToScreen({ x: 0, y: 0 });
        ctx.strokeStyle = "#39434c";
        ctx.beginPath();
        ctx.moveTo(origin.x, 0);
        ctx.lineTo(origin.x, cssHeight);
        ctx.moveTo(0, origin.y);
        ctx.lineTo(cssWidth, origin.y);
        ctx.stroke();
    }

    function drawWall(wall) {
        const start = worldToScreen(wall.start);
        const end = worldToScreen(wall.end);
        const selected = selection?.type === "wall" && selection.id === wall.id;
        const dx = end.x - start.x;
        const dy = end.y - start.y;
        const angle = Math.atan2(dy, dx);
        const lengthPixels = Math.hypot(dx, dy);
        const thickness = Math.max(4, wall.thickness * pixelsPerMeter());

        ctx.save();
        ctx.translate(start.x, start.y);
        ctx.rotate(angle);
        ctx.fillStyle = selected ? "#ff7759" : "#c8ced4";
        ctx.fillRect(0, -thickness / 2, lengthPixels, thickness);
        ctx.strokeStyle = selected ? "#ffb19f" : "#e8ecef";
        ctx.lineWidth = selected ? 2 : 1;
        ctx.strokeRect(0, -thickness / 2, lengthPixels, thickness);
        ctx.restore();

        if (selected || mode === "wall") {
            [start, end].forEach((point) => {
                ctx.beginPath();
                ctx.arc(point.x, point.y, selected ? 6 : 4, 0, Math.PI * 2);
                ctx.fillStyle = selected ? "#0f1216" : "#ff6847";
                ctx.fill();
                ctx.lineWidth = 2;
                ctx.strokeStyle = "#ff8c72";
                ctx.stroke();
            });
        }

        if (selected) {
            const midpoint = { x: (start.x + end.x) / 2, y: (start.y + end.y) / 2 };
            const label = `${Core.wallLength(wall).toFixed(2)} m`;
            ctx.font = "700 10px Inter, system-ui, sans-serif";
            const width = ctx.measureText(label).width + 12;
            ctx.fillStyle = "#0f1317";
            ctx.fillRect(midpoint.x - width / 2, midpoint.y - 25, width, 18);
            ctx.fillStyle = "#ff9a82";
            ctx.textAlign = "center";
            ctx.textBaseline = "middle";
            ctx.fillText(label, midpoint.x, midpoint.y - 16);
        }
    }

    function drawOpening(opening) {
        const wall = plan.walls.find((candidate) => candidate.id === opening.wallId);
        const position = openingWorldPosition(opening);
        if (!wall || !position) {
            return;
        }
        const worldLength = Core.wallLength(wall);
        if (worldLength <= 0.0001) {
            return;
        }
        const center = worldToScreen(position);
        const dx = wall.end.x - wall.start.x;
        const dy = wall.end.y - wall.start.y;
        const angle = Math.atan2(dy, dx);
        const width = Math.max(8, opening.width * pixelsPerMeter());
        const thickness = Math.max(8, wall.thickness * pixelsPerMeter() + 4);
        const selected = selection?.type === "opening" && selection.id === opening.id;
        const color = opening.type === "door" ? "#5ce0c0" : "#72aaff";

        ctx.save();
        ctx.translate(center.x, center.y);
        ctx.rotate(angle);
        ctx.fillStyle = "#0b0e11";
        ctx.fillRect(-width / 2, -thickness / 2, width, thickness);
        ctx.strokeStyle = selected ? "#ffffff" : color;
        ctx.lineWidth = selected ? 3 : 2;
        ctx.strokeRect(-width / 2, -thickness / 2, width, thickness);

        if (opening.type === "door") {
            ctx.beginPath();
            ctx.moveTo(-width / 2, thickness / 2);
            ctx.lineTo(-width / 2, thickness / 2 - width * 0.72);
            ctx.arc(
                -width / 2,
                thickness / 2,
                width * 0.72,
                -Math.PI / 2,
                0
            );
            ctx.strokeStyle = color;
            ctx.lineWidth = 1.5;
            ctx.stroke();
        } else {
            ctx.beginPath();
            ctx.moveTo(-width / 2, 0);
            ctx.lineTo(width / 2, 0);
            ctx.strokeStyle = color;
            ctx.lineWidth = 3;
            ctx.stroke();
        }
        ctx.restore();
    }

    function drawPreview() {
        if (mode === "wall" && drawStart) {
            const end = hoverTarget?.point || {
                x: snapValue(hoverPoint.x),
                y: snapValue(hoverPoint.y),
            };
            const startScreen = worldToScreen(drawStart);
            const endScreen = worldToScreen(end);
            ctx.save();
            ctx.setLineDash([7, 5]);
            ctx.strokeStyle = "#ff6847";
            ctx.lineWidth = 2;
            ctx.beginPath();
            ctx.moveTo(startScreen.x, startScreen.y);
            ctx.lineTo(endScreen.x, endScreen.y);
            ctx.stroke();
            ctx.restore();
        }

        if (hoverTarget && mode !== "select") {
            const point = worldToScreen(hoverTarget.point);
            ctx.beginPath();
            ctx.arc(point.x, point.y, 7, 0, Math.PI * 2);
            ctx.fillStyle =
                hoverTarget.type === "endpoint"
                    ? "rgba(92, 224, 192, .25)"
                    : "rgba(255, 104, 71, .22)";
            ctx.fill();
            ctx.lineWidth = 2;
            ctx.strokeStyle =
                hoverTarget.type === "endpoint" ? "#5ce0c0" : "#ff6847";
            ctx.stroke();
        }
    }

    function draw() {
        ctx.setTransform(dpr, 0, 0, dpr, 0, 0);
        ctx.clearRect(0, 0, cssWidth, cssHeight);
        ctx.fillStyle = "#090b0e";
        ctx.fillRect(0, 0, cssWidth, cssHeight);
        drawGrid();
        plan.walls.forEach(drawWall);
        plan.openings.forEach(drawOpening);
        drawPreview();
    }

    function numberField(label, property, value, step, min = 0, unit = "m") {
        return `<label class="property-row">
            <span>${label}</span>
            <span class="number-field">
                <input type="number" data-property="${property}" value="${Number(
                    value
                ).toFixed(2)}" min="${min}" step="${step}">
                <b>${unit}</b>
            </span>
        </label>`;
    }

    function updateSelectionPanel() {
        const item = selectedItem();
        elements.deleteSelectionButton.disabled = !item;
        if (!item) {
            elements.selectionTitle.textContent = "Nothing selected";
            elements.selectionProperties.innerHTML =
                '<p class="empty-copy">Choose Select, then click a wall, door, or window.</p>';
            return;
        }

        if (selection.type === "wall") {
            elements.selectionTitle.textContent = "Wall";
            const directionDegrees =
                (Math.atan2(
                    item.end.y - item.start.y,
                    item.end.x - item.start.x
                ) *
                    180) /
                Math.PI;
            elements.selectionProperties.innerHTML = `
                ${numberField("Length", "length", Core.wallLength(item), 0.1, 0.2)}
                ${numberField(
                    "Direction",
                    "directionDegrees",
                    directionDegrees,
                    1,
                    -360,
                    "deg"
                )}
                ${numberField("Start X", "start.x", item.start.x, 0.1, -1000)}
                ${numberField("Start Z", "start.y", item.start.y, 0.1, -1000)}
                ${numberField("End X", "end.x", item.end.x, 0.1, -1000)}
                ${numberField("End Z", "end.y", item.end.y, 0.1, -1000)}
                ${numberField("Height", "height", item.height, 0.1, 0.2)}
                ${numberField("Thickness", "thickness", item.thickness, 0.01, 0.02)}
            `;
        } else {
            elements.selectionTitle.textContent =
                item.type === "door" ? "Door" : "Window";
            const wall = plan.walls.find((candidate) => candidate.id === item.wallId);
            elements.selectionProperties.innerHTML = `
                <div class="property-row"><span>Wall</span><span class="readonly-value">${
                    wall ? wall.id : "Missing"
                }</span></div>
                ${numberField("Position", "positionMeters", wall ? item.position * Core.wallLength(wall) : 0, 0.1, 0)}
                ${numberField("Width", "width", item.width, 0.1, 0.1)}
                ${numberField("Height", "height", item.height, 0.1, 0.1)}
                ${numberField("Panel depth", "depth", item.depth, 0.005, 0.005)}
                ${
                    item.type === "window"
                        ? numberField("Sill height", "sill", item.sill, 0.1, 0)
                        : ""
                }
            `;
        }
    }

    function updateSettingsUi() {
        document.querySelectorAll("[data-setting]").forEach((input) => {
            const key = input.dataset.setting;
            if (!(key in plan.settings)) {
                return;
            }
            if (input.type === "checkbox") {
                input.checked = Boolean(plan.settings[key]);
            } else {
                input.value = plan.settings[key];
            }
        });
    }

    function updatePlanSizeUi() {
        const bounds = Core.planBounds(plan);
        elements.planWidthInput.value = bounds.width.toFixed(2);
        elements.planDepthInput.value = bounds.depth.toFixed(2);
    }

    function updateAllUi() {
        updateSelectionPanel();
        updateSettingsUi();
        updatePlanSizeUi();
        updateHistoryButtons();
        draw();
    }

    function setNestedProperty(target, path, value) {
        const parts = path.split(".");
        let object = target;
        for (let index = 0; index < parts.length - 1; index += 1) {
            object = object[parts[index]];
        }
        object[parts[parts.length - 1]] = value;
    }

    function selectionPropertyChanged(event) {
        const input = event.target.closest("[data-property]");
        const item = selectedItem();
        if (!input || !item) {
            return;
        }
        const value = Number(input.value);
        if (!Number.isFinite(value)) {
            updateSelectionPanel();
            return;
        }
        const property = input.dataset.property;
        if (property === "positionMeters" && selection.type === "opening") {
            const wall = plan.walls.find((candidate) => candidate.id === item.wallId);
            const length = wall ? Core.wallLength(wall) : 0;
            const half = Math.min(item.width / 2, length / 2);
            item.position =
                length > 0
                    ? Core.clamp(value / length, half / length, 1 - half / length)
                    : 0.5;
        } else if (property === "length" && selection.type === "wall") {
            const currentLength = Core.wallLength(item);
            const direction =
                currentLength > 0.0001
                    ? Math.atan2(
                          item.end.y - item.start.y,
                          item.end.x - item.start.x
                      )
                    : 0;
            const length = Math.max(0.2, value);
            item.end.x = item.start.x + Math.cos(direction) * length;
            item.end.y = item.start.y + Math.sin(direction) * length;
        } else if (
            property === "directionDegrees" &&
            selection.type === "wall"
        ) {
            const length = Math.max(0.2, Core.wallLength(item));
            const direction = (value * Math.PI) / 180;
            item.end.x = item.start.x + Math.cos(direction) * length;
            item.end.y = item.start.y + Math.sin(direction) * length;
        } else {
            setNestedProperty(item, property, value);
        }
        plan = Core.normalizePlan(plan);
        recordHistory();
        updateAllUi();
    }

    function settingChanged(event) {
        const input = event.target.closest("[data-setting]");
        if (!input) {
            return;
        }
        const key = input.dataset.setting;
        plan.settings[key] =
            input.type === "checkbox"
                ? input.checked
                : input.type === "number"
                  ? Number(input.value)
                  : input.value;
        plan = Core.normalizePlan(plan);
        recordHistory();
        updateAllUi();
    }

    function resizePlan() {
        if (!plan.walls.length) {
            showToast("Add at least one wall before resizing the footprint.");
            return;
        }
        const width = Number(elements.planWidthInput.value);
        const depth = Number(elements.planDepthInput.value);
        if (
            !Number.isFinite(width) ||
            !Number.isFinite(depth) ||
            width < 0.1 ||
            depth < 0.1
        ) {
            showToast("Enter a footprint width and depth of at least 0.1 m.");
            updatePlanSizeUi();
            return;
        }
        plan = Core.resizePlanFootprint(plan, width, depth);
        recordHistory();
        updateAllUi();
        fitPlan();
        showToast(
            `Footprint set to ${width.toFixed(2)} m × ${depth.toFixed(2)} m.`
        );
    }

    function scalePlan() {
        if (!plan.walls.length) {
            showToast("Add at least one wall before scaling the plan.");
            return;
        }
        const percent = Number(elements.planScaleInput.value);
        if (!Number.isFinite(percent) || percent < 10 || percent > 500) {
            showToast("Uniform scale must be between 10% and 500%.");
            elements.planScaleInput.value = "100";
            return;
        }
        plan = Core.scalePlanUniformly(plan, percent / 100);
        elements.planScaleInput.value = "100";
        recordHistory();
        updateAllUi();
        fitPlan();
        showToast(`Plan scaled to ${percent.toFixed(0)}%.`);
    }

    function downloadBlob(contents, filename, type) {
        const blob = new Blob([contents], { type });
        const url = URL.createObjectURL(blob);
        const anchor = document.createElement("a");
        anchor.href = url;
        anchor.download = filename;
        document.body.appendChild(anchor);
        anchor.click();
        anchor.remove();
        URL.revokeObjectURL(url);
    }

    async function saveUsda() {
        const warnings = Core.validatePlan(plan);
        if (warnings.length) {
            showToast(warnings[0]);
        }
        const contents = Core.generatePicoUsda(plan);
        if ("showSaveFilePicker" in window) {
            try {
                const handle = await window.showSaveFilePicker({
                    suggestedName: "FloorPlan.usda",
                    types: [
                        {
                            description: "Universal Scene Description ASCII",
                            accept: { "text/plain": [".usda"] },
                        },
                    ],
                });
                const writable = await handle.createWritable();
                await writable.write(contents);
                await writable.close();
                showToast("FloorPlan.usda saved.");
                return;
            } catch (error) {
                if (error?.name === "AbortError") {
                    return;
                }
                console.warn("Direct save failed; using download", error);
            }
        }
        downloadBlob(contents, "FloorPlan.usda", "text/plain;charset=utf-8");
        showToast("FloorPlan.usda downloaded.");
    }

    function exportJson() {
        const filename = `${(plan.name || "floor-plan")
            .replace(/[^a-z0-9-_]+/gi, "-")
            .replace(/^-+|-+$/g, "") || "floor-plan"}.json`;
        downloadBlob(
            JSON.stringify(plan, null, 2),
            filename,
            "application/json;charset=utf-8"
        );
        showToast("Editable plan JSON exported.");
    }

    async function importJsonFile(file) {
        try {
            const parsed = JSON.parse(await file.text());
            plan = Core.normalizePlan(parsed);
            selection = null;
            drawStart = null;
            resetHistory();
            updateAllUi();
            fitPlan();
            autosave();
            showToast("Plan imported.");
        } catch (error) {
            console.error(error);
            showToast("That file is not a valid floor-plan JSON file.");
        } finally {
            elements.jsonFileInput.value = "";
        }
    }

    function previewUsda() {
        const warnings = Core.validatePlan(plan);
        elements.validationMessages.textContent = warnings.join(" ");
        elements.usdaPreview.textContent = Core.generatePicoUsda(plan);
        elements.usdaDialog.showModal();
    }

    function newPlan() {
        if (
            plan.walls.length &&
            !window.confirm("Clear the current plan and start with an empty canvas?")
        ) {
            return;
        }
        plan = Core.normalizePlan({ name: "Floor Plan", settings: plan.settings });
        selection = null;
        drawStart = null;
        resetHistory();
        updateAllUi();
        fitPlan();
        autosave();
    }

    function loadDemo() {
        if (
            plan.walls.length &&
            !window.confirm("Replace the current plan with the demo room?")
        ) {
            return;
        }
        plan = Core.createDemoPlan();
        selection = null;
        drawStart = null;
        resetHistory();
        updateAllUi();
        fitPlan();
        autosave();
    }

    function keyDown(event) {
        if (!document.getElementById("walkthroughOverlay").hidden) {
            return;
        }
        const editing =
            event.target instanceof HTMLInputElement ||
            event.target instanceof HTMLTextAreaElement;
        if (event.code === "Space" && !editing) {
            spacePressed = true;
            canvas.style.cursor = "grab";
            event.preventDefault();
        }
        if (editing) {
            return;
        }
        if ((event.ctrlKey || event.metaKey) && event.key.toLowerCase() === "z") {
            event.preventDefault();
            event.shiftKey ? redo() : undo();
            return;
        }
        if (
            (event.ctrlKey || event.metaKey) &&
            event.key.toLowerCase() === "y"
        ) {
            event.preventDefault();
            redo();
            return;
        }
        if (event.key === "1") setMode("select");
        if (event.key === "2") setMode("wall");
        if (event.key === "3") setMode("door");
        if (event.key === "4") setMode("window");
        if (event.key === "Delete" || event.key === "Backspace") {
            event.preventDefault();
            deleteSelection();
        }
        if (event.key === "Escape") {
            if (drag?.before) {
                plan = Core.normalizePlan(JSON.parse(drag.before));
                updateAllUi();
            }
            drawStart = null;
            drag = null;
            if (elements.usdaDialog.open) {
                elements.usdaDialog.close();
            }
            draw();
        }
    }

    function keyUp(event) {
        if (event.code === "Space") {
            spacePressed = false;
            if (!pan) {
                canvas.style.cursor = mode === "select" ? "default" : "crosshair";
            }
        }
    }

    document.querySelectorAll("[data-mode]").forEach((button) => {
        button.addEventListener("click", () => setMode(button.dataset.mode));
    });
    elements.selectionProperties.addEventListener("change", selectionPropertyChanged);
    document
        .querySelector(".inspector")
        .addEventListener("change", settingChanged);
    elements.deleteSelectionButton.addEventListener("click", deleteSelection);
    elements.undoButton.addEventListener("click", undo);
    elements.redoButton.addEventListener("click", redo);
    document.getElementById("zoomOutButton").addEventListener("click", () => zoomBy(0.82));
    document.getElementById("zoomInButton").addEventListener("click", () => zoomBy(1.22));
    document.getElementById("fitButton").addEventListener("click", fitPlan);
    document.getElementById("resizePlanButton").addEventListener("click", resizePlan);
    document.getElementById("scalePlanButton").addEventListener("click", scalePlan);
    document.getElementById("newPlanButton").addEventListener("click", newPlan);
    document.getElementById("demoPlanButton").addEventListener("click", loadDemo);
    document
        .getElementById("importJsonButton")
        .addEventListener("click", () => elements.jsonFileInput.click());
    document.getElementById("exportJsonButton").addEventListener("click", exportJson);
    document.getElementById("saveUsdaButton").addEventListener("click", saveUsda);
    document.getElementById("previewUsdaButton").addEventListener("click", previewUsda);
    document.getElementById("dialogSaveUsdaButton").addEventListener("click", saveUsda);
    document.getElementById("closeDialogButton").addEventListener("click", () => {
        elements.usdaDialog.close();
    });
    document.getElementById("copyUsdaButton").addEventListener("click", async () => {
        try {
            await navigator.clipboard.writeText(elements.usdaPreview.textContent);
            showToast("USDA copied.");
        } catch {
            showToast("Clipboard access is unavailable; select the preview text instead.");
        }
    });
    elements.jsonFileInput.addEventListener("change", () => {
        const [file] = elements.jsonFileInput.files;
        if (file) {
            importJsonFile(file);
        }
    });

    canvas.addEventListener("pointerdown", pointerDown);
    canvas.addEventListener("pointermove", pointerMove);
    canvas.addEventListener("pointerup", pointerUp);
    canvas.addEventListener("pointercancel", pointerUp);
    canvas.addEventListener("wheel", wheel, { passive: false });
    canvas.addEventListener("contextmenu", (event) => event.preventDefault());
    window.addEventListener("keydown", keyDown);
    window.addEventListener("keyup", keyUp);
    window.addEventListener("resize", resizeCanvas);

    try {
        const walkthrough = window.FloorPlanWalkthrough.create({
            canvas: document.getElementById("walkthroughCanvas"),
            overlay: document.getElementById("walkthroughOverlay"),
            positionLabel: document.getElementById("walkthroughPosition"),
            lockHint: document.getElementById("walkthroughLockHint"),
            collisionButton: document.getElementById("walkthroughCollisionButton"),
            resetButton: document.getElementById("walkthroughResetButton"),
            exitButton: document.getElementById("walkthroughExitButton"),
            getPlan: () => plan,
        });
        elements.walkRoomButton.addEventListener("click", walkthrough.open);
    } catch (error) {
        console.error("Could not initialize the desktop walkthrough", error);
        elements.walkRoomButton.disabled = true;
        elements.walkRoomButton.title =
            "The browser could not initialize WebGL for the 3D walkthrough.";
    }

    resetHistory();
    updateAllUi();
    resizeCanvas();
    window.setTimeout(fitPlan, 0);
})();
