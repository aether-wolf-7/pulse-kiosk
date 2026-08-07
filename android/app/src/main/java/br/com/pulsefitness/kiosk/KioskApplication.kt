package br.com.pulsefitness.kiosk

import android.app.Application

class KioskApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        // Stage 2: initialize Room DB + WorkManager sync queue here.
    }
}
