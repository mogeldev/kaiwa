package com.kaiwa.chat

import android.view.View
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding

/**
 * Keeps a screen clear of the status and navigation bars.
 *
 * The NP902KC needs none of this. At API 27 the framework hands the app a window that already
 * stops short of both bars, so the insets arrive as zero and every call below adds nothing -
 * which is what lets one piece of code be right on both phones. From Android 15 the system
 * draws the app *behind* the bars instead, and a screen that draws its own header has to keep
 * its own content out of the way.
 *
 * The padding is added to whatever the layout already set, never replacing it, and a view's
 * background still fills the area behind a bar. That is why a header pads itself rather than
 * the column beneath it, and why the chat screen's sidebar pads rather than its parent: what
 * shows behind the status bar is then the header's own colour and not a band of window
 * background. On the phone where the insets are zero the question does not arise.
 *
 * Nothing here consumes the insets, so a view inside one of these - the transcript, a form
 * field - still sees them if it ever needs to.
 *
 * The view has to be able to grow into the padding. Every view this is called on is
 * `match_parent` or `wrap_content`, which is deliberate: a header fixed at one control's
 * height has no room for a status bar's worth of padding, and pushes its own contents out of
 * itself rather than getting taller - which is exactly what the first version of this did.
 */
object SystemBars {

    /**
     * The status bar, and any display cutout. For a view whose own background should reach the
     * top of the screen.
     */
    fun top(view: View) = pad(view, top = true, bottom = false)

    /**
     * The navigation bar - or the keyboard, when it is up, since on a phone that has one it
     * covers the same bottom edge. Both are measured rather than guessed: a window the system
     * has resized for the keyboard reports no keyboard inset, so this cannot count it twice.
     */
    fun bottom(view: View) = pad(view, top = false, bottom = true)

    /** Both, for a column that runs the full height of the screen. */
    fun vertical(view: View) = pad(view, top = true, bottom = true)

    private fun pad(view: View, top: Boolean, bottom: Boolean) {
        // Read once: the layout's own padding, to add to rather than overwrite.
        val laidOutTop = view.paddingTop
        val laidOutBottom = view.paddingBottom
        ViewCompat.setOnApplyWindowInsetsListener(view) { v, insets ->
            val bars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
            )
            val keyboard = insets.getInsets(WindowInsetsCompat.Type.ime())
            v.updatePadding(
                top = laidOutTop + if (top) bars.top else 0,
                bottom = laidOutBottom + if (bottom) maxOf(bars.bottom, keyboard.bottom) else 0
            )
            insets
        }
    }
}
