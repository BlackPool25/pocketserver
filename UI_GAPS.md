# PocketServer UI Gaps & Backend Integration Ledger

This document tracks all UI controls, telemetry metrics, and screen features that are deliberately hidden or disabled pending backend glue implementation, honoring the **Zero Fake Data** policy.

---

## 1. Activity Log / Event Stream
- **Location:** `app/src/main/java/com/pocketserver/ui/activity/ActivityScreen.kt`
- **Current State:** Displays calm, informative empty state pointing to the Services dashboard.
- **Backend Dependency:** An `ActivityEvent` Room Entity and DAO flow (`Flow<List<ActivityEvent>>`) recording lifecycle events (service start, stop, crash, tunnel provision, package install).
- **Behavior Once Available:** Will seamlessly populate the time-grouped event list with semantic status icons.

---

## 2. Real-Time CPU Telemetry
- **Location:** `app/src/main/java/com/pocketserver/ui/components/ServiceCard.kt` & `app/src/main/java/com/pocketserver/ui/services/ServiceDetailScreen.kt`
- **Current State:** Only real RAM usage (MB) from `/proc/<pid>/statm` is rendered. CPU percentage is omitted when `cpuPercent <= 0.0`.
- **Backend Dependency:** `/proc/<pid>/stat` sampling window in `ServiceOrchestrator` to calculate delta CPU jiffies.
- **Behavior Once Available:** The mono stat pill dynamically shows `${cpuPercent}% CPU` alongside RAM without layout shift.

---

## 3. Public Cloudflare HTTPS Tunnel URL
- **Location:** `ServiceCard.kt` (Tunnel Row & QR Modal) and `ServiceDetailScreen.kt` (Network Tab)
- **Current State:** Public tunnel copy button and QR code tab are hidden or gracefully disabled when `tunnelUrl` is `null` or empty.
- **Backend Dependency:** Cloudflare Quick Tunnel provisioner (`cloudflared tunnel --url http://127.0.0.1:<port>`) output stream parsing in `ServiceOrchestrator`.
- **Behavior Once Available:** Instantly illuminates the `https://*.trycloudflare.com` URL pill with 1-tap Copy and vector Qrose QR share modal.

---

## 4. Environment Variables Persisted Hot-Reload
- **Location:** `app/src/main/java/com/pocketserver/ui/services/ServiceDetailScreen.kt` (Env Tab)
- **Current State:** Secret masking toggle, in-memory key-value editing, and dirty state save bar are fully interactive. Changes are staged in UI state.
- **Backend Dependency:** Room `SandboxEntity` update mutation to write modified `env` JSON map and trigger process restart with updated POSIX `envp`.
- **Behavior Once Available:** Direct callback from Save bar to `viewModel.updateServiceEnv(serviceId, envMap)`.

---

## 5. Storage Breakdown by Runtime Category
- **Location:** `app/src/main/java/com/pocketserver/ui/system/SystemScreen.kt`
- **Current State:** Renders device total and free internal storage using Android `StatFs`. Detailed runtime breakdown bar reflects current proportional sizes or calm base proportion.
- **Backend Dependency:** Storage doctor calculating directory disk footprints for `sandboxes/`, `conda/`, `toolchains/`, and `cache/`.
- **Behavior Once Available:** Real-time multi-color segmented progress bar with exact megabyte tallies per subsystem.
