# Home VPS (Phase 1)

Turn an Android phone into a small server. Open the app, start the server, and your
phone exposes a session-manager HTTP API that agents and tools can talk to. Each
session is a long-lived shell (like a Termux/tmux session): start one by name, run
commands in it, stream output line by line, and stop it with `exit`.

The main API server **auto-starts on reboot** (foreground service + boot receiver),
so the VPS comes back up on its own.

## What Phase 1 includes

- Foreground service that keeps the server alive in the background
- Boot receiver: server restarts after reboot (if it was running + autostart is on)
- Session-manager HTTP API with Bearer-token auth
- Line-by-line output streaming over SSE and over WebSocket
- Dashboard: start/stop, local IP + endpoint, API key (copy/regenerate), battery
  exemption, autostart toggle

## Ubuntu (Phase 2)

The APK built by GitHub Actions contains proot (from Termux's package repo) and the
Ubuntu 24.04 base image. Tap **Install Ubuntu** on the dashboard once (1-2 minutes).
After that every session runs Ubuntu bash as root (fake root via proot), so you can:

```
apt update && apt install -y python3 nodejs npm git curl nginx
```

Until Ubuntu is installed, sessions fall back to the Android shell. `GET /health`
reports which one is active (`"shell": "ubuntu"` or `"android-sh"`).

Not possible without root: systemd, Docker, kernel modules, ports below 1024.
proot is somewhat slower than native because it intercepts system calls.
Only arm64 phones are supported.

## Build

Push to GitHub; the workflow in `.github/workflows/build.yml` fetches proot and the
Ubuntu image (`scripts/fetch-linux.sh`), builds, and uploads the APK as the
`HomeVPS-debug` artifact. A local build works too, but run
`bash scripts/fetch-linux.sh` first (Linux/WSL with patchelf), or the APK will not
include Ubuntu.

No external libraries. `org.json` ships with Android. `minSdk 24`, `targetSdk 34`.

## API

Base URL: `http://<phone-ip>:8090` (port is configurable).
Auth: send `Authorization: Bearer <API_KEY>`, `X-API-Key: <API_KEY>`, or `?key=<API_KEY>`
(the query form exists for browsers, which cannot set headers on a WebSocket).
The key is shown on the dashboard.

| Method | Path | Body | Purpose |
|---|---|---|---|
| GET | `/` or `/terminal` | — | Web terminal page (no auth; the page asks for the key) |
| GET | `/health` | — | Liveness, uptime, session count, active shell (no auth) |
| GET | `/sessions` | — | List sessions + status |
| POST | `/sessions` | `{"name","cwd"}` | Start a named session |
| DELETE | `/sessions/{name}` | — | Force-stop a session |
| POST | `/sessions/{name}/exec` | `{"command","timeout","stream"}` | Run a command |
| POST | `/sessions/{name}/input` | `{"data"}` | Send raw stdin (answer prompts, keys) |
| POST | `/sessions/{name}/interrupt` | — | Ctrl+C the running command |
| GET | `/sessions/{name}/output?since=N&tail=M` | — | Replay buffered output |
| GET | `/sessions/{name}/stream` | — | WebSocket: live terminal |

### Web terminal

Open `http://<phone-ip>:8090/` in any browser (or tap **Open web terminal** in the app,
which passes the key for you). Features: one tab per session, live output with
colours, stderr in red, `[exit N]` after failed commands, command history (↑ ↓),
Ctrl+C, Ctrl+D, and Kill. While a command is running, Enter sends your text to its
stdin, so `[Y/n]` prompts can be answered.

Ctrl+C sends SIGINT to the running program. If the command is still running 1.5 s
later (for example a `while true` loop), the shell is restarted in the same folder;
shell variables are reset in that case.

Sessions use pipes, not a PTY, so full-screen programs (`nano`, `vim`, `htop`) do not
work here. Use `apt install -y` to skip confirmation prompts in scripts.

### Start / run / stop

```bash
KEY=xxxxts
BASE=http://192.168.1.50:8090

# start a session named "bot1"
curl -s -X POST $BASE/sessions \
  -H "Authorization: Bearer $KEY" \
  -d '{"name":"bot1","cwd":"/data/data/com.suvo.homevps/files"}'

# run a command, wait for the result
curl -s -X POST $BASE/sessions/bot1/exec \
  -H "Authorization: Bearer $KEY" \
  -d '{"command":"echo hello && uname -a"}'
# -> {"output":"hello\nLinux ...","exit_code":0,"timed_out":false,...}

# stop the session
curl -s -X POST $BASE/sessions/bot1/exec \
  -H "Authorization: Bearer $KEY" \
  -d '{"command":"exit"}'
```

### Streaming output (SSE)

Add `"stream": true`. Each line arrives as it is printed:

```bash
curl -N -X POST $BASE/sessions/bot1/exec \
  -H "Authorization: Bearer $KEY" \
  -d '{"command":"for i in 1 2 3; do echo line $i; sleep 1; done","stream":true}'
```
```
event: output
data: {"line":"line 1"}

event: output
data: {"line":"line 2"}

event: done
data: {"exit_code":0,"timed_out":false,"duration_ms":3050}
```

Event types: `output` (stdout), `error` (stderr), `ping` (every 15s keep-alive),
`done` (final, with exit code). The `ping` keeps long commands alive through
Cloudflare/other proxies.

### Live terminal (WebSocket)

Connect to `ws://<ip>:8090/sessions/{name}/stream`. The last 200 lines are replayed,
then live output streams in.

- Server → client: `{"type":"output|error|closed","line":"...","offset":N}`
- Client → server: `{"type":"input","data":"ls\n"}`,
  `{"type":"signal","name":"SIGINT"}` (sends Ctrl+C byte; real signals need the
  Phase 2 PTY)

## Hooking up an agent

Give your agent (Your Friend / Tanu / Telegram agent) these tools. The agent calls
them; your adapter turns each call into one HTTP request above.

```json
[
  {
    "type": "function",
    "function": {
      "name": "start_session",
      "description": "Start a new named shell session on the phone VPS.",
      "parameters": {
        "type": "object",
        "properties": {
          "name": {"type": "string", "description": "Session name, e.g. bot1"},
          "cwd": {"type": "string", "description": "Working directory (optional)"}
        },
        "required": ["name"]
      }
    }
  },
  {
    "type": "function",
    "function": {
      "name": "run_command",
      "description": "Run a shell command in a session and return its output and exit code.",
      "parameters": {
        "type": "object",
        "properties": {
          "name": {"type": "string"},
          "command": {"type": "string"},
          "timeout": {"type": "integer", "description": "Milliseconds, default 60000"}
        },
        "required": ["name", "command"]
      }
    }
  },
  {
    "type": "function",
    "function": {
      "name": "send_input",
      "description": "Send raw text/keys to a session's stdin (answer an interactive prompt).",
      "parameters": {
        "type": "object",
        "properties": {
          "name": {"type": "string"},
          "data": {"type": "string"}
        },
        "required": ["name", "data"]
      }
    }
  },
  {
    "type": "function",
    "function": {
      "name": "read_output",
      "description": "Read buffered output of a session since an offset (for background/long commands).",
      "parameters": {
        "type": "object",
        "properties": {
          "name": {"type": "string"},
          "since": {"type": "integer", "description": "Offset to read from, default 0"}
        },
        "required": ["name"]
      }
    }
  },
  {
    "type": "function",
    "function": {
      "name": "stop_session",
      "description": "Stop a session.",
      "parameters": {
        "type": "object",
        "properties": {"name": {"type": "string"}},
        "required": ["name"]
      }
    }
  }
]
```

Adapter mapping:

| Tool call | HTTP request |
|---|---|
| `start_session(name, cwd)` | `POST /sessions` |
| `run_command(name, command, timeout)` | `POST /sessions/{name}/exec` |
| `send_input(name, data)` | `POST /sessions/{name}/input` |
| `read_output(name, since)` | `GET /sessions/{name}/output?since=` |
| `stop_session(name)` | `DELETE /sessions/{name}` |

For long builds: call `run_command` with a short `timeout`; if it returns
`timed_out: true`, the command is still running — poll `read_output` with the
`next_offset` from the previous response until the session is idle again.

## Setup guide (in the app)

The top of the dashboard is a 5-step checklist that updates itself: Install Ubuntu,
Allow unrestricted battery, Start the server, Turn on internet access, Connect your
agent. Each step has its own button.

## Internet access (Cloudflare Tunnel)

Flip **Internet access** in the app. It downloads `cloudflared` into Ubuntu once and
connects out to Cloudflare, so it works on mobile data and behind CGNAT, with no port
forwarding and no account. You get a public link like
`https://random-words.trycloudflare.com`, shown in the app and in the notification.

That free link changes whenever the tunnel restarts. For a fixed address, create a
tunnel in the Cloudflare dashboard (Zero Trust > Networks > Tunnels), add a public
hostname pointing to `http://localhost:8090`, and paste the token and hostname under
**Fixed address** in the app.

Anyone with the link and the API key can run commands on the phone. Keep the key
private and use **New key** if it leaks.

## Connecting an agent

**Copy connection details** in the app copies the base URL (public if internet
access is on), the API key, every endpoint, and the tool list address. Tool
definitions in OpenAI format are served at `GET /tools.json` (no auth).

## Android notes

- Keep the app's battery set to unrestricted (dashboard button) or Doze will pause it.
- On Xiaomi / Realme / Oppo, also turn on **Autostart** for the app, or the boot
  restart won't fire.
- Android 12+ kills apps with more than ~32 child processes (phantom process
  killer). The session limit defaults to 10 for this reason. To raise it you must
  disable the killer via ADB:
  `adb shell settings put global settings_enable_monitor_phantom_procs false`
- Ports below 1024 need root. The server uses 8090; put HTTPS in front via the tunnel.
