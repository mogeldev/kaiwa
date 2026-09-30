package com.kaiwa.chat

import android.content.Intent
import android.os.Bundle
import com.kaiwa.chat.databinding.ActivitySettingsBinding

/**
 * The Settings menu: the three things that are not chatting.
 *
 * A screen of its own rather than the popup menu it used to be, because a popup cannot be
 * driven by focus the way everything else here can - and because the config import is the
 * point of the app on a keypad, which is not second place in an overflow.
 *
 * Every row is an ordinary focusable view, so there is no key handling on this screen: the
 * framework's focus search walks the rows, and Up from the first one reaches the Back button.
 */
class SettingsActivity : KeypadActivity() {

    private lateinit var binding: ActivitySettingsBinding

    /** The same picker, import and report as the welcome screen's. */
    private val picker = ConfigPicker(this) { showImportReport(it) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        SystemBars.top(binding.headerBar)
        SystemBars.bottom(binding.root)

        binding.buttonBack.setOnClickListener { finish() }
        binding.rowImport.setOnClickListener { picker.launch() }
        binding.rowApiSettings.setOnClickListener {
            startActivity(Intent(this, ApiSettingsActivity::class.java))
        }
        binding.rowAbout.setOnClickListener {
            startActivity(Intent(this, AboutActivity::class.java))
        }
    }
}
