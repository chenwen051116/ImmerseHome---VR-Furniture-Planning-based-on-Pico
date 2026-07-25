const https = require("https");
const model = process.argv[2] || "gpt-5";
const data = JSON.stringify({
    model: model,
    messages: [
        { role: "user", content: 'Return this JSON: {"ok":true}' },
    ],
    response_format: { type: "json_object" },
});
console.log(`Testing ${model}...`);
const start = Date.now();
const req = https.request(
    {
        hostname: "api.openai-next.com",
        port: 443,
        path: "/v1/chat/completions",
        method: "POST",
        headers: {
            Authorization: "Bearer sk-BDmU7qDaIsfH0L8yCdCeBdE5Eb0b47Af92D5Ae8469A49c3f",
            "Content-Type": "application/json",
            "Content-Length": Buffer.byteLength(data),
        },
        timeout: 120000,
    },
    (res) => {
        let b = "";
        res.on("data", (c) => (b += c));
        res.on("end", () => {
            console.log(`STATUS: ${res.statusCode} (${Date.now() - start}ms)`);
            console.log("BODY:", b.slice(0, 400));
        });
    }
);
req.on("error", (e) => console.log(`ERR: ${e.code} ${e.message} (${Date.now() - start}ms)`));
req.on("timeout", () => {
    console.log(`TIMEOUT (${Date.now() - start}ms)`);
    req.destroy();
});
req.write(data);
req.end();
