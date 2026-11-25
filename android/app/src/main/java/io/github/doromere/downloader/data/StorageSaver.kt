package io.github.doromere.downloader.data

import android.content.ContentValues
import android.content.Context
import android.media.MediaScannerConnection
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.webkit.MimeTypeMap
import androidx.core.content.FileProvider
import java.io.File

data class SavedFile(
    val displayPath: String,
    val contentUri: String?,
    val mimeType: String
)

object StorageSaver {

    fun saveFilesToDownloads(
        context: Context,
        stagingDir: File,
        serviceFolder: String,
        subFolder: String? = null
    ): List<SavedFile> {
        val files = stagingDir.walkTopDown()
            .filter { it.isFile && !it.name.endsWith(".part") && !it.name.endsWith(".ytdl") }
            .toList()
        if (files.isEmpty()) return emptyList()

        val relFolder = buildString {
            append(Environment.DIRECTORY_DOWNLOADS)
            append("/Termux Downloads/")
            append(sanitizeSegment(serviceFolder))
            if (!subFolder.isNullOrBlank()) {
                append("/")
                append(sanitizeSegment(subFolder))
            }
        }

        val saved = mutableListOf<SavedFile>()
        for (file in files) {
            val mime = guessMime(file)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val values = ContentValues().apply {
                    put(MediaStore.Downloads.DISPLAY_NAME, file.name)
                    put(MediaStore.Downloads.MIME_TYPE, mime)
                    put(MediaStore.Downloads.RELATIVE_PATH, relFolder)
                    put(MediaStore.Downloads.IS_PENDING, 1)
                }
                val resolver = context.contentResolver
                val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                if (uri != null) {
                    resolver.openOutputStream(uri)?.use { out ->
                        file.inputStream().use { inp -> inp.copyTo(out) }
                    }
                    values.clear()
                    values.put(MediaStore.Downloads.IS_PENDING, 0)
                    resolver.update(uri, values, null, null)
                    saved.add(SavedFile("$relFolder/${file.name}", uri.toString(), mime))
                }
            } else {
                @Suppress("DEPRECATION")
                val targetDir = File(Environment.getExternalStorageDirectory(), relFolder).apply { mkdirs() }
                val targetFile = uniqueTarget(targetDir, file.name)
                file.copyTo(targetFile, overwrite = false)
                MediaScannerConnection.scanFile(context, arrayOf(targetFile.absolutePath), arrayOf(mime), null)
                val uri = runCatching {
                    FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", targetFile).toString()
                }.getOrNull()
                saved.add(SavedFile(targetFile.absolutePath, uri, mime))
            }
        }
        return saved
    }

    private fun uniqueTarget(dir: File, name: String): File {
        val candidate = File(dir, name)
        if (!candidate.exists()) return candidate
        val base = name.substringBeforeLast('.', name)
        val ext = name.substringAfterLast('.', "")
        val suffix = System.currentTimeMillis()
        return File(dir, if (ext.isNotEmpty()) "${base}_$suffix.$ext" else "${base}_$suffix")
    }

    private fun sanitizeSegment(s: String): String =
        s.replace(Regex("""[\\/:*?"<>|]+"""), "_").trim(' ', '.').ifEmpty { "Other" }

    private fun guessMime(file: File): String {
        val ext = file.extension.lowercase()
        return MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: "application/octet-stream"
    }
}
