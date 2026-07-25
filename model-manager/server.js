// Model Manager — tiny local web tool to push/clear files in the Room Planner app's
// models folder on the PICO emulator (adb-side storage). Zero dependencies.
//
// Run:  node model-manager/server.js   (or double-click start.cmd)
// Open: http://localhost:8931
//
// Config via env: ADB (path to adb.exe), MM_PORT, MM_PKG (app package), MM_DIR (remote subdir).

const http = require("http");
const fs = require("fs");
const path = require("path");
const { execFile } = require("child_process");

const PORT = Number(process.env.MM_PORT || 8931);
const PKG = process.env.MM_PKG || "com.example.testfull";
const REMOTE_DIR =
    process.env.MM_DIR || `/sdcard/Android/data/${PKG}/files/models`;
const MAX_UPLOAD_BYTES = 300 * 1024 * 1024;
const TMP_DIR = path.join(__dirname, ".tmp");

const ADB_CANDIDATES = [
    process.env.ADB,
    process.env.USERPROFILE &&
        path.join(process.env.USERPROFILE, "AppData", "Local", "Android", "Sdk", "platform-tools", "adb.exe"),
    process.env.ANDROID_SDK_ROOT &&
        path.join(process.env.ANDROID_SDK_ROOT, "platform-tools", "adb.exe"),
    "C:\\Users\\Acer\\AppData\\Local\\Android\\Sdk\\platform-tools\\adb.exe",
    "adb",
].filter(Boolean);

let adbPath = null;

function runAdb(args, timeoutMs = 30000) {
    return new Promise((resolve, reject) => {
        execFile(
            adbPath,
            args,
            { timeout: timeoutMs, maxBuffer: 16 * 1024 * 1024, windowsHide: true },
            (error, stdout, stderr) => {
                if (error) {
                    reject(new Error((stderr || error.message).toString().trim()));
                } else {
                    resolve(stdout.toString());
                }
            }
        );
    });
}

async function detectAdb() {
    for (const candidate of ADB_CANDIDATES) {
        adbPath = candidate;
        try {
            await runAdb(["version"], 8000);
            return candidate;
        } catch (_) {
            // try next
        }
    }
    adbPath = ADB_CANDIDATES[ADB_CANDIDATES.length - 1];
    return null;
}

// --- File name safety: basename only, no traversal, no shell-breaking characters. ---
function sanitizeName(raw) {
    if (!raw) return null;
    const name = path.basename(String(raw)).trim();
    if (!name || name === "." || name === "..") return null;
    if (/['"\\/$`;&|<>]/.test(name)) return null;
    return name;
}

function remotePath(name) {
    return `${REMOTE_DIR}/${name}`;
}

async function ensureRemoteDir() {
    await runAdb(["shell", `mkdir -p '${REMOTE_DIR}'`]);
}

async function listModels() {
    await ensureRemoteDir();
    const out = await runAdb(["shell", `ls -la '${REMOTE_DIR}'`]);
    const files = [];
    for (const line of out.split("\n")) {
        const trimmed = line.trim();
        if (!trimmed.startsWith("-")) continue;
        const parts = trimmed.split(/\s+/);
        if (parts.length < 8) continue;
        const size = Number(parts[4]);
        const name = parts.slice(7).join(" ");
        if (name && Number.isFinite(size)) files.push({ name, size });
    }
    files.sort((a, b) => a.name.localeCompare(b.name));
    return files;
}

async function deviceOnline() {
    try {
        await runAdb(["shell", "true"], 8000);
        return true;
    } catch (_) {
        return false;
    }
}

function readBody(req, limit) {
    return new Promise((resolve, reject) => {
        const chunks = [];
        let total = 0;
        req.on("data", (chunk) => {
            total += chunk.length;
            if (total > limit) {
                reject(new Error("upload too large"));
                req.destroy();
                return;
            }
            chunks.push(chunk);
        });
        req.on("end", () => resolve(Buffer.concat(chunks)));
        req.on("error", reject);
    });
}

function sendJson(res, status, payload) {
    const body = JSON.stringify(payload);
    res.writeHead(status, {
        "Content-Type": "application/json; charset=utf-8",
        "Content-Length": Buffer.byteLength(body),
    });
    res.end(body);
}

const server = http.createServer(async (req, res) => {
    const url = new URL(req.url, `http://${req.headers.host || "localhost"}`);
    try {
        if (req.method === "GET" && (url.pathname === "/" || url.pathname === "/index.html")) {
            const html = fs.readFileSync(path.join(__dirname, "index.html"));
            res.writeHead(200, { "Content-Type": "text/html; charset=utf-8" });
            res.end(html);
            return;
        }

        if (req.method === "GET" && url.pathname === "/api/models") {
            const [online, files] = await Promise.all([deviceOnline(), listModels()]);
            sendJson(res, 200, { online, dir: REMOTE_DIR, files });
            return;
        }

        if (req.method === "POST" && url.pathname === "/api/push") {
            const name = sanitizeName(url.searchParams.get("name"));
            if (!name) return sendJson(res, 400, { error: "bad file name" });
            const body = await readBody(req, MAX_UPLOAD_BYTES);
            if (body.length === 0) return sendJson(res, 400, { error: "empty file" });
            fs.mkdirSync(TMP_DIR, { recursive: true });
            const tmp = path.join(TMP_DIR, `upload-${Date.now()}-${name}`);
            fs.writeFileSync(tmp, body);
            try {
                await ensureRemoteDir();
                await runAdb(["push", tmp, remotePath(name)], 120000);
            } finally {
                fs.rmSync(tmp, { force: true });
            }
            sendJson(res, 200, { ok: true, name, size: body.length });
            return;
        }

        if (req.method === "POST" && url.pathname === "/api/delete") {
            const name = sanitizeName(url.searchParams.get("name"));
            if (!name) return sendJson(res, 400, { error: "bad file name" });
            await runAdb(["shell", `rm -f '${remotePath(name)}'`]);
            sendJson(res, 200, { ok: true, name });
            return;
        }

        if (req.method === "POST" && url.pathname === "/api/clear") {
            await ensureRemoteDir();
            try {
                await runAdb(["shell", `rm -f '${REMOTE_DIR}'/*`]);
            } catch (error) {
                // rm fails on an empty glob — that is still "cleared".
                if (!/No such file/i.test(error.message)) throw error;
            }
            sendJson(res, 200, { ok: true });
            return;
        }

        sendJson(res, 404, { error: "not found" });
    } catch (error) {
        sendJson(res, 500, { error: error.message });
    }
});

(async () => {
    const found = await detectAdb();
    server.listen(PORT, "127.0.0.1", () => {
        console.log(`Model Manager: http://localhost:${PORT}`);
        console.log(`adb: ${found ? adbPath : "NOT FOUND (set the ADB env var)"}`);
        console.log(`remote dir: ${REMOTE_DIR}`);
    });
})();
