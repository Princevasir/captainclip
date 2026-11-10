package app.clipbridge.sync

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.ParcelUuid
import android.util.Log
import app.clipbridge.core.PacketInputStream
import app.clipbridge.core.PacketOutputStream
import app.clipbridge.core.PairingInfo
import app.clipbridge.core.PairingTag
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit

/**
 * Bluetooth fallback, phone side: a BLE GATT client to the Mac's ClipBridge service, exposed as a byte-stream
 * [Link]. The encrypted session on top is exactly the same as over Wi-Fi. Radio code only — verified on device.
 */
@SuppressLint("MissingPermission")  // every entry point checks [allowed] first
class BleLink private constructor(private val gatt: BluetoothGatt, private val callback: Callback) : Link {
    override val isBluetooth = true

    override fun open(readTimeoutMs: Int): Pair<InputStream, OutputStream> {
        callback.input.readTimeoutMs = readTimeoutMs
        val out = PacketOutputStream(callback.payloadSize) { packet -> callback.write(gatt, packet) }
        return callback.input to out
    }

    override fun close() {
        runCatching { gatt.disconnect() }
        runCatching { gatt.close() }
        callback.input.closeFromRemote()
    }

    class Callback : BluetoothGattCallback() {
        val input = PacketInputStream()
        val ready = CountDownLatch(1)
        @Volatile var failed = false
        @Volatile var payloadSize = 20
        @Volatile var toMac: BluetoothGattCharacteristic? = null
        private val writeDone = Semaphore(0)

        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                gatt.requestMtu(517)
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                failed = true
                ready.countDown()
                input.closeFromRemote()
                writeDone.release()
            }
        }

        override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
            payloadSize = app.clipbridge.core.PacketSize.forMtu(mtu)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                gatt.setPreferredPhy(BluetoothDevice.PHY_LE_2M_MASK, BluetoothDevice.PHY_LE_2M_MASK, BluetoothDevice.PHY_OPTION_NO_PREFERRED)
            }
            gatt.discoverServices()
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            val service = gatt.getService(SERVICE)
            val toPhone = service?.getCharacteristic(TO_PHONE)
            toMac = service?.getCharacteristic(TO_MAC)
            if (toPhone == null || toMac == null) { failed = true; ready.countDown(); return }
            gatt.setCharacteristicNotification(toPhone, true)
            val cccd = toPhone.getDescriptor(CCCD) ?: run { failed = true; ready.countDown(); return }
            if (Build.VERSION.SDK_INT >= 33) {
                gatt.writeDescriptor(cccd, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
            } else {
                @Suppress("DEPRECATION") run { cccd.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE; gatt.writeDescriptor(cccd) }
            }
        }

        override fun onDescriptorWrite(gatt: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
            failed = status != BluetoothGatt.GATT_SUCCESS
            ready.countDown()
        }

        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, value: ByteArray) {
            if (characteristic.uuid == TO_PHONE) input.offer(value)
        }

        @Deprecated("Android < 13 path")
        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            @Suppress("DEPRECATION") val value = characteristic.value ?: return
            if (characteristic.uuid == TO_PHONE) input.offer(value)
        }

        override fun onCharacteristicWrite(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) {
            writeDone.release()
        }

        /** One packet, waiting for the stack before the next (flow control). False when the link is gone. */
        fun write(gatt: BluetoothGatt, packet: ByteArray): Boolean {
            val ch = toMac ?: return false
            repeat(50) {
                if (failed) return false
                val started = if (Build.VERSION.SDK_INT >= 33) {
                    gatt.writeCharacteristic(ch, packet, BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE) == android.bluetooth.BluetoothStatusCodes.SUCCESS
                } else {
                    @Suppress("DEPRECATION") run {
                        ch.writeType = BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
                        ch.value = packet
                        gatt.writeCharacteristic(ch)
                    }
                }
                if (started) return writeDone.tryAcquire(5, TimeUnit.SECONDS) && !failed
                Thread.sleep(10)  // stack busy: try again shortly
            }
            return false
        }
    }

    companion object {
        private const val TAG = "ClipBridgeBLE"
        val SERVICE: UUID = UUID.fromString("6C1B0001-8C4F-4E5A-9B8E-5A1D3C2B7F10")
        val TO_MAC: UUID = UUID.fromString("6C1B0002-8C4F-4E5A-9B8E-5A1D3C2B7F10")
        val TO_PHONE: UUID = UUID.fromString("6C1B0003-8C4F-4E5A-9B8E-5A1D3C2B7F10")
        private val CCCD: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

        /** Bluetooth is on and the needed permissions are granted. */
        fun allowed(context: Context): Boolean {
            val adapter = context.getSystemService(BluetoothManager::class.java)?.adapter ?: return false
            if (!adapter.isEnabled) return false
            val needed = if (Build.VERSION.SDK_INT >= 31)
                listOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
            else listOf(Manifest.permission.ACCESS_FINE_LOCATION)
            return needed.all { context.checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }
        }

        /** Scans (up to [scanMs]) for this pairing's Mac and connects. Null if not found / not allowed. Blocking. */
        fun find(context: Context, info: PairingInfo, scanMs: Long = 10_000): BleLink? = try {
            findOrNull(context, info, scanMs)
        } catch (e: InterruptedException) {
            throw e
        } catch (e: Exception) {
            Log.i(TAG, "Bluetooth unavailable: ${e.javaClass.simpleName}")  // e.g. radio switched off mid-scan
            null
        }

        private fun findOrNull(context: Context, info: PairingInfo, scanMs: Long): BleLink? {
            if (!allowed(context)) return null
            val adapter = context.getSystemService(BluetoothManager::class.java).adapter
            val scanner = adapter.bluetoothLeScanner ?: return null
            val wanted = PairingTag.localName(info.macId)
            val found = CountDownLatch(1)
            var device: BluetoothDevice? = null
            val scan = object : ScanCallback() {
                override fun onScanFailed(errorCode: Int) { Log.i(TAG, "Bluetooth scan failed: $errorCode") }
                override fun onScanResult(callbackType: Int, result: ScanResult) {
                    val name = result.scanRecord?.deviceName ?: return
                    if (name == wanted && device == null) { device = result.device; found.countDown() }
                }
            }
            val filter = ScanFilter.Builder().setServiceUuid(ParcelUuid(SERVICE)).build()
            val settings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build()
            try {
                scanner.startScan(listOf(filter), settings, scan)
                if (!found.await(scanMs, TimeUnit.MILLISECONDS)) Log.i(TAG, "Bluetooth scan: Mac $wanted not found")
            } finally {
                runCatching { scanner.stopScan(scan) }
            }
            val mac = device ?: return null
            val callback = Callback()
            val gatt = mac.connectGatt(context, false, callback, BluetoothDevice.TRANSPORT_LE) ?: return null
            val ready = try {
                callback.ready.await(15, TimeUnit.SECONDS) && !callback.failed
            } catch (e: InterruptedException) {
                runCatching { gatt.disconnect(); gatt.close() }  // stopped mid-setup: never leave a link behind
                throw e
            }
            if (!ready) {
                Log.i(TAG, "Bluetooth link setup failed")
                runCatching { gatt.disconnect(); gatt.close() }
                return null
            }
            Log.i(TAG, "Bluetooth link to Mac ready (payload ${callback.payloadSize} bytes)")
            return BleLink(gatt, callback)
        }
    }
}
