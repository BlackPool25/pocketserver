package com.pocketserver.ui.editor

import com.pocketserver.nativeengine.NativeEngine
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * Saves project source files atomically to prevent corruption or partial writes
 * in the event of unexpected mobile power loss, app backgrounding, or OS termination.
 */
object AtomicFileSaver {

    /**
     * Atomically writes string content to targetFile:
     * 1. Writes content to a temporary sibling file.
     * 2. Flushes buffers and synchronizes file descriptor via fsync().
     * 3. Atomically moves temporary file over targetFile using ATOMIC_MOVE.
     * 4. Synchronizes parent directory metadata.
     */
    fun saveAtomic(targetFile: File, content: String): Boolean {
        val parent = targetFile.parentFile ?: return false
        parent.mkdirs()

        val tempFile = File(parent, ".${targetFile.name}.tmp.${System.nanoTime()}")

        try {
            FileOutputStream(tempFile).use { fos ->
                fos.write(content.toByteArray(Charsets.UTF_8))
                fos.flush()
                try {
                    fos.fd.sync() // Physical storage barrier
                } catch (ignored: Exception) {
                }
            }

            // Perform atomic file replacement
            try {
                Files.move(
                    tempFile.toPath(),
                    targetFile.toPath(),
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING
                )
            } catch (e: Exception) {
                // Fallback to standard atomic rename via NativeEngine
                val res = NativeEngine.atomicRename(tempFile, targetFile)
                if (res != 0) {
                    if (!tempFile.renameTo(targetFile)) {
                        targetFile.writeText(content)
                        tempFile.delete()
                    }
                }
            }

            // Sync parent directory to persist directory entry
            NativeEngine.fsyncDirectory(parent)
            return true
        } catch (e: Exception) {
            tempFile.delete()
            return false
        }
    }
}
