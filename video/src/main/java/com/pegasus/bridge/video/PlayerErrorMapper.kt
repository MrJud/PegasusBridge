package com.pegasus.bridge.video

import androidx.media3.common.PlaybackException

object PlayerErrorMapper {
    fun humanMessage(e: PlaybackException): String = when (e.errorCode) {
        PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
        PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT ->
            "Connessione di rete assente o timeout."
        PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS ->
            "Il server ha rifiutato la richiesta (URL scaduto?)."
        PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND ->
            "Video non trovato sul server."
        PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED,
        PlaybackException.ERROR_CODE_PARSING_MANIFEST_MALFORMED ->
            "Formato video non supportato."
        else -> "Errore riproduzione: ${e.errorCodeName}"
    }
}
