# PocketServer System Architecture & Systems Engineering Specification

This document provides the canonical architectural specification for PocketServer, an open-source, local web service and container hosting platform running on unrooted Android devices.

---

## 1. High-Level System Component Architecture

```mermaid
flowchart TD
    subgraph UI_Layer["Application Presentation Layer (Jetpack Compose)"]
        UI_Dash["Dashboard & Telemetry View"]
        UI_Repo["3-Tap Repository Onboarding"]
        UI_Editor["PocketEditor (Sora Native Engine)"]
        UI_Storage["Storage Doctor & Purge Controller"]
    end

    subgraph Host_Daemon["PocketServer Host Daemon (Foreground & VPN Shield)"]
        VPN_Shield["PocketServerVpnService\n(Loopback Shield 10.245.0.1/32)"]
        Wifi_Gov["Dual Wi-Fi Lock Governor\n(HIGH_PERF & LOW_LATENCY)"]
        Power_Gov["Power & Thermal Governor\n(Thermal Status & Partial WakeLock)"]
        Boot_Recv["Direct-Boot BootReceiver\n(ACTION_BOOT_COMPLETED)"]
    end

    subgraph Proxy_Layer["Socket-Activation & Network Proxy Engine"]
        Proxy_Epoll["Epoll Reverse Proxy Daemon"]
        Port_Router["Virtual Port Remapper & Conflict Allocator"]
        Sig_Supervisor["Process Supervisor\n(kill -pgid SIGSTOP / SIGCONT)"]
        KeepAlive_Pinger["15s QUIC Keep-Alive Heartbeat"]
        DB_PrePing["Database Pre-Ping Injector\n(Stale Socket Recovery)"]
    end

    subgraph Build_Layer["Build Engine & Thermal Governor"]
        CPU_Gov["CpuPinningGovernor\n(taskset -c 0-3 nice -n 19)"]
        RAM_Shm["RamTmpfsManager\n(/dev/shm Build Scratchpad)"]
        Build_Eng["BuildEngine Pipeline"]
    end

    subgraph Storage_Layer["Content-Addressable Storage (CAS) & Storage Doctor"]
        CAS_Store["Global Immutable Store\n(/files/store/<hash>-<name>/)"]
        CAS_Db["Room Metadata Catalog & RefCount DB"]
        Zstd_Engine["Zstd-JNI Stream Extractor\n(> 1.2 GB/s Decompression)"]
        Elf_Patcher["16KB Alignment & RPATH Patcher"]
        Doc_Storage["Storage Doctor\n(Hidden 5 Cache Reclaimer)"]
    end

    subgraph Hub_Layer["Curated Package Hub"]
        Hub_Reg["HubPackageRegistry\n(14 Polyglot Runtimes)"]
        Hub_Ingest["HubIngestionEngine"]
        Smoke_Runner["PolyglotSmokeRunner\n(RSS < 45MB Healthcheck)"]
    end

    subgraph Sandboxes["Service Execution Sandboxes"]
        SB_A["Service Sandbox Alpha\n(Relative Symlink Rootfs)"]
        SB_B["Service Sandbox Beta\n(Relative Symlink Rootfs)"]
        Tmp_Shm["RAM tmpfs Scratchpad\n(/dev/shm & TMPDIR)"]
    end

    UI_Layer -->|Local IPC / Kotlin StateFlow| Host_Daemon
    Host_Daemon --> Proxy_Layer
    Host_Daemon --> Storage_Layer
    Host_Daemon --> Build_Layer
    Host_Daemon --> Hub_Layer
    Build_Layer --> Sandboxes
    Proxy_Layer --> Sandboxes
    Storage_Layer -->|Read-Only Symlink Farm| Sandboxes
    RAM_Shm -.->|RAM-backed ephemeral mounts| Sandboxes
    Hub_Layer --> CAS_Store
```

---

## 2. Socket-Activation & Sleep/Wake State Machine

PocketServer utilizes an epoll-based reverse proxy to decouple network ingress from backend CPU execution. Idle services are transitioned to a frozen state (`SIGSTOP`) where the kernel suspends CPU scheduling entirely while keeping process memory resident. Upon incoming TCP connection requests, the proxy dispatches `SIGCONT` to wake the process group in under 1 millisecond.

