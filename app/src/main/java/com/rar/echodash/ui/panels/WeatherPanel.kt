package com.rar.echodash.ui.panels

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rar.echodash.ha.EntityState
import com.rar.echodash.ui.model.DailyForecast
import com.rar.echodash.ui.model.conditionIcon
import com.rar.echodash.ui.model.formatSensor
import com.rar.echodash.ui.model.parseForecasts
import com.rar.echodash.ui.weatherIcon
import kotlinx.coroutines.delay
import kotlinx.serialization.json.JsonElement

@Composable
fun WeatherPanel(
    weather: EntityState?,
    weatherEntityId: String?,
    forecastDays: Int,
    sensorDecimals: Int,
    fetchForecast: suspend (String) -> JsonElement?,
) {
    PanelSurface {
        if (weather == null || weatherEntityId == null) {
            EmptyHint("Set a weather entity in the web config")
            return@PanelSurface
        }
        var forecast by remember(weatherEntityId) { mutableStateOf<List<DailyForecast>>(emptyList()) }
        // refresh on open and every 30 min; keep last on failure
        LaunchedEffect(weatherEntityId) {
            while (true) {
                val parsed = parseForecasts(fetchForecast(weatherEntityId), weatherEntityId)
                if (parsed.isNotEmpty()) forecast = parsed
                delay(30 * 60_000L)
            }
        }
        Column(Modifier.fillMaxSize()) {
            // current conditions, centered in the space above the forecast strip
            Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(24.dp),
                ) {
                    Icon(
                        imageVector = weatherIcon(conditionIcon(weather.state)),
                        contentDescription = weather.state,
                        tint = Color.White,
                        modifier = Modifier.size(110.dp),
                    )
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        weather.attrDouble("temperature")?.let {
                            Text(
                                "${formatSensor(it, sensorDecimals)}°",
                                color = Color.White, fontSize = 56.sp, fontWeight = FontWeight.Light,
                            )
                        }
                        Text(weather.state, color = Color.White, fontSize = 24.sp)
                        weather.attrDouble("humidity")?.let {
                            Text("Humidity ${it.toInt()}%", color = Color.White.copy(alpha = 0.7f), fontSize = 16.sp)
                        }
                    }
                }
            }
            // forecast strip along the bottom, days sharing the full width
            if (forecast.isNotEmpty()) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    forecast.take(forecastDays).forEach { day ->
                        Column(
                            Modifier
                                .weight(1f)
                                .clip(RoundedCornerShape(14.dp))
                                .background(Color(0xFF1B1F2A))
                                .padding(vertical = 14.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Text(day.dayOfWeek, color = Color.White.copy(alpha = 0.8f), fontSize = 16.sp)
                            Icon(
                                imageVector = weatherIcon(day.icon),
                                contentDescription = null,
                                tint = Color.White,
                                modifier = Modifier.size(34.dp),
                            )
                            Text(
                                "${day.high?.toInt() ?: "-"}° / ${day.low?.toInt() ?: "-"}°",
                                color = Color.White, fontSize = 15.sp,
                            )
                        }
                    }
                }
            }
        }
    }
}
