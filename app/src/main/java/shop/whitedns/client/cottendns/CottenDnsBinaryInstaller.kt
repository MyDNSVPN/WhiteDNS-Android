package shop.whitedns.client.cottendns

import android.content.Context
import java.io.File

class CottenDnsBinaryInstaller(
    private val context: Context,
) {

    fun installExecutable(nativeLibraryName: String = CottenDnsLibraryName): File {
        val executable = File(context.applicationInfo.nativeLibraryDir, nativeLibraryName)
        if (!executable.exists()) {
            throw IllegalStateException(
                "Engine native executable not found: ${executable.absolutePath}",
            )
        }
        if (!executable.canExecute()) {
            throw IllegalStateException(
                "Engine native executable is not executable: ${executable.absolutePath}",
            )
        }
        return executable
    }

    companion object {
        const val CottenDnsLibraryName = "libcottendns_client.so"
        const val PhantomDnsLibraryName = "libphantomdns_client.so"
    }
}