```mermaid
stateDiagram-v2
    [*] --> Stopped : Service Defined / Deployed
    
    Stopped --> Starting : Launch Command Triggered
    Starting --> Active_Serving : Process Spawned & Healthcheck Passed (HTTP 200)
    
    state Active_Serving {
        [*] --> Processing_Requests
        Processing_Requests --> Awaiting_Next_Request : Connection Closed / Idle
        Awaiting_Next_Request --> Processing_Requests : Inbound TCP Packet
    }
    
    Active_Serving --> Freezing : Idle Inactivity Timer (60s Expired)
    
    state Freezing {
        [*] --> Signal_Stop : kill(-pgid, SIGSTOP)
        Signal_Stop --> Flush_Buffers : Swap Stale TCP Pools
    }
    
    Freezing --> Sleeping : Kernel Suspends Process Threads
    
    state Sleeping {
        [*] --> Zero_CPU_Idle : 0.00% CPU Consumption
        Zero_CPU_Idle --> Inbound_SYN_Detected : Incoming TCP SYN on Proxy Port
    }
    
    Sleeping --> Waking : Proxy Intercepts SYN Packet
    
    state Waking {
        [*] --> Signal_Cont : kill(-pgid, SIGCONT)
        Signal_Cont --> Validate_PrePing : Pre-Ping DB Drivers / Reconnect Sockets
    }
    
    Waking --> Active_Serving : Process Group Resumed (< 1ms Latency)
    
    Active_Serving --> Stopped : User Stops Service / App Exit
    Sleeping --> Stopped : User Stops Service / Cold Termination
```

---

## 3. Process Lifecycle & `SIGSTOP` / `SIGCONT` Sequence Diagram

```mermaid
sequenceDiagram
    autonumber
    actor Client as External Client / Browser
    participant Radio as Mobile Wi-Fi Radio
    participant Proxy as Socket Activation Proxy
    participant Supervisor as Process Supervisor
    participant Backend as Hosted Backend Service (PGID)
    participant Kernel as Linux Kernel (Bionic)

    Note over Client,Backend: Active Request Phase
    Client->>Proxy: HTTP GET /api/v1/resource
    Proxy->>Backend: Proxy TCP payload to localhost:8000
    Backend-->>Proxy: HTTP 200 OK (Response Body)
    Proxy-->>Client: HTTP 200 OK
    
    Note over Proxy,Backend: Idle Timeout Window (60 seconds)
    Proxy->>Supervisor: Inactivity timer expired (60s without TCP traffic)
    Supervisor->>Kernel: kill(-pgid, SIGSTOP)
    Kernel->>Backend: Suspend all threads in thread-group
    Note over Backend: Process enters TASK_STOPPED<br/>0.00% CPU usage, memory remains mapped in RAM/zRAM
    
    Note over Radio,Proxy: Radio Priming Phase (Screen Off)
    loop Every 15 seconds
        Proxy->>Radio: Outbound QUIC keep-alive ping
        Radio-->>Proxy: Baseband ACK (prevents DTIM power collapse)
    end
    
    Note over Client,Backend: Instant Wake Phase (< 35ms TTFB)
    Client->>Proxy: Inbound TCP SYN (New Request)
    Proxy->>Supervisor: Awake trigger (Port 8000 target)
    Supervisor->>Kernel: kill(-pgid, SIGCONT)
    Kernel->>Backend: Resume all threads in thread-group (< 1ms)
    Backend->>Backend: Pre-ping pool discards stale external DB sockets
    Proxy->>Backend: Forward TCP stream
    Backend-->>Proxy: HTTP 200 OK
    Proxy-->>Client: HTTP 200 OK
```

---

## 4. Build Engine & LITTLE Core CPU Pinning Architecture

Mobile SoCs (Snapdragon, Tensor, Dimensity) employ heterogeneous big.LITTLE architectures (e.g. 4 efficiency LITTLE cores + 3 performance cores + 1 prime core). Running intensive compiler toolchains (`cargo build`, `bun build`, `uv pip install`) on performance cores spikes temperatures above 42°C, causing kernel thermal throttling and rapid battery drain.

PocketServer routes all builds through `CpuPinningGovernor` and `RamTmpfsManager`:

```mermaid
flowchart TD
    BuildReq["Build Request Triggered\n(cargo build / uv pip / bun build)"]
    
    subgraph CorePinning["LITTLE Core Pinning Engine"]
        Topology["Detect CPU Topology\n(Cores 0-3 Efficiency Clustered)"]
        WrapCLI["Wrap Invocation:\ntaskset -c 0-3 nice -n 19"]
        NativeAffinity["Native JNI sched_setaffinity()\n& setpriority(PRIO_PROCESS, 19)"]
    end
    
    subgraph ScratchpadManager["RAM-backed tmpfs Scratchpad (/dev/shm)"]
        ProbeSHM["Probe /dev/shm Availability"]
        AllocSHM["Allocate /dev/shm/pocketserver_builds/<id>/"]
        InjectEnv["Route Toolchain Caches:\nTMPDIR, CARGO_TARGET_DIR, UV_CACHE_DIR, npm_config_cache"]
    end
    
    subgraph Execution["Build Execution Under Governor"]
        SpawnProc["Spawn Child Compiler Process"]
        StreamLogs["Stream Build Logs"]
        EvalThermal["Thermal Budget Check:\nTemp < 34°C & SoC Power < 0.6W"]
    end
    
    subgraph CleanUp["Instant Evaporation"]
        WipeSHM["Evaporate RAM Scratchpad\n(Zero NAND Flash Wear)"]
    end
    
    BuildReq --> Topology
    Topology --> WrapCLI
    BuildReq --> ProbeSHM
    ProbeSHM --> AllocSHM --> InjectEnv
    WrapCLI --> SpawnProc
    InjectEnv --> SpawnProc
    SpawnProc --> NativeAffinity
    NativeAffinity --> StreamLogs
    StreamLogs --> EvalThermal
    EvalThermal --> WipeSHM
```

