package app.clipbridge

import android.app.Activity
import android.content.ClipboardManager
import android.os.Bundle
import android.view.View
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast

/** "What CaptainClip does": every feature in a line, and a box to try pasting what you copied on the Mac. */
class FeaturesActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_features)
        val list = findViewById<LinearLayout>(R.id.features)
        Features.list(pro = Edition.AUTO_COPY).forEach { f ->
            val row = layoutInflater.inflate(R.layout.item_feature, list, false)
            row.findViewById<ImageView>(R.id.featureIcon).setImageResource(f.icon)
            row.findViewById<TextView>(R.id.featureTitle).text = f.title
            row.findViewById<TextView>(R.id.featureBody).text = f.body
            list.addView(row)
        }
        val box = findViewById<EditText>(R.id.tryBox)
        findViewById<View>(R.id.pasteButton).setOnClickListener {
            val text = (getSystemService(CLIPBOARD_SERVICE) as ClipboardManager).primaryClip
                ?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(this)?.toString()
            if (text.isNullOrEmpty()) Toast.makeText(this, "Nothing copied yet — copy something on your Mac first", Toast.LENGTH_SHORT).show()
            else { box.setText(text); box.setSelection(text.length) }
        }
        findViewById<View>(R.id.done).setOnClickListener { finish() }
        // Keep clear of the status and navigation bars (same as the Mac setup screen).
        val content = findViewById<View>(R.id.content).parent as View
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
