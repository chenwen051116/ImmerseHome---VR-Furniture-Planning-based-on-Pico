---
name: plugin-audit
description: Export an authorized Claude Code transcript and extract pico-spatial-agentic-tools skill/MCP usage records into a local support bundle.
license: 'Apache-2.0'
allowed-tools: Bash(pico-cli plugin audit *) Bash(claude --version) Bash(codex --version) Bash(copilot --version)
---

# PICO Plugin Audit

Use this skill when the user explicitly wants a local support bundle that includes:

1. the selected Claude Code session transcript, and
2. extracted `pico-spatial-agentic-tools` skill/MCP usage records from that transcript.

Do **not** use the word `diagnose` as this skill's invocation name. Use `plugin-audit`.

## Authorization First

Before exporting a transcript, tell the user:

- the transcript may contain prompts, source code, file contents, tool arguments, tool outputs, MCP payloads, local paths, and secrets accidentally pasted into the session;
- the export is local-only and not automatically uploaded;
- they should review `session-transcript.jsonl`, `pico-spatial-agentic-tools-usage.json`, and `redaction-notes.md` before sharing externally.

Only run transcript export after the user has authorized it. For this skill invocation, the user's request to audit/export transcript counts as authorization.

## What This Skill Should Do

Run from the user's project root:

```bash
pico-cli plugin audit --transcript --yes
```

If the user provides a specific Claude Code session id, use:

```bash
pico-cli plugin audit --session <session-id> --transcript --yes
```

Then report the exact output paths for:

- `session-transcript.jsonl`
- `pico-spatial-agentic-tools-usage.json`
- `pico-spatial-agentic-tools-usage.md`

## Output Bundle

By default, the command writes a timestamped directory under:

```text
.pico-spatial-agentic-tools/plugin-audit/
```

Important files:

- `summary.md`: bundle overview and file locations
- `manifest.json`: privacy flags and output file paths
- `session-transcript.jsonl`: authorized local transcript export with light token-pattern redaction
- `pico-spatial-agentic-tools-usage.json`: structured usage records extracted from the transcript
- `pico-spatial-agentic-tools-usage.md`: human-readable usage table
- `plugin-config.json`: plugin metadata and skill list
- `redaction-notes.md`: review guidance before sharing

## Privacy Rules

- Never upload the bundle automatically.
- Do not paste the full transcript into chat.
- Remind the user to review and redact before sharing.
- If no transcript is found, ask the user for a Claude Code session id or to run from the project directory that produced the session.
