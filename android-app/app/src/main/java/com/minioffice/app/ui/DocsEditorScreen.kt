package com.minioffice.app.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import uniffi.mini_office_core.DocHeading
import uniffi.mini_office_core.DocSearchMatch
import uniffi.mini_office_core.DocStats
import uniffi.mini_office_core.docsComputeStats
import uniffi.mini_office_core.docsExtractOutline
import uniffi.mini_office_core.docsSearch

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun DocsEditorScreen(
    activeDoc: ActiveDocument,
    onContentChange: (String) -> Unit,
    onAutoSave: () -> Unit,
    onExportDocx: () -> Unit
) {
    var textValue by remember(activeDoc.path) {
        mutableStateOf(TextFieldValue(activeDoc.content))
    }
    var outline by remember(activeDoc.path) { mutableStateOf<List<DocHeading>>(emptyList()) }
    var stats by remember(activeDoc.path) { mutableStateOf(DocStats(0u, 0u, 0u, 0u)) }
    var showOutlineManual by remember { mutableStateOf(true) }
    var showSearch by remember { mutableStateOf(false) }
    var searchQuery by remember { mutableStateOf("") }
    var searchMatches by remember { mutableStateOf<List<DocSearchMatch>>(emptyList()) }

    val isKeyboardOpen = WindowInsets.isImeVisible
    val showOutlinePane = showOutlineManual && !isKeyboardOpen

    // Đồng bộ nếu tệp thay đổi từ ngoài
    LaunchedEffect(activeDoc.content) {
        if (textValue.text != activeDoc.content) {
            textValue = textValue.copy(text = activeDoc.content)
        }
    }

    // Phân tích mục lục & thống kê bằng Rust ở background thread (debounce nhẹ 250ms)
    LaunchedEffect(textValue.text) {
        delay(250)
        val snapshot = textValue.text
        val (newOutline, newStats) = withContext(Dispatchers.Default) {
            docsExtractOutline(snapshot) to docsComputeStats(snapshot)
        }
        outline = newOutline
        stats = newStats
    }

    // Tự động lưu nền sau 2 giây ngừng gõ để bảo vệ eMMC 5.1 và không giật khung hình
    LaunchedEffect(activeDoc.content, activeDoc.isDirty) {
        if (activeDoc.isDirty) {
            delay(2000)
            onAutoSave()
        }
    }

    // Tìm kiếm trong tài liệu qua Rust Core
    LaunchedEffect(searchQuery, textValue.text, showSearch) {
        if (!showSearch || searchQuery.isBlank()) {
            searchMatches = emptyList()
        } else {
            delay(150)
            val snap = textValue.text
            val q = searchQuery
            searchMatches = withContext(Dispatchers.Default) { docsSearch(snap, q) }
        }
    }

    fun applySnippet(prefix: String, suffix: String = "") {
        val sel = textValue.selection
        val text = textValue.text
        val start = minOf(sel.start, sel.end)
        val end = maxOf(sel.start, sel.end)
        val selected = text.substring(start, end)
        val updated = text.substring(0, start) + prefix + selected + suffix + text.substring(end)
        val newCursor = start + prefix.length + selected.length + suffix.length
        textValue = TextFieldValue(updated, TextRange(newCursor))
        onContentChange(updated)
    }

    fun jumpToOffset(offset: Int) {
        val safe = offset.coerceIn(0, textValue.text.length)
        textValue = textValue.copy(selection = TextRange(safe))
    }

    Column(modifier = Modifier.fillMaxSize()) {
        // Thanh định dạng nhanh (vùng chạm rộng cho tablet 11 inch)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            OutlinedButton(onClick = { showOutlineManual = !showOutlineManual }) {
                Text(if (showOutlineManual) "Ẩn mục lục" else "Mục lục")
            }
            FilledTonalButton(onClick = { applySnippet("
# ") }) { Text("H1") }
            FilledTonalButton(onClick = { applySnippet("
## ") }) { Text("H2") }
            FilledTonalButton(onClick = { applySnippet("**", "**") }) { Text("Đậm") }
            FilledTonalButton(onClick = { applySnippet("*", "*") }) { Text("Nghiêng") }
            FilledTonalButton(onClick = { applySnippet("
- ") }) { Text("Danh sách") }
            FilledTonalButton(onClick = {
                applySnippet("
| Cột 1 | Cột 2 |
| --- | --- |
| Giá trị | Giá trị |
")
            }) { Text("Bảng") }
            Spacer(modifier = Modifier.weight(1f))
            OutlinedButton(onClick = { showSearch = !showSearch }) { Text("Tìm") }
            Button(onClick = onExportDocx) { Text("Xuất .docx") }
        }

        if (showSearch) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it },
                    modifier = Modifier.width(280.dp),
                    singleLine = true,
                    placeholder = { Text("Tìm trong văn bản...") }
                )
                Text(
                    text = "${searchMatches.size} kết quả",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                searchMatches.take(3).forEach { m ->
                    OutlinedButton(onClick = { jumpToOffset(m.charOffset.toInt()) }) {
                        Text("Dòng ${m.lineNumber}")
                    }
                }
            }
        }

        HorizontalDivider()

        Row(modifier = Modifier.weight(1f).fillMaxWidth()) {
            // Cột mục lục 220dp tự ẩn khi bật bàn phím ảo
            if (showOutlinePane) {
                Column(
                    modifier = Modifier
                        .width(220.dp)
                        .fillMaxHeight()
                        .padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text(
                        text = "Mục lục",
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    if (outline.isEmpty()) {
                        Text(
                            text = "Dùng # hoặc ## để tạo tiêu đề mục lục.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    } else {
                        LazyColumn(
                            modifier = Modifier.weight(1f),
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            items(outline) { heading ->
                                val indent = ((heading.level.toInt() - 1).coerceAtLeast(0) * 12).dp
                                Text(
                                    text = heading.title,
                                    style = MaterialTheme.typography.bodyMedium,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable { jumpToOffset(heading.charOffset.toInt()) }
                                        .padding(start = indent, top = 6.dp, bottom = 6.dp)
                                )
                            }
                        }
                    }
                    HorizontalDivider()
                    Text(
                        text = "${stats.words} từ · ${stats.chars} ký tự",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                VerticalDivider()
            }

            // Trang viết căn giữa tối đa 720dp tối ưu cho màn hình 11 inch
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .padding(horizontal = 24.dp, vertical = 12.dp),
                contentAlignment = Alignment.TopCenter
            ) {
                Surface(
                    tonalElevation = 1.dp,
                    shape = MaterialTheme.shapes.medium,
                    modifier = Modifier
                        .widthIn(max = 720.dp)
                        .fillMaxSize()
                ) {
                    OutlinedTextField(
                        value = textValue,
                        onValueChange = { updated ->
                            textValue = updated
                            onContentChange(updated.text)
                        },
                        modifier = Modifier.fillMaxSize(),
                        placeholder = { Text("Bắt đầu soạn thảo văn bản tiếng Việt...") }
                    )
                }
            }
        }
    }
}
