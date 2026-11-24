package com.minioffice.app.data

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import java.io.File
import uniffi.mini_office_core.atomicWriteBytes

class SafFileBridge(private val context: Context) {

    val workspaceDir: File
        get() = File(context.filesDir, "workspace").apply {
            if (!exists()) mkdirs()
        }

    fun resolveDisplayName(uri: Uri, fallback: String = "imported_file.md"): String {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (idx >= 0) {
                        val name = cursor.getString(idx)
                        if (!name.isNullOrBlank()) return name
                    }
                }
            }
        return fallback
    }

    fun importFromSaf(uri: Uri): File {
        val fileName = resolveDisplayName(uri)
        val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
            ?: error("Không thể đọc nội dung tệp từ SAF")
        val target = File(workspaceDir, fileName)
        atomicWriteBytes(target.absolutePath, bytes)
        return target
    }

    fun exportToSaf(sourceFile: File, destinationUri: Uri) {
        val bytes = sourceFile.readBytes()
        context.contentResolver.openOutputStream(destinationUri, "wt")?.use { out ->
            out.write(bytes)
            out.flush()
        } ?: error("Không thể ghi tệp ra vị trí SAF đã chọn")
    }
}