---

## 5. Storage Doctor & 1-Tap Deep Purge Flowchart

PocketServer isolates the "Hidden 5" compiler caches and ensures safe, complete reclamation without compromising user project code or immutable CAS store runtimes.

```mermaid
flowchart TD
    StartScan["User Opens Storage Doctor / Calls analyze()"]
    
    subgraph Discovery["Parallel Storage Inspection"]
        ScanCAS["Measure Immutable CAS Store\n(/files/store/<hash>-<name>/)"]
        ScanCode["Calculate User Source Code\n(sandboxes/ & projects/)"]
        ScanHidden5["Scan the Hidden 5 Mobile Compiler Caches"]
    end
    
    subgraph Hidden5["The Hidden 5 Reclaimable Caches"]
        C1["1. Cargo Registry Sources:\n~/.cargo/registry/src/"]
        C2["2. Python uv Cache:\n~/.cache/uv/"]
        C3["3. npm & Bun Cache:\n~/.npm/_cacache/, ~/.bun/install/cache/"]
        C4["4. Incremental Builds:\ntarget/*/incremental/, __pycache__, .pytest_cache"]
        C5["5. Git Temp Objects:\n.git/objects/tmp_*, index.lock"]
    end
    
    subgraph Breakdown["Breakdown Assembly"]
        SumBytes["Compute Reclaimable Bytes & Percentages"]
        RenderUI["Render Plain-English Breakdown\n(76MB Runtimes, 45MB Code, 1.08GB Reclaimable)"]
    end
    
    subgraph PurgeAction["1-Tap Deep Purge Execution"]
        SafetyGate{"Safety Gate:\nIs Path inside CAS Store\nor User Source File?"}
        RejectDelete["Skip & Preserve Safe Path"]
        PerformDelete["Purge Cache Directory / File"]
        Freespace["Update Available Free Storage Space"]
    end
    
    StartScan --> Discovery
    Discovery --> ScanCAS & ScanCode & ScanHidden5
    ScanHidden5 --> C1 & C2 & C3 & C4 & C5
    ScanCAS & ScanCode & C1 & C2 & C3 & C4 & C5 --> SumBytes
    SumBytes --> RenderUI
    RenderUI -->|User taps Free X.XX GB| SafetyGate
    SafetyGate -- Yes (Protected) --> RejectDelete
    SafetyGate -- No (Reclaimable) --> PerformDelete
    PerformDelete --> Freespace
```

---

## 6. External Database Pre-Ping Connection Recovery

When a hosted service is suspended (`SIGSTOP`), remote databases (Supabase, Neon, AWS RDS) time out the idle TCP connection. Upon unfreezing (`SIGCONT`), attempting to write to the stale socket throws `BrokenPipeError: [Errno 32] Broken pipe`.

```mermaid
sequenceDiagram
    autonumber
    actor Client as HTTP Client
    participant Proxy as PocketServer Proxy
    participant Backend as Python/Node/Go Service
    participant Pool as Database Connection Pool
    participant RemoteDB as Remote PostgreSQL / MySQL

    Note over Backend,RemoteDB: Process Suspended Phase (SIGSTOP)
    Note over RemoteDB: Remote DB TCP idle timeout expires (e.g. 5 minutes)
    RemoteDB->>RemoteDB: Close idle socket (Send FIN / RST)
    Note over Backend: Process is suspended; cannot process FIN packet

    Note over Client,Proxy: Request Arrives -> Process Resumed (SIGCONT)
    Client->>Proxy: HTTP GET /api/users
    Proxy->>Backend: kill(-pgid, SIGCONT) (Wakes in < 1ms)
    Backend->>Pool: Acquire connection to execute query

    Note over Pool,RemoteDB: Pre-Ping Validation Phase
    alt With Pre-Ping Injection (PocketServer)
        Pool->>Pool: Check connection age > pool_recycle (30s)
        Pool->>RemoteDB: Send pre-ping probe query (SELECT 1)
        RemoteDB-->>Pool: Dead socket detected (Broken Pipe / RST)
        Pool->>Pool: Discard stale socket silently
        Pool->>RemoteDB: Establish fresh TCP + TLS connection
        RemoteDB-->>Pool: Connection established
        Pool->>RemoteDB: Execute user query (SELECT * FROM users)
        RemoteDB-->>Backend: Query results (200 OK)
        Backend-->>Proxy: HTTP 200 OK
        Proxy-->>Client: HTTP 200 OK
    else Without Pre-Ping (Standard Behavior)
        Pool->>RemoteDB: Send query on stale socket
        RemoteDB-->>Pool: RST packet (Broken Pipe Error)
        Pool-->>Backend: Crash with BrokenPipeError: [Errno 32]
        Backend-->>Proxy: HTTP 500 Internal Server Error
    end
```

