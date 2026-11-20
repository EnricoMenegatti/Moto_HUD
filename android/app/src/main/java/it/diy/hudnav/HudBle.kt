package it.diy.hudnav

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.Build
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID

/** Cliente GATT del servizio percorso. Le scritture sono una alla volta, con risposta. */
class HudBle(private val app: Context) {
    private val svc = UUID.fromString("8d1f0001-6a4c-4b3e-9a0e-7c2d5f1e4b00")
    private val chr = UUID.fromString("8d1f0002-6a4c-4b3e-9a0e-7c2d5f1e4b00")
    private val gate = Object()
    private val writeGate = Object()
    private var gatt: BluetoothGatt? = null
    private var characteristic: BluetoothGattCharacteristic? = null
    private var ready = false
    private var mtu = 23
    private var pendingOk: Boolean? = null
    private var scannerStopped = false

    fun isReady(): Boolean = ready && characteristic != null

    fun pointsPerPacket(): Int {
        val payload = (mtu - 3).coerceAtLeast(20)
        return ((payload - 4) / 8).coerceIn(2, 20)
    }

    fun payloadRoom(): Int = (mtu - 3).coerceAtLeast(20)

    @SuppressLint("MissingPermission")
    fun connectBlocking(timeoutMs: Long): Boolean {
        close()
        val manager = app.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        val adapter = manager.adapter ?: return false
        if (!adapter.isEnabled) return false
        val bonded = adapter.bondedDevices?.firstOrNull { it.name == "HUD-Moto" }
        if (bonded != null) connectDevice(bonded) else scan(adapter)
        val deadline = System.currentTimeMillis() + timeoutMs
        synchronized(gate) {
            while (!ready && System.currentTimeMillis() < deadline) {
                gate.wait(250)
            }
            return ready
        }
    }

    @SuppressLint("MissingPermission")
    fun writeBlocking(bytes: ByteArray): Boolean {
        val ch = characteristic ?: return false
        val g = gatt ?: return false
        synchronized(writeGate) { pendingOk = null }
        val started = if (Build.VERSION.SDK_INT >= 33) {
            g.writeCharacteristic(ch, bytes, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT) ==
                android.bluetooth.BluetoothStatusCodes.SUCCESS
        } else {
            @Suppress("DEPRECATION")
            ch.value = bytes
            @Suppress("DEPRECATION")
            ch.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
            @Suppress("DEPRECATION")
            g.writeCharacteristic(ch)
        }
        if (!started) return false
        synchronized(writeGate) {
            val deadline = System.currentTimeMillis() + 2500
            while (pendingOk == null && System.currentTimeMillis() < deadline) writeGate.wait(200)
            return pendingOk == true
        }
    }

    @SuppressLint("MissingPermission")
    fun close() {
        ready = false
        characteristic = null
        stopScan()
        gatt?.disconnect()
        gatt?.close()
        gatt = null
    }

    @SuppressLint("MissingPermission")
    private fun scan(adapter: android.bluetooth.BluetoothAdapter) {
        val scanner = adapter.bluetoothLeScanner ?: return
        scannerStopped = false
        val settings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build()
        scanner.startScan(null, settings, scanCallback)
    }

    @SuppressLint("MissingPermission")
    private fun stopScan() {
        if (scannerStopped) return
        scannerStopped = true
        val manager = app.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        manager.adapter?.bluetoothLeScanner?.stopScan(scanCallback)
    }

