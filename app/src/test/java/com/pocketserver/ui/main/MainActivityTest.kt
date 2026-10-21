package com.pocketserver.ui.main

import android.view.WindowManager
import com.google.common.truth.Truth.assertThat
import com.pocketserver.ui.dashboard.ServiceCardModel
import com.pocketserver.ui.theme.PocketDesignTokens
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MainActivityTest {

    @Test
    fun testMainActivityThemeAndStatusBarColor() {
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        val activity = controller.get()

        // Verify status bar and navigation bar are set to Zinc 950 Dark (#09090B)
        assertThat(activity.window.statusBarColor).isEqualTo(PocketDesignTokens.CanvasColor)
        assertThat(activity.window.navigationBarColor).isEqualTo(PocketDesignTokens.CanvasColor)
    }

    @Test
    fun testShareModalAutoBrightnessBoostCycle() {
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        val activity = controller.get()

        val sampleService = ServiceCardModel(
            serviceId = "test-service-1",
            name = "my-fastapi",
            framework = "Python FastAPI",
            assignedPort = 8000,
            localUrl = "http://192.168.1.105:8000",
            mDnsUrl = "my-fastapi.local"
        )

        // When user taps Share
        activity.onShareClicked(sampleService)
        assertThat(activity.window.attributes.screenBrightness).isEqualTo(1.0f)
        val hasKeepScreenOn = (activity.window.attributes.flags and WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) != 0
        assertThat(hasKeepScreenOn).isTrue()

        // When user dismisses Share modal
        activity.onDismissShareModal()
        assertThat(activity.window.attributes.screenBrightness).isNotEqualTo(1.0f)
    }
}
