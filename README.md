<div align="center">
  <img src="manfaz_logo.png" width="180" alt="Manfaz VPN logo" />

  # Manfaz VPN

  **A polished, privacy-focused Android VPN client built for fast, flexible, and reliable connections.**

  [![Android](https://img.shields.io/badge/Android-8.0%2B-3DDC84?style=for-the-badge&logo=android&logoColor=white)](https://developer.android.com/)
  [![Kotlin](https://img.shields.io/badge/Kotlin-2.0-7F52FF?style=for-the-badge&logo=kotlin&logoColor=white)](https://kotlinlang.org/)
  [![Jetpack Compose](https://img.shields.io/badge/Jetpack%20Compose-Material%203-4285F4?style=for-the-badge&logo=jetpackcompose&logoColor=white)](https://developer.android.com/compose)
  [![Xray](https://img.shields.io/badge/Core-Xray%20%2B%20hev--socks5--tunnel-55D6BE?style=for-the-badge)](https://github.com/XTLS/Xray-core)
</div>

---

## Overview

Manfaz VPN is a modern Android connection client designed around clarity, control, and dependable everyday use. It combines an Xray-based proxy core with a native TUN bridge, a Persian-first interface, real connection diagnostics, and extensive routing controls—without turning advanced networking into a confusing experience.

The interface adapts cleanly across Light, Dark, and AMOLED themes, while technical settings include concise in-app explanations so users can understand how each option affects privacy, latency, stability, and battery usage.

## Highlights

- **Multiple protocol support** — VLESS, VMess, Trojan, Shadowsocks, SOCKS, and HTTP
- **Flexible imports** — share links, subscriptions, QR scanning, gallery images, files, and clipboard detection
- **Real server testing** — latency measurement, bulk testing, sorting, favorites, and automatic failover
- **Advanced DNS** — plain DNS and encrypted DoH, validated custom resolvers, leak protection, and curated presets
- **Routing controls** — per-app split tunneling, LAN access, IPv6 modes, and configurable MTU
- **Connection automation** — boot connection, app-open connection, and independent Wi-Fi/mobile actions
- **Cloudflare optimization** — verified CDN detection and optional clean-IP discovery
- **Secure backups** — AES-GCM encrypted backups protected by PBKDF2-HMAC-SHA256
- **Live visibility** — traffic speed, totals, exit IP, connection time, and diagnostic logs
- **Polished Persian UX** — RTL-first layouts with accessible technical guidance

## Design

Manfaz uses a warm orange identity in Light mode and a cooler teal–blue palette in Dark and AMOLED modes. Server cards can identify common countries from server metadata or IP information and display a matching landmark illustration without overwhelming the connection details.

## Tech Stack

| Layer | Technology |
|---|---|
| Language | Kotlin |
| UI | Jetpack Compose + Material 3 |
| VPN service | Android `VpnService` |
| Proxy engine | Xray |
| TUN bridge | hev-socks5-tunnel |
| QR | CameraX, ML Kit, ZXing |
| Persistence | Encrypted local files + Android Keystore |
| Background work | Android JobScheduler |

## Build

### Requirements

- Android Studio with JDK 17
- Android SDK 35
- Android 8.0 (API 26) or newer for the target device

### Run a debug build

```bash
./gradlew assembleDebug
```

On Windows:

```powershell
.\gradlew.bat assembleDebug
```

Release signing credentials are intentionally excluded from the repository. Without local signing properties, Gradle can still produce normal debug builds and an unsigned release artifact.

## Security Notes

- Release keys, local configuration, generated builds, and developer-specific files are excluded from version control.
- Subscription links and imported credentials are stored locally rather than embedded in the source tree.
- The internal kill switch protects against core failures; Android's **Always-on VPN** and **Block connections without VPN** options provide the strongest system-level protection.

## Project Status

Manfaz VPN is under active development. Protocol behavior can vary across providers, network operators, and Android vendors, so device-level testing is recommended for connection-related changes.

---

<div align="center">
  Made with ♥ by <a href="https://github.com/hrschemiker"><strong>Hamidreza</strong></a>
</div>
