package com.example.unutma.data

import android.content.Context
import com.example.unutma.Label
import com.example.unutma.NOTE_TYPE_LIST
import com.example.unutma.NOTE_TYPE_TEXT
import com.example.unutma.Note
import com.example.unutma.RESET_DAILY
import kotlinx.coroutines.sync.withLock

/**
 * Built-in notes that explain the app. They are added once, the first time Unutma opens
 * (and again from Settings › Add the guide notes). Only the three in [MAIN] become notes; all of
 * them, those three included, are on Settings › Help. Each note shows the feature it
 * explains: the checklist note is a real checklist, the countdown note has a countdown, and so on.
 * They are ordinary notes, so they can be edited or deleted like any other.
 */
object Guide {
    private const val FLAG = "guide_seeded"

    /** The guide notes a new user finds on the notes screen. The rest are only on the Help page. */
    private val MAIN = listOf("Welcome to Unutma", "Checklists", "Reminders and dates")
    private const val DAY = 24L * 60 * 60 * 1000

    /** One at a time: two quick starts of the app used to add every guide note twice. */
    private val lock = kotlinx.coroutines.sync.Mutex()

    suspend fun seedIfNeeded(ctx: Context) = lock.withLock {
        val p = Prefs.get(ctx)
        if (p.flag(FLAG)) return@withLock
        p.setFlag(FLAG, true)
        addLocked(ctx)
    }

    private fun t(s: String) = TextBlock(text = s.trimIndent())
    private fun c(s: String, done: Boolean = false, indent: Int = 0, qty: Int? = null) =
        CheckBlock(text = s, checked = done, indent = indent, qty = qty)

    /**
     * Adds the guide notes under a "Guide" label (re-using it if it already exists).
     * Guide notes that are already there (not deleted) are skipped, so they never appear twice.
     */
    suspend fun add(ctx: Context) = lock.withLock { addLocked(ctx) }

    private suspend fun addLocked(ctx: Context) {
        val labels = Repo.labels(ctx)
        val guide = labels.all().firstOrNull { it.name.equals("Guide", ignoreCase = true) }
            ?: run {
                val pos = (labels.all().maxOfOrNull { it.position } ?: -1) + 1
                val id = labels.insert(Label(name = "Guide", color = "PURPLE", position = pos)).toInt()
                Label(id = id, name = "Guide", color = "PURPLE", position = pos)
            }
        val notes = build(guide.id, System.currentTimeMillis()).filter { it.title in MAIN }
        val existing = Repo.notes(ctx).activeNow().filter { it.labelId == guide.id }.map { it.title }.toSet()
        for (n in notes.asReversed()) if (n.title !in existing) Repo.save(ctx, n)
    }

