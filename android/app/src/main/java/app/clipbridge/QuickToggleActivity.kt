package app.clipbridge

import android.app.Activity
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import app.clipbridge.sync.Prefs

/** "Send to Mac toggle": what it is, Add it now (Android 13+), and how to place it on any phone. */
class QuickToggleActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_quick_toggle)
        findViewById<View>(R.id.preview).background = GradientDrawable(GradientDrawable.Orientation.TL_BR,
            intArrayOf(0xFF0EA5A4.toInt(), 0xFF4F46E5.toInt())).apply { cornerRadius = 28 * resources.displayMetrics.density }
        val list = findViewById<LinearLayout>(R.id.steps)
        QuickToggleGuide.steps(Build.VERSION.SDK_INT).forEachIndexed { i, step ->
            val row = layoutInflater.inflate(R.layout.item_setup_step, list, false)
            row.findViewById<TextView>(R.id.stepNumber).text = "${i + 1}"
            row.findViewById<TextView>(R.id.stepText).text = step
            row.findViewById<View>(R.id.stepPicture).visibility = View.GONE
            list.addView(row)
        }
        findViewById<View>(R.id.addNow).apply {
            visibility = if (QuickToggleGuide.oneTap(Build.VERSION.SDK_INT)) View.VISIBLE else View.GONE
            setOnClickListener {
                QuickToggle.request(this@QuickToggleActivity) { added ->
                    if (added) Toast.makeText(this@QuickToggleActivity, "Added — swipe down to see it", Toast.LENGTH_SHORT).show()
                    render()
                }
            }
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

    private fun render() {
        findViewById<TextView>(R.id.status).text =
            if (Prefs.tileAdded(this)) "✓ In your notification panel" else "Not added yet"
    }
}
