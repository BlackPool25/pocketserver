package com.pocketserver.ui.main

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = com.pocketserver.PocketServerApplication::class, sdk = [34])
class MainActivityTest {

    @Test
    fun testMainActivityLaunchesSuccessfully() {
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        val activity = controller.get()

        assertThat(activity).isNotNull()
        assertThat(activity.isFinishing).isFalse()
    }
}
