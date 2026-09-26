package fel.quill.android.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import fel.quill.android.QuillUiState
import fel.quill.android.QuillViewModel
import fel.quill.android.model.AiConfig

private val providerLabels = listOf(
    "opencode-go" to "OpenCode Go",
    "openai" to "OpenAI",
    "anthropic" to "Anthropic",
    "google" to "Google Gemini",
    "openrouter" to "OpenRouter",
    "groq" to "Groq",
    "ollama" to "Ollama",
    "openai-compatible" to "OpenAI-compatible",
    "off" to "Off",
)

@Composable
fun SettingsDialog(state: QuillUiState, viewModel: QuillViewModel, onDismiss: () -> Unit) {
    var provider by remember(state.aiConfig) { mutableStateOf(state.aiConfig.provider) }
    var model by remember(state.aiConfig) { mutableStateOf(state.aiConfig.model) }
    var baseUrl by remember(state.aiConfig) { mutableStateOf(state.aiConfig.baseUrl) }
    var apiKey by remember(state.aiConfig) { mutableStateOf(state.aiConfig.apiKey) }
    var maxTokens by remember(state.aiConfig) { mutableStateOf(state.aiConfig.maxTokens.toString()) }
    var aiCapture by remember(state.aiConfig) { mutableStateOf(state.aiConfig.aiCapture) }
    var encryptCache by remember(state.encryptCache) { mutableStateOf(state.encryptCache) }
    var providerMenu by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Quill settings") },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text("AI provider", style = androidx.compose.material3.MaterialTheme.typography.titleSmall)
                androidx.compose.material3.OutlinedButton(
                    onClick = { providerMenu = true },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(providerLabels.firstOrNull { it.first == provider }?.second ?: provider)
                }
                DropdownMenu(expanded = providerMenu, onDismissRequest = { providerMenu = false }) {
                    providerLabels.forEach { option ->
                        DropdownMenuItem(
                            text = { Text(option.second) },
                            onClick = {
                                provider = option.first
                                providerMenu = false
                            },
                        )
                    }
                }
                OutlinedTextField(value = model, onValueChange = { model = it }, modifier = Modifier.fillMaxWidth(), label = { Text("Model") }, singleLine = true)
                OutlinedTextField(value = baseUrl, onValueChange = { baseUrl = it }, modifier = Modifier.fillMaxWidth(), label = { Text("API base URL (optional)") }, singleLine = true)
                OutlinedTextField(value = apiKey, onValueChange = { apiKey = it }, modifier = Modifier.fillMaxWidth(), label = { Text("API key") }, singleLine = true, visualTransformation = PasswordVisualTransformation())
                OutlinedTextField(value = maxTokens, onValueChange = { maxTokens = it.filter(Char::isDigit).take(5) }, modifier = Modifier.fillMaxWidth(), label = { Text("Max reply tokens (0 = provider default)") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), singleLine = true)
                Row(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("AI capture")
                        Text("Structure new captures into todos and notes.", style = androidx.compose.material3.MaterialTheme.typography.bodySmall)
                    }
                    Switch(checked = aiCapture, onCheckedChange = { aiCapture = it })
                }
                Text("Keys are encrypted with a key held by Android Keystore.", style = androidx.compose.material3.MaterialTheme.typography.labelSmall)
                Text("${state.pendingChanges} pending change(s) · ${state.conflicts} conflict(s)", style = androidx.compose.material3.MaterialTheme.typography.labelSmall)
                TextButton(onClick = viewModel::createPairingCode, enabled = !state.busy) {
                    Text("Create code for another device")
                }
                state.pairingCode?.let { code ->
                    Text("Pairing code: $code", style = androidx.compose.material3.MaterialTheme.typography.titleMedium)
                }
                TextButton(onClick = {
                    viewModel.forgetBridge()
                    onDismiss()
                }) { Text("Forget paired PC") }
                Text("Local cache", style = androidx.compose.material3.MaterialTheme.typography.titleSmall)
                Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "Encrypt the notes kept on this phone. The Markdown files on your PC stay plain.",
                        modifier = Modifier.weight(1f),
                        style = androidx.compose.material3.MaterialTheme.typography.bodySmall,
                    )
                    Switch(checked = encryptCache, onCheckedChange = { encryptCache = it })
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                viewModel.saveAiConfig(
                    AiConfig(
                        provider = provider,
                        model = model.trim(),
                        baseUrl = baseUrl.trim(),
                        apiKey = apiKey.trim(),
                        maxTokens = maxTokens.toIntOrNull() ?: 0,
                        aiCapture = aiCapture,
                    ),
                )
                viewModel.setCacheEncryption(encryptCache)
                onDismiss()
            }) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
