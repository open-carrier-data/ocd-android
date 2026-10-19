package io.github.open_carrier_data.check.phone

import android.os.Binder
import android.os.IBinder
import android.os.Parcel
import io.github.open_carrier_data.check.core.CallWatch
import io.github.open_carrier_data.check.core.CallWatchSummary
import io.github.open_carrier_data.check.core.CommandRunner
import io.github.open_carrier_data.check.core.DeepCheck
import io.github.open_carrier_data.check.core.DeepCheckTarget
import io.github.open_carrier_data.check.core.HomeNetwork
import io.github.open_carrier_data.check.core.RegistryParser
import io.github.open_carrier_data.check.core.ShellCommand
import io.github.open_carrier_data.check.core.SimSlot
import java.io.IOException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread
import kotlin.system.exitProcess

/**
 * The calls between the app and [DeepCheckService]. The app sends a slot
 * number and the home network digits, never a command; the service checks
 * both again and runs only [ShellCommand] entries.
 */
internal object DeepCheckProtocol {
    const val DESCRIPTOR = "io.github.open_carrier_data.check.DeepCheck"
    const val RUN = IBinder.FIRST_CALL_TRANSACTION

    /** Start watching one slot's call state (the guided call test), with the mailbox short codes an incoming number is compared with. */
    const val WATCH_START = IBinder.FIRST_CALL_TRANSACTION + 1

    /** The watch so far, without stopping it. */
    const val WATCH_READ = IBinder.FIRST_CALL_TRANSACTION + 2

    /** Stop the watch and return what it saw. */
    const val WATCH_STOP = IBinder.FIRST_CALL_TRANSACTION + 3

    /** Shizuku's USER_SERVICE_TRANSACTION_destroy, which the library marks for its own use; AllowlistTest pins it. */
    const val DESTROY = 16777115

    /** The typed result as JSON ([io.github.open_carrier_data.check.core.DeepCheckReading]), or null. */
    fun call(binder: IBinder, target: DeepCheckTarget): String? =
        transact(binder, RUN, {
            writeInt(target.slot.index)
            writeString(target.home?.mccmnc)
        }) { readString() }

    fun watchStart(binder: IBinder, slot: SimSlot, mailboxes: List<String>): Boolean =
        transact(binder, WATCH_START, {
            writeInt(slot.index)
            writeStringList(mailboxes)
        }) { readInt() == 1 }

    /** Mailbox short codes: 3 to 6 ASCII digits each, at most [MAX_MAILBOXES]. Anything else is dropped. */
    fun mailboxCodes(codes: List<String>?): Set<String> =
        codes.orEmpty().filter(MAILBOX_SHAPE::matches).take(MAX_MAILBOXES).toSet()

    private val MAILBOX_SHAPE = Regex("[0-9]{3,6}")
    private const val MAX_MAILBOXES = 32

    fun watchRead(binder: IBinder): CallWatchSummary? =
        transact(binder, WATCH_READ, {}) { readString() }?.let(CallWatchSummary::decode)

    fun watchStop(binder: IBinder): CallWatchSummary? =
        transact(binder, WATCH_STOP, {}) { readString() }?.let(CallWatchSummary::decode)

    private inline fun <T> transact(binder: IBinder, code: Int, write: Parcel.() -> Unit, read: Parcel.() -> T): T {
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        try {
            data.writeInterfaceToken(DESCRIPTOR)
            data.write()
            binder.transact(code, data, reply, 0)
            reply.readException()
            return reply.read()
        } finally {
            data.recycle()
            reply.recycle()
        }
    }
}

/**
 * Shizuku's user service: Shizuku starts this class from the app's APK in a
 * process of its own, as the shell user (or root). It parses each command's
 * output in that process, so only typed results reach the app.
 */
