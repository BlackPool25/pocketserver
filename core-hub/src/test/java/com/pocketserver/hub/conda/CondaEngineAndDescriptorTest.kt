package com.pocketserver.hub.conda

import com.google.common.truth.Truth.assertThat
import com.pocketserver.hub.search.PackageTier
import com.pocketserver.hub.shim.BinaryShimGenerator
import kotlinx.coroutines.runBlocking
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
class CondaEngineAndDescriptorTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var condaRoot: File
    private lateinit var binDir: File
    private lateinit var shimGenerator: BinaryShimGenerator

    @Before
    fun setUp() {
        condaRoot = tempFolder.newFolder("conda_root")
        binDir = tempFolder.newFolder("bin")
        shimGenerator = BinaryShimGenerator(binDir)
    }

    private val sampleRepodataJson = """
        {
          "packages.conda": {
            "pytorch-2.4.0-cpu_py312h1234567_0.conda": {
              "name": "pytorch",
              "version": "2.4.0",
              "build": "cpu_py312h1234567_0",
              "build_number": 0,
              "subdir": "linux-aarch64",
              "size": 75000000,
              "sha256": "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
              "depends": [
                "python >=3.12",
                "numpy >=1.26",
                "openblas"
              ]
            },
            "scipy-1.14.0-py312_0.conda": {
              "name": "scipy",
              "version": "1.14.0",
              "build": "py312_0",
              "build_number": 0,
              "subdir": "linux-aarch64",
              "size": 22000000,
              "sha256": "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
              "depends": [
                "numpy >=1.26"
              ]
            }
          }
        }
    """.trimIndent()

    @Test
    fun testParseCondaRepodataJson() {
        val packages = CondaPackageDescriptor.parseRepodata(sampleRepodataJson)
        assertThat(packages.size).isEqualTo(2)

        val pytorch = packages.find { it.name == "pytorch" }
        assertThat(pytorch).isNotNull()
        assertThat(pytorch!!.version).isEqualTo("2.4.0")
        assertThat(pytorch.build).isEqualTo("cpu_py312h1234567_0")
        assertThat(pytorch.sizeBytes).isEqualTo(75000000L)
        assertThat(pytorch.depends).contains("openblas")

        val unifiedRecord = pytorch.toUnifiedRecord()
        assertThat(unifiedRecord.tier).isEqualTo(PackageTier.CONDA)
        assertThat(unifiedRecord.category).isEqualTo("AI & Computer Vision")
        assertThat(unifiedRecord.downloadUrl).contains("pytorch-2.4.0-cpu_py312h1234567_0.conda")
    }

    @Test
    fun testMicromambaConfigFileWrittenCorrectly() {
        val fakeMambaBin = tempFolder.newFile("micromamba").apply {
            writeText("#!/bin/sh\nexit 0")
            setExecutable(true)
        }

        val engine = MicromambaEngine(fakeMambaBin, condaRoot)
        assertThat(engine.configFile.exists()).isTrue()

        val configText = engine.configFile.readText()
        // Requirements: repodata_use_zst: true and use_jlap: true
        assertThat(configText).contains("repodata_use_zst: true")
        assertThat(configText).contains("use_jlap: true")
        assertThat(configText).contains("channels:")
        assertThat(configText).contains("conda-forge")
    }

    @Test
    fun testCondaEnvironmentManagerGeneratesOptimizedShim() = runBlocking {
        // Mock a script behaving as micromamba install
        val fakeMambaBin = tempFolder.newFile("micromamba_mock").apply {
            writeText(
                """
                #!/bin/sh
                # Create mocked prefix directory structure
                PREFIX=""
                for arg in "$@"; do
                  if [ "${'$'}PREV" = "--prefix" ]; then
                    PREFIX="${'$'}arg"
                  fi
                  PREV="${'$'}arg"
                done
                if [ -n "${'$'}PREFIX" ]; then
                  mkdir -p "${'$'}PREFIX/bin"
                  mkdir -p "${'$'}PREFIX/lib"
                  echo "#!/bin/sh" > "${'$'}PREFIX/bin/scipy-test"
                  chmod +x "${'$'}PREFIX/bin/scipy-test"
                fi
                exit 0
                """.trimIndent()
            )
            setExecutable(true)
        }

        val engine = MicromambaEngine(fakeMambaBin, condaRoot)
        val manager = CondaEnvironmentManager(engine, shimGenerator)

        val result = manager.installPackage("ai-env", "scipy-test")
        assertThat(result.isSuccess).isTrue()
        assertThat(result.shimFile).isNotNull()
        assertThat(result.shimFile!!.exists()).isTrue()

        val shimContent = result.shimFile!!.readText()
        // Must contain governor optimizations and prefix
        assertThat(shimContent).contains("export PREFIX=")
        assertThat(shimContent).contains("export OPENBLAS_NUM_THREADS=")
        assertThat(shimContent).contains("export PYTHONPYCACHEPREFIX=")
    }
}
