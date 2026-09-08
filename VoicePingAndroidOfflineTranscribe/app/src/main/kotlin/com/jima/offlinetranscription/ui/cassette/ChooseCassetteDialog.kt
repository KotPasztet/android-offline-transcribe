package com.voiceping.offlinetranscription.ui.cassette

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.voiceping.offlinetranscription.data.cassette.CassetteEntity
import com.voiceping.offlinetranscription.data.cassette.CassetteRepository

/**
 * Shown when a file was shared into the app from elsewhere (Share sheet).
 * Lets the user pick which cassette to import it into, or create a new one.
 *
 * [onCreateNewCassette] performs the actual DB insert (caller owns the
 * coroutine scope) and should return the new cassette's id.
 */
@Composable
fun ChooseCassetteDialog(
    repository: CassetteRepository,
    onCassetteChosen: (Long) -> Unit,
    onCreateNewCassette: (name: String, onCreated: (Long) -> Unit) -> Unit,
    onDismiss: () -> Unit
) {
    val cassettes by repository.observeCassettes().collectAsState(initial = emptyList())
    var newCassetteName by remember { mutableStateOf("") }
    var creatingNew by remember { mutableStateOf(cassettes.isEmpty()) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Dodaj do której kasety?") },
        text = {
            Column {
                if (!creatingNew) {
                    LazyColumn(modifier = Modifier.heightIn(max = 280.dp)) {
                        items(cassettes, key = { it.id }) { cassette ->
                            CassetteRow(cassette = cassette, onClick = { onCassetteChosen(cassette.id) })
                        }
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    TextButton(onClick = { creatingNew = true }) {
                        Text("+ Nowa kaseta")
                    }
                } else {
                    OutlinedTextField(
                        value = newCassetteName,
                        onValueChange = { newCassetteName = it },
                        label = { Text("Nazwa nowej kasety") },
                        singleLine = true
                    )
                    if (cassettes.isNotEmpty()) {
                        Spacer(modifier = Modifier.height(8.dp))
                        TextButton(onClick = { creatingNew = false }) {
                            Text("Wybierz istniejącą zamiast tego")
                        }
                    }
                }
            }
        },
        confirmButton = {
            if (creatingNew) {
                TextButton(onClick = {
                    val name = newCassetteName.ifBlank { "Nowa kaseta" }
                    onCreateNewCassette(name) { id -> onCassetteChosen(id) }
                }) { Text("Utwórz i dodaj") }
            } else {
                TextButton(onClick = onDismiss) { Text("Anuluj") }
            }
        },
        dismissButton = {
            if (creatingNew && cassettes.isNotEmpty()) {
                TextButton(onClick = onDismiss) { Text("Anuluj") }
            }
        }
    )
}

@Composable
private fun CassetteRow(cassette: CassetteEntity, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 10.dp)
    ) {
        Box(
            modifier = Modifier
                .size(14.dp)
                .clip(CircleShape)
                .background(Color(cassette.colorArgb))
        )
        Text(
            cassette.name,
            modifier = Modifier.padding(start = 10.dp),
            style = MaterialTheme.typography.bodyLarge
        )
    }
}
