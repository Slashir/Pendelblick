package app.pendelwecker

import android.app.Application
import app.pendelwecker.alarm.CheckScheduler
import app.pendelwecker.alarm.Notifier

class PendlerApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Notifier.createChannels(this)
        CheckScheduler.scheduleNext(this)
    }
}
