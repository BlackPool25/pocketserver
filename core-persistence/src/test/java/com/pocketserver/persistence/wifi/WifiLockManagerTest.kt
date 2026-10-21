package com.pocketserver.persistence.wifi

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class WifiLockManagerTest {

    private lateinit var context: Context
    private lateinit var manager: WifiLockManager

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        manager = WifiLockManager(context)
    }

    @Test
    fun `initial lock mode is RELEASED`() {
        assertThat(manager.currentMode.value).isEqualTo(WifiLockManager.LockMode.RELEASED)
    }

    @Test
    fun `acquireHighPerf engages HIGH_PERF mode`() {
        manager.acquireHighPerf()
        assertThat(manager.currentMode.value).isEqualTo(WifiLockManager.LockMode.HIGH_PERF)
    }

    @Test
    fun `acquireLowLatency engages LOW_LATENCY mode on API 29+`() {
        manager.acquireLowLatency()
        assertThat(manager.currentMode.value).isEqualTo(WifiLockManager.LockMode.LOW_LATENCY)
    }

    @Test
    fun `releaseAll sets state to RELEASED`() {
        manager.acquireHighPerf()
        assertThat(manager.currentMode.value).isEqualTo(WifiLockManager.LockMode.HIGH_PERF)

        manager.releaseAll()
        assertThat(manager.currentMode.value).isEqualTo(WifiLockManager.LockMode.RELEASED)
    }
}
