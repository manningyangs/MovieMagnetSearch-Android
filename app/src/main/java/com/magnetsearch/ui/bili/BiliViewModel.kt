package com.magnetsearch.ui.bili

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.magnetsearch.data.model.BiliVideo
import com.magnetsearch.data.repository.BiliRepository
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

enum class BiliListState { IDLE, LOADING, SUCCESS, ERROR }

class BiliViewModel : ViewModel() {

    private val _list = MutableStateFlow<List<BiliVideo>>(emptyList())
    val list: StateFlow<List<BiliVideo>> = _list

    private val _state = MutableStateFlow(BiliListState.IDLE)
    val state: StateFlow<BiliListState> = _state

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error

    private var currentCategory: String? = null
    private var loadJob: Job? = null

    fun loadCategory(category: String) {
        if (category == currentCategory && _list.value.isNotEmpty()) return
        currentCategory = category
        loadJob?.cancel()
        loadJob = viewModelScope.launch {
            _state.value = BiliListState.LOADING
            _error.value = null
            val result = BiliRepository.nameToTid(category)?.let { tid ->
                BiliRepository.fetchRanking(tid)
            } ?: BiliRepository.fetchRecommend()

            result.fold(
                onSuccess = { videos ->
                    _list.value = videos
                    _state.value = BiliListState.SUCCESS
                },
                onFailure = { e ->
                    _error.value = e.message ?: "加载失败"
                    _state.value = BiliListState.ERROR
                }
            )
        }
    }
}

/** 格式化播放量：1.2万 / 3.4亿 */
fun formatPlayCount(n: Long): String = when {
    n >= 100_000_000 -> "%.1f亿".format(n / 100_000_000.0)
    n >= 10_000 -> "%.1f万".format(n / 10_000.0)
    else -> n.toString()
}

/** 格式化时长：12:34 / 1:23:45 */
fun formatDuration(sec: Int): String {
    if (sec <= 0) return ""
    val h = sec / 3600
    val m = (sec % 3600) / 60
    val s = sec % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}
