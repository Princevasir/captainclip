package app.clipbridge

import android.Manifest
import android.app.Activity
import android.app.NotificationManager
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import app.clipbridge.capture.CopyDetectService
import app.clipbridge.core.PairingInfo
import app.clipbridge.sync.Prefs
import app.clipbridge.sync.SyncService
import com.google.zxing.integration.android.IntentIntegrator

class MainActivity : Activity() {
    private lateinit var statusView: TextView
    private lateinit var stepView: TextView
    private lateinit var stepButton: Button
    private lateinit var toggleButton: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(buildLayout())
        if (Prefs.pairing(this) != null && Prefs.syncEnabled(this)) SyncService.start(this)
    }

    override fun onResume() {
        super.onResume()
        SyncService.onStatusChanged = { refresh() }
        refresh()
    }

    override fun onPause() {
        SyncService.onStatusChanged = null
        super.onPause()
    }

    private fun refresh() {
        val step = currentStep()
        statusView.text = "Status: ${SyncService.status}"
        stepView.text = step.help
        stepButton.visibility = if (step == SetupStep.DONE) View.GONE else View.VISIBLE
        stepButton.text = when (step) {
            SetupStep.PAIR -> "Scan Mac QR code"
            SetupStep.NOTIFICATIONS -> "Allow notifications"
            SetupStep.AUTO_COPY -> "Open Accessibility settings"
            SetupStep.DONE -> ""
        }
        toggleButton.text = if (Prefs.syncEnabled(this)) "Stop syncing" else "Start syncing"
        toggleButton.isEnabled = Prefs.pairing(this) != null
    }

    private fun currentStep() = SetupStep.next(
        paired = Prefs.pairing(this) != null,
        notificationsAllowed = getSystemService(NotificationManager::class.java).areNotificationsEnabled(),
        autoCopyOn = isAutoCopyOn(),
    )

    private fun isAutoCopyOn(): Boolean {
        val enabled = Settings.Secure.getString(contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: return false
        return enabled.split(':').any { it.equals("$packageName/${CopyDetectService::class.java.name}", ignoreCase = true) }
    }

    private fun onStepButton() {
        when (currentStep()) {
            SetupStep.PAIR -> scanQr()
            SetupStep.NOTIFICATIONS ->
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
                } else {
                    startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, packageName))
                }
            SetupStep.AUTO_COPY -> startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            SetupStep.DONE -> {}
        }
    }

    private fun scanQr() {
        IntentIntegrator(this)
            .setDesiredBarcodeFormats(IntentIntegrator.QR_CODE)
            .setPrompt("Scan the QR code shown by ClipBridge on your Mac")
            .setBeepEnabled(false)
            .setOrientationLocked(false)
            .initiateScan()
    }

    @Deprecated("Activity result API from the platform Activity")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        val result = IntentIntegrator.parseActivityResult(requestCode, resultCode, data)
        if (result == null) { super.onActivityResult(requestCode, resultCode, data); return }
        val contents = result.contents ?: return
        val info = PairingInfo.parse(contents)
        if (info == null) {
            Toast.makeText(this, "Not a ClipBridge code", Toast.LENGTH_LONG).show()
            return
        }
        Prefs.savePairing(this, info)
        Prefs.setSyncEnabled(this, true)
        SyncService.stop(this)
        SyncService.start(this)
        Toast.makeText(this, "Paired with Mac", Toast.LENGTH_SHORT).show()
        refresh()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        refresh()
    }

    private fun toggleSync() {
        val enable = !Prefs.syncEnabled(this)
        Prefs.setSyncEnabled(this, enable)
        if (enable) SyncService.start(this) else SyncService.stop(this)
        refresh()
    }

    private fun buildLayout(): View {
        val pad = (16 * resources.displayMetrics.density).toInt()
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad * 2, pad, pad)
        }
        fun text(size: Float, value: String = "") = TextView(this).apply {
            textSize = size; text = value; setPadding(0, pad / 2, 0, pad / 2)
        }
        fun button(label: String, onClick: () -> Unit) = Button(this).apply {
            text = label; setOnClickListener { onClick() }
        }

        column.addView(text(26f, "ClipBridge").apply { gravity = Gravity.START })
        statusView = text(16f).also(column::addView)
        stepView = text(16f).also(column::addView)
        stepButton = button("") { onStepButton() }.also(column::addView)
        toggleButton = button("") { toggleSync() }.also(column::addView)
        column.addView(button("Pair with a different Mac") { scanQr() })
        column.addView(button("Battery: let ClipBridge run in background") {
            startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
        })
        column.addView(text(14f, "Tip: add the \"Send to Mac\" tile to Quick Settings for one-tap sending. " +
            "You can also use Share → Send to Mac from any app."))
        return ScrollView(this).apply { addView(column) }
    }
}
