package com.aaronzandman.btdiag

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothProfile
import android.content.Context
import java.io.InputStream
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** "Test connect": tries the adapter both ways (Bluetooth 3.0 classic and Bluetooth LE) and logs what answers. */
@SuppressLint("MissingPermission")
object Probe {
    private val SPP = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")
    private val CCCD = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
    private val CMDS = listOf("ATZ", "ATE0", "ATI", "ATRV")
    @Volatile var busy = false

    fun run(c: Context, addr: String) {
        if (busy) { L.w("TEST", "a test is already running"); return }
        val a = Env.adapter(c) ?: run { L.w("TEST", "no Bluetooth"); return }
        if (!a.isEnabled) { L.w("TEST", "Bluetooth is OFF"); return }
        val d = try { a.getRemoteDevice(addr) } catch (e: Exception) { L.w("TEST", "bad address $addr: $e"); return }
        busy = true
        Thread {
            try {
                L.w("TEST", "===== Test connect: ${Env.who(d)} =====")
                try { a.cancelDiscovery() } catch (_: Exception) {}
                val t = try { d.type } catch (_: Exception) { BluetoothDevice.DEVICE_TYPE_UNKNOWN }
                if (t != BluetoothDevice.DEVICE_TYPE_LE) classic(d) else L.w("TEST", "skipping classic: Android knows this address as LE-only")
                if (t != BluetoothDevice.DEVICE_TYPE_CLASSIC) ble(c, d) else L.w("TEST", "skipping LE: Android knows this address as classic-only")
                L.w("TEST", "===== Test finished =====")
            } catch (e: Exception) { L.w("TEST", "test crashed: $e") } finally { busy = false }
        }.start()
    }

    private fun readReply(inp: InputStream, ms: Long): String {
        val sb = StringBuilder(); val end = System.currentTimeMillis() + ms
        while (System.currentTimeMillis() < end) {
            if (inp.available() > 0) { val b = inp.read(); if (b < 0) break; sb.append(b.toChar()); if (b == '>'.code) break }
            else Thread.sleep(30)
        }
        return clean(sb.toString())
    }
    private fun clean(s: String) = s.replace("\r", " ").replace("\n", " ").replace(Regex(" +"), " ").trim().ifBlank { "(no reply)" }

    private fun classic(d: BluetoothDevice) {
        L.w("TEST", "Classic (Bluetooth 3.0, serial port) connecting…")
        val t0 = System.currentTimeMillis()
        var s = try { d.createRfcommSocketToServiceRecord(SPP) } catch (e: Exception) { L.w("TEST", "classic socket error: $e"); null } ?: return
        try { s.connect() } catch (e: Exception) {
            L.w("TEST", "classic secure connect failed after ${System.currentTimeMillis() - t0} ms: ${e.message}; trying insecure…")
            try { s.close() } catch (_: Exception) {}
            s = try { d.createInsecureRfcommSocketToServiceRecord(SPP).also { it.connect() } } catch (e2: Exception) {
                L.w("TEST", "classic insecure connect failed too: ${e2.message}"); return
            }
        }
        L.w("TEST", "★ classic CONNECTED in ${System.currentTimeMillis() - t0} ms")
        try {
            val out = s.outputStream; val inp = s.inputStream
            for (cmd in CMDS) { out.write("$cmd\r".toByteArray()); out.flush(); L.w("TEST", "classic $cmd → ${readReply(inp, 3000)}") }
        } catch (e: Exception) { L.w("TEST", "classic talk error: $e") } finally { try { s.close() } catch (_: Exception) {} }
    }

