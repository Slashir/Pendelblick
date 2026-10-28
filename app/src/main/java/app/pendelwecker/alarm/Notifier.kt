package app.pendelwecker.alarm

import android.Manifest
import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import app.pendelwecker.MainActivity
import app.pendelwecker.R
import app.pendelwecker.domain.CommuteProfile
import app.pendelwecker.domain.Status
import app.pendelwecker.domain.StatusSnapshot

object Notifier {
    const val CHANNEL_ALARM = "pendel_alarm"
    const val CHANNEL_INFO = "pendel_info"

    fun createChannels(context: Context) {
        val nm = context.getSystemService(NotificationManager::class.java) ?: return
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ALARM, "Ausfälle und Verspätungen", NotificationManager.IMPORTANCE_HIGH)
        )
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_INFO, "Alles pünktlich", NotificationManager.IMPORTANCE_LOW)
        )
    }

    private val PROBLEMS = setOf(Status.AUSFALL, Status.ANSCHLUSS, Status.VERSPAETET)

    /**
     * Nur melden, wenn sich etwas geändert hat – kein Dauerfeuer bei jeder Prüfung. Verglichen wird mit
     * dem Stand derselben Fahrt (gleiche geplante Abfahrt); ein Ausfall von gestern zählt nicht.
     * Verglichen wird das Problem, nicht der Text: Minutenangaben ändern sich bei jeder Prüfung.
     */
    fun shouldNotify(previous: StatusSnapshot?, now: StatusSnapshot, confirmOnTime: Boolean): Boolean {
        val prev = previous?.takeIf { sameTrip(it, now) }
        return when (now.status) {
            Status.AUSFALL, Status.ANSCHLUSS, Status.VERSPAETET -> prev == null || problemKey(prev) != problemKey(now)
            Status.PUENKTLICH -> confirmOnTime || prev?.status in PROBLEMS
            Status.UNBEKANNT, Status.FEHLER -> false
        }
    }

    /** Ersatzverbindung wurde nachgereicht (Routing im Hintergrund): Meldung ergänzen, ohne erneut zu klingeln. */
    fun addsAlternative(previous: StatusSnapshot?, now: StatusSnapshot): Boolean =
        previous != null && sameTrip(previous, now) && now.status in PROBLEMS &&
            problemKey(previous) == problemKey(now) && previous.alternative == null && now.alternative != null

    private fun sameTrip(a: StatusSnapshot, b: StatusSnapshot): Boolean =
        a.target != null && a.target.plannedDep == b.target?.plannedDep

    /** Was für ein Problem: Status, welche Teilfahrt fällt aus bzw. wo wackelt der Umstieg, Verspätung in 5-Minuten-Stufen. */
    private fun problemKey(s: StatusSnapshot): String = when (s.status) {
        Status.AUSFALL -> "A:" + (s.legs.indexOfFirst { it.cancelled }.takeIf { it >= 0 } ?: 0)
        Status.ANSCHLUSS -> "U:" + s.transferProblem?.substringBefore(":")
        Status.VERSPAETET -> "V:" + (s.target?.let { maxOf(it.delayDepMin, it.delayArrMin) / 5 } ?: 0)
        else -> s.status.name
    }

    @SuppressLint("MissingPermission") // wird oben geprüft
    fun show(context: Context, p: CommuteProfile, s: StatusSnapshot, silent: Boolean = false) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return

        val problem = s.status == Status.AUSFALL || s.status == Status.ANSCHLUSS || s.status == Status.VERSPAETET
        val title = when (s.status) {
            Status.AUSFALL -> "${p.label}: Ausfall"
            Status.ANSCHLUSS -> "${p.label}: Anschluss gefährdet"
            Status.VERSPAETET -> "${p.label}: Verspätung"
            else -> "${p.label}: pünktlich"
        }
        val text = s.message + (s.alternative?.let { a ->
            " Stattdessen: ${a.line} ab ${hm(a.realDep)}, an ${hm(a.realArr)}."
        } ?: "")
        val open = PendingIntent.getActivity(
            context, p.id, Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val n = NotificationCompat.Builder(context, if (problem) CHANNEL_ALARM else CHANNEL_INFO)
            .setSmallIcon(R.drawable.ic_train)
            .setColor(0xFF35556F.toInt())
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setSilent(silent)
            .setContentIntent(open)
            .setAutoCancel(true)
            .setPriority(if (problem) NotificationCompat.PRIORITY_HIGH else NotificationCompat.PRIORITY_LOW)
            .build()
        NotificationManagerCompat.from(context).notify(1000 + p.id, n)
    }

    private fun hm(epochSec: Long): String =
        java.time.Instant.ofEpochSecond(epochSec).atZone(java.time.ZoneId.systemDefault())
            .format(java.time.format.DateTimeFormatter.ofPattern("HH:mm"))
}
