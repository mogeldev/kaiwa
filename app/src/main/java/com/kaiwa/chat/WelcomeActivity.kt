package com.kaiwa.chat

import android.content.Intent
import android.os.Bundle
import android.view.View
import com.kaiwa.chat.databinding.ActivityWelcomeBinding

/**
 * The first screen a new install shows: a greeting, and the way to import a config file.
 *
 * It exists because the app used to open straight onto Settings, which is the wrong first
 * impression on a keypad-only handset. The whole point of the config import is that the
 * API key never has to be typed, so the file picker is what should be offered first.
 * Typing a key in by hand is still one button down, and goes straight to the API form.
 *
 * [MainActivity] starts this only while [Prefs.isConfigured] is false, and this finishes
 * as soon as it becomes true, so the screen is gone for good once setup is done.
 */
class WelcomeActivity : KeypadActivity() {

    private lateinit var binding: ActivityWelcomeBinding
    private lateinit var prefs: Prefs

    /** Set only when Settings was opened from here, so a return can carry straight on. */
    private var returningFromSettings = false

    /** The same picker and import as the Settings menu's. */
    private val picker = ConfigPicker(this) { showReport(it) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityWelcomeBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // No header on this screen, so the whole thing is a column between the bars.
        SystemBars.vertical(binding.root)

        prefs = Prefs(this)

        // Recreated once setup is already done - after the process was killed, or a
        // configuration change - while the report was up or the API form was on top. Neither
        // the dialog nor returningFromSettings survives that, and staying here would strand a
        // user who has nothing left to set up.
        if (savedInstanceState != null && prefs.isConfigured) {
            startChatting()
            return
        }

        binding.buttonImport.setOnClickListener { picker.launch() }
        binding.buttonSettings.setOnClickListener {
            // A key typed by hand is setup too, so coming back may be the last step. This
            // goes straight to the form, past the Settings menu: the other two rows there
            // are the import, which this screen offers itself, and About.
            returningFromSettings = true
            startActivity(Intent(this, ApiSettingsActivity::class.java))
        }
    }

    override fun onResume() {
        super.onResume()
        if (returningFromSettings) {
            returningFromSettings = false
            if (prefs.isConfigured) startChatting()
        }
    }

    /**
     * The same report the Settings menu shows, and the only place the app moves on from:
     * an import that worked can be seen to have worked before the chat opens, however the
     * report is closed.
     */
    private fun showReport(report: ConfigImporter.Report) {
        showImportReport(report) {
            if (prefs.isConfigured) startChatting() else showStillUnconfigured()
        }
    }

    /**
     * A file can be perfectly valid and still leave no key - one that only names a model,
     * or one that unset the key with null. Say so, rather than looping back to the same
     * screen with no explanation of why nothing happened.
     */
    private fun showStillUnconfigured() {
        binding.textWelcomeStatus.text = getString(R.string.welcome_still_unconfigured)
        binding.textWelcomeStatus.visibility = View.VISIBLE
    }

    /** Hands over to the chat screen and gets out of the back stack while doing it. */
    private fun startChatting() {
        startActivity(Intent(this, MainActivity::class.java))
        finish()
    }
}
