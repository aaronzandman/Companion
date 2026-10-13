package com.aaronzandman.companion

import android.content.Context
import java.security.MessageDigest

/**
 * Feature levels. One app; extra features unlock with a code entered in Settings.
 *  1 Companion   – GPS mileage (plus the odometer you enter)
 *  2 Companion+  – adds the OBD odometer adapter
 *  3 Companion++ – adds the Ford connection and the oil life meter
 * Only fingerprints (SHA-256) of the codes are kept here, never the codes themselves.
 */
object Tier {
    private const val SALT = "companion-unlock-v1|"
    private const val PLUS = "1dd2688d4adb3638daf55d70090d83612980987aca8e39b4b0c8f62191eb7764"
    private const val PLUS_PLUS = "6003a1ed58e7a92a3031c7d61b9820b3ebf1e6d2ecd2701c36b1a8e67cf27b3a"

    fun level(c: Context) = Store.get(c, "tier", "1").toIntOrNull()?.coerceIn(1, 3) ?: 1
    fun name(level: Int) = when (level) { 3 -> "Companion++"; 2 -> "Companion+"; else -> "Companion" }
    fun obd(c: Context) = level(c) >= 2
    fun ford(c: Context) = level(c) >= 3

    /** Checks a code. Returns the new level (2 or 3), or 0 if the code is wrong. */
    fun unlock(c: Context, code: String): Int {
        val d = MessageDigest.getInstance("SHA-256").digest((SALT + code.trim()).toByteArray()).joinToString("") { "%02x".format(it) }
        val lvl = when (d) { PLUS_PLUS -> 3; PLUS -> 2; else -> 0 }
        if (lvl > 0) Store.put(c, "tier", lvl.toString())
        return lvl
    }

    fun reset(c: Context) = Store.put(c, "tier", "1")
}
