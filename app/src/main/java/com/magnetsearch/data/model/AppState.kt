package com.magnetsearch.data.model

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * 全局 App 状态（跨屏幕共享，不通过 ViewModel）
 * 主要用于子屏幕（如全屏播放器）控制父屏幕（如底部 Tab）的显示/隐藏
 */
object AppState {
    /** 是否处于视频全屏模式 —— 全屏时隐藏底部导航栏 */
    var isVideoFullscreen by mutableStateOf(false)
}
