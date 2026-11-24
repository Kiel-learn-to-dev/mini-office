package com.minioffice.app.ui

import android.app.Activity
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
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
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import kotlinx.coroutines.launch
import uniffi.mini_office_core.SlideItem
import uniffi.mini_office_core.SlideLayout

@Composable
fun SlidesEditorScreen(
    slides: List<SlideItem>,
    onSlidesChanged: (List<SlideItem>) -> Unit,
    onExportPptx: () -> Unit
) {
    if (slides.isEmpty()) return

    var selectedIndex by remember(slides.size) { mutableIntStateOf(0) }
    val safeIndex = selectedIndex.coerceIn(0, slides.lastIndex)
    val current = slides[safeIndex]
    var isPresenting by remember { mutableStateOf(false) }

    if (isPresenting) {
        SlidePresenterOverlay(
            slides = slides,
            initialPage = safeIndex,
            onExit = { isPresenting = false }
        )
        return
    }

    fun updateCurrent(transform: (SlideItem) -> SlideItem) {
        val next = slides.toMutableList()
        next[safeIndex] = transform(current)
        onSlidesChanged(next)
    }

    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            FilledTonalButton(onClick = {
                val blank = SlideItem(
                    title = "Slide ${slides.size + 1}",
                    bodyLeft = "- Ý chính 1
- Ý chính 2",
                    bodyRight = "",
                    notes = "",
                    layout = SlideLayout.TITLE_BODY
                )
                onSlidesChanged(slides + blank)
                selectedIndex = slides.size
            }) { Text("+ Slide") }

            OutlinedButton(onClick = {
                val next = slides.toMutableList()
                next.add(safeIndex + 1, current.copy())
                onSlidesChanged(next)
                selectedIndex = safeIndex + 1
            }) { Text("Nhân bản") }

            OutlinedButton(
                enabled = safeIndex > 0,
                onClick = {
                    val next = slides.toMutableList()
                    val item = next.removeAt(safeIndex)
                    next.add(safeIndex - 1, item)
                    onSlidesChanged(next)
                    selectedIndex = safeIndex - 1
                }
            ) { Text("Lên") }

            OutlinedButton(
                enabled = safeIndex < slides.lastIndex,
                onClick = {
                    val next = slides.toMutableList()
                    val item = next.removeAt(safeIndex)
                    next.add(safeIndex + 1, item)
                    onSlidesChanged(next)
                    selectedIndex = safeIndex + 1
                }
            ) { Text("Xuống") }

            OutlinedButton(
                enabled = slides.size > 1,
                onClick = {
                    val next = slides.toMutableList()
                    next.removeAt(safeIndex)
                    onSlidesChanged(next)
                    selectedIndex = (safeIndex - 1).coerceAtLeast(0)
                }
            ) { Text("Xóa") }

            Spacer(modifier = Modifier.weight(1f))

            OutlinedButton(onClick = {
                val nextLayout = when (current.layout) {
                    SlideLayout.COVER -> SlideLayout.TITLE_BODY
                    SlideLayout.TITLE_BODY -> SlideLayout.TWO_COLUMN
                    SlideLayout.TWO_COLUMN -> SlideLayout.COVER
                }
                updateCurrent { it.copy(layout = nextLayout) }
            }) {
                val label = when (current.layout) {
                    SlideLayout.COVER -> "Bố cục: Bìa"
                    SlideLayout.TITLE_BODY -> "Bố cục: Nội dung"
                    SlideLayout.TWO_COLUMN -> "Bố cục: 2 cột"
                }
                Text(label)
            }

            OutlinedButton(onClick = onExportPptx) { Text("Xuất .pptx") }
            Button(onClick = { isPresenting = true }) { Text("Trình chiếu") }
        }

        HorizontalDivider()

        Row(modifier = Modifier.weight(1f).fillMaxWidth()) {
            LazyColumn(
                modifier = Modifier
                    .width(200.dp)
                    .fillMaxHeight()
                    .padding(10.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                itemsIndexed(slides) { idx, item ->
                    val isSelected = idx == safeIndex
                    Surface(
                        tonalElevation = if (isSelected) 3.dp else 1.dp,
                        shape = MaterialTheme.shapes.small,
                        modifier = Modifier
                            .fillMaxWidth()
                            .aspectRatio(16f / 9f)
                            .border(
                                width = if (isSelected) 2.dp else 0.5.dp,
                                color = if (isSelected) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.outlineVariant,
                                shape = MaterialTheme.shapes.small
                            )
                            .clickable { selectedIndex = idx }
                            .padding(8.dp)
                    ) {
                        Column(verticalArrangement = Arrangement.SpaceBetween) {
                            Text(
                                text = "${idx + 1}. ${item.title}",
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                                style = MaterialTheme.typography.labelMedium
                            )
                            Text(
                                text = item.layout.name,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }

            VerticalDivider()

            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                OutlinedTextField(
                    value = current.title,
                    onValueChange = { t -> updateCurrent { it.copy(title = t) } },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    label = { Text("Tiêu đề Slide ${safeIndex + 1}") }
                )

                Row(
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    OutlinedTextField(
                        value = current.bodyLeft,
                        onValueChange = { v -> updateCurrent { it.copy(bodyLeft = v) } },
                        modifier = Modifier.weight(1f).fillMaxHeight(),
                        label = {
                            Text(
                                when (current.layout) {
                                    SlideLayout.COVER -> "Phụ đề / Người trình bày"
                                    SlideLayout.TITLE_BODY -> "Nội dung chính"
                                    SlideLayout.TWO_COLUMN -> "Nội dung Cột trái"
                                }
                            )
                        }
                    )

                    if (current.layout == SlideLayout.TWO_COLUMN) {
                        OutlinedTextField(
                            value = current.bodyRight,
                            onValueChange = { v -> updateCurrent { it.copy(bodyRight = v) } },
                            modifier = Modifier.weight(1f).fillMaxHeight(),
                            label = { Text("Nội dung Cột phải") }
                        )
                    }
                }

                OutlinedTextField(
                    value = current.notes,
                    onValueChange = { n -> updateCurrent { it.copy(notes = n) } },
                    modifier = Modifier.fillMaxWidth().height(72.dp),
                    label = { Text("Ghi chú trình chiếu (Speaker Notes)") }
                )
            }
        }
    }
}

@Composable
private fun SlidePresenterOverlay(
    slides: List<SlideItem>,
    initialPage: Int,
    onExit: () -> Unit
) {
    val pagerState = rememberPagerState(initialPage = initialPage, pageCount = { slides.size })
    val scope = rememberCoroutineScope()
    val focusRequester = remember { FocusRequester() }
    val view = LocalView.current
    var laserPoint by remember { mutableStateOf<Offset?>(null) }

    // Ẩn toàn bộ thanh hệ thống Android (Immersive Mode) khi trình chiếu và tự khôi phục khi thoát
    DisposableEffect(view) {
        val window = (view.context as? Activity)?.window
        val controller = window?.let { WindowCompat.getInsetsController(it, view) }
        controller?.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        controller?.hide(WindowInsetsCompat.Type.systemBars())
        onDispose {
            controller?.show(WindowInsetsCompat.Type.systemBars())
        }
    }

    LaunchedEffect(Unit) {
        focusRequester.requestFocus()
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF101216))
            .focusRequester(focusRequester)
            .focusable()
            .onPreviewKeyEvent { event ->
                if (event.type == KeyEventType.KeyDown) {
                    when (event.key) {
                        Key.Escape -> {
                            onExit()
                            true
                        }
                        Key.DirectionRight, Key.Spacebar -> {
                            val next = (pagerState.currentPage + 1).coerceAtMost(slides.lastIndex)
                            scope.launch { pagerState.animateScrollToPage(next) }
                            true
                        }
                        Key.DirectionLeft -> {
                            val prev = (pagerState.currentPage - 1).coerceAtLeast(0)
                            scope.launch { pagerState.animateScrollToPage(prev) }
                            true
                        }
                        else -> false
                    }
                } else false
            }
            .pointerInput(Unit) {
                detectDragGesturesAfterLongPress(
                    onDragStart = { offset -> laserPoint = offset },
                    onDrag = { change, _ ->
                        change.consume()
                        laserPoint = change.position
                    },
                    onDragEnd = { laserPoint = null },
                    onDragCancel = { laserPoint = null }
                )
            }
    ) {
        HorizontalPager(
            state = pagerState,
            modifier = Modifier.fillMaxSize()
        ) { page ->
            val slide = slides[page]
            Box(
                modifier = Modifier.fillMaxSize().padding(24.dp),
                contentAlignment = Alignment.Center
            ) {
                Surface(
                    color = Color.White,
                    shape = MaterialTheme.shapes.medium,
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(16f / 9f)
                        .padding(8.dp)
                ) {
                    SlideCanvasContent(slide)
                }
            }
        }

        if (laserPoint != null) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                val pt = laserPoint ?: return@Canvas
                drawCircle(color = Color(0x55FF1744), radius = 26f, center = pt)
                drawCircle(color = Color(0xFFFF1744), radius = 10f, center = pt)
            }
        }

        Row(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "${pagerState.currentPage + 1} / ${slides.size}",
                color = Color.White,
                style = MaterialTheme.typography.labelLarge
            )
            FilledTonalButton(onClick = onExit) { Text("Thoát (Esc)") }
        }
    }
}

