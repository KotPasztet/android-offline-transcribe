package com.voiceping.offlinetranscription.ui.cassette

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.UploadFile
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import android.widget.Toast
import com.voiceping.offlinetranscription.data.cassette.MemoEntity
import com.voiceping.offlinetranscription.service.SessionState
import com.voiceping.offlinetranscription.util.FormatUtils

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CassetteScreen(
    viewModel: CassetteViewModel,
    onBack: () -> Unit,
    onOpenSettings: () -> Unit
) {
    val cassette by viewModel.cassette.collectAsState()
    val memos by viewModel.memos.collectAsState()
    val isRecording by viewModel.isRecording.collectAsState()
    val sessionState by viewModel.sessionState.collectAsState()
    val confirmedText by viewModel.confirmedText.collectAsState()
    val hypothesisText by viewModel.hypothesisText.collectAsState()
    val selectedModel by viewModel.selectedModel.collectAsState()
    val fileProgress by viewModel.fileTranscriptionProgress.collectAsState()
    val isDecoding by viewModel.isImportDecoding.collectAsState()
    val lastError by viewModel.lastError.collectAsState()

    val filePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri?.let { viewModel.importFileAsMemo(it) }
    }

    val labelColor = cassette?.let { Color(it.colorArgb) } ?: MaterialTheme.colorScheme.primary
    val isImporting = (isDecoding || (fileProgress > 0f && fileProgress < 1f)) && !isRecording
    val isPaused = sessionState == SessionState.Paused

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(cassette?.name ?: "Kaseta") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Filled.ArrowBack, contentDescription = "Wróć do półki")
                    }
                },
                actions = {
                    IconButton(
                        onClick = { viewModel.exportAndShareCassette() },
                        enabled = memos.isNotEmpty()
                    ) {
                        Icon(Icons.Filled.Share, contentDescription = "Eksportuj całą kasetę")
                    }
                    IconButton(onClick = onOpenSettings) {
                        Icon(Icons.Filled.Settings, contentDescription = "Model: ${selectedModel.displayName}")
                    }
                }
            )
        },
        floatingActionButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                if (!isRecording && !isPaused) {
                    FloatingActionButton(
                        onClick = { filePickerLauncher.launch("audio/*") },
                    ) {
                        Icon(Icons.Filled.UploadFile, contentDescription = "Dodaj plik audio")
                    }
                }
                if (isRecording || isPaused) {
                    // Pause/resume without ending the memo-in-progress.
                    FloatingActionButton(
                        onClick = { if (isPaused) viewModel.resumeRecording() else viewModel.pauseRecording() }
                    ) {
                        Icon(
                            if (isPaused) Icons.Filled.PlayArrow else Icons.Filled.Pause,
                            contentDescription = if (isPaused) "Wznów nagrywanie" else "Wstrzymaj nagrywanie"
                        )
                    }
                }
                FloatingActionButton(
                    onClick = {
                        when {
                            isRecording || isPaused -> viewModel.stopRecordingAndSaveMemo()
                            else -> viewModel.startRecording()
                        }
                    },
                    containerColor = if (isRecording || isPaused) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
                ) {
                    Icon(
                        if (isRecording || isPaused) Icons.Filled.Stop else Icons.Filled.Mic,
                        contentDescription = if (isRecording || isPaused) "Zakończ i zapisz nagranie" else "Nagraj nowe memo"
                    )
                }
            }
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            // "Tape" header strip with the cassette's label color.
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(6.dp)
                    .background(labelColor)
            )

            if (isRecording || isPaused || isImporting) {
                LiveTranscriptCard(
                    isRecording = isRecording,
                    isPaused = isPaused,
                    isImporting = isImporting,
                    isDecoding = isDecoding,
                    progress = fileProgress,
                    confirmedText = confirmedText,
                    hypothesisText = hypothesisText,
                    modelName = selectedModel.displayName
                )
            }

            if (memos.isEmpty() && !isRecording && !isPaused && !isImporting) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(24.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        "Ta kaseta jest jeszcze pusta.\nNagraj coś albo dodaj plik audio (w tym m4a).",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(memos, key = { it.id }) { memo ->
                        MemoRow(
                            memo = memo,
                            labelColor = labelColor,
                            onPlay = { viewModel.playMemo(memo) },
                            onDelete = { viewModel.deleteMemo(memo) }
                        )
                    }
                }
            }
        }
    }

    // Transcription/import failures used to be swallowed silently (set on a
    // StateFlow nobody displayed) — the screen just looked like "nothing
    // happened". Now surfaced as a dismissible dialog with the real reason.
    lastError?.let { error ->
        AlertDialog(
            onDismissRequest = { viewModel.dismissError() },
            title = { Text("Błąd") },
            text = { Text(error.message) },
            confirmButton = {
                TextButton(onClick = { viewModel.dismissError() }) { Text("OK") }
            }
        )
    }
}

