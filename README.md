# Orbit-AI

A portable Android app that turns AI coding agents (OpenClaude, OpenCode, Claude Code, Codex) into pocket-sized, on-device tools. No Termux, no root, no laptop required.

Orbit-AI ships as **five editions**, each bundling one agent (or, for the Normal edition, letting you pick). Every edition runs the agent inside a self-contained Linux runtime via **PRoot** and pipes your chat to it.

## Editions (product flavors)

| Flavor | Bundled agent | App label | Default provider |
|--------|---------------|-----------|------------------|
| `normal` | **User chooses** in the setup wizard (OpenClaude / Claude Code / OpenCode / Codex / Default) | Orbit AI | OpenRouter (`tencent/hy3:free`) |
| `openclaude` | OpenClaude (`@gitlawb/openclaude`) | Orbit + OpenClaude | OpenRouter (any provider) |
| `opencode` | OpenCode (`@opencode-ai/cli`, binary `lildax`) | Orbit + OpenCode | OpenRouter (any provider) |
| `claudecode` | Claude Code (`@anthropic-ai/claude-code`) | Orbit + Claude Code | Anthropic Claude |
| `codex` | Codex (`@openai/codex`) | Orbit + Codex | OpenAI |

Build all five with `./gradlew assembleDebug`, or one with `./gradlew assemble<Flavor>Debug` (e.g. `assembleCodexDebug`).

## Per-edition authentication

- **Codex Edition** — authenticates with the **OpenAI API** (`OPENAI_API_KEY`). The wizard preselects the OpenAI provider so the agent gets a usable key.
- **Claude Code Edition** — supports **both** authentication methods:
  - **Anthropic API key** (`ANTHROPIC_API_KEY`), or
  - **Claude Max subscription** — paste your subscription token; Orbit exports it as `ANTHROPIC_AUTH_TOKEN`. The setup wizard shows an *API Key / Claude Max Subscription* toggle when Anthropic is the selected provider.
- **OpenClaude / OpenCode Editions** — work with **any** provider from the bundled catalog (OpenRouter, OpenAI, Anthropic, Gemini, DeepSeek, Groq, xAI, and 25+ more). Orbit passes the selected provider's key + base URL to the agent via environment variables.
- **Normal Edition** — the setup wizard lets you **choose which agent** you want; after selection the installer automatically downloads, installs (via `npm install -g`), and configures the chosen agent inside the Linux runtime. No manual setup required.

## How it works

Orbit-AI bundles a **complete Linux runtime** (Debian/Ubuntu rootfs) inside the APK and runs it via **PRoot** — a user-space `chroot` replacement that needs no root privileges. Agents run inside this Linux environment where they have access to `node`, `npm`, `git`, `gh`, and a standard filesystem layout.

```
┌─────────────────────────────────────────┐
│            Orbit-AI APK                  │
│                                         │
│  ┌─────────────┐  ┌──────────────────┐  │
│  │  Jetpack     │  │  libproot.so     │  │
│  │  Compose UI  │  │  (PRoot binary)  │  │
│  │  (the app)   │  └────────┬─────────┘  │
│  └──────┬───────┘           │            │
│         │                   ▼            │
│  ┌──────▼───────────────────────────┐    │
│  │     PRoot Linux runtime          │    │
│  │  ┌─────────────────────────────┐ │    │
│  │  │ /usr/bin/node  /usr/bin/git │ │    │
│  │  │ /usr/bin/npm   /usr/bin/gh  │ │    │
│  │  │ /agents/  /workspace/       │ │    │
│  │  │ /sdcard/ (Android storage)  │ │    │
│  │  └─────────────────────────────┘ │    │
│  └──────────────────────────────────┘    │
│                                         │
│  Bundled assets: runtime rootfs,        │
│  offline debs, agent installer          │
└─────────────────────────────────────────┘
```

## Features

