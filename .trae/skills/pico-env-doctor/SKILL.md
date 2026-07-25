---
name: pico-env-doctor
description: >-
  Verify-first environment workflow for environment-dependent PICO Spatial
  execution. Must run before tasks that actually execute pico-cli, query the
  pico-spatial-knowledge MCP server, install or update plugin/host integration,
  or start PICO emulator/device workflows, and whenever the user asks to verify,
  doctor, health-check, repair, set up, update, or reconnect the PICO Spatial
  agent environment. Reuse a successful result once per host session unless the
  environment may have changed. Repair commands require explicit user
  authorization or a task that clearly asks for setup/update/start behavior.
  Checks whether pico-cli exists and reports the installed version/channel
  state, discovers supported commands before using doctor-style checks, and
  explains host restart requirements.
license: 'Apache-2.0'
allowed-tools: Bash(node --version) Bash(npm --version) Bash(command -v:*) Bash(pico-cli:*)
---

# pico-env-doctor — Verify-first PICO environment workflow

## Scope and trigger policy

Run this skill **first** only for environment-dependent execution tasks, such as:

- Running `pico-cli` commands as part of the requested work.
- Querying the `pico-spatial-knowledge` MCP server.
- Installing, setting up, updating, or reconnecting plugin/host integration.
- Starting PICO emulator/device workflows whose success depends on local setup.

Do **not** treat this skill as a universal gate for every Spatial task. It is
not required for purely local code reading, architecture discussion, static code
edits, or project analysis that does not depend on a live `pico-cli`, plugin,
or MCP environment.

When this skill is in scope, do not run the dependent CLI/MCP workflow or claim
the environment is healthy until the environment has been checked or the
remaining blocker is clearly reported.

Treat a broken or stale environment as the default assumption when the user mentions:

- `pico-cli` missing, old, failing, or returning unknown commands.
- Plugin setup, missing skills, or host integration problems.
- `pico-spatial-knowledge` MCP absent, disconnected, or failing to start.
- Requests to verify, doctor, health-check, repair, set up, update, or reconnect
  the PICO Spatial agent environment.

## Session reuse rule

Do not rerun the full environment check on every turn. Reuse the latest
successful `pico-env-doctor` result once per host session when all of the
following remain true:

- The current workspace and target host/tooling are the same.
- No setup/update/install/start command has run since the last check.
- No new failure signal suggests the environment changed.
- The user did not explicitly ask to re-verify.

Re-run the skill when any of those conditions is false.

## Repair authorization rule

Read-only diagnosis is allowed whenever this skill is in scope. Repair actions
such as `pico-cli setup`, `pico-cli plugin update`, package installation, or
other environment mutation must only run when one of the following is true:

- The user explicitly asked to repair, set up, update, reconnect, or fix the environment.
- The task itself clearly requires setup/update/start behavior to complete.

If the user asked only to verify or diagnose, stop after diagnosis and report
the exact repair command that would be appropriate instead of executing it.

## Capability-discovery rule

`pico-cli doctor` is the top-level environment summary when the installed build
exposes it. Treat it as a read-only orchestrator: it should report CLI runtime
health and point to module-specific doctors or fallback commands, not perform
setup, plugin updates, package installs, or long-running MCP launches by itself.

The CLI can grow module doctors over time, but a user's installed build may not
expose every command. Discover capabilities before using them; this keeps the
workflow useful across public, internal, old, and newly released CLI builds.

Start with bootstrap and help discovery when `pico-cli` is available:

```bash
node --version
npm --version
command -v pico-cli || echo "pico-cli not on PATH"
pico-cli --version
pico-cli --help
pico-cli setup --help
pico-cli plugin --help
pico-cli plugin update --help
pico-cli mcp --help
```

If the installed CLI exposes doctor commands, prefer JSON for machine-readable
results and plain output for a quick human summary:

```bash
pico-cli doctor --format json
pico-cli plugin doctor --format json
pico-cli mcp doctor --format json
pico-cli primer doctor --format json
```

Expected doctor JSON shape, when supported, follows the CLI `tool-result`
contract:

```json
{
  "tool_name": "doctor",
  "tool_status": "SUCCESS|PARTIAL|FAILED",
  "summary": "human-readable one-line result",
  "data": {
    "checks": [
      {
        "id": "runtime",
        "status": "ok|warn|fail|skip",
        "checks": []
      }
    ]
  },
  "errors": [],
  "resources": [],
  "meta": { "format": "json", "truncated": false }
}
```

