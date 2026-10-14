package com.mathalarm.domain

import com.mathalarm.data.Difficulty
import kotlin.random.Random

/**
 * The whole math gate as one immutable value: which problem is on screen, what has been typed, how
 * many questions are done, how many have been got wrong in a row, and whether the escape hatch is
 * open.
 *
 * **Pure Kotlin — no Android imports.** That is not decoration; it is what makes the two rules that
 * actually matter testable off-device: *a wrong answer always produces a brand-new problem*, and
 * *the escape hatch is unreachable before five consecutive wrong answers*.
 *
 * ### Why this is a value and not a pile of `remember`s
 * `AlarmRingActivity` holds exactly one of these in an activity-scoped `mutableStateOf` and saves
 * its fields into the activity's `Bundle`. Composition only *reads* it. No composable calls
 * [ProblemGenerator] and no composable owns [consecutiveWrong], so no recomposition — and no
 * rotation, and no process death — can hand the user a fresh problem or forgive a failure.
 *
 * ### Determinism
 * There is no stored `Random`; a `Random` is not serializable and a session has to survive process
 * death. Instead the session keeps a [seed] and a monotonically increasing [draws] counter, and
 * every problem is drawn from `Random(seed + draws * GOLDEN_GAMMA)`. Restoring `(seed, draws)`
 * restores the exact stream, and a test can replay a whole session from one `Long`.
 */