---

## 7. Package Hub & Polyglot Runtime Class Model

```mermaid
classDiagram
    class PolyglotRuntime {
        <<enumeration>>
        PYTHON
        JAVASCRIPT
        GO
        RUST
        CPP
        JAVA_KOTLIN
        PHP
        RUBY
        DOTNET
        ZIG
        REDIS
        SQLITE
        DUCKDB
        CADDY
    }

    class HubPackageDescriptor {
        +PolyglotRuntime runtime
        +RuntimeCategory category
        +String packageName
        +String engineVersion
        +Long upstreamSizeBytes
        +Long strippedSizeBytes
        +String sha256Checksum
        +String archiveFileName
        +String primaryBinaryRelPath
        +String strippingMethod
        +Int defaultPort
        +Map environmentVariables
        +String helloWorldScript
        +List helloWorldCommand
        +Double getUpstreamSizeMb()
        +Double getStrippedSizeMb()
        +Double getCompressionRatio()
    }

    class HubPackageRegistry {
        +getAllPackages() List~HubPackageDescriptor~
        +getLanguageRuntimes() List~HubPackageDescriptor~
        +getInfrastructureServices() List~HubPackageDescriptor~
        +findByRuntime(PolyglotRuntime) HubPackageDescriptor
        +findByPackageName(String) HubPackageDescriptor
    }

    class HubIngestionEngine {
        -ContentAddressableStore casStore
        -PocketDatabase database
        -ToolchainIngestionPipeline pipeline
        +ingestPackage(HubPackageDescriptor, File archive) IngestionResult
        +isPackageInstalled(HubPackageDescriptor) Boolean
    }

    class PolyglotSmokeRunner {
        +runSmokeTest(PolyglotRuntime, List command, File dir, Int port) PolyglotSmokeResult
        +readProcessRssBytes(Long pid) Long
    }

    class BuildEngine {
        -CpuPinningGovernor cpuGovernor
        -RamTmpfsManager ramTmpfsManager
        +executeBuild(String projectId, File dir, List command, BuildOptions) BuildResult
    }

    class CpuPinningGovernor {
        +resolveLittleCoreRange() Pair~Int, Int~
        +getLittleCoreMaskString() String
        +wrapCommand(List command, Int nice) List~String~
        +applyPinning(Int pid, Int maxLittleCore, Int nice) Boolean
        +evaluateThermalBudget(Boolean isPinned, Long durationMs) ThermalMetrics
    }

    class RamTmpfsManager {
        -File preferredShmDir
        +isShmAvailable() Boolean
        +allocateScratchpad(String id, File fallback) File
        +prepareBuildCacheEnv(File scratchpad) Map~String, String~
        +evaporateScratchpad(File scratchpad) Boolean
    }

    class StorageDoctor {
        -ContentAddressableStore casStore
        -PocketDatabase database
        +analyze(File baseDir, File homeDir, List projectDirs) StorageBreakdown
        +cleanReclaimableCaches(StorageBreakdown, File baseDir, File homeDir, List projectDirs) StorageReclaimResult
    }

    HubPackageRegistry --> HubPackageDescriptor : provides
    HubPackageDescriptor --> PolyglotRuntime : specifies
    HubIngestionEngine --> HubPackageDescriptor : installs
    PolyglotSmokeRunner --> PolyglotRuntime : validates
    BuildEngine --> CpuPinningGovernor : pins CPU
    BuildEngine --> RamTmpfsManager : caches in RAM
    StorageDoctor ..> HubPackageRegistry : protects installed CAS runtimes
```

---

## 8. Core Systems Engineering Principles

### 1. 16 KB Page Alignment Deep-Dive
Starting with Android 15, devices can run with a 16 KB kernel page size instead of 4 KB. Any shared library or ELF binary loaded via `dlopen()` or `execve()` whose program headers (`LOAD` segments) have an alignment (`p_align`) less than `0x4000` (16,384 bytes) or whose virtual address (`p_vaddr`) does not match the file offset (`p_offset`) modulo 16 KB will cause immediate kernel faults (`SIGBUS: bus error`).

