package com.nikosm.voiceassistant

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

@Composable
fun WelcomeScreen(service: AssistantService?, onFinish: () -> Unit, personaColor: Color) {
    var step by remember { mutableIntStateOf(0) }
    val isReady = service != null

    // Step 1: Ollama
    var ollamaName by remember { mutableStateOf("Local Ollama") }
    var ollamaUrl by remember { mutableStateOf("") }
    var ollamaUser by remember { mutableStateOf("") }
    var ollamaPass by remember { mutableStateOf("") }
    var ollamaAuthType by remember { mutableStateOf(AuthType.NONE) }
    var ollamaApiKey by remember { mutableStateOf("") }

    // Step 2: Gateway
    var gwName by remember { mutableStateOf("Voice Gateway") }
    var gwUrl by remember { mutableStateOf("") }
    var gwUser by remember { mutableStateOf("") }
    var gwPass by remember { mutableStateOf("") }
    var gwAuthType by remember { mutableStateOf(AuthType.NONE) }
    var gwApiKey by remember { mutableStateOf("") }

    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(modifier = Modifier.fillMaxSize().safeDrawingPadding().padding(32.dp).imePadding(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
            when (step) {
                0 -> {
                    Icon(Icons.Default.AutoAwesome, null, tint = personaColor, modifier = Modifier.size(80.dp))
                    Spacer(modifier = Modifier.height(24.dp))
                    Text("Welcome to Voice Assistant", style = MaterialTheme.typography.headlineMedium, textAlign = TextAlign.Center)
                    Text("Your private, offline-first AI companion.", style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.7f), textAlign = TextAlign.Center)
                    Spacer(modifier = Modifier.height(48.dp))
                    Button(
                        onClick = { step = 1 }, 
                        enabled = isReady,
                        modifier = Modifier.fillMaxWidth().height(56.dp), 
                        shape = MaterialTheme.shapes.medium, 
                        colors = ButtonDefaults.buttonColors(containerColor = personaColor)
                    ) {
                        if (isReady) {
                            Text("Get Started", color = Color.Black, fontWeight = FontWeight.Bold)
                        } else {
                            CircularProgressIndicator(modifier = Modifier.size(24.dp), color = Color.Black, strokeWidth = 2.dp)
                        }
                    }
                }
                1 -> {
                    Text("Step 1: Ollama Server", style = MaterialTheme.typography.headlineSmall)
                    Text("The engine that runs your AI models.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.5f))
                    Spacer(modifier = Modifier.height(24.dp))

                    OutlinedTextField(value = ollamaName, onValueChange = { ollamaName = it }, label = { Text("Server Name") }, modifier = Modifier.fillMaxWidth())
                    Spacer(modifier = Modifier.height(12.dp))
                    OutlinedTextField(value = ollamaUrl, onValueChange = { ollamaUrl = it }, label = { Text("URL (e.g. http://192.168.1.10:11434)") }, modifier = Modifier.fillMaxWidth())

                    Spacer(modifier = Modifier.height(12.dp))
                    Text("Authentication", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f))
                    Spacer(modifier = Modifier.height(4.dp))
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        AuthType.values().forEach { type ->
                            FilterChip(
                                selected = ollamaAuthType == type,
                                onClick = { ollamaAuthType = type },
                                label = { Text(type.name.lowercase().replaceFirstChar { it.uppercase() }) }
                            )
                        }
                    }

                    if (ollamaAuthType == AuthType.BASIC) {
                        Spacer(modifier = Modifier.height(8.dp))
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedTextField(value = ollamaUser, onValueChange = { ollamaUser = it }, label = { Text("Username") }, modifier = Modifier.weight(1f))
                            OutlinedTextField(value = ollamaPass, onValueChange = { ollamaPass = it }, label = { Text("Password") }, modifier = Modifier.weight(1f), visualTransformation = PasswordVisualTransformation())
                        }
                    }

                    if (ollamaAuthType == AuthType.API_KEY) {
                        Spacer(modifier = Modifier.height(8.dp))
                        OutlinedTextField(value = ollamaApiKey, onValueChange = { ollamaApiKey = it }, label = { Text("API Key") }, modifier = Modifier.fillMaxWidth(), visualTransformation = PasswordVisualTransformation())
                    }

                    Spacer(modifier = Modifier.height(32.dp))
                    Button(
                        onClick = {
                            if (ollamaUrl.isNotBlank()) {
                                service?.addServerBase(ollamaName, ollamaUrl, ollamaUser.ifBlank { null }, ollamaPass.ifBlank { null }, ollamaAuthType, ollamaApiKey.ifBlank { null })
                            }
                            step = 2
                        },
                        enabled = isReady,
                        modifier = Modifier.fillMaxWidth().height(56.dp),
                        shape = MaterialTheme.shapes.medium,
                        colors = ButtonDefaults.buttonColors(containerColor = personaColor)
                    ) {
                        if (isReady) {
                            Text("Next", color = Color.Black)
                        } else {
                            CircularProgressIndicator(modifier = Modifier.size(24.dp), color = Color.Black, strokeWidth = 2.dp)
                        }
                    }
                    TextButton(onClick = { step = 2 }, modifier = Modifier.padding(top = 8.dp)) {
                        Text("Skip for now", color = personaColor)
                    }
                }
                2 -> {
                    Text("Step 2: Voice Gateway (Optional)", style = MaterialTheme.typography.headlineSmall)
                    Text("For advanced features like web search and TTS.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.5f))
                    Spacer(modifier = Modifier.height(24.dp))

                    OutlinedTextField(value = gwName, onValueChange = { gwName = it }, label = { Text("Gateway Name") }, modifier = Modifier.fillMaxWidth())
                    Spacer(modifier = Modifier.height(12.dp))
                    OutlinedTextField(value = gwUrl, onValueChange = { gwUrl = it }, label = { Text("URL (e.g. http://192.168.1.10:8880)") }, modifier = Modifier.fillMaxWidth())

                    Spacer(modifier = Modifier.height(12.dp))
                    Text("Authentication", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f))
                    Spacer(modifier = Modifier.height(4.dp))
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        AuthType.values().forEach { type ->
                            FilterChip(
                                selected = gwAuthType == type,
                                onClick = { gwAuthType = type },
                                label = { Text(type.name.lowercase().replaceFirstChar { it.uppercase() }) }
                            )
                        }
                    }

                    if (gwAuthType == AuthType.BASIC) {
                        Spacer(modifier = Modifier.height(8.dp))
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedTextField(value = gwUser, onValueChange = { gwUser = it }, label = { Text("Username") }, modifier = Modifier.weight(1f))
                            OutlinedTextField(value = gwPass, onValueChange = { gwPass = it }, label = { Text("Password") }, modifier = Modifier.weight(1f), visualTransformation = PasswordVisualTransformation())
                        }
                    }

                    if (gwAuthType == AuthType.API_KEY) {
                        Spacer(modifier = Modifier.height(8.dp))
                        OutlinedTextField(value = gwApiKey, onValueChange = { gwApiKey = it }, label = { Text("API Key") }, modifier = Modifier.fillMaxWidth(), visualTransformation = PasswordVisualTransformation())
                    }

                    Spacer(modifier = Modifier.height(32.dp))
                    Button(
                        onClick = {
                            if (gwUrl.isNotBlank()) {
                                service?.addServerBase(gwName, gwUrl, gwUser.ifBlank { null }, gwPass.ifBlank { null }, gwAuthType, gwApiKey.ifBlank { null })
                            }
                            onFinish()
                        },
                        enabled = isReady,
                        modifier = Modifier.fillMaxWidth().height(56.dp),
                        shape = MaterialTheme.shapes.medium,
                        colors = ButtonDefaults.buttonColors(containerColor = personaColor)
                    ) {
                        if (isReady) {
                            Text("Finish Setup", color = Color.Black)
                        } else {
                            CircularProgressIndicator(modifier = Modifier.size(24.dp), color = Color.Black, strokeWidth = 2.dp)
                        }
                    }
                    TextButton(onClick = { onFinish() }, modifier = Modifier.padding(top = 8.dp)) {
                        Text("Skip for now", color = personaColor)
                    }
                }
            }
        }
    }
}
