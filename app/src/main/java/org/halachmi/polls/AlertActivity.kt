package org.halachmi.polls

import android.app.Activity
import android.app.KeyguardManager
import android.content.Intent
import android.graphics.Typeface
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast

/**
 * "There is a poll in group X", over the lock screen when the ring comes in, and when the
 * notification is tapped. One button opens the chat; the other only stops the ring. Started with
 * [PollAlarm.EXTRA_OPEN] (the notification's own button), it opens the chat as soon as it is shown.
 *
 * Opening clicks the chat's row in the hosted widget. That click is sent by Android's widget code,
 * which gives the host no control over the launch and drops a blocked one silently. So it is only
 * sent while this screen is resumed and focused, and if this screen has not been paused within
 * [FALLBACK_MS], WhatsApp did not come up and is opened directly, on its chat list.
 */
class AlertActivity : Activity() {
    private val main = Handler(Looper.getMainLooper())
    private var chat = ""
    private var question = ""
    private var openWhenFocused = false
    private var paused = true

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setShowWhenLocked(true)
        setTurnScreenOn(true)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        take(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        take(intent)
    }

    override fun onResume() {
        super.onResume()
        paused = false
    }

    override fun onPause() {
        paused = true
        super.onPause()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus && openWhenFocused) {
            openWhenFocused = false
            open()
        }
    }

    private fun take(intent: Intent) {
        chat = intent.getStringExtra(PollAlarm.EXTRA_CHAT).orEmpty()
        question = intent.getStringExtra(PollAlarm.EXTRA_QUESTION).orEmpty()
        render()
        if (intent.getBooleanExtra(PollAlarm.EXTRA_OPEN, false)) {
            if (hasWindowFocus()) open() else openWhenFocused = true
        }
    }

    private fun render() {
        val pad = dp(24)
        val white = getColor(R.color.alert_text)
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(pad, pad, pad, pad)
            setBackgroundColor(getColor(R.color.alert_background))
            fitsSystemWindows = true
        }
        column.addView(label("📊", 64f, white))
        column.addView(label(getString(R.string.poll_in_short), 22f, white))
        column.addView(label(chat, 30f, white).apply {
            setTypeface(typeface, Typeface.BOLD)
            setPadding(0, dp(8), 0, dp(8))
        })
        if (question.isNotEmpty()) {
            column.addView(label(question, 20f, getColor(R.color.alert_question)).apply { setPadding(0, 0, 0, dp(24)) })
        }
        column.addView(
            Button(this).apply {
                text = getString(R.string.open_poll)
                textSize = 22f
                setOnClickListener { open() }
            },
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(72)),
        )
        column.addView(
            Button(this).apply {
                text = getString(R.string.later)
                textSize = 16f
                setOnClickListener {
                    PollAlarm.stop(this@AlertActivity, chat, all = false)
                    finish()
                }
            },
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                topMargin = dp(16)
            },
        )
        setContentView(column)
    }

    private fun label(text: String, size: Float, color: Int) = TextView(this).apply {
        this.text = text
        textSize = size
        setTextColor(color)
        gravity = Gravity.CENTER
    }

    private fun open() {
        PollAlarm.stop(this, chat, all = true)
        val keyguard = getSystemService(KeyguardManager::class.java)
        if (keyguard.isKeyguardLocked) {
            // WhatsApp would open behind the lock screen: unlock first.
            keyguard.requestDismissKeyguard(this, object : KeyguardManager.KeyguardDismissCallback() {
                override fun onDismissSucceeded() {
                    main.postDelayed({ openChat() }, 300)
                }

                override fun onDismissError() = openChat()
            })
        } else {
            openChat()
        }
    }

    private fun openChat() {
        if (WidgetWatcher.clickChatRow(this, chat)) {
            main.postDelayed({
                if (!paused && !isFinishing) openWhatsApp()
                finish()
            }, FALLBACK_MS)
        } else {
            openWhatsApp()
            finish()
        }
    }

    private fun openWhatsApp() {
        if (!WidgetWatcher.openWhatsApp(this)) Toast.makeText(this, R.string.no_whatsapp, Toast.LENGTH_LONG).show()
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private companion object {
        const val FALLBACK_MS = 1_500L
    }
}
