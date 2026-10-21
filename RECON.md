# PocketServer Reconnaissance Report (Phase 0)

## Executive Summary
PocketServer is designed as an unrooted Android host for web services, databases, and language runtimes. While the lower-level system modules (`core-toolchain`, `core-proxy`, `core-storage`, `native-engine`, `core-hub`) contain sophisticated systems code (POSIX signal management, NIO epoll socket activation proxy, 16KB ELF page boundary validation, and Minisign verification), the application UI layer (`app`) is currently disconnected from these subsystems. The existing UI is implemented imperatively in a single Activity using classic Android Views without Compose or Fragments.

---

## 1. Module Inventory & Responsibilities

| Module | Primary Namespace | Core Purpose & Capabilities | Current UI Call Paths |
| :--- | :--- | :--- | :--- |
| **`:app`** | `com.pocketserver` | Application shell, ViewModels, UI presentation, QS TileService, Editor session models. | Root module hosting `MainActivity`. |
| **`:core-hub`** | `com.pocketserver.hub` | Curated package registry (14 runtimes), Alpine APKINDEX parser, Conda-Forge repodata engine, PocketForge repository manager, and SQLite FTS5 search DB (`UnifiedPackageSearchDb`). | `searchDb` and `pocketForgeManager` injected into `PocketServerViewModel`. |
| **`:core-storage`** | `com.pocketserver.storage` | Content-Addressable Storage (CAS) in `/files/store/`, Room database (`PocketDatabase`, `sandboxDao`, `toolchainDao`), Symlink farm manager, and `StorageDoctor`. | Room DB and `StorageDoctor` called directly by `PocketServerViewModel`. |
| **`:core-proxy`** | `com.pocketserver.proxy` | High-performance Java NIO epoll reverse proxy (`SocketActivationProxy`), POSIX `SIGSTOP`/`SIGCONT` lifecycle management (`ProcessSupervisor`), and virtual port allocator. | **Completely Unwired** in `app`. No ViewModel or UI references. |
| **`:core-persistence`** | `com.pocketserver.persistence` | `PocketServerHostService` (foreground orchestration), `PocketServerVpnService` (dummy loopback persistence shield), dual Wi-Fi locks (`WifiLockManager`), and boot receiver. | Called via `PocketServerHostService.start()` and `PocketServerVpnService.start()`. |
| **`:core-network`** | `com.pocketserver.network` | Network roaming & Wi-Fi IP detection (`NetworkRoamingMonitor`), mDNS discovery (`MdnsDiscoveryManager`), Cloudflare quick tunnels (`CloudflareQuickTunnel`), and dual QR bitmap generator (`DualQrCodeEngine`). | `DualQrCodeEngine` called by `QrSharingController`; others **Unwired**. |
| **`:core-toolchain`** | `com.pocketserver.toolchain` | Zstd streaming decompression, 16KB ELF alignment engine, and 5-stage atomic toolchain ingestion pipeline (`ToolchainIngestionPipeline`). | Indirectly used by `PocketForgeManager`; **no UI download trigger**. |
| **`:core-build`** | `com.pocketserver.build` | CPU affinity governor (pinning heavy builds to LITTLE cores), RAM `tmpfs` manager (`/dev/shm`), and build engine. | **Unwired** in `app`. |
| **`:native-engine`** | `com.pocketserver.nativeengine` | C++ NDK library compiled with 16KB page alignment. POSIX symlinks, fsync, process signals, CPU affinity, and nice priorities. | Used by `ProcessSupervisor`, `ToolchainIngestionPipeline`, and `NativeEngineTest`. |

---

## 2. UI Layer Architecture & Screen Inventory

