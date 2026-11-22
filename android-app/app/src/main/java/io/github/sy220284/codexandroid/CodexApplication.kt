package io.github.sy220284.codexandroid

import android.app.Application

/** One session per Android process: screen recreation never destroys the core subprocess. */
class CodexApplication : Application() {
    val session: CodexViewModel by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        CodexViewModel(this)
    }
}
