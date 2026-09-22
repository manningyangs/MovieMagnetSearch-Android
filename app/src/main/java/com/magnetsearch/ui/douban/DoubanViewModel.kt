package com.magnetsearch.ui.douban

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.magnetsearch.data.model.DoubanDetail
import com.magnetsearch.data.model.DoubanMovie
import com.magnetsearch.data.repository.DoubanRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

data class DoubanUiState(
    val isLoading: Boolean = false,
    val movies: List<DoubanMovie> = emptyList(),
    val error: String? = null,
    val isTop250: Boolean = true,
    // 详情页导航状态
    val selectedMovie: DoubanMovie? = null,       // 当前选中的电影（列表中的那个）
    val detailLoading: Boolean = false,
    val detail: DoubanDetail? = null,             // 解析出的详情
    val detailError: String? = null
)

class DoubanViewModel : ViewModel() {
    private val repo = DoubanRepository()

    private val _uiState = MutableStateFlow(DoubanUiState())
    val uiState: StateFlow<DoubanUiState> = _uiState

    fun loadTop250() {
        _uiState.value = DoubanUiState(isLoading = true, isTop250 = true)
        viewModelScope.launch {
            runCatching { repo.getTop250(250) }
                .onSuccess { movies ->
                    _uiState.value = DoubanUiState(movies = movies, isTop250 = true)
                }
                .onFailure { e ->
                    _uiState.value = DoubanUiState(error = e.message ?: "加载失败", isTop250 = true)
                }
        }
    }

    fun search(query: String) {
        _uiState.value = DoubanUiState(isLoading = true, isTop250 = false)
        viewModelScope.launch {
            runCatching { repo.search(query, 30) }
                .onSuccess { movies ->
                    _uiState.value = DoubanUiState(movies = movies, isTop250 = false)
                }
                .onFailure { e ->
                    _uiState.value = DoubanUiState(error = e.message ?: "搜索失败", isTop250 = false)
                }
        }
    }

    /** 点击某部电影 → 进入详情页并异步加载详情。 */
    fun selectMovie(movie: DoubanMovie) {
        _uiState.value = _uiState.value.copy(
            selectedMovie = movie,
            detail = null,
            detailLoading = true,
            detailError = null
        )
        if (movie.doubanId.isNotBlank()) {
            viewModelScope.launch {
                runCatching { repo.getDetail(movie.doubanId) }
                    .onSuccess { detail ->
                        _uiState.value = _uiState.value.copy(
                            detail = detail,
                            detailLoading = false,
                            // 把列表已有的 summary/cover 合并进去（detail 解析可能不全）
                            selectedMovie = movie.copy(
                                summary = detail?.fullSummary?.takeIf { it.isNotBlank() } ?: movie.summary,
                                coverUrl = detail?.coverUrl?.takeIf { it.isNotBlank() } ?: movie.coverUrl
                            )
                        )
                    }
                    .onFailure { e ->
                        _uiState.value = _uiState.value.copy(
                            detailLoading = false,
                            detailError = e.message ?: "加载详情失败"
                        )
                    }
            }
        } else {
            _uiState.value = _uiState.value.copy(
                detailLoading = false,
                detailError = "缺少豆瓣 ID，无法加载详情"
            )
        }
    }

    /** 返回按钮 → 回到列表页。 */
    fun backToList() {
        _uiState.value = _uiState.value.copy(
            selectedMovie = null,
            detail = null,
            detailLoading = false,
            detailError = null
        )
    }
}
