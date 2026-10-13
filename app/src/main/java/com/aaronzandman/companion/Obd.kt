package com.aaronzandman.companion

import android.annotation.SuppressLint
import android.bluetooth.*
import android.content.Context
import android.os.Build
import org.json.JSONArray
import org.json.JSONObject
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * Reads the vehicle odometer through an ELM327-compatible adapter (Vgate vLinker FD+ and similar),
 * over regular Bluetooth (paired, SPP) or Bluetooth LE.
 * Odometer: standard PID 01 A6 (4 bytes, 0.1 km) → miles = raw / 16.09344  (same as the owner's Torque PID
 * "Ford Transit Odometer 2020+"). Fallback: Ford 22 DD01 via header 7E0 (3 bytes, km).
 */
object Obd {
    private val SPP: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")
    private val CCCD: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
    // Known adapter characteristics (Vgate vLinker, common FFF0/FFE0 clones).
    private val KNOWN = listOf(
        "bef8d6c9-9c21-4c9e-b632-bd58c1009f9f",
        "0000fff1-0000-1000-8000-00805f9b34fb", "0000fff2-0000-1000-8000-00805f9b34fb",
        "0000ffe1-0000-1000-8000-00805f9b34fb"
    )
    private val lock = Any()

    interface Link { fun send(cmd: String, timeoutMs: Long = 4000): String; fun close() }

    // ---------------- regular Bluetooth (SPP) ----------------
    @SuppressLint("MissingPermission")
    private class Classic(dev: BluetoothDevice) : Link {
        private val sock = dev.createRfcommSocketToServiceRecord(SPP)
        private val inp: InputStream; private val out: OutputStream
        init {
            BluetoothAdapter.getDefaultAdapter()?.cancelDiscovery()
            sock.connect(); inp = sock.inputStream; out = sock.outputStream
        }
        override fun send(cmd: String, timeoutMs: Long): String {
            while (inp.available() > 0) inp.read()
            out.write((cmd + "\r").toByteArray()); out.flush()
            val sb = StringBuilder(); val end = System.currentTimeMillis() + timeoutMs
            while (System.currentTimeMillis() < end) {
                if (inp.available() > 0) { val ch = inp.read().toChar(); if (ch == '>') break; sb.append(ch) }
                else Thread.sleep(15)
            }
            return sb.toString()
        }
        override fun close() { try { sock.close() } catch (_: Exception) {} }
    }

