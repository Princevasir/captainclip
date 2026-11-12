package app.clipbridge

import android.app.Activity
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import app.clipbridge.sync.Prefs

/** "Notification panel": the two ClipBridge buttons (Send to Mac, ClipBridge on/off), each with Add / Added, plus tips. */
class QuickToggleActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_quick_toggle)
        bindButton(R.id.sendButton, R.drawable.ic_tile_send, "Send to Mac",
            "Sends what you just copied on your phone to your Mac.", onoff = false)
        bindButton(R.id.switchButton, R.drawable.ic_devices, "ClipBridge",
            "Turns syncing with your Mac on or off, like the Wi-Fi button.", onoff = true)
        val sdk = Build.VERSION.SDK_INT
        findViewById<TextView>(R.id.helpTitle).text = QuickToggleGuide.title(sdk)
        val list = findViewById<LinearLayout>(R.id.steps)
        QuickToggleGuide.steps(sdk).forEachIndexed { i, step ->
            val row = layoutInflater.inflate(R.layout.item_setup_step, list, false)
            row.findViewById<TextView>(R.id.stepNumber).text = "${i + 1}"
            row.findViewById<TextView>(R.id.stepText).text = step
            row.findViewById<View>(R.id.stepPicture).visibility = View.GONE
            list.addView(row)
        }
        findViewById<View>(R.id.back).setOnClickListener { finish() }
        findViewById<View>(R.id.done).setOnClickListener { finish() }
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
    }

    override fun onResume() {
        super.onResume()
        render()
    }

    private fun bindButton(id: Int, icon: Int, name: String, what: String, onoff: Boolean) {
        val card = findViewById<View>(id)
        card.findViewById<android.widget.ImageView>(R.id.buttonIcon).setImageResource(icon)
        card.findViewById<TextView>(R.id.buttonName).text = name
        card.findViewById<TextView>(R.id.buttonWhat).text = what
        card.findViewById<View>(R.id.buttonAdd).setOnClickListener {
            QuickToggle.request(this, onoff) { added ->
                if (added) Toast.makeText(this, "Added — swipe down to see it", Toast.LENGTH_SHORT).show()
                render()
            }
        }
    }

    private fun render() {
        val oneTap = QuickToggleGuide.oneTap(Build.VERSION.SDK_INT)
        for ((id, added) in listOf(R.id.sendButton to Prefs.tileAdded(this), R.id.switchButton to Prefs.switchTileAdded(this))) {
            val card = findViewById<View>(id)
            card.findViewById<View>(R.id.buttonAdded).visibility = if (added) View.VISIBLE else View.GONE
            // Older Android can't add from the app: the steps below explain it instead.
            card.findViewById<View>(R.id.buttonAdd).visibility = if (!added && oneTap) View.VISIBLE else View.GONE
        }
    }
}
