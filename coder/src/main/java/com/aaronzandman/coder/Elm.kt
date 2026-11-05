package com.aaronzandman.coder

import android.annotation.SuppressLint
import android.bluetooth.*
import android.content.Context
import android.os.Build
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * ELM327 / STN link to an OBD adapter, over classic Bluetooth (SPP) or BLE.
 * Mirrors Companion's Obd link so the same Vgate vLinker FD+ works here.
 */
object Elm {
    private val SPP: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")
    private val CCCD: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
    private val KNOWN = listOf(
        "bef8d6c9-9c21-4c9e-b632-bd58c1009f9f",
        "0000fff1-0000-1000-8000-00805f9b34fb", "0000fff2-0000-1000-8000-00805f9b34fb",
        "0000ffe1-0000-1000-8000-00805f9b34fb"
    )

    interface Link { fun send(cmd: String, timeoutMs: Long = 4000): String; fun close() }

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
                else Thread.sleep(12)
            }
            return sb.toString()
        }
        override fun close() { try { sock.close() } catch (_: Exception) {} }
    }

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

    @SuppressLint("MissingPermission")
    fun open(ctx: Context, addr: String, type: String): Link {
        val bt = ctx.getSystemService(BluetoothManager::class.java)?.adapter ?: error("Bluetooth is not available")
        require(bt.isEnabled) { "Bluetooth is off" }
        val dev = bt.getRemoteDevice(addr)
        if (type != "ble") {
            try { return Classic(dev) } catch (e: Exception) { if (type == "classic") throw e }
        }
        return Ble(ctx, dev)
    }

    @SuppressLint("MissingPermission")
    fun bonded(ctx: Context): List<Pair<String, String>> {
        val bt = ctx.getSystemService(BluetoothManager::class.java)?.adapter ?: return emptyList()
        return try { bt.bondedDevices.map { (it.name ?: "Adapter") to it.address } } catch (_: Exception) { emptyList() }
    }
}
