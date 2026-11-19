package org.halachmi.polls

import android.Manifest
import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import org.halachmi.polls.core.ChatNames
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * The app's one screen: the setup steps still missing (each with its button), any problem, what
 * happened lately, the chats to follow, and the version with a manual update check.
 */
class MainActivity : Activity() {
    private lateinit var status: LinearLayout
    private lateinit var log: LinearLayout
    private lateinit var chats: LinearLayout
    private lateinit var version: TextView
    private val refresh: () -> Unit = { render() }
    private var updateNote: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val pad = dp(16)
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
        }
        column.addView(text(getString(R.string.intro), 15f))
        status = vertical().apply { setPadding(0, dp(8), 0, dp(8)) }
        column.addView(status)

        column.addView(heading(getString(R.string.log_title)))
        log = vertical()
        column.addView(log)

        column.addView(heading(getString(R.string.chats_title)))
        chats = vertical()
        column.addView(chats)
        column.addView(text(getString(R.string.type_hint), 13f).apply { setPadding(0, dp(12), 0, 0) })
        val typed = EditText(this).apply {
            hint = getString(R.string.type_field)
            isSingleLine = true
        }
        column.addView(typed)
        column.addView(button(getString(R.string.add)) {
            val name = typed.text.toString().trim()
            if (ChatNames.key(name).isNotEmpty()) {
                WidgetWatcher.setFollowed(this, name, true)
                typed.setText("")
                render()
            }
        })
        column.addView(button(getString(R.string.test_ring)) {
            PollAlarm.ring(this, getString(R.string.test_chat), getString(R.string.test_question), 1)
        })

        version = text("", 13f).apply { setPadding(0, dp(16), 0, 0) }
        column.addView(version)
        if (Updater.enabled) column.addView(button(getString(R.string.check_updates)) { checkForUpdate() })

        setContentView(ScrollView(this).apply {
            fitsSystemWindows = true
            addView(column)
        })
        title = getString(R.string.app_name)
    }

    override fun onStart() {
        super.onStart()
        WidgetWatcher.addListener(refresh)
        WatchService.start(this)
        WidgetWatcher.start(this)
        Updater.schedule(this)
        render()
    }

    override fun onResume() {
        super.onResume()
        // Back from a settings page: show what it granted.
        render()
    }

    override fun onStop() {
        WidgetWatcher.removeListener(refresh)
        super.onStop()
    }

    @Deprecated("Deprecated in the platform Activity; this app has no AndroidX activity")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        if (!WidgetWatcher.onActivityResult(this, requestCode, resultCode, data)) super.onActivityResult(requestCode, resultCode, data)
        render()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        render()
    }

    private fun checkForUpdate() {
        updateNote = getString(R.string.update_checking)
        render()
        val app = applicationContext
        Thread {
            val note = try {
                val release = Updater.check(app)
                if (release == null) {
                    getString(R.string.update_latest)
                } else {
                    runOnUiThread { updateNote = getString(R.string.update_installing, release.versionCode); render() }
                    Updater.install(app, release)
                    null
                }
            } catch (e: Exception) {
                getString(R.string.update_failed, e.message ?: e.javaClass.simpleName)
            }
            runOnUiThread {
                if (note != null) updateNote = note
                render()
            }
        }.start()
    }

    private fun render() {
        if (isDestroyed) return
        renderStatus()
        renderLog()
        renderChats()
        version.text = getString(R.string.version, Updater.installedVersion(this).toInt()) + (updateNote?.let { " · $it" } ?: "")
    }

    private fun renderStatus() {
        status.removeAllViews()
        HealthMonitor.problem(this)?.let { problem ->
            status.addView(text(getString(R.string.problem_line, HealthMonitor.describe(this, problem), WHEN.format(time(HealthMonitor.since(this)))), 15f).apply {
                setTextColor(getColor(R.color.problem))
            })
            status.addView(button(getString(R.string.send_report)) {
                val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, HealthMonitor.report(this))
                open(Intent.createChooser(send, getString(R.string.send_report)))
            })
        }
        if (WidgetWatcher.widgetInfo(this) == null) {
            status.addView(text(getString(R.string.step_widget)))
            val providers = WidgetWatcher.providers(this)
            for (p in providers) status.addView(button(getString(R.string.connect_widget, p.loadLabel(packageManager))) { WidgetWatcher.bind(this, p) })
            if (providers.isEmpty()) status.addView(text(getString(R.string.no_widget)))
        } else {
            val at = WidgetWatcher.lastReadAt(this)
            status.addView(text(if (at == 0L) getString(R.string.widget_connected) else getString(R.string.widget_connected_read, CLOCK.format(time(at)))))
        }
        status.addView(text(getString(if (WatchService.running) R.string.running else R.string.not_running)))
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            status.addView(text(getString(R.string.step_notifications)))
            status.addView(button(getString(R.string.allow_notifications)) { requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1) })
        }
        if (!PollAlarm.canUseFullScreen(this)) {
            status.addView(text(getString(R.string.step_full_screen)))
            status.addView(button(getString(R.string.allow_full_screen)) {
                open(Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT, Uri.parse("package:$packageName")))
            })
        }
        if (!getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(packageName)) {
            status.addView(text(getString(R.string.battery_hint)))
            status.addView(button(getString(R.string.battery_allow)) {
                open(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName")))
            })
        }
        if (Updater.enabled && !packageManager.canRequestPackageInstalls()) {
            status.addView(text(getString(R.string.update_need_permission)))
            status.addView(button(getString(R.string.update_allow)) {
                open(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:$packageName")))
            })
        }
    }

    private fun renderLog() {
        log.removeAllViews()
        val events = EventLog.load(this).takeLast(5).asReversed()
        if (events.isEmpty()) log.addView(text(getString(R.string.log_empty), 13f))
        for (e in events) log.addView(text(WHEN.format(time(e.at)) + "  " + e.text, 13f))
    }

    private fun renderChats() {
        chats.removeAllViews()
        val follow = WidgetWatcher.followed(this)
        val seen = WidgetWatcher.chatsSeen(this)
        // Followed first; a typed name that the widget has not shown yet has no time.
        val names = (follow.filter { f -> seen.none { ChatNames.same(it.first, f) } } + seen.map { it.first })
            .sortedByDescending { ChatNames.isFollowed(follow, it) }
        if (names.isEmpty()) chats.addView(text(getString(R.string.no_chats)))
        val lastSeen = seen.toMap()
        for (name in names) {
            chats.addView(CheckBox(this).apply {
                text = name + (lastSeen[name]?.let { "  ·  " + WHEN.format(time(it)) } ?: "")
                isChecked = ChatNames.isFollowed(follow, name)
                textSize = 16f
                setPadding(0, dp(6), 0, dp(6))
                setOnCheckedChangeListener { _, on -> WidgetWatcher.setFollowed(this@MainActivity, name, on) }
            })
        }
    }

    private fun open(intent: Intent) {
        try {
            startActivity(intent)
        } catch (_: ActivityNotFoundException) {
            startActivity(Intent(Settings.ACTION_SETTINGS))
        }
    }

    private fun time(ms: Long) = Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault())

    private fun vertical() = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

    private fun heading(s: String) = text(s, 18f).apply { setPadding(0, dp(12), 0, dp(4)) }

    private fun text(s: String, size: Float = 14f) = TextView(this).apply {
        text = s
        textSize = size
        setPadding(0, dp(4), 0, dp(2))
    }

    private fun button(label: String, onClick: () -> Unit) = Button(this).apply {
        text = label
        setOnClickListener { onClick() }
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private companion object {
        val WHEN: DateTimeFormatter = DateTimeFormatter.ofPattern("dd/MM HH:mm")
        val CLOCK: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")
    }
}
