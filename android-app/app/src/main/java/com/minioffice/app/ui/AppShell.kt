package com.minioffice.app.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.GridOn
import androidx.compose.material.icons.outlined.Slideshow
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.unit.dp
import uniffi.mini_office_core.FileEntry
import uniffi.mini_office_core.FileKind
import uniffi.mini_office_core.SheetData
import uniffi.mini_office_core.SlideItem

@Composable
fun MiniOfficeAppShell(
    activeModule: OfficeModule,
    onSelectModule: (OfficeModule) -> Unit,
    workspaceFiles: List<FileEntry>,
    activeDoc: ActiveDocument?,
    activeSheets: List<SheetData>,
    activeSlides: List<SlideItem>,
    statusMessage: String,
    onCreateSampleFile: (FileKind) -> Unit,
    onOpenFile: (FileEntry) -> Unit,
    onDeleteFile: (FileEntry) -> Unit,
    onContentChange: (String) -> Unit,
    onSheetsChanged: (List<SheetData>) -> Unit,
    onSlidesChanged: (List<SlideItem>) -> Unit,
    onSaveActiveDoc: () -> Unit,
    onExportDocx: () -> Unit,
    onExportXlsx: () -> Unit,
    onExportPptx: () -> Unit,
    onImportSaf: () -> Unit,
    onExportSaf: () -> Unit
) {
    Surface(
        modifier = Modifier
            .fillMaxSize()
            .onPreviewKeyEvent { event ->
                // Hỗ trợ phím tắt toàn cục cho bàn phím Bluetooth trên Redmi Pad SE
                if (event.type == KeyEventType.KeyDown && event.isCtrlPressed) {
                    when (event.key) {
                        Key.S -> {
                            onSaveActiveDoc()
                            true
                        }
                        Key.One -> {
                            onSelectModule(OfficeModule.Docs)
                            true
                        }
                        Key.Two -> {
                            onSelectModule(OfficeModule.Sheets)
                            true
                        }
                        Key.Three -> {
                            onSelectModule(OfficeModule.Slides)
                            true
                        }
                        Key.Four -> {
                            onSelectModule(OfficeModule.Files)
                            true
                        }
                        else -> false
                    }
                } else {
                    false
                }
            }
    ) {
        Row(modifier = Modifier.fillMaxSize()) {
            NavigationRail(modifier = Modifier.width(72.dp).fillMaxHeight()) {
                Spacer(modifier = Modifier.height(8.dp))
                RailEntry(OfficeModule.Docs, Icons.Outlined.Description, activeModule, onSelectModule)
                RailEntry(OfficeModule.Sheets, Icons.Outlined.GridOn, activeModule, onSelectModule)
                RailEntry(OfficeModule.Slides, Icons.Outlined.Slideshow, activeModule, onSelectModule)
                Spacer(modifier = Modifier.weight(1f))
                RailEntry(OfficeModule.Files, Icons.Outlined.Folder, activeModule, onSelectModule)
                Spacer(modifier = Modifier.height(8.dp))
            }

            VerticalDivider()

            Column(modifier = Modifier.weight(1f).fillMaxHeight()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(52.dp)
                        .padding(horizontal = 16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Text(
                            text = activeDoc?.name ?: "Mini Office · ${activeModule.label}",
                            style = MaterialTheme.typography.titleMedium
                        )
                        AssistChip(
                            onClick = {},
                            label = {
                                Text(if (activeDoc?.isDirty == true) "Đang sửa..." else "Đã lưu · Offline")
                            }
                        )
                    }

                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = onImportSaf) { Text("Nhập SAF") }
                        if (activeDoc != null) {
                            OutlinedButton(onClick = onExportSaf) { Text("Xuất tệp") }
                            Button(onClick = onSaveActiveDoc) { Text("Lưu (Ctrl+S)") }
                        }
                    }
                }

                HorizontalDivider()

                Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                    when (activeModule) {
                        OfficeModule.Files -> Box(modifier = Modifier.padding(16.dp)) {
                            FilesBrowserPane(
                                files = workspaceFiles,
                                onCreateSampleFile = onCreateSampleFile,
                                onOpenFile = onOpenFile,
                                onDeleteFile = onDeleteFile
                            )
                        }
                        OfficeModule.Docs -> {
                            if (activeDoc != null) {
                                DocsEditorScreen(
                                    activeDoc = activeDoc,
                                    onContentChange = onContentChange,
                                    onAutoSave = onSaveActiveDoc,
                                    onExportDocx = onExportDocx
                                )
                            } else {
                                EmptyModulePrompt(OfficeModule.Docs, FileKind.DOCS, onCreateSampleFile)
                            }
                        }
                        OfficeModule.Sheets -> {
                            if (activeSheets.isNotEmpty()) {
                                SheetsEditorScreen(
                                    sheets = activeSheets,
                                    onSheetsChanged = onSheetsChanged,
                                    onExportXlsx = onExportXlsx
                                )
                            } else {
                                EmptyModulePrompt(OfficeModule.Sheets, FileKind.SHEETS, onCreateSampleFile)
                            }
                        }
                        OfficeModule.Slides -> {
                            if (activeSlides.isNotEmpty()) {
                                SlidesEditorScreen(
                                    slides = activeSlides,
                                    onSlidesChanged = onSlidesChanged,
                                    onExportPptx = onExportPptx
                                )
                            } else {
                                EmptyModulePrompt(OfficeModule.Slides, FileKind.SLIDES, onCreateSampleFile)
                            }
                        }
                    }
                }

                HorizontalDivider()
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(34.dp)
                        .padding(horizontal = 16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = statusMessage,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = "Phím tắt: Ctrl+S lưu · Ctrl+1..4 chuyển mục",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

@Composable
private fun RailEntry(
    module: OfficeModule,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    activeModule: OfficeModule,
    onSelect: (OfficeModule) -> Unit
) {
    NavigationRailItem(
        selected = activeModule == module,
        onClick = { onSelect(module) },
        icon = { Icon(icon, contentDescription = module.label) },
        label = { Text(module.label) }
    )
}

@Composable
private fun EmptyModulePrompt(
    module: OfficeModule,
    kind: FileKind,
    onCreateSampleFile: (FileKind) -> Unit
) {
    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text("Chưa có tệp đang mở cho ${module.label}", style = MaterialTheme.typography.titleMedium)
        Button(onClick = { onCreateSampleFile(kind) }) {
            Text("Tạo tệp ${module.label} mới")
        }
    }
}

@Composable
private fun FilesBrowserPane(
    files: List<FileEntry>,
    onCreateSampleFile: (FileKind) -> Unit,
    onOpenFile: (FileEntry) -> Unit,
    onDeleteFile: (FileEntry) -> Unit
) {
    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { onCreateSampleFile(FileKind.DOCS) }) { Text("+ Tài liệu (.md)") }
            OutlinedButton(onClick = { onCreateSampleFile(FileKind.SHEETS) }) { Text("+ Bảng tính (.csv)") }
            OutlinedButton(onClick = { onCreateSampleFile(FileKind.SLIDES) }) { Text("+ Slide (.slides)") }
        }

        if (files.isEmpty()) {
            Text(
                text = "Chưa có tệp cục bộ nào. Bấm nút bên trên để tạo tệp mẫu hoặc Nhập SAF.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(files, key = { it.path }) { entry ->
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onOpenFile(entry) }
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 12.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column {
                                Text(entry.name, style = MaterialTheme.typography.titleSmall)
                                Text(
                                    text = "${entry.kind} · ${entry.sizeBytes} bytes",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            OutlinedButton(onClick = { onDeleteFile(entry) }) {
                                Text("Xóa")
                            }
                        }
                    }
                }
            }
        }
    }
}
