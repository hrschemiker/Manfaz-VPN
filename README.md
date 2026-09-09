<div align="center">
  <img src="manfaz_logo.png" width="168" alt="Manfaz VPN" />

  # Manfaz VPN

  **A Persian-first Android VPN client engineered for clarity, control, and dependable everyday connectivity.**

  [![Latest release](https://img.shields.io/github/v/release/hrschemiker/Manfaz-VPN?style=flat-square&color=ff6a00)](https://github.com/hrschemiker/Manfaz-VPN/releases/latest)
  [![Android 8+](https://img.shields.io/badge/Android-8.0%2B-3DDC84?style=flat-square&logo=android&logoColor=white)](https://developer.android.com/)
  [![Kotlin](https://img.shields.io/badge/Kotlin-100%25-7F52FF?style=flat-square&logo=kotlin&logoColor=white)](https://kotlinlang.org/)
  [![Jetpack Compose](https://img.shields.io/badge/UI-Jetpack%20Compose-4285F4?style=flat-square&logo=jetpackcompose&logoColor=white)](https://developer.android.com/compose)

  [Download](https://github.com/hrschemiker/Manfaz-VPN/releases/latest) · [Features](#features) · [Build](#build-from-source) · [Architecture](#architecture)
</div>

---

## About

Manfaz is a native Android connection client built around an Xray proxy core and a dedicated TUN bridge. It presents advanced networking controls through a clean RTL interface while keeping everyday actions—importing a subscription, checking server reachability, connecting, and understanding connection health—straightforward.

The application is designed for real mobile conditions: process recreation, background execution, network changes, unreliable IPv6 paths, subscription refreshes, and vendor-specific Android power management.

<p align="center">
  <img src="banner.png" width="760" alt="Manfaz VPN interface" />
</p>

## Features

### Connection

- VLESS, VMess, Trojan, Shadowsocks, SOCKS5, and HTTP through Xray
- Native Android `VpnService` integration with an isolated core process
- Two-phase latency testing: a fast TCP-handshake sweep across every server, then a real
  end-to-end probe through the fastest candidates—with live progress and a stop button
- Automatic failover, fastest-server selection, and last-server reconnect
- Connection state recovery after the UI process returns from the background
- Validated Wi-Fi/mobile handover with in-place proxy-core recovery
- Conservative end-to-end health monitoring with multi-failure recovery and reconnect-loop protection
- Quick Settings tile, launcher shortcuts, persistent notification, and home-screen widget

### Circumvention

Defaults are tuned for the conditions Iranian networks actually present:

- **TLS ClientHello fragmentation** through a dedicated `freedom` dialer, so SNI-based
  inspection cannot reassemble the hostname. Four modes, from off to an aggressive profile
  that adds padding noise for the hardest conditions.
- **REALITY**, including post-quantum `mldsa65Verify` verification when a share link carries it
- **Configurable uTLS fingerprint**, because a ClientHello that matches no real browser is
  itself a signal
- **Domestic split routing**: `.ir` and the major Iranian services stay off the tunnel, so
  banking and shopping sites keep seeing a domestic address
- **QUIC blocking**, which pushes browsers back to TCP+TLS that the tunnel carries reliably
- **Mux.Cool with XUDP** for networks that throttle UDP or limit connection counts
- **Encrypted DNS by default**, resolved inside the tunnel

### Import and subscriptions

- Share links, subscription URLs, QR codes, gallery images, files, and clipboard import
- Subscription usage and expiration information
- Stable server identity across subscription refreshes
- Compatibility handling for TLS, Reality, WebSocket, gRPC, HTTP Upgrade, XHTTP, and legacy VMess metadata

### Routing and privacy controls

- Per-app split tunneling with bypass and allow-only modes
- DNS leak protection, on by default, with plain DNS and DoH configuration. The proxy
  endpoint's own hostname and the DoH resolver are pinned to a numeric bootstrap resolver,
  so resolution can never become circular or leak the destination
- Routing uses explicit CIDR and domain matchers rather than `geoip:`/`geosite:` tags, which
  would require geo databases the app does not ship
- IPv4/IPv6 routing modes, LAN access control, and automatic or manual MTU
- **Kill switch with an escape hatch**: when the tunnel fails, the interface is held open so
  no traffic leaks, and a "release the internet" action appears immediately both on the home
  screen and in the phone's notification—along with a retry action and a Quick Settings tile
  that says what is happening
- Android Always-on VPN compatibility
- Optional Cloudflare clean-IP discovery for eligible CDN configurations

### Experience

- Persian-first RTL interface
- Light, Dark, and AMOLED themes
- Consistent app bars, edge-to-edge insets, and 48dp touch targets throughout
- Search across name, address, subscription and protocol; sorting, favorites, and
  destructive actions behind a long-press menu with confirmation
- Connection diagnostics with a monospaced, auto-scrolled log and one-tap sharing
- Contextual explanations for technical settings
- Password-encrypted backup and restore

## Architecture

| Layer | Implementation |
|---|---|
| Application | Kotlin, Android SDK 35 |
| Interface | Jetpack Compose, Material 3 |
| VPN lifecycle | Android `VpnService` in an isolated `:core` process |
| Proxy engine | Xray via AndroidLibXrayLite |
| TUN bridge | hev-socks5-tunnel |
| Persistence | Encrypted local storage and Android Keystore |
| QR pipeline | CameraX, ML Kit, and ZXing |
| Background work | Foreground service, JobScheduler, and app widgets |

The UI process owns presentation and user intent. The isolated core process owns the TUN descriptor, Xray lifecycle, traffic counters, and authoritative connection state. Process-safe snapshots and app-scoped messages reconcile both sides after Android recreates either process.

## Build from source

### Requirements

- Android Studio with JDK 17
- Android SDK 35
- Android 8.0 / API 26 or newer for the target device

### Debug APK

```bash
./gradlew assembleDebug
```

On Windows:

```powershell
.\gradlew.bat assembleDebug
```

### Release signing

Signing credentials belong in `local.properties` and are intentionally excluded from version control:

```properties
manfaz.storeFile=path/to/your-keystore.jks
manfaz.storePassword=your-store-password
manfaz.keyAlias=your-key-alias
manfaz.keyPassword=your-key-password
```

With valid credentials, `assembleRelease` produces signed per-ABI APKs. Without them, Gradle can still produce debug and unsigned release artifacts.

### Releases

Pushing a `v*` tag runs `.github/workflows/release.yml`, which builds the per-ABI APKs and
attaches them, with SHA-256 checksums, to a GitHub Release. The same workflow can also be
started by hand from the Actions tab, which creates the tag for you.

#### One-time signing setup

Release builds are signed in CI with the project's keystore, which is supplied as encrypted
repository secrets and never committed — this repository is public, and a leaked signing key
would let anyone publish a malicious build that installs silently over the real app.

Add two secrets under **Settings → Secrets and variables → Actions → New repository secret**:

| Secret | Value |
|---|---|
| `KEYSTORE_BASE64` | the keystore file, base64-encoded (`base64 -w0 manfaz-release.jks`) |
| `KEYSTORE_PASSWORD` | the keystore password |

The key alias is `manfaz` and is set in the workflow, since on its own it protects nothing.

Keep an offline backup of the keystore file and its password. Losing them means a future
release can only be signed with a different key, and Android will not install that over an
existing installation — every user would have to uninstall first.

Without those secrets the workflow still publishes, but the APKs are unsigned, the files are
named `-unsigned.apk`, and the release is marked as a pre-release explaining why — an
unsigned APK is one Android refuses to install.

## Security and privacy

- Imported credentials and subscription URLs remain on the device.
- Signing keys, generated artifacts, local settings, and developer-specific files are excluded from Git.
- Backup archives use password-based encryption before leaving the application.
- Manfaz does not claim that an application-level switch can replace Android's system-enforced lockdown. For the strongest leak protection, enable **Always-on VPN** and **Block connections without VPN** in Android settings.

## Project status

Manfaz is actively developed and tested against real subscription formats and mobile-network behavior. Proxy compatibility can still depend on the provider, Android vendor, carrier, and server-side configuration; reproducible reports should include the protocol, transport, Android version, and diagnostic output with credentials removed.

---

<div align="center">
  Made with ♥ by <a href="https://github.com/hrschemiker"><strong>Hamidreza</strong></a>
</div>
