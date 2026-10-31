package io.embodit.dronesight.debug

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import io.embodit.dronesight.platform.DsLog

/**
 * Debug builds only. Forwards adb broadcasts to the running Stage (action `io.embodit.dronesight.DEBUG`); see
 * [DebugCommands] and AGENTS.md for the command list.
 *
 *   adb -s emulator-5556 shell "am broadcast -a io.embodit.dronesight.DEBUG -p io.embodit.dronesight --es cmd 'lesson L1'"
 *
 * Extras: `cmd` = command name; its argument may follow the name inside `cmd` (`--es cmd 'fly gate 2'`) or come as `s`.
 */
class DebugCommandReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val raw = intent.getStringExtra("cmd") ?: return
        val parts = raw.trim().split(Regex("\\s+"), limit = 2)
        val cmd = parts[0]
        val s = intent.getStringExtra("s") ?: parts.getOrNull(1)
        val a = intent.getStringExtra("a")
        DsLog.i("CMD recv '$cmd' s='$s'")
        // broadcasts arrive on the main thread, which is where the controller and the scene live
        DebugCommands.run(context.applicationContext, cmd, s, a)
    }
}
