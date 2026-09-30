package com.kaiwa.chat

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.view.View
import android.widget.EditText
import com.kaiwa.chat.databinding.ActivityApiSettingsBinding

/**
 * The API connection: key, base URL, model, system prompt.
 *
 * "Test connection" calls GET /models: it validates the key, reports the models the
 * key can actually use, and (on this SoftBank handset) triggers the network consent
 * dialog the first time the app touches the network.
 *
 * Reached from the Settings menu, which is why this is not the screen named for Settings.
 */
class ApiSettingsActivity : KeypadActivity() {

    private lateinit var binding: ActivityApiSettingsBinding
    private lateinit var prefs: Prefs
    private val main = Handler(Looper.getMainLooper())
    private var testing = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityApiSettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        SystemBars.top(binding.headerBar)
        SystemBars.bottom(binding.root)

        prefs = Prefs(this)
        binding.editApiKey.setText(prefs.apiKey)
        binding.editBaseUrl.setText(prefs.baseUrl)
        binding.editModel.setText(prefs.model)
        binding.editSystemPrompt.setText(prefs.systemPrompt)

        binding.buttonBack.setOnClickListener { finish() }
        binding.buttonTest.setOnClickListener { testConnection() }
        // Only Save writes the form. Back, and the system Back, leave it as it was stored.
        binding.buttonSave.setOnClickListener {
            if (writeFields(prefs)) finish() else showStatus(getString(R.string.invalid_base_url))
        }
    }

    /**
     * The arrow keys, which the fields would otherwise keep. An `EditText` holds them for as
     * long as it can, and these fields swallow Down even at the end of a single line, so
     * without this the form cannot be walked at all: Down never leaves the first field, and Up
     * never leaves any of them. Both keys are handed to the focus search instead, but only when
     * the caret is on the field's first or last line, so the arrows still edit the wrapped
     * system prompt. The buttons are left to the framework, which handles them by itself.
     */
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN) {
            if (event.keyCode == KeyEvent.KEYCODE_CALL) {
                testConnection()
                return true
            }
            if (event.keyCode == KeyEvent.KEYCODE_DPAD_UP && caretIsAtTheTop()) {
                return moveFocus(View.FOCUS_UP)
            }
            if (event.keyCode == KeyEvent.KEYCODE_DPAD_DOWN && caretIsAtTheBottom()) {
                return moveFocus(View.FOCUS_DOWN)
            }
        }
        return super.dispatchKeyEvent(event)
    }

    /** Hands the arrow to the focus search, which also knows about the header above the form. */
    private fun moveFocus(direction: Int): Boolean {
        val next = currentFocus?.focusSearch(direction) ?: return false
        return next.requestFocus()
    }

    /** True when Up belongs to the screen rather than to the caret. */
    private fun caretIsAtTheTop(): Boolean {
        val field = currentFocus as? EditText ?: return false
        val layout = field.layout ?: return true
        return layout.getLineForOffset(field.selectionStart) == 0
    }

    /**
     * The same downwards. Line-based rather than offset-based on purpose: a caret at the end of
     * the first line of a wrapped prompt can still go down, and only making the arrow leave at
     * the very end of the text would mean holding the key until the caret got there. A
     * single-line field has one line, so it always answers true to both.
     */
    private fun caretIsAtTheBottom(): Boolean {
        val field = currentFocus as? EditText ?: return false
        val layout = field.layout ?: return true
        return layout.getLineForOffset(field.selectionEnd) == layout.lineCount - 1
    }

    /**
     * Writes the form into [target], all of it or nothing: a base URL that is not an http://
     * or https:// address would fail every request, so the form is kept on screen to be
     * fixed instead.
     */
    private fun writeFields(target: Prefs): Boolean {
        val baseUrl = binding.editBaseUrl.text.toString().trim().ifBlank { Prefs.DEFAULT_BASE_URL }
        if (!Urls.isAbsolute(baseUrl)) return false

        target.apiKey = binding.editApiKey.text.toString()
        target.baseUrl = baseUrl
        target.model = binding.editModel.text.toString().ifBlank { Prefs.DEFAULT_MODEL }
        target.systemPrompt = binding.editSystemPrompt.text.toString()
        return true
    }

    private fun testConnection() {
        if (testing) return

        // The test runs on what is typed, not on what is stored, and saves none of it: it
        // works on a copy of the settings with the form written into it.
        val candidate = prefs.snapshot()
        if (!writeFields(candidate)) {
            showStatus(getString(R.string.invalid_base_url))
            return
        }

        // The chat screen's own rule: a key, or a local http:// server the config allowed,
        // which normally has none.
        if (!candidate.isConfigured) {
            showStatus(getString(R.string.need_api_key))
            return
        }

        // The button stays enabled, and [testing] turns a second press away instead:
        // disabling the focused button would hand focus to the first view in the window,
        // the header's Back, and the next Center would then leave the screen.
        testing = true
        showStatus(getString(R.string.testing))

        Thread {
            val result = ApiClient.listModels(candidate)
            main.post {
                showStatus(
                    when (result) {
                        is ApiClient.Result.Success -> result.content
                        is ApiClient.Result.Failure -> getString(R.string.test_failed, result.message)
                    }
                )
                testing = false
            }
        }.start()
    }

    /**
     * The status line is the last thing on the screen, and it is not focusable, so the keypad
     * will never scroll to it. It scrolls itself into view instead - by the smallest amount
     * that uncovers it, or, if the list of models is taller than the screen, to its first
     * line, because the answer is at the top of it.
     */
    private fun showStatus(text: String) {
        binding.textStatus.text = text
        binding.textStatus.visibility = View.VISIBLE

        binding.textStatus.post {
            val status = binding.textStatus
            val viewport = binding.scrollSettings.height
            val target = if (status.height > viewport) status.top
                         else (status.bottom - viewport).coerceAtLeast(0)
            binding.scrollSettings.smoothScrollTo(0, target)
        }
    }
}
