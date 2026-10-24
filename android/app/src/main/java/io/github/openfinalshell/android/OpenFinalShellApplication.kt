package io.github.openfinalshell.android

import android.app.Application
import androidx.lifecycle.ViewModelStore
import io.github.openfinalshell.android.core.ssh.AndroidSshdInitializer

class OpenFinalShellApplication : Application() {
    /**
     * Sessions outlive the activity. Backgrounding, rotation, and swiping the task away must not
     * clear the ViewModel that owns the SSH clients. Force-stop still kills the process.
     */
    val workbenchStore: ViewModelStore = ViewModelStore()

    override fun onCreate() {
        super.onCreate()
        // Configure SSHD before any activity or session can initialize ClientBuilder.
        AndroidSshdInitializer.configure(filesDir.toPath())
    }
}
