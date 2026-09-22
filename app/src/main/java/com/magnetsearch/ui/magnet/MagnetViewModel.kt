package com.magnetsearch.ui.magnet

import android.app.Application
import android.content.SharedPreferences
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.magnetsearch.data.model.MediaType
import com.magnetsearch.data.model.MagnetResult
import com.magnetsearch.data.model.QualityFilter
import com.magnetsearch.data.model.SearchSource
import com.magnetsearch.data.model.SortBy
import com.magnetsearch.data.repository.MagnetRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class MagnetUiState(
    val isLoading: Boolean = false,
    val rawResults: List<MagnetResult> = emptyList(),
    val filteredResults: List<MagnetResult> = emptyList(),
    val error: String? = null,
    val enabledSources: Set<SearchSource> = SearchSource.all().toSet(),
    val qualityFilter: QualityFilter = QualityFilter.ALL,
    val sortBy: SortBy = SortBy.SEEDERS
)

class MagnetViewModel(application: Application) : AndroidViewModel(application) {
    private val repo = MagnetRepository()
    private val prefs: SharedPreferences =
        application.getSharedPreferences(PREFS_NAME, Application.MODE_PRIVATE)

    private val _uiState = MutableStateFlow(MagnetUiState())
    val uiState: StateFlow<MagnetUiState> = _uiState.asStateFlow()

    init {
        loadPrefs()
    }

    private fun loadPrefs() {
        val saved = prefs.getString(KEY_ENABLED_SOURCES, "") ?: ""
        val sources = if (saved.isBlank()) {
            SearchSource.all().toSet()
        } else {
            saved.split(",").mapNotNull { name ->
                SearchSource.values().find { it.name == name }
            }.ifEmpty { SearchSource.all() }.toSet()
        }
        _uiState.value = _uiState.value.copy(enabledSources = sources)
    }

    private fun saveSources(sources: Set<SearchSource>) {
        prefs.edit().putString(KEY_ENABLED_SOURCES, sources.joinToString(",") { it.name }).apply()
    }

    fun toggleSource(source: SearchSource, enabled: Boolean) {
        val current = _uiState.value.enabledSources.toMutableSet()
        if (enabled) current.add(source) else current.remove(source)
        if (current.isEmpty()) return // 至少保留一个
        _uiState.value = _uiState.value.copy(enabledSources = current)
        saveSources(current)
        reapplyFilter()
    }

    fun setQualityFilter(filter: QualityFilter) {
        _uiState.value = _uiState.value.copy(qualityFilter = filter)
        reapplyFilter()
    }

    fun setSortBy(sortBy: SortBy) {
        _uiState.value = _uiState.value.copy(sortBy = sortBy)
        reapplyFilter()
    }

    fun search(query: String, mediaType: MediaType = MediaType.MOVIE) {
        val sources = _uiState.value.enabledSources.toList()
        _uiState.value = _uiState.value.copy(isLoading = true, error = null)
        viewModelScope.launch {
            runCatching { repo.search(query, mediaType, sources) }
                .onSuccess { results ->
                    _uiState.value = _uiState.value.copy(
                        rawResults = results,
                        filteredResults = results,
                        isLoading = false
                    )
                    reapplyFilter()
                }
                .onFailure { e ->
                    _uiState.value = _uiState.value.copy(
                        isLoading = false,
                        error = e.message ?: "搜索失败"
                    )
                }
        }
    }

    private fun reapplyFilter() {
        val s = _uiState.value
        val filtered = s.rawResults
            .filter { s.qualityFilter.matches(it) }
            .sortedWith { a, b -> s.sortBy.compare(a, b) }
        _uiState.value = s.copy(filteredResults = filtered)
    }

    private companion object {
        const val PREFS_NAME = "magnet_search_prefs"
        const val KEY_ENABLED_SOURCES = "enabled_sources"
    }
}
