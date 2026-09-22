package com.magnetsearch.ui.douban

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.magnetsearch.data.model.DoubanMovie
import com.magnetsearch.data.repository.DoubanRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

data class DoubanUiState(
    val isLoading: Boolean = false,
    val movies: List<DoubanMovie> = emptyList(),
    val error: String? = null,
    val isTop250: Boolean = true
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
}
