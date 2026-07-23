package com.vaibhavmirche.linkbridge.server

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.ParcelUuid
import timber.log.Timber
import java.util.UUID

data class BlePeer(val sessionId: String, val deviceLabel: String)

/**
 * Cross-network pairing without a shared Wi-Fi network or a camera scan: the "show" side
 * advertises its pairing session UUID over Bluetooth LE, and the "join" side scans for it
 * by proximity alone (same idea as Nearby Share) - the resulting session id + the already
 * configured signaling server URL are handed off to the same WebRTC pairing flow QR codes use.
 *
 * BLE is proximity-based (tens of metres), not literally network-independent internet
 * discovery - it complements, not replaces, the QR/manual-code flow for pairing over the
 * internet with devices that aren't nearby.
 */
class BleDiscoveryHelper(context: Context) {

    private val appContext = context.applicationContext
    private val logger = Timber.tag("BleDiscoveryHelper")
    private val bluetoothAdapter: BluetoothAdapter? by lazy {
        (appContext.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter
    }

    private var advertiseCallback: AdvertiseCallback? = null
    private var scanCallback: ScanCallback? = null

    fun isSupported(): Boolean =
        bluetoothAdapter != null && bluetoothAdapter?.bluetoothLeAdvertiser != null

    @SuppressLint("MissingPermission") // caller is responsible for requesting BLUETOOTH_ADVERTISE first
    fun startAdvertising(sessionId: UUID) {
        stopAdvertising()
        val advertiser = bluetoothAdapter?.bluetoothLeAdvertiser ?: return
        val settings = AdvertiseSettings.Builder()
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
            .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_HIGH)
            .setConnectable(false)
            .build()
        val data = AdvertiseData.Builder()
            .addServiceUuid(ParcelUuid(sessionId))
            .setIncludeDeviceName(false)
            .build()
        val callback = object : AdvertiseCallback() {
            override fun onStartSuccess(settingsInEffect: AdvertiseSettings?) {
                logger.i("BLE advertise started for session $sessionId")
            }
            override fun onStartFailure(errorCode: Int) {
                logger.w("BLE advertise failed to start: $errorCode")
            }
        }
        advertiseCallback = callback
        runCatching { advertiser.startAdvertising(settings, data, callback) }
            .onFailure { logger.w(it, "startAdvertising threw") }
    }

    @SuppressLint("MissingPermission")
    fun stopAdvertising() {
        val advertiser = bluetoothAdapter?.bluetoothLeAdvertiser
        advertiseCallback?.let { cb -> runCatching { advertiser?.stopAdvertising(cb) } }
        advertiseCallback = null
    }

    /** Scans for a short window and reports whatever LinkBridge sessions were found nearby. */
    @SuppressLint("MissingPermission") // caller is responsible for requesting BLUETOOTH_SCAN first
    fun scanForPeers(durationMs: Long, mainHandler: android.os.Handler, onResult: (List<BlePeer>) -> Unit) {
        stopScan()
        val scanner = bluetoothAdapter?.bluetoothLeScanner ?: run {
            onResult(emptyList())
            return
        }
        val found = LinkedHashMap<String, BlePeer>()
        var finished = false

        fun finish() {
            if (finished) return
            finished = true
            stopScan()
            mainHandler.post { onResult(found.values.toList()) }
        }

        val callback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                val uuid = result.scanRecord?.serviceUuids?.firstOrNull()?.uuid ?: return
                val label = result.device?.name ?: "Nearby device"
                found[uuid.toString()] = BlePeer(sessionId = uuid.toString(), deviceLabel = label)
            }
            override fun onScanFailed(errorCode: Int) {
                logger.w("BLE scan failed: $errorCode")
                finish()
            }
        }
        scanCallback = callback
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()
        runCatching { scanner.startScan(null, settings, callback) }
            .onFailure { finish() }

        mainHandler.postDelayed({ finish() }, durationMs)
    }

    @SuppressLint("MissingPermission")
    fun stopScan() {
        val scanner = bluetoothAdapter?.bluetoothLeScanner
        scanCallback?.let { cb -> runCatching { scanner?.stopScan(cb) } }
        scanCallback = null
    }
}
