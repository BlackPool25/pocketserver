# PocketServer Design System Specification (DESIGN.md)

**Status:** LAW (Single Source of Truth)  
**Target:** Android Jetpack Compose + Material 3  
**Tone:** Calm, trustworthy, technical. Railway / Vercel on a mobile device.

---

## 1. Design Philosophy & Principles

1. **Information Density with Breathing Room:** Designed for developers who need instantaneous answers: *Is it running? What is its URL? What is it costing my battery?*
2. **Quiet Surfaces, Hairline Borders:** Never use drop shadows or heavy blur cards. Surfaces are defined by tonal step-ups (`#09090B` → `#111113` → `#18181B`) bordered with subtle 1dp hairlines (`#27272A`).
3. **Status is Precise, Never Decorative:** Colors are reserved strictly for semantic states (Running, Sleeping, Starting, Error, Stopped). Every status indicator pairs a specific semantic color with an explicit text label and icon (never color alone).
4. **Sentence Case Everywhere:** No all-caps buttons, labels, or badges. No `HOST ACTIVE` or `DISMISS`. Use `Host active` and `Dismiss`.
5. **Zero Marketing Fluff:** Banished phrases include "Welcome to...", "Zero VM Overhead", and generic booster text. All copy must be factual, plain English, and technical without unexplained jargon.

---

## 2. Color System & Contrast Ratios

### Dark Theme (Default)
| Token Name | Hex Code | Purpose | Contrast vs Background |
| :--- | :--- | :--- | :--- |
| **`Background`** | `#09090B` | Root window background (Zinc 950) | Baseline |
| **`Surface`** | `#111113` | Primary cards, sheets, top app bar | N/A (Tonal step 1) |
| **`SurfaceRaised`** | `#18181B` | Dropdown menus, dialogs, elevated chips | N/A (Tonal step 2) |
| **`Border`** | `#27272A` | 1dp card borders, dividers, chip outlines | 1.8:1 (Hairline) |
| **`TextPrimary`** | `#FAFAFA` | Main headings, service names, body copy | **17.5:1** (WCAG AAA) |
| **`TextSecondary`** | `#A1A1AA` | Subtitles, secondary metadata, port labels | **8.1:1** (WCAG AAA) |
| **`TextTertiary`** | `#71717A` | Timestamps, inactive states, hints | **4.6:1** (WCAG AA Pass) |
| **`PrimaryAction`** | `#FAFAFA` | High-contrast Vercel button fill | With `#09090B` text: **17.5:1** |
| **`ActionOnDark`** | `#09090B` | Text color on primary action button | **17.5:1** |
| **`Accent`** | `#38BDF8` | Focus rings, active tabs, clickable links | **9.2:1** |

### Light Theme (Optional Toggle)
| Token Name | Hex Code | Purpose | Contrast vs Background |
| :--- | :--- | :--- | :--- |
| **`Background`** | `#FAFAFA` | Root window background (Zinc 50) | Baseline |
| **`Surface`** | `#FFFFFF` | Primary cards, sheets | N/A |
| **`SurfaceRaised`** | `#F4F4F5` | Elevated surfaces (Zinc 100) | N/A |
| **`Border`** | `#E4E4E7` | 1dp dividers and borders (Zinc 200) | 1.3:1 (Hairline) |
| **`TextPrimary`** | `#09090B` | Main headings and text | **17.5:1** |
| **`TextSecondary`** | `#71717A` | Metadata and secondary text | **4.6:1** |
| **`TextTertiary`** | `#A1A1AA` | Timestamps and captions | **2.2:1** |
| **`PrimaryAction`** | `#09090B` | High-contrast dark button fill | With `#FAFAFA` text: **17.5:1** |
| **`ActionOnLight`** | `#FAFAFA` | Text on dark button | **17.5:1** |
| **`Accent`** | `#0284C7` | Links, active tab indicator | **4.8:1** |

### Semantic Status Palette (Accessible & Consistent Across Themes)
| Status | Color Hex | Accompanying Icon | Text Label | Plain English Explanation |
| :--- | :--- | :--- | :--- | :--- |
| **Running** | `#34D399` | Solid dot / Play | `Running` | Active process serving traffic. |
| **Sleeping** | `#60A5FA` | Pause / Moon | `Sleeping` | Paused (SIGSTOP) to save battery; wakes in <1ms on incoming request. |
| **Starting / Warning**| `#FBBF24` | Spinner / Pulsing dot| `Starting` | Initializing or compiling. |
| **Error** | `#F87171` | Exclamation triangle | `Error` | Process crashed or port conflict. |
| **Stopped** | `#71717A` | Outlined square / Off | `Stopped` | Service is shut down; zero resources used. |

