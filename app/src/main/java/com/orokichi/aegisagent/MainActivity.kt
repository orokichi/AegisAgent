package com.orokichi.aegisagent

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Dashboard
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material.icons.rounded.Security
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.StopCircle
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import com.orokichi.aegisagent.agent.AgentEngine
import com.orokichi.aegisagent.data.SettingsStore
import com.orokichi.aegisagent.scheduler.TaskScheduler
import com.orokichi.aegisagent.ui.screens.DashboardScreen
import com.orokichi.aegisagent.ui.screens.ScheduleScreen
import com.orokichi.aegisagent.ui.screens.SecurityScreen
import com.orokichi.aegisagent.ui.screens.SettingsScreen
import com.orokichi.aegisagent.ui.theme.AegisTheme

class MainActivity : ComponentActivity() {

    private val notifLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { }

    @OptIn(ExperimentalMaterial3Api::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        createNotificationChannel()
        requestNotificationPermissionIfNeeded()

        AgentEngine.loadLogsFromStore(this)
        TaskScheduler.rescheduleAll(this)

        setContent {
            val store = remember { SettingsStore(this) }
            var themeTick by remember { mutableIntStateOf(0) }
            val useDynamic = remember(themeTick) { store.useDynamicColor }
            val themeMode = remember(themeTick) { store.themeMode }

            var selectedTab by remember { mutableStateOf(0) }

            AegisTheme(useDynamicColor = useDynamic, themeMode = themeMode) {
                Scaffold(
                    topBar = {
                        TopAppBar(
                            title = { Text("AegisAgent") },
                            actions = {
                                IconButton(
                                    onClick = {
                                        AgentEngine.emergencyStop(this@MainActivity, "ツールバーの緊急停止アイコンから停止")
                                    }
                                ) {
                                    Icon(
                                        imageVector = Icons.Rounded.StopCircle,
                                        contentDescription = "緊急停止",
                                        tint = MaterialTheme.colorScheme.error
                                    )
                                }
                            }
                        )
                    },
                    bottomBar = {
                        NavigationBar {
                            NavigationBarItem(
                                selected = selectedTab == 0,
                                onClick = { selectedTab = 0 },
                                icon = { Icon(Icons.Rounded.Dashboard, contentDescription = null) },
                                label = { Text("ホーム") }
                            )
                            NavigationBarItem(
                                selected = selectedTab == 1,
                                onClick = { selectedTab = 1 },
                                icon = { Icon(Icons.Rounded.Schedule, contentDescription = null) },
                                label = { Text("定期") }
                            )
                            NavigationBarItem(
                                selected = selectedTab == 2,
                                onClick = { selectedTab = 2 },
                                icon = { Icon(Icons.Rounded.Security, contentDescription = null) },
                                label = { Text("安全装置") }
                            )
                            NavigationBarItem(
                                selected = selectedTab == 3,
                                onClick = { selectedTab = 3 },
                                icon = { Icon(Icons.Rounded.Settings, contentDescription = null) },
                                label = { Text("設定") }
                            )
                        }
                    }
                ) { innerPadding ->
                    val mod = Modifier.padding(innerPadding)
                    when (selectedTab) {
                        0 -> DashboardScreen(onOpenSettings = { selectedTab = 3 }, modifier = mod)
                        1 -> ScheduleScreen(modifier = mod)
                        2 -> SecurityScreen(modifier = mod)
                        else -> SettingsScreen(onThemeChanged = { themeTick++ }, modifier = mod)
                    }
                }
            }
        }
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            AgentEngine.CHANNEL_ID,
            "AegisAgent 自律実行・緊急停止",
            NotificationManager.IMPORTANCE_HIGH
        )
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(channel)
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED
            ) {
                notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }
}