    @Suppress("DEPRECATION")
    private fun ble(c: Context, d: BluetoothDevice) {
        L.w("TEST", "Bluetooth LE connecting…")
        val t0 = System.currentTimeMillis()
        val done = CountDownLatch(1)
        val reply = StringBuffer()
        var notifyCh: BluetoothGattCharacteristic? = null
        var writeCh: BluetoothGattCharacteristic? = null
        val ready = CountDownLatch(1)

        val cb = object : BluetoothGattCallback() {
            override fun onConnectionStateChange(g: BluetoothGatt, status: Int, state: Int) {
                if (state == BluetoothProfile.STATE_CONNECTED) { L.w("TEST", "★ LE CONNECTED in ${System.currentTimeMillis() - t0} ms (status $status); reading services…"); g.discoverServices() }
                else if (state == BluetoothProfile.STATE_DISCONNECTED) { L.w("TEST", "LE disconnected (status $status${if (status == 133) " = generic failure / out of range" else ""})"); ready.countDown(); done.countDown() }
            }
            override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
                for (sv in g.services) {
                    val su = sv.uuid.toString().take(8)
                    L.w("TEST", "LE service $su: " + sv.characteristics.joinToString(", ") { ch -> "${ch.uuid.toString().take(8)}[${props(ch.properties)}]" })
                    if (su in setOf("00001800", "00001801", "0000180a")) continue
                    val n = sv.characteristics.firstOrNull { it.properties and (BluetoothGattCharacteristic.PROPERTY_NOTIFY or BluetoothGattCharacteristic.PROPERTY_INDICATE) != 0 }
                    val w = sv.characteristics.firstOrNull { it.properties and (BluetoothGattCharacteristic.PROPERTY_WRITE or BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE) != 0 }
                    if (n != null && w != null && notifyCh == null) { notifyCh = n; writeCh = w }
                }
                val n = notifyCh
                if (n == null) { L.w("TEST", "LE: no serial-style service found"); ready.countDown(); return }
                L.w("TEST", "LE: using notify ${n.uuid.toString().take(8)} / write ${writeCh?.uuid.toString().take(8)}")
                g.setCharacteristicNotification(n, true)
                val desc = n.getDescriptor(CCCD)
                if (desc == null) { ready.countDown(); return }
                desc.value = if (n.properties and BluetoothGattCharacteristic.PROPERTY_NOTIFY != 0) BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE else BluetoothGattDescriptor.ENABLE_INDICATION_VALUE
                if (!g.writeDescriptor(desc)) ready.countDown()
            }
            override fun onDescriptorWrite(g: BluetoothGatt, desc: BluetoothGattDescriptor, status: Int) { ready.countDown() }
            @Deprecated("old API")
            override fun onCharacteristicChanged(g: BluetoothGatt, ch: BluetoothGattCharacteristic) { ch.value?.let { reply.append(String(it)) } }
            override fun onCharacteristicChanged(g: BluetoothGatt, ch: BluetoothGattCharacteristic, value: ByteArray) { reply.append(String(value)) }
        }

        val g = try { d.connectGatt(c, false, cb, BluetoothDevice.TRANSPORT_LE) } catch (e: Exception) { L.w("TEST", "LE connect error: $e"); return }
        if (g == null) { L.w("TEST", "LE connect returned nothing"); return }
        if (!ready.await(20, TimeUnit.SECONDS)) L.w("TEST", "LE: no connection within 20 s (adapter off, out of range, or connected to another phone)")
        val w = writeCh
        if (w != null && notifyCh != null && done.count > 0) {
            for (cmd in CMDS) {
                reply.setLength(0)
                run {
                    w.writeType = if (w.properties and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE != 0) BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE else BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                    w.value = "$cmd\r".toByteArray()
                    g.writeCharacteristic(w)
                }
                val end = System.currentTimeMillis() + 3000
                while (System.currentTimeMillis() < end && !reply.contains(">")) Thread.sleep(50)
                L.w("TEST", "LE $cmd → ${clean(reply.toString())}")
            }
        }
        try { g.disconnect() } catch (_: Exception) {}
        done.await(3, TimeUnit.SECONDS)
        try { g.close() } catch (_: Exception) {}
    }

    private fun props(p: Int): String {
        val l = mutableListOf<String>()
        if (p and BluetoothGattCharacteristic.PROPERTY_READ != 0) l += "R"
        if (p and BluetoothGattCharacteristic.PROPERTY_WRITE != 0) l += "W"
        if (p and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE != 0) l += "Wn"
        if (p and BluetoothGattCharacteristic.PROPERTY_NOTIFY != 0) l += "N"
        if (p and BluetoothGattCharacteristic.PROPERTY_INDICATE != 0) l += "I"
        return l.joinToString("")
    }
}
