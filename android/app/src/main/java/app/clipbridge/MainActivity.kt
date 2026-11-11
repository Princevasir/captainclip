package app.clipbridge

import android.app.Activity
import android.content.ClipboardManager
import android.content.Intent
import android.graphics.Outline
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.View
import android.view.ViewOutlineProvider
import android.widget.Button
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import app.clipbridge.capture.ClipPolicy
import app.clipbridge.capture.CopyDetectService
import app.clipbridge.core.PairingInfo
import app.clipbridge.files.Files
import app.clipbridge.sync.Prefs
import app.clipbridge.sync.SyncLoop
import app.clipbridge.sync.SyncService
import com.google.zxing.integration.android.IntentIntegrator

class MainActivity : Activity() {
    private val handler = Handler(Looper.getMainLooper())
    private val ticker = object : Runnable {
        override fun run() { refresh(); handler.postDelayed(this, 30_000) }
    }

    private lateinit var statusDot: View
    private lateinit var statusTitle: TextView
    private lateinit var statusDetail: TextView
    private lateinit var offerCard: View
    private lateinit var offerName: TextView
    private lateinit var offerMeta: TextView
    private lateinit var saveButton: Button
    private lateinit var shareButton: Button
    private lateinit var savedText: TextView
    private lateinit var openButton: Button
    private lateinit var setupCard: View
    private lateinit var setupTitle: TextView
    private lateinit var setupHelp: TextView
    private lateinit var setupButton: Button
    private lateinit var setupNotNow: Button
    private lateinit var pauseButton: Button

