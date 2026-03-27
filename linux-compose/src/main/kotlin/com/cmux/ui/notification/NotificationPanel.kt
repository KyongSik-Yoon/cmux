package com.cmux.ui.notification

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.cmux.ui.theme.CmuxColors
import com.cmux.ui.theme.CmuxTypography
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

enum class NotificationLevel {
    INFO, SUCCESS, WARNING, ERROR
}

data class Notification(
    val id: String = java.util.UUID.randomUUID().toString(),
    val title: String,
    val body: String = "",
    val level: NotificationLevel = NotificationLevel.INFO,
    val source: String = "",
    val timestamp: LocalDateTime = LocalDateTime.now()
)

class NotificationStore {
    private val _notifications = mutableStateListOf<Notification>()
    val notifications: List<Notification> get() = _notifications

    val unreadCount: Int get() = _notifications.size

    fun add(notification: Notification) {
        _notifications.add(0, notification)
        // Keep max 100 notifications
        while (_notifications.size > 100) {
            _notifications.removeAt(_notifications.size - 1)
        }
    }

    fun clear() {
        _notifications.clear()
    }

    fun remove(id: String) {
        _notifications.removeAll { it.id == id }
    }
}

/**
 * Notification panel shown in sidebar or as overlay.
 */
@Composable
fun NotificationPanel(
    store: NotificationStore,
    modifier: Modifier = Modifier
) {
    val scrollState = rememberScrollState()

    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(CmuxColors.surface)
            .padding(8.dp)
    ) {
        // Header
        Row(
            modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                "Notifications",
                style = MaterialTheme.typography.titleSmall.copy(
                    color = CmuxColors.onBackground,
                    fontWeight = FontWeight.Bold
                )
            )
            if (store.notifications.isNotEmpty()) {
                Text(
                    "${store.notifications.size}",
                    style = MaterialTheme.typography.labelSmall.copy(
                        color = CmuxColors.onSurface
                    )
                )
            }
        }

        if (store.notifications.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxWidth().padding(vertical = 24.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    "No notifications",
                    style = MaterialTheme.typography.bodySmall.copy(
                        color = CmuxColors.onSurface
                    )
                )
            }
        } else {
            Column(
                modifier = Modifier.verticalScroll(scrollState),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                store.notifications.forEach { notif ->
                    NotificationItem(notif)
                }
            }
        }
    }
}

@Composable
private fun NotificationItem(notification: Notification) {
    val levelColor = when (notification.level) {
        NotificationLevel.INFO -> CmuxColors.notifInfo
        NotificationLevel.SUCCESS -> CmuxColors.notifSuccess
        NotificationLevel.WARNING -> CmuxColors.notifWarning
        NotificationLevel.ERROR -> CmuxColors.notifError
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(6.dp))
            .background(CmuxColors.surfaceVariant)
            .padding(8.dp),
        verticalAlignment = Alignment.Top
    ) {
        // Level indicator
        Box(
            modifier = Modifier
                .padding(top = 4.dp)
                .size(8.dp)
                .clip(CircleShape)
                .background(levelColor)
        )

        Spacer(modifier = Modifier.width(8.dp))

        Column(modifier = Modifier.weight(1f)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    notification.title,
                    style = MaterialTheme.typography.bodySmall.copy(
                        color = CmuxColors.onBackground,
                        fontWeight = FontWeight.Medium,
                        fontSize = CmuxTypography.sidebarFontSize,
                    ),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                Text(
                    notification.timestamp.format(DateTimeFormatter.ofPattern("HH:mm")),
                    style = MaterialTheme.typography.labelSmall.copy(
                        color = CmuxColors.onSurface,
                        fontSize = CmuxTypography.titleFontSize,
                    )
                )
            }
            if (notification.body.isNotEmpty()) {
                Text(
                    notification.body,
                    style = MaterialTheme.typography.labelSmall.copy(
                        color = CmuxColors.onSurfaceVariant,
                        fontSize = CmuxTypography.titleFontSize,
                    ),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
            if (notification.source.isNotEmpty()) {
                Text(
                    notification.source,
                    style = MaterialTheme.typography.labelSmall.copy(
                        color = CmuxColors.onSurface,
                        fontSize = CmuxTypography.titleFontSize,
                    ),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}
