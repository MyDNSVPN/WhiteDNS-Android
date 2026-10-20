package shop.whitedns.client.cottendns

import android.content.Context
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit
import java.util.UUID
import kotlin.concurrent.thread
import shop.whitedns.client.model.ConnectionProfile
import shop.whitedns.client.model.CottenDnsServerProfile
import shop.whitedns.client.model.WhiteDnsSettings
import shop.whitedns.client.phantomdns.PhantomDnsConfigRenderer

data class CottenDnsLaunchSpec(
    val binaryFile: File,
    val workingDirectory: File,
    val configFile: File,
    val resolversFile: File,
)

class CottenDnsProcessManager(
    private val context: Context,
    private val binaryInstaller: CottenDnsBinaryInstaller = CottenDnsBinaryInstaller(context),
) {

    private val processLock = Any()
    private var process: Process? = null
    private var currentLaunchSpec: CottenDnsLaunchSpec? = null
    private var outputDrainThread: Thread? = null

    fun prepareLaunch(
        serverProfile: CottenDnsServerProfile,
        settings: WhiteDnsSettings,
    ): CottenDnsLaunchSpec {
        val runtimeDir = File(context.noBackupFilesDir, "CottenDns/runtime").apply {
            mkdirs()
        }
        cleanupStaleLaunchFiles(runtimeDir)
        // The launch mechanics (exec a native binary with -config/-resolvers,
        // drain stdout) are identical across engines; only the binary and the
        // config dialect differ, so route those two by the selected engine and
        // keep everything else shared. CottenDns is the default and unchanged.
        val isPhantom = ConnectionProfile.normalizeEngine(settings.engine) ==
            ConnectionProfile.EnginePhantomDns
        val binaryFile = binaryInstaller.installExecutable(
            if (isPhantom) {
                CottenDnsBinaryInstaller.PhantomDnsLibraryName
            } else {
                CottenDnsBinaryInstaller.CottenDnsLibraryName
            },
        )
        val launchId = UUID.randomUUID().toString()
        val configFile = File(runtimeDir, ".wd-$launchId.toml")
        val resolversFile = File(runtimeDir, ".wd-$launchId.resolvers")

        configFile.writeText(
            if (isPhantom) {
                PhantomDnsConfigRenderer.renderClientToml(serverProfile, settings)
            } else {
                CottenDnsConfigRenderer.renderClientToml(serverProfile, settings)
            },
        )
        resolversFile.writeText(CottenDnsConfigRenderer.renderResolvers(settings))

        return CottenDnsLaunchSpec(
            binaryFile = binaryFile,
            workingDirectory = runtimeDir,
            configFile = configFile,
            resolversFile = resolversFile,
        )
    }

    fun start(
        serverProfile: CottenDnsServerProfile,
        settings: WhiteDnsSettings,
        onOutput: (String) -> Unit = {},
    ): CottenDnsLaunchSpec {
        stop()
        val launchSpec = prepareLaunch(serverProfile, settings)
        onOutput("Runtime prepared")
        try {
            val startedProcess = ProcessBuilder(
                launchSpec.binaryFile.absolutePath,
                "-config",
                launchSpec.configFile.absolutePath,
                "-resolvers",
                launchSpec.resolversFile.absolutePath,
            )
                .directory(launchSpec.workingDirectory)
                .redirectErrorStream(true)
                .start()
            val drainThread = drainProcessOutput(startedProcess, onOutput)
            synchronized(processLock) {
                currentLaunchSpec = launchSpec
                process = startedProcess
                outputDrainThread = drainThread
            }
            drainThread.start()
            onOutput("CottenDns process started")
        } catch (error: IOException) {
            cleanupLaunchFiles(launchSpec)
            throw error
        }
        return launchSpec
    }

    fun stop(gracePeriodMillis: Long = 1_500) {
        val activeProcess: Process
        val drainThread: Thread?
        synchronized(processLock) {
            activeProcess = process ?: return
            drainThread = outputDrainThread
        }
        activeProcess.destroy()
        try {
            activeProcess.waitFor(gracePeriodMillis, TimeUnit.MILLISECONDS)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }
        if (activeProcess.isAlive) {
            activeProcess.destroyForcibly()
            try {
                activeProcess.waitFor(gracePeriodMillis, TimeUnit.MILLISECONDS)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
            }
        }
        synchronized(processLock) {
            if (process === activeProcess) {
                process = null
                outputDrainThread = null
                cleanupLaunchFilesLocked()
            }
        }
        if (drainThread != null && drainThread !== Thread.currentThread()) {
            try {
                drainThread.join(OutputDrainJoinMillis)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
            }
        }
    }

    fun cleanupLaunchFiles() {
        synchronized(processLock) {
            cleanupLaunchFilesLocked()
        }
    }

    private fun cleanupLaunchFiles(launchSpec: CottenDnsLaunchSpec) {
        runCatching { launchSpec.configFile.delete() }
        runCatching { launchSpec.resolversFile.delete() }
    }

    private fun cleanupLaunchFilesLocked() {
        val launchSpec = currentLaunchSpec ?: return
        cleanupLaunchFiles(launchSpec)
        currentLaunchSpec = null
    }

    fun isRunning(): Boolean {
        return synchronized(processLock) {
            process?.isAlive == true
        }
    }

    fun exitCodeOrNull(): Int? {
        val activeProcess = synchronized(processLock) {
            process
        } ?: return null
        if (activeProcess.isAlive) {
            return null
        }
        return synchronized(processLock) {
            if (process !== activeProcess) {
                return@synchronized null
            }
            val exitCode = activeProcess.exitValue()
            process = null
            outputDrainThread = null
            cleanupLaunchFilesLocked()
            exitCode
        }
    }

    private fun drainProcessOutput(
        process: Process,
        onOutput: (String) -> Unit,
    ): Thread {
        return thread(
            name = "CottenDns-output",
            isDaemon = true,
            start = false,
        ) {
            try {
                process.inputStream.bufferedReader().useLines { lines ->
                    lines.forEach { line ->
                        if (line.isNotBlank()) {
                            onOutput(line)
                        }
                    }
                }
            } catch (_: IOException) {
                // Destroying the process closes this stream on another thread during normal shutdown.
            } finally {
                cleanupExitedProcess(process)
            }
        }
    }

    private fun cleanupExitedProcess(finishedProcess: Process) {
        synchronized(processLock) {
            if (process !== finishedProcess || finishedProcess.isAlive) {
                return
            }
            process = null
            outputDrainThread = null
            cleanupLaunchFilesLocked()
        }
    }

    companion object {
        private const val StaleLaunchFileMaxAgeMillis = 24L * 60L * 60L * 1_000L
        private const val OutputDrainJoinMillis = 500L
        private val LaunchFileRegex = Regex("""\.wd-[A-Za-z0-9-]+\.(toml|resolvers)""")

        internal fun cleanupStaleLaunchFiles(
            runtimeDir: File,
            nowMillis: Long = System.currentTimeMillis(),
            maxAgeMillis: Long = StaleLaunchFileMaxAgeMillis,
        ) {
            runtimeDir.listFiles()
                ?.asSequence()
                ?.filter { file ->
                    file.isFile &&
                        LaunchFileRegex.matches(file.name) &&
                        nowMillis - file.lastModified() > maxAgeMillis
                }
                ?.forEach { file ->
                    runCatching { file.delete() }
                }
        }
    }
}
