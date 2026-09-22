package com.magnetsearch.ui.magnet

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.magnetsearch.data.model.MediaType
import com.magnetsearch.data.model.MagnetResult
import com.magnetsearch.data.model.SearchSource
import com.magnetsearch.data.repository.MagnetRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

data class MagnetUiState(
    val isLoading: Boolean = false,
    val results: List<MagnetResult> = emptyList(),
    val error: String? = null
)

class MagnetViewModel : ViewModel() {
    private val repo = MagnetRepository()

    private val _uiState = MutableStateFlow(MagnetUiState())
    val uiState: StateFlow<MagnetUiState> = _uiState

    fun search(
        query: String,
        mediaType: MediaType = MediaType.MOVIE,
        sources: List<SearchSource> = SearchSource.all()
    ) {
        _uiState.value = MagnetUiState(isLoading = true)
        viewModelScope.launch {
            runCatching { repo.search(query, mediaType, sources) }
                .onSuccess { results ->
                    _uiState.value = MagnetUiState(results = results.sortedWith(
                        compareByDescending<MagnetResult> { it.seeders }.thenBy { it.source }
                    ))
                }
                .onFailure { e ->
                    _uiState.value = MagnetUiState(error = e.message ?: "搜索失败")
                }
        }
    }
}