PocketServer enforces two levels of protection:
1. **Compilation Flags:** All native NDK shared libraries and toolchain executables are linked using:
   ```text
   -Wl,-z,max-page-size=16384 -Wl,-z,common-page-size=16384
   ```
2. **Ingestion Verification:** The embedded `ElfAlignmentEngine` parses the 64-bit ELF header (`Elf64_Ehdr`), iterates over all program headers (`Elf64_Phdr`), and strictly verifies that for every segment of type `PT_LOAD`:
   $$\text{p\_align} \ge 16384 \quad \text{and} \quad (\text{p\_vaddr} \pmod{\text{p\_align}}) == (\text{p\_offset} \pmod{\text{p\_align}})$$
   Any archive containing non-compliant binaries is rejected before touching the global store.

### 2. The Non-Root POSIX Boundary
Android's multi-user sandbox models each application under a distinct Linux UID (e.g. `u0_a245`). Unrooted applications operate under the following POSIX constraints:
- **No `mount()` or `chroot()`:** Namespaces are restricted to privileged callers. PocketServer avoids `chroot` by generating relative symlink farms (`ln -s ../../../store/... /sandbox/usr/bin/...`) and setting `PATH`, `LD_LIBRARY_PATH`, and `HOME` dynamically in spawned processes.
- **No Symlinks on Shared Storage:** Android's Scoped Storage FUSE filesystem mounted at `/sdcard/` rejects `symlink()` with `EPERM` and mounts the filesystem `noexec`. PocketServer mandates that all sandboxes, toolchains, and build caches reside strictly within private internal storage: `context.filesDir`.
- **Atomic Directory Updates:** When packages are installed, they are extracted into a hidden staging directory (`.staging-<uuid>`) on the same physical filesystem and renamed via `renameat()` to ensure atomic updates that are crash-proof across battery drops or power cuts.

### 3. OEM Background Survival (`VpnService` Shield)
Standard Android background mechanisms (such as `WorkManager`, `JobScheduler`, or basic `ForegroundService` with notification) are aggressively killed by vendor operating systems (Samsung One UI, Xiaomi HyperOS, OnePlus OxygenOS) within 3 to 10 minutes of screen lock.

PocketServer overcomes this via `PocketServerVpnService`:
1. It registers an internal dummy VPN tunnel using Android's `VpnService.Builder`.
2. The network interface assigns a non-routable local address (`10.245.0.1/32`) and binds only an internal dummy descriptor without establishing a default internet route (`0.0.0.0/0`).
3. Because the Android framework treats active VPN providers as foundational network infrastructure, OEM task killers classify the process as an untouchable core networking component.

### 4. Dual Wi-Fi Lock Governor & Radio Priming
Mobile Wi-Fi transceivers enter aggressive sleep cycles (DTIM listening intervals up to 1,000ms) when the device screen locks:
1. **Screen Off State:** PocketServer acquires `WifiManager.WIFI_MODE_FULL_HIGH_PERF`, preventing the hardware radio from powering down completely.
2. **Screen On / Active Connection State:** Dynamically acquires `WifiManager.WIFI_MODE_FULL_LOW_LATENCY` (API 29+), reducing ping jitter for real-time web development.
3. **Heartbeat Priming:** An outbound 15-second QUIC heartbeat ping maintains the NAT state table and primes baseband RF amplifiers, guaranteeing Time-To-First-Byte (TTFB) latency under **35ms** with the device screen locked.

---

## 9. Approachable UI/UX, PocketEditor & Mobile Ergonomics Architecture (Phase 4)

PocketServer rejects developer application "AI slop" clichés—pure `#000000` pitch-black backgrounds, eye-straining radioactive neon greens, and intimidating virtual hacker keyboards. Instead, it implements a warm, human-centric design inspired by Vercel and Railway, driven by **progressive disclosure**.

### 1. Presentation Architecture & State Flow

