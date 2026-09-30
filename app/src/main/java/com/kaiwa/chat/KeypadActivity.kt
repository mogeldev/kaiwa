package com.kaiwa.chat

import android.view.KeyEvent
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity

/**
 * What every screen shares: the SEND key never leaves the app.
 *
 * SEND is KEYCODE_CALL, and a CALL key that nothing consumes falls through to the system,
 * which opens the phone's dialer on top of whatever was on screen. The chat screen and the API
 * form give the key a job of their own and handle it before this sees it; on every other screen
 * it is swallowed here. Dialogs are windows of their own that never pass through an activity's
 * dispatchKeyEvent, so they are built with [keepingSendKey] for the same reason.
 */
abstract class KeypadActivity : AppCompatActivity() {

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.keyCode == KeyEvent.KEYCODE_CALL) return true
        return super.dispatchKeyEvent(event)
    }
}

/** Keeps SEND from reaching the dialer while the dialog being built is up. */
fun AlertDialog.Builder.keepingSendKey(): AlertDialog.Builder =
    setOnKeyListener { _, keyCode, _ -> keyCode == KeyEvent.KEYCODE_CALL }
