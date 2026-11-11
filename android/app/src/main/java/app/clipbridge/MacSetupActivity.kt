package app.clipbridge

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast

/** "Set up ClipBridge on your Mac": numbered steps, plus open/share the download link. */
class MacSetupActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_mac_setup)
        val list = findViewById<LinearLayout>(R.id.steps)
        MacSetupGuide.steps.forEachIndexed { i, step ->
            val row = layoutInflater.inflate(R.layout.item_setup_step, list, false)
            row.findViewById<TextView>(R.id.stepNumber).text = "${i + 1}"
            row.findViewById<TextView>(R.id.stepText).text = step
            list.addView(row)
        }
        findViewById<TextView>(R.id.linkText).text = MacSetupGuide.DOWNLOAD_URL
        findViewById<View>(R.id.openLink).setOnClickListener {
            runCatching { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(MacSetupGuide.DOWNLOAD_URL))) }
                .onFailure { Toast.makeText(this, "No browser found", Toast.LENGTH_SHORT).show() }
        }
        findViewById<View>(R.id.shareLink).setOnClickListener {
            val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, MacSetupGuide.shareText)
            startActivity(Intent.createChooser(send, "Send the link to your Mac"))
        }
        findViewById<View>(R.id.done).setOnClickListener { finish() }
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
}
