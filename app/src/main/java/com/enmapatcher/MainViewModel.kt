package com.enmapatcher

import android.app.Application
import android.content.Context
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.enmapatcher.model.AppSettings
import com.enmapatcher.model.EnmaCfg
import com.enmapatcher.model.PatchState
import com.enmapatcher.model.PatchStep
import com.enmapatcher.model.PatchStepStatus
import com.enmapatcher.patcher.ApkBundleProcessor
import com.enmapatcher.patcher.EnmaPatcherEngine
import com.enmapatcher.patcher.GithubPatchSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

private val Context.dataStore by preferencesDataStore(name = "app_settings")
private val SETTINGS_KEY = stringPreferencesKey("settings")

data class SecurityWarnings(
    val showDrmWarning: Boolean = false,
    val showSmaliWarning: Boolean = false,
)

class MainViewModel(app: Application) : AndroidViewModel(app) {

    private val context: Context get() = getApplication()

    private val _patchState = MutableStateFlow<PatchState>(PatchState.Idle)
    val patchState: StateFlow<PatchState> = _patchState.asStateFlow()

    private val _config = MutableStateFlow<EnmaCfg?>(null)
    val config: StateFlow<EnmaCfg?> = _config.asStateFlow()

    private val _settings = MutableStateFlow(AppSettings())
    val settings: StateFlow<AppSettings> = _settings.asStateFlow()

    private val _appInstalled = MutableStateFlow(false)
    val appInstalled: StateFlow<Boolean> = _appInstalled.asStateFlow()

    private val _backupFile = MutableStateFlow<File?>(null)
    val backupFile: StateFlow<File?> = _backupFile.asStateFlow()

    private val _hasDexPatches = MutableStateFlow(false)
    val hasDexPatches: StateFlow<Boolean> = _hasDexPatches.asStateFlow()

    init {
        viewModelScope.launch {

            val prefs = context.dataStore.data.first()
            prefs[SETTINGS_KEY]?.let { json ->
                runCatching { Json.decodeFromString<AppSettings>(json) }.getOrNull()
                    ?.let { saved ->
                        _settings.value = saved
                        applyLocale(saved.language)
                    }
            }
            checkInstalled()
            fetchRemoteConfig()
            checkExistingBackup()
        }
    }

    private fun checkExistingBackup() {
        val existing = try {
            val dir = context.getExternalFilesDir("backups") ?: File(context.filesDir, "backups")
            File(dir, "original_backup.apk").takeIf { it.exists() }
        } catch (_: Exception) { null }
        _backupFile.value = existing
    }

    fun fetchRemoteConfig() {
        viewModelScope.launch {
            try {
                val remote = GithubPatchSource(_settings.value).fetchConfig()
                _config.value = remote
            } catch (_: Exception) {}
        }
        viewModelScope.launch {
            _hasDexPatches.value = checkDexInRepo()
        }
    }

    private fun checkInstalled() {
        _appInstalled.value = ApkBundleProcessor(context).isInstalled(_settings.value.targetPackage)
    }

    fun updateSettings(newSettings: AppSettings) {
        _settings.value = newSettings
        checkInstalled()
        fetchRemoteConfig()
        applyLocale(newSettings.language)
        viewModelScope.launch {
            context.dataStore.edit { prefs ->
                prefs[SETTINGS_KEY] = Json.encodeToString(newSettings)
            }
        }
    }

    fun getSecurityWarnings(): SecurityWarnings {
        val s = _settings.value
        return SecurityWarnings(
            showDrmWarning = s.drmbUri.isNotBlank(),
            showSmaliWarning = _hasDexPatches.value,
        )
    }

    private suspend fun checkDexInRepo(): Boolean = try {
        val s = _settings.value
        val url = "https://api.github.com/repos/${s.githubOwner}/${s.githubRepoName}" +
                "/git/trees/${s.githubBranch}?recursive=1"
        val response = GithubPatchSource.client.newCall(
            okhttp3.Request.Builder().url(url)
                .header("Accept", "application/vnd.github+json")
                .build()
        ).execute()
        if (response.isSuccessful) {
            val body = response.body?.string().orEmpty()
            body.contains(".dex") || body.contains(".smali")
        } else false
    } catch (_: Exception) { false }

    private fun applyLocale(language: String) {
        val locale = if (language.isBlank())
            LocaleListCompat.getEmptyLocaleList()
        else
            LocaleListCompat.forLanguageTags(language)
        AppCompatDelegate.setApplicationLocales(locale)
    }

    fun patch() {
        val packageName = _settings.value.targetPackage
        if (_patchState.value is PatchState.Patching) return

        viewModelScope.launch(Dispatchers.IO) {
            val steps = mutableListOf<PatchStep>()
            _patchState.value = PatchState.Patching(steps.toList())

            try {
                val engine = EnmaPatcherEngine(context)
                val result = engine.patch(packageName, _settings.value) { step ->
                    val idx = steps.indexOfFirst { it.name == step.name }
                    if (idx >= 0) steps[idx] = step else steps += step
                    _patchState.value = PatchState.Patching(
                        steps.toList(),
                        steps.indexOfFirst { it.status == PatchStepStatus.RUNNING },
                    )
                }
                if (result.backupApk != null) _backupFile.value = result.backupApk
                _patchState.value = PatchState.Success(result.outputApk.absolutePath)
                _config.value = result.config
            } catch (e: Exception) {
                val msg = buildString {
                    var ex: Throwable? = e
                    while (ex != null) {
                        if (isNotEmpty()) append("\n→ ")
                        append(ex.javaClass.simpleName)
                        if (!ex.message.isNullOrBlank()) append(": ${ex.message}")
                        ex = ex.cause
                    }
                }
                _patchState.value = PatchState.Error(msg, e)
            }
        }
    }


    fun updateDrmbUri(path: String) {
        val newSettings = _settings.value.copy(drmbUri = path)
        _settings.value = newSettings
        viewModelScope.launch {
            context.dataStore.edit { prefs ->
                prefs[SETTINGS_KEY] = Json.encodeToString(newSettings)
            }
        }
    }

    fun resetState() {
        _patchState.value = PatchState.Idle
    }
}
