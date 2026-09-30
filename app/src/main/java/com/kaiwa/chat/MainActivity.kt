package com.kaiwa.chat

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import com.kaiwa.chat.databinding.ActivityMainBinding
import java.lang.ref.WeakReference

/**
 * The chat screen: the transcript with the input box beneath it on the left, and the
 * sidebar down the right-hand edge.
 *
 * The sidebar carries everything the screen can do: a "+" for a new chat, the chat-list
 * symbol, the gear that opens the Settings menu, and the transcript's own controls below
 * them. The open chat's name lives in the chat list rather than up here, and the app's name
 * is on the launcher.
 *
 * A new install never sees this screen until it is configured - onCreate hands over to
 * [WelcomeActivity] first.
 */
class MainActivity : KeypadActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var prefs: Prefs
    private lateinit var adapter: ChatAdapter
    private lateinit var recorder: VoiceRecorder

    private val messages = mutableListOf<Message>()
    private val main = Handler(Looper.getMainLooper())
    private var chatId: String = ""

    /**
     * Whether the chat on screen has a reply on its way - asked from here, from a screen
     * since replaced, or by [deliverTo] while the chat was not open. Nothing more goes out
     * to it until that reply lands, so a second question can never overtake the first.
     */
    private val waiting: Boolean get() = chatId in awaitingReply

    /** Voice input state. */
    private var recording = false
    private var transcribing = false

    /**
     * The chat a recording was made in. Transcription is slow enough that the user can
     * have moved elsewhere by the time it comes back, and the message still belongs to
     * the chat that asked for it.
     */
    private var recordingChatId = ""

    /** The "Transcribing..." row, kept so it can be taken down again. */
    private var transcribingRow: Message? = null

    /**
     * A transcript that arrived while a reply was still on its way - the chat it belongs to,
     * and the words - sent the moment that reply lands.
     */
    private var queued: Pair<String, String>? = null

    /** Stops a recording that has run to its limit, so a forgotten one cannot run away. */
    private val autoStop = Runnable { finishRecording() }

    /** Recording needs a dangerous permission, asked for on the first press of the mic. */
    private val requestMic =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) startRecording() else toast(R.string.mic_denied)
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Each column keeps clear of the system bars in its own right: the sidebar because
        // its buttons sit at the foot of the screen, the transcript column because the
        // message box does. Padding the two rather than the row that holds them is what
        // lets the sidebar's own background still reach the edges.
        SystemBars.vertical(binding.columnTranscript)
        SystemBars.vertical(binding.columnControls)

        prefs = Prefs(this)
        adapter = ChatAdapter(this)
        recorder = VoiceRecorder(this)
        binding.listMessages.adapter = adapter

        // First run: hand over to the welcome screen instead of opening Settings on the
        // user. Nothing here is ever drawn, because the activity finishes immediately -
        // but the view is inflated first on purpose: onResume() below can still run before
        // this activity is finally destroyed, and it expects binding and adapter to exist.
        if (!prefs.isConfigured) {
            startActivity(Intent(this, WelcomeActivity::class.java))
            finish()
            return
        }

        // From here on, this is the screen that replies still in flight are delivered to.
        live = WeakReference(this)

        // Belt and braces alongside the layout attributes: the transcript must never take
        // focus, so the keypad cannot select or highlight a message.
        binding.listMessages.isFocusable = false
        binding.listMessages.isFocusableInTouchMode = false

        binding.buttonSend.setOnClickListener { send() }
        binding.buttonScrollUp.setOnClickListener { scrollTranscript(-1) }
        binding.buttonScrollDown.setOnClickListener { scrollTranscript(1) }
        binding.buttonMic.setOnClickListener { toggleRecording() }

        // The three actions that used to be action-bar items, now ordinary buttons at the
        // foot of the sidebar.
        binding.buttonNewChat.setOnClickListener {
            // Straight into a fresh chat, with no detour through the chat list.
            openChat(ChatStore.createChat(this).id)
        }
        binding.buttonChats.setOnClickListener {
            startActivity(Intent(this, ChatListActivity::class.java))
        }
        binding.buttonSettings.setOnClickListener { openSettings() }

        // Send from an on-screen keyboard's own send action. The hardware ENTER never gets
        // this far: dispatchKeyEvent sends on it before the box sees the key.
        binding.editInput.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEND) {
                send()
                true
            } else {
                false
            }
        }

        openChat(resolveActiveChat())
    }

    override fun onResume() {
        super.onResume()
        // The chat list may have switched chats, or deleted the one on screen, while we
        // were paused. It communicates by writing activeChatId rather than by result.
        val wanted = prefs.activeChatId
        if (wanted.isNotBlank() && wanted != chatId) openChat(wanted) else updateEmptyState()
    }

    /**
     * A recording still running when the screen goes away is thrown away: the microphone
     * is handed back, and nothing is uploaded for a message the user walked away from
     * mid-sentence.
     */
    override fun onStop() {
        super.onStop()
        if (recording) {
            recording = false
            main.removeCallbacks(autoStop)
            recorder.cancel()
            binding.editInput.setHint(R.string.hint_input)
            updateControls()
        }
    }

    override fun onDestroy() {
        if (recording) recorder.cancel()
        if (live?.get() === this) live = null
        super.onDestroy()
    }

    /**
     * The gear in the sidebar. It opens the Settings menu - a screen of its own rather than
     * a popup, since a popup cannot be driven by focus the way the rest of this app can.
     */
    private fun openSettings() {
        startActivity(Intent(this, SettingsActivity::class.java))
    }

    /**
     * The SEND key is the one key that always reaches the app, so it stays the
     * fastest way to send while typing. ENTER also sends, but only while the input holds
     * focus, so a focused button still handles its own click.
     *
     * While a recording is running it stops that instead, since there is no other
     * always-reachable key and "send" is meaningless with a microphone open.
     *
     * Up and down are handled here rather than left to focus search: an EditText consumes
     * the arrow keys for caret movement, so from a focused input the D-pad would never
     * reach the buttons on the right. Left and right are deliberately not intercepted -
     * those still move the caret, which is what you want while editing a message.
     *
     * A typing key pressed in the sidebar - after ▲/▼, say - has nowhere to go: a button
     * ignores it, and the IME only takes keys for a text field. It brings focus back to the
     * message box instead, so the next key types. The key itself is not typed, because
     * handing it to the box directly would go around the IME and its input mode. Both halves
     * of the press are taken here and focus moves on the release, so the box's IME is never
     * given a key-up without the key-down it belongs to.
     */
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.isPrintingKey && currentFocus !== binding.editInput) {
            if (event.action == KeyEvent.ACTION_UP) binding.editInput.requestFocus()
            return true
        }

        if (event.action == KeyEvent.ACTION_DOWN) {
            if (event.keyCode == KeyEvent.KEYCODE_CALL) {
                if (recording) finishRecording() else send()
                return true
            }

            // Some hardware has a MENU key and some does not; where it exists it opens
            // the same Settings menu as the sidebar's gear. Once per press: a held key
            // repeats, and every repeat would stack another Settings screen on the last.
            if (event.keyCode == KeyEvent.KEYCODE_MENU) {
                if (event.repeatCount == 0) openSettings()
                return true
            }

            if (currentFocus === binding.editInput) {
                when (event.keyCode) {
                    // The transcript is deliberately not a focus target - it is read,
                    // not navigated - so up goes straight to the top of the sidebar.
                    // Focus search would otherwise pick the microphone, since the
                    // buttons sit beside the box rather than above it.
                    KeyEvent.KEYCODE_DPAD_UP -> {
                        binding.buttonNewChat.requestFocus()
                        return true
                    }
                    // Send is disabled while a reply or a transcript is on its way, and while
                    // recording, and a disabled view refuses focus - the key would be taken
                    // with nothing to show for it. The lowest control that can have it gets
                    // it instead: the mic, which is how a recording is stopped, or else ▼.
                    KeyEvent.KEYCODE_DPAD_DOWN -> {
                        listOf(binding.buttonSend, binding.buttonMic, binding.buttonScrollDown)
                            .first { it.isEnabled }
                            .requestFocus()
                        return true
                    }
                }
            }

            val isEnter = event.keyCode == KeyEvent.KEYCODE_ENTER ||
                event.keyCode == KeyEvent.KEYCODE_DPAD_CENTER
            if (isEnter && currentFocus === binding.editInput) {
                send()
                return true
            }
        }
        return super.dispatchKeyEvent(event)
    }

    // ---- chats ----

    /** Falls back to the newest chat whenever the remembered one has gone away. */
    private fun resolveActiveChat(): String {
        val chats = ChatStore.ensureChatExists(this)
        val stored = prefs.activeChatId
        return if (chats.any { it.id == stored }) stored else chats.first().id
    }

    private fun openChat(id: String) {
        chatId = id
        prefs.activeChatId = id
        messages.clear()
        messages.addAll(ChatStore.loadMessages(this, id))

        // The disk knows nothing of a request in flight, so a chat reopened mid-request gets
        // back the row that says so: "Transcribing…" for a recording made here, "Thinking…"
        // for a reply on its way. The reply replaces it wherever it was put up.
        if (transcribing && recordingChatId == id) {
            val row = Message(Message.ROLE_USER, "", pending = true)
            transcribingRow = row
            messages.add(row)
        }
        if (id in awaitingReply) messages.add(Message(Message.ROLE_ASSISTANT, "", pending = true))

        // Half-typed text belongs to the chat it was typed in, not to whichever one is
        // opened next. Without this, "+" would carry the draft into the new chat and
        // send it there.
        binding.editInput.setText("")
        updateControls()
        refresh()
        scrollToBottom()
    }

    /**
     * Scrolls the transcript without moving focus, so the button can be pressed again and
     * again to read further back. A typing key then brings focus back to the input - see
     * [dispatchKeyEvent].
     */
    private fun scrollTranscript(direction: Int) {
        if (adapter.count == 0) return
        val step = (binding.listMessages.height * 2 / 3).coerceAtLeast(48)
        binding.listMessages.smoothScrollBy(step * direction, 120)
    }

    // ---- voice input ----

    /**
     * One button, two jobs: it starts a recording and it stops one. The right-hand column
     * is narrow, so there is nowhere for a separate stop control to live.
     */
    private fun toggleRecording() {
        if (recording) {
            finishRecording()
            return
        }
        if (transcribing || waiting) return

        if (!prefs.isVoiceConfigured) {
            toast(
                if (prefs.transcribeUse == Prefs.USE_CLOUD) R.string.mic_not_configured_cloud
                else R.string.mic_not_configured
            )
            return
        }

        val granted = ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
        if (granted) startRecording() else requestMic.launch(Manifest.permission.RECORD_AUDIO)
    }

    private fun startRecording() {
        try {
            recorder.start()
        } catch (e: Exception) {
            toast(getString(R.string.mic_failed, e.message ?: e.javaClass.simpleName))
            return
        }

        recording = true
        recordingChatId = chatId
        binding.editInput.setHint(R.string.hint_recording)
        updateControls()

        // A forgotten recording stops itself. Five minutes is already a long wait at the
        // other end: this box spends about 23 seconds on a request before it starts.
        main.postDelayed(autoStop, MAX_RECORDING_MS)
    }

    /**
     * Stops the recorder and hands the audio to the transcription endpoint. Reached from
     * the mic button, the SEND key and the recording cap alike, so it latches on
     * the first call and ignores the rest.
     */
    private fun finishRecording() {
        if (!recording) return
        recording = false
        main.removeCallbacks(autoStop)
        binding.editInput.setHint(R.string.hint_input)

        val audio = recorder.stop()
        if (audio == null) {
            toast(R.string.mic_nothing_recorded)
            updateControls()
            return
        }

        transcribing = true

        // A row of its own rather than a change of button: this box costs about twenty
        // seconds before it has read a single frame, and the transcript is where the user
        // is looking.
        if (chatId == recordingChatId) {
            val row = Message(Message.ROLE_USER, "", pending = true)
            transcribingRow = row
            messages.add(row)
            refresh()
            scrollToBottom()
        }
        updateControls()

        val target = recordingChatId
        val currentPrefs = prefs.snapshot()
        Thread {
            val result = ApiClient.transcribe(currentPrefs, audio)
            audio.delete()
            main.post { onTranscript(target, result) }
        }.start()
    }

    private fun onTranscript(target: String, result: ApiClient.Result) {
        transcribing = false
        transcribingRow?.let { row ->
            messages.remove(row)
            transcribingRow = null
            if (chatId == target) refresh()
        }
        updateControls()

        // This screen may be gone by now - Back on the handset finishes it, and a
        // configuration change replaces it - so what the words do to a chat is decided by
        // the screen that is live, never by this one's stale copy of the transcript.
        val screen = live?.get()?.takeIf { it.chatId == target }

        when (result) {
            is ApiClient.Result.Failure -> {
                // A failed transcription reads as an error in the chat that asked, the
                // same way a failed reply does. Nothing is filed to disk: error rows are
                // transient by design.
                if (screen != null) {
                    screen.messages.add(replyFrom(result))
                    screen.refresh()
                    screen.scrollToBottom()
                } else {
                    toast(result.message)
                }
            }

            is ApiClient.Result.Success -> {
                val text = result.content
                when {
                    // Silence, or speech the model could not make anything of. Sending an
                    // empty message would look like nothing happened at all.
                    text.isBlank() -> toast(R.string.mic_nothing_transcribed)

                    // The chat was deleted while the words were on their way, and it stays
                    // deleted: nothing is sent, and nothing is filed.
                    !ChatStore.exists(applicationContext, target) -> Unit

                    // The usual path: the words go straight out, so a recording is one
                    // press to start and one to send.
                    screen != null -> screen.sendTranscript(target, text)

                    // The user moved on while the box was thinking. The message belongs
                    // to the chat that asked, not to whichever transcript happens to be
                    // open now, so it is filed there instead of being sent into the wrong
                    // conversation.
                    else -> deliverTo(target, text)
                }
            }
        }
    }

    /**
     * Sends [text] to a chat that is not the one on screen, once its transcription
     * arrives. Transcription takes tens of seconds, so the user being somewhere else by
     * then is ordinary rather than an edge case.
     */
    private fun deliverTo(target: String, text: String) {
        val stored = ChatStore.loadMessages(this, target)
        stored.add(Message(Message.ROLE_USER, text, time = System.currentTimeMillis()))

        // File the question before asking, for the same reason send() does.
        ChatStore.saveMessages(this, target, stored)

        // Marked as waiting like any other question, so the chat opened meanwhile shows the
        // reply is coming and takes nothing new until it has.
        awaitingReply += target
        val history = stored.filter { !it.pending && !it.isError }
        val currentPrefs = prefs.snapshot()

        Thread {
            val result = ApiClient.send(currentPrefs, history)
            main.post { landReply(target, result) }
        }.start()
    }

    // ---- sending ----

    /**
     * Sends what is in the message box.
     *
     * The keys that reach this without the button - SEND, ENTER, the IME's own action - keep
     * to the button's rule: nothing goes out while a reply is on its way, the microphone is
     * open, or a recording is still being transcribed.
     */
    private fun send() {
        if (waiting || recording || transcribing) return

        val text = binding.editInput.text.toString().trim()
        if (text.isEmpty()) return

        if (!prefs.isConfigured) {
            Toast.makeText(this, R.string.need_api_key, Toast.LENGTH_LONG).show()
            startActivity(Intent(this, ApiSettingsActivity::class.java))
            return
        }

        binding.editInput.setText("")
        ask(text)
    }

    /**
     * Sends [text] as the user's next message in the chat on screen, and files the reply when
     * it lands. [send] brings it from the message box; a transcript comes here directly.
     */
    private fun ask(text: String) {
        messages.add(Message(Message.ROLE_USER, text, time = System.currentTimeMillis()))

        // Show a placeholder while the request is in flight, then replace it.
        messages.add(Message(Message.ROLE_ASSISTANT, "", pending = true))

        awaitingReply += chatId
        updateControls()
        refresh()
        scrollToBottom()

        // File the question now rather than when the reply lands: the user may have moved
        // to another chat by then, and this one would otherwise lose what was asked. The
        // save also names an untitled chat after it, so no title ever has to be typed.
        ChatStore.saveMessages(this, chatId, messages)

        // Snapshot the history and the settings: the worker thread must read neither live.
        val history = messages.filter { !it.pending && !it.isError }
        val currentPrefs = prefs.snapshot()
        val askedIn = chatId

        Thread {
            val result = ApiClient.send(currentPrefs, history)
            main.post { landReply(askedIn, result) }
        }.start()
    }

    /**
     * A reply is in, for whichever route asked: its chat is free again, the reply is filed
     * where it belongs, and a transcript that waited for it can go out now.
     */
    private fun landReply(target: String, result: ApiClient.Result) {
        awaitingReply -= target
        fileReply(target, replyFrom(result))
        val screen = live?.get() ?: return
        screen.updateControls()
        screen.releaseQueued()
    }

    /**
     * Sends a transcript into the chat on screen.
     *
     * Straight to [ask] rather than through the message box: the box would cut a long
     * transcript down to its 4000 characters, and replace whatever was typed there while the
     * recording was being transcribed. A reply that is still on its way is waited for.
     */
    private fun sendTranscript(target: String, text: String) {
        when {
            // Not set up any more - an import can unset the key. The words go into the box,
            // where they are kept, and send() says what is missing.
            !prefs.isConfigured -> {
                binding.editInput.setText(text)
                send()
            }
            target in awaitingReply -> queued = target to text
            else -> ask(text)
        }
    }

    /**
     * Sends the transcript that arrived while a reply was on its way, once that reply has
     * landed. A reply for some other chat leaves it where it is.
     */
    private fun releaseQueued() {
        val (target, text) = queued ?: return
        if (target in awaitingReply) return
        queued = null
        if (!ChatStore.exists(applicationContext, target)) return
        val screen = live?.get()?.takeIf { it.chatId == target }
        if (screen != null) screen.sendTranscript(target, text) else deliverTo(target, text)
    }

    private fun replyFrom(result: ApiClient.Result): Message = when (result) {
        is ApiClient.Result.Success -> Message(
            role = Message.ROLE_ASSISTANT,
            content = result.content,
            time = System.currentTimeMillis(),
            tokensIn = result.promptTokens,
            tokensOut = result.completionTokens,
            reasoning = result.reasoning
        )
        is ApiClient.Result.Failure -> Message(
            role = Message.ROLE_ASSISTANT,
            content = result.message,
            isError = true,
            time = System.currentTimeMillis(),
            tokensIn = result.promptTokens,
            tokensOut = result.completionTokens
        )
    }

    /**
     * Files a reply under the chat that asked for it, whether or not that is still the
     * chat on screen. A switch mid-request must not drop the answer into the wrong
     * transcript, and must not lose it either.
     *
     * "On screen" means the screen that is live now, which need not be the one that asked.
     * Back on the handset finishes this activity while its request carries on, and a
     * configuration change replaces it. Saving this instance's own copy of the chat then
     * would put back a transcript the live screen has moved on from - and the live screen's
     * next save would in turn write over a reply it was never shown.
     */
    private fun fileReply(target: String, reply: Message) {
        val screen = live?.get()
        if (screen != null && screen.chatId == target) {
            // The "Thinking…" row, whether it went up when the question was asked or when
            // the chat was reopened while it waited. A chat waits for one reply at a time.
            screen.messages.removeAll { it.pending && !it.isUser }
            screen.messages.add(reply)
            ChatStore.saveMessages(screen, target, screen.messages)
            screen.refresh()
            screen.scrollToBottom()
        } else if (reply.isError) {
            // A chat that is not on screen has nowhere to show an error row, and an error row
            // is never saved. So it is said here, and the chat is left as it was - rather than
            // moved to the top of the list as though an answer had arrived.
            toast(reply.content)
        } else {
            val waiting = ChatStore.loadMessages(applicationContext, target)
            waiting.add(reply)
            ChatStore.saveMessages(applicationContext, target, waiting)
        }
    }

    /**
     * The send and mic buttons, for every combination of idle, sending, recording and
     * transcribing. They are the only status this screen has room for: the column is
     * narrow, so each button has to carry its own state.
     */
    private fun updateControls() {
        val busy = waiting || transcribing

        binding.buttonSend.isEnabled = !busy && !recording
        binding.buttonSend.text = getString(if (busy) R.string.sending else R.string.action_send)

        // Enabled while recording, because that is how a recording is stopped.
        binding.buttonMic.isEnabled = !busy
        binding.buttonMic.text = getString(
            when {
                recording -> R.string.mic_stop
                transcribing -> R.string.sending
                else -> R.string.mic
            }
        )
    }

    private fun refresh() {
        adapter.submit(messages)
        updateEmptyState()
    }

    private fun updateEmptyState() {
        binding.textEmpty.visibility =
            if (adapter.count == 0) View.VISIBLE else View.GONE
    }

    private fun toast(resId: Int) {
        Toast.makeText(this, resId, Toast.LENGTH_SHORT).show()
    }

    private fun toast(text: CharSequence) {
        Toast.makeText(this, text, Toast.LENGTH_SHORT).show()
    }

    /**
     * Jumps to the newest message.
     *
     * The second attempt after layout is deliberate: setSelection() can resolve to nothing
     * when the selection already points at the last row, which is exactly the situation
     * after the user has scrolled away with the buttons.
     */
    private fun scrollToBottom() {
        if (adapter.count == 0) return
        val list = binding.listMessages
        list.setSelection(adapter.count - 1)
        list.post {
            if (adapter.count > 0) list.setSelection(adapter.count - 1)
        }
    }

    private companion object {
        /**
         * Five minutes of speech. Long enough for a real message, and still under the
         * 25 MB the reverse proxy in front of the transcription box accepts.
         */
        const val MAX_RECORDING_MS = 5 * 60 * 1000L

        /**
         * The chat screen that is on the device now. A request outlives the screen that made
         * it, so its reply is delivered here rather than to the instance that asked - see
         * [fileReply]. Weak, so a screen that is gone is never held on to by it.
         */
        var live: WeakReference<MainActivity>? = null

        /**
         * The chats with a reply on its way, whatever asked for it. It outlives any one
         * screen, as the requests do: a chat reopened from disk, or opened on a screen that
         * replaced the one that asked, still knows to wait.
         */
        val awaitingReply = mutableSetOf<String>()
    }
}