---

## 3. Typography Scale & Font Pairing

PocketServer uses **System Sans** (or Geist Sans) for all UI copy and **System Mono** (or JetBrains Mono) for all technical identifiers (ports, URLs, hashes, paths, logs).

| Style | Font Family | Size (sp) | Weight | Line Height (sp) | Tracking | Usage |
| :--- | :--- | :--- | :--- | :--- | :--- | :--- |
| **Display** | Sans | 28 | Bold (700) | 34 | -0.5sp | Large stat counters, screen top headers |
| **Title** | Sans | 20 | SemiBold (600)| 26 | -0.2sp | Screen headers, modal sheet titles |
| **Section** | Sans | 16 | SemiBold (600)| 22 | 0sp | Card headers, section dividers |
| **Body** | Sans | 14 | Regular (400) | 20 | 0sp | General text, descriptions, lists |
| **BodyMedium** | Sans | 14 | Medium (500) | 20 | 0sp | Button labels, interactive rows |
| **Caption** | Sans | 12 | Regular (400) | 16 | 0.2sp | Badges, timestamps, secondary hints |
| **CodeBlock** | Mono | 13 | Regular (400) | 18 | 0sp | Log stream, editor buffers |
| **CodeInline**| Mono | 12 | Medium (500) | 16 | 0sp | Ports (`:8000`), URLs, file paths, SHA |

---

## 4. Spacing, Shapes & Layout Grid

- **Base Grid:** Strictly 8dp base unit (`4dp`, `8dp`, `12dp`, `16dp`, `24dp`, `32dp`, `48dp`).
- **Corner Radii:**
  - Cards & Containers: `12dp`.
  - Buttons: `10dp`.
  - Input Fields: `10dp`.
  - Chips & Pills: Full / Circle (`999dp`).
  - Sheets & Dialogs: `16dp` top corners.
- **Elevation:** Zero drop shadows. Depth is achieved entirely through background color step-ups and 1dp `#27272A` hairline borders.
- **Touch Target Floor:** All interactive elements must measure at least **48dp × 48dp** touch target area.

---

## 5. Navigation & Shell Architecture

Single-Activity architecture with **Navigation Compose** and 4 primary bottom navigation tabs:
1. **Services (Home):** Active node status, running/sleeping services, quick open/share, starter templates.
2. **Packages:** High-speed SQLite FTS5 package search, tier badges, package details, 4-stage inline installation.
3. **Activity:** Chronological system events (started, slept, woke, crashed) and unified searchable log viewer.
4. **System:** Loopback VPN Shield toggle, Storage Doctor breakdown and deep purge, Wi-Fi locks, Power reliability & OEM guidance, About.

---

## 6. Motion & Micro-Interactions

- **Duration:** 150ms – 250ms with `FastOutSlowInEasing` / `EaseOutQuad`.
- **Status Indicators:**
  - `Starting`: Smooth pulsing alpha opacity (0.4f to 1.0f, 800ms period).
  - `Sleeping` → `Running`: Quick 150ms color transition with ripple.
  - `Running`: Static solid emerald dot. No distracting looping animations.
- **Accessibility:** Must respect `LocalContext.current.resources.configuration.fontScale` up to 200% without text truncation, and honor system reduced motion flags.

---

## 7. Strict Ban List (Automated Rejection Criteria)

Any component containing the following will be rejected:
- ❌ Purple / indigo-to-pink gradient backgrounds or banners.
- ❌ Three-column feature grids or marketing hero cards.
- ❌ Centered "Welcome to PocketServer" or "Zero VM Overhead" copy.
- ❌ Giant rounded bubble buttons (>16dp radius).
- ❌ Glassmorphism, blurred backdrops, or neon glows.
- ❌ Emojis used as system UI icons (use standard Outlined Material Symbols).
- ❌ Stock "rocket" or "space" illustrations.
- ❌ Fake or hardcoded charts, numbers, or telemetry.
- ❌ All-caps button text (`START`, `CANCEL`).
