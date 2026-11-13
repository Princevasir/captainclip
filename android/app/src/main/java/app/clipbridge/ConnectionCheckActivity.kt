package app.clipbridge

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import app.clipbridge.capture.OwnWrites
import app.clipbridge.sync.CheckFacts
import app.clipbridge.sync.CheckLine
import app.clipbridge.sync.ConnectionCheck
import app.clipbridge.sync.ConnectionLogHub
import app.clipbridge.sync.ConnectionProbe
import app.clipbridge.sync.ConnectionReport
import app.clipbridge.sync.Severity
import java.util.concurrent.Executors

/** "Check connection": what is working and what isn't, with one sentence on how to fix each. */
class ConnectionCheckActivity : Activity() {
    private val worker = Executors.newSingleThreadExecutor()
    private lateinit var headline: TextView
    private lateinit var list: LinearLayout
    private lateinit var checkAgain: Button
    private lateinit var copyReport: Button
    private var lines: List<CheckLine> = emptyList()
    private var running = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_connection_check)
        headline = findViewById(R.id.headline)
        list = findViewById(R.id.lines)
        checkAgain = findViewById(R.id.checkAgain)
        copyReport = findViewById(R.id.copyReport)
        checkAgain.setOnClickListener { run() }
        copyReport.setOnClickListener { copy() }
        // Keep clear of the status and navigation bars (same approach as the setup guide).
        val content = (findViewById<View>(R.id.content).parent as View)
        val top = content.paddingTop
        val bottom = content.paddingBottom
        content.setOnApplyWindowInsetsListener { v, insets ->
            val (barTop, barBottom) = if (Build.VERSION.SDK_INT >= 30) {
                val bars = insets.getInsets(android.view.WindowInsets.Type.systemBars() or android.view.WindowInsets.Type.displayCutout())
                bars.top to bars.bottom
            } else {
                @Suppress("DEPRECATION") (insets.systemWindowInsetTop to insets.systemWindowInsetBottom)
            }
            v.setPadding(v.paddingLeft, top + barTop, v.paddingRight, bottom + barBottom)
            insets
        }
        content.requestApplyInsets()
        run()
    }

    override fun onDestroy() {
        worker.shutdownNow()
        super.onDestroy()
    }

    private fun run() {
        if (running) return
        if (app.clipbridge.sync.Prefs.pairing(this) == null) {
            headline.text = "Not paired yet — scan the QR code from your Mac"
            list.removeAllViews()
            lines = emptyList()
            copyReport.isEnabled = false
            return
        }
        running = true
        headline.text = "Checking…"
        list.removeAllViews()
        lines = emptyList()
        checkAgain.isEnabled = false
        copyReport.isEnabled = false
        val app = applicationContext
        worker.execute {
            val facts = runCatching { ConnectionProbe(app).gather() }.getOrNull()
            runOnUiThread { if (!isDestroyed) show(facts) }
        }
    }

    private fun show(facts: CheckFacts?) {
        running = false
        checkAgain.isEnabled = true
        if (facts == null) {
            headline.text = "Couldn't run the check"
            return
        }
        lines = ConnectionCheck.lines(facts)
        headline.text = ConnectionCheck.headline(facts)
        copyReport.isEnabled = true
        list.removeAllViews()
        lines.forEach { line ->
            val row = layoutInflater.inflate(R.layout.item_check_line, list, false)
            val (mark, colour) = when (line.severity) {
                Severity.OK -> "✓" to 0xFF16A34A.toInt()
                Severity.WARN -> "!" to 0xFFD97706.toInt()
                Severity.PROBLEM -> "✕" to 0xFFDC2626.toInt()
            }
            row.findViewById<TextView>(R.id.lineMark).apply { text = mark; setTextColor(colour) }
            row.findViewById<TextView>(R.id.lineTitle).text = line.title
            row.findViewById<TextView>(R.id.lineDetail).text = line.detail
            list.addView(row)
        }
    }

    private fun copy() {
        val fmt = android.text.format.DateFormat.getTimeFormat(this)
        val day = java.text.SimpleDateFormat("d MMM", java.util.Locale.getDefault())
        val text = ConnectionReport.text(
            app = BuildConfig.VERSION_NAME,
            android = Build.VERSION.RELEASE,
            lines = lines,
            events = ConnectionLogHub.log.recent(20),
            now = System.currentTimeMillis(),
            time = { "${day.format(java.util.Date(it))} ${fmt.format(java.util.Date(it))}" },
        )
        OwnWrites.markNow()  // ClipBridge's own write: not sent to the Mac
        getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("ClipBridge report", text))
        Toast.makeText(this, "Report copied — it has no clipboard text", Toast.LENGTH_SHORT).show()
    }
}
