package io.github.openfinalshell.android

import android.os.Bundle
import androidx.activity.enableEdgeToEdge
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.lifecycle.ViewModelProvider

class MainActivity : AppCompatActivity() {
    private val viewModel: MainViewModel by lazy {
        val app = application as OpenFinalShellApplication
        ViewModelProvider(
            app.workbenchStore,
            ViewModelProvider.AndroidViewModelFactory.getInstance(app)
        )[MainViewModel::class.java]
    }
    private val settingsViewModel by viewModels<SettingsViewModel>()
    private val lanSyncViewModel by viewModels<LanSyncViewModel>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { OpenFinalShellApp(viewModel, settingsViewModel, lanSyncViewModel) }
    }

    override fun onStop() {
        // Re-assert the foreground service before the process is backgrounded.
        viewModel.ensureBackgroundKeepAlive()
        super.onStop()
    }

    override fun onResume() {
        super.onResume()
        // PackageInstaller reports completion while the activity is not visible.
        settingsViewModel.refreshInstallStatus()
        // Also captures a language chosen from Android 13's system app-language page.
        settingsViewModel.setLanguageFromPlatform(
            AndroidLocales.settingFor(AppCompatDelegate.getApplicationLocales())
        )
    }
}
