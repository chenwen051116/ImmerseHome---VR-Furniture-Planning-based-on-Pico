const https = require("https");

const MODELS = [
    "gpt-5.4-pro",
    "gpt-5.4",
    "gpt-5.4-xhigh",
    "gpt-5.4-mini",
    "gpt-5",
    "gpt-5.1",
    "gpt-5.2",
    "gpt-5.5",
    "claude-opus-4-8",
    "claude-opus-4-7",
    "claude-opus-5",
    "claude-sonnet-5",
    "claude-sonnet-4-6",
    "o3",
    "o3-pro",
    "o4-mini",
    "gemini-2.5-pro",
    "gemini-3-pro-preview",
    "deepseek-v3.2",
    "deepseek-r1",
    "grok-4",
    "grok-4.5",
    "gpt-4o",
    "gpt-4.1",
    "chatgpt-4o-latest",
];

const TIMEOUT = 60000; // 1 min per model

function testModel(model) {
    return new Promise((resolve) => {
        const data = JSON.stringify({
            model: model,
            messages: [{ role: "user", content: "hi" }],
        });
        const start = Date.now();
        const req = https.request(
            {
                hostname: "api.openai-next.com",
                port: 443,
                path: "/v1/chat/completions",
                method: "POST",
                headers: {
                    Authorization:
                        "Bearer sk-BDmU7qDaIsfH0L8yCdCeBdE5Eb0b47Af92D5Ae8469A49c3f",
                    "Content-Type": "application/json",
                    "Content-Length": Buffer.byteLength(data),
                },
                timeout: TIMEOUT,
            },
            (res) => {
                let b = "";
                res.on("data", (c) => (b += c));
                res.on("end", () => {
                    const ms = Date.now() - start;
                    let ok = res.statusCode === 200;
                    let detail = b.slice(0, 150);
                    resolve({ model, status: res.statusCode, ms, ok, detail });
                });
            }
        );
        req.on("error", (e) => {
            const ms = Date.now() - start;
            resolve({ model, status: 0, ms, ok: false, detail: `${e.code}` });
        });
        req.on("timeout", () => {
            const ms = Date.now() - start;
            req.destroy();
            resolve({ model, status: 0, ms, ok: false, detail: "TIMEOUT" });
        });
        req.write(data);
        req.end();
    });
}

async function main() {
    console.log(`Testing ${MODELS.length} models (2min timeout each)...\n`);
    const results = [];
    for (const model of MODELS) {
        process.stdout.write(`${model.padEnd(28)} ... `);
        const r = await testModel(model);
        results.push(r);
        const tag = r.ok ? "OK" : r.status === 0 ? "FAIL" : `HTTP ${r.status}`;
        console.log(`${tag.padEnd(10)} ${String(r.ms).padStart(7)}ms  ${r.detail.slice(0, 80)}`);
    }
    console.log("\n=== WORKING MODELS ===");
    const working = results.filter((r) => r.ok);
    if (working.length === 0) {
        console.log("(none)");
    } else {
        working
            .sort((a, b) => a.ms - b.ms)
            .forEach((r) => console.log(`  ${r.model.padEnd(28)} ${r.ms}ms`));
    }
    console.log(`\n${working.length}/${results.length} models working`);
}

main();
