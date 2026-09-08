package com.voiceping.offlinetranscription.util

import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * Transient (in-process only, not persisted) holder for an audio file the
 * user shared into this app from another app (Files, WhatsApp, a browser
 * download, etc. via Android's "Share" sheet). [MainActivity] sets this when
 * it receives an `ACTION_SEND` intent; [AppNavigation] shows a "pick a
 * cassette" dialog while it's non-null; the chosen [CassetteViewModel]
 * consumes (and clears) it once to start the import.
 */
object PendingShareHolder {
    var pendingAudioUri: Uri? by mutableStateOf(null)
}
