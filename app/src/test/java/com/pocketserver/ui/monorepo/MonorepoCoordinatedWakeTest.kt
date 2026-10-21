package com.pocketserver.ui.monorepo

import com.google.common.truth.Truth.assertThat
import com.pocketserver.ui.onboarding.repo.MonorepoLink
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MonorepoCoordinatedWakeTest {

    private lateinit var coordinator: MonorepoWakeCoordinator

    @Before
    fun setUp() {
        coordinator = MonorepoWakeCoordinator()
    }

    @Test
    fun testCoordinatedWakeSequenceWithinTwoMilliseconds() {
        runBlocking {
            val backendId = "backend-" + UUID.randomUUID().toString()
            val frontendId = "frontend-" + UUID.randomUUID().toString()

            val link = MonorepoLink(
                monorepoId = "monorepo-" + UUID.randomUUID().toString(),
                repoUrl = "https://github.com/developer/app.git",
                backendServiceId = backendId,
                frontendServiceId = frontendId,
                allServiceIds = listOf(frontendId, backendId)
            )
            coordinator.registerLink(link)

            val unfreezeOrder = mutableListOf<String>()
            val unfreezeCounter = AtomicInteger(0)

            // Simulate native unfreeze action (SIGCONT / epoll wake)
            val mockUnfreeze: suspend (String) -> Long = { serviceId ->
                val start = System.nanoTime()
                unfreezeOrder.add(serviceId)
                unfreezeCounter.incrementAndGet()
                // Micro-duration simulating SIGCONT unfreeze (< 0.1ms)
                System.nanoTime() - start
            }

            // Trigger wake from frontend QR code access
            val result = coordinator.wakeCoordinated(
                sourceServiceId = frontendId,
                unfreezeAction = mockUnfreeze
            )

            // Verify that both frontend and backend were unfrozen in sequence
            assertThat(result.awakenedServiceIds).containsExactly(frontendId, backendId).inOrder()
            assertThat(unfreezeOrder).containsExactly(frontendId, backendId).inOrder()
            assertThat(unfreezeCounter.get()).isEqualTo(2)

            // Acceptance Criteria: Coordinated unfreeze sequence completes in < 2ms (< 2,000,000 ns)
            assertThat(result.totalLatencyMillis).isLessThan(2.0)
        }
    }

    @Test
    fun testStandaloneServiceWakeWithoutLinks() {
        runBlocking {
            val standaloneId = "standalone-" + UUID.randomUUID().toString()
            val unfreezeList = mutableListOf<String>()

            val result = coordinator.wakeCoordinated(
                sourceServiceId = standaloneId,
                unfreezeAction = { id ->
                    unfreezeList.add(id)
                    100_000L
                }
            )

            assertThat(result.awakenedServiceIds).containsExactly(standaloneId)
            assertThat(unfreezeList).containsExactly(standaloneId)
        }
    }
}
