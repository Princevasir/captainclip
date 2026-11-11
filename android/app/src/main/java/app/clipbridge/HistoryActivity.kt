package app.clipbridge

import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.EditText
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast
import app.clipbridge.capture.OwnWrites
import app.clipbridge.history.Direction
import app.clipbridge.history.HistoryEntry
import app.clipbridge.history.HistoryHub
import app.clipbridge.history.HistoryLimits
import app.clipbridge.sync.Prefs
import app.clipbridge.sync.SyncService

/** Searchable stack of clips that crossed between the Mac and this phone (stored encrypted, on the phone only). */
class HistoryActivity : Activity() {
    private val store by lazy { HistoryHub.store(this) }
    private var shown: List<HistoryEntry> = emptyList()
    private lateinit var search: EditText
    private lateinit var list: ListView
    private lateinit var empty: TextView
    private lateinit var summary: TextView

    private val adapter = object : BaseAdapter() {
        override fun getCount() = shown.size
        override fun getItem(position: Int) = shown[position]
        override fun getItemId(position: Int) = position.toLong()
        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val v = convertView ?: layoutInflater.inflate(R.layout.item_history, parent, false)
            val e = shown[position]
            val dir = if (e.direction == Direction.FROM_MAC) "From Mac" else "To Mac"
            v.findViewById<TextView>(R.id.meta).text = "$dir · ${UiText.ago(System.currentTimeMillis() - e.at)}"
            v.findViewById<TextView>(R.id.preview).text = UiText.historyPreview(e)
            return v
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_history)
        search = findViewById(R.id.search)
        list = findViewById(R.id.list)
        empty = findViewById(R.id.empty)
        summary = findViewById(R.id.summary)
        list.adapter = adapter
        list.setOnItemClickListener { _, _, pos, _ -> copy(shown[pos]) }
        list.setOnItemLongClickListener { _, _, pos, _ -> actions(shown[pos]); true }
        search.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) = refresh()
        })
        findViewById<View>(R.id.menu).setOnClickListener { menu() }
        val root = findViewById<View>(R.id.root)
        val top = root.paddingTop
        val bottom = root.paddingBottom
        root.setOnApplyWindowInsetsListener { v, insets ->
            val (barTop, barBottom) = if (android.os.Build.VERSION.SDK_INT >= 30) {
                val bars = insets.getInsets(android.view.WindowInsets.Type.systemBars() or android.view.WindowInsets.Type.displayCutout()
                    or android.view.WindowInsets.Type.ime())
                bars.top to bars.bottom
            } else {
                @Suppress("DEPRECATION") (insets.systemWindowInsetTop to insets.systemWindowInsetBottom)
            }
            v.setPadding(v.paddingLeft, top + barTop, v.paddingRight, bottom + barBottom)
            insets
        }
        root.requestApplyInsets()
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun refresh() {
        val query = search.text.toString()
        shown = store.search(query)
        adapter.notifyDataSetChanged()
        val total = store.entries().size
        val l = store.limits
        summary.text = if (!store.enabled) "History is off"
            else "$total of ${l.maxItems} clips · kept for ${l.maxAgeMs / HistoryLimits.DAY_MS} days · only on this phone, encrypted"
        val nothing = shown.isEmpty()
        list.visibility = if (nothing) View.GONE else View.VISIBLE
        empty.visibility = if (nothing) View.VISIBLE else View.GONE
        empty.text = when {
            !store.enabled -> "History is off. Turn it on from ⋮ → Settings."
            query.isNotBlank() -> "No matches."
            else -> "Nothing yet — clips you send between your Mac and phone appear here."
        }
    }

    private fun copy(e: HistoryEntry) {
        if (e.isFile) { toast("Only the file's name is kept — its contents aren't stored"); return }
        val text = e.text ?: return
        OwnWrites.markNow()  // ClipBridge Pro: don't send this back to the Mac by itself
        val clip = if (e.html != null) ClipData.newHtmlText("ClipBridge", text, e.html) else ClipData.newPlainText("ClipBridge", text)
        getSystemService(ClipboardManager::class.java).setPrimaryClip(clip)
        toast("Copied")
    }

    private fun actions(e: HistoryEntry) {
        val options = mutableListOf<Pair<String, () -> Unit>>()
        if (!e.isFile) {
            options += "Copy" to { copy(e) }
            if (SyncService.isConnected) options += "Send to Mac" to {
                val ok = SyncService.send(e.text ?: "", e.html, userInitiated = true)
                toast(if (ok) "Sent to Mac" else "Couldn't send — not connected to your Mac")
                refresh()
            }
        }
        options += "Delete" to { store.delete(e.id); refresh() }
        AlertDialog.Builder(this)
            .setTitle(UiText.historyPreview(e).take(60))
            .setItems(options.map { it.first }.toTypedArray()) { _, i -> options[i].second() }
            .show()
    }

    private fun menu() {
        AlertDialog.Builder(this)
            .setItems(arrayOf("Settings", "Clear history")) { _, i -> if (i == 0) settings() else confirmClear() }
            .show()
    }

    private fun settings() {
        val l = store.limits
        val items = arrayOf(
            "History: ${if (store.enabled) "On" else "Off"}",
            "Keep up to ${l.maxItems} clips",
            "Keep for ${l.maxAgeMs / HistoryLimits.DAY_MS} days",
        )
        AlertDialog.Builder(this).setTitle("History settings").setItems(items) { _, i ->
            when (i) {
                0 -> toggle()
                1 -> choose("Keep up to", HistoryLimits.ITEM_CHOICES.map { "$it clips" }, HistoryLimits.ITEM_CHOICES.indexOf(l.maxItems)) {
                    Prefs.setHistoryMaxItems(this, HistoryLimits.ITEM_CHOICES[it]); applyLimits()
                }
                2 -> choose("Keep for", HistoryLimits.DAY_CHOICES.map { if (it == 1) "1 day" else "$it days" },
                    HistoryLimits.DAY_CHOICES.indexOf((l.maxAgeMs / HistoryLimits.DAY_MS).toInt())) {
                    Prefs.setHistoryMaxDays(this, HistoryLimits.DAY_CHOICES[it]); applyLimits()
                }
            }
        }.show()
    }

    private fun toggle() {
        if (store.enabled) {
            AlertDialog.Builder(this).setTitle("Turn off history?")
                .setMessage("Clips won't be kept, and the saved history is deleted.")
                .setPositiveButton("Turn off") { _, _ -> Prefs.setHistoryEnabled(this, false); store.setEnabled(false); refresh() }
                .setNegativeButton("Cancel", null).show()
        } else {
            Prefs.setHistoryEnabled(this, true); store.setEnabled(true); refresh()
        }
    }

    private fun choose(title: String, labels: List<String>, current: Int, onPick: (Int) -> Unit) {
        AlertDialog.Builder(this).setTitle(title)
            .setSingleChoiceItems(labels.toTypedArray(), current) { d, i -> onPick(i); d.dismiss() }
            .show()
    }

    private fun applyLimits() {
        store.applyLimits(HistoryLimits.from(Prefs.historyMaxItems(this), Prefs.historyMaxDays(this)))
        refresh()
    }

    private fun confirmClear() {
        AlertDialog.Builder(this).setTitle("Clear history?")
            .setMessage("Deletes every saved clip from this phone.")
            .setPositiveButton("Clear") { _, _ -> store.clear(); refresh() }
            .setNegativeButton("Cancel", null).show()
    }

    private fun toast(text: String) = Toast.makeText(this, text, Toast.LENGTH_SHORT).show()
}
