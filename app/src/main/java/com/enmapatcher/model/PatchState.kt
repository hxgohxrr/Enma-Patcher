package com.enmapatcher.model

sealed class PatchState {
    object Idle : PatchState()
    object Loading : PatchState()
    data class Patching(val steps: List<PatchStep>, val currentStep: Int = 0) : PatchState()
    data class Success(val outputPath: String) : PatchState()
    data class Error(val message: String, val cause: Throwable? = null) : PatchState()
}
