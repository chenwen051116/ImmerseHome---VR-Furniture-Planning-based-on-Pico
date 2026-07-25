// AI API proxy — bridges the PICO emulator (no internet/default route) to the
// OpenAI-compatible relay on the host. The emulator reaches 10.0.2.2 (host
// loopback) over its local subnet route; this proxy forwards to the real HTTPS endpoint.
//
// Run:  node model-manager/ai-proxy.js
// Then set in local.properties:  ai.api.base=http://10.0.2.2:8932/v1

const http = require("http");
const https = require("https");
const fs = require("fs");
const path = require("path");

const PORT = 8932;
const TARGET_PORT = 443;

// Upstream timeout: flagship reasoning models (gpt-5.x) can take 2+ minutes to respond.
// Set this above the app's READ_TIMEOUT_MS (300s) so the proxy never cuts off before the
// app does. NOTE: the relay's Cloudflare edge still has its own ~100s cutoff and will
// return HTTP 524 if the origin doesn't respond in time — that one is not under our control.
const UPSTREAM_TIMEOUT_MS = 330_000;

// Read the real upstream host from ai.upstream.host (fallback: api.openai-next.com).
const propsPath = path.join(__dirname, "..", "local.properties");
let upstreamHost = "api.openai-next.com";
if (fs.existsSync(propsPath)) {
    const props = fs.readFileSync(propsPath, "utf8");
    const match = props.match(/^ai\.upstream\.host\s*=\s*(.+)/m);
    if (match) upstreamHost = match[1].trim();
}

const server = http.createServer((req, res) => {
    // Collect request body
    const chunks = [];
    req.on("data", (chunk) => chunks.push(chunk));
    req.on("end", () => {
        const body = Buffer.concat(chunks);
        // Forward to HTTPS upstream
        const options = {
            hostname: upstreamHost,
            port: TARGET_PORT,
            path: req.url,
            method: req.method,
            headers: {
                ...req.headers,
                Host: upstreamHost,
            },
        };
        const proxyReq = https.request(options, (proxyRes) => {
            res.writeHead(proxyRes.statusCode, proxyRes.headers);
            proxyRes.pipe(res);
        });
        // Explicitly keep the upstream socket alive for UPSTREAM_TIMEOUT_MS so the proxy
        // doesn't time out before the app does on slow flagship-model responses.
        proxyReq.setTimeout(UPSTREAM_TIMEOUT_MS, () => {
            proxyReq.destroy(new Error(`upstream timed out after ${UPSTREAM_TIMEOUT_MS}ms`));
        });
        proxyReq.on("error", (err) => {
            console.error(`[ai-proxy] upstream error: ${err.message}`);
            res.writeHead(502, { "Content-Type": "application/json" });
            res.end(JSON.stringify({ error: { message: `Proxy upstream error: ${err.message}` } }));
        });
        if (body.length > 0) proxyReq.write(body);
        proxyReq.end();
    });
});

server.listen(PORT, "127.0.0.1", () => {
    console.log(`[ai-proxy] listening on http://127.0.0.1:${PORT} -> https://${upstreamHost}`);
    console.log(`[ai-proxy] set ai.api.base=http://10.0.2.2:${PORT}/v1 in local.properties`);
});