### Activities & Fragments
- **Activities:** Exactly one Activity: [`com.pocketserver.ui.main.MainActivity`](file:///home/shreyas/projects/pocketserver/app/src/main/java/com/pocketserver/ui/main/MainActivity.kt).
- **Fragments:** None. Zero fragments in the project.
- **XML Layouts:** Zero XML layout files in `app/src/main/res/layout/`. The entire layout is inflated programmatically via Kotlin code using `ScrollView`, `LinearLayout`, `TextView`, `Button`, and `EditText` within `MainActivity.buildDashboardLayout()`.

### Screen Inventory
Currently, the app consists of a single monolithic view:
1. **Home / Dashboard View:**
   - **Header Bar:** Brand title "PocketServer" + "HOST ACTIVE" status chip.
   - **Search Input:** `EditText` with hint `"Search 70,000+ packages or runtimes..."`.
   - **Diagnostics Card:** "Local Node Environment" (Subtitle: "Non-Root • 16KB Aligned • Zero VM Overhead") with "Shield: Off/Active" button and "Storage Doctor" button.
   - **Services Section:** "Hosted Services & Containers" containing dynamically created service cards.
   - **Zero-Data State View:** Displayed when 0 services exist, listing 5 starter template buttons (FastAPI, React/Vite, Go, Bun Elysia, Rust Axum).

---

## 3. Comprehensive Button & Click Handler Trace

| UI Element | Location in Code | Visual Label / Type | Destination / Handler | Status | Evidence / Analysis |
| :--- | :--- | :--- | :--- | :--- | :--- |
| **VPN Shield Button** | `MainActivity.kt:197-205` | "Shield: Active" / "Shield: Off" | `toggleVpnShield()` | **WIRED** | Checks `PersistencePreferences`, requests `VpnService.prepare()` consent, starts/stops `PocketServerVpnService`. |
| **Storage Doctor Button** | `MainActivity.kt:207-216` | "Storage Doctor" | `onCleanStorageDoctorClicked()` | **PARTIALLY WIRED (NO UI)** | Calls `viewModel.cleanStorageDoctorCaches()` which purges caches. Shows a Toast, but does NOT display storage breakdown bar or reclaimable sizes to user. |
| **Package Search Field** | `MainActivity.kt:141-159` | "Search 70,000+ packages or runtimes..." | `onSearchQueryChanged()` | **MISWIRED / DEAD** | Calls `viewModel.setSearchQuery(query)` which filters *local hosted services* by name/port. It **does NOT** query `searchDb` and does not display packages! |
| **Starter Template Buttons** | `MainActivity.kt:308-326` | 5 Template Rows (FastAPI, Vite, Go, Bun, Rust) | `onLaunchTemplateClicked()` | **WIRED (DISK ONLY)** | Materializes template files in `filesDir/projects/<id>` and writes Room DB `SandboxEntity`. Does NOT install runtime binaries. |
| **Service Card Tap** | `MainActivity.kt:488-492` | Whole Service Card | `onServiceCardTapped()` | **DEAD** | The method exists in Activity but **no click listener** is attached to the card in `onServicesUpdated()`. Progressive disclosure (`isExpanded`) is unreached. |
| **Service "Open" Button** | `MainActivity.kt:376-380` | "Open" | `onOpenInBrowserClicked()` | **WIRED** | Launches external browser with `service.localUrl` (e.g. `http://192.168.1.105:<port>`). |
| **Service "Share QR" Button** | `MainActivity.kt:382-386` | "Share QR" | `onShareClicked()` | **STUBBED (NO UI)** | Instantiates `QrSharingController` and sets screen brightness to 100%, but **never opens a dialog, bottom sheet, or displays any QR image**. |
| **Service "Sleep / Wake" Button**| `MainActivity.kt:388-392` | "Sleep" / "Wake" | `onToggleSleepStateClicked()` | **FAKE-DATA** | Calls `toggleServiceSleepStateSync()` which only updates a string in Room DB. Does **not** send SIGSTOP/SIGCONT and launches no process. |
| **Code Editor / Save & Restart** | `MainActivity.kt:457-468` | (None) | `onSaveAndRestartEditor()` | **DEAD CODE** | Logic for `HotReloadCoordinator` and `PocketEditorSession` is implemented in files, but **no UI element** triggers it. |
| **Log Viewer** | `HumanLogViewer.kt` | (None) | `HumanLogViewer` | **DEAD CODE** | Complete log viewer with filtering and auto-scroll exists in `ui/logs/`, but **not instantiated** in `MainActivity`. |
| **Command Runner** | `FriendlyCommandRunner.kt` | (None) | `FriendlyCommandRunner` | **DEAD CODE** | Command runner and shell drawer exist in `ui/commands/`, but **not instantiated** in `MainActivity`. |

---

## 4. Platform, Version & Framework Audit

- **`minSdk`:** `26` (Android 8.0 Oreo).
- **`compileSdk`:** `35`.
- **`targetSdk`:** Currently `35` (Blocks direct `execve()` from app data under Android 10+ W^X; being updated to `28` per architectural decision).
- **Compose Status:** **Not present**. `app/build.gradle.kts` does not yet declare Jetpack Compose compiler or dependencies.
- **Material Components:** `com.google.android.material:material:1.12.0` (XML theme inheritance: `Theme.MaterialComponents.DayNight.NoActionBar`).
- **Edge-to-Edge:** **Broken / Deprecated**. Uses `window.statusBarColor` and `window.navigationBarColor` (deprecated in API 35). Lacks `enableEdgeToEdge()` and WindowInsets dispatch, causing headers to clip behind status bar.
- **Typography & Theming:** Custom tokens in `PocketDesignTokens.kt` mimic Tailwind Zinc 950, but rely on hardcoded color hexes and programmatic view instantiation.
