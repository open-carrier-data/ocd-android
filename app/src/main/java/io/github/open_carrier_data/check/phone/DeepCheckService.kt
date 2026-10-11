package io.github.open_carrier_data.check.phone

import android.os.Binder
import android.os.IBinder
import android.os.Parcel
import io.github.open_carrier_data.check.core.CommandRunner
import io.github.open_carrier_data.check.core.DeepCheck
import io.github.open_carrier_data.check.core.DeepCheckTarget
import io.github.open_carrier_data.check.core.HomeNetwork
import io.github.open_carrier_data.check.core.ShellCommand
import io.github.open_carrier_data.check.core.SimSlot
import java.io.IOException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread
import kotlin.system.exitProcess

/**
 * The two calls between the app and [DeepCheckService]. The app sends a slot
 * number and the home network digits, never a command; the service checks
 * both again and runs only [ShellCommand] entries.
 */
internal object DeepCheckProtocol {
    const val DESCRIPTOR = "io.github.open_carrier_data.check.DeepCheck"
    const val RUN = IBinder.FIRST_CALL_TRANSACTION

    /** Shizuku's USER_SERVICE_TRANSACTION_destroy, which the library marks for its own use; AllowlistTest pins it. */
    const val DESTROY = 16777115

    /** The typed result as JSON ([io.github.open_carrier_data.check.core.DeepCheckReading]), or null. */
    fun call(binder: IBinder, target: DeepCheckTarget): String? {
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        try {
            data.writeInterfaceToken(DESCRIPTOR)
            data.writeInt(target.slot.index)
            data.writeString(target.home?.mccmnc)
            binder.transact(RUN, data, reply, 0)
            reply.readException()
            return reply.readString()
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
        DeepCheckProtocol.DESTROY -> exitProcess(0)
        else -> super.onTransact(code, data, reply, flags)
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
