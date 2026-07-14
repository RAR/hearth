package com.rar.echodash.ui.panels

import android.text.format.DateFormat
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rar.echodash.config.ClockFormat
import com.rar.echodash.ui.clockIs24
import com.rar.echodash.ui.model.AgendaDay
import com.rar.echodash.ui.model.CalendarEvent
import com.rar.echodash.ui.model.agendaDays
import com.rar.echodash.ui.model.eventClockLabel
import java.time.ZoneId

/** 3-day agenda: three equal-weight day columns of color-coded event rows. */
@Composable
fun CalendarPanel(
    events: List<CalendarEvent>,
    hasCalendars: Boolean,
    clockFormat: ClockFormat,
) {
    PanelSurface {
        if (!hasCalendars) {
            EmptyHint("Add calendars in the web config")
            return@PanelSurface
        }
        val context = LocalContext.current
        val is24 = clockIs24(clockFormat, DateFormat.is24HourFormat(context))
        val zone = ZoneId.systemDefault()
        val nowMs = System.currentTimeMillis()
        val days = agendaDays(events, nowMs, zone)
        Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            days.forEach { day ->
                DayColumn(day, nowMs, zone, is24, Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun DayColumn(
    day: AgendaDay,
    nowMs: Long,
    zone: ZoneId,
    is24: Boolean,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier
            .fillMaxHeight()
            .clip(RoundedCornerShape(14.dp))
            .background(Color(0xFF1B1F2A))
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(
            day.label, color = Color.White.copy(alpha = 0.8f),
            fontSize = 16.sp, fontWeight = FontWeight.Medium,
        )
        if (day.events.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("—", color = Color.White.copy(alpha = 0.4f), fontSize = 20.sp)
            }
        } else {
            Column(
                Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                day.events.forEach { EventRow(it, nowMs, zone, is24) }
            }
        }
    }
}

@Composable
private fun EventRow(event: CalendarEvent, nowMs: Long, zone: ZoneId, is24: Boolean) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box(Modifier.size(10.dp).clip(CircleShape).background(Color(event.colorArgb)))
        Column {
            Text(
                eventClockLabel(event, nowMs, zone, is24),
                color = Color.White.copy(alpha = 0.7f), fontSize = 13.sp,
            )
            Text(
                event.title, color = Color.White, fontSize = 15.sp,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