```mermaid
flowchart TD
    subgraph UI_State["Dashboard & Mobile Ergonomics Layer"]
        VM["PocketServerViewModel\n(Central State & Flow Coordinator)"]
        ZeroData["Zero-Data State\n(1-Tap Quick Start Templates)"]
        Cards["Service Cards\n(Surface: URL, Status, Monorepo Badge)"]
        LayerBeneath["The Layer Beneath\n(CPU/RAM, Log Stream, Env Editor)"]
        Editor["PocketEditor Session\n(Syntax Highlighting, Line/Col Telemetry)"]
        CmdRunner["Friendly Command Runner\n(1-Tap Maintenance Chips + Raw Shell)"]
        LogViewer["Human Log Viewer\n(Anti-Jerk Scroll Lock & Error Filters)"]
        ShareModal["High-Fidelity QR Modal\n(Auto-Brightness Boost to 100%)"]
    end

    subgraph System_Integration["System-Level Presence"]
        QSTile["Android Quick Settings TileService\n(1-Tap Toggle Active / Sleeping)"]
        LockNotification["Actionable Lockscreen Notification\n([Pause All], [Copy Link], [Stop All])"]
    end

    subgraph Core_Engines["Core Backend Engines"]
        WakeCoord["MonorepoWakeCoordinator\n(Sequence Unfreeze < 2ms)"]
        FileSaver["AtomicFileSaver\n(POSIX fsync Barrier)"]
        HotReload["HotReloadCoordinator\n(Config Reload < 500ms)"]
        RoomDB["PocketDatabase\n(Sandbox & Manifest Catalog)"]
    end

    VM --> ZeroData
    VM --> Cards
    Cards --> LayerBeneath
    LayerBeneath --> Editor
    LayerBeneath --> CmdRunner
    LayerBeneath --> LogViewer
    Cards --> ShareModal

    QSTile <--> VM
    LockNotification <--> VM
    Editor --> FileSaver
    FileSaver --> HotReload
    Cards --> WakeCoord
    VM --> RoomDB
```

### 2. PocketEditor Hot Reload & Monorepo Coordinated Wake Sequence

```mermaid
sequenceDiagram
    autonumber
    actor Dev as Developer / Mobile Browser
    participant Editor as PocketEditor
    participant Saver as AtomicFileSaver
    participant Coord as HotReloadCoordinator
    participant Wake as MonorepoWakeCoordinator
    participant FE as Frontend Process (Vite)
    participant BE as Backend Process (FastAPI)

    Note over Dev,BE: Flow A: PocketEditor Hot Reload (< 500ms Acceptance Protocol)
    Dev->>Editor: Edit .env (PORT=8000 -> PORT=8080)
    Dev->>Editor: Tap [Save & Restart]
    Editor->>Coord: saveAndRestart(serviceId, envFile, content)
    Coord->>Saver: saveAtomic(targetFile, content)
    Saver->>Saver: Write to .env.tmp.<nano> + fsync(fd)
    Saver->>Saver: POSIX atomic rename over .env
    Saver-->>Coord: File saved atomically
    Coord->>BE: Reload configuration / Restart supervisor
    BE-->>Coord: Port 8080 bound & responding
    Coord-->>Editor: HotReloadResult (Latency < 500ms: PASS)

    Note over Dev,BE: Flow B: Monorepo Coordinated Wake (< 2ms Acceptance Protocol)
    Dev->>FE: Inbound HTTP Request to Frontend (QR scan / LAN)
    FE->>Wake: Inbound connection triggers wake
    Wake->>FE: SIGCONT unfreeze (Frontend)
    Wake->>BE: Sequenced unfreeze (Backend API)
    Note over FE,BE: Coordinated wake completes in < 2ms total
    BE-->>FE: API data returned
    FE-->>Dev: HTTP 200 Complete Web Application Rendered
```

### 3. Class Diagram: Phase 4 Mobile Presentation Layer

```mermaid
classDiagram
    class PocketDesignTokens {
        +String COLOR_CANVAS_HEX = "#09090B"
        +String COLOR_SURFACE_HEX = "#18181B"
        +String COLOR_BORDER_HEX = "#27272A"
        +String COLOR_BRAND_INDIGO_HEX = "#6366F1"
        +String COLOR_STATUS_LIVE_HEX = "#10B981"
        +String COLOR_STATUS_SLEEPING_HEX = "#64748B"
        +Float CARD_CORNER_RADIUS_DP = 14f
        +getHumanStatusDescription(status) String
        +getStatusColor(status) Int
    }

    class StarterTemplate {
        +String id
        +String title
        +String runtimeName
        +Int defaultPort
        +List startCommand
        +Map templateFiles
        +StarterTemplate FASTAPI_HELLO_WORLD
        +StarterTemplate REACT_VITE_FRONTEND
        +StarterTemplate GO_MICROSERVICE
        +StarterTemplate BUN_ELYSIA
        +StarterTemplate RUST_AXUM
    }

    class MonorepoDetector {
        +detect(File repoDir, String name) List~DetectedService~
        -inspectDirectory(File dir, File root, String name) DetectedService
    }

    class MonorepoWakeCoordinator {
        -ConcurrentHashMap linksByMonorepoId
        +registerLink(MonorepoLink)
        +getLinkedServices(String serviceId) List~String~
        +wakeCoordinated(String sourceId, unfreezeAction) CoordinatedWakeResult
    }

    class AtomicFileSaver {
        +saveAtomic(File targetFile, String content) Boolean
    }

    class HotReloadCoordinator {
        +saveAndRestart(String serviceId, File file, String content, restartAction) HotReloadResult
    }

    class HumanLogViewer {
        -List allEntries
        -MutableStateFlow isAutoScrollPaused
        -MutableStateFlow currentFilter
        +append(LogLevel, String message, String tag)
        +onUserTouchOrScroll()
        +jumpToLatest()
        +setFilter(LogFilter)
        +copyErrorTraces() String
        +copyCurrentLogsFormatted() String
    }

    class QrSharingController {
        -MutableStateFlow state
        +selectTab(SharingTab)
        +onModalShown(Window)
        +onModalDismissed(Window)
        +generateActiveQrBitmap(Int sizePx) Bitmap
        +validateEndpoint(String urlString) Int
    }

    class PocketServerTileService {
        -StateFlow isServerActive
        +onClick()
        +updateTileState()
    }

    PocketServerViewModel --> StarterTemplate : provisions
    PocketServerViewModel --> MonorepoDetector : detects
    PocketServerViewModel --> MonorepoWakeCoordinator : coordinates wake
    PocketServerViewModel --> HumanLogViewer : streams logs
    PocketServerViewModel --> QrSharingController : displays share modal
    PocketServerViewModel --> PocketServerTileService : controls QS state
    HotReloadCoordinator --> AtomicFileSaver : atomicity guarantee
```

