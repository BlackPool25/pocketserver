package com.pocketserver.ui.theme

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PocketDesignTokensTest {

    @Test
    fun testZinc950DesignTokensIntegrity() {
        assertThat(PocketDesignTokens.COLOR_CANVAS_HEX).isEqualTo("#09090B")
        assertThat(PocketDesignTokens.COLOR_SURFACE_HEX).isEqualTo("#18181B")
        assertThat(PocketDesignTokens.COLOR_BORDER_HEX).isEqualTo("#27272A")
        assertThat(PocketDesignTokens.COLOR_BRAND_INDIGO_HEX).isEqualTo("#38BDF8")
        assertThat(PocketDesignTokens.COLOR_STATUS_LIVE_HEX).isEqualTo("#34D399")

        assertThat(PocketDesignTokens.CARD_CORNER_RADIUS_DP).isEqualTo(14f)
        assertThat(PocketDesignTokens.HAIRLINE_BORDER_WIDTH_DP).isEqualTo(1f)
    }

    @Test
    fun testHumanStatusDescriptions() {
        assertThat(PocketDesignTokens.getHumanStatusDescription("LIVE"))
            .isEqualTo("Live • Running smoothly")
        assertThat(PocketDesignTokens.getHumanStatusDescription("RUNNING"))
            .isEqualTo("Live • Running smoothly")
        assertThat(PocketDesignTokens.getHumanStatusDescription("SLEEPING"))
            .isEqualTo("Sleeping • Wakes instantly on click")
        assertThat(PocketDesignTokens.getHumanStatusDescription("STOPPED"))
            .isEqualTo("Stopped • Ready to start")
    }

    @Test
    fun testStatusColorResolution() {
        assertThat(PocketDesignTokens.getStatusColor("LIVE"))
            .isEqualTo(PocketDesignTokens.StatusLiveColor)
        assertThat(PocketDesignTokens.getStatusColor("SLEEPING"))
            .isEqualTo(PocketDesignTokens.StatusSleepingColor)
        assertThat(PocketDesignTokens.getStatusColor("STOPPED"))
            .isEqualTo(PocketDesignTokens.StatusStoppedColor)
    }
}
