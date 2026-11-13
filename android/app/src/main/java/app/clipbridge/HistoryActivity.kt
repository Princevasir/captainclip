package app.clipbridge

import android.app.Activity
import android.app.AlertDialog
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
    /** Rows on screen: a day header ("Today"…) or an entry. */
    private var rows: List<Any> = emptyList()
    private lateinit var search: EditText
    private lateinit var list: ListView
    private lateinit var empty: TextView
    private lateinit var summary: TextView

    private val adapter = object : BaseAdapter() {
        override fun getCount() = rows.size
        override fun getItem(position: Int) = rows[position]
        override fun getItemId(position: Int) = position.toLong()
        override fun getViewTypeCount() = 2
        override fun getItemViewType(position: Int) = if (rows[position] is String) 0 else 1
        override fun isEnabled(position: Int) = rows[position] is HistoryEntry
        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val row = rows[position]
            if (row is String) {
                val v = (convertView as? TextView) ?: layoutInflater.inflate(R.layout.item_history_header, parent, false) as TextView
                v.text = row
                return v
            }
            val e = row as HistoryEntry
            val v = convertView ?: layoutInflater.inflate(R.layout.item_history, parent, false)
            HistoryUi.bindBadge(v.findViewById(R.id.badge), e)
            val dir = if (e.direction == Direction.FROM_MAC) "From Mac" else "To Mac"
            v.findViewById<TextView>(R.id.meta).text = "$dir · ${UiText.ago(System.currentTimeMillis() - e.at)}"
            v.findViewById<TextView>(R.id.preview).text =
                if (e.isFile) "${e.fileName} · ${UiText.size(e.size)}" else UiText.historyPreview(e)
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
        list.setOnItemClickListener { _, _, pos, _ -> (rows[pos] as? HistoryEntry)?.let { HistoryUi.copy(this, it) } }
        list.setOnItemLongClickListener { _, _, pos, _ -> (rows[pos] as? HistoryEntry)?.let { actions(it) }; true }
        findViewById<View>(R.id.back).setOnClickListener { finish() }
        findViewById<View>(R.id.clearSearch).setOnClickListener { search.setText("") }
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
        val now = System.currentTimeMillis()
        rows = buildList {
            var group: String? = null
            for (e in shown) {
                val g = UiText.dayGroup(e.at, now)
                if (g != group) { add(g); group = g }
                add(e)
            }
        }
        adapter.notifyDataSetChanged()
        findViewById<View>(R.id.clearSearch).visibility = if (query.isEmpty()) View.GONE else View.VISIBLE
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
            store.includeFromMac -> "Nothing yet — clips you send between your Mac and phone appear here."
            else -> "Nothing yet — what you send to your Mac, and Mac files you open here, appear here."
        }
    }

    private fun actions(e: HistoryEntry) {
        val options = mutableListOf<Pair<String, () -> Unit>>()
        if (!e.isFile) {
            options += "Copy" to { HistoryUi.copy(this, e) }
            if (Prefs.pairing(this) != null) options += "Send to Mac" to {  // queues while the Mac is away
                val result = SyncService.send(this, e.text ?: "", e.html, userInitiated = true)
                toast(UiText.sendResultText(result, SyncService.holdsSends(this)))
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
            "Include clips from your Mac: ${if (store.includeFromMac) "On" else "Off"}",
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
                3 -> toggleFromMac()
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

    private fun toggleFromMac() {
        if (store.includeFromMac) {
            Prefs.setHistoryFromMac(this, false); store.setIncludeFromMac(false); refresh()
            toast("Mac copies removed from history")
        } else {
            AlertDialog.Builder(this).setTitle("Include clips from your Mac?")
                .setMessage("Everything you copy on your Mac while connected will be kept here too, even if you " +
                    "never paste it on this phone. Off by default; things you send from this phone, and Mac files " +
                    "you open here, are always kept.")
                .setPositiveButton("Include") { _, _ -> Prefs.setHistoryFromMac(this, true); store.setIncludeFromMac(true); refresh() }
                .setNegativeButton("Cancel", null).show()
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