---

## 7. Universal Package Ecosystem & PocketForge Engine (Phase 5)

PocketServer elevates mobile hosting beyond isolated sandboxes into a 70,000+ package universal Linux substrate across four integrated tiers:

```mermaid
flowchart TD
    subgraph Tiers["The 4 Integrated Package Tiers"]
        direction LR
        T1["Tier 1: Curated Core\n- 14 Polyglot Runtimes\n- Zstd < 50MB, 16KB Aligned\n- Standalone & Stripped"]
        T2["Tier 2: Alpine Linux\n- 36,000+ Packages\n- apk.static Engine\n- Musl Sysroot & Transitive .so"]
        T3["Tier 3: Conda-Forge\n- 25,000+ AI & Math Packages\n- Micromamba aarch64\n- PyTorch, OpenCV, SciPy"]
        T4["Tier 4: PocketForge\n- Decentralized Community Repos\n- Fedora COPR / AUR / TUR\n- Minisign Ed25519 Signed"]
    end

    subgraph Isolation["Multi-Ecosystem Libc & Hardware Isolation"]
        SHIM["Layer 1: Transparent POSIX Shims (/files/bin/)\nHermetic PREFIX, PATH, and dynamic linker virtualization"]
        MUSL_ENV["Musl Sysroot (/files/store/alpine/)\n- PT_INTERP: ld-musl-aarch64.so.1\n- LD_PRELOAD: libmimalloc.so"]
        GLIBC_ENV["Glibc Prefix (/files/conda/envs/)\n- PT_INTERP: ld-linux-aarch64.so.1\n- Thread Governor: OMP_NUM_THREADS=4"]
        PAGE_GATE["Layer 4: 16KB Page-Alignment Gate\np_align == 0x4000 (16,384 bytes) on all PT_LOAD segments"]
    end

    subgraph Search["Unified Full-Text Index"]
        FTS["Layer 5: SQLite FTS5 Search Database\nSub-10ms query latency across 70,000+ packages\nInstant source tier tags ([Core], [Alpine], [Conda], [PocketForge])"]
    end

    T1 --> SHIM
    T2 --> MUSL_ENV --> SHIM
    T3 --> GLIBC_ENV --> SHIM
    T4 --> SHIM
    SHIM --> PAGE_GATE
    T1 -.-> FTS
    T2 -.-> FTS
    T3 -.-> FTS
    T4 -.-> FTS
```

### 7.1 UML Class Diagram (Phase 5 Hub Components)

