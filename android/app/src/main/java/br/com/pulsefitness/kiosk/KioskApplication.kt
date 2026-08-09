package br.com.pulsefitness.kiosk

import android.app.Application
import br.com.pulsefitness.kiosk.sync.SyncWorker

class KioskApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        // Drain anything left in the offline queue from before a restart.
        SyncWorker.enqueue(this)
    }
}
