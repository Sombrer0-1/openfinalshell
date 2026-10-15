package io.github.openfinalshell.android

import android.app.Application
import android.content.Intent
import androidx.appcompat.app.AppCompatDelegate
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.github.openfinalshell.android.storage.AndroidSettings
import io.github.openfinalshell.android.storage.AppDatabase
import io.github.openfinalshell.android.storage.SettingsRepository
import io.github.openfinalshell.android.storage.SettingsWriteQueue
import io.github.openfinalshell.android.update.AndroidUpdateManager
import io.github.openfinalshell.android.update.UpdateState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

data class SettingsUiState(
    val settings: AndroidSettings = AndroidSettings(),
    val loaded: Boolean = false,
    val saving: Boolean = false,
    val update: UpdateState = UpdateState.Idle,
    val message: String? = null
)

/** Owns Android settings and the in-app updater independently of the SSH session state. */
class SettingsViewModel(application: Application) : AndroidViewModel(application) {
    private val database = AppDatabase.create(application)
    private val repository = SettingsRepository(database.documents())
    private val updateManager = AndroidUpdateManager(application)
    private val queue = SettingsWriteQueue()
    private val saveMutex = Mutex()
    private var persistGeneration = 0
    private var loadJob: Job

    private val mutableState = MutableStateFlow(
        SettingsUiState(update = updateManager.state.value)
    )
    val state: StateFlow<SettingsUiState> = mutableState.asStateFlow()

    init {
        viewModelScope.launch {
            updateManager.state.collect { update -> mutableState.update { it.copy(update = update) } }
        }
        // Room and the network check run in background coroutines; first frame remains responsive.
        loadJob = viewModelScope.launch {
            val storedSettings = runCatching { withContext(Dispatchers.IO) { repository.load() } }
                .onFailure { error ->
                    mutableState.update { it.copy(message = error.message ?: application.getString(R.string.settings_unavailable)) }
                }
                .getOrElse { AndroidSettings() }
            // Android 13 may receive a per-app language change while the app is not running.
            // A concrete platform locale takes precedence over our older local document.
            val platformLanguage = AndroidLocales.settingFor(AppCompatDelegate.getApplicationLocales())
            val adjusted = if (platformLanguage != AndroidLocales.SYSTEM && platformLanguage != storedSettings.language) {
                storedSettings.copy(language = platformLanguage)
            } else {
                storedSettings
            }
            // Replay edits that happened before this load onto the stored document. Do not write
            // the default object, and do not replace the screen with a snapshot captured before
            // a later keystroke.
            queue.confirm(adjusted, dirty = adjusted != storedSettings)
            mutableState.update { it.copy(settings = queue.current(), loaded = true) }
            val settings = queue.current()
            AndroidLocales.apply(settings.language)
            if (queue.needsSave()) schedulePersist()
            if (settings.autoCheckUpdates) checkForUpdates()
        }
    }

    fun update(transform: (AndroidSettings) -> AndroidSettings) {
        updateAndPersist(transform)
    }

    private fun updateAndPersist(transform: (AndroidSettings) -> AndroidSettings) {
        queue.edit(transform)
        mutableState.update { it.copy(settings = queue.current(), message = null) }
        if (queue.isConfirmed()) schedulePersist()
    }

    fun setLanguage(language: String) {
        update { it.copy(language = AndroidLocales.normalize(language)) }
        AndroidLocales.apply(state.value.settings.language)
    }

