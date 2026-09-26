package fel.quill.android.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.ArrowBack
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.CloudOff
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.WifiTethering
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import android.net.Uri
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import fel.quill.android.BuildConfig
import fel.quill.android.ConnectionStatus
import fel.quill.android.QuillUiState
import fel.quill.android.QuillViewModel
import fel.quill.android.model.AppTab
import fel.quill.android.model.DiscoveredBridge

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun QuillApp(sharedText: String? = null, pairingUri: Uri? = null, viewModel: QuillViewModel = viewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }

    LaunchedEffect(sharedText) {
        if (!sharedText.isNullOrBlank()) viewModel.acceptSharedText(sharedText)
    }
    LaunchedEffect(state.message) {
        state.message?.let {
            if (state.connection == ConnectionStatus.CONNECTED || state.connection == ConnectionStatus.OFFLINE) {
                snackbar.showSnackbar(it)
                viewModel.clearMessage()
            }
        }
    }

    when (state.connection) {
        ConnectionStatus.NEEDS_PAIRING,
        ConnectionStatus.CONNECTING,
        ConnectionStatus.ERROR,
        -> PairingScreen(state, viewModel, pairingUri)
        ConnectionStatus.CONNECTED,
        ConnectionStatus.OFFLINE,
        -> {
            if (state.selectedNote != null) {
                NoteEditorScreen(state, viewModel)
            } else {
                MainShell(state, viewModel, snackbar)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MainShell(state: QuillUiState, viewModel: QuillViewModel, snackbar: SnackbarHostState) {
    var settingsOpen by remember { mutableStateOf(false) }
    val syncLabel = when {
        state.conflicts > 0 -> "${state.conflicts} conflicts"
        state.connection == ConnectionStatus.OFFLINE -> "offline"
        state.pendingChanges > 0 -> "${state.pendingChanges} pending"
        else -> "synced"
    }
    Scaffold(
        modifier = Modifier.fillMaxSize(),
        contentWindowInsets = WindowInsets.safeDrawing,
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("Quill", fontWeight = FontWeight.Bold)
                        Text(
                            "${state.library.counts.open} open · ${state.library.counts.notes} notes · $syncLabel",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
                actions = {
                    IconButton(onClick = viewModel::refresh) {
                        Icon(Icons.Outlined.Refresh, contentDescription = "Sync")
                    }
                    IconButton(onClick = { settingsOpen = true }) {
                        Icon(Icons.Outlined.Settings, contentDescription = "Settings")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            )
        },
        bottomBar = {
            NavigationBar(modifier = Modifier.windowInsetsPadding(WindowInsets.navigationBars)) {
                AppTab.entries.forEach { tab ->
                    NavigationBarItem(
                        selected = state.tab == tab,
                        onClick = { viewModel.selectTab(tab) },
                        icon = {
                            Icon(
                                imageVector = when (tab) {
                                    AppTab.TODOS -> Icons.Outlined.CheckCircle
                                    AppTab.NOTES -> Icons.Outlined.Description
                                    AppTab.CALENDAR -> Icons.Outlined.CalendarMonth
                                    AppTab.ASK -> Icons.Outlined.AutoAwesome
                                },
                                contentDescription = tab.label,
                            )
                        },
                        label = { Text(tab.label) },
                    )
                }
            }
        },
        floatingActionButton = {
            if (state.tab == AppTab.NOTES) {
                FloatingActionButton(onClick = viewModel::newNote) {
                    Icon(Icons.Outlined.Add, contentDescription = "New note")
                }
            }
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        val tabStateHolder = rememberSaveableStateHolder()
        Box(modifier = Modifier.padding(padding).fillMaxSize()) {
            tabStateHolder.SaveableStateProvider(state.tab.name) {
                when (state.tab) {
                    AppTab.TODOS -> TodosScreen(state, viewModel)
                    AppTab.NOTES -> NotesScreen(state, viewModel)
                    AppTab.CALENDAR -> CalendarScreen(state, viewModel)
                    AppTab.ASK -> AskScreen(state, viewModel)
                }
            }
            if (state.busy) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth().align(Alignment.TopCenter))
            }
        }
    }

    if (settingsOpen) {
        SettingsDialog(state, viewModel, onDismiss = { settingsOpen = false })
    }
}

@Composable
private fun PairingScreen(state: QuillUiState, viewModel: QuillViewModel, pairingUri: Uri?) {
    val pairingUsesTls = pairingUri?.getQueryParameter("tls")?.toBooleanStrictOrNull() ?: true
    var host by remember(pairingUri) { mutableStateOf(pairingUri?.getQueryParameter("host") ?: state.discovered.firstOrNull()?.host.orEmpty()) }
    var port by remember(pairingUri) { mutableStateOf(pairingUri?.getQueryParameter("port") ?: state.discovered.firstOrNull()?.port?.toString() ?: "8765") }
    var code by remember(pairingUri) { mutableStateOf(pairingUri?.getQueryParameter("code").orEmpty()) }
    var useTls by remember(pairingUri) { mutableStateOf(if (BuildConfig.DEBUG) pairingUsesTls else true) }
    var fingerprint by remember(pairingUri) { mutableStateOf(pairingUri?.getQueryParameter("fingerprint") ?: state.discovered.firstOrNull()?.fingerprint.orEmpty()) }

    LaunchedEffect(state.discovered) {
        val first = state.discovered.firstOrNull()
        if (first != null) {
            host = first.host
            port = first.port.toString()
            useTls = if (BuildConfig.DEBUG) first.useTls else true
            fingerprint = first.fingerprint
        }
    }

    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .windowInsetsPadding(WindowInsets.safeDrawing)
                .imePadding()
                .padding(horizontal = 24.dp, vertical = 28.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Surface(
                    modifier = Modifier.size(54.dp),
                    shape = RoundedCornerShape(16.dp),
                    color = MaterialTheme.colorScheme.primaryContainer,
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(Icons.Outlined.Description, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(30.dp))
                    }
                }
                Spacer(Modifier.width(16.dp))
                Column {
                    Text("Pair with Quill", style = MaterialTheme.typography.headlineMedium)
                    Text("Keep your Markdown notes with you.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }

            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.55f))) {
                Row(modifier = Modifier.padding(16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.Top) {
                    Icon(Icons.Outlined.WifiTethering, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("Start the bridge on your PC", style = MaterialTheme.typography.titleMedium)
                        Text("Run quill-bridge from the repository. It prints a short code and listens on your local network.", style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }

            OutlinedButton(onClick = viewModel::discover, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Outlined.Refresh, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("Find bridge on this network")
            }

            if (state.discovered.isNotEmpty()) {
                Text("Discovered bridges", style = MaterialTheme.typography.titleMedium)
                state.discovered.forEach { bridge ->
                    DiscoveredBridgeRow(bridge, onClick = {
                        host = bridge.host
                        port = bridge.port.toString()
                        useTls = if (BuildConfig.DEBUG) bridge.useTls else true
                        fingerprint = bridge.fingerprint
                    })
                }
            }

            OutlinedTextField(
                value = host,
                onValueChange = { host = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("PC address") },
                singleLine = true,
            )
            OutlinedTextField(
                value = port,
                onValueChange = { port = it.filter(Char::isDigit).take(5) },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Port") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                singleLine = true,
            )
            OutlinedTextField(
                value = code,
                onValueChange = { code = it.uppercase().take(9) },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Pairing code") },
                placeholder = { Text("ABCD-EFGH") },
                singleLine = true,
            )
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Encrypt the connection", style = MaterialTheme.typography.bodyLarge)
                    Text("The bridge uses a pinned certificate.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Switch(checked = useTls, onCheckedChange = { useTls = it }, enabled = BuildConfig.DEBUG)
            }
            if (useTls) {
                OutlinedTextField(
                    value = fingerprint,
                    onValueChange = { fingerprint = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Certificate fingerprint (optional; fetched if blank)") },
                    supportingText = { Text("Use the fingerprint printed by the bridge or supplied by its pairing URI.") },
                    singleLine = true,
                )
            }
            Button(
                onClick = { viewModel.pair(host, port.toIntOrNull() ?: 0, code, useTls, fingerprint) },
                modifier = Modifier.fillMaxWidth(),
                enabled = state.connection != ConnectionStatus.CONNECTING,
            ) {
                if (state.connection == ConnectionStatus.CONNECTING) {
                    CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                } else {
                    Text("Pair device")
                }
            }
            if (state.connection == ConnectionStatus.ERROR) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Outlined.CloudOff, contentDescription = null, tint = MaterialTheme.colorScheme.error)
                    Text(state.message ?: "Could not connect", color = MaterialTheme.colorScheme.error)
                }
            }
            Text(
                "The Android app stores its bridge token and AI keys in Android Keystore-backed storage.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun DiscoveredBridgeRow(bridge: DiscoveredBridge, onClick: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f)),
    ) {
        Row(modifier = Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Outlined.WifiTethering, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text("${bridge.host}:${bridge.port}", style = MaterialTheme.typography.titleMedium)
                Text(if (bridge.useTls) "Encrypted bridge" else "Unencrypted development mode", style = MaterialTheme.typography.bodySmall)
            }
            Text("Use", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NoteEditorScreen(state: QuillUiState, viewModel: QuillViewModel) {
    val note = state.selectedNote ?: return
    var aiMenu by remember { mutableStateOf(false) }
    var rewriteDialog by remember { mutableStateOf(false) }
    var rewriteInstruction by remember { mutableStateOf("") }
    var deleteDialog by remember { mutableStateOf(false) }
    var preview by remember { mutableStateOf(false) }
    BackHandler(onBack = viewModel::closeNote)
    Scaffold(
        modifier = Modifier.fillMaxSize(),
        contentWindowInsets = WindowInsets.safeDrawing,
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = viewModel::closeNote) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "Back")
                    }
                },
                title = { Text(note.title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                actions = {
                    IconButton(onClick = { preview = !preview }) {
                        Icon(if (preview) Icons.Outlined.Edit else Icons.Outlined.Visibility, contentDescription = if (preview) "Edit note" else "Preview note")
                    }
                    IconButton(onClick = { aiMenu = true }, enabled = !state.busy) {
                        Icon(Icons.Outlined.AutoAwesome, contentDescription = "AI actions")
                    }
                    DropdownMenu(expanded = aiMenu, onDismissRequest = { aiMenu = false }) {
                        DropdownMenuItem(text = { Text("Summarize") }, onClick = { aiMenu = false; viewModel.summarizeSelected() }, enabled = !state.busy)
                        DropdownMenuItem(text = { Text("Extract todos") }, onClick = { aiMenu = false; viewModel.extractSelectedTodos() }, enabled = !state.busy)
                        DropdownMenuItem(text = { Text("AI edit…") }, onClick = { aiMenu = false; rewriteDialog = true }, enabled = !state.busy)
                    }
                    IconButton(onClick = viewModel::copySelectedNote, enabled = !state.busy) {
                        Icon(Icons.Outlined.ContentCopy, contentDescription = "Copy note")
                    }
                    TextButton(onClick = viewModel::saveNote, enabled = !state.busy) { Text("Save") }
                    IconButton(onClick = { deleteDialog = true }, enabled = !state.busy) {
                        Icon(Icons.Outlined.Delete, contentDescription = "Delete note")
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .padding(horizontal = 18.dp, vertical = 10.dp)
                .background(MaterialTheme.colorScheme.background),
        ) {
            OutlinedTextField(
                value = state.noteTitleDraft,
                onValueChange = viewModel::setNoteTitle,
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Title") },
                singleLine = true,
            )
            Spacer(Modifier.height(8.dp))
            if (state.selectedNote?.path != null && state.selectedNote.path in state.conflictPaths) {
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.errorContainer,
                ) {
                    Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(
                            "This note also changed on the PC. Your version is shown here; pick which one to keep.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onErrorContainer,
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            TextButton(onClick = viewModel::keepMine, enabled = !state.busy) { Text("Keep mine") }
                            TextButton(onClick = viewModel::keepPc, enabled = !state.busy) { Text("Keep PC") }
                        }
                    }
                }
                Spacer(Modifier.height(8.dp))
            }
            Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                if (preview) {
                    if (state.editorText.isBlank()) {
                        Text("Nothing to preview", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    } else {
                        MarkdownPreview(state.editorText, onWikiLink = viewModel::openWikiLink)
                    }
                } else {
                    androidx.compose.foundation.text.BasicTextField(
                        value = state.editorText,
                        onValueChange = viewModel::setEditorText,
                        modifier = Modifier.fillMaxSize().padding(horizontal = 4.dp, vertical = 4.dp),
                        textStyle = MaterialTheme.typography.bodyLarge.copy(color = MaterialTheme.colorScheme.onBackground),
                        decorationBox = { inner ->
                            if (state.editorText.isEmpty()) {
                                Text("Write in Markdown…", color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            inner()
                        },
                    )
                }
            }
        }
    }
    if (deleteDialog) {
        AlertDialog(
            onDismissRequest = { deleteDialog = false },
            title = { Text("Delete note?") },
            text = { Text("This removes the Markdown file from the PC.") },
            confirmButton = {
                TextButton(onClick = {
                    deleteDialog = false
                    viewModel.deleteNote()
                }) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { deleteDialog = false }) { Text("Cancel") } },
        )
    }
    if (rewriteDialog) {
        AlertDialog(
            onDismissRequest = { rewriteDialog = false },
            title = { Text("AI edit") },
            text = {
                OutlinedTextField(
                    value = rewriteInstruction,
                    onValueChange = { rewriteInstruction = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Instruction") },
                    placeholder = { Text("Improve this note") },
                    minLines = 3,
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    rewriteDialog = false
                    viewModel.rewriteSelected(rewriteInstruction)
                    rewriteInstruction = ""
                }) { Text("Rewrite") }
            },
            dismissButton = { TextButton(onClick = { rewriteDialog = false }) { Text("Cancel") } },
        )
    }
}
