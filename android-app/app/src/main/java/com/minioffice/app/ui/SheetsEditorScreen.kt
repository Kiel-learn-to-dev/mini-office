package com.minioffice.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import uniffi.mini_office_core.SheetCell
import uniffi.mini_office_core.SheetData
import uniffi.mini_office_core.SheetSummary
import uniffi.mini_office_core.sheetsEvaluate
import uniffi.mini_office_core.sheetsSummarizeNumbers

private fun colName(colIndex: Int): String {
    var c = colIndex
    val sb = StringBuilder()
    while (true) {
        sb.insert(0, ('A'.code + (c % 26)).toChar())
        if (c < 26) break
        c = c / 26 - 1
    }
    return sb.toString()
}

@Composable
fun SheetsEditorScreen(
    sheets: List<SheetData>,
    onSheetsChanged: (List<SheetData>) -> Unit,
    onExportXlsx: () -> Unit
) {
    if (sheets.isEmpty()) return

    var activeSheetIndex by remember(sheets.size) { mutableIntStateOf(0) }
    val safeIndex = activeSheetIndex.coerceIn(0, sheets.lastIndex)
    val currentSheet = sheets[safeIndex]

    var selectedRow by remember(safeIndex) { mutableIntStateOf(0) }
    var selectedCol by remember(safeIndex) { mutableIntStateOf(0) }

    val cellMap = remember(currentSheet) {
        currentSheet.cells.associateBy { it.row.toInt() to it.col.toInt() }
    }

    var formulaText by remember(safeIndex, selectedRow, selectedCol, currentSheet) {
        mutableStateOf(cellMap[selectedRow to selectedCol]?.raw ?: "")
    }
    var summary by remember { mutableStateOf(SheetSummary(0.0, 0.0, 0u)) }

    // Tính tổng nhanh cột hiện tại bằng Rust Core
    LaunchedEffect(currentSheet, selectedCol) {
        val colValues = currentSheet.cells
            .filter { it.col.toInt() == selectedCol }
            .map { it.display }
        summary = withContext(Dispatchers.Default) { sheetsSummarizeNumbers(colValues) }
    }

    fun commitFormula() {
        val updatedCells = currentSheet.cells
            .filterNot { it.row.toInt() == selectedRow && it.col.toInt() == selectedCol }
            .toMutableList()
        if (formulaText.isNotEmpty()) {
            updatedCells.add(
                SheetCell(
                    row = selectedRow.toUInt(),
                    col = selectedCol.toUInt(),
                    raw = formulaText,
                    display = formulaText
                )
            )
        }
        val evaluated = sheetsEvaluate(currentSheet.copy(cells = updatedCells))
        val newList = sheets.toMutableList()
        newList[safeIndex] = evaluated
        onSheetsChanged(newList)
    }

    val hScroll = rememberScrollState()
    val cellWidth = 128.dp
    val rowNumWidth = 48.dp
    val rowHeight = 38.dp

    Column(modifier = Modifier.fillMaxSize()) {
        // Thanh công thức trên cùng (tối ưu chạm và bàn phím Bluetooth)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Surface(
                tonalElevation = 2.dp,
                shape = MaterialTheme.shapes.small,
                modifier = Modifier.width(64.dp).height(44.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Text(
                        text = "${colName(selectedCol)}${selectedRow + 1}",
                        style = MaterialTheme.typography.titleSmall,
                        fontFamily = FontFamily.Monospace
                    )
                }
            }

            OutlinedTextField(
                value = formulaText,
                onValueChange = { formulaText = it },
                modifier = Modifier.weight(1f),
                singleLine = true,
                placeholder = { Text("Nhập giá trị hoặc công thức (=SUM(C2:C3), =B2*C2...)") }
            )

            Button(onClick = { commitFormula() }) { Text("Áp dụng") }
            FilledTonalButton(onClick = {
                val newList = sheets.toMutableList()
                newList[safeIndex] = currentSheet.copy(rowCount = currentSheet.rowCount + 10u)
                onSheetsChanged(newList)
            }) { Text("+10 hàng") }
            OutlinedButton(onClick = onExportXlsx) { Text("Xuất .xlsx") }
        }

        HorizontalDivider()

        // Hàng tiêu đề cột (A, B, C...) cố định trên cùng, đồng bộ cuộn ngang
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(34.dp)
                .background(MaterialTheme.colorScheme.surfaceVariant)
        ) {
            Box(
                modifier = Modifier
                    .width(rowNumWidth)
                    .height(34.dp)
                    .border(0.5.dp, MaterialTheme.colorScheme.outlineVariant),
                contentAlignment = Alignment.Center
            ) {
                Text("#", style = MaterialTheme.typography.labelMedium)
            }
            Row(modifier = Modifier.horizontalScroll(hScroll)) {
                for (c in 0 until currentSheet.colCount.toInt()) {
                    val isColSelected = c == selectedCol
                    Box(
                        modifier = Modifier
                            .width(cellWidth)
                            .height(34.dp)
                            .background(
                                if (isColSelected) MaterialTheme.colorScheme.primaryContainer
                                else MaterialTheme.colorScheme.surfaceVariant
                            )
                            .border(0.5.dp, MaterialTheme.colorScheme.outlineVariant),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(colName(c), style = MaterialTheme.typography.labelLarge)
                    }
                }
            }
        }

        // Lưới dữ liệu ảo hóa theo chiều dọc (LazyColumn) giữ 90Hz trên hàng nghìn dòng
        LazyColumn(modifier = Modifier.weight(1f).fillMaxWidth()) {
            items(currentSheet.rowCount.toInt()) { r ->
                Row(modifier = Modifier.fillMaxWidth().height(rowHeight)) {
                    // Cột số thứ tự hàng cố định bên trái
                    Box(
                        modifier = Modifier
                            .width(rowNumWidth)
                            .height(rowHeight)
                            .background(
                                if (r == selectedRow) MaterialTheme.colorScheme.primaryContainer
                                else MaterialTheme.colorScheme.surfaceVariant
                            )
                            .border(0.5.dp, MaterialTheme.colorScheme.outlineVariant),
                        contentAlignment = Alignment.Center
                    ) {
                        Text("${r + 1}", style = MaterialTheme.typography.labelMedium)
                    }

                    Row(modifier = Modifier.horizontalScroll(hScroll)) {
                        for (c in 0 until currentSheet.colCount.toInt()) {
                            val isActive = r == selectedRow && c == selectedCol
                            val cell = cellMap[r to c]
                            Box(
                                modifier = Modifier
                                    .width(cellWidth)
                                    .height(rowHeight)
                                    .background(
                                        if (isActive) MaterialTheme.colorScheme.primaryContainer
                                        else MaterialTheme.colorScheme.surface
                                    )
                                    .border(
                                        width = if (isActive) 1.5.dp else 0.5.dp,
                                        color = if (isActive) MaterialTheme.colorScheme.primary
                                        else MaterialTheme.colorScheme.outlineVariant
                                    )
                                    .clickable {
                                        selectedRow = r
                                        selectedCol = c
                                    }
                                    .padding(horizontal = 8.dp),
                                contentAlignment = Alignment.CenterStart
                            ) {
                                Text(
                                    text = cell?.display ?: "",
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    style = MaterialTheme.typography.bodyMedium
                                )
                            }
                        }
                    }
                }
            }
        }

        HorizontalDivider()

        // Thanh tab Sheet + Tổng nhanh ở đáy
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(44.dp)
                .padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            sheets.forEachIndexed { idx, s ->
                if (idx == safeIndex) {
                    FilledTonalButton(onClick = { activeSheetIndex = idx }) { Text(s.name) }
                } else {
                    OutlinedButton(onClick = { activeSheetIndex = idx }) { Text(s.name) }
                }
            }
            OutlinedButton(onClick = {
                val nextNum = sheets.size + 1
                val blank = SheetData("Sheet $nextNum", 25u, 10u, emptyList())
                onSheetsChanged(sheets + blank)
                activeSheetIndex = sheets.size
            }) { Text("+") }

            Spacer(modifier = Modifier.weight(1f))

            Text(
                text = "Cột ${colName(selectedCol)}: SUM=${"%.2f".format(summary.sum).trimEnd('0').trimEnd('.')} · COUNT=${summary.count}",
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
