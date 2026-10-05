package ir.mycon.capture

import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

object SessionPackager {
    fun zip(dir: File, out: File): File {
        ZipOutputStream(FileOutputStream(out)).use { zos ->
            dir.walkTopDown()
                .filter { it.isFile }
                .forEach { file ->
                    val rel = file.relativeTo(dir).path.replace('\\', '/')
                    zos.putNextEntry(ZipEntry(rel))
                    BufferedInputStream(FileInputStream(file)).use { input ->
                        input.copyTo(zos)
                    }
                    zos.closeEntry()
                }
        }
        return out
    }
}
