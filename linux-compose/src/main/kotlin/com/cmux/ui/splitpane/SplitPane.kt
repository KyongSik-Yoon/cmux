package com.cmux.ui.splitpane

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.cmux.ui.theme.CmuxColors

enum class SplitOrientation {
    HORIZONTAL,  // Side by side
    VERTICAL     // Top and bottom
}

/**
 * Resizable split pane container.
 */
@Composable
fun SplitPane(
    orientation: SplitOrientation = SplitOrientation.HORIZONTAL,
    initialRatio: Float = 0.5f,
    dividerWidth: Dp = 4.dp,
    first: @Composable () -> Unit,
    second: @Composable () -> Unit,
    modifier: Modifier = Modifier
) {
    var ratio by remember { mutableStateOf(initialRatio) }
    val density = LocalDensity.current

    if (orientation == SplitOrientation.HORIZONTAL) {
        Row(modifier = modifier) {
            Box(modifier = Modifier.weight(ratio).fillMaxHeight()) {
                first()
            }

            // Divider
            Box(
                modifier = Modifier
                    .width(dividerWidth)
                    .fillMaxHeight()
                    .background(CmuxColors.border)
                    .pointerInput(Unit) {
                        detectDragGestures { change, dragAmount ->
                            change.consume()
                            val totalWidth = size.width.toFloat()
                            if (totalWidth > 0) {
                                val delta = dragAmount.x / totalWidth
                                ratio = (ratio + delta).coerceIn(0.1f, 0.9f)
                            }
                        }
                    }
            )

            Box(modifier = Modifier.weight(1f - ratio).fillMaxHeight()) {
                second()
            }
        }
    } else {
        Column(modifier = modifier) {
            Box(modifier = Modifier.fillMaxWidth().weight(ratio)) {
                first()
            }

            // Divider
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(dividerWidth)
                    .background(CmuxColors.border)
                    .pointerInput(Unit) {
                        detectDragGestures { change, dragAmount ->
                            change.consume()
                            val totalHeight = size.height.toFloat()
                            if (totalHeight > 0) {
                                val delta = dragAmount.y / totalHeight
                                ratio = (ratio + delta).coerceIn(0.1f, 0.9f)
                            }
                        }
                    }
            )

            Box(modifier = Modifier.fillMaxWidth().weight(1f - ratio)) {
                second()
            }
        }
    }
}