    private val scanCallback = object : ScanCallback() {
        @SuppressLint("MissingPermission")
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val name = result.device.name ?: result.scanRecord?.deviceName ?: return
            if (name != "HUD-Moto") return
            stopScan()
            connectDevice(result.device)
        }
    }

    @SuppressLint("MissingPermission")
    private fun connectDevice(device: BluetoothDevice) {
        gatt = if (Build.VERSION.SDK_INT >= 23) {
            device.connectGatt(app, false, callback, BluetoothDevice.TRANSPORT_LE)
        } else {
            device.connectGatt(app, false, callback)
        }
    }

    private val callback = object : BluetoothGattCallback() {
        @SuppressLint("MissingPermission")
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                g.discoverServices()
                g.requestMtu(247)
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                ready = false
                characteristic = null
                synchronized(gate) { gate.notifyAll() }
                synchronized(writeGate) { pendingOk = false; writeGate.notifyAll() }
            }
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            characteristic = g.getService(svc)?.getCharacteristic(chr)
            if (characteristic == null) {
                synchronized(gate) { gate.notifyAll() }
                return
            }
            Thread {
                Thread.sleep(900)
                if (!ready && characteristic != null) {
                    ready = true
                    synchronized(gate) { gate.notifyAll() }
                }
            }.start()
        }

        override fun onMtuChanged(g: BluetoothGatt, mtu: Int, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) this@HudBle.mtu = mtu
            if (characteristic != null) {
                ready = true
                synchronized(gate) { gate.notifyAll() }
            }
        }

        override fun onCharacteristicWrite(g: BluetoothGatt, c: BluetoothGattCharacteristic, status: Int) {
            synchronized(writeGate) {
                pendingOk = status == BluetoothGatt.GATT_SUCCESS
                writeGate.notifyAll()
            }
        }
    }

    companion object {
        fun begin(n: Int, lat: Double, lon: Double): ByteArray {
            val b = ByteBuffer.allocate(11).order(ByteOrder.LITTLE_ENDIAN)
            b.put(0x01.toByte())
            b.putShort(n.toShort())
            b.putInt(e7(lat))
            b.putInt(e7(lon))
            return b.array()
        }

        fun points(first: Int, chunk: List<LatLon>): ByteArray {
            val b = ByteBuffer.allocate(4 + chunk.size * 8).order(ByteOrder.LITTLE_ENDIAN)
            b.put(0x02.toByte())
            b.putShort(first.toShort())
            b.put(chunk.size.toByte())
            for (p in chunk) {
                b.putInt(e7(p.lat))
                b.putInt(e7(p.lon))
            }
            return b.array()
        }

        fun end(n: Int): ByteArray {
            val b = ByteBuffer.allocate(3).order(ByteOrder.LITTLE_ENDIAN)
            b.put(0x03.toByte())
            b.putShort(n.toShort())
            return b.array()
        }

        fun pos(lat: Double, lon: Double, kmh: Double, course: Double): ByteArray {
            val b = ByteBuffer.allocate(13).order(ByteOrder.LITTLE_ENDIAN)
            b.put(0x04.toByte())
            b.putInt(e7(lat))
            b.putInt(e7(lon))
            b.putShort(kmh.coerceIn(0.0, 6553.5).times(10).toInt().toShort())
            val c = (((course % 360) + 360) % 360 * 10).toInt() % 3600
            b.putShort(c.toShort())
            return b.array()
        }

        fun clear(): ByteArray = byteArrayOf(0x05)

        /** cmd: 1 layout, 2 specchio, 3 luminosità, 4 display acceso. */
        fun ctrl(cmd: Int, arg: Int): ByteArray = byteArrayOf(0x07, cmd.coerceIn(0, 255).toByte(), arg.coerceIn(0, 255).toByte())

        /** Istruzione per i layout icona e mappa. La via e' UTF-8, max 72 byte. */
        fun guide(turn: Int, distM: Int, remainM: Int, durS: Int, etaMin: Int, street: String): ByteArray {
            val raw = clipUtf8(street, 72)
            val b = ByteBuffer.allocate(13 + raw.size).order(ByteOrder.LITTLE_ENDIAN)
            b.put(0x06.toByte())
            b.put(turn.coerceIn(0, 0x18).toByte())
            b.putShort(distM.coerceIn(0, 65535).toShort())
            b.putInt(remainM.coerceAtLeast(0))
            b.putShort(durS.coerceIn(0, 65535).toShort())
            b.putShort((if (etaMin < 0) 0xFFFF else etaMin.coerceIn(0, 24 * 60 - 1)).toShort())
            b.put(raw.size.toByte())
            b.put(raw)
            return b.array()
        }

        private fun clipUtf8(s: String, max: Int): ByteArray {
            val raw = s.toByteArray(Charsets.UTF_8)
            if (raw.size <= max) return raw
            var n = max
            while (n > 0 && (raw[n].toInt() and 0xC0) == 0x80) n--
            return raw.copyOf(n)
        }

        private fun e7(deg: Double): Int {
            val v = deg * 1e7
            return (if (deg >= 0) v + 0.5 else v - 0.5).toInt()
        }
    }
}
