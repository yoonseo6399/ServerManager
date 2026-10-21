package local.servermanager

import java.nio.file.Files
import java.nio.file.Path

/** Strata refuses an existing ready file. Each invocation owns a fresh directory, even after a crash. */
internal class StrataLaunchFiles(parent:Path):AutoCloseable {
    val directory:Path=Files.createTempDirectory(parent,"run-")
    val ready:Path=directory.resolve("ready.json")
    override fun close() {
        // Never scan/delete another invocation's files, legacy ready.json, or arbitrary directory contents.
        Files.deleteIfExists(ready)
        Files.deleteIfExists(directory)
    }
}
