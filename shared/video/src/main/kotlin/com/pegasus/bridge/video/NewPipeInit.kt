package com.pegasus.bridge.video

import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.localization.Localization
import java.util.Locale

object NewPipeInit {
    @Volatile private var initialized = false

    fun ensure() {
        if (initialized) return
        synchronized(this) {
            if (initialized) return
            NewPipe.init(
                NewPipeOkHttpDownloader.get(),
                Localization(Locale.getDefault().language, Locale.getDefault().country)
            )
            initialized = true
        }
    }
}
