package it.diy.hudnav

import android.content.Context
import android.os.Handler
import android.os.Looper
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock

/**
 * Un solo collegamento BLE. La ricerca della scheda non tiene in coda
 * gli aggiornamenti di posizione: se il collegamento è occupato, la write si salta.
 */
object HudLink {
    private val io = Executors.newSingleThreadExecutor()
    private val link = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private val gate = ReentrantLock()
    private lateinit var ble: HudBle

    @Volatile
    var connected = false
        private set

    fun init(ctx: Context) {
        if (::ble.isInitialized) return
        ble = HudBle(ctx.applicationContext)
    }

    fun <T> call(fallback: T, timeoutSec: Long = 8, block: (HudBle) -> T): T {
        check(::ble.isInitialized)
        return try {
            io.submit(Callable {
                if (!gate.tryLock(80, TimeUnit.MILLISECONDS)) return@Callable fallback
                try {
                    if (!ble.isReady()) {
                        connected = false
                        return@Callable fallback
                    }
                    val value = block(ble)
                    connected = ble.isReady()
                    value
                } finally {
                    gate.unlock()
                }
            }).get(timeoutSec, TimeUnit.SECONDS)
        } catch (_: Exception) {
            fallback
        }
    }

    /** Collegamento su un thread a parte. Le write non lo aspettano. */
    fun connect(timeoutMs: Long): Boolean {
        if (!::ble.isInitialized) return false
        if (connected && ble.isReady()) return true
        return try {
            link.submit(Callable {
                if (!gate.tryLock(timeoutMs, TimeUnit.MILLISECONDS)) return@Callable false
                try {
                    if (ble.isReady()) {
                        connected = true
                        return@Callable true
                    }
                    connected = ble.connectBlocking(timeoutMs)
                    connected
                } finally {
                    gate.unlock()
                }
            }).get(timeoutMs + 1000, TimeUnit.MILLISECONDS)
        } catch (_: Exception) {
            false
        }
    }

    fun control(cmd: Int, arg: Int, done: (Boolean) -> Unit) {
        check(::ble.isInitialized)
        link.execute {
            val ok = try {
                if (!gate.tryLock(4000, TimeUnit.MILLISECONDS)) {
                    false
                } else {
                    try {
                        if (!ble.isReady()) connected = ble.connectBlocking(4000)
                        val wrote = ble.isReady() && ble.writeBlocking(HudBle.ctrl(cmd, arg))
                        connected = ble.isReady()
                        wrote
                    } finally {
                        gate.unlock()
                    }
                }
            } catch (_: Exception) {
                false
            }
            main.post { done(ok) }
        }
    }

    fun close() {
        if (!::ble.isInitialized) return
        link.execute {
            if (gate.tryLock(1500, TimeUnit.MILLISECONDS)) {
                try {
                    ble.close()
                } finally {
                    gate.unlock()
                }
            }
            connected = false
        }
    }
}
