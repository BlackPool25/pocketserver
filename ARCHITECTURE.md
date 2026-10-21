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
    end

    subgraph Storage_Layer["Content-Addressable Storage (CAS)"]
        CAS_Store["Global Immutable Store\n(/files/store/<hash>-<name>/)"]
        CAS_Db["Room Metadata Catalog & RefCount DB"]
        Zstd_Engine["Zstd-JNI Stream Extractor\n(> 1.2 GB/s Decompression)"]
        Apk_Engine["apk.static Transitive Resolver"]
        Elf_Patcher["16KB Alignment & RPATH Patcher"]
    end

    subgraph Sandboxes["Service Execution Sandboxes"]
        SB_A["Service Sandbox Alpha\n(Relative Symlink Rootfs)"]
        SB_B["Service Sandbox Beta\n(Relative Symlink Rootfs)"]
        Tmp_Shm["RAM tmpfs Scratchpad\n(/dev/shm & TMPDIR)"]
    end

    UI_Layer -->|Local IPC / Kotlin StateFlow| Host_Daemon
    Host_Daemon --> Proxy_Layer
    Host_Daemon --> Storage_Layer
    Proxy_Layer --> Sandboxes
    Storage_Layer -->|Read-Only Symlink Farm| Sandboxes
    Tmp_Shm -.->|RAM-backed ephemeral mounts| Sandboxes
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

## 4. Content-Addressable Storage (CAS) & Virtual Symlink Entity Model

To achieve hermetic isolation without requiring container virtualization (such as Docker, namespaces, or `chroot` which require root on Android), PocketServer enforces an **"Install Once, Link Everywhere"** storage design. All packages, runtime toolchains, and shared libraries reside in an immutable global content-addressable store indexed by SHA-256 hash. Per-service sandboxes are constructed dynamically using relative POSIX symlink farms.

```mermaid
erDiagram
    TOOLCHAIN_PACKAGE ||--o{ CAS_FILE_NODE : contains
    TOOLCHAIN_PACKAGE ||--o{ SANDBOX_SYMLINK : maps_into
    SERVICE_SANDBOX ||--o{ SANDBOX_SYMLINK : contains
    SERVICE_SANDBOX ||--o{ PROJECT_FILE : executes

    TOOLCHAIN_PACKAGE {
        string package_id PK "SHA-256 Hash"
        string name "e.g. python-3.12, bun-1.2"
        string version "Semantic version"
        int size_bytes "Total disk footprint"
        int ref_count "Active sandboxes linking this tool"
        string rpath "Embedded ELF runtime search path"
        boolean is_16kb_aligned "Verified 16KB page boundary"
    }

    CAS_FILE_NODE {
        string file_sha256 PK "Content SHA-256"
        string relative_path "e.g. bin/python, lib/libssl.so"
        int file_mode "POSIX permissions (e.g. 0755)"
        int byte_size "Uncompressed size"
    }

    SERVICE_SANDBOX {
        string service_id PK "UUID"
        string name "User-assigned project name"
        string rootfs_dir "Path inside context.filesDir"
        int assigned_port "Listening TCP port"
        string active_state "STOPPED, ACTIVE, SLEEPING"
    }

    SANDBOX_SYMLINK {
        string link_path PK "e.g. /sandbox/rootfs/usr/bin/python"
        string target_cas_path "Relative link: ../../../store/<hash>/bin/python"
        string link_type "DIRECTORY or REGULAR_FILE"
    }

    PROJECT_FILE {
        string file_path PK "Relative path in repository"
        int file_size "Bytes on disk"
        datetime modified_at "Timestamp"
    }
```

```mermaid
classDiagram
    class ContentAddressableStore {
        -File storeRootDir
        -ToolchainDatabase database
        +ingestPackage(File tarZstFile, String expectedHash) ToolchainPackage
        +verifyPackageIntegrity(String packageId) Boolean
        +incrementRefCount(String packageId) Unit
        +decrementRefCount(String packageId) Unit
        +runGarbageCollector() Int
    }

    class SymlinkFarmManager {
        -File sandboxesRootDir
        +createSandboxRootfs(String serviceId, List~String~ packageIds) File
        +linkToolchainIntoSandbox(File rootfsDir, ToolchainPackage pkg) Unit
        +teardownSandbox(String serviceId) Boolean
    }

    class ZstdExtractor {
        +extractTarZstStream(InputStream input, File targetDir) ExtractionResult
        +streamDirect(File archiveFile, File destinationDir) Long
    }

    class ElfAlignmentEngine {
        +verify16KbAlignment(File elfFile) AlignmentResult
        +inspectProgramHeaders(File elfFile) List~ProgramHeader~
        +verifyRpath(File elfFile, String expectedPrefix) Boolean
    }

    class ApkStaticManager {
        -File apkStaticBinary
        -File storeDir
        +resolveAndInstall(String packageName, File targetStoreDir) Boolean
        +queryDependencies(String packageName) List~String~
    }

    ContentAddressableStore --> ZstdExtractor : uses
    ContentAddressableStore --> ElfAlignmentEngine : verifies
    ContentAddressableStore --> ApkStaticManager : orchestrates
    SymlinkFarmManager ..> ContentAddressableStore : resolves links from
```

---

## 5. Core Systems Engineering Principles

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
Standard Android background mechanisms (such as `WorkManager`, `JobScheduler`, or basic `ForegroundService` with notification) are aggressively killed by aggressive vendor operating systems (Samsung One UI, Xiaomi HyperOS, OnePlus OxygenOS) within 3 to 10 minutes of screen lock.

PocketServer overcomes this via `PocketServerVpnService`:
1. It registers an internal dummy VPN tunnel using Android's `VpnService.Builder`.
2. The network interface assigns a non-routable local address (`10.245.0.1/32`) and binds only an internal dummy descriptor without establishing a default internet route (`0.0.0.0/0`).
3. Because the Android framework treats active VPN providers as foundational network infrastructure, OEM task killers classify the process as an untouchable core networking component.

### 4. Dual Wi-Fi Lock Governor & Radio Priming
Mobile Wi-Fi transceivers enter aggressive sleep cycles (DTIM listening intervals up to 1,000ms) when the device screen locks:
1. **Screen Off State:** PocketServer acquires `WifiManager.WIFI_MODE_FULL_HIGH_PERF`, preventing the hardware radio from powering down completely.
2. **Screen On / Active Connection State:** Dynamically acquires `WifiManager.WIFI_MODE_FULL_LOW_LATENCY` (API 29+), reducing ping jitter for real-time web development.
3. **Heartbeat Priming:** An outbound 15-second QUIC heartbeat ping maintains the NAT state table and primes baseband RF amplifiers, guaranteeing Time-To-First-Byte (TTFB) latency under **35ms** with the device screen locked.
