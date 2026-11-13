package app.clipbridge.sync

import android.app.Activity
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanFilter
import android.companion.AssociationInfo
import android.companion.AssociationRequest
import android.companion.BluetoothLeDeviceFilter
import android.companion.CompanionDeviceManager
import android.content.Context
import android.content.IntentSender
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.ParcelUuid
import app.clipbridge.core.PairingTag
import java.util.regex.Pattern

/**
 * Links the paired Mac as an Android *companion device* (like a watch or earbuds). Android then exempts CaptainClip
 * from battery restrictions and lets it start its connection from the background (REQUEST_COMPANION_* permissions).
 * The system dialog finds the Mac over Bluetooth by its advertised pairing tag.
 */
object MacCompanion {
    const val REQUEST_CODE = 41

    /** The Mac advertises exactly its 8-hex-char pairing tag as its Bluetooth name. */
    fun namePattern(macId: String): Pattern = Pattern.compile("^" + Pattern.quote(PairingTag.localName(macId)) + "$")

    fun available(ctx: Context): Boolean =
        ctx.packageManager.hasSystemFeature(PackageManager.FEATURE_COMPANION_DEVICE_SETUP)

    /** Already linked? Uses the system's own records where it can (Android 13+), else what we remember. */
    fun linked(ctx: Context): Boolean {
        if (Build.VERSION.SDK_INT >= 33) {
            val cdm = ctx.getSystemService(CompanionDeviceManager::class.java) ?: return false
            return runCatching { cdm.myAssociations.isNotEmpty() }.getOrDefault(Prefs.companionLinked(ctx))
        }
        return Prefs.companionLinked(ctx)
    }

    /** Starts the system "link your Mac" dialog. [onError] gets a message for the user. */
    fun link(activity: Activity, macId: String, onError: (String) -> Unit) {
        val bt = activity.getSystemService(BluetoothManager::class.java)?.adapter
        if (bt == null || !bt.isEnabled) return onError("Turn on Bluetooth so your phone can find your Mac")
        val cdm = activity.getSystemService(CompanionDeviceManager::class.java) ?: return onError("Not supported on this phone")
        val filter = BluetoothLeDeviceFilter.Builder()
            .setNamePattern(namePattern(macId))
            .setScanFilter(ScanFilter.Builder().setServiceUuid(ParcelUuid(BleLink.SERVICE)).build())
            .build()
        val request = AssociationRequest.Builder().addDeviceFilter(filter).setSingleDevice(true).build()
        val callback = object : CompanionDeviceManager.Callback() {
            override fun onAssociationPending(sender: IntentSender) = show(sender)
            @Deprecated("Android < 13") override fun onDeviceFound(sender: IntentSender) = show(sender)
            override fun onAssociationCreated(info: AssociationInfo) { Prefs.setCompanionLinked(activity, true) }
            override fun onFailure(error: CharSequence?) {
                onError("Couldn't find your Mac — make sure CaptainClip is running on it and Bluetooth is on")
            }
            private fun show(sender: IntentSender) {
                runCatching { activity.startIntentSenderForResult(sender, REQUEST_CODE, null, 0, 0, 0) }
                    .onFailure { onError("Couldn't open the linking screen") }
            }
        }
        @Suppress("DEPRECATION") cdm.associate(request, callback, Handler(Looper.getMainLooper()))
    }
}
