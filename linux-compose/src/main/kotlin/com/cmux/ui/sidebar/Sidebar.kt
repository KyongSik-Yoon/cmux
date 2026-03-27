package com.cmux.ui.sidebar

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.cmux.terminal.Terminal
import com.cmux.ui.theme.CmuxColors
import com.cmux.ui.theme.CmuxTypography

/**
 * Vertical tab sidebar - the signature cmux UI element.
 */
@Composable
fun Sidebar(
    tabs: List<Terminal>,
    activeTabId: String,
    onTabSelected: (String) -> Unit,
    onTabClosed: (String) -> Unit,
    onNewTab: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxHeight()
            .width(220.dp)
            .background(CmuxColors.surface)
            .padding(top = 8.dp)
    ) {
        // Header
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "cmux",
                style = MaterialTheme.typography.titleSmall.copy(
                    fontWeight = FontWeight.Bold,
                    color = CmuxColors.primary
                )
            )
            IconButton(
                onClick = onNewTab,
                modifier = Modifier.size(24.dp)
            ) {
                Icon(
                    Icons.Default.Add,
                    contentDescription = "New Tab",
                    tint = CmuxColors.onSurface,
                    modifier = Modifier.size(16.dp)
                )
            }
        }

        HorizontalDivider(
            color = CmuxColors.border,
            modifier = Modifier.padding(horizontal = 8.dp)
        )

        Spacer(modifier = Modifier.height(4.dp))

        // Tab list
        val scrollState = rememberScrollState()
        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(scrollState)
                .padding(horizontal = 6.dp)
        ) {
            tabs.forEachIndexed { index, terminal ->
                SidebarTab(
                    terminal = terminal,
                    index = index + 1,
                    isActive = terminal.id == activeTabId,
                    onClick = { onTabSelected(terminal.id) },
                    onClose = { onTabClosed(terminal.id) }
                )
                Spacer(modifier = Modifier.height(2.dp))
            }
        }

        // Bottom status
        HorizontalDivider(color = CmuxColors.border, modifier = Modifier.padding(horizontal = 8.dp))
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "${tabs.size} tab${if (tabs.size != 1) "s" else ""}",
                style = MaterialTheme.typography.labelSmall.copy(
                    color = CmuxColors.onSurface,
                    fontSize = CmuxTypography.titleFontSize
                )
            )
        }
    }
}

@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun SidebarTab(
    terminal: Terminal,
    index: Int,
    isActive: Boolean,
    onClick: () -> Unit,
    onClose: () -> Unit
) {
    val title by terminal.title.collectAsState()
    val cwd by terminal.cwd.collectAsState()
    val alive by terminal.alive.collectAsState()
    var hovered by remember { mutableStateOf(false) }

    val bgColor = when {
        isActive -> CmuxColors.surfaceVariant
        hovered -> CmuxColors.surfaceVariant.copy(alpha = 0.5f)
        else -> Color.Transparent
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(6.dp))
            .background(bgColor)
            .clickable(onClick = onClick)
            .onPointerEvent(PointerEventType.Enter) { hovered = true }
            .onPointerEvent(PointerEventType.Exit) { hovered = false }
            .padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Tab index badge
        Box(
            modifier = Modifier
                .size(20.dp)
                .clip(RoundedCornerShape(4.dp))
                .background(
                    if (isActive) CmuxColors.primary.copy(alpha = 0.2f)
                    else CmuxColors.border
                ),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = "$index",
                style = MaterialTheme.typography.labelSmall.copy(
                    color = if (isActive) CmuxColors.primary else CmuxColors.onSurface,
                    fontSize = CmuxTypography.titleFontSize,
                    fontWeight = FontWeight.Medium
                )
            )
        }

        Spacer(modifier = Modifier.width(8.dp))

        // Tab info
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title.ifEmpty { "Terminal" },
                style = MaterialTheme.typography.bodySmall.copy(
                    color = if (isActive) CmuxColors.onBackground else CmuxColors.onSurfaceVariant,
                    fontWeight = if (isActive) FontWeight.Medium else FontWeight.Normal,
                    fontSize = CmuxTypography.sidebarFontSize,
                ),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            // Show shortened cwd
            val shortCwd = cwd.replace(System.getenv("HOME") ?: "", "~")
            Text(
                text = shortCwd,
                style = MaterialTheme.typography.labelSmall.copy(
                    color = CmuxColors.onSurface,
                    fontSize = CmuxTypography.titleFontSize,
                ),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }

        // Status indicator / close button
        if (hovered || isActive) {
            IconButton(
                onClick = onClose,
                modifier = Modifier.size(18.dp)
            ) {
                Icon(
                    Icons.Default.Close,
                    contentDescription = "Close Tab",
                    tint = CmuxColors.onSurface,
                    modifier = Modifier.size(12.dp)
                )
            }
        } else {
            // Alive indicator dot
            Box(
                modifier = Modifier
                    .size(6.dp)
                    .clip(CircleShape)
                    .background(
                        if (alive) CmuxColors.secondary.copy(alpha = 0.6f)
                        else CmuxColors.error.copy(alpha = 0.4f)
                    )
            )
        }
    }
}

