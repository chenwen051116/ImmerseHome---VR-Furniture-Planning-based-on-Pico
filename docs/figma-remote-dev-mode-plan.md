# Plan: Let the dev machine's agent read a Figma design that lives on another computer

**Goal**: the Kimi Code agent on the dev machine (`D:\Pico Dev\TestFull`, the PICO Room
Planner project) can read a Figma design that is open in Figma desktop on a *different*
computer ("the design machine"), and implement it (VR panel UI / web tools).

**Key fact**: Figma's Dev Mode MCP server runs inside the Figma desktop app, listens only on
`http://127.0.0.1:3845/mcp`, and serves the *currently selected frame*. So the design
machine must (a) run that server and (b) make the port reachable by the dev machine over a
secure tunnel. A person on the design machine selects frames on request.

Topology:

```
dev machine (agent)  ──ssh -L 3845──►  design machine (Figma desktop + MCP on 127.0.0.1:3845)
```

---

## Part A — on the design machine (the one with Figma)

1. Install/open **Figma desktop**, log in, open the design file. View access is enough.
2. Enable the local MCP server: **Figma menu → Preferences → Enable Dev Mode MCP server**
   (name varies slightly by version; it's under the Dev Mode section).
3. Verify it is up, in a terminal on the design machine:
   ```bash
   curl http://127.0.0.1:3845/mcp
   ```
   Any HTTP response (even 405/406) means it listens. No response = MCP not enabled.
4. Make the machine reachable via SSH:
   - **Windows**: Settings → Apps → Optional features → Add → *OpenSSH Server*, then
     `Start-Service sshd` in an admin PowerShell (and `Set-Service sshd -StartupType Automatic`).
   - **macOS**: System Settings → Sharing → enable *Remote Login*.
   - Note the machine's LAN IP and the login username.
   - Alternative to SSH: install **Tailscale** on both machines; then the dev machine can
     reach the design machine at its Tailscale IP (still forward the port — see B-1, the MCP
     server only listens on localhost, so keep using an `ssh -L` style forward over Tailscale).
5. Leave Figma open. When the agent needs a frame, **select it in Figma** (the MCP serves the
   current selection). Ask the agent which frame it wants, click it, tell it "selected".

## Part B — on the dev machine (this computer, with the agent)

1. Open the tunnel (keep this terminal running):
   ```bash
   ssh -L 3845:127.0.0.1:3845 <user>@<design-machine-ip>
   ```
2. Verify from the dev machine: `curl http://127.0.0.1:3845/mcp` → any HTTP response = good.
3. Tell the agent (in this project): *"add the Figma Dev Mode MCP server at
   http://127.0.0.1:3845/mcp to your config"* — it will register it via its config tooling.
4. Working loop, repeat per screen:
   1. On the design machine, select the frame.
   2. Ask the agent: *"read the Figma selection and implement it for <target>"* where target
      is e.g. the Room Planner panel (`FloorPlanDesigner.kt`, Pico design kit) or a web tool
      (`floor-plan-tool/`, `model-manager/`).
   3. The agent pulls layout/tokens/assets via MCP, implements, then shows a screenshot
      (emulator screencap or browser capture) for you to compare against Figma.

## Part C — fallback: no tunnel needed (Figma REST API)

If SSH/Tailscale is a hassle, skip MCP entirely:

1. On any Figma account with view access to the file: **Settings → Security → Generate new
   token** (personal access token).
2. Copy the file link; the key is the long id in `figma.com/design/<KEY>/...` (older links:
   `/file/<KEY>/`).
3. Hand the agent: the token + the link. It will call `GET /v1/files/<KEY>` and
   `/v1/images/<KEY>` itself — works from any machine, no desktop app, no port forwarding.
4. Store the token in `local.properties` (gitignored), never in source.

## Security notes

- The MCP endpoint has **no authentication** — only ever reach it through the SSH/Tailscale
  tunnel. Do not bind it to a LAN interface or expose it with a public tunnel (ngrok etc.).
- The REST token is a secret: `local.properties` or an env var, never committed.

## Acceptance checklist

- [ ] `curl http://127.0.0.1:3845/mcp` responds on the design machine
- [ ] same curl responds on the dev machine through the tunnel
- [ ] agent lists the Figma MCP among its tools and reads a selected frame
- [ ] first screen implemented and screenshot-compared against the design