    // ---------------- Bluetooth LE ----------------
    @SuppressLint("MissingPermission")
    private class Ble(ctx: Context, dev: BluetoothDevice) : Link {
        private val q = LinkedBlockingQueue<String>()
        private val ready = LinkedBlockingQueue<Boolean>()
        private var tx: BluetoothGattCharacteristic? = null
        private val gatt: BluetoothGatt
        private val cb = object : BluetoothGattCallback() {
            override fun onConnectionStateChange(g: BluetoothGatt, status: Int, state: Int) {
                if (state == BluetoothProfile.STATE_CONNECTED) g.discoverServices() else ready.offer(false)
            }
            override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
                val chars = g.services.flatMap { it.characteristics }
                fun can(c: BluetoothGattCharacteristic, p: Int) = c.properties and p != 0
                val notify = chars.sortedBy { k -> KNOWN.indexOf(k.uuid.toString()).let { if (it < 0) 99 else it } }
                    .firstOrNull { can(it, BluetoothGattCharacteristic.PROPERTY_NOTIFY) || can(it, BluetoothGattCharacteristic.PROPERTY_INDICATE) }
                val write = (listOfNotNull(notify) + chars.sortedBy { k -> KNOWN.indexOf(k.uuid.toString()).let { if (it < 0) 99 else it } })
                    .firstOrNull { can(it, BluetoothGattCharacteristic.PROPERTY_WRITE) || can(it, BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE) }
                if (notify == null || write == null) { ready.offer(false); return }
                tx = write
                g.setCharacteristicNotification(notify, true)
                val d = notify.getDescriptor(CCCD)
                if (d == null) { ready.offer(true); return }
                val v = if (can(notify, BluetoothGattCharacteristic.PROPERTY_NOTIFY)) BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE else BluetoothGattDescriptor.ENABLE_INDICATION_VALUE
                if (Build.VERSION.SDK_INT >= 33) g.writeDescriptor(d, v)
                else { @Suppress("DEPRECATION") run { d.value = v; g.writeDescriptor(d) } }
            }
            override fun onDescriptorWrite(g: BluetoothGatt, d: BluetoothGattDescriptor, status: Int) { ready.offer(true) }
            override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic, value: ByteArray) { q.offer(String(value)) }
            @Deprecated("Deprecated in Java")
            override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic) {
                if (Build.VERSION.SDK_INT < 33) { @Suppress("DEPRECATION") q.offer(String(c.value ?: ByteArray(0))) }
            }
        }
        init {
            gatt = dev.connectGatt(ctx, false, cb, BluetoothDevice.TRANSPORT_LE)
            if (ready.poll(15, TimeUnit.SECONDS) != true) { close(); throw IllegalStateException("Couldn't open the adapter over Bluetooth LE") }
        }
        override fun send(cmd: String, timeoutMs: Long): String {
            q.clear()
            val ch = tx ?: return ""
            val bytes = (cmd + "\r").toByteArray()
            val type = if (ch.properties and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE != 0) BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE else BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
            for (i in bytes.indices step 20) {
                val part = bytes.copyOfRange(i, minOf(bytes.size, i + 20))
                if (Build.VERSION.SDK_INT >= 33) gatt.writeCharacteristic(ch, part, type)
                else { @Suppress("DEPRECATION") run { ch.writeType = type; ch.value = part; gatt.writeCharacteristic(ch) } }
                Thread.sleep(30)
            }
            val sb = StringBuilder(); val end = System.currentTimeMillis() + timeoutMs
            while (System.currentTimeMillis() < end) {
                val s = q.poll(100, TimeUnit.MILLISECONDS) ?: continue
                sb.append(s); if (s.contains('>')) break
            }
            return sb.toString().substringBefore('>')
        }
        override fun close() { try { gatt.disconnect(); gatt.close() } catch (_: Exception) {} }
    }

    // ---------------- reading the odometer ----------------
    private fun hex(s: String) = s.uppercase().replace(Regex("[^0-9A-F]"), "")

    @SuppressLint("MissingPermission")
    private fun open(ctx: Context): Link {
        val addr = Store.get(ctx, "obdAddr", "")
        require(addr.isNotEmpty()) { "No adapter chosen" }
        val bt = ctx.getSystemService(BluetoothManager::class.java)?.adapter ?: error("Bluetooth is not available")
        require(bt.isEnabled) { "Bluetooth is off" }
        val dev = bt.getRemoteDevice(addr)
        val type = Store.get(ctx, "obdType", "auto")
        if (type != "ble") {
            try { return Classic(dev) } catch (e: Exception) { if (type == "classic") throw e }
        }
        return Ble(ctx, dev)
    }

    /** Returns the odometer in miles, or null. [log] collects what happened, for the test screen. */
    fun readMiles(ctx: Context, log: StringBuilder? = null): Double? {
      synchronized(lock) {
        var link: Link? = null
        try {
            link = open(ctx)
            for (c in listOf("ATZ", "ATE0", "ATL0", "ATS0", "ATH0", "ATSP0")) {
                val r = link.send(c, if (c == "ATZ") 3000 else 2000); log?.append("$c → ${r.trim().replace("\r", " ")}\n")
                if (c == "ATZ") Thread.sleep(300)
            }
            // Standard odometer (Ford Transit 2020+): 41 A6 AA BB CC DD → (AABBCCDD) / 10 km
            val r1 = hex(link.send("01A6", 8000)); log?.append("01A6 → $r1\n")
            Regex("41A6([0-9A-F]{8})").find(r1)?.let { m ->
                val raw = m.groupValues[1].toLong(16); if (raw > 0) return raw / 16.09344
            }
            // Ford fallback: 62 DD01 AA BB CC → km
            link.send("ATSH7E0", 2000)
            val r2 = hex(link.send("22DD01", 8000)); log?.append("22DD01 → $r2\n")
            Regex("62DD01([0-9A-F]{6})").find(r2)?.let { m ->
                val km = m.groupValues[1].toLong(16); if (km > 0) return km / 1.609344
            }
            return null
        } catch (e: Exception) { log?.append("Error: ${e.message}\n"); return null }
        finally { link?.close() }
      }
    }

    // ---------------- odometer log ----------------
    /** Saves a reading taken at time t (ms). Kept about 60 days. */
    fun record(ctx: Context, t: Long, miles: Double) {
        val a = JSONArray(Store.get(ctx, "odoLog", "[]")); val out = JSONArray()
        val cut = System.currentTimeMillis() - 60L * 86_400_000L
        for (i in 0 until a.length()) { val o = a.getJSONObject(i); if (o.optLong("t") >= cut) out.put(o) }
        out.put(JSONObject().put("t", t).put("mi", Math.round(miles * 10) / 10.0))
        Store.put(ctx, "odoLog", out.toString())
        Store.put(ctx, "odoLast", JSONObject().put("t", t).put("mi", Math.round(miles * 10) / 10.0).toString())
    }

    fun log(ctx: Context): JSONArray = JSONArray(Store.get(ctx, "odoLog", "[]"))
    fun enabled(ctx: Context) = Tier.obd(ctx) && Store.get(ctx, "obdAddr", "").isNotEmpty()
}
