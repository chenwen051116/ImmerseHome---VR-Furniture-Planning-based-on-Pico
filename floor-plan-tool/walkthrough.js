(function (root, factory) {
    "use strict";
    root.FloorPlanWalkthrough = factory(root.FloorPlanCore);
})(typeof globalThis !== "undefined" ? globalThis : this, function (Core) {
    "use strict";

    const VERTEX_STRIDE_FLOATS = 10;
    const PLAYER_RADIUS = 0.26;
    const WALK_SPEED = 2.8;
    const SPRINT_SPEED = 6;
    const MAX_PITCH = Math.PI * 0.47;

    const FACE_DEFINITIONS = [
        {
            normal: [1, 0, 0],
            corners: [
                [0.5, -0.5, -0.5],
                [0.5, -0.5, 0.5],
                [0.5, 0.5, 0.5],
                [0.5, 0.5, -0.5],
            ],
        },
        {
            normal: [-1, 0, 0],
            corners: [
                [-0.5, -0.5, 0.5],
                [-0.5, -0.5, -0.5],
                [-0.5, 0.5, -0.5],
                [-0.5, 0.5, 0.5],
            ],
        },
        {
            normal: [0, 1, 0],
            corners: [
                [-0.5, 0.5, -0.5],
                [0.5, 0.5, -0.5],
                [0.5, 0.5, 0.5],
                [-0.5, 0.5, 0.5],
            ],
        },
        {
            normal: [0, -1, 0],
            corners: [
                [-0.5, -0.5, 0.5],
                [0.5, -0.5, 0.5],
                [0.5, -0.5, -0.5],
                [-0.5, -0.5, -0.5],
            ],
        },
        {
            normal: [0, 0, 1],
            corners: [
                [0.5, -0.5, 0.5],
                [-0.5, -0.5, 0.5],
                [-0.5, 0.5, 0.5],
                [0.5, 0.5, 0.5],
            ],
        },
        {
            normal: [0, 0, -1],
            corners: [
                [-0.5, -0.5, -0.5],
                [0.5, -0.5, -0.5],
                [0.5, 0.5, -0.5],
                [-0.5, 0.5, -0.5],
            ],
        },
    ];

    const TRIANGLE_ORDER = [0, 1, 2, 0, 2, 3];

    function compileShader(gl, type, source) {
        const shader = gl.createShader(type);
        gl.shaderSource(shader, source);
        gl.compileShader(shader);
        if (!gl.getShaderParameter(shader, gl.COMPILE_STATUS)) {
            const message = gl.getShaderInfoLog(shader) || "Unknown shader error";
            gl.deleteShader(shader);
            throw new Error(message);
        }
        return shader;
    }

    function createProgram(gl) {
        const vertexShader = compileShader(
            gl,
            gl.VERTEX_SHADER,
            `
                attribute vec3 aPosition;
                attribute vec3 aNormal;
                attribute vec4 aColor;

                uniform mat4 uViewProjection;
                uniform vec3 uEye;

                varying vec3 vNormal;
                varying vec4 vColor;
                varying float vDistance;

                void main() {
                    gl_Position = uViewProjection * vec4(aPosition, 1.0);
                    vNormal = aNormal;
                    vColor = aColor;
                    vDistance = distance(aPosition, uEye);
                }
            `
        );
        const fragmentShader = compileShader(
            gl,
            gl.FRAGMENT_SHADER,
            `
                precision mediump float;

                varying vec3 vNormal;
                varying vec4 vColor;
                varying float vDistance;

                void main() {
                    vec3 lightDirection = normalize(vec3(0.38, 0.88, 0.24));
                    float diffuse = max(dot(normalize(vNormal), lightDirection), 0.0);
                    float light = 0.46 + diffuse * 0.54;
                    vec3 litColor = vColor.rgb * light;
                    vec3 fogColor = vec3(0.025, 0.043, 0.066);
                    float fog = smoothstep(28.0, 72.0, vDistance);
                    gl_FragColor = vec4(mix(litColor, fogColor, fog), vColor.a);
                }
            `
        );
        const program = gl.createProgram();
        gl.attachShader(program, vertexShader);
        gl.attachShader(program, fragmentShader);
        gl.linkProgram(program);
        gl.deleteShader(vertexShader);
        gl.deleteShader(fragmentShader);
        if (!gl.getProgramParameter(program, gl.LINK_STATUS)) {
            const message = gl.getProgramInfoLog(program) || "Unknown link error";
            gl.deleteProgram(program);
            throw new Error(message);
        }
        return program;
    }

    function normalize(vector) {
        const length = Math.hypot(vector[0], vector[1], vector[2]) || 1;
        return [vector[0] / length, vector[1] / length, vector[2] / length];
    }

    function cross(a, b) {
        return [
            a[1] * b[2] - a[2] * b[1],
            a[2] * b[0] - a[0] * b[2],
            a[0] * b[1] - a[1] * b[0],
        ];
    }

    function dot(a, b) {
        return a[0] * b[0] + a[1] * b[1] + a[2] * b[2];
    }

    function perspective(fieldOfView, aspect, near, far) {
        const f = 1 / Math.tan(fieldOfView / 2);
        const rangeInverse = 1 / (near - far);
        return new Float32Array([
            f / aspect,
            0,
            0,
            0,
            0,
            f,
            0,
            0,
            0,
            0,
            (near + far) * rangeInverse,
            -1,
            0,
            0,
            near * far * rangeInverse * 2,
            0,
        ]);
    }

    function lookAt(eye, center, up) {
        const backward = normalize([
            eye[0] - center[0],
            eye[1] - center[1],
            eye[2] - center[2],
        ]);
        const right = normalize(cross(up, backward));
        const correctedUp = cross(backward, right);
        return new Float32Array([
            right[0],
            correctedUp[0],
            backward[0],
            0,
            right[1],
            correctedUp[1],
            backward[1],
            0,
            right[2],
            correctedUp[2],
            backward[2],
            0,
            -dot(right, eye),
            -dot(correctedUp, eye),
            -dot(backward, eye),
            1,
        ]);
    }

    function multiplyMatrices(a, b) {
        const result = new Float32Array(16);
        for (let column = 0; column < 4; column += 1) {
            for (let row = 0; row < 4; row += 1) {
                let value = 0;
                for (let index = 0; index < 4; index += 1) {
                    value += a[index * 4 + row] * b[column * 4 + index];
                }
                result[column * 4 + row] = value;
            }
        }
        return result;
    }

    function colorComponents(hexColor, alpha) {
        const match = /^#?([0-9a-f]{6})$/i.exec(String(hexColor || ""));
        const value = match ? Number.parseInt(match[1], 16) : 0xcccccc;
        return [
            ((value >> 16) & 255) / 255,
            ((value >> 8) & 255) / 255,
            (value & 255) / 255,
            alpha,
        ];
    }

    function appendBoxVertices(target, box) {
        const cosine = Math.cos(box.yaw);
        const sine = Math.sin(box.yaw);
        const color = colorComponents(box.color, box.alpha);

        FACE_DEFINITIONS.forEach((face) => {
            const normalX = cosine * face.normal[0] + sine * face.normal[2];
            const normalZ = -sine * face.normal[0] + cosine * face.normal[2];
            TRIANGLE_ORDER.forEach((cornerIndex) => {
                const corner = face.corners[cornerIndex];
                const localX = corner[0] * box.width;
                const localY = corner[1] * box.height;
                const localZ = corner[2] * box.depth;
                const worldX = box.x + cosine * localX + sine * localZ;
                const worldZ = box.z - sine * localX + cosine * localZ;
                target.push(
                    worldX,
                    box.y + localY,
                    worldZ,
                    normalX,
                    face.normal[1],
                    normalZ,
                    color[0],
                    color[1],
                    color[2],
                    color[3]
                );
            });
        });
    }

    function pointHitsBox(x, z, box) {
        const dx = x - box.x;
        const dz = z - box.z;
        const cosine = Math.cos(box.yaw);
        const sine = Math.sin(box.yaw);
        const localX = cosine * dx - sine * dz;
        const localZ = sine * dx + cosine * dz;
        return (
            Math.abs(localX) <= box.width / 2 + PLAYER_RADIUS &&
            Math.abs(localZ) <= box.depth / 2 + PLAYER_RADIUS
        );
    }

    function create(options) {
        if (!Core) {
            throw new Error("FloorPlanCore is required before walkthrough.js.");
        }
        const canvas = options.canvas;
        const overlay = options.overlay;
        const positionLabel = options.positionLabel;
        const lockHint = options.lockHint;
        const collisionButton = options.collisionButton;
        const getPlan = options.getPlan;
        const gl =
            canvas.getContext("webgl", {
                alpha: false,
                antialias: true,
                powerPreference: "high-performance",
            }) || canvas.getContext("experimental-webgl");

        if (!gl) {
            throw new Error("This browser does not provide WebGL.");
        }

        const program = createProgram(gl);
        const buffer = gl.createBuffer();
        const locations = {
            position: gl.getAttribLocation(program, "aPosition"),
            normal: gl.getAttribLocation(program, "aNormal"),
            color: gl.getAttribLocation(program, "aColor"),
            viewProjection: gl.getUniformLocation(program, "uViewProjection"),
            eye: gl.getUniformLocation(program, "uEye"),
        };

        let active = false;
        let animationFrame = 0;
        let lastFrameTime = 0;
        let scene = Core.buildPreviewScene(Core.createDemoPlan());
        let opaqueVertexCount = 0;
        let transparentVertexCount = 0;
        let collisionsEnabled = true;
        const keys = new Set();
        const camera = { x: 0, y: 1.65, z: 0, yaw: 0, pitch: 0 };

        function uploadScene() {
            scene = Core.buildPreviewScene(getPlan());
            const opaque = [];
            const transparent = [];
            scene.boxes.forEach((box) => {
                appendBoxVertices(box.alpha < 0.999 ? transparent : opaque, box);
            });
            opaqueVertexCount = opaque.length / VERTEX_STRIDE_FLOATS;
            transparentVertexCount = transparent.length / VERTEX_STRIDE_FLOATS;
            gl.bindBuffer(gl.ARRAY_BUFFER, buffer);
            gl.bufferData(
                gl.ARRAY_BUFFER,
                new Float32Array(opaque.concat(transparent)),
                gl.STATIC_DRAW
            );
        }

        function resetPosition() {
            Object.assign(camera, scene.spawn);
            updateHud();
        }

        function updateHud() {
            positionLabel.textContent = `X ${camera.x.toFixed(2)} m · Y ${camera.y.toFixed(
                2
            )} m · Z ${camera.z.toFixed(2)} m`;
            collisionButton.textContent = collisionsEnabled
                ? "Collision: on (C)"
                : "Collision: off (C)";
            collisionButton.classList.toggle("active", collisionsEnabled);
        }

        function setCollisionsEnabled(enabled) {
            collisionsEnabled = Boolean(enabled);
            updateHud();
        }

        function collides(x, z) {
            return scene.colliders.some((box) => pointHitsBox(x, z, box));
        }

        function updateMovement(deltaSeconds) {
            let forwardAmount = 0;
            let rightAmount = 0;
            if (keys.has("KeyW") || keys.has("ArrowUp")) forwardAmount += 1;
            if (keys.has("KeyS") || keys.has("ArrowDown")) forwardAmount -= 1;
            if (keys.has("KeyD") || keys.has("ArrowRight")) rightAmount += 1;
            if (keys.has("KeyA") || keys.has("ArrowLeft")) rightAmount -= 1;
            if (!forwardAmount && !rightAmount) {
                return;
            }

            const inputLength = Math.hypot(forwardAmount, rightAmount) || 1;
            forwardAmount /= inputLength;
            rightAmount /= inputLength;
            const speed =
                keys.has("ShiftLeft") || keys.has("ShiftRight")
                    ? SPRINT_SPEED
                    : WALK_SPEED;
            const forwardX = Math.sin(camera.yaw);
            const forwardZ = -Math.cos(camera.yaw);
            const rightX = Math.cos(camera.yaw);
            const rightZ = Math.sin(camera.yaw);
            const moveX =
                (forwardX * forwardAmount + rightX * rightAmount) *
                speed *
                deltaSeconds;
            const moveZ =
                (forwardZ * forwardAmount + rightZ * rightAmount) *
                speed *
                deltaSeconds;

            const nextX = camera.x + moveX;
            if (!collisionsEnabled || !collides(nextX, camera.z)) {
                camera.x = nextX;
            }
            const nextZ = camera.z + moveZ;
            if (!collisionsEnabled || !collides(camera.x, nextZ)) {
                camera.z = nextZ;
            }
            updateHud();
        }

        function resize() {
            const pixelRatio = Math.min(2, window.devicePixelRatio || 1);
            const width = Math.max(1, Math.round(canvas.clientWidth * pixelRatio));
            const height = Math.max(1, Math.round(canvas.clientHeight * pixelRatio));
            if (canvas.width !== width || canvas.height !== height) {
                canvas.width = width;
                canvas.height = height;
            }
            gl.viewport(0, 0, canvas.width, canvas.height);
        }

        function bindAttributes() {
            const stride = VERTEX_STRIDE_FLOATS * Float32Array.BYTES_PER_ELEMENT;
            gl.bindBuffer(gl.ARRAY_BUFFER, buffer);
            gl.enableVertexAttribArray(locations.position);
            gl.vertexAttribPointer(locations.position, 3, gl.FLOAT, false, stride, 0);
            gl.enableVertexAttribArray(locations.normal);
            gl.vertexAttribPointer(
                locations.normal,
                3,
                gl.FLOAT,
                false,
                stride,
                3 * Float32Array.BYTES_PER_ELEMENT
            );
            gl.enableVertexAttribArray(locations.color);
            gl.vertexAttribPointer(
                locations.color,
                4,
                gl.FLOAT,
                false,
                stride,
                6 * Float32Array.BYTES_PER_ELEMENT
            );
        }

        function render() {
            resize();
            gl.clearColor(0.025, 0.043, 0.066, 1);
            gl.clear(gl.COLOR_BUFFER_BIT | gl.DEPTH_BUFFER_BIT);
            gl.enable(gl.DEPTH_TEST);
            gl.disable(gl.CULL_FACE);
            gl.useProgram(program);
            bindAttributes();

            const cosPitch = Math.cos(camera.pitch);
            const direction = [
                Math.sin(camera.yaw) * cosPitch,
                Math.sin(camera.pitch),
                -Math.cos(camera.yaw) * cosPitch,
            ];
            const eye = [camera.x, camera.y, camera.z];
            const center = [
                camera.x + direction[0],
                camera.y + direction[1],
                camera.z + direction[2],
            ];
            const view = lookAt(eye, center, [0, 1, 0]);
            const projection = perspective(
                Math.PI / 3,
                canvas.width / canvas.height,
                0.04,
                120
            );
            const viewProjection = multiplyMatrices(projection, view);
            gl.uniformMatrix4fv(locations.viewProjection, false, viewProjection);
            gl.uniform3fv(locations.eye, eye);

            gl.disable(gl.BLEND);
            gl.depthMask(true);
            if (opaqueVertexCount) {
                gl.drawArrays(gl.TRIANGLES, 0, opaqueVertexCount);
            }

            if (transparentVertexCount) {
                gl.enable(gl.BLEND);
                gl.blendFunc(gl.SRC_ALPHA, gl.ONE_MINUS_SRC_ALPHA);
                gl.depthMask(false);
                gl.drawArrays(
                    gl.TRIANGLES,
                    opaqueVertexCount,
                    transparentVertexCount
                );
                gl.depthMask(true);
            }
        }

        function frame(time) {
            if (!active) {
                return;
            }
            const deltaSeconds = Math.min(
                0.05,
                Math.max(0, (time - lastFrameTime) / 1000)
            );
            lastFrameTime = time;
            updateMovement(deltaSeconds);
            render();
            animationFrame = window.requestAnimationFrame(frame);
        }

        function open() {
            uploadScene();
            resetPosition();
            active = true;
            overlay.hidden = false;
            lockHint.classList.remove("hidden");
            lastFrameTime = performance.now();
            window.cancelAnimationFrame(animationFrame);
            animationFrame = window.requestAnimationFrame(frame);
            canvas.focus();
        }

        function close() {
            active = false;
            keys.clear();
            overlay.hidden = true;
            window.cancelAnimationFrame(animationFrame);
            if (document.pointerLockElement === canvas) {
                document.exitPointerLock();
            }
        }

        function onKeyDown(event) {
            if (!active) {
                return;
            }
            if (
                [
                    "KeyW",
                    "KeyA",
                    "KeyS",
                    "KeyD",
                    "ArrowUp",
                    "ArrowDown",
                    "ArrowLeft",
                    "ArrowRight",
                    "ShiftLeft",
                    "ShiftRight",
                ].includes(event.code)
            ) {
                keys.add(event.code);
                event.preventDefault();
            }
            if (event.code === "KeyR" && !event.repeat) {
                resetPosition();
            }
            if (event.code === "KeyC" && !event.repeat) {
                setCollisionsEnabled(!collisionsEnabled);
            }
        }

        function onKeyUp(event) {
            keys.delete(event.code);
        }

        function onMouseMove(event) {
            if (!active || document.pointerLockElement !== canvas) {
                return;
            }
            camera.yaw += event.movementX * 0.0022;
            camera.pitch = Core.clamp(
                camera.pitch - event.movementY * 0.0022,
                -MAX_PITCH,
                MAX_PITCH
            );
        }

        canvas.addEventListener("click", () => {
            if (active && document.pointerLockElement !== canvas) {
                canvas.requestPointerLock();
            }
        });
        lockHint.addEventListener("click", () => {
            if (active && document.pointerLockElement !== canvas) {
                canvas.requestPointerLock();
            }
        });
        document.addEventListener("pointerlockchange", () => {
            lockHint.classList.toggle("hidden", document.pointerLockElement === canvas);
        });
        document.addEventListener("mousemove", onMouseMove);
        window.addEventListener("keydown", onKeyDown);
        window.addEventListener("keyup", onKeyUp);
        window.addEventListener("blur", () => keys.clear());
        window.addEventListener("resize", () => {
            if (active) {
                render();
            }
        });

        options.exitButton.addEventListener("click", close);
        options.resetButton.addEventListener("click", resetPosition);
        collisionButton.addEventListener("click", () => {
            setCollisionsEnabled(!collisionsEnabled);
        });

        return {
            open,
            close,
            isActive: () => active,
        };
    }

    return { create };
});
