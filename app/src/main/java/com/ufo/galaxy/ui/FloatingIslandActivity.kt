package com.ufo.galaxy.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.ufo.galaxy.ui.theme.GeekThemePremium

/**
 * 灵动岛 Activity（FloatingIslandActivity）
 *
 * 最小可用实现，满足 Manifest 注册要求，不会因类缺失导致崩溃。
 * 提供一个简单的欢迎界面，告知用户使用悬浮窗服务。
 *
 * @author UFO³ Galaxy
 * @version 1.0
 */
class FloatingIslandActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            GeekThemePremium {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(32.dp),
                        verticalArrangement = Arrangement.Center,
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            text = "UFO³ Galaxy 灵动岛",
                            style = MaterialTheme.typography.headlineMedium,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                        Text(
                            text = "悬浮窗服务由 EnhancedFloatingService 提供。\n请从主界面启动悬浮窗。",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.height(32.dp))
                        Button(onClick = { finish() }) {
                            Text("关闭")
                        }
                    }
                }
            }
        }
    }
}
