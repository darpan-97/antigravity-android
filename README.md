# Antigravity Mobile 🚀

> **Open-Source Remote Client for Google Antigravity Desktop AI**  
> Control, monitor, and stream your local Antigravity AI coding agent from your Android phone over a secure, encrypted **Tailscale** mesh network anywhere in the world.

---

## 🌟 Key Features

- **📱 Desktop-Aligned Dark UI**: Pixel-accurate recreation of the Antigravity desktop interface with model indicator (`Gemini 3.8 Flash Medium`), clean input bar, and breadcrumb navigation.
- **📁 Real-Time Workspace & Conversation Switching**:
  - Live sidebar drawer populated directly from Antigravity's local SQLite database (`conversation_summaries.db`).
  - Conversations grouped by workspace directory (`📁 Nitro-C`, `📁 WASM`, etc.) with relative timestamps (`now`, `14h`, `4mo`).
  - Active running sessions marked with a live blue status indicator (`●`).
  - **+ New Conversation** dialog allowing you to spawn fresh agent tasks remotely.
- **⚡ Live Agent & Antigravity Process Banner**:
  - Displays real-time agent tasks directly from the Antigravity language server process tree.
  - Collapsible running tasks banner showing active tasks and background processes.
  - Interactive "Thinking" status indicator with animated tool action feedback (e.g. `Capturing screen`, `Running command`).
- **📝 Native Markdown Engine**: Built-in markdown parser rendering code blocks, bold text, bullet points, blockquotes, and links directly into native Android spans without heavy WebViews.
- **🔒 Zero-Configuration Tailscale Mesh Networking**:
  - End-to-end encrypted WireGuard connection directly between your phone and your PC.
  - No public port forwarding or router configuration required.
  - One-tap connect button (`🔒 Connect via Tailscale`) and explicit `Log Out` button.

---

## 🏗️ Architecture

```
                                  [ Tailscale Private Mesh ]
[ Samsung Galaxy / Android ] <──────────────────────────────────> [ Local Desktop / PC ]
  (com.antigravity.mobile)       (WireGuard Encrypted VPN)         (nitro-gateway : 8080)
        │                                                                   │
        │ 1. HTTP POST /message                                             │
        │ 2. SSE GET /events (Stream replies, status, tasks)                ▼
        └──────────────────────────────────────────────────────────> bridge_server.mjs
                                                                            │
                                          ┌─────────────────────────────────┴───────────────────┐
                                          ▼                                                     ▼
                               Antigravity language_server.exe                      conversation_summaries.db
                               (agentapi send-message / new-conversation)           (Live conversation index)
```

---

## 🚀 Quick Setup Guide

### 1. Requirements
- **PC**:
  - [Google Antigravity](https://antigravity.google) installed.
  - [Node.js](https://nodejs.org/) (v18+).
  - [Tailscale](https://tailscale.com/) installed and signed in.
- **Phone**:
  - Android 5.0+ (Lollipop) or newer.
  - [Tailscale for Android](https://play.google.com/store/apps/details?id=com.tailscale.ipn) installed and signed in with the same account.

---

### 2. Start the Desktop Bridge Server

Clone this repository on your computer and run:

```bash
# Start the bridge server
node --no-warnings bridge_server.mjs
```

The bridge server will automatically:
1. Bind to port `8080` (accessible to your Tailscale network).
2. Connect to the local Antigravity `conversation_summaries.db` in read-only mode.
3. Hook into Antigravity's `agentapi` to deliver messages and create new sessions.
4. Stream transcripts, thinking state, and task statuses via Server-Sent Events (SSE).

---

### 3. Install & Connect the Android App

1. Download or compile `antigravity-mobile.apk`.
2. Install it on your phone:
   ```bash
   adb install -r antigravity-mobile.apk
   ```
3. Open **Tailscale** on your phone and ensure it is connected.
4. Open **Antigravity Mobile**:
   - Tap the **Settings (⚙️)** icon in the top right.
   - Tap **🔒 Connect via Tailscale** (or enter `http://<YOUR_PC_TAILSCALE_IP>:8080`).
   - Tap **Save & Connect**.
5. You will see the status pill switch to **`● Live`**. You are now controlling your desktop Antigravity session!

---

## 🛠️ Building the APK from Source

The app uses a lightweight, self-contained build script that requires only the Android SDK Build Tools and JDK:

```bash
cd antigravity-app
node build_apk.mjs
```

The output signed APK will be generated at `antigravity-app/antigravity-mobile.apk`.

## 📚 Technical Documentation

- **[Tailscale Remote Setup Guide](docs/TAILSCALE_SETUP.md)**: End-to-end pairing guide for desktop & phone over Tailscale.
- **[System Architecture](docs/ARCHITECTURE.md)**: Native Android MVP contract, SQLite transactional cache, and SSE reactive stream engine.
- **[Desktop Protocol Specification](docs/DESKTOP_PROTOCOL.md)**: Analysis of Antigravity's internal language server IPC and RPC endpoints.
- **[Protocol Analysis & Synthesis](docs/PROTOCOL_ANALYSIS.md)**: Clean architecture blueprint and component contracts.

---

## 📄 License
MIT License. Open-source and free to customize.
