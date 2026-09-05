package com.enmapatcher

import android.app.Application
import android.content.Context
import android.net.Uri
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.enmapatcher.BuildConfig
import com.enmapatcher.model.AppSettings
import com.enmapatcher.model.EnmaCfg
import com.enmapatcher.model.ModEntry
import com.enmapatcher.model.ModKind
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
import kotlinx.coroutines.withContext
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

    private val _updateAvailable = MutableStateFlow<String?>(null)
    val updateAvailable: StateFlow<String?> = _updateAvailable.asStateFlow()

    init {
        viewModelScope.launch {
            val prefs = context.dataStore.data.first()
            prefs[SETTINGS_KEY]?.let { json ->
                runCatching { Json.decodeFromString<AppSettings>(json) }.getOrNull()
                    ?.let { saved ->
                        val migrated = saved.withMigratedMods()
                        _settings.value = migrated
                        applyLocale(migrated.language)
                        if (migrated != saved) persistSettings(migrated)
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
        } catch (_: Exception) {
            null
        }
        _backupFile.value = existing
    }

    fun fetchRemoteConfig() {
        viewModelScope.launch {
            try {
                val mods = _settings.value.effectiveMods().filter { it.enabled && it.kind == ModKind.GITHUB }
                val first = mods.firstOrNull()
                if (first != null) {
                    val remote = withContext(Dispatchers.IO) {
                        GithubPatchSource.fetchRemoteConfigOrNull(
                            first.owner,
                            first.repoName,
                            first.branch.ifBlank { "main" }
                        )
                    }
                    _config.value = remote
                    remote?.version?.let { remoteVer ->
                        if (isNewerVersion(remoteVer, BuildConfig.VERSION_NAME)) {
                            _updateAvailable.value = remoteVer
                        }
                    }
                }
            } catch (_: Exception) {
            }
        }
        viewModelScope.launch {
            _hasDexPatches.value = checkDexInMods()
        }
    }

    private fun checkInstalled() {
        _appInstalled.value = ApkBundleProcessor(context).isInstalled(_settings.value.targetPackage)
    }

    fun updateSettings(newSettings: AppSettings) {
        val migrated = newSettings.withMigratedMods()
        _settings.value = migrated
        checkInstalled()
        fetchRemoteConfig()
        applyLocale(migrated.language)
        viewModelScope.launch {
            persistSettings(migrated)
        }
    }

    private suspend fun persistSettings(value: AppSettings) {
        context.dataStore.edit { prefs ->
            prefs[SETTINGS_KEY] = Json.encodeToString(value)
        }
    }

    fun addGithubMod(repo: String, branch: String) {
        val cleanRepo = repo.trim()
        if (cleanRepo.isBlank() || "/" !in cleanRepo) return
        val entry = ModEntry(
            kind = ModKind.GITHUB,
            repo = cleanRepo,
            branch = branch.trim().ifBlank { "main" },
            enabled = true
        )
        updateSettings(_settings.value.copy(mods = _settings.value.effectiveMods() + entry))
    }

    fun addZipMod(zipUri: String, zipName: String) {
        if (zipUri.isBlank()) return
        val entry = ModEntry(
            kind = ModKind.ZIP,
            zipUri = zipUri,
            zipName = zipName.ifBlank { Uri.parse(zipUri).lastPathSegment.orEmpty() },
            enabled = true
        )
        updateSettings(_settings.value.copy(mods = _settings.value.effectiveMods() + entry))
    }

    fun removeMod(id: String) {
        val updated = _settings.value.effectiveMods().filterNot { it.id == id }
        val base = _settings.value.copy(mods = updated)
        _settings.value = base
        viewModelScope.launch { persistSettings(base) }
        fetchRemoteConfig()
    }

    fun moveMod(id: String, delta: Int) {
        val list = _settings.value.effectiveMods().toMutableList()
        val idx = list.indexOfFirst { it.id == id }
        if (idx < 0) return
        val target = (idx + delta).coerceIn(0, list.size - 1)
        if (target == idx) return
        val item = list.removeAt(idx)
        list.add(target, item)
        val base = _settings.value.copy(mods = list)
        _settings.value = base
        viewModelScope.launch { persistSettings(base) }
    }

    fun toggleMod(id: String, enabled: Boolean) {
        val list = _settings.value.effectiveMods().map { if (it.id == id) it.copy(enabled = enabled) else it }
        val base = _settings.value.copy(mods = list)
        _settings.value = base
        viewModelScope.launch { persistSettings(base) }
        fetchRemoteConfig()
    }

    fun getSecurityWarnings(): SecurityWarnings {
        val s = _settings.value
        return SecurityWarnings(
            showDrmWarning = s.drmbUri.isNotBlank(),
            showSmaliWarning = _hasDexPatches.value,
        )
    }

    private suspend fun checkDexInMods(): Boolean = withContext(Dispatchers.IO) {
        try {
            for (mod in _settings.value.effectiveMods().filter { it.enabled }) {
                if (mod.kind == ModKind.GITHUB) {
                    if (mod.repo.isBlank() || "/" !in mod.repo) continue
                    val hit = runCatching {
                        GithubPatchSource.listRepoFiles(mod.owner, mod.repoName, mod.branch.ifBlank { "main" })
                            .any { it.endsWith(".dex") || it.endsWith(".smali") || it.startsWith("smali/") }
                    }.getOrDefault(false)
                    if (hit) return@withContext true
                } else {
                    val hit = runCatching {
                        val uri = Uri.parse(mod.zipUri)
                        context.contentResolver.openInputStream(uri)?.use { stream ->
                            GithubPatchSource.listLocalZipEntries(stream, 2000)
                                .any { it.endsWith(".dex") || it.endsWith(".smali") || it.startsWith("smali/") }
                        } ?: false
                    }.getOrDefault(false)
                    if (hit) return@withContext true
                }
            }
            false
        } catch (_: Exception) {
            false
        }
    }

    private fun isNewerVersion(remote: String, local: String): Boolean {
        val r = remote.trim().removePrefix("v").split(".").mapNotNull { it.toIntOrNull() }
        val l = local.trim().removePrefix("v").split(".").mapNotNull { it.toIntOrNull() }
        for (i in 0 until maxOf(r.size, l.size)) {
            val rv = r.getOrElse(i) { 0 }
            val lv = l.getOrElse(i) { 0 }
            if (rv > lv) return true
            if (rv < lv) return false
        }
        return false
    }

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
            persistSettings(newSettings)
        }
    }

    fun resetState() {
        _patchState.value = PatchState.Idle
    }
}
