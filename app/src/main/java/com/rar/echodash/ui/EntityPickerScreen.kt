package com.rar.echodash.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.unit.dp
import com.rar.echodash.data.SettingsStore
import com.rar.echodash.ha.EntityState
import com.rar.echodash.ha.HaWebSocket
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout

const val DEFAULT_TEMPERATURE_ENTITY = "sensor.outside_temperature"

@Composable
fun EntityPickerScreen(settings: SettingsStore, ws: HaWebSocket, onPicked: () -> Unit) {
    var sensors by remember { mutableStateOf<List<EntityState>?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var attempt by remember { mutableIntStateOf(0) }

    LaunchedEffect(attempt) {
        error = null
        sensors = null
        ws.start(null)
        try {
            val fetched = withTimeout(15_000) { ws.fetchTemperatureSensors() }
            // default sensor first, then alphabetical by display name
            sensors = fetched.sortedWith(
                compareByDescending<EntityState> { it.entityId == DEFAULT_TEMPERATURE_ENTITY }
                    .thenBy { it.friendlyName ?: it.entityId }
            )
        } catch (e: TimeoutCancellationException) {
            error = "Couldn't load sensors: timed out"
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            error = "Couldn't load sensors: ${e.message ?: e.javaClass.simpleName}"
        }
    }

    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Text("Pick a temperature sensor", style = MaterialTheme.typography.headlineSmall)
        when {
            error != null -> Column(
                Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(error!!, color = MaterialTheme.colorScheme.error)
                Button(onClick = { attempt++ }) { Text("Retry") }
            }
            sensors == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
            sensors!!.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("No temperature sensors found in Home Assistant")
            }
            else -> LazyColumn(Modifier.fillMaxSize()) {
                items(sensors!!, key = { it.entityId }) { sensor ->
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .clickable {
                                settings.temperatureEntityId = sensor.entityId
                                onPicked()
                            }
                            .padding(vertical = 12.dp, horizontal = 8.dp)
                    ) {
                        Text(
                            "${sensor.friendlyName ?: sensor.entityId} — ${sensor.state}${sensor.unit ?: ""}",
                            style = MaterialTheme.typography.bodyLarge,
                        )
                        Text(sensor.entityId, style = MaterialTheme.typography.bodySmall)
                    }
                    HorizontalDivider()
                }
            }
        }
    }
}
