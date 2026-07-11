package com.rar.echodash.ui.panels

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
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
import com.rar.echodash.ui.model.parseForecasts
import com.rar.echodash.ui.weatherIcon
import kotlinx.coroutines.delay
import kotlinx.serialization.json.JsonElement

@Composable
fun WeatherPanel(
    weather: EntityState?,
    weatherEntityId: String?,
    fetchForecast: suspend (String) -> JsonElement?,
) {
    PanelSurface {
        if (weather == null || weatherEntityId == null) {
            EmptyHint("Label a weather entity with `echo-weather` in Home Assistant")
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
        Row(horizontalArrangement = Arrangement.spacedBy(32.dp)) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(
                    imageVector = weatherIcon(conditionIcon(weather.state)),
                    contentDescription = weather.state,
                    tint = Color.White,
                    modifier = Modifier.size(96.dp),
                )
                Text(weather.state, color = Color.White, fontSize = 22.sp)
                weather.attrDouble("temperature")?.let {
                    Text("${it}°", color = Color.White, fontSize = 40.sp, fontWeight = FontWeight.Light)
                }
                weather.attrDouble("humidity")?.let {
                    Text("Humidity ${it.toInt()}%", color = Color.White.copy(alpha = 0.7f), fontSize = 14.sp)
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                forecast.forEach { day ->
                    Column(
                        Modifier
                            .clip(RoundedCornerShape(14.dp))
                            .background(Color(0xFF1B1F2A))
                            .padding(horizontal = 14.dp, vertical = 12.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Text(day.dayOfWeek, color = Color.White.copy(alpha = 0.8f), fontSize = 14.sp)
                        Icon(
                            imageVector = weatherIcon(day.icon),
                            contentDescription = null,
                            tint = Color.White,
                            modifier = Modifier.size(28.dp),
                        )
                        Text(
                            "${day.high?.toInt() ?: "-"}° / ${day.low?.toInt() ?: "-"}°",
                            color = Color.White, fontSize = 13.sp,
                        )
                    }
                }
            }
        }
    }
}
