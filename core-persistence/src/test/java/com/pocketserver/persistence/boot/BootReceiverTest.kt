package com.pocketserver.persistence.boot

import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class BootReceiverTest {

    private lateinit var context: Context
    private lateinit var receiver: BootReceiver

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        receiver = BootReceiver()
    }

    @Test
    fun `preferences reflect auto-start toggling`() {
        PersistencePreferences.setAutoStartOnBoot(context, false)
        assertThat(PersistencePreferences.isAutoStartOnBoot(context)).isFalse()

        PersistencePreferences.setAutoStartOnBoot(context, true)
        assertThat(PersistencePreferences.isAutoStartOnBoot(context)).isTrue()
    }

    @Test
    fun `boot receiver with auto-start enabled starts service`() {
        PersistencePreferences.setAutoStartOnBoot(context, true)

        val bootIntent = Intent(Intent.ACTION_BOOT_COMPLETED)
        receiver.onReceive(context, bootIntent)

        val shadowApp = shadowOf(context as android.app.Application)
        val nextStartedService = shadowApp.nextStartedService
        assertThat(nextStartedService).isNotNull()
        assertThat(nextStartedService.component?.className).contains("PocketServerHostService")
    }

    @Test
    fun `boot receiver with auto-start disabled does not start service`() {
        PersistencePreferences.setAutoStartOnBoot(context, false)

        val bootIntent = Intent(Intent.ACTION_BOOT_COMPLETED)
        receiver.onReceive(context, bootIntent)

        val shadowApp = shadowOf(context as android.app.Application)
        val nextStartedService = shadowApp.nextStartedService
        assertThat(nextStartedService).isNull()
    }
}
