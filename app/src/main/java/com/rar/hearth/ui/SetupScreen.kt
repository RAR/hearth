package com.rar.hearth.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * First-run pointer card. Setup now happens in the browser at the config page; this screen just tells
 * the user where to go and shows the PIN. The device flips to the Dashboard automatically when the
 * browser completes setup (AppDeps.setupEvents), so there is no on-device input here.
 */
@Composable
fun SetupScreen(configUrl: String, configPin: String) {
    Column(
        Modifier
            .fillMaxSize()
            .background(Color(0xFF0F1420))
            .padding(32.dp),
        verticalArrangement = Arrangement.spacedBy(24.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            "Set up this dashboard",
            color = Color(0xFFE7ECF3),
            fontWeight = FontWeight.Bold,
            fontSize = 30.sp,
            textAlign = TextAlign.Center,
        )
        Text(
            "Open a browser on another device on this network and go to:",
            color = Color(0xFFAAB8D2),
            fontSize = 18.sp,
            textAlign = TextAlign.Center,
        )
        Card(
            shape = RoundedCornerShape(18.dp),
            colors = CardDefaults.cardColors(containerColor = Color(0xFF151B28)),
        ) {
            Column(
                Modifier.padding(horizontal = 40.dp, vertical = 28.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    configUrl,
                    color = Color(0xFF7FB2FF),
                    fontWeight = FontWeight.Bold,
                    fontSize = 26.sp,
                    textAlign = TextAlign.Center,
                )
                Text(
                    "PIN  $configPin",
                    color = Color(0xFFE7ECF3),
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 22.sp,
                )
            }
        }
        Text(
            "Enter the PIN, then connect to Home Assistant. This screen switches to the dashboard automatically once setup finishes.",
            color = Color(0xFF7F8DA6),
            fontSize = 15.sp,
            textAlign = TextAlign.Center,
        )
    }
}
