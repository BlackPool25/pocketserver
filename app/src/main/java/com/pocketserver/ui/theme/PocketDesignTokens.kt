package com.pocketserver.ui.theme

import android.graphics.Color

/**
 * Human-Centric Design Tokens for PocketServer.
 *
 * Rejects "AI Slop" clichés (harsh #000000 backgrounds, radioactive neon cyans/greens,
 * and intimidating hacker virtual keyboards).
 *
 * Implements a warm, approachable, calm aesthetic inspired by Vercel, Railway, and GitHub Mobile:
 * - Canvas Background: Neutral Zinc 950 (#09090B)
 * - Card Surface: Neutral Zinc 900 (#18181B) with friendly 14dp corner radii
 * - Interactive Inputs: Neutral Zinc 800 (#27272A)
 * - Hairline Borders: Neutral Zinc 800 (#27272A) 1px hairline divider
 * - Primary Brand Accent: Refined Indigo / Blurple (#6366F1)
 * - Status LIVE: Soft Sage Emerald (#10B981)
 * - Status SLEEPING: Soft Slate Blue (#64748B) - "Saving Battery"
 * - Status ATTENTION: Warm Amber (#F59E0B) - Low memory or thermal warning
 * - Status STOPPED: Muted Rose (#F43F5E) - Non-alarming stopped badge
 */
object PocketDesignTokens {

    // Canvas & Surface Palette (Zinc Scale)
    const val COLOR_CANVAS_HEX = "#09090B" // Zinc 950
    const val COLOR_SURFACE_HEX = "#18181B" // Zinc 900
    const val COLOR_SURFACE_ELEVATED_HEX = "#27272A" // Zinc 800
    const val COLOR_BORDER_HEX = "#27272A" // Zinc 800 hairline divider
    const val COLOR_BORDER_SUBTLE_HEX = "#3F3F46" // Zinc 700

    // Typography & Content Colors
    const val COLOR_TEXT_PRIMARY_HEX = "#F4F4F5" // Zinc 100
    const val COLOR_TEXT_SECONDARY_HEX = "#A1A1AA" // Zinc 400
    const val COLOR_TEXT_MUTED_HEX = "#71717A" // Zinc 500

    // Brand & Status Accents (Sky 400 accent, Zinc 500 stopped, Emerald 400 live, Blue 400 sleeping)
    const val COLOR_BRAND_INDIGO_HEX = "#38BDF8" // Replaced old Indigo with Sky 400 accent
    const val COLOR_STATUS_LIVE_HEX = "#34D399" // Emerald 400
    const val COLOR_STATUS_SLEEPING_HEX = "#60A5FA" // Blue 400
    const val COLOR_STATUS_ATTENTION_HEX = "#FBBF24" // Amber 400
    const val COLOR_STATUS_STOPPED_HEX = "#71717A" // Zinc 500 (Stopped ≠ error)

    // Parsed Color Integers for Android Canvas/Views
    val CanvasColor: Int = Color.parseColor(COLOR_CANVAS_HEX)
    val SurfaceColor: Int = Color.parseColor(COLOR_SURFACE_HEX)
    val SurfaceElevatedColor: Int = Color.parseColor(COLOR_SURFACE_ELEVATED_HEX)
    val BorderColor: Int = Color.parseColor(COLOR_BORDER_HEX)
    val BorderSubtleColor: Int = Color.parseColor(COLOR_BORDER_SUBTLE_HEX)

    val TextPrimaryColor: Int = Color.parseColor(COLOR_TEXT_PRIMARY_HEX)
    val TextSecondaryColor: Int = Color.parseColor(COLOR_TEXT_SECONDARY_HEX)
    val TextMutedColor: Int = Color.parseColor(COLOR_TEXT_MUTED_HEX)

    val BrandIndigoColor: Int = Color.parseColor(COLOR_BRAND_INDIGO_HEX)
    val StatusLiveColor: Int = Color.parseColor(COLOR_STATUS_LIVE_HEX)
    val StatusSleepingColor: Int = Color.parseColor(COLOR_STATUS_SLEEPING_HEX)
    val StatusAttentionColor: Int = Color.parseColor(COLOR_STATUS_ATTENTION_HEX)
    val StatusStoppedColor: Int = Color.parseColor(COLOR_STATUS_STOPPED_HEX)

    // Metric Dimensions (in density-independent pixels dp / scale-independent sp)
    const val CARD_CORNER_RADIUS_DP = 14f
    const val INPUT_CORNER_RADIUS_DP = 10f
    const val BADGE_CORNER_RADIUS_DP = 6f
    const val HAIRLINE_BORDER_WIDTH_DP = 1f

    // Typography Sizing
    const val TEXT_SIZE_TITLE_SP = 18f
    const val TEXT_SIZE_BODY_SP = 14f
    const val TEXT_SIZE_SUBTITLE_SP = 13f
    const val TEXT_SIZE_CODE_SP = 12f

    /**
     * Human-friendly status badge descriptions.
     * Replaces raw terminal codes or cryptic signals with plain language.
     */
    fun getHumanStatusDescription(status: String): String {
        return when (status.uppercase()) {
            "LIVE", "RUNNING", "ACTIVE" -> "Live • Running smoothly"
            "SLEEPING", "FROZEN" -> "Sleeping • Wakes instantly on click"
            "ATTENTION", "THROTTLED" -> "Attention • Thermal cooldown"
            "STOPPED" -> "Stopped • Ready to start"
            else -> status
        }
    }

    /**
     * Resolves the badge color integer for a given status string.
     */
    fun getStatusColor(status: String): Int {
        return when (status.uppercase()) {
            "LIVE", "RUNNING", "ACTIVE" -> StatusLiveColor
            "SLEEPING", "FROZEN" -> StatusSleepingColor
            "ATTENTION", "THROTTLED" -> StatusAttentionColor
            "STOPPED" -> StatusStoppedColor
            else -> TextMutedColor
        }
    }
}
