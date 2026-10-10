package io.github.v2compose.ui.settings

import io.github.v2compose.usecase.RefreshSingBoxSubscriptionUseCase

/** 设置页订阅导入的 UI 状态 */
sealed interface SingBoxImportState {

    data object Idle : SingBoxImportState

    data object Loading : SingBoxImportState

    data class Success(val nodeCount: Int) : SingBoxImportState

    data class Failed(
        val reason: RefreshSingBoxSubscriptionUseCase.FailureReason,
        val message: String? = null,
    ) : SingBoxImportState
}
