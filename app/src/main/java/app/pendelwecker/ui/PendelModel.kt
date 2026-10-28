package app.pendelwecker.ui

import android.Manifest
import android.app.AlarmManager
import android.app.Application
import android.content.pm.PackageManager
import android.os.Build
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.pendelwecker.alarm.CheckScheduler
import app.pendelwecker.alarm.CommuteRunner
import app.pendelwecker.data.Store
import app.pendelwecker.domain.CommuteProfile
import app.pendelwecker.domain.StatusSnapshot
import app.pendelwecker.widget.CommuteWidget
import kotlinx.coroutines.launch
import java.time.ZoneId
import java.time.ZonedDateTime

/** Die nächste anstehende Fahrt eines Profils. */
data class Upcoming(
    val profile: CommuteProfile,
    val departure: ZonedDateTime,
    val nextCheck: ZonedDateTime?,
)

/** Zustand der Oberfläche: Profile, letzte Prüfungen, Berechtigungen. */
class PendelModel(app: Application) : AndroidViewModel(app) {
    private val store = Store(app)

    val profiles = mutableStateListOf<CommuteProfile>()
    val snapshots = mutableStateMapOf<Int, StatusSnapshot>()
    var checking by mutableStateOf<Set<Int>>(emptySet())
        private set
    var exactAllowed by mutableStateOf(true)
        private set
    var notificationsAllowed by mutableStateOf(true)
        private set

    init {
        reload()
    }

    /** Neu einlesen, z. B. wenn die App wieder sichtbar wird oder ein Alarm geprüft hat. */
    fun reload() {
        val app = getApplication<Application>()
        val stored = store.profiles()
        if (stored != profiles.toList()) {
            profiles.clear(); profiles.addAll(stored)
        }
        val snaps = store.snapshots().associateBy { it.profileId }
        snapshots.keys.retainAll(snaps.keys)
        snaps.forEach { (id, s) -> if (snapshots[id] != s) snapshots[id] = s }
        exactAllowed = app.getSystemService(AlarmManager::class.java)?.let(CheckScheduler::canExact) ?: true
        notificationsAllowed = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(app, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
    }

    fun profile(id: Int): CommuteProfile? = profiles.firstOrNull { it.id == id }

    fun save(p: CommuteProfile) {
        // Andere Strecke oder andere Verbindung: der letzte Prüfstand gehört nicht mehr dazu.
        if (profile(p.id)?.trips != p.trips) {
            store.removeSnapshot(p.id)
            snapshots.remove(p.id)
        }
        val i = profiles.indexOfFirst { it.id == p.id }
        if (i >= 0) profiles[i] = p
        store.saveProfile(p)
        CheckScheduler.scheduleNext(getApplication())
        viewModelScope.launch { CommuteWidget.refresh(getApplication()) }
    }

    fun setActive(p: CommuteProfile, active: Boolean) = save(p.copy(active = active))

    fun checkNow(p: CommuteProfile) {
        if (p.id in checking || !p.isComplete) return
        checking = checking + p.id
        viewModelScope.launch {
            try {
                snapshots[p.id] = CommuteRunner.checkNow(getApplication(), p)
            } finally {
                checking = checking - p.id
            }
        }
    }

    /** Die als Nächstes anstehende Fahrt: zuerst unter den aktiven, sonst unter den eingerichteten Profilen. */
    fun upcoming(now: ZonedDateTime = ZonedDateTime.now(ZoneId.systemDefault())): Upcoming? {
        val complete = profiles.filter { it.isComplete && it.weekdays.isNotEmpty() }
        val pool = complete.filter { it.active }.ifEmpty { complete }
        return pool.map { p ->
            val day = CommuteRunner.serviceDate(p, now)
            Upcoming(p, day.atTime(p.departureTime).atZone(now.zone), CheckScheduler.nextCheck(p, now, store.lastHandledCheck(p.id))?.at)
        }.minByOrNull { it.departure }
    }

    /**
     * Gehört die letzte Prüfung zu genau dieser Abfahrt? Ältere Prüfungen (gestern, andere Uhrzeit)
     * werden nicht als aktueller Stand gezeigt. Ohne gefundene Fahrt zählt eine Prüfung der letzten zwei Stunden.
     */
    fun snapshotFor(u: Upcoming): StatusSnapshot? {
        val s = snapshots[u.profile.id] ?: return null
        val target = s.target
        return if (target != null) s.takeIf { kotlin.math.abs(target.plannedDep - u.departure.toEpochSecond()) <= 90 }
        else s.takeIf { System.currentTimeMillis() / 1000 - it.checkedAtEpochSec < 2 * 3600 }
    }
}
