package com.pocketserver.persistence.power

import android.content.Context
import android.os.PowerManager
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PowerGovernorTest {

    private lateinit var context: Context
    private lateinit var governor: PowerGovernor

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        governor = PowerGovernor(context)
    }

    @After
    fun tearDown() {
        governor.teardown()
    }

    @Test
    fun `initial thermal state is NOMINAL`() {
        assertThat(governor.thermalState.value).isEqualTo(PowerGovernor.ThermalState.NOMINAL)
    }

    @Test
    fun `acquireWakeLock and releaseWakeLock manage wake lock state`() {
        governor.acquireWakeLock()
        assertThat(governor.isWakeLockHeld.value).isTrue()

        governor.releaseWakeLock()
        assertThat(governor.isWakeLockHeld.value).isFalse()
    }

    @Test
    fun `samplePowerTelemetry produces non-negative power readings`() {
        val telemetry = governor.samplePowerTelemetry()
        assertThat(telemetry.wattageWatts).isAtLeast(0.0)
        assertThat(telemetry.batteryPercentage).isIn(com.google.common.collect.Range.closed(0, 100))
    }
}