data class ChallengeState(
    val difficulty: Difficulty,
    val questionCount: Int,
    val seed: Long,
    /** How many problems this session has drawn. Also the per-problem sub-seed. */
    val draws: Int,
    val solved: Int,
    val consecutiveWrong: Int,
    /** Every problem text asked this session — the no-repeat memory (BRAINSTORM §4.2). */
    val asked: List<String>,
    val questionText: String,
    val questionAnswer: Int,
    /** What the keypad has typed so far. Graded only by [submit], never live, so typos are fixable. */
    val entry: String,
    /** Set by a wrong [submit] and cleared by the next keypress: drives the red state. */
    val showError: Boolean,
    /**
     * `elapsedRealtime` instant at which the escape countdown completes, or [NO_DEADLINE].
     *
     * Deliberately **never persisted** — see `AlarmRingActivity`. A countdown that outlived the
     * screen would be a countdown the user did not have to watch.
     */
    val escapeDeadlineElapsedMs: Long,
) {

    // --------------------------------------------------------------- read-only

    /** `true` once five answers in a row have been wrong. The single gate on the escape hatch. */
    val escapeUnlocked: Boolean
        get() = consecutiveWrong >= ESCAPE_AFTER_CONSECUTIVE_WRONG

    val escapeRunning: Boolean
        get() = escapeDeadlineElapsedMs != NO_DEADLINE

    /** Every required question answered: the alarm may be dismissed. */
    val isSolved: Boolean
        get() = solved >= questionCount

    /** Submit stays disabled on an empty entry; there is no "submit nothing" failure. */
    val canSubmit: Boolean
        get() = entry.isNotEmpty() && !escapeRunning

    /** The `2` of `2 / 3`. Only shown when [questionCount] > 1. */
    val questionNumber: Int
        get() = (solved + 1).coerceAtMost(questionCount)

    // -------------------------------------------------------------- keypad

    /** Append a digit. A no-op past [MAX_ENTRY_DIGITS] or while the countdown is running. */
    fun digit(value: Int): ChallengeState = when {
        escapeRunning || value !in 0..9 -> this
        entry.length >= MAX_ENTRY_DIGITS -> copy(showError = false)
        else -> copy(entry = entry + value, showError = false)
    }

    fun backspace(): ChallengeState =
        if (escapeRunning || entry.isEmpty()) this else copy(entry = entry.dropLast(1), showError = false)

    /**
     * Grade the entry. **This is the only place an answer is ever checked** — nothing grades as the
     * user types, so a mistyped digit can be backspaced away without penalty.
     *
     * Right: count it, reset [consecutiveWrong] to zero (which re-locks the escape hatch and
     * cancels any countdown — the user has just proved they can do the math), and either finish or
     * move to the next problem.
     *
     * Wrong: increment [consecutiveWrong], raise [showError], and **always draw a brand-new
     * problem**. Re-offering the same problem would let a lucky guess be retried.
     *
     * Either way the sound is untouched: this type does not know the alarm is making any.
     */
    fun submit(): ChallengeState {
        if (escapeRunning) return this
        val value = entry.toIntOrNull() ?: return this
        if (value == questionAnswer) {
            val advanced = copy(
                solved = solved + 1,
                consecutiveWrong = 0,
                entry = "",
                showError = false,
                escapeDeadlineElapsedMs = NO_DEADLINE,
            )
            return if (advanced.isSolved) advanced else advanced.withNextProblem()
        }
        return copy(
            consecutiveWrong = consecutiveWrong + 1,
            entry = "",
            showError = true,
        ).withNextProblem()
    }

    private fun withNextProblem(): ChallengeState {
        val nextDraw = draws + 1
        val problem = ProblemGenerator.generate(
            difficulty = difficulty,
            random = Random(drawSeed(seed, nextDraw)),
            exclude = asked.toSet(),
        )
        return copy(
            draws = nextDraw,
            asked = asked + problem.text,
            questionText = problem.text,
            questionAnswer = problem.answer,
        )
    }

    // -------------------------------------------------------- escape hatch

    /**
     * Start the 60-second countdown.
     *
     * **A no-op unless [escapeUnlocked].** The UI does not compose the button before then, and this
     * guard is the second, independent lock: even a stale callback, a restored `Bundle` or a future
     * caller that forgets to check cannot start a countdown the user has not earned.
     */
    fun startEscape(nowElapsedMs: Long): ChallengeState =
        if (!escapeUnlocked || escapeRunning) this
        else copy(
            escapeDeadlineElapsedMs = nowElapsedMs + ESCAPE_COUNTDOWN_MS,
            entry = "",
            showError = false,
        )

    /** Called when the user leaves the screen. Backgrounding resets the countdown (BUILD_SPECS §3). */
    fun cancelEscape(): ChallengeState =
        if (!escapeRunning) this else copy(escapeDeadlineElapsedMs = NO_DEADLINE)

    /**
     * Whole seconds still to wait, for the display. Zero when no countdown is running.
     *
     * **The only copy of the ceiling arithmetic.** `AlarmRingActivity`'s countdown composable used
     * to carry its own `secondsUntil`, so the four tests that read as "the countdown runs a full
     * sixty seconds and not a millisecond less" pinned arithmetic the app never executed while the
     * number the user actually watched was untested (review M2-1.5). The composable now calls this.
     */
    fun escapeSecondsLeft(nowElapsedMs: Long): Int {
        if (!escapeRunning) return 0
        val remaining = escapeDeadlineElapsedMs - nowElapsedMs
        if (remaining <= 0L) return 0
        return ((remaining + 999L) / 1000L).toInt()
    }

    /**
     * The alarm may be dismissed by the escape hatch.
     *
     * Re-checks [escapeUnlocked] as well as the deadline, so a session that fell back below five
     * consecutive failures can never complete a countdown it is no longer entitled to. Third lock.
     */
    fun isEscapeComplete(nowElapsedMs: Long): Boolean =
        escapeUnlocked && escapeRunning && nowElapsedMs >= escapeDeadlineElapsedMs

    companion object {
        /** BUILD_SPECS §0: the escape hatch unlocks at five consecutive wrong answers, not four. */
        const val ESCAPE_AFTER_CONSECUTIVE_WRONG = 5

        /** BUILD_SPECS §0: 60 seconds, watched, at full volume. */
        const val ESCAPE_COUNTDOWN_MS = 60_000L

        const val NO_DEADLINE = 0L

        /** Generous: the largest answer any tier can produce is 891 (`99 × 9`). */
        const val MAX_ENTRY_DIGITS = 6

        /**
         * Bumped whenever the field list below changes, so a `Bundle` written by an older build is
         * declined rather than mis-parsed.
         */
        private const val FORMAT_VERSION = 1

        /** ASCII unit / record separators. No problem text, entry or enum name can contain them. */
        private const val FIELD_SEPARATOR = "\u001F"
        private const val RECORD_SEPARATOR = "\u001E"

        /** The eleven non-deadline fields plus the format version. */
        private const val FIELD_COUNT = 12

        /** SplittableRandom's golden-ratio increment: decorrelates adjacent draw sub-seeds. */
        private const val GOLDEN_GAMMA = -0x61c8864680b583ebL

        internal fun drawSeed(seed: Long, draw: Int): Long = seed + draw * GOLDEN_GAMMA

        /**
         * The whole session as one flat string — the save half of the activity's `Bundle` round
         * trip, and [decode] is the restore half.
         *
         * This lives here, pure, rather than as eleven `outState.putX` calls in the activity, for
         * one reason: a test can drive it. The previous arrangement had
         * `ChallengeStateTest`'s restore test *hand-build* the restored value, mirroring the
         * activity's reader instead of calling it, so **dropping a field from the save path failed
         * no test at all** (review M2-1's coverage gap). Now `a session survives the Bundle round
         * trip` runs the real pair, off-device, with no Android dependency.
         *
         * [escapeDeadlineElapsedMs] is deliberately absent: an `elapsedRealtime` instant keeps
         * advancing while the process is dead, so persisting it would let a countdown complete that
         * nobody watched (BUILD_SPECS §3).
         */
        fun encode(state: ChallengeState): String = listOf(
            FORMAT_VERSION.toString(),
            state.difficulty.name,
            state.questionCount.toString(),
            state.seed.toString(),
            state.draws.toString(),
            state.solved.toString(),
            state.consecutiveWrong.toString(),
            state.questionAnswer.toString(),
            if (state.showError) "1" else "0",
            state.questionText,
            state.entry,
            state.asked.joinToString(RECORD_SEPARATOR),
        ).joinToString(FIELD_SEPARATOR)

        /**
         * Rebuild a session from [encode]'s output, or `null` if it cannot be trusted completely.
         *
         * **Every field is required and every field is validated; nothing has a default.** The old
         * reader defaulted `questionAnswer` to `0` on an absent key, and `0` is a typable answer —
         * a `Bundle` that kept the question text and lost the answer would have handed out a
         * dismissal for one keypress (review M2-1.2). There is no such hole to plug here, because
         * an absent or unparsable field returns `null`, and `null` means "start a fresh session,
         * reading the alarm's real tier" — strictly harder than the restored one, never easier.
         *
         * The validations beyond parsing are all in the same fail-closed direction:
         *  - a non-positive answer is rejected: no tier can produce one, and `0` is typable;
         *  - a session that arrives already `isSolved` is rejected, so a crafted `Bundle` cannot
         *    walk in holding the dismissal (see also M2-1.3);
         *  - the entry must be digits within [MAX_ENTRY_DIGITS], so a crafted entry cannot be
         *    anything the keypad could not have typed.
         */
        fun decode(encoded: String?): ChallengeState? {
            val fields = encoded?.split(FIELD_SEPARATOR) ?: return null
            if (fields.size != FIELD_COUNT) return null
            if (fields[0] != FORMAT_VERSION.toString()) return null

            val difficulty = Difficulty.entries.firstOrNull { it.name == fields[1] } ?: return null
            val questionCount = (fields[2].toIntOrNull() ?: return null).coerceAtLeast(1)
            val seed = fields[3].toLongOrNull() ?: return null
            val draws = fields[4].toIntOrNull() ?: return null
            val solved = fields[5].toIntOrNull() ?: return null
            val consecutiveWrong = fields[6].toIntOrNull() ?: return null
            val questionAnswer = fields[7].toIntOrNull() ?: return null
            val showError = when (fields[8]) {
                "1" -> true
                "0" -> false
                else -> return null
            }
            val questionText = fields[9]
            val entry = fields[10]
            val asked = fields[11].split(RECORD_SEPARATOR).filter { it.isNotEmpty() }

            if (draws < 1) return null
            if (solved !in 0 until questionCount) return null
            if (consecutiveWrong < 0) return null
            if (questionAnswer <= 0) return null
            if (questionText.isEmpty()) return null
            if (entry.length > MAX_ENTRY_DIGITS || entry.any { it !in '0'..'9' }) return null

            return ChallengeState(
                difficulty = difficulty,
                questionCount = questionCount,
                seed = seed,
                draws = draws,
                solved = solved,
                consecutiveWrong = consecutiveWrong,
                asked = asked.ifEmpty { listOf(questionText) },
                questionText = questionText,
                questionAnswer = questionAnswer,
                entry = entry,
                showError = showError,
                // Always restarted, never restored.
                escapeDeadlineElapsedMs = NO_DEADLINE,
            )
        }

        /**
         * Begin a session. [questionCount] is floored at 1: a zero would otherwise mean an alarm
         * that dismisses itself.
         */
        fun start(difficulty: Difficulty, questionCount: Int, seed: Long): ChallengeState {
            val first = ProblemGenerator.generate(difficulty, Random(drawSeed(seed, 1)))
            return ChallengeState(
                difficulty = difficulty,
                questionCount = questionCount.coerceAtLeast(1),
                seed = seed,
                draws = 1,
                solved = 0,
                consecutiveWrong = 0,
                asked = listOf(first.text),
                questionText = first.text,
                questionAnswer = first.answer,
                entry = "",
                showError = false,
                escapeDeadlineElapsedMs = NO_DEADLINE,
            )
        }
    }
}
