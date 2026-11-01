package com.dkraft.powhvm6

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainViewModel(app: Application) : AndroidViewModel(app) {
    val settings = Settings(app)

    var snapshot by mutableStateOf<Snapshot?>(null)
        private set
    var error by mutableStateOf<String?>(null)
        private set
    var busy by mutableStateOf(false)
        private set

    var foundDevices by mutableStateOf<List<Found>>(emptyList())
        private set
    var searching by mutableStateOf(false)
        private set

    private var pollJob: Job? = null

    fun startPolling(host: String) {
        settings.host = host
        snapshot = null
        error = null
        pollJob?.cancel()
        pollJob = viewModelScope.launch {
            while (true) {
                try {
                    val s = withContext(Dispatchers.IO) { Api.fetchNow(host) }
                    snapshot = s
                    error = null
                } catch (e: Exception) {
                    error = e.message ?: e::class.java.simpleName
                }
                delay(2000)
            }
        }
    }

    fun stopPolling() {
        pollJob?.cancel()
        pollJob = null
    }

    fun search() {
        viewModelScope.launch {
            searching = true
            foundDevices = emptyList()
            val results = withContext(Dispatchers.IO) {
                val out = mutableListOf<Found>()
                Discover.broadcast()?.let { out.add(it) }
                if (out.isEmpty()) {
                    val prefix = settings.host.substringBeforeLast(".")
                        .ifEmpty { "10.0.0" }
                    Discover.scanSubnet(prefix)?.let { out.addAll(it) }
                }
                out
            }
            foundDevices = results
            searching = false
        }
    }

    fun pickDevice(d: Found) {
        settings.host = d.ip
        startPolling(d.ip)
    }
}