Use status consistently:

- `SUCCESS`: checked areas are healthy.
- `PARTIAL`: CLI is usable, but some optional/module checks warned or were
  skipped.
- `FAILED`: a blocking runtime/setup issue prevents normal use.

If root or module doctor commands are absent, do **not** invent them and do not
treat their absence as a failed environment by itself. Fall back to the supported
checks and repair commands below, then report which doctor-style commands were
unavailable.

## The flow: bootstrap → discover → diagnose → authorized repair → re-verify

### Step 1 — Bootstrap the runtime and CLI

The CLI cannot diagnose itself if Node.js or `pico-cli` is missing.

```bash
node --version                       # Node.js 18+ required
npm --version                        # useful for public npm install/update paths
command -v pico-cli || echo "pico-cli not on PATH"
pico-cli --version                   # installed CLI version, if present
```

Heal the bootstrap layer before continuing:

- `node` missing or older than 18 → stop and tell the user to install Node.js 18+
  first.
- `pico-cli` not installed → install it through the user's normal channel.
  External/public users can use:
  ```bash
  npm install -g @picoxr/pico-cli
  ```
  Internal users should use their team's configured registry/scope instead.
- `pico-cli` installed → continue to version and capability discovery.

### Step 2 — Check the installed `pico-cli` version and channel

Decide the update path by distribution BEFORE running any npm command. Internal
and external builds ship under different scopes and registries, so a public
`npm view`/`npm install` run against an internal install compares to, or
installs, the wrong build and can break the environment.

1. **Prefer the CLI's own read-only doctor path.** If the installed CLI exposes
   `pico-cli doctor`, run it before repair commands. It reports the installed
   version/distribution and available module doctors, but it does **not** yet
   perform a live latest-version lookup. Treat its output as diagnosis and
   routing guidance; only run an install/update command after the output or the
   user's request makes that action explicit:
   ```bash
   pico-cli doctor --format json
   ```
2. **Internal / private install** (the CLI did not come from public npm): do NOT
   run `npm view` / `npm install` against a public package. Report the installed
   `pico-cli --version` and ask the user to update through their team's
   configured registry/channel.
3. **Confirmed public install only**: compare against public npm, and update only
   when the installed version is older:
   ```bash
   pico-cli --version
   npm view @picoxr/pico-cli version
   npm install -g @picoxr/pico-cli@latest
   ```
   Then re-run `pico-cli --version`. Never update when already current.

If you cannot tell which distribution is installed, do not guess a public
update — prefer `pico-cli doctor --format json`, or ask the user which channel
they installed from.

### Step 3 — Discover command support and run available doctors

Inspect the installed command surface before deciding which checks to run:

```bash
pico-cli --help
pico-cli doctor --help
pico-cli plugin --help
pico-cli plugin doctor --help
pico-cli mcp --help
pico-cli mcp doctor --help
pico-cli primer --help
pico-cli primer doctor --help
pico-cli setup --help
pico-cli plugin update --help
```

Then:

- If `pico-cli doctor` exists, run `pico-cli doctor --format json` first and
  treat it as the broadest read-only summary.
- If module doctors exist (`plugin doctor`, `mcp doctor`, `primer doctor`), run
  the module that matches the failure. Prefer `--format json` when supported.
- If a module doctor is absent or marked as skipped/planned by root doctor,
  continue with the concrete plugin/MCP/setup checks below and say that the
  installed CLI does not expose that module doctor yet.

Report real output. Do not assume the environment is fine because a help command
succeeded.

### Step 4 — Verify plugin and setup state

Use setup/update as the canonical repair path for host plugin registration,
skill installation, and setup-phase dependencies provisioned by the CLI.

**Authorization gate:** only enter this step when the repair authorization rule
above is satisfied. Otherwise, report the exact setup/update command that should
be run and why it is needed, but do not execute it.

For a known host, repair that host:

```bash
pico-cli setup --tool claude-code --plugin pico-spatial-agentic-tools
pico-cli setup --tool codex --plugin pico-spatial-agentic-tools
pico-cli setup --tool copilot --plugin pico-spatial-agentic-tools
pico-cli setup --tool cursor --plugin pico-spatial-agentic-tools
pico-cli setup --tool traecli --plugin pico-spatial-agentic-tools
```

For “fix everything available on this machine” requests, use:

```bash
pico-cli setup --tool all --plugin pico-spatial-agentic-tools
pico-cli plugin update --tool all --plugin pico-spatial-agentic-tools
```

