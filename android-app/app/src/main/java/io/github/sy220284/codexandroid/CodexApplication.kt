package io.github.sy220284.codexandroid

import android.app.Application

/** One session per Android process: screen recreation never destroys the core subprocess. */
class CodexApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        // Restore verified user-selected data before any app-server or UI state is opened.
        LocalDataArchive.restoreIfPending(this)
    }

    val session: CodexViewModel by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        CodexViewModel(this)
    }
}
