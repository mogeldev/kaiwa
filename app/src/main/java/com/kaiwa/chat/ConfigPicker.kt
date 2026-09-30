package com.kaiwa.chat

import android.app.Activity
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog

/**
 * The config import, as every screen that offers one runs it: the system file picker, the
 * file read and applied off the main thread, and [onReport] back on it with what happened.
 *
 * Off the main thread because the picker hands back whatever document provider the file
 * lives in, a cloud drive among them, and one that has to fetch the file first can take long
 * enough to freeze the screen. Construct it while the activity is being constructed, as the
 * result API requires.
 *
 * Every MIME type is accepted rather than only "application/json": a config named .txt, or
 * saved with no extension, would otherwise be greyed out and unselectable - a dead end when
 * the picker has to be driven with four arrow keys.
 */
class ConfigPicker(
    private val activity: ComponentActivity,
    private val onReport: (ConfigImporter.Report) -> Unit
) {
    /** One import at a time: a press while a file is still being read does nothing. */
    private var busy = false

    private val launcher =
        activity.registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri == null) {
                Toast.makeText(activity, R.string.import_cancelled, Toast.LENGTH_SHORT).show()
            } else {
                busy = true
                val prefs = Prefs(activity)
                Thread {
                    val report = ConfigImporter.importFromUri(activity, uri, prefs)
                    activity.runOnUiThread {
                        busy = false
                        // The settings are written either way; only the report needs a screen.
                        if (!activity.isFinishing && !activity.isDestroyed) onReport(report)
                    }
                }.start()
            }
        }

    fun launch() {
        if (!busy) launcher.launch(arrayOf("*/*"))
    }
}

/**
 * What an import did, in the same dialog on every screen that offers one. [onClosed] runs
 * however the dialog goes, OK or Back - Back closes a dialog without pressing any of its
 * buttons, so a button listener alone would miss it.
 */
fun Activity.showImportReport(report: ConfigImporter.Report, onClosed: () -> Unit = {}) {
    AlertDialog.Builder(this)
        .setTitle(report.title)
        .setMessage(report.message)
        .setPositiveButton(android.R.string.ok, null)
        .setOnDismissListener { if (!isFinishing && !isDestroyed) onClosed() }
        .keepingSendKey()
        .show()
}
