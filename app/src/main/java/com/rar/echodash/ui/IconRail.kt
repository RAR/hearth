package com.rar.echodash.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/** Right-side translucent rail: six Material icon buttons; the active one sits on an accent square. */
@Composable
fun IconRail(current: DashView, onSelect: (DashView) -> Unit, modifier: Modifier = Modifier) {
    Column(
        modifier
            .width(72.dp)
            .clip(RoundedCornerShape(24.dp))
            .background(Color.Black.copy(alpha = 0.55f))
            .padding(vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        DashView.entries.forEach { view ->
            val active = view == current
            Box(
                Modifier
                    .size(52.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .background(if (active) Color(0xFF3A6EA5) else Color.Transparent)
                    .clickable { onSelect(view) },
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = railIcon(view),
                    contentDescription = view.name,
                    tint = Color.White,
                    modifier = Modifier.size(28.dp),
                )
            }
        }
    }
}
