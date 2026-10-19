package io.github.open_carrier_data.check.owner

import android.os.Binder
import android.os.IBinder
import android.os.Parcel
import android.os.ParcelFileDescriptor
import android.os.Parcelable
import io.github.open_carrier_data.check.owner.core.OwnerArgs
import io.github.open_carrier_data.check.owner.core.OwnerCommand
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlin.system.exitProcess

/**
 * The calls between the owner app and [OwnerShellService]. The app sends a
 * command name and typed arguments, never a command line; the service builds
 * the argv from [OwnerCommand] again, which checks every argument (and the
 * key guard) on its side too.
 */
internal object OwnerShellProtocol {
    const val DESCRIPTOR = "io.github.open_carrier_data.check.owner.OwnerShell"
    const val RUN = IBinder.FIRST_CALL_TRANSACTION

    /** Shizuku's USER_SERVICE_TRANSACTION_destroy. */
    const val DESTROY = 16777115

    /** At most this much output comes back (the phone dump is the largest, about 1 MB). */
    const val MAX_OUTPUT = 16 * 1024 * 1024

    fun writeArgs(parcel: Parcel, command: OwnerCommand, a: OwnerArgs) {
        parcel.writeString(command.name)
        parcel.writeInt(a.slot ?: -1)
        parcel.writeInt(a.subId ?: -1)
        parcel.writeString(a.key)
        parcel.writeInt(
            when (a.value) {
                true -> 1
                false -> 0
                null -> -1
            },
        )
        parcel.writeString(a.numeric)
        parcel.writeLong(a.id ?: -1)
        parcel.writeInt(a.binds.size)
        for ((k, v) in a.binds) {
            parcel.writeString(k)
            parcel.writeString(v)
        }
    }

    fun readArgs(parcel: Parcel): Pair<OwnerCommand?, OwnerArgs> {
        val command = parcel.readString()?.let { name -> OwnerCommand.entries.firstOrNull { it.name == name } }
        val slot = parcel.readInt().takeIf { it >= 0 }
        val subId = parcel.readInt().takeIf { it >= 0 }
        val key = parcel.readString()
        val value = when (parcel.readInt()) {
            1 -> true
            0 -> false
            else -> null
        }
        val numeric = parcel.readString()
        val id = parcel.readLong().takeIf { it >= 0 }
        val count = parcel.readInt().coerceIn(0, 64)
        val binds = LinkedHashMap<String, String>()
        repeat(count) {
            val k = parcel.readString()
            val v = parcel.readString()
            if (k != null && v != null) binds[k] = v
        }
        return command to OwnerArgs(slot, subId, key, value, numeric, id, binds)
    }
}

/**
 * Shizuku's user service for the owner build: Shizuku starts this class from
 * the APK in a process of its own, as root on the owner's S20. It runs only
 * [OwnerCommand] argv. The output goes back through a pipe, so a large dump
 * fits; the app parses it and keeps only typed fields.
 */
class OwnerShellService : Binder() {
    override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean = when (code) {
        OwnerShellProtocol.RUN -> {
            data.enforceInterface(OwnerShellProtocol.DESCRIPTOR)
            val (command, args) = OwnerShellProtocol.readArgs(data)
            val argv = command?.let { runCatching { it.argv(args) }.getOrNull() }
            val (exit, output) = if (command == null || argv == null) -2 to ByteArray(0) else run(argv, command.timeoutSeconds)
            reply?.writeNoException()
            reply?.writeInt(exit)
            val pipe = ParcelFileDescriptor.createPipe()
            reply?.writeInt(1)
            // Parcel dups the descriptor; the return-value flag closes this side's copy.
            if (reply != null) pipe[0].writeToParcel(reply, Parcelable.PARCELABLE_WRITE_RETURN_VALUE) else pipe[0].close()
            thread(isDaemon = true, name = "owner-shell-out") {
                runCatching { ParcelFileDescriptor.AutoCloseOutputStream(pipe[1]).use { it.write(output) } }
            }
            true
        }
        OwnerShellProtocol.DESTROY -> exitProcess(0)
        else -> super.onTransact(code, data, reply, flags)
    }

    /** The only place in the owner build that starts a process on the Shizuku route. */
    private fun run(argv: List<String>, timeoutSeconds: Long): Pair<Int, ByteArray> {
        val process = try {
            ProcessBuilder(argv).redirectErrorStream(true).start()
        } catch (_: IOException) {
            return -3 to ByteArray(0)
        }
        val out = ByteArrayOutputStream()
        val reader = thread(isDaemon = true, name = "owner-shell-read") {
            runCatching {
                process.inputStream.use { input ->
                    val buffer = ByteArray(1 shl 16)
                    while (true) {
                        val n = input.read(buffer)
                        if (n < 0) break
                        if (out.size() + n <= OwnerShellProtocol.MAX_OUTPUT) out.write(buffer, 0, n)
                    }
                }
            }
        }
        runCatching { process.outputStream.close() }
        val done = process.waitFor(timeoutSeconds, TimeUnit.SECONDS)
        if (!done) process.destroyForcibly()
        reader.join(5_000)
        return (if (done) process.exitValue() else -4) to out.toByteArray()
    }
}
