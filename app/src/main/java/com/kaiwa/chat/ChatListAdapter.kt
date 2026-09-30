package com.kaiwa.chat

import android.content.Context
import android.graphics.Typeface
import android.text.format.DateUtils
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.Button
import android.widget.TextView

/**
 * Renders the chat list. Same plain BaseAdapter approach as the transcript: no
 * RecyclerView dependency on a 1 GB device.
 *
 * Each row carries its own Delete, and the row itself takes the click that opens a chat.
 * That is not the usual ListView arrangement - normally a focusable Delete inside a row
 * swallows the item click, and the list's own selector stops drawing - but it is the one
 * that leaves every action reachable from a keypad. The highlight lives on the row for the
 * same reason; see `bg_row`.
 *
 * The open chat is also marked in bold, which does not fight the highlight.
 */
class ChatListAdapter(
    private val context: Context,
    private val onOpen: (ChatMeta) -> Unit,
    private val onDelete: (ChatMeta) -> Unit
) : BaseAdapter() {

    private val items = mutableListOf<ChatMeta>()

    /** Which chat is currently open, so it can be marked in the list. */
    var activeId: String = ""

    fun submit(chats: List<ChatMeta>) {
        items.clear()
        items.addAll(chats)
        notifyDataSetChanged()
    }

    override fun getCount(): Int = items.size
    override fun getItem(position: Int): ChatMeta = items[position]
    override fun getItemId(position: Int): Long = position.toLong()

    override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
        val view = convertView
            ?: LayoutInflater.from(context).inflate(R.layout.item_chat, parent, false)

        val chat = items[position]
        val title = view.findViewById<TextView>(R.id.textChatTitle)
        val whenText = view.findViewById<TextView>(R.id.textChatWhen)
        val delete = view.findViewById<Button>(R.id.buttonRowDelete)

        title.text = chat.title
        title.setTypeface(null, if (chat.id == activeId) Typeface.BOLD else Typeface.NORMAL)
        whenText.text = DateUtils.getRelativeTimeSpanString(
            chat.updated,
            System.currentTimeMillis(),
            DateUtils.MINUTE_IN_MILLIS
        )

        // Both rebound on every pass, since a recycled view may be showing another chat.
        view.setOnClickListener { onOpen(chat) }
        delete.setOnClickListener { onDelete(chat) }

        return view
    }
}