    /** Result of the last in-app save, shown on the offer card until the offer changes. */
    private var saved: Pair<String, Uri>? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        bindViews()
        if (Prefs.pairing(this) != null && Prefs.syncEnabled(this)) SyncService.start(this)
    }

    override fun onResume() {
        super.onResume()
        SyncService.onStatusChanged = { refresh() }
        handler.post(ticker)
    }

    override fun onPause() {
        SyncService.onStatusChanged = null
        handler.removeCallbacks(ticker)
        super.onPause()
    }

    /** Android 15+ draws edge to edge: keep content clear of the status and navigation bars. */
    private fun applySystemBarInsets() {
        val content = findViewById<View>(R.id.content)
        val top = content.paddingTop
        val bottom = content.paddingBottom
        content.setOnApplyWindowInsetsListener { v, insets ->
            val (barTop, barBottom) = if (android.os.Build.VERSION.SDK_INT >= 30) {
                val bars = insets.getInsets(android.view.WindowInsets.Type.systemBars() or android.view.WindowInsets.Type.displayCutout())
                bars.top to bars.bottom
            } else {
                @Suppress("DEPRECATION") (insets.systemWindowInsetTop to insets.systemWindowInsetBottom)
            }
            v.setPadding(v.paddingLeft, top + barTop, v.paddingRight, bottom + barBottom)
            insets
        }
        content.requestApplyInsets()
    }

    private fun bindViews() {
        applySystemBarInsets()
        findViewById<ImageView>(R.id.logo).apply {
            outlineProvider = object : ViewOutlineProvider() {
                override fun getOutline(view: View, outline: Outline) =
                    outline.setRoundRect(0, 0, view.width, view.height, view.width * 0.28f)
            }
            clipToOutline = true
        }
        statusDot = findViewById(R.id.statusDot)
        statusTitle = findViewById(R.id.statusTitle)
        statusDetail = findViewById(R.id.statusDetail)
        offerCard = findViewById(R.id.offerCard)
        offerName = findViewById(R.id.offerName)
        offerMeta = findViewById(R.id.offerMeta)
        saveButton = findViewById(R.id.saveButton)
        shareButton = findViewById(R.id.shareButton)
        savedText = findViewById(R.id.savedText)
        openButton = findViewById(R.id.openButton)
        setupCard = findViewById(R.id.setupCard)
        setupTitle = findViewById(R.id.setupTitle)
        setupHelp = findViewById(R.id.setupHelp)
        setupButton = findViewById(R.id.setupButton)
        setupNotNow = findViewById(R.id.setupNotNow)
        setupNotNow.setOnClickListener { Prefs.setBluetoothDismissed(this); refresh() }
        pauseButton = findViewById(R.id.pauseButton)

        saveButton.setOnClickListener { saveOffer() }
        shareButton.setOnClickListener { shareOffers() }
        setupButton.setOnClickListener { onSetupAction() }
        pauseButton.setOnClickListener { toggleSync() }
        findViewById<View>(R.id.sendClipboardButton).setOnClickListener { sendClipboard() }
        if (!Edition.AUTO_COPY) findViewById<View>(R.id.sendHint).visibility = View.VISIBLE
        if (android.os.Build.VERSION.SDK_INT >= 33) {
            findViewById<View>(R.id.addTileButton).apply { visibility = View.VISIBLE; setOnClickListener { addTile() } }
        }
        findViewById<View>(R.id.repairRow).setOnClickListener { scanQr() }
        findViewById<View>(R.id.macGuideRow).setOnClickListener { startActivity(Intent(this, MacSetupActivity::class.java)) }
        findViewById<View>(R.id.setupMacGuide).setOnClickListener { startActivity(Intent(this, MacSetupActivity::class.java)) }
        findViewById<View>(R.id.batteryRow).setOnClickListener {
            startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
        }
        findViewById<TextView>(R.id.tipText).text = UiText.tip(Edition.AUTO_COPY,
            SetupStep.bluetoothFallbackAvailable(android.os.Build.VERSION.SDK_INT, Edition.LOCATION_FOR_BLUETOOTH))
        if (!Edition.AUTO_COPY) findViewById<View>(R.id.autoCopyRow).visibility = View.GONE  // only ClipBridge Pro has automatic copy
        findViewById<View>(R.id.autoCopyRow).setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
    }

    private fun refresh() {
        renderStatus()
        renderOffer()
        renderSetup()
        val enabled = Prefs.syncEnabled(this)
        pauseButton.text = if (enabled) "Pause syncing" else "Resume syncing"
        pauseButton.isEnabled = Prefs.pairing(this) != null
    }

    private fun renderStatus() {
        val paired = Prefs.pairing(this) != null
        val (color, title, detail) = when {
            !paired -> Triple(R.color.idle, "Not paired yet", "Scan the QR code from ClipBridge on your Mac")
            !Prefs.syncEnabled(this) -> Triple(R.color.idle, "Syncing paused", "Copies stay on this phone until you resume")
            else -> when (SyncService.state) {
                SyncLoop.Status.CONNECTED -> Triple(R.color.ok,
                    SyncService.macName?.let { "Connected to $it" } ?: "Connected to your Mac",
                    if (SyncService.onBluetooth) "Via Bluetooth · end-to-end encrypted · files up to 5 MB"
                    else "Same Wi-Fi · end-to-end encrypted")
                SyncLoop.Status.SEARCHING, SyncLoop.Status.RETRYING -> Triple(R.color.busy,
                    "Looking for your Mac…", "Make sure ClipBridge is running on the Mac and both are on the same Wi-Fi")
                SyncLoop.Status.PAIRING_INVALID -> Triple(R.color.bad,
                    "Your Mac doesn't recognise this phone", "Its pairing was reset — scan the QR code again")
                else -> Triple(R.color.idle, "Starting…", "")
            }
        }
        statusDot.background.mutate().setTint(getColor(color))
        statusTitle.text = title
        statusDetail.text = detail
        statusDetail.visibility = if (detail.isEmpty()) View.GONE else View.VISIBLE
    }

    private fun renderOffer() {
        val cache = Files.cache(this)
        val offers = cache.currentAll()
        val offer = offers.firstOrNull()
        if (offer == null) {
            offerCard.visibility = View.GONE
            saved = null
            return
        }
        offerCard.visibility = View.VISIBLE
        val timeLeft = UiText.timeLeft(cache.msSinceOffer() ?: 0)
        if (offers.size == 1) {
            offerName.text = offer.name
            offerMeta.text = "${UiText.size(offer.size)} · $timeLeft"
        } else {
            offerName.text = "${offers.size} files"
            offerMeta.text = "${offers.joinToString(", ") { it.name }} · ${UiText.size(offers.sumOf { it.size })} · $timeLeft"
        }
        saveButton.text = if (offers.size == 1) "Save to Downloads" else "Save all"
        if (shareButton.isEnabled) shareButton.text = if (offers.size == 1) "Share…" else "Share all…"
        val done = saved?.takeIf { it.first == offer.id }
        saveButton.visibility = if (done == null) View.VISIBLE else View.GONE
        savedText.visibility = if (done == null) View.GONE else View.VISIBLE
        openButton.visibility = if (done != null && offers.size == 1) View.VISIBLE else View.GONE
        if (done != null) {
            savedText.text = if (offers.size == 1) "Saved to Download/ClipBridge" else "Saved ${offers.size} files to Download/ClipBridge"
            openButton.setOnClickListener { open(done.second, offer.mime) }
        }
    }

    private fun renderSetup() {
        val step = currentStep()
        setupCard.visibility = if (step == SetupStep.DONE) View.GONE else View.VISIBLE
        setupTitle.text = step.title
        setupHelp.text = step.help
        setupButton.text = when (step) {
            SetupStep.PAIR -> "Scan QR code"
            SetupStep.AUTO_COPY -> "Open Accessibility settings"
            SetupStep.BLUETOOTH -> "Allow Bluetooth"
            SetupStep.DONE -> ""
        }
        setupNotNow.visibility = if (step == SetupStep.BLUETOOTH) View.VISIBLE else View.GONE
        findViewById<View>(R.id.setupMacGuide).visibility = if (step == SetupStep.PAIR) View.VISIBLE else View.GONE
    }

    private fun currentStep() = SetupStep.next(
        paired = Prefs.pairing(this) != null,
        autoCopyOn = isAutoCopyOn(),
        autoCopyAvailable = Edition.AUTO_COPY,
        bluetoothAvailable = SetupStep.bluetoothFallbackAvailable(android.os.Build.VERSION.SDK_INT, Edition.LOCATION_FOR_BLUETOOTH),
        bluetoothAllowed = bluetoothPermissionGranted(),
        bluetoothDismissed = Prefs.bluetoothDismissed(this),
    )

    private fun bluetoothPermissionGranted(): Boolean {
        val needed = if (android.os.Build.VERSION.SDK_INT >= 31)
            arrayOf(android.Manifest.permission.BLUETOOTH_SCAN, android.Manifest.permission.BLUETOOTH_CONNECT)
        else arrayOf(android.Manifest.permission.ACCESS_FINE_LOCATION)
        return needed.all { checkSelfPermission(it) == android.content.pm.PackageManager.PERMISSION_GRANTED }
    }

    private fun onSetupAction() {
        when (currentStep()) {
            SetupStep.PAIR -> scanQr()
            SetupStep.AUTO_COPY -> startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            SetupStep.BLUETOOTH -> {
                val perms = if (android.os.Build.VERSION.SDK_INT >= 31)
                    arrayOf(android.Manifest.permission.BLUETOOTH_SCAN, android.Manifest.permission.BLUETOOTH_CONNECT)
                else arrayOf(android.Manifest.permission.ACCESS_FINE_LOCATION)
                when (SetupStep.bluetoothAction(Prefs.bluetoothAsked(this), perms.any { shouldShowRequestPermissionRationale(it) })) {
                    SetupStep.PermissionAction.ASK -> { Prefs.setBluetoothAsked(this); requestPermissions(perms, 7) }
                    SetupStep.PermissionAction.OPEN_APP_INFO -> {
                        toast("Tap Permissions → Nearby devices → Allow")
                        startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null)))
                    }
                }
            }
            SetupStep.DONE -> {}
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        refresh()
    }

    /** Ask Android to add the Send to Mac tile to Quick Settings (Android 13+ shows a one-tap prompt). */
    @android.annotation.SuppressLint("NewApi")
    private fun addTile() {
        val bar = getSystemService(android.app.StatusBarManager::class.java) ?: return
        bar.requestAddTileService(
            android.content.ComponentName(this, app.clipbridge.capture.SendTileService::class.java),
            getString(R.string.tile_label),
            android.graphics.drawable.Icon.createWithResource(this, R.drawable.ic_tile_send),
            mainExecutor,
        ) { result ->
            if (result == android.app.StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_ALREADY_ADDED) toast("Send to Mac is already in Quick Settings")
        }
    }

    /** Share…: fetch the files, then Android's share menu with all of them attached (Gmail, WhatsApp, Drive…). */
    private fun shareOffers() {
        val offers = Files.cache(this).currentAll()
        if (offers.isEmpty()) return refresh()
        shareButton.isEnabled = false
        shareButton.text = "Getting files…"
        SyncService.prepareOffers(offers.map { it.id }) { error ->
            shareButton.isEnabled = true
            refresh()
            if (error != null) return@prepareOffers toast(error)
            val uris = ArrayList(offers.map { Files.uriFor(it.id, it.name) })
            val send = if (uris.size == 1) {
                Intent(Intent.ACTION_SEND).putExtra(Intent.EXTRA_STREAM, uris[0])
            } else {
                Intent(Intent.ACTION_SEND_MULTIPLE).putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
            }
            send.type = app.clipbridge.files.ShareSet.mimeFor(offers.map { it.mime })
            // ClipData carries the read grant for every file to the app the user picks.
            send.clipData = android.content.ClipData.newRawUri(offers[0].name, uris[0]).apply {
                uris.drop(1).forEach { addItem(android.content.ClipData.Item(it)) }
            }
            send.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            startActivity(Intent.createChooser(send, if (uris.size == 1) "Share ${offers[0].name}" else "Share ${uris.size} files"))
        }
    }

    private val saveProgress = app.clipbridge.files.SaveProgress()
    private var firstSavedUri: Uri? = null

    private fun saveOffer() {
        val offers = Files.cache(this).currentAll()
        if (offers.isEmpty()) return refresh()
        val todo = saveProgress.remaining(offers.map { it.id })
        if (todo.size == offers.size) firstSavedUri = null
        saveButton.isEnabled = false
        saveButton.text = "Saving…"
        fun saveNext(i: Int) {
            if (i >= todo.size) {
                saveButton.isEnabled = true
                firstSavedUri?.let { saved = offers.first().id to it }
                refresh()
                return
            }
            SyncService.saveOffer(todo[i]) { uri, error ->
                if (uri != null) {
                    saveProgress.saved(todo[i])
                    if (todo[i] == offers.first().id) firstSavedUri = uri
                    saveNext(i + 1)
                } else {
                    saveButton.isEnabled = true
                    toast(error ?: "Couldn't save ${offers.first { it.id == todo[i] }.name}")
                    refresh()
                }
            }
        }
        saveNext(0)
    }

    private fun open(uri: Uri, mime: String) {
        runCatching {
            startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri, mime).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
        }.onFailure { toast("No app here can open this file") }
    }

    /** The screen is focused, so the clipboard can be read directly. */
    private fun sendClipboard() {
        val clip = getSystemService(ClipboardManager::class.java).primaryClip?.takeIf { it.itemCount > 0 }
        val item = clip?.getItemAt(0)
        val hasUri = item?.uri != null
        val text = if (hasUri) null else item?.coerceToText(this)?.toString()
        val sensitive = clip?.description?.extras?.getBoolean(ClipPolicy.EXTRA_IS_SENSITIVE, false) ?: false
        if (hasUri) {
            Thread {
                val outcome = app.clipbridge.capture.ClipImages.sendIfImage(applicationContext, clip)
                app.clipbridge.capture.ImageSend.message(outcome, manual = true)?.let { runOnUiThread { toast(it) } }
            }.start()
            return
        }
        toast(when {
            !ClipPolicy.shouldSend(text, sensitive) -> if (sensitive) "Passwords aren't sent" else "Your clipboard is empty"
            SyncService.send(text!!, app.clipbridge.capture.ClipHtml.of(item)) -> "Sent to Mac"
            else -> "Not connected to your Mac (or already sent)"
        })
    }

    private fun isAutoCopyOn(): Boolean {
        val enabled = Settings.Secure.getString(contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: return false
        return enabled.split(':').any { it.equals("$packageName/${CopyDetectService::class.java.name}", ignoreCase = true) }
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
        val info = PairingInfo.parse(contents) ?: return toast("Not a ClipBridge code")
        Prefs.savePairing(this, info)
        Prefs.setSyncEnabled(this, true)
        SyncService.reconnect(this)
        toast("Paired with your Mac")
        refresh()
    }

    private fun toggleSync() {
        val enable = !Prefs.syncEnabled(this)
        Prefs.setSyncEnabled(this, enable)
        if (enable) SyncService.start(this) else SyncService.stop(this)
        refresh()
    }

    private fun toast(text: String) = Toast.makeText(this, text, Toast.LENGTH_SHORT).show()
}
