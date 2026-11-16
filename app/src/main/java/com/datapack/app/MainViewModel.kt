package com.datapack.app

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class UiState(
    val batches: List<Batch> = emptyList(),
    val selectedId: String? = null,
    /** 0..1 position of the handle on the selected bar. */
    val fraction: Float = 0f,
    val isPlaying: Boolean = false,
)

class MainViewModel(app: Application) : AndroidViewModel(app) {
    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    private val player = Player(onFinished = {
        _state.value = _state.value.copy(isPlaying = false, fraction = 0f)
        progressJob?.cancel()
    })
    private var progressJob: Job? = null

    init {
        viewModelScope.launch {
            BatchStore.changes.collect { refresh() }
        }
    }

    fun refresh() {
        viewModelScope.launch {
            val list = withContext(Dispatchers.IO) { BatchStore.list(getApplication()) }
            val s = _state.value
            val stillThere = list.any { it.id == s.selectedId }
            _state.value = s.copy(
                batches = list,
                selectedId = if (stillThere) s.selectedId else null,
                fraction = if (stillThere) s.fraction else 0f,
            )
            if (!stillThere && s.isPlaying) stop()
        }
    }

    val selected: Batch? get() = _state.value.let { s -> s.batches.find { it.id == s.selectedId } }

    fun select(batch: Batch) {
        if (_state.value.isPlaying) stop()
        _state.value = _state.value.copy(selectedId = batch.id, fraction = 0f)
    }

    fun dragStart() {
        if (_state.value.isPlaying) stop()
    }

    fun dragTo(fraction: Float) {
        _state.value = _state.value.copy(fraction = fraction.coerceIn(0f, 1f))
    }

    fun play() {
        val b = selected ?: return
        val s = _state.value
        val from = if (s.fraction >= 0.999f) 0f else s.fraction
        try {
            player.play(b, from)
        } catch (e: Exception) {
            return
        }
        _state.value = s.copy(isPlaying = true, fraction = from)
        progressJob?.cancel()
        progressJob = viewModelScope.launch {
            while (isActive && player.isPlaying) {
                player.fraction()?.let { f -> _state.value = _state.value.copy(fraction = f) }
                delay(100)
            }
        }
    }

    fun stop() {
        progressJob?.cancel()
        player.stop()
        _state.value = _state.value.copy(isPlaying = false)
    }

    fun delete(batch: Batch) {
        if (batch.id == _state.value.selectedId) stop()
        viewModelScope.launch(Dispatchers.IO) { BatchStore.delete(batch) }
    }

    override fun onCleared() {
        player.stop()
        super.onCleared()
    }
}
