package com.rar.echodash.ui

import android.annotation.SuppressLint
import android.net.Uri
import android.os.Build
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.rar.echodash.data.SettingsStore
import com.rar.echodash.ha.AuthManager
import com.rar.echodash.ha.DeviceInfo
import com.rar.echodash.ha.RegistrationClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

fun normalizeBaseUrl(input: String): String? {
    val trimmed = input.trim().trimEnd('/').trim()
    if (trimmed.isEmpty()) return null
    val withScheme = if ("://" in trimmed) trimmed else "http://$trimmed"
    val ok = withScheme.startsWith("http://") || withScheme.startsWith("https://")
    return if (ok) withScheme.trimEnd('/') else null
}

private sealed interface SetupPhase {
    data object EnterUrl : SetupPhase
    data class Login(val authorizeUrl: String) : SetupPhase
    data object Working : SetupPhase
}

@Composable
fun SetupScreen(
    settings: SettingsStore,
    auth: AuthManager,
    registration: RegistrationClient,
    onDone: () -> Unit,
) {
    var phase by remember { mutableStateOf<SetupPhase>(SetupPhase.EnterUrl) }
    var error by remember { mutableStateOf<String?>(null) }
    var urlText by remember { mutableStateOf(settings.baseUrl ?: "") }
    var loginJob by remember { mutableStateOf<Job?>(null) }
    val scope = rememberCoroutineScope()

    when (val p = phase) {
        is SetupPhase.EnterUrl -> Column(
            Modifier.fillMaxSize().padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text("Connect to Home Assistant", style = MaterialTheme.typography.headlineSmall)
            OutlinedTextField(
                value = urlText,
                onValueChange = { urlText = it },
                label = { Text("HA URL, e.g. http://homeassistant.local:8123") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            Button(onClick = {
                val base = normalizeBaseUrl(urlText)
                if (base == null) {
                    error = "Enter a valid http(s) URL"
                } else {
                    settings.baseUrl = base
                    error = null
                    phase = SetupPhase.Login(auth.authorizeUrl(base))
                }
            }) { Text("Connect") }
        }

        is SetupPhase.Login -> Box(Modifier.fillMaxSize()) {
            AuthWebView(
                authorizeUrl = p.authorizeUrl,
                onCode = { code ->
                    phase = SetupPhase.Working
                    loginJob = scope.launch {
                        try {
                            auth.exchangeCode(code)
                            registration.register(
                                DeviceInfo(
                                    deviceName = "Echo Dashboard",
                                    manufacturer = Build.MANUFACTURER,
                                    model = Build.MODEL,
                                    osVersion = Build.VERSION.RELEASE ?: "?",
                                )
                            )
                            onDone()
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            error = "Login failed: ${e.message}"
                            phase = SetupPhase.EnterUrl
                        }
                    }
                },
                onError = { msg ->
                    error = "Can't reach Home Assistant: $msg"
                    phase = SetupPhase.EnterUrl
                },
            )
            TextButton(
                onClick = { phase = SetupPhase.EnterUrl },
                modifier = Modifier.align(Alignment.TopStart).padding(8.dp),
            ) { Text("Cancel") }
        }

        SetupPhase.Working -> Column(
            Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            CircularProgressIndicator()
            TextButton(onClick = {
                loginJob?.cancel()
                loginJob = null
                phase = SetupPhase.EnterUrl
            }) { Text("Cancel") }
        }
    }
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun AuthWebView(authorizeUrl: String, onCode: (String) -> Unit, onError: (String) -> Unit) {
    AndroidView(
        modifier = Modifier.fillMaxSize(),
        factory = { ctx ->
            WebView(ctx).apply {
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                webViewClient = object : WebViewClient() {
                    override fun shouldOverrideUrlLoading(
                        view: WebView?,
                        request: WebResourceRequest?,
                    ): Boolean {
                        val url = request?.url?.toString() ?: return false
                        if (url.startsWith(AuthManager.REDIRECT_URI)) {
                            val code = Uri.parse(url).getQueryParameter("code")
                            if (code != null) onCode(code) else onError("no code in redirect")
                            return true
                        }
                        return false
                    }

                    override fun onReceivedError(
                        view: WebView?,
                        request: WebResourceRequest?,
                        error: WebResourceError?,
                    ) {
                        if (request?.isForMainFrame == true) {
                            onError(error?.description?.toString() ?: "page load error")
                        }
                    }
                }
                loadUrl(authorizeUrl)
            }
        },
    )
}