    /** The guide notes themselves (also shown on the Help page). */
    fun build(labelId: Int?, now: Long): List<Note> {
        fun note(title: String, blocks: List<Block>, type: String = NOTE_TYPE_TEXT) =
            Note(title = title, type = type, labelId = labelId, description = serializeBlocks(blocks))


        // Saved last-to-first, so "Welcome" ends up at the top of the list.
        return listOf(
            note("Welcome to Unutma", listOf(
                t("""
                    Unutma means **don't forget**. These guide notes show what the app can do — read them, try things in them, then delete them whenever you like.

                    • Tap **+** to write a note. Long-press **+** for a checklist, or to talk and have it written for you.
                    • Notes save themselves as you type. There's no Save button.
                    • Made a mistake? Use undo and redo at the top of a note.
                    • Tap a note to open it. Long-press a note to select several.
                    • The bar at the bottom switches between Notes, Routines (checklists that come back, with streaks) and Calendar (your Google Calendar). In Calendar, swipe the days left or right for other weeks, or tap the month to jump to a date.

                    Start with [[Checklists]] and [[Reminders and dates]]. Everything else — labels, routines, writing tricks, pictures, locking notes, backups, the calendar and widgets — is in ⋯ › Settings › Help.

                    Finished with these? Delete them; Settings › Add the guide notes brings them back.
                """),
            )).copy(isPinned = true),

            note("Checklists", listOf(
                t("Any note can mix text and checkboxes. Press Enter at the end of an item to start the next one; press Enter on an empty item to go back to normal text."),
                c("Tap a box to tick it", done = true),
                c("Drag the dots on the left to reorder"),
                c("Long-press a box for more options"),
                c("Like making this a sub-item", indent = 1),
                c("Or adding a quantity", indent = 1),
                c("Eggs", qty = 12),
                c("Tomatoes, 500 g"),
                t("Text › checkbox (at the bottom of a note) turns the line you're on into a checkbox, and back. Drag an item by its dots to move it anywhere in the note, even into another list. Next: [[Reminders and dates]]."),
            ), NOTE_TYPE_LIST),

            note("Morning routine", listOf(
                t("This is a routine: it unticks itself every day and keeps a streak in the Routines tab. There, swipe its week back to earlier weeks, tap a day to see what you ticked, or tap the calendar button for the whole month. Set one up in Details › Repeat (every day, every week, on chosen days or every few days), or use Details › Untick all."),
                c("Drink a glass of water"),
                c("Take the keys"),
                c("Check the train times"),
            ), NOTE_TYPE_LIST).copy(resetMode = RESET_DAILY, lastResetAt = now),

            note("Labels", listOf(
                t("""
                    Labels group your notes. Each label becomes a coloured section on the notes screen, and a tab at the top so you can show just that label. Hold a tab and drag it to change the order ("All" stays first).

                    • Give a note a label with the chip above its title.
                    • Create, rename and recolour labels in ⋯ › Manage labels, and drag them into the order you like.
                    • Hold a note until it buzzes, then drag it to put the notes in your own order (pinned notes stay above the others).
                    • Tap a section's title on the notes screen to fold it away; tap again to open it.
                    • Select several notes (long-press) to label, pin, lock, archive or delete them together. The select-all button picks every note on screen.

                    This note is in the **Guide** label. Next: [[Reminders and dates]].
                """),
            )),

            note("Reminders and dates", listOf(
                t("""
                    Tap Remind at the bottom of a note to set a reminder: in 1 hour, in 3 hours, tomorrow at this time, or any date and time (flick the hour and minute wheels). The notification has **Done** and **Snooze** buttons.

                    *Repeat until done* keeps reminding you every few minutes until you tap Done — handy for things you really can't forget.

                    Unutma also spots dates you type. Try writing: Call the dentist on Monday 09:30 — a "Remind me?" suggestion appears above the bottom bar.

                    In Details you'll also find:
                    • **Countdown** — shows "in 3d" on the list (Help › Trip countdown).
                    • **Expires** — the note moves to Recently deleted on that day (Help › This note expires).

                    Dates that are coming up soon are shown in the label's colour on the notes screen.
                """),
            )),

            note("Trip countdown", listOf(
                t("This note counts down to a date and time. Tap the date under the title (or Details › Countdown) to change it. Under a day away it counts the hours. You can also put a countdown on your home screen with the Countdown widget."),
            )).copy(countdownAt = Fmt0.at(now + 3 * DAY, 9)),

            note("This note expires", listOf(
                t("In a week this note moves to Recently deleted by itself. Great for Wi-Fi codes, parking spots or anything you only need for a while. Change it in Details › Expires."),
            )).copy(expiresAt = Fmt0.at(now + 7 * DAY, 23, 59)),

            note("Writing tricks", listOf(
                t("""
                    # Text sizes
                    Tap Text › **H** to make the line you're on a heading: H1, H2, H3, then back to normal. Hold H to pick one.
                    ## Like this N2
                    ### And this N3

                    Numbered lists: tap the list button, or type 1. and a space. Press Enter and the next number appears; press Enter on an empty number to stop.
                    1. Like this
                    2. And this

                    **Bold** and *italic*: select text and tap B or I, or tap them before you type. Both at once works too: ***like this***.

                    Maths: end a line with = and the answer appears.
                    Groceries 12,50 + 7,20 + 3 =
                    Split between 3: 22,7 / 3 =

                    Links: type the title of another note between double square brackets, or use Insert › link. Put the cursor on a link to open it: [[Welcome to Unutma]]
                """),
            )),

            note("Pictures and voice", listOf(
                t("""
                    Insert › picture adds a photo where your cursor is — text goes above and below it, never beside it. Drag its round corner to make it smaller.

                    Hold a picture and drag it: a line shows where it will go, even into the middle of a paragraph. Drag it left or right (or tap it › Left, Centre, Right) to move a smaller picture along its line.

                    Tap a picture and choose **Extract text** to copy the words from it into the note: receipts, tickets, whiteboards.

                    Insert › microphone records a voice note you can play back inside the note.

                    **Talk to create**: long-press + and choose Talk, or use the mic on the quick capture widget. Say "shopping list eggs, milk and bread" for a checklist, or "remind me to call the bank tomorrow at 10" for a note with a reminder.
                """),
            )),

            note("Keeping things safe", listOf(
                t("""
                    **Pin to the notification bar**: Details › Pin to notification bar keeps a note in your notifications; if you swipe it away it comes straight back. You can tick a checklist's items right there. Settings › Show pinned notes opened keeps them with your main notifications, where Android opens the top one up.

                    **Lock**: Details › Lock (or hold notes › ⋯ › Lock) hides a note behind your fingerprint or screen lock — in the list, widgets and notifications too — and encrypts what's in it. If you ever remove your phone's screen lock, Android deletes the key and locked notes can't be opened, so unlock them first.

                    **Archive and Recently deleted**: ⋯ › Archive keeps notes you put away until you restore them. Deleted notes wait in ⋯ › Recently deleted for a week before they're removed (change how long in Settings), so you can restore them. Both keep their label.

                    **Share**: ⋯ › Share sends a note as text, a PDF or an image.

                    **Back up**: Settings › Back up puts everything — notes, checklists, labels, the archive, pictures, voice notes, routines and settings — into one .zip. Save it in Downloads or Google Drive, or share it. Locked notes are unlocked for the backup, so it asks for your fingerprint first.

                    **Restore**: Settings › Restore from a backup adds the notes from a backup .zip, on this phone or a new one (or from Unutma (debug) to Unutma). Notes you already have aren't doubled, and reminders and pins are set up again.

                    **From Google Keep**: export Keep at takeout.google.com (just Keep), then Settings › Import from Google Keep and pick the .zip. Notes, checklists, labels, pictures and recordings come across; notes in Keep's trash don't, and importing twice doesn't double them.
                """),
            )),

            note("Widgets and settings", listOf(
                t("""
                    Long-press your home screen, choose Widgets and find Unutma:
                    • **Quick capture** — new note, new checklist or talk.
                    • **Countdown** — days left until a note's date.
                    • **Small list** and **Checklist** — tick items right from the home screen.
                    • **Daily checklist**, **Progress ring** and **Next up** — small checklists you can tick from the home screen.
                    • **Streak** — how many days in a row you've ticked everything.
                    • **Note** — keep a note in view.
                    • **Agenda**, **Next event** and **This week** — your calendar on the home screen; they update when your events change.
                    • **Notes** — several notes at once, with a + for a new one.

                    Widgets can be resized: long-press one and drag its edges. Long-press › Widget settings changes its text size (and which note, or which label, it shows).

                    In ⋯ › Settings you can change the theme, accent colour, text size, default widget text size, sort order and how long deleted notes are kept, set notification sound and vibration, back up, restore, import from Google Keep, read Help and send feedback.

                    **Calendar tab**: swipe the week to go back or forward (Today brings you back), tap a day to see its events, tap an event to change it, swipe it left to delete it. Every event reminds you when it starts; you can add one more reminder before it.

                    Finished with the guide? Select these notes and delete them. You can bring them back in Settings › Add the guide notes.
                """),
            )),
        )
    }

    /** The guide as (title, text) pairs for the Help page. */
    fun topics(): List<Pair<String, String>> =
        build(null, System.currentTimeMillis()).map { it.title to parseBlocks(it).plainText() }

    /** Local date at a fixed time of day. */
    private object Fmt0 {
        fun at(dayMs: Long, hour: Int, minute: Int = 0): Long = java.util.Calendar.getInstance().apply {
            timeInMillis = dayMs
            set(java.util.Calendar.HOUR_OF_DAY, hour); set(java.util.Calendar.MINUTE, minute)
            set(java.util.Calendar.SECOND, 0); set(java.util.Calendar.MILLISECOND, 0)
        }.timeInMillis
    }
}
