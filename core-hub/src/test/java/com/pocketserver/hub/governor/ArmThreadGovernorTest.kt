package com.pocketserver.hub.governor

import com.google.common.truth.Truth.assertThat
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class ArmThreadGovernorTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var sysCpuDir: File

    @Before
    fun setUp() {
        sysCpuDir = tempFolder.newFolder("cpu")
    }

    @Test
    fun testDetectHeterogeneousBigLittleTopology() {
        // Mock an 8-core DynamIQ SoC: 4 LITTLE (Silver @ 1.8GHz) + 3 Gold (@ 2.8GHz) + 1 Prime (X-Core @ 3.2GHz)
        for (i in 0..3) {
            val dir = File(sysCpuDir, "cpu$i/cpufreq").apply { mkdirs() }
            File(dir, "cpuinfo_max_freq").writeText("1800000\n")
        }
        for (i in 4..6) {
            val dir = File(sysCpuDir, "cpu$i/cpufreq").apply { mkdirs() }
            File(dir, "cpuinfo_max_freq").writeText("2800000\n")
        }
        val primeDir = File(sysCpuDir, "cpu7/cpufreq").apply { mkdirs() }
        File(primeDir, "cpuinfo_max_freq").writeText("3200000\n")

        val governor = ArmThreadGovernor(sysCpuDir)
        val topology = governor.detectTopology()

        assertThat(topology.totalCores).isEqualTo(8)
        assertThat(topology.isHeterogeneous).isTrue()
        // Performance cores (3 Gold + 1 Prime = 4)
        assertThat(topology.performanceCores).isEqualTo(4)
        assertThat(topology.efficiencyCores).isEqualTo(4)

        val env = governor.getComputeEnvironmentVariables()
        // Mitigates DynamIQ straggler effect by restricting OpenMP threads to 4 instead of 8
        assertThat(env["OPENBLAS_NUM_THREADS"]).isEqualTo("4")
        assertThat(env["OMP_NUM_THREADS"]).isEqualTo("4")
        assertThat(env["OMP_PLACES"]).isEqualTo("cores")
        assertThat(env["OMP_PROC_BIND"]).isEqualTo("close")
    }

    @Test
    fun testMimallocEnvironmentInjection() {
        val casLibDir = tempFolder.newFolder("lib")
        val mimallocSo = File(casLibDir, "libmimalloc.so").apply {
            writeBytes("ELF MIMALLOC STUB".toByteArray())
        }

        val governor = ArmThreadGovernor(sysCpuDir)
        val env = governor.getMimallocEnvironment(casLibDir)

        assertThat(env).containsKey("LD_PRELOAD")
        assertThat(env["LD_PRELOAD"]).isEqualTo(mimallocSo.absolutePath)
    }

    @Test
    fun testPythonBytecodeEvaporation() {
        val shmDir = tempFolder.newFolder("shm")
        val governor = ArmThreadGovernor(sysCpuDir)

        val env = governor.getPythonOptimizationEnvironment(shmDir)
        assertThat(env).containsKey("PYTHONPYCACHEPREFIX")
        assertThat(env["PYTHONPYCACHEPREFIX"]).contains("pycache")
    }
}
