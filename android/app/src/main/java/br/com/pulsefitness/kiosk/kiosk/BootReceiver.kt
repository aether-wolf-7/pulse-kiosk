package br.com.pulsefitness.kiosk.kiosk

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import br.com.pulsefitness.kiosk.MainActivity
import br.com.pulsefitness.kiosk.sync.SyncWorker

/**
 * Brings the tablet back by itself after a power cut, which in a gym is a
 * matter of when, not if.
 *
 * Owning the HOME role usually covers this on its own, but not always: a
 * tablet that has not finished provisioning, or one where the persistent
 * preferred activity was cleared for maintenance, would otherwise sit on the
 * stock launcher until someone noticed. Starting explicitly is cheap
 * insurance, and it also gets the offline queue draining immediately rather
 * than whenever a student happens to walk up.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED &&
            intent.action != Intent.ACTION_LOCKED_BOOT_COMPLETED
        ) {
            return
        }
        SyncWorker.enqueue(context)
        val launch = Intent(context, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        runCatching { context.startActivity(launch) }
    }
}