For a direct per-host refresh flow, use:

```bash
pico-cli plugin update --tool traecli --plugin pico-spatial-agentic-tools
```

Treat a missing host CLI as an actionable skipped host, not as proof that every
host is broken. Tell the user which host command is missing and rerun the
matching setup command after they install that host.

`setup` is the right fallback when plugin setup dependencies are missing because
it owns the initial provisioning path for the plugin, skills, MCP metadata, and
supporting setup resources. Prefer rerunning `setup` over manual edits to host
plugin files.

#### Setup-phase dependencies

`pico-cli setup` also provisions the dependencies spatial skills rely on. These
installs are non-fatal — `setup` continues and only warns if one fails — so read
the setup log, find the dependency that warned, and fix that specific one instead
of only re-running `setup` blindly. A single failed dependency does not mean the
whole environment is broken.

| Dependency                          | What it provides                                     | If it warns/fails                                                                                                                             |
| ----------------------------------- | ---------------------------------------------------- | --------------------------------------------------------------------------------------------------------------------------------------------- |
| Knowledge pack (agent-vault/primer) | The Spatial SDK knowledge the MCP server serves      | Re-run `setup`; persistent failures are usually network/registry reachability for the knowledge-pack download — fix connectivity, then retry. |
| `uv` (Python runtime/manager)       | Python environment used by graphify and Python tools | Usually missing network or Python toolchain; ensure outbound network and a working Python, then re-run `setup`.                               |
| graphify                            | Knowledge-graph tooling (depends on `uv`/Python)     | Fix `uv`/Python first, then re-run `setup`.                                                                                                   |
| profiler                            | Perfetto/profiler support for `pico-cli perf` work   | Non-blocking for most tasks; re-run `setup` (or `pico-cli doctor`) when perf workflows are needed.                                            |

### Step 5 — Verify MCP visibility and launch path

The plugin declares the `pico-spatial-knowledge` server in `.mcp.json`; the host
launches it via the command declared there. To reproduce the launch yourself,
prefer the installed binary (scope-agnostic — works regardless of how `pico-cli`
was installed):

```bash
pico-cli mcp:spatial-knowledge
```

This is a stdio server: a healthy run starts and then waits for input (no
immediate exit). Do not run it as an unbounded foreground check in an agent Bash
tool. Use it only as a bounded smoke test when reproducing MCP startup failures:
confirm it starts, then stop it; if it exits with an error, read that error.
When inspecting a plugin payload, check that `.mcp.json` exists, parses, and
contains the expected `pico-spatial-knowledge` server entry. In an active agent
host, prefer host-visible MCP status where available, such as `/mcp`, `/doctor`,
`claude --debug mcp`, or the MCP tools exposed in the current session.

Important: `pico-cli mcp doctor` currently verifies whether the local MCP server
can be resolved and started from the CLI side. It does **not** prove that a
host like Claude, Codex, Cursor, or Trae has already reloaded that MCP config
into the current session. A green MCP doctor still requires a host restart or a
new session before concluding that the MCP is visible inside the host.

If setup or plugin update changed `.mcp.json` or host plugin registration, the
current session may not see the change. Tell the user to fully restart the host
or open a new agent session before declaring the MCP fixed.

#### MCP connection root causes → fix

When the server is missing or will not connect, match the symptom to the cause
and apply the targeted fix instead of only re-running setup and hoping:

| Root cause                                           | How to spot it                                 | Fix                                                                                                                         |
| ---------------------------------------------------- | ---------------------------------------------- | --------------------------------------------------------------------------------------------------------------------------- |
| Host not restarted after setup/update                | MCP only changed after install in this session | Fully restart the host or open a new session, then re-check.                                                                |
| `node`/`npx` not on `PATH`                           | `node --version` or `command -v npx` fails     | Install Node.js 18+ / fix `PATH`, then retry.                                                                               |
| Launcher cannot fetch the package (offline/registry) | Standalone launch errors while downloading     | Fix network or registry reachability, or install `pico-cli` globally so the host uses the local binary instead of fetching. |
| `pico-cli` too old; `mcp:spatial-knowledge` missing  | `pico-cli mcp --help` lacks the subcommand     | Update `pico-cli` (Step 2), then retry.                                                                                     |
| Knowledge-graph data not provisioned                 | Launch starts but errors on missing graph/data | Re-run `pico-cli setup` so the knowledge pack installs (see setup dependencies), then retry.                                |
| `.mcp.json` missing or lacks the server entry        | Inspect the plugin payload `.mcp.json`         | Re-run `setup` for the host to restore the plugin and its `.mcp.json`, then restart.                                        |

