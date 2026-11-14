package com.orokichi.aegisagent.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Palette
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.orokichi.aegisagent.api.GroqClient
import com.orokichi.aegisagent.data.SettingsStore
import com.orokichi.aegisagent.model.GroqModels
import kotlinx.coroutines.launch

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SettingsScreen(
    onThemeChanged: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val store = remember { SettingsStore(context) }
    val scope = rememberCoroutineScope()
    val groqClient = remember { GroqClient() }

    var apiKey by remember { mutableStateOf(store.groqApiKey) }
    var showKey by remember { mutableStateOf(false) }
    var model by remember { mutableStateOf(store.groqModel) }
    var maxSteps by remember { mutableIntStateOf(store.maxStepsDefault) }
    var useDynamic by remember { mutableStateOf(store.useDynamicColor) }
    var themeMode by remember { mutableStateOf(store.themeMode) }
    var testResult by remember { mutableStateOf<String?>(null) }
    var testing by remember { mutableStateOf(false) }

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 104.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
                shape = MaterialTheme.shapes.extraLarge,
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(18.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Text("Groq Console API 設定", style = MaterialTheme.typography.titleLarge)
                    OutlinedTextField(
                        value = apiKey,
                        onValueChange = { apiKey = it },
                        label = { Text("Groq API Key (gsk_...)") },
                        singleLine = true,
                        visualTransformation = if (showKey) VisualTransformation.None else PasswordVisualTransformation(),
                        trailingIcon = {
                            IconButton(onClick = { showKey = !showKey }) {
                                Icon(
                                    imageVector = if (showKey) Icons.Rounded.VisibilityOff else Icons.Rounded.Visibility,
                                    contentDescription = "表示切替"
                                )
                            }
                        },
                        modifier = Modifier.fillMaxWidth()
                    )

                    Text("モデル選択", style = MaterialTheme.typography.titleSmall)
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        GroqModels.presets.forEach { (id, label) ->
                            FilterChip(
                                selected = model == id,
                                onClick = { model = id },
                                label = { Text(label) }
                            )
                        }
                    }

                    OutlinedTextField(
                        value = model,
                        onValueChange = { model = it },
                        label = { Text("カスタムモデルID") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )

                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Button(
                            onClick = {
                                store.groqApiKey = apiKey
                                store.groqModel = model
                                testResult = "保存しました"
                            },
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(Icons.Rounded.Check, contentDescription = null)
                            Spacer(Modifier.width(6.dp))
                            Text("保存")
                        }
                        OutlinedButton(
                            enabled = !testing,
                            onClick = {
                                store.groqApiKey = apiKey
                                store.groqModel = model
                                testing = true
                                testResult = "接続テスト中..."
                                scope.launch {
                                    val res = groqClient.testConnection(apiKey, model)
                                    testResult = res.fold(
                                        onSuccess = { "✅ $it" },
                                        onFailure = { "❌ ${it.message}" }
                                    )
                                    testing = false
                                }
                            },
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("接続テスト")
                        }
                    }
                    if (testResult != null) {
                        Text(testResult!!, style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
        }

        item {
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
                shape = MaterialTheme.shapes.large,
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(18.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Rounded.Palette, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text("Material 3 Expressive · テーマ", style = MaterialTheme.typography.titleMedium)
                    }
                    Spacer(Modifier.height(10.dp))
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Dynamic Color（壁紙連動）", style = MaterialTheme.typography.bodyLarge)
                            Text("Androidのデバイスカラーに自動で合わせます", style = MaterialTheme.typography.bodySmall)
                        }
                        Switch(
                            checked = useDynamic,
                            onCheckedChange = {
                                useDynamic = it
                                store.useDynamicColor = it
                                onThemeChanged()
                            }
                        )
                    }
                    Spacer(Modifier.height(10.dp))
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf("system" to "システム連動", "light" to "ライト", "dark" to "ダーク").forEach { (id, label) ->
                            FilterChip(
                                selected = themeMode == id,
                                onClick = {
                                    themeMode = id
                                    store.themeMode = id
                                    onThemeChanged()
                                },
                                label = { Text(label) }
                            )
                        }
                    }
                }
            }
        }

        item {
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
                shape = MaterialTheme.shapes.large,
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(18.dp)) {
                    Text("最大自律ステップ数: $maxSteps ステップ", style = MaterialTheme.typography.titleMedium)
                    Slider(
                        value = maxSteps.toFloat(),
                        onValueChange = {
                            maxSteps = it.toInt()
                            store.maxStepsDefault = maxSteps
                        },
                        valueRange = 3f..35f
                    )
                }
            }
        }
    }
}
