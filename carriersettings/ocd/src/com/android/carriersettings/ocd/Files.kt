/*
 * SPDX-FileCopyrightText: Open Carrier Data
 * SPDX-License-Identifier: Apache-2.0
 */

package com.android.carriersettings.ocd

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.security.MessageDigest

/** Hashes and file helpers. Plain java.io and java.security only. */
object Files {
    fun hex(bytes: ByteArray): String {
        val out = CharArray(bytes.size * 2)
        val digits = "0123456789abcdef"
        for (i in bytes.indices) {
            val b = bytes[i].toInt() and 0xff
            out[2 * i] = digits[b ushr 4]
            out[2 * i + 1] = digits[b and 0x0f]
        }
        return String(out)
    }

    fun sha256(bytes: ByteArray): String = hex(MessageDigest.getInstance("SHA-256").digest(bytes))

    fun sha256(text: String): String = sha256(text.toByteArray(Charsets.UTF_8))

    fun sha512(bytes: ByteArray): String = hex(MessageDigest.getInstance("SHA-512").digest(bytes))

    /** SHA-256, SHA-512 and git's blob SHA-1 of one file, read once. */
    class Digests(val bytes: Long, val sha256: String, val sha512: String, val blob: String)

    fun digests(file: File): Digests {
        val len = file.length()
        val s256 = MessageDigest.getInstance("SHA-256")
        val s512 = MessageDigest.getInstance("SHA-512")
        val s1 = MessageDigest.getInstance("SHA-1")
        s1.update("blob $len\u0000".toByteArray(Charsets.US_ASCII))
        var total = 0L
        FileInputStream(file).use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buffer)
                if (n < 0) break
                s256.update(buffer, 0, n)
                s512.update(buffer, 0, n)
                s1.update(buffer, 0, n)
                total += n
            }
        }
        if (total != len) throw IOException("${file.name} changed while it was read")
        return Digests(len, hex(s256.digest()), hex(s512.digest()), hex(s1.digest()))
    }

    /** git's blob SHA-1: sha1("blob " + len + "\0" + bytes). */
    fun gitBlob(bytes: ByteArray): String {
        val s1 = MessageDigest.getInstance("SHA-1")
        s1.update("blob ${bytes.size}\u0000".toByteArray(Charsets.US_ASCII))
        return hex(s1.digest(bytes))
    }

    /** At most [max] bytes; one more byte means "over the cap" to the caller. */
    fun readBounded(file: File, max: Long): ByteArray = FileInputStream(file).use { readBounded(it, max) }

    fun readBounded(input: InputStream, max: Long): ByteArray {
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(64 * 1024)
        while (out.size() < max) {
            val n = input.read(buffer, 0, minOf(buffer.size.toLong(), max - out.size()).toInt())
            if (n < 0) break
            out.write(buffer, 0, n)
        }
        return out.toByteArray()
    }

    /**
     * Copies [input] to [target], at most [cap] bytes. Returns the bytes copied, or -1 when the
     * input holds more than [cap] (the partial copy is deleted).
     */
    fun copyBounded(input: InputStream, target: File, cap: Long): Long {
        target.parentFile?.mkdirs()
        var total = 0L
        FileOutputStream(target).use { out ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buffer)
                if (n < 0) break
                total += n
                if (total > cap) {
                    out.close()
                    target.delete()
                    return -1
                }
                out.write(buffer, 0, n)
            }
            out.fd.sync()
        }
        return total
    }

    /** A temp file, an fsync, a rename and a folder fsync, as round 29's LayerStore.atomicWrite. */
    fun atomicWrite(target: File, bytes: ByteArray) {
        target.parentFile?.mkdirs()
        val tmp = File(target.parentFile, target.name + ".tmp")
        FileOutputStream(tmp).use { out ->
            out.write(bytes)
            out.fd.sync()
        }
        if (!tmp.renameTo(target)) {
            tmp.delete()
            throw IOException("could not rename ${tmp.name}")
        }
        syncDir(target.absoluteFile.parentFile)
    }

    fun syncDir(dir: File?) {
        dir ?: return
        runCatching {
            java.nio.channels.FileChannel.open(dir.toPath(), java.nio.file.StandardOpenOption.READ).use { it.force(true) }
        }
    }

    fun deleteTree(file: File) {
        if (!file.exists()) return
        if (file.isDirectory) file.listFiles()?.forEach { deleteTree(it) }
        file.delete()
    }
}