Report which root cause applied; do not just say "restarted and hoped".

If MCP still fails after setup/update plus restart, collect evidence with:

```bash
pico-cli plugin audit
```

Do not collect transcripts by default. Use transcript mode only when the user
explicitly approves sharing reviewed support evidence.

### Step 6 — Re-verify

After every authorized repair:

1. Re-run the relevant version/help/doctor checks.
2. Re-run host setup/update only if the previous output still shows a concrete
   gap.
3. Ask for or inspect host-visible skill/MCP status after a restart/new session.
4. Report remaining blockers instead of claiming success when the host has not
   reloaded yet.

## Findings → fix quick map

| Finding                                          | Fix                                                                                                                                             |
| ------------------------------------------------ | ----------------------------------------------------------------------------------------------------------------------------------------------- |
| Node missing or older than 18                    | Install Node.js 18+ before continuing.                                                                                                          |
| `pico-cli` not on `PATH`                         | Public: `npm install -g @picoxr/pico-cli`; internal: team channel.                                                                              |
| `pico-cli` version needs review                  | Use `pico-cli doctor --format json` plus `pico-cli --version`; only public installs should compare against `npm view @picoxr/pico-cli version`. |
| Unknown `pico-cli` command or option             | Run help discovery; use only commands the installed CLI exposes.                                                                                |
| Plugin not installed or skills missing           | `pico-cli setup --tool <host> --plugin pico-spatial-agentic-tools`.                                                                             |
| Plugin installed but stale                       | `pico-cli plugin update --tool all --plugin pico-spatial-agentic-tools`.                                                                        |
| Setup dependency or knowledge resource missing   | Re-run `pico-cli setup --tool <host> --plugin pico-spatial-agentic-tools`.                                                                      |
| MCP server absent or disconnected                | Re-run setup/update, restart the host/new session, then inspect MCP status.                                                                     |
| Still broken after supported repair plus restart | Run `pico-cli plugin audit` and report the support bundle path.                                                                                 |

## Self-heal rules and safe defaults

- Verify before fixing; re-verify after each fix.
- Discover the installed command surface before using doctor-style commands.
- Update `pico-cli` only when version evidence shows it is outdated.
- Use `setup` and `plugin update` for repair because they are intended to be
  repeatable and host-aware.
- Reuse a healthy result within the same host session instead of rerunning this
  skill by reflex.
- Do not run repair commands without explicit user authorization or a task that
  clearly requires setup/update/start behavior.
- Restart the host or open a new session after plugin/MCP changes before
  declaring skills or MCP fixed.
- Do not hand-edit host plugin manifests unless `pico-cli setup` cannot support
  the host and the user explicitly asks for manual setup guidance.

## Anti-patterns

- Starting environment-dependent CLI/MCP/emulator work without this environment
  check when setup is suspect.
- Running `pico-cli doctor`, `pico-cli plugin doctor`, `pico-cli mcp doctor`, or
  `pico-cli primer doctor` without first confirming the installed CLI exposes
  those commands.
- Declaring MCP fixed in the same session that changed `.mcp.json` without a host
  restart/new session.
- Replacing `pico-cli setup` with manual plugin file edits as the first repair.
- Running `pico-cli setup` / `pico-cli plugin update` automatically during a
  read-only verify/doctor request.
- Updating from a guessed internal package/scope/registry.

## Reporting format

When you run this skill, report:

1. **Environment status**: Node/npm, `pico-cli` path, installed version, latest
   version if checked, and whether an update was needed.
2. **Capabilities discovered**: which setup/plugin/MCP/doctor commands the
   installed CLI exposes.
3. **Findings**: each concrete gap with the relevant command output.
4. **Actions taken**: exact install, setup, update, or audit commands and their
   results.
5. **Restart needed?**: whether the user must restart the host or open a new
   session for skills/MCP to load.
6. **Next step / handoff**: the smallest next action, or the specialized skill to
   continue with (`pico-cli`, `plugin-audit`, `spatial-emulator-usage`,
   `spatial-app-onboarding`, etc.).

## Related skills

- `pico-cli` — generic CLI usage and command-family selection once healthy.
- `plugin-audit` — deeper local support-bundle workflow for setup/host issues.
- `spatial-emulator-usage` — emulator/device lifecycle once prerequisites pass.
- `spatial-app-onboarding` — project scaffolding after the environment is healthy.
