package com.enmapatcher.model

data class PatchStep(
    val name: String,
    val description: String = "",
    val status: PatchStepStatus = PatchStepStatus.PENDING,
)

enum class PatchStepStatus { PENDING, RUNNING, DONE, ERROR }