```mermaid
classDiagram
    class PocketForgeManager {
        -ConcurrentHashMap repositories
        -PocketForgeVerifier verifier
        -PocketForgeManifestParser manifestParser
        -UnifiedPackageSearchDb searchDb
        -ContentAddressableStore casStore
        -BinaryShimGenerator shimGenerator
        +getRepositories() List~PocketForgeRepository~
        +addRepository(PocketForgeRepository) Result
        +removeRepository(String id) Boolean
        +syncRepository(String id, String manifest, String signature) Result~Int~
        +installForgePackage(PocketForgePackage, File archive) Result~File~
    }

    class PocketForgeVerifier {
        +verifyManifestSignature(String manifest, String signature, String pubKey) Boolean
        +verifySha256(File file, String expectedHex) Boolean
        +parsePublicKey(String pubKeyBase64) ParsedMinisignPublicKey
        +parseSignature(String sigContent) ParsedMinisignSignature
    }

    class PocketForgeManifestParser {
        +parse(String jsonContent) PocketForgeManifest
        -parseAndValidatePackage(JSONObject, String repo) PocketForgePackage
    }

    class ApkStaticEngine {
        -File apkStaticBinary
        -File alpineSysroot
        -BinaryShimGenerator shimGenerator
        -ElfAlignmentEngine elfAlignmentEngine
        -AlpineDnsConfigurator dnsConfigurator
        +installPackage(String packageName) InstallResult
        +addMirror(String mirrorUrl)
    }

    class AlpineDnsConfigurator {
        -File sysrootEtcDir
        +resolveActiveDnsServers() List~String~
        +generateResolvConf() String
        +writeResolvConf() File
        +getNodeDnsArguments() List~String~
    }

    class MicromambaEngine {
        -File micromambaBinary
        -File rootPrefixDir
        +createEnvironment(String envName, List~String~ packages) MicromambaResult
        +installPackage(String envName, String packageName) MicromambaResult
        +buildProcessInCondaEnv(String envName, List~String~ command) ProcessBuilder
    }

    class CondaEnvironmentManager {
        -MicromambaEngine micromambaEngine
        -BinaryShimGenerator shimGenerator
        -ArmThreadGovernor governor
        +installPackage(String envName, String packageName) InstallResult
    }

    class ArmThreadGovernor {
        -File sysCpuDir
        +detectTopology() CpuClusterTopology
        +getComputeEnvironmentVariables() Map~String, String~
        +getMimallocEnvironment(File casLibDir) Map~String, String~
        +getPythonOptimizationEnvironment() Map~String, String~
    }

    class BinaryShimGenerator {
        -File binDir
        +generateShim(ShimConfiguration) File
        +removeShim(String commandName) Boolean
        +hasShim(String commandName) Boolean
    }

    class UnifiedPackageSearchDb {
        -SQLiteDatabase database
        +indexPackages(List~UnifiedPackageRecord~)
        +search(String query, PackageTier tier) List~UnifiedPackageRecord~
        +getPackageById(String id) UnifiedPackageRecord
        +setInstalled(String id, Boolean installed)
    }

    PocketForgeManager --> PocketForgeVerifier : cryptographic validation
    PocketForgeManager --> PocketForgeManifestParser : parses schema
    PocketForgeManager --> UnifiedPackageSearchDb : populates FTS index
    PocketForgeManager --> BinaryShimGenerator : emits shims
    ApkStaticEngine --> AlpineDnsConfigurator : configures DNS
    ApkStaticEngine --> BinaryShimGenerator : emits Musl shims
    CondaEnvironmentManager --> MicromambaEngine : manages rootless conda
    CondaEnvironmentManager --> ArmThreadGovernor : compute thread pinning
    CondaEnvironmentManager --> BinaryShimGenerator : emits Glibc shims
```

### 7.2 Ingestion & Security Validation Sequence

```mermaid
sequenceDiagram
    autonumber
    actor User as User / App UI
    participant PFM as PocketForgeManager
    participant PFV as PocketForgeVerifier
    participant CAS as CasStoreEngine
    participant PE as ElfAlignmentEngine
    participant BSG as BinaryShimGenerator
    participant FTS as UnifiedPackageSearchDb

    User->>PFM: Add Custom Repository URL
    PFM->>PFV: verifyManifestSignature(manifest, signature, pubKey)
    alt Signature Fails
        PFV-->>PFM: Invalid Signature (REJECT)
        PFM-->>User: SecurityException: Corrupted or forged signature
    else Signature Passes
        PFV-->>PFM: Valid Minisign Ed25519 Signature (PASS)
        PFM->>FTS: Ingest package catalog (< 10ms)
        FTS-->>User: Instant live search results with tier badge
    end

    User->>PFM: Tap [Install Package]
    PFM->>PFV: verifySha256(downloadedArchive, expectedHash)
    alt SHA-256 Mismatch
        PFV-->>PFM: Checksum Mismatch (REJECT)
        PFM->>PFM: Quarantine cleanup (delete corrupted file)
        PFM-->>User: SecurityException: Package integrity compromised
    else Checksum Valid
        PFV-->>PFM: Valid SHA-256 (PASS)
        PFM->>CAS: Streaming Zstd decompression into CAS store
        CAS-->>PFM: Extracted to /files/store/<hash>-<name>/
        PFM->>PE: 16KB Page-Alignment Audit (p_align >= 0x4000)
        PE-->>PFM: 100% Android 15/16 compliant
        PFM->>BSG: generateShim(commandName, targetBin, prefixDir, envMap)
        BSG-->>PFM: Executable POSIX shim generated in /files/bin/
        PFM->>FTS: setInstalled(packageId, true)
        PFM-->>User: Installation Complete! Ready to run in 1-tap.
    end
```


