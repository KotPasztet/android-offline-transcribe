package com.voiceping.offlinetranscription.ui.cassette

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.voiceping.offlinetranscription.data.cassette.CassetteEntity
import com.voiceping.offlinetranscription.data.cassette.CassetteRepository
import com.voiceping.offlinetranscription.util.CassetteAudioStorage
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** Cassette shelf label colors (kept few and cheerful, like real tape colors). */
val CassetteColors = listOf(
    0xFFE57373.toInt(), // red
    0xFFFFB74D.toInt(), // orange
    0xFFFFD54F.toInt(), // yellow
    0xFF81C784.toInt(), // green
    0xFF4FC3F7.toInt(), // blue
    0xFF9575CD.toInt(), // purple
    0xFFF06292.toInt()  // pink
)

class HomeShelfViewModel(
    private val context: Context,
    private val repository: CassetteRepository
) : ViewModel() {

    val cassettes: StateFlow<List<CassetteEntity>> = repository.observeCassettes()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun createCassette(name: String, onCreated: (Long) -> Unit) {
        viewModelScope.launch {
            val color = CassetteColors[(cassettes.value.size) % CassetteColors.size]
            val id = repository.createCassette(name.ifBlank { "Nowa kaseta" }, color)
            onCreated(id)
        }
    }

    fun deleteCassette(cassette: CassetteEntity) {
        viewModelScope.launch {
            repository.deleteCassette(cassette)
            CassetteAudioStorage.deleteCassetteFiles(context, cassette.id)
        }
    }
}
