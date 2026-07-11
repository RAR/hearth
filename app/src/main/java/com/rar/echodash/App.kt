package com.rar.echodash

import android.content.Context
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.rar.echodash.data.PrefsSettingsStore
import com.rar.echodash.data.SettingsStore
import com.rar.echodash.ha.AuthManager
import com.rar.echodash.ha.ConnState
import com.rar.echodash.ha.HaWebSocket
import com.rar.echodash.ui.DashboardScreen
import com.rar.echodash.ui.EntityPickerScreen
import com.rar.echodash.ui.SetupScreen
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import okhttp3.OkHttpClient

class AppDeps(context: Context) {
    val settings: SettingsStore = PrefsSettingsStore(context.applicationContext)
    val client = OkHttpClient()
    val auth = AuthManager(settings, client)
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val ws = HaWebSocket(settings, auth, client, scope)
}

sealed interface Screen {
    data object Setup : Screen
    data object Picker : Screen
    data object Dashboard : Screen
}

fun initialScreen(settings: SettingsStore): Screen = when {
    settings.refreshToken == null -> Screen.Setup
    settings.temperatureEntityId == null -> Screen.Picker
    else -> Screen.Dashboard
}

@Composable
fun EchoDashApp(deps: AppDeps) {
    var screen by remember { mutableStateOf(initialScreen(deps.settings)) }
    val connState by deps.ws.connectionState.collectAsStateWithLifecycle()

    LaunchedEffect(connState) {
        if (connState == ConnState.AUTH_FAILED) {
            deps.ws.stop()
            screen = Screen.Setup
        }
    }

    MaterialTheme(colorScheme = darkColorScheme()) {
        when (screen) {
            Screen.Setup -> SetupScreen(deps.settings, deps.auth) {
                screen = Screen.Picker
            }
            Screen.Picker -> EntityPickerScreen(deps.settings, deps.ws) {
                screen = Screen.Dashboard
            }
            Screen.Dashboard -> {
                LaunchedEffect(Unit) { deps.ws.start(deps.settings.temperatureEntityId) }
                val reading by deps.ws.reading.collectAsStateWithLifecycle()
                DashboardScreen(
                    reading = reading,
                    connState = connState,
                    onChangeSensor = { screen = Screen.Picker },
                    onLogout = {
                        deps.ws.stop()
                        deps.settings.clearAuth()
                        screen = Screen.Setup
                    },
                )
            }
        }
    }
}
