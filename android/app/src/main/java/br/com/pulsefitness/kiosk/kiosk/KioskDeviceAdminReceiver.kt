package br.com.pulsefitness.kiosk.kiosk

import android.app.admin.DeviceAdminReceiver

/**
 * Stage 3: the app is set as Device Owner via ADB during tablet provisioning
 * (`dpm set-device-owner`), which unlocks Lock Task Mode (true kiosk: home
 * button, status bar and recents blocked). Empty subclass is enough to be
 * a valid admin component.
 */
class KioskDeviceAdminReceiver : DeviceAdminReceiver()
