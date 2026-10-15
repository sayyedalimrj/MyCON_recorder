package ir.mycon.capture

import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/** Read-only source archiver. Originals are never moved or deleted. */
object SessionPackager {
    data class SourceFile(val file: File, val path: String, val size: Long, val modified: Long)
    data class ArchiveResult(val count: Int, val originalBytes: Long)

    fun sourceFiles(dir: File): List<SourceFile> {
        require(dir.isDirectory) { "Session folder not found" }
        val canonical = dir.canonicalPath
        return dir.walkTopDown().filter { it.isFile }.map { file ->
            require(file.canonicalPath.startsWith(canonical + File.separator)) { "Unexpected file path" }
            SourceFile(
                file,
                file.relativeTo(dir).invariantSeparatorsPath,
                file.length(),
                file.lastModified()
            )
        }.sortedBy { it.path }.toList()
    }

    fun zip(dir: File, out: File): File {
        require(!out.canonicalPath.startsWith(dir.canonicalPath + File.separator)) {
            "ZIP destination must be outside the source folder"
        }
        val pending = File(out.parentFile, ".${out.name}.${System.nanoTime()}.partial")
        try {
            val expected = FileOutputStream(pending).use { zipToStream(dir, it) }
            FileInputStream(pending).use { verifyZip(it, sourceFiles(dir), expected) }
            require(pending.renameTo(out)) { "Could not finish ZIP without overwriting the original" }
        } finally {
            pending.delete()
        }
        return out
    }

    fun zipToStream(dir: File, destination: OutputStream): ArchiveResult {
        val files = sourceFiles(dir)
        require(files.isNotEmpty()) { "No session files found" }
        var bytes = 0L
        ZipOutputStream(BufferedOutputStream(destination, 128 * 1024)).use { zip ->
            files.forEach { source ->
                require(source.file.length() == source.size && source.file.lastModified() == source.modified) {
                    "Source changed during export: ${source.path}"
                }
                zip.putNextEntry(ZipEntry(source.path))
                var copied = 0L
                BufferedInputStream(FileInputStream(source.file), 128 * 1024).use { input ->
                    val buffer = ByteArray(128 * 1024)
                    while (true) {
                        val n = input.read(buffer)
                        if (n == -1) break
                        zip.write(buffer, 0, n)
                        copied += n
                    }
                }
                zip.closeEntry()
                require(copied == source.size && source.file.length() == source.size &&
                    source.file.lastModified() == source.modified) {
                    "Source changed during export: ${source.path}"
                }
                bytes += copied
            }
        }
        return ArchiveResult(files.size, bytes)
    }

    /** Reads every byte and checks ZIP CRC plus exact names and sizes. */
    fun verifyZip(stream: InputStream, files: List<SourceFile>, expected: ArchiveResult) {
        val byName = files.associateBy { it.path }
        val seen = HashSet<String>()
        var count = 0
        var total = 0L
        ZipInputStream(BufferedInputStream(stream, 128 * 1024)).use { zip ->
            val buffer = ByteArray(128 * 1024)
            while (true) {
                val entry = zip.nextEntry ?: break
                require(!entry.isDirectory && seen.add(entry.name)) { "Unexpected or duplicate ZIP entry" }
                val source = byName[entry.name] ?: error("Unknown ZIP entry: ${entry.name}")
                var size = 0L
                while (true) {
                    val n = zip.read(buffer)
                    if (n == -1) break
                    size += n
                }
                // ZipInputStream verifies CRC during read/closeEntry.
                zip.closeEntry()
                require(size == source.size) { "Incomplete ZIP entry: ${entry.name}" }
                total += size
                count++
            }
        }
        require(count == expected.count && total == expected.originalBytes &&
            seen.size == files.size) { "ZIP verification failed: missing session files" }
    }
}
