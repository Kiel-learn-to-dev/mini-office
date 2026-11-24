package com.minioffice.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import com.minioffice.app.data.SafFileBridge
import com.minioffice.app.ui.ActiveDocument
import com.minioffice.app.ui.MiniOfficeAppShell
import com.minioffice.app.ui.OfficeModule
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import uniffi.mini_office_core.FileEntry
import uniffi.mini_office_core.FileKind
import uniffi.mini_office_core.SheetData
import uniffi.mini_office_core.SlideItem
import uniffi.mini_office_core.atomicWriteText
import uniffi.mini_office_core.deleteWorkspaceFile
import uniffi.mini_office_core.docsExportDocx
import uniffi.mini_office_core.listWorkspaceFiles
import uniffi.mini_office_core.pingCore
import uniffi.mini_office_core.readTextFile
import uniffi.mini_office_core.sheetsExportXlsx
import uniffi.mini_office_core.sheetsParseCsv
import uniffi.mini_office_core.sheetsReadXlsx
import uniffi.mini_office_core.sheetsToCsv
import uniffi.mini_office_core.slidesExportPptx
import uniffi.mini_office_core.slidesParseDeck
import uniffi.mini_office_core.slidesReadPptx
import uniffi.mini_office_core.slidesSerializeDeck

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val safBridge = SafFileBridge(this)

        setContent {
            MaterialTheme {
                val scope = rememberCoroutineScope()
                var activeModule by remember { mutableStateOf(OfficeModule.Slides) }
                var workspaceFiles by remember { mutableStateOf<List<FileEntry>>(emptyList()) }
                var activeDoc by remember { mutableStateOf<ActiveDocument?>(null) }
                var activeSheets by remember { mutableStateOf<List<SheetData>>(emptyList()) }
                var activeSlides by remember { mutableStateOf<List<SlideItem>>(emptyList()) }
                var statusMessage by remember { mutableStateOf("Đang khởi động Rust Core...") }

                fun refreshFiles() {
                    scope.launch {
                        runCatching {
                            withContext(Dispatchers.IO) {
                                listWorkspaceFiles(safBridge.workspaceDir.absolutePath)
                            }
                        }.onSuccess { files ->
                            workspaceFiles = files
                        }.onFailure { err ->
                            statusMessage = "Lỗi tải danh sách tệp: ${err.message}"
                        }
                    }
                }

                LaunchedEffect(Unit) {
                    val status = withContext(Dispatchers.IO) { pingCore() }
                    statusMessage = "${status.message} · (${status.targetArch})"
                    refreshFiles()
                }

                val importLauncher = rememberLauncherForActivityResult(
                    contract = ActivityResultContracts.OpenDocument()
                ) { uri ->
                    if (uri != null) {
                        scope.launch {
                            runCatching {
                                withContext(Dispatchers.IO) { safBridge.importFromSaf(uri) }
                            }.onSuccess { imported ->
                                statusMessage = "Đã nhập từ SAF: ${imported.name}"
                                refreshFiles()
                            }.onFailure { err ->
                                statusMessage = "Lỗi nhập SAF: ${err.message}"
                            }
                        }
                    }
                }

                val exportRawLauncher = rememberLauncherForActivityResult(
                    contract = ActivityResultContracts.CreateDocument("*/*")
                ) { uri ->
                    val current = activeDoc ?: return@rememberLauncherForActivityResult
                    if (uri != null) {
                        scope.launch {
                            runCatching {
                                withContext(Dispatchers.IO) {
                                    atomicWriteText(current.path, current.content)
                                    safBridge.exportToSaf(File(current.path), uri)
                                }
                            }.onSuccess {
                                activeDoc = current.copy(isDirty = false)
                                statusMessage = "Đã xuất tệp qua SAF: ${current.name}"
                            }.onFailure { err ->
                                statusMessage = "Lỗi xuất SAF: ${err.message}"
                            }
                        }
                    }
                }

                val exportDocxLauncher = rememberLauncherForActivityResult(
                    contract = ActivityResultContracts.CreateDocument(
                        "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
                    )
                ) { uri ->
                    val current = activeDoc ?: return@rememberLauncherForActivityResult
                    if (uri != null) {
                        scope.launch {
                            runCatching {
                                withContext(Dispatchers.IO) {
                                    val temp = File(cacheDir, "export_temp.docx")
                                    val bytes = docsExportDocx(current.content, temp.absolutePath)
                                    safBridge.exportToSaf(temp, uri)
                                    temp.delete()
                                    bytes
                                }
                            }.onSuccess { bytes ->
                                statusMessage = "Đã xuất .docx thành công ($bytes bytes)"
                            }.onFailure { err ->
                                statusMessage = "Lỗi xuất .docx: ${err.message}"
                            }
                        }
                    }
                }

                val exportXlsxLauncher = rememberLauncherForActivityResult(
                    contract = ActivityResultContracts.CreateDocument(
                        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
                    )
                ) { uri ->
                    if (uri != null && activeSheets.isNotEmpty()) {
                        scope.launch {
                            runCatching {
                                withContext(Dispatchers.IO) {
                                    val temp = File(cacheDir, "export_temp.xlsx")
                                    val bytes = sheetsExportXlsx(activeSheets, temp.absolutePath)
                                    safBridge.exportToSaf(temp, uri)
                                    temp.delete()
                                    bytes
                                }
                            }.onSuccess { bytes ->
                                statusMessage = "Đã xuất .xlsx thành công ($bytes bytes)"
                            }.onFailure { err ->
                                statusMessage = "Lỗi xuất .xlsx: ${err.message}"
                            }
                        }
                    }
                }

                val exportPptxLauncher = rememberLauncherForActivityResult(
                    contract = ActivityResultContracts.CreateDocument(
                        "application/vnd.openxmlformats-officedocument.presentationml.presentation"
                    )
                ) { uri ->
                    if (uri != null && activeSlides.isNotEmpty()) {
                        scope.launch {
                            runCatching {
                                withContext(Dispatchers.IO) {
                                    val temp = File(cacheDir, "export_temp.pptx")
                                    val bytes = slidesExportPptx(activeSlides, temp.absolutePath)
                                    safBridge.exportToSaf(temp, uri)
                                    temp.delete()
                                    bytes
                                }
                            }.onSuccess { bytes ->
                                statusMessage = "Đã xuất .pptx thành công ($bytes bytes)"
                            }.onFailure { err ->
                                statusMessage = "Lỗi xuất .pptx: ${err.message}"
                            }
                        }
                    }
                }

                MiniOfficeAppShell(
                    activeModule = activeModule,
                    onSelectModule = { activeModule = it },
                    workspaceFiles = workspaceFiles,
                    activeDoc = activeDoc,
                    activeSheets = activeSheets,
                    activeSlides = activeSlides,
                    statusMessage = statusMessage,
                    onCreateSampleFile = { kind ->
                        scope.launch {
                            val (fileName, sampleContent, targetModule) = when (kind) {
                                FileKind.DOCS -> Triple(
                                    "tai_lieu_${System.currentTimeMillis() % 10000}.md",
                                    "# Kế hoạch công việc\n\n## 1. Mục tiêu\nSoạn thảo văn bản **offline 100%** trên Redmi Pad SE.\n",
                                    OfficeModule.Docs
                                )
                                FileKind.SHEETS -> Triple(
                                    "bang_tinh_${System.currentTimeMillis() % 10000}.csv",
                                    "HangMuc,SL,DonGia,ThanhTien\nMayChu,2,8500000,=B2*C2\nBanQuyen,1,6000000,=B3*C3\nTong,=SUM(B2:B3),,=SUM(D2:D3)",
                                    OfficeModule.Sheets
                                )
                                else -> Triple(
                                    "trinh_chieu_${System.currentTimeMillis() % 10000}.slides",
                                    "# Đề xuất giải pháp 2026\n@layout: cover\n@notes: Chào mừng và giới thiệu mục tiêu\nNhanh · Nhẹ · 100% Offline\n===\n# Kiến trúc hệ thống\n@layout: two_col\nRust Core:\n- Xử lý .docx/.xlsx/.pptx\n- Không GC, ít tốn RAM\n@right\nJetpack Compose:\n- Mượt 90Hz trên 11 inch\n- Bộ gõ tiếng Việt chuẩn",
                                    OfficeModule.Slides
                                )
                            }
                            val path = File(safBridge.workspaceDir, fileName).absolutePath
                            runCatching {
                                withContext(Dispatchers.IO) {
                                    val bytes = atomicWriteText(path, sampleContent)
                                    val parsedSheets = if (kind == FileKind.SHEETS) {
                                        listOf(sheetsParseCsv("Sheet 1", sampleContent))
                                    } else emptyList()
                                    val parsedSlides = if (kind == FileKind.SLIDES) {
                                        slidesParseDeck(sampleContent)
                                    } else emptyList()
                                    Triple(bytes, parsedSheets, parsedSlides)
                                }
                            }.onSuccess { (bytes, parsedSheets, parsedSlides) ->
                                activeDoc = ActiveDocument(fileName, path, kind, sampleContent, false)
                                if (parsedSheets.isNotEmpty()) activeSheets = parsedSheets
                                if (parsedSlides.isNotEmpty()) activeSlides = parsedSlides
                                activeModule = targetModule
                                statusMessage = "Đã tạo tệp ($bytes bytes): $fileName"
                                refreshFiles()
                            }.onFailure { err ->
                                statusMessage = "Lỗi tạo tệp: ${err.message}"
                            }
                        }
                    },
                    onOpenFile = { entry ->
                        scope.launch {
                            val lower = entry.name.lowercase()
                            when {
                                lower.endsWith(".xlsx") || lower.endsWith(".xls") || lower.endsWith(".ods") -> {
                                    runCatching {
                                        withContext(Dispatchers.IO) { sheetsReadXlsx(entry.path) }
                                    }.onSuccess { loaded ->
                                        activeSheets = loaded
                                        activeDoc = ActiveDocument(entry.name, entry.path, FileKind.SHEETS, "", false)
                                        activeModule = OfficeModule.Sheets
                                        statusMessage = "Đã mở bảng tính (${loaded.size} sheet): ${entry.name}"
                                    }.onFailure { err ->
                                        statusMessage = "Lỗi đọc bảng tính: ${err.message}"
                                    }
                                }
                                lower.endsWith(".pptx") -> {
                                    runCatching {
                                        withContext(Dispatchers.IO) { slidesReadPptx(entry.path) }
                                    }.onSuccess { loaded ->
                                        activeSlides = loaded
                                        activeDoc = ActiveDocument(entry.name, entry.path, FileKind.SLIDES, "", false)
                                        activeModule = OfficeModule.Slides
                                        statusMessage = "Đã mở trình chiếu (${loaded.size} slide): ${entry.name}"
                                    }.onFailure { err ->
                                        statusMessage = "Lỗi đọc .pptx: ${err.message}"
                                    }
                                }
                                else -> {
                                    runCatching {
                                        withContext(Dispatchers.IO) {
                                            val text = readTextFile(entry.path)
                                            val parsedSheets = if (lower.endsWith(".csv")) {
                                                listOf(sheetsParseCsv("Sheet 1", text))
                                            } else emptyList()
                                            val parsedSlides = if (lower.endsWith(".slides")) {
                                                slidesParseDeck(text)
                                            } else emptyList()
                                            Triple(text, parsedSheets, parsedSlides)
                                        }
                                    }.onSuccess { (text, parsedSheets, parsedSlides) ->
                                        activeDoc = ActiveDocument(entry.name, entry.path, entry.kind, text, false)
                                        if (parsedSheets.isNotEmpty()) activeSheets = parsedSheets
                                        if (parsedSlides.isNotEmpty()) activeSlides = parsedSlides
                                        activeModule = when (entry.kind) {
                                            FileKind.DOCS -> OfficeModule.Docs
                                            FileKind.SHEETS -> OfficeModule.Sheets
                                            FileKind.SLIDES -> OfficeModule.Slides
                                            FileKind.OTHER -> OfficeModule.Docs
                                        }
                                        statusMessage = "Đã mở: ${entry.name}"
                                    }.onFailure { err ->
                                        statusMessage = "Không đọc được tệp: ${err.message}"
                                    }
                                }
                            }
                        }
                    },
                    onDeleteFile = { entry ->
                        scope.launch {
                            runCatching {
                                withContext(Dispatchers.IO) { deleteWorkspaceFile(entry.path) }
                            }.onSuccess {
                                if (activeDoc?.path == entry.path) {
                                    activeDoc = null
                                    activeSheets = emptyList()
                                    activeSlides = emptyList()
                                }
                                statusMessage = "Đã xóa: ${entry.name}"
                                refreshFiles()
                            }.onFailure { err ->
                                statusMessage = "Lỗi xóa tệp: ${err.message}"
                            }
                        }
                    },
                    onContentChange = { updated ->
                        activeDoc = activeDoc?.copy(content = updated, isDirty = true)
                    },
                    onSheetsChanged = { updatedSheets ->
                        activeSheets = updatedSheets
                        val current = activeDoc
                        if (current != null && current.name.lowercase().endsWith(".csv") && updatedSheets.isNotEmpty()) {
                            activeDoc = current.copy(content = sheetsToCsv(updatedSheets.first()), isDirty = true)
                        } else if (current != null) {
                            activeDoc = current.copy(isDirty = true)
                        }
                    },
                    onSlidesChanged = { updatedSlides ->
                        activeSlides = updatedSlides
                        val current = activeDoc
                        if (current != null && current.name.lowercase().endsWith(".slides")) {
                            activeDoc = current.copy(content = slidesSerializeDeck(updatedSlides), isDirty = true)
                        } else if (current != null) {
                            activeDoc = current.copy(isDirty = true)
                        }
                    },
                    onSaveActiveDoc = {
                        val current = activeDoc ?: return@MiniOfficeAppShell
                        scope.launch {
                            runCatching {
                                withContext(Dispatchers.IO) {
                                    val lower = current.name.lowercase()
                                    when {
                                        lower.endsWith(".xlsx") && activeSheets.isNotEmpty() ->
                                            sheetsExportXlsx(activeSheets, current.path)
                                        lower.endsWith(".pptx") && activeSlides.isNotEmpty() ->
                                            slidesExportPptx(activeSlides, current.path)
                                        else ->
                                            atomicWriteText(current.path, current.content)
                                    }
                                }
                            }.onSuccess { bytes ->
                                activeDoc = current.copy(isDirty = false)
                                statusMessage = "Đã lưu atomic ($bytes bytes): ${current.name}"
                                refreshFiles()
                            }.onFailure { err ->
                                statusMessage = "Lỗi lưu tệp: ${err.message}"
                            }
                        }
                    },
                    onExportDocx = {
                        val base = activeDoc?.name?.substringBeforeLast('.') ?: "tai_lieu"
                        exportDocxLauncher.launch("$base.docx")
                    },
                    onExportXlsx = {
                        val base = activeDoc?.name?.substringBeforeLast('.') ?: "bang_tinh"
                        exportXlsxLauncher.launch("$base.xlsx")
                    },
                    onExportPptx = {
                        val base = activeDoc?.name?.substringBeforeLast('.') ?: "trinh_chieu"
                        exportPptxLauncher.launch("$base.pptx")
                    },
                    onImportSaf = { importLauncher.launch(arrayOf("*/*")) },
                    onExportSaf = {
                        val name = activeDoc?.name ?: "export.md"
                        exportRawLauncher.launch(name)
                    }
                )
            }
        }
    }
}
