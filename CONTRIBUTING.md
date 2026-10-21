# Contributing to PocketServer

Thank you for your interest in contributing to PocketServer. As a systems-level mobile infrastructure project running unrooted polyglot services, PocketServer maintains strict quality, architectural consistency, and safety standards.

---

## 1. Developer Certificate of Origin (DCO)

All commits contributed to PocketServer must be accompanied by a Developer Certificate of Origin sign-off, confirming you have the legal right to submit the code under the Apache-2.0 license.

To sign your commit automatically:
```bash
git commit -s -m "feat(storage): implement atomic directory rotation"
```

This appends the standard DCO trailer to your commit message:
```text
Signed-off-by: Jane Doe <jane.doe@example.com>
```

---

## 2. Commit Message Conventions

We enforce [Conventional Commits](https://www.conventionalcommits.org/en/v1.0.0/) across all pull requests and branches:

- `feat:` Introduces a new feature or architectural capability.
- `fix:` Patches a bug or system failure mode.
- `arch:` High-level architectural configuration or structural refactoring.
- `test:` Introduces new automated unit, integration, or emulator tests.
- `docs:` Updates technical documentation or architectural diagrams.
- `refactor:` Modifies internal implementation without altering functional behavior.

Scope tags should clearly identify the affected system domain:
- `feat(persistence): add dual Wi-Fi lock governor`
- `fix(storage): resolve symlink traversal bug in sandbox rootfs`
- `feat(toolchain): integrate zstd-jni streaming tarball extractor`
- `arch(native): enforce 16KB max-page-size linker flags`

---

## 3. Engineering Constraints & Principles

All contributions must adhere to the following hard technical constraints:

### A. 16 KB Page Alignment
Android 15 and future releases require native binaries and shared objects to align on 16 KB boundary pages (`0x4000`). All native C/C++, Rust, and Go binaries must be compiled and linked with:
```bash
-Wl,-z,max-page-size=16384 -Wl,-z,common-page-size=16384
```
Unverified or 4 KB-aligned binaries will be rejected by automated CI checks.

### B. Strictly Non-Root
PocketServer never requires or requests superuser privileges. All operations, sandbox files, and toolchains must reside entirely within `context.filesDir` or ephemeral RAM tmpfs (`/dev/shm`). Hard assumptions about `/sdcard/` or external shared storage are forbidden due to Android FUSE symlink and `noexec` blocks.

### C. Human-Centric UI Design Tokens
Any visual contributions must adhere strictly to the **Tailwind Zinc 950 Design System**:
- Canvas Background: Neutral Zinc 950 (`#09090B`)
- Card Surfaces: Neutral Zinc 900 (`#18181B`)
- Interactive Inputs / Dividers: Neutral Zinc 800 (`#27272A`)
- Accents: Refined Indigo (`#6366F1`) and Sage Emerald (`#10B981`)
- No harsh pure `#000000` pitch-black, no neon greens, and no intrusive virtual hacker keyboards.

### D. Zero AI-Slop Code & Documentation
- Every line of code must be production-ready, compiling, and tested.
- No dummy mock stubs, empty interfaces, or unhandled `TODO` shortcuts.
- Documentation must be direct, technically precise, and devoid of marketing buzzwords.

---

## 4. Local Development Workflow

### Prerequisites
- JDK 21 (Temurin / OpenJDK 21+)
- Android SDK Platform 35 / 36
- Android NDK r28b or higher
- CMake 3.22.1+

### Build and Verification Commands
```bash
# Verify Gradle wrapper and toolchain resolution
./gradlew help

# Run unit tests across all core modules
./gradlew test

# Assemble debug artifacts
./gradlew assembleDebug
```

---

## 5. Submitting Pull Requests

1. Fork the repository and create your feature branch from `main`:
   ```bash
   git checkout -b feat/cas-garbage-collector
   ```
2. Commit your changes incrementally with `-s` sign-off:
   ```bash
   git commit -s -m "feat(storage): implement mark-and-sweep CAS garbage collection"
   ```
3. Run the complete automated test suite locally:
   ```bash
   ./gradlew check test
   ```
4. Open a pull request against `main`. Provide an architectural description of the changes and attach test verification output.