    /** Mirrors a language selected in Android 13's system app-language settings. */
    fun setLanguageFromPlatform(language: String) {
        if (!state.value.loaded || state.value.settings.language == language) return
        update { it.copy(language = AndroidLocales.normalize(language)) }
    }
    fun setTheme(theme: String) = update { it.copy(theme = theme) }
    fun setAccentColor(color: String) = update { it.copy(accentColor = color) }
    fun setMaskHosts(enabled: Boolean) = update { it.copy(maskHosts = enabled) }
    fun setAutoCheckUpdates(enabled: Boolean) {
        update { it.copy(autoCheckUpdates = enabled) }
        if (enabled) checkForUpdates()
    }
    fun setTerminalFontSize(value: Int) = update { it.copy(terminalFontSize = value) }
    fun setTerminalCursorStyle(value: String) = update { it.copy(terminalCursorStyle = value) }
    fun setTerminalScrollbackLines(value: Int) = update { it.copy(terminalScrollbackLines = value) }
    fun setTerminalPalette(value: String) = update { it.copy(terminalPalette = value) }
    fun setTerminalLineHeight(value: String) = update { it.copy(terminalLineHeight = value) }
    fun setTerminalCursorBlink(enabled: Boolean) = update { it.copy(terminalCursorBlink = enabled) }
    fun setTerminalCopyOnSelect(enabled: Boolean) = update { it.copy(terminalCopyOnSelect = enabled) }
    fun setTerminalConfirmMultilinePaste(enabled: Boolean) = update { it.copy(terminalConfirmMultilinePaste = enabled) }
    fun setSftpConcurrency(value: Int) = update { it.copy(sftpConcurrency = value) }
    fun setSftpConflictPolicy(value: String) = update { it.copy(sftpConflictPolicy = value) }
    fun setSftpFileTap(value: String) = update { it.copy(sftpFileTap = value) }
    fun setSftpShowHiddenFiles(enabled: Boolean) = update { it.copy(sftpShowHiddenFiles = enabled) }
    fun setMonitorIntervalSeconds(value: Int) = update { it.copy(monitorIntervalSeconds = value) }
    fun setDownloadDirectoryUri(uri: String?) = update { it.copy(downloadDirectoryUri = uri) }

    /**
     * The second gate on the root tier, independent of the build flag and off by default.
     *
     * Stored as intent rather than as a resolved capability: the device's actual root state is
     * probed at each connect, and this only decides whether the tier may be offered at all.
     */
    fun setLocalAllowRoot(enabled: Boolean) = update { it.copy(localAllowRoot = enabled) }

    fun setSessionConnectionWidth(dp: Int) = update { it.copy(sessionConnectionWidthDp = dp) }

    fun setSessionMonitorWidth(dp: Int) = update { it.copy(sessionMonitorWidthDp = dp) }

    fun setSessionFileHeight(dp: Int) = update { it.copy(sessionFileHeightDp = dp) }

    fun checkForUpdates() {
        viewModelScope.launch {
            updateManager.checkForUpdates()
        }
    }

    fun downloadUpdate() {
        val available = state.value.update as? UpdateState.Available ?: return
        viewModelScope.launch { updateManager.download(available.release, available.apk) }
    }

    fun cancelDownload() = updateManager.cancelDownload()

    fun installUpdate() {
        viewModelScope.launch { updateManager.installReady() }
    }

    fun refreshInstallStatus() {
        mutableState.update { it.copy(update = updateManager.refreshInstallStatus()) }
    }

    fun installPermissionIntent(): Intent = updateManager.installPermissionIntent()
    fun canInstallPackages(): Boolean = updateManager.canInstallPackages()

    private fun schedulePersist() {
        val generation = ++persistGeneration
        viewModelScope.launch {
            delay(PERSIST_DEBOUNCE_MS)
            if (generation != persistGeneration) return@launch
            flushPersist()
        }
    }

    private suspend fun flushPersist() {
        val committed = saveMutex.withLock {
            val batch = queue.snapshotForSave()
            if (batch == null) {
                false
            } else {
                mutableState.update { it.copy(saving = true) }
                val saved = runCatching { withContext(Dispatchers.IO) { repository.save(batch.settings) } }
                saved.onSuccess {
                    queue.commit(batch)
                    mutableState.update { it.copy(settings = queue.current(), saving = false) }
                }.onFailure { error ->
                    mutableState.update {
                        it.copy(
                            saving = false,
                            message = error.message ?: getApplication<Application>().getString(R.string.settings_save_failed)
                        )
                    }
                }
                saved.isSuccess
            }
        }
        // A failed write stays queued for the next edit. Retrying it here would spin on a persistent I/O error.
        if (committed && queue.needsSave()) schedulePersist()
    }

    private companion object {
        const val PERSIST_DEBOUNCE_MS = 120L
    }

    override fun onCleared() {
        database.close()
        super.onCleared()
    }
}
