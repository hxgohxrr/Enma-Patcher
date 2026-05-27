package com.enmapatcher

import android.app.Application
import android.content.Context
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
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
import kotlinx.coroutines.launch
import java.io.File

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

    init {
        checkInstalled()
        fetchRemoteConfig()
        checkExistingBackup()
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
    }

    private fun checkInstalled() {
        _appInstalled.value = ApkBundleProcessor(context).isInstalled(_settings.value.targetPackage)
    }

    fun updateSettings(newSettings: AppSettings) {
        _settings.value = newSettings
        checkInstalled()
        fetchRemoteConfig()
        val locale = if (newSettings.language.isBlank())
            LocaleListCompat.getEmptyLocaleList()
        else
            LocaleListCompat.forLanguageTags(newSettings.language)
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

    fun resetState() {
        _patchState.value = PatchState.Idle
    }
}
