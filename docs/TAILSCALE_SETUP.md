# Quick Setup: Antigravity Mobile with Tailscale

This guide walks you through pairing the **Antigravity Mobile** Android app with your local computer running **Google Antigravity** using your own **Tailscale** account.

---

## What is Tailscale & Why Use It?

Tailscale creates a secure, end-to-end encrypted mesh network (using WireGuard) between your devices:
- **No port forwarding**: Works behind any home router or firewall.
- **No public exposure**: Your Antigravity instance is only reachable by devices authenticated to **your** Tailscale account.
- **Connect from anywhere**: Works over 5G/LTE cellular or public Wi-Fi.

---

## Step 1: Install Tailscale on Your Desktop / PC

1. Download and install Tailscale on your computer:
   - **Windows / macOS / Linux**: [https://tailscale.com/download](https://tailscale.com/download)
2. Open Tailscale and sign in with your preferred provider (Google, GitHub, Microsoft, Apple, etc.).
3. Note your desktop's Tailscale IPv4 address:
   - On Windows: Right-click the Tailscale icon in the system tray or run in terminal:
     ```bash
     tailscale ip -4
     ```
   - Your address will look like `100.x.y.z` (for example: `100.85.120.45`).

---

## Step 2: Install Tailscale on Your Android Phone

1. Install Tailscale from the Google Play Store:
   - [Tailscale on Google Play](https://play.google.com/store/apps/details?id=com.tailscale.ipn)
2. Open the app and sign in with the **exact same account** you used on your PC.
3. Toggle the VPN switch to **Connected**.
4. You should now see your PC listed in the Tailscale device list with a green dot.

---

## Step 3: Run the Antigravity Desktop Bridge

On your computer where Google Antigravity is installed:

```bash
# Clone the repository
git clone https://github.com/darpan-97/antigravity-android.git
cd antigravity-android

# Start the bridge server
node --no-warnings bridge_server.mjs
```

The bridge server will output:
```text
Antigravity Bridge running on http://0.0.0.0:8080
Tailscale IPv4: 100.x.y.z
Listening for mobile client connections...
```

---

## Step 4: Connect the Android App

1. Install the APK on your phone (download from GitHub Releases or build with `node build_apk.mjs`).
2. Open **Antigravity Mobile**.
3. Tap the **Settings** icon in the top right.
4. Enter your computer's Tailscale endpoint:
   ```text
   http://100.x.y.z:8080
   ```
   *(Replace `100.x.y.z` with your actual Tailscale IP from Step 1)*
5. Tap **Save & Connect**.
6. The status pill in the top bar will turn **`● Live`**.

---

## Optional: Protecting with an Auth Token

If you want extra protection against other devices on your Tailnet:
1. Start the bridge with a token:
   ```bash
   # Windows PowerShell
   $env:ANTIGRAVITY_AUTH_TOKEN="my-secret-password-123"
   node --no-warnings bridge_server.mjs
   ```
2. In the mobile app's settings dialog, enter the same token in the **Auth Token** field.
3. Tap **Save & Connect**.

---

## Troubleshooting

- **Status shows `Offline` or `Connecting...`**:
  - Check that the Tailscale toggle is ON in the Tailscale app on your phone.
  - Verify you can reach the bridge from your phone browser: open Chrome on your phone and go to `http://100.x.y.z:8080/health`. You should see `{"status":"ok"}`.
  - Make sure Windows Defender / Firewall allows incoming connections on port `8080` for private networks.
- **Messages don't show up in desktop Antigravity**:
  - Ensure the official Antigravity desktop app is running on your PC. The bridge uses Antigravity's built-in `agentapi` CLI to inject prompts directly into your active session.