class DeepCheckService : Binder() {
    override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean = when (code) {
        DeepCheckProtocol.RUN -> {
            data.enforceInterface(DeepCheckProtocol.DESCRIPTOR)
            val slot = SimSlot.of(data.readInt())
            val home = HomeNetwork.of(data.readString())
            val result = slot?.let { DeepCheck.run(DeepCheckTarget(it, home), ProcessRunner).encode() }
            reply?.writeNoException()
            reply?.writeString(result)
            true
        }
        DeepCheckProtocol.WATCH_START -> {
            data.enforceInterface(DeepCheckProtocol.DESCRIPTOR)
            val slot = SimSlot.of(data.readInt())
            val mailboxes = DeepCheckProtocol.mailboxCodes(data.createStringArrayList())
            val started = slot?.let { CallWatcher.start(it, mailboxes) } ?: false
            reply?.writeNoException()
            reply?.writeInt(if (started) 1 else 0)
            true
        }
        DeepCheckProtocol.WATCH_READ, DeepCheckProtocol.WATCH_STOP -> {
            data.enforceInterface(DeepCheckProtocol.DESCRIPTOR)
            val summary = if (code == DeepCheckProtocol.WATCH_STOP) CallWatcher.stop() else CallWatcher.read()
            reply?.writeNoException()
            reply?.writeString(summary?.encode())
            true
        }
        DeepCheckProtocol.DESTROY -> exitProcess(0)
        else -> super.onTransact(code, data, reply, flags)
    }
}

/**
 * The guided call test's watcher, in the deep check's process: it reads the
 * registry once a second for one slot and keeps only [CallWatch]'s counts.
 * It runs here, not in the app, so it keeps going while the person is in the
 * phone app and this app sits in the background. It never touches a call.
 * The incoming number is compared with the mailbox short codes inside
 * [RegistryParser]; only whether it is one of them reaches [CallWatch].
 */
internal object CallWatcher {
    private const val INTERVAL_MS = 1_000L
    private const val LIMIT_MS = 15 * 60 * 1_000L

    private var watch: CallWatch? = null
    private var thread: Thread? = null

    @Volatile
    private var stopping = false

    @Synchronized
    fun start(slot: SimSlot, mailboxes: Set<String>): Boolean {
        if (thread?.isAlive == true) return false
        val current = CallWatch()
        val target = DeepCheckTarget(slot, null)
        watch = current
        stopping = false
        thread = thread(isDaemon = true, name = "call-watch") {
            val started = System.currentTimeMillis()
            while (!stopping) {
                if (System.currentTimeMillis() - started > LIMIT_MS) {
                    current.markStoppedByLimit()
                    break
                }
                current.add(ProcessRunner.run(ShellCommand.TELEPHONY_REGISTRY, target) { RegistryParser.parse(it, slot, mailboxes)?.call })
                try {
                    Thread.sleep(INTERVAL_MS)
                } catch (_: InterruptedException) {
                    break
                }
            }
        }
        return true
    }

    @Synchronized
    fun read(): CallWatchSummary? = watch?.summary()

    @Synchronized
    fun stop(): CallWatchSummary? {
        stopping = true
        thread?.let {
            it.interrupt()
            it.join(5_000)
        }
        thread = null
        return watch?.summary().also { watch = null }
    }
}

/** Starts the argv of one [ShellCommand]; the only place in the app that starts a process. */
internal object ProcessRunner : CommandRunner {
    override fun <T> run(command: ShellCommand, target: DeepCheckTarget, read: (Sequence<String>) -> T): T? {
        val argv = command.argv(target) ?: return null
        val process = try {
            ProcessBuilder(argv).redirectErrorStream(true).start()
        } catch (_: IOException) {
            return null
        }
        val timedOut = AtomicBoolean(false)
        val watchdog = thread(isDaemon = true, name = "deep-check-timeout") {
            try {
                if (!process.waitFor(command.timeoutSeconds, TimeUnit.SECONDS)) {
                    timedOut.set(true)
                    process.destroyForcibly()
                }
            } catch (_: InterruptedException) {
            }
        }
        return try {
            process.outputStream.close()
            val result = process.inputStream.bufferedReader().use { reader ->
                val lines = generateSequence { reader.readLine() }
                read(if (command.firstLineOnly) lines.take(1) else lines)
            }
            if (timedOut.get()) null else result
        } catch (_: IOException) {
            null
        } catch (_: RuntimeException) {
            null
        } finally {
            process.destroy()
            watchdog.interrupt()
        }
    }
}