- **Five editions** — Normal (agent picker), OpenClaude, OpenCode, Claude Code, Codex.
- **Self-installing agents** — the setup wizard installs the chosen agent via `npm install -g` inside the runtime and writes a launch wrapper. No manual setup.
- **30+ AI providers** — OpenRouter (default), OpenAI, Anthropic, Gemini, DeepSeek, Groq, xAI, Ollama (local), and more, loaded from a dynamic catalog.
- **OpenRouter default** — new installs default to OpenRouter with the free `tencent/hy3:free` model, listed first in the wizard.
- **Claude Code: API key OR subscription** — choose Anthropic API key or Claude Max subscription token in setup.
- **Android-aware agents** — a built-in skill tells agents about their environment (filesystem, storage, available tools).
- **Expert logging** — every operation logged to file + logcat. Settings → Diagnostics shows the log path.

## Architecture

### PRoot runtime

The core is using **PRoot** instead of wrapper scripts or shared-library hacks:

| Approach | Works? | Why |
|----------|--------|-----|
| Wrapper scripts in filesDir | No | SELinux W^X blocks exec of scripts |
| Termux debs (shared libs) | Fragile | Dependency hell, stale URLs |
| **PRoot + Linux rootfs** | **Yes** | PRoot is a real binary (exec'd from nativeLibDir), agents run inside a full Linux env |

PRoot uses `ptrace` to intercept syscalls and translate paths — no root, no `chroot`, no `mount` needed. It works on all Android 7+ devices.

### Agent execution

When you send a message to an agent:

1. `ChatViewModel` resolves the agent's run command (e.g. `openclaude`, `lildax`, `claude`, `codex`).
2. It builds environment-variable exports for the selected provider (e.g. `OPENAI_API_KEY` + `OPENAI_BASE_URL`, or `ANTHROPIC_API_KEY` / `ANTHROPIC_AUTH_TOKEN` for Claude).
3. The agent is launched inside the PRoot runtime with those env vars.
4. Output is captured and returned to the chat UI.

## Building

Prerequisites: Android SDK (platform + build-tools), **JDK 17+** to run the Android Gradle plugin (CI uses JDK 21; app source is Java 11-compatible).

```bash
git clone https://github.com/TheOneWhoSpeaksJanna/Orbit-AI.git
cd Orbit-AI
./gradlew assembleDebug          # builds all 5 edition APKs
./gradlew assembleCodexDebug     # build a single edition
```

> **Note (Linux/x86-64 build-tools on ARM hosts):** the Android build-tools (`aapt2`) are x86-64 binaries. On an aarch64 build machine, export `LD_LIBRARY_PATH=/usr/x86_64-linux-gnu/lib:/usr/x86_64-linux-gnu/lib64` so `aapt2` can find its native libs.

The APKs land in `app/build/outputs/apk/<flavor>/debug/`.

## First launch flow

1. App starts → Setup Wizard.
2. **Normal edition only:** pick the agent you want (OpenClaude / Claude Code / OpenCode / Codex / Default).
3. Choose a provider and enter its API key (or, for Claude Code, an API key **or** a Claude Max subscription token).
4. App extracts the Linux runtime rootfs from APK assets.
5. App installs the selected agent via `npm install -g` inside the runtime.
6. App creates a wrapper that launches the agent via PRoot.
7. Chat opens — messages are piped to the agent with the provider's credentials exported as environment variables.

## Logging

Logs go to both file and logcat:

- **File**: Settings → Diagnostics shows the path (under the app's files directory).
- **Logcat**: `adb logcat -s Orbit AI` shows real-time logs.

Log levels: `I` (info), `W` (warnings), `E` (errors), `D` (debug). Every command execution, package install, and agent run is logged with full context.

## Security

- PRoot runs as the app's unprivileged UID — no privilege escalation.
- Agents are sandboxed inside the app's data directory.
- API keys are stored in the app's DataStore and exported to the agent only as environment variables at launch time.

## CI/CD

GitHub Actions (`.github/workflows/build.yml`):

- Builds all 5 flavors on push to `main` and on PRs.
- Runs unit tests on PRs.
- Uploads APKs as artifacts (30-day retention).
- Auto-creates a GitHub Release on push to `main`.
