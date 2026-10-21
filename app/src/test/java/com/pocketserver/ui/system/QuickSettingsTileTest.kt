package com.pocketserver.ui.system

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class QuickSettingsTileTest {

    private lateinit var context: Context
    private lateinit var notificationController: PocketNotificationController

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        notificationController = PocketNotificationController(context)
        PocketServerTileService.setServerStatus(false, emptyList())
    }

    @After
    fun tearDown() {
        notificationController.cancel()
        PocketServerTileService.onToggleListener = null
    }

    @Test
    fun testQuickSettingsTileToggleAndNotificationUpdate() {
        val tileService = Robolectric.buildService(PocketServerTileService::class.java).create().get()

        var toggledState: Boolean? = null
        PocketServerTileService.onToggleListener = { active ->
            toggledState = active
        }

        PocketServerTileService.setServerStatus(false, listOf(8000, 3000))
        assertThat(PocketServerTileService.isServerActive.value).isFalse()

        // 1. User taps QS Tile from notification shade to toggle Active
        tileService.onClick()

        assertThat(PocketServerTileService.isServerActive.value).isTrue()
        assertThat(toggledState).isTrue()

        // 2. User taps QS Tile again to toggle Sleeping
        tileService.onClick()

        assertThat(PocketServerTileService.isServerActive.value).isFalse()
        assertThat(toggledState).isFalse()
    }

    @Test
    fun testNotificationActionsBuiltForLiveServices() {
        val notification = notificationController.buildNotification(
            liveCount = 2,
            sleepingCount = 0,
            activePorts = listOf(8000, 3000),
            publicTunnelUrl = "https://my-app.trycloudflare.com"
        )

        assertThat(notification).isNotNull()
        // Should have 3 actions: [ Pause All (Sleep) ], [ Copy Link ], [ Stop All ]
        assertThat(notification.actions).hasLength(3)

        val actionTitles = notification.actions.map { it.title.toString() }
        assertThat(actionTitles).contains("Pause All (Sleep)")
        assertThat(actionTitles).contains("Copy Link")
        assertThat(actionTitles).contains("Stop All")
    }

    @Test
    fun testNotificationActionsBuiltForSleepingServices() {
        val notification = notificationController.buildNotification(
            liveCount = 0,
            sleepingCount = 2,
            activePorts = emptyList()
        )

        assertThat(notification).isNotNull()
        val actionTitles = notification.actions.map { it.title.toString() }
        assertThat(actionTitles).contains("Resume All")
        assertThat(actionTitles).contains("Stop All")
    }
}
