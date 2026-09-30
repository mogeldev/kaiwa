package com.kaiwa.chat

import android.content.Context
import android.text.SpannableString
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.text.style.RelativeSizeSpan
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.TextView
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Renders the transcript as bubbles.
 *
 * A plain BaseAdapter over a ListView rather than RecyclerView: on a 1 GB device the
 * built-in widget is lighter and has zero extra dependencies.
 *
 * Which side a bubble sits on is the only thing that says who is talking, so there is no
 * speaker label. The user's goes right and blue, Kaiwa's left and grey, and a failure goes
 * left in red - it is the reply that failed, not the question.
 */
class ChatAdapter(private val context: Context) : BaseAdapter() {

    private val items = mutableListOf<Message>()

    /**
     * What each bubble says, stamp included, worked out once per [submit] rather than on
     * every bind. A bind happens for every visible row on every step of a scroll, and the
     * text of a message never changes; building it there was garbage for the collector on
     * a Cortex-A7 in the middle of the scroll animation.
     */
    private val texts = mutableListOf<CharSequence>()

    /** The row's views, looked up once when it is inflated rather than on every bind. */
    private class Holder(val bubble: TextView, val spacerStart: View, val spacerEnd: View)

    fun submit(messages: List<Message>) {
        items.clear()
        items.addAll(messages)

        // "Today" as of this submit. A stamp only gains its date at the next one, which is
        // at worst a message sent or a chat opened after midnight away.
        val zone = ZoneId.systemDefault()
        val today = LocalDate.now(zone)
        texts.clear()
        messages.mapTo(texts) { textOf(it, zone, today) }

        notifyDataSetChanged()
    }

    override fun getCount(): Int = items.size
    override fun getItem(position: Int): Message = items[position]
    override fun getItemId(position: Int): Long = position.toLong()

    override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
        val view = convertView
            ?: LayoutInflater.from(context).inflate(R.layout.item_message, parent, false).also {
                it.tag = Holder(
                    bubble = it.findViewById(R.id.textBubble),
                    spacerStart = it.findViewById(R.id.spacerStart),
                    spacerEnd = it.findViewById(R.id.spacerEnd)
                )
            }
        val holder = view.tag as Holder
        val message = items[position]

        // The spacer before the bubble pushes it right for the user; the one after it
        // pushes it left for Kaiwa. Exactly one is ever visible.
        val mine = message.isUser
        holder.spacerStart.visibility = if (mine) View.VISIBLE else View.GONE
        holder.spacerEnd.visibility = if (mine) View.GONE else View.VISIBLE

        val bubble = holder.bubble
        bubble.text = texts[position]
        when {
            message.pending -> {
                // The faded ink says it is not a real message yet.
                bubble.setTextColor(
                    context.getColor(
                        if (mine) R.color.text_on_bubble_muted else R.color.text_secondary
                    )
                )
                bubble.setBackgroundResource(bubbleFor(mine))
            }

            message.isError -> {
                bubble.setTextColor(context.getColor(R.color.text_error))
                bubble.setBackgroundResource(R.drawable.bg_bubble_error)
            }

            else -> {
                bubble.setTextColor(
                    context.getColor(if (mine) R.color.text_on_bubble else R.color.text_primary)
                )
                bubble.setBackgroundResource(bubbleFor(mine))
            }
        }

        return view
    }

    /** What a bubble says: a placeholder's own words, or the message with its stamp. */
    private fun textOf(message: Message, zone: ZoneId, today: LocalDate): CharSequence {
        val mine = message.isUser
        return when {
            // Either the assistant thinking or a voice note still being transcribed; the
            // role decides which. No stamp: it has no time worth printing and no cost yet.
            message.pending ->
                context.getString(if (mine) R.string.transcribing else R.string.thinking)

            message.isError -> stamped(message, R.color.text_muted, zone, today)

            else -> stamped(
                message = message,
                inkRes = if (mine) R.color.text_on_bubble_muted else R.color.text_muted,
                zone = zone,
                today = today
            )
        }
    }

    /**
     * The message with its time - and, for a reply, what it cost - appended in smaller
     * muted type.
     *
     * Inside the bubble rather than on a line beneath it. A line of its own costs about
     * 14dp on every message, and on a two-word message on a 569dp screen that is nearly
     * half the bubble again; as a suffix it costs nothing until the text happens to wrap
     * one word earlier. The bubble is a TextView precisely so this can be one string with
     * spans rather than a second view.
     */
    private fun stamped(
        message: Message,
        inkRes: Int,
        zone: ZoneId,
        today: LocalDate
    ): CharSequence {
        val text = message.content
        val stamp = stampFor(message, zone, today)
        if (stamp.isEmpty()) return text

        val full = "$text  $stamp"
        return SpannableString(full).apply {
            val start = full.length - stamp.length
            setSpan(RelativeSizeSpan(0.72f), start, full.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            setSpan(
                ForegroundColorSpan(context.getColor(inkRes)),
                start, full.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
            )
        }
    }

    /** "07:52", and for a reply with usage "07:52 · 586↑2↓". */
    private fun stampFor(message: Message, zone: ZoneId, today: LocalDate): String {
        val parts = mutableListOf<String>()
        if (message.time > 0) parts += clock(message.time, zone, today)
        if (message.tokensIn > 0 || message.tokensOut > 0) {
            parts += compact(message.tokensIn) + "↑" + compact(message.tokensOut) + "↓"
        }
        return parts.joinToString(" · ")
    }

    /**
     * The bare time for anything from today, and the date as well for anything older.
     * Most messages in a chat this small are from today; the chat list already carries the
     * date for the ones that are not.
     */
    private fun clock(millis: Long, zone: ZoneId, today: LocalDate): String {
        val moment = Instant.ofEpochMilli(millis).atZone(zone)
        return moment.format(if (moment.toLocalDate() == today) TIME else DATE_AND_TIME)
    }

    /** 412, or 1.2k once the number stops being readable at a glance. */
    private fun compact(tokens: Int): String =
        if (tokens < 1000) tokens.toString()
        else String.format(Locale.US, "%.1fk", tokens / 1000.0)

    private fun bubbleFor(mine: Boolean): Int =
        if (mine) R.drawable.bg_bubble_user else R.drawable.bg_bubble_kaiwa

    private companion object {
        // Built once: a formatter parses its pattern every time one is made.
        val TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm", Locale.US)
        val DATE_AND_TIME: DateTimeFormatter =
            DateTimeFormatter.ofPattern("d.M. HH:mm", Locale.US)
    }
}
