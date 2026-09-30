package com.kaiwa.chat

import android.os.Bundle
import com.kaiwa.chat.databinding.ActivityAboutBinding

/**
 * What this app is and which build it is, on a screen of its own.
 *
 * It is reached from Settings rather than from the sidebar: it is not something you reach for
 * while chatting, and the sidebar has no room left for another glyph.
 *
 * There is nothing to focus here but Back, so there is no key handling either - the header
 * button takes focus on the way in and Center leaves.
 */
class AboutActivity : KeypadActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val binding = ActivityAboutBinding.inflate(layoutInflater)
        setContentView(binding.root)

        SystemBars.top(binding.headerBar)
        SystemBars.bottom(binding.root)

        binding.buttonBack.setOnClickListener { finish() }
        binding.textAboutVersion.text = getString(R.string.about_version, BuildConfig.VERSION_NAME)
    }
}