@Composable
private fun LiveTranscriptCard(
    isRecording: Boolean,
    isPaused: Boolean,
    isImporting: Boolean,
    isDecoding: Boolean,
    progress: Float,
    confirmedText: String,
    hypothesisText: String,
    modelName: String
) {
    val fullText = remember(confirmedText, hypothesisText) {
        buildString {
            append(confirmedText)
            if (hypothesisText.isNotBlank()) {
                if (isNotEmpty()) append(" ")
                append(hypothesisText)
            }
        }.ifBlank { "..." }
    }
    val scrollState = rememberScrollState()

    // Keep the latest tokens in view as they arrive, but the user can still
    // scroll up manually to re-read earlier parts — scrollState isn't reset,
    // just nudged to the bottom whenever new text comes in.
    LaunchedEffect(fullText) {
        scrollState.scrollTo(scrollState.maxValue)
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(12.dp)
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    when {
                        isPaused -> "Wstrzymano ($modelName)"
                        isRecording -> "Nagrywanie... ($modelName)"
                        isDecoding -> "Dekodowanie pliku... (to może chwilę potrwać, appka działa dalej)"
                        else -> "Transkrypcja... (${(progress * 100).toInt()}%)"
                    },
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f)
                )
                val clipboardManager = LocalClipboardManager.current
                val context = LocalContext.current
                IconButton(onClick = {
                    clipboardManager.setText(AnnotatedString(fullText))
                    Toast.makeText(context, "Skopiowano transkrypcję", Toast.LENGTH_SHORT).show()
                }) {
                    Icon(Icons.Filled.ContentCopy, contentDescription = "Kopiuj dotychczasową transkrypcję")
                }
            }
            if (isImporting) {
                Spacer(modifier = Modifier.height(6.dp))
                if (isDecoding) {
                    // No percentage available yet during decode — indeterminate
                    // spinner communicates "still working" without a fake number.
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                } else {
                    LinearProgressIndicator(
                        progress = { progress },
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = fullText,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 220.dp)
                    .verticalScroll(scrollState)
            )
        }
    }
}

@Composable
private fun MemoRow(
    memo: MemoEntity,
    labelColor: Color,
    onPlay: () -> Unit,
    onDelete: () -> Unit
) {
    var expanded by remember(memo.id) { mutableStateOf(false) }
    val clipboardManager = LocalClipboardManager.current
    val context = LocalContext.current

    Card(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Small colored "reel" chip, ties visually back to the cassette label.
            Box(
                modifier = Modifier
                    .size(10.dp)
                    .clip(CircleShape)
                    .background(labelColor)
            )
            Spacer(modifier = Modifier.width(10.dp))
            Column(
                modifier = Modifier
                    .weight(1f)
                    // Tap the transcript to expand/collapse it — this is how you
                    // read a memo's full text instead of just the truncated preview.
                    .clickable { expanded = !expanded }
            ) {
                Text(
                    memo.transcriptText.ifBlank { "(brak transkrypcji)" },
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = if (expanded) Int.MAX_VALUE else 3
                )
                Spacer(modifier = Modifier.height(2.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "${FormatUtils.formatDuration(memo.durationMs / 1000.0)} · ${memo.modelDisplayName}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Icon(
                        if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                        contentDescription = if (expanded) "Zwiń transkrypcję" else "Rozwiń transkrypcję",
                        modifier = Modifier.size(16.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            IconButton(onClick = {
                clipboardManager.setText(AnnotatedString(memo.transcriptText))
                Toast.makeText(context, "Skopiowano transkrypcję", Toast.LENGTH_SHORT).show()
            }) {
                Icon(Icons.Filled.ContentCopy, contentDescription = "Kopiuj transkrypcję")
            }
            IconButton(onClick = onPlay) {
                Icon(Icons.Filled.PlayArrow, contentDescription = "Odtwórz")
            }
            IconButton(onClick = onDelete) {
                Icon(Icons.Filled.Delete, contentDescription = "Usuń")
            }
        }
    }
}
