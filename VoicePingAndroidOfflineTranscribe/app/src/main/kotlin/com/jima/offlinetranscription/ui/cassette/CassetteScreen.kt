package com.voiceping.offlinetranscription.ui.cassette

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.UploadFile
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.voiceping.offlinetranscription.data.cassette.MemoEntity
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
    val confirmedText by viewModel.confirmedText.collectAsState()
    val hypothesisText by viewModel.hypothesisText.collectAsState()
    val selectedModel by viewModel.selectedModel.collectAsState()
    val fileProgress by viewModel.fileTranscriptionProgress.collectAsState()

    val filePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri?.let { viewModel.importFileAsMemo(it) }
    }

    val labelColor = cassette?.let { Color(it.colorArgb) } ?: MaterialTheme.colorScheme.primary
    val isImporting = fileProgress > 0f && fileProgress < 1f && !isRecording

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
                    IconButton(onClick = onOpenSettings) {
                        Icon(Icons.Filled.Settings, contentDescription = "Model: ${selectedModel.displayName}")
                    }
                }
            )
        },
        floatingActionButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                FloatingActionButton(
                    onClick = { filePickerLauncher.launch("audio/*") },
                ) {
                    Icon(Icons.Filled.UploadFile, contentDescription = "Dodaj plik audio")
                }
                FloatingActionButton(
                    onClick = {
                        if (isRecording) viewModel.stopRecordingAndSaveMemo() else viewModel.startRecording()
                    },
                    containerColor = if (isRecording) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
                ) {
                    Icon(
                        if (isRecording) Icons.Filled.Stop else Icons.Filled.Mic,
                        contentDescription = if (isRecording) "Zatrzymaj nagrywanie" else "Nagraj nowe memo"
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

            if (isRecording || isImporting) {
                LiveTranscriptCard(
                    isRecording = isRecording,
                    isImporting = isImporting,
                    progress = fileProgress,
                    confirmedText = confirmedText,
                    hypothesisText = hypothesisText,
                    modelName = selectedModel.displayName
                )
            }

            if (memos.isEmpty() && !isRecording && !isImporting) {
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
}

@Composable
private fun LiveTranscriptCard(
    isRecording: Boolean,
    isImporting: Boolean,
    progress: Float,
    confirmedText: String,
    hypothesisText: String,
    modelName: String
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(12.dp)
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(
                when {
                    isRecording -> "Nagrywanie... ($modelName)"
                    else -> "Importowanie pliku... (${(progress * 100).toInt()}%)"
                },
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Bold
            )
            if (isImporting) {
                Spacer(modifier = Modifier.height(6.dp))
                LinearProgressIndicator(
                    progress = { progress },
                    modifier = Modifier.fillMaxWidth()
                )
            }
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = buildString {
                    append(confirmedText)
                    if (hypothesisText.isNotBlank()) {
                        if (isNotEmpty()) append(" ")
                        append(hypothesisText)
                    }
                }.ifBlank { "..." },
                style = MaterialTheme.typography.bodyMedium
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
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    memo.transcriptText.ifBlank { "(brak transkrypcji)" },
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 3
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    "${FormatUtils.formatDuration(memo.durationMs / 1000.0)} · ${memo.modelDisplayName}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
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
