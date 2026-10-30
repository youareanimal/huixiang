package io.github.korppujauho.pocketts

import android.content.Context
import android.os.Handler
import android.os.Looper
import io.github.korppujauho.pocketts.proto.Identity
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update

/**
 * Raising the identity's security level, which some servers ask for: a one-time job, done while its dialog is open
 * (ui/RaiseLevelDialog), on all but one of the phone's cores. Stopping keeps the level reached so far.
 */
object IdentityRaise {
    /** Where raising is: the level reached, the one aimed at, and whether it's still going. */
    data class Progress(val level: Int, val target: Int, val running: Boolean)

    private val _progress = MutableStateFlow<Progress?>(null)
    val progress: StateFlow<Progress?> = _progress

    @Volatile private var stopping = false

    private val threads: Int get() = (Runtime.getRuntime().availableProcessors() - 1).coerceAtLeast(1)

    /** The saved identity's level (null when it can't be read). */
    fun level(context: Context): Int? = try {
        Settings(context).identity().securityLevel
    } catch (e: Settings.IdentityUnreadable) {
        null
    }

    /** Tries a second on this phone, measured briefly (blocking: call it off the main thread). */
    fun triesPerSecond(): Double = Identity.triesPerSecond(millis = 400, threads = threads)

    /** Starts raising to [target]; [onDone] (on the main thread) hears whether it got there. */
    fun start(context: Context, target: Int, onDone: (Boolean) -> Unit = {}) {
        if (_progress.value?.running == true) return
        val settings = Settings(context.applicationContext)
        val id = try {
            settings.identity()
        } catch (e: Settings.IdentityUnreadable) {
            return
        }
        stopping = false
        _progress.value = Progress(id.securityLevel, target, running = true)
        Thread({
            val started = System.currentTimeMillis()
            val raised = id.improvedTo(target, cancelled = { stopping }, threads = threads) { level ->
                _progress.update { it?.copy(level = level) }
            }
            if (raised.securityLevel > id.securityLevel) settings.saveIdentity(raised)
            AppLog.log(
                "Security level ${id.securityLevel} -> ${raised.securityLevel} (aimed at $target) in " +
                    "${(System.currentTimeMillis() - started) / 1000} s" + if (stopping) ", stopped" else ""
            )
            _progress.value = Progress(raised.securityLevel, target, running = false)
            Handler(Looper.getMainLooper()).post { onDone(raised.securityLevel >= target) }
        }, "identity-raise").start()
    }

    fun stop() {
        stopping = true
    }

    /** Forgets a finished raise (its dialog closed). */
    fun clear() {
        if (_progress.value?.running != true) _progress.value = null
    }
}
