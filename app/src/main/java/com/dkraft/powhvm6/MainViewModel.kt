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

    /** App -> Pi fetch round-trip and failures since polling (re)started. */
    var appLink by mutableStateOf(AppLink())
        private set
    var busy by mutableStateOf(false)
        private set

    var foundDevices by mutableStateOf<List<Found>>(emptyList())
        private set
    var searching by mutableStateOf(false)
        private set

    /** Observable so theme changes recompose immediately. */
    var themeMode by mutableStateOf(settings.themeMode)
        private set

    fun updateThemeMode(mode: ThemeMode) {
        settings.themeMode = mode
        themeMode = mode
    }

    var keepScreenOn by mutableStateOf(settings.keepScreenOn)
        private set

    fun updateKeepScreenOn(on: Boolean) {
        settings.keepScreenOn = on
        keepScreenOn = on
    }

    var tempUnit by mutableStateOf(settings.tempUnit)
        private set

    fun updateTempUnit(unit: TempUnit) {
        settings.tempUnit = unit
        tempUnit = unit
    }

    /** Observable so field toggles update immediately. */
    var hiddenFields by mutableStateOf(settings.hiddenFields())
        private set

    fun isFieldVisible(id: String): Boolean = id !in hiddenFields

    fun setFieldVisible(id: String, visible: Boolean) {
        settings.setFieldVisible(id, visible)
        hiddenFields = settings.hiddenFields()
    }

    /** Settings the Pi says it can write; empty until loaded or if unsupported. */
    var settingDefs by mutableStateOf<List<SettingDef>>(emptyList())
        private set
    /** Why settings aren't available (old Pi script, unreachable), or null. */
    var settingsMessage by mutableStateOf<String?>(null)
        private set
    var edit by mutableStateOf<EditState?>(null)
        private set

    private var pollJob: Job? = null

    /** Host the app is currently talking to: the primary (LAN) or the ZeroTier fallback. */
    @Volatile private var activeHost: String = ""
    private var onFallback = false
    private var lastPrimaryTry = 0L

    /** All reads and writes go through whichever route is answering. */
    fun currentHost(): String = activeHost.ifEmpty { settings.host }

    var fallbackEnabled by mutableStateOf(settings.fallbackEnabled)
        private set
    var fallbackHost by mutableStateOf(settings.fallbackHost)
        private set
    var fallbackPort by mutableStateOf(settings.fallbackPort)
        private set

    fun updateFallback(enabled: Boolean, host: String, port: Int) {
        settings.fallbackEnabled = enabled
        settings.fallbackHost = host
        settings.fallbackPort = port
        fallbackEnabled = settings.fallbackEnabled
        fallbackHost = settings.fallbackHost
        fallbackPort = settings.fallbackPort
        if (!fallbackEnabled && onFallback) {
            onFallback = false
            activeHost = settings.host
        }
    }

    private suspend fun fetchRoute(host: String, primary: Boolean): Snapshot =
        withContext(Dispatchers.IO) {
            if (primary) Api.fetchNow(host, connectMs = PRIMARY_TIMEOUT_MS, readMs = PRIMARY_TIMEOUT_MS)
            else Api.fetchNow(host)
        }

    /** One poll: current route, failing over (primary -> fallback) or back (every ~60 s). */
    private suspend fun pollOnce(primary: String) {
        val fallback = settings.fallbackAddr
        val now = System.currentTimeMillis()
        val order = mutableListOf<Pair<String, Boolean>>()
        if (onFallback && fallback.isNotEmpty()) {
            if (now - lastPrimaryTry >= PRIMARY_RETRY_MS) { lastPrimaryTry = now; order += primary to true }
            order += fallback to false
            if (order.size == 1) order += primary to true  // fallback down too: try LAN once
        } else {
            onFallback = false
            order += primary to true
            if (fallback.isNotEmpty()) order += fallback to false
        }
        var lastErr: Exception? = null
        for ((host, isPrimary) in order.distinctBy { it.first }) {
            val t0 = System.nanoTime()
            try {
                val s = fetchRoute(host, isPrimary)
                val switched = activeHost != host
                activeHost = host
                if (!isPrimary && !onFallback) lastPrimaryTry = System.currentTimeMillis()
                onFallback = !isPrimary
                appLink = appLink.onRoute(if (isPrimary) "LAN" else "ZeroTier").good((System.nanoTime() - t0) / 1e6)
                snapshot = s
                error = null
                if (switched && settingDefs.isEmpty()) loadSettings()
                return
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                lastErr = e
            }
        }
        appLink = appLink.bad()
        error = lastErr?.let { it.message ?: it::class.java.simpleName }
    }

    init {
        // Resume polling the saved Pi after an app restart.
        val saved = settings.host
        if (saved.isNotEmpty()) startPolling(saved)
    }

    fun startPolling(input: String) {
        val host = HostAddr.normalize(input)
        if (host.isEmpty()) return
        settings.host = host
        snapshot = null
        error = null
        appLink = AppLink()
        activeHost = host
        onFallback = false
        lastPrimaryTry = 0L
        settingDefs = emptyList()
        settingsMessage = null
        loadSettings()
        pollJob?.cancel()
        pollJob = viewModelScope.launch {
            while (true) {
                pollOnce(host)
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
                    val ip = HostAddr.hostOnly(settings.host)
                    val prefix = if (Regex("""^\d+\.\d+\.\d+\.\d+$""").matches(ip))
                        ip.substringBeforeLast(".") else "10.0.0"
                    out.addAll(Discover.scanSubnet(prefix))
                }
                out
            }
            foundDevices = results
            searching = false
        }
    }

    fun pickDevice(d: Found) {
        val port = d.httpPort.takeIf { it in 1..65535 } ?: HostAddr.DEFAULT_PORT
        startPolling("${d.ip}:$port")
    }

    // ---- Writable inverter settings (registry served by the Pi) ----

    fun loadSettings() {
        val host = currentHost()
        if (host.isEmpty()) return
        viewModelScope.launch { refreshSettings(host) }
    }

    private suspend fun refreshSettings(host: String): Boolean {
        return try {
            settingDefs = withContext(Dispatchers.IO) { SettingsApi.fetch(host) }
            settingsMessage = null
            true
        } catch (e: SettingsUnsupported) {
            settingDefs = emptyList()
            settingsMessage = e.message
            false
        } catch (e: Exception) {
            settingsMessage = "Couldn't load settings: ${e.message ?: e::class.java.simpleName}"
            false
        }
    }

    /** Field ids that should look tappable on the dashboard. */
    fun editableFieldIds(): Set<String> {
        val ids = settingDefs.filter { it.enabled }.mapNotNull { it.fieldId }.toSet()
        // Before the registry loads (or on an old Pi script) keep the known ones tappable
        // so the user gets a clear "update the Pi" message instead of nothing.
        return if (settingDefs.isEmpty()) setOf("max_charge", "ac_chg_max") else ids
    }

    /** Open the editor for a dashboard field id or a registry key. */
    fun beginEdit(fieldId: String? = null, key: String? = null) {
        val host = currentHost()
        edit = EditState(stage = EditStage.LOADING)
        viewModelScope.launch {
            val ok = refreshSettings(host)
            if (!ok) {
                edit = EditState(stage = EditStage.ERROR, message = settingsMessage ?: "Settings unavailable")
                return@launch
            }
            val def = settingDefs.firstOrNull { (key != null && it.key == key) || (fieldId != null && it.fieldId == fieldId) }
            edit = when {
                def == null -> EditState(stage = EditStage.ERROR, message = "This value can't be changed from the app.")
                !def.enabled -> EditState(def, EditStage.ERROR, message = "${def.label} is read-only on this Pi (disabled in the settings registry).")
                def.current == null -> EditState(def, EditStage.ERROR, message = "The Pi hasn't read ${def.label} from the inverter yet. Try again in a few seconds.")
                else -> EditState(def, EditStage.PICK, pending = def.current)
            }
        }
    }

    fun choose(value: Double) {
        val e = edit ?: return
        edit = e.copy(pending = value)
    }

    fun toConfirm() {
        val e = edit ?: return
        if (e.def == null || e.pending == null) return
        edit = e.copy(stage = EditStage.CONFIRM)
    }

    fun backToPick() {
        val e = edit ?: return
        edit = e.copy(stage = EditStage.PICK)
    }

    fun applyEdit() {
        val e = edit ?: return
        val def = e.def ?: return
        val v = e.pending ?: return
        val host = currentHost()
        edit = e.copy(stage = EditStage.WRITING)
        viewModelScope.launch {
            val result = try {
                withContext(Dispatchers.IO) { SettingsApi.set(host, def.key, v) }
            } catch (ex: SettingsUnsupported) {
                SetResult(false, ex.message ?: "Pi doesn't support writes", null, null)
            } catch (ex: Exception) {
                SetResult(false, "Write failed: ${ex.message ?: ex::class.java.simpleName}", null, null)
            }
            refreshSettings(host)
            try { snapshot = withContext(Dispatchers.IO) { Api.fetchNow(host) } } catch (_: Exception) {}
            edit = e.copy(stage = EditStage.DONE, result = result)
        }
    }

    fun dismissEdit() {
        if (edit?.stage == EditStage.WRITING) return  // can't abandon a write mid-flight
        edit = null
    }

    // ---- Events (generic power-event trackers held on the Pi; read-only w.r.t. the inverter) ----

    var events by mutableStateOf<EventsList?>(null)
        private set
    /** Wall clock when [events] arrived; the UI ticks live timers from here. */
    var eventsFetchedAtMs by mutableStateOf(0L)
        private set
    var eventsError by mutableStateOf<String?>(null)
        private set
    var eventsBusy by mutableStateOf(false)
        private set
    var eventHistory by mutableStateOf<EventHistory?>(null)
        private set
    var eventHistoryError by mutableStateOf<String?>(null)
        private set
    private var eventsJob: Job? = null
    /** Set by the activity; the Events poll idles while the app is in the background. */
    @Volatile var appForeground = true

    /** Run [block] against the active route; on a network failure try the other route once. */
    private suspend fun <T> onRoute(block: (String) -> T): T = withContext(Dispatchers.IO) {
        val first = currentHost()
        try {
            block(first)
        } catch (e: java.io.IOException) {
            val other = listOf(settings.host, settings.fallbackAddr)
                .firstOrNull { it.isNotEmpty() && it != first } ?: throw e
            block(other)
        }
    }

    private fun msg(e: Throwable): String = when (e) {
        is java.net.SocketTimeoutException -> "Pi didn't answer (timeout)"
        is java.io.IOException -> "Can't reach the Pi: ${e.message ?: e.javaClass.simpleName}"
        else -> e.message ?: e.javaClass.simpleName
    }

    suspend fun refreshEvents() {
        if (currentHost().isEmpty()) return
        try {
            val l = onRoute { EventsApi.list(it) }
            events = l
            eventsFetchedAtMs = System.currentTimeMillis()
            eventsError = null
        } catch (e: Exception) {
            eventsError = msg(e)
        }
    }

    /** Poll the tracker list every 3 s while the Events tab is on screen. */
    fun startEventsPolling() {
        if (eventsJob?.isActive == true) return
        eventsJob = viewModelScope.launch {
            while (true) {
                if (appForeground) refreshEvents()
                delay(3000)
            }
        }
    }

    fun stopEventsPolling() {
        eventsJob?.cancel()
        eventsJob = null
    }

    fun loadEventHistory(id: Long) {
        viewModelScope.launch {
            eventHistoryError = null
            try {
                eventHistory = onRoute { EventsApi.history(it, id, 200) }
            } catch (e: Exception) {
                eventHistoryError = msg(e)
            }
        }
    }

    fun clearEventHistory() {
        eventHistory = null
        eventHistoryError = null
    }

    /** Create (id == null) or update a tracker. [done] gets null on success, else the error text. */
    fun saveTracker(id: Long?, draft: TrackerDraft, done: (String?) -> Unit) {
        viewModelScope.launch {
            eventsBusy = true
            val err = try {
                draft.toJson() // local number check before going to the network
                onRoute { if (id == null) EventsApi.create(it, draft) else EventsApi.update(it, id, draft) }
                null
            } catch (e: Exception) {
                msg(e)
            }
            if (err == null) refreshEvents()
            eventsBusy = false
            done(err)
        }
    }

    /** pause | resume | reset | delete */
    fun trackerAction(id: Long, action: String) {
        viewModelScope.launch {
            eventsBusy = true
            try {
                onRoute { EventsApi.action(it, id, action) }
                refreshEvents()
                if (eventHistory?.tracker?.id == id) {
                    if (action == "delete") clearEventHistory() else loadEventHistory(id)
                }
            } catch (e: Exception) {
                eventsError = msg(e)
            }
            eventsBusy = false
        }
    }
}

private const val PRIMARY_TIMEOUT_MS = 3000
private const val PRIMARY_RETRY_MS = 60_000L

enum class EditStage { LOADING, PICK, CONFIRM, WRITING, DONE, ERROR }

data class EditState(
    val def: SettingDef? = null,
    val stage: EditStage,
    val pending: Double? = null,
    val result: SetResult? = null,
    val message: String? = null
)
