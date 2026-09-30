package com.kaiwa.chat

import android.os.Bundle
import android.view.KeyEvent
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import com.kaiwa.chat.databinding.ActivityChatListBinding

/**
 * The chat list: pick one, or delete one. Starting a new chat belongs to the sidebar's
 * "+" on the chat screen, which switches straight into it as well.
 *
 * Choosing a chat writes it to [Prefs.activeChatId] and finishes; MainActivity notices the
 * change in onResume and loads it. That keeps the two screens from having to hand results
 * back and forth through an Intent.
 */
class ChatListActivity : KeypadActivity() {

    private lateinit var binding: ActivityChatListBinding
    private lateinit var prefs: Prefs
    private lateinit var adapter: ChatListAdapter

    private var chats: List<ChatMeta> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityChatListBinding.inflate(layoutInflater)
        setContentView(binding.root)

        SystemBars.top(binding.headerBar)
        SystemBars.bottom(binding.root)

        prefs = Prefs(this)
        chats = ChatStore.ensureChatExists(this)

        // Delete belongs to the row that was pressed, so nothing here depends on the
        // list's selection - which never fired reliably for every D-pad move anyway.
        adapter = ChatListAdapter(
            context = this,
            onOpen = { open(it.id) },
            onDelete = { confirmDelete(it) }
        )
        adapter.activeId = prefs.activeChatId
        adapter.submit(chats)
        binding.listChats.adapter = adapter

        // Told that its rows take focus, the list moves focus from row to row by itself,
        // scrolls the next one into view, and keeps its own selection on the focused row.
        binding.listChats.itemsCanFocus = true

        // The one way out that does not depend on a system Back key this handset may or may
        // not have. Up from the first row reaches it: the list has no row further up, so the
        // framework's focus search carries the key on to the header.
        binding.buttonBack.setOnClickListener { finish() }
    }

    /**
     * The list draws no selection of its own any more - the rows carry the highlight - so
     * without this the screen would open with nothing showing where the keypad is.
     */
    override fun onResume() {
        super.onResume()
        binding.listChats.post { focusRow(prefs.activeChatId) }
    }

    /**
     * Right steps into the focused row's Delete, and left steps back out.
     *
     * Up and down are the list's own, now that it knows its rows take focus. They used to be
     * handled here, by hand, which left the list's selection wherever it was: at either end
     * of the rows laid out the key fell through to the list, which then spent the presses
     * walking that invisible selection - so Up from the top row took one extra press for
     * every row between the top and the open chat before it reached the header. Left and
     * right the list cannot do, because the Delete sits inside its row rather than beside it.
     * Everything else, including the centre key that opens or deletes, is the views' own
     * business.
     */
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action != KeyEvent.ACTION_DOWN) return super.dispatchKeyEvent(event)
        val focused = currentFocus ?: return super.dispatchKeyEvent(event)

        when (event.keyCode) {
            KeyEvent.KEYCODE_DPAD_RIGHT -> {
                val delete = rowFor(focused)?.findViewById<View>(R.id.buttonRowDelete)
                if (delete != null && focused !== delete) {
                    delete.requestFocus()
                    return true
                }
            }

            KeyEvent.KEYCODE_DPAD_LEFT -> {
                val row = rowFor(focused)
                if (row != null && focused !== row) {
                    row.requestFocus()
                    return true
                }
            }
        }
        return super.dispatchKeyEvent(event)
    }

    /** The row [view] belongs to: the row itself, or the row holding the Delete in focus. */
    private fun rowFor(view: View): View? {
        var candidate: View? = view
        while (candidate != null && candidate.parent !== binding.listChats) {
            candidate = candidate.parent as? View
        }
        return candidate
    }

    /** The rows currently laid out, in the order the adapter gave them. */
    private fun rows(): List<View> =
        (0 until binding.listChats.childCount).map { binding.listChats.getChildAt(it) }

    /**
     * Puts the keypad on [chatId]'s row, falling back to the first one.
     *
     * A `ListView` only holds children for the rows it has laid out, so a position in
     * [chats] is not an index into them: on a list longer than the screen the two stop
     * agreeing and the wrong row - or no row - takes focus. Asking the list which position
     * each child belongs to is the only mapping that survives scrolling.
     */
    private fun focusRow(chatId: String) {
        val position = chats.indexOfFirst { it.id == chatId }.takeIf { it >= 0 } ?: 0
        // A row below the fold has no child yet, so bring it into view first.
        binding.listChats.setSelection(position)
        focusPosition(position)
    }

    /**
     * Focuses the row laid out for [position]. `setSelection` only schedules a layout, so
     * that row may not exist on the first pass; retry once, and if it is still not there
     * leave focus alone rather than putting it on some other chat.
     */
    private fun focusPosition(position: Int, retries: Int = 1) {
        binding.listChats.post {
            val row = rows().firstOrNull {
                binding.listChats.getPositionForView(it) == position
            }
            when {
                row != null -> row.requestFocus()
                retries > 0 -> focusPosition(position, retries - 1)
            }
        }
    }

    private fun open(id: String) {
        prefs.activeChatId = id
        finish()
    }

    private fun confirmDelete(chat: ChatMeta) {
        AlertDialog.Builder(this)
            .setTitle(R.string.delete_chat_title)
            .setMessage(getString(R.string.delete_chat_message, chat.title))
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.action_delete) { _, _ -> delete(chat) }
            .keepingSendKey()
            .show()
    }

    private fun delete(chat: ChatMeta) {
        ChatStore.deleteChat(this, chat.id)

        // ensureChatExists gives us a fresh empty chat when the last one is removed.
        chats = ChatStore.ensureChatExists(this)
        if (chats.none { it.id == prefs.activeChatId }) {
            prefs.activeChatId = chats.first().id
        }

        adapter.activeId = prefs.activeChatId
        adapter.submit(chats)
        Toast.makeText(this, R.string.chat_deleted, Toast.LENGTH_SHORT).show()

        // The row that had focus is gone with the chat it belonged to.
        binding.listChats.post { focusRow(prefs.activeChatId) }
    }
}
