package com.ufo.galaxy.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.ufo.galaxy.call.PhoneCallController
import com.ufo.galaxy.shared.protocol.call.CallState

/**
 * 和智能体通话的界面:状态、智能体说的话、交给它的事做到哪了、静音与挂断。
 *
 * 通话本体在 [com.ufo.galaxy.call.PhoneCallService] 里(要活过熄屏和划走界面);这里只看它的状态。
 */
@Composable
fun PhoneCallScreen(controller: PhoneCallController, onHangup: () -> Unit) {
    val ui by controller.ui.collectAsState()
    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(
            modifier = Modifier.fillMaxSize().padding(24.dp),
            verticalArrangement = Arrangement.SpaceBetween,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text("Galaxy", style = MaterialTheme.typography.headlineMedium)
                Text(
                    text = when (ui.state) {
                        CallState.IDLE, CallState.DIALING -> "呼叫中…"
                        CallState.IN_CALL -> "通话中"
                        CallState.ENDED -> "已结束"
                    },
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                if (ui.transcript.isNotBlank()) {
                    Text(ui.transcript, style = MaterialTheme.typography.bodyLarge, textAlign = TextAlign.Center)
                }
                if (ui.agentTask.isNotBlank()) {
                    Text(
                        ui.agentTask,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.primary,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(top = 12.dp),
                    )
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                OutlinedButton(onClick = { controller.setMuted(!ui.muted) }) {
                    Text(if (ui.muted) "取消静音" else "静音")
                }
                Button(
                    onClick = onHangup,
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                ) {
                    Text("挂断")
                }
            }
        }
    }
}