@Composable
private fun SlideCanvasContent(slide: SlideItem) {
    when (slide.layout) {
        SlideLayout.COVER -> {
            Column(
                modifier = Modifier.fillMaxSize().padding(48.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = slide.title,
                    style = MaterialTheme.typography.displaySmall,
                    color = Color(0xFF111827),
                    textAlign = TextAlign.Center
                )
                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    text = slide.bodyLeft,
                    style = MaterialTheme.typography.titleLarge,
                    color = Color(0xFF4B5563),
                    textAlign = TextAlign.Center
                )
            }
        }
        SlideLayout.TITLE_BODY -> {
            Column(
                modifier = Modifier.fillMaxSize().padding(40.dp),
                verticalArrangement = Arrangement.spacedBy(20.dp)
            ) {
                Text(
                    text = slide.title,
                    style = MaterialTheme.typography.headlineLarge,
                    color = Color(0xFF111827)
                )
                HorizontalDivider()
                Text(
                    text = slide.bodyLeft,
                    style = MaterialTheme.typography.titleMedium,
                    color = Color(0xFF1F2937)
                )
            }
        }
        SlideLayout.TWO_COLUMN -> {
            Column(
                modifier = Modifier.fillMaxSize().padding(40.dp),
                verticalArrangement = Arrangement.spacedBy(20.dp)
            ) {
                Text(
                    text = slide.title,
                    style = MaterialTheme.typography.headlineLarge,
                    color = Color(0xFF111827)
                )
                HorizontalDivider()
                Row(
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(24.dp)
                ) {
                    Text(
                        text = slide.bodyLeft,
                        style = MaterialTheme.typography.titleMedium,
                        color = Color(0xFF1F2937),
                        modifier = Modifier.weight(1f)
                    )
                    VerticalDivider()
                    Text(
                        text = slide.bodyRight,
                        style = MaterialTheme.typography.titleMedium,
                        color = Color(0xFF1F2937),
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        }
    }
}
