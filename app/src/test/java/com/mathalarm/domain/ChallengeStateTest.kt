package com.mathalarm.domain

import com.mathalarm.data.Difficulty
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The gate's behaviour, off-device.
 *
 * These are the tests that stand behind BUILD_SPECS §3's two named acceptance criteria — *a wrong
 * answer generates a different problem* and *the escape hatch is unreachable before five
 * consecutive failures* — plus the properties that make the UI's rotation and process-death story
 * true rather than hopeful.
 */
class ChallengeStateTest {

    private val seed = 0x5EEDL

    /** `ChallengeState`'s own field separator. Duplicated here so a test can take a session apart. */
    private val SEPARATOR = "\u001F"

    /** Indices into [ChallengeState.encode]'s field list, for the tests that corrupt one field. */
    private val SOLVED_FIELD = 5
    private val ANSWER_FIELD = 7
    private val ENTRY_FIELD = 10
    private val ASKED_FIELD = 11

    private fun session(
        difficulty: Difficulty = Difficulty.MEDIUM,
        questionCount: Int = 1,
        seed: Long = this.seed,
    ) = ChallengeState.start(difficulty, questionCount, seed)

    /** Type a number one digit at a time, exactly as the keypad does. */
    private fun ChallengeState.type(value: Int): ChallengeState =
        value.toString().fold(this) { state, char -> state.digit(char - '0') }

    private fun ChallengeState.answerRight(): ChallengeState = type(questionAnswer).submit()

    private fun ChallengeState.answerWrong(): ChallengeState = type(questionAnswer + 1).submit()

    // ---------------------------------------------------------------- opening

    @Test
    fun `a session starts with one unanswered problem and no failures`() {
        val state = session(Difficulty.HARD, questionCount = 3)
        assertEquals(1, state.draws)
        assertEquals(0, state.solved)
        assertEquals(0, state.consecutiveWrong)
        assertEquals(listOf(state.questionText), state.asked)
        assertEquals("", state.entry)
        assertFalse(state.showError)
        assertFalse(state.isSolved)
        assertFalse(state.escapeUnlocked)
        assertFalse(state.escapeRunning)
        assertTrue(state.questionText.isNotBlank())
    }

    /** A zero question count would be an alarm that dismisses itself. */
    @Test
    fun `question count is floored at one`() {
        listOf(0, -4).forEach { count ->
            val state = session(questionCount = count)
            assertEquals(1, state.questionCount)
            assertFalse(state.isSolved)
        }
    }

    // ----------------------------------------------------------- the grading

    /** Acceptance criterion: a wrong answer gives a **brand-new** problem, every single time. */
    @Test
    fun `a wrong answer always produces a brand new problem`() {
        Difficulty.entries.forEach { difficulty ->
            var state = session(difficulty, questionCount = 1, seed = difficulty.ordinal * 101L)
            repeat(30) { attempt ->
                val before = state.questionText
                state = state.answerWrong()
                assertNotEquals(
                    "$difficulty re-offered the same problem on attempt $attempt",
                    before,
                    state.questionText,
                )
            }
        }
    }

    @Test
    fun `a wrong answer counts a failure, clears the entry and leaves the alarm unsolved`() {
        val state = session(questionCount = 2).answerWrong()
        assertEquals(1, state.consecutiveWrong)
        assertEquals(0, state.solved)
        assertEquals("", state.entry)
        assertTrue(state.showError)
        assertFalse(state.isSolved)
    }

    @Test
    fun `a correct answer advances and resets the consecutive failure count`() {
        val state = session(questionCount = 2)
            .answerWrong()
            .answerWrong()
            .answerRight()
        assertEquals(1, state.solved)
        assertEquals(0, state.consecutiveWrong)
        assertFalse(state.showError)
        assertFalse(state.isSolved)
    }

    @Test
    fun `one correct answer solves a single question alarm`() {
        val state = session(questionCount = 1).answerRight()
        assertTrue(state.isSolved)
        assertEquals(1, state.solved)
    }

    @Test
    fun `a three question alarm needs three correct answers`() {
        var state = session(Difficulty.EASY, questionCount = 3)
        assertEquals(1, state.questionNumber)
        state = state.answerRight()
        assertFalse(state.isSolved)
        assertEquals(2, state.questionNumber)
        state = state.answerRight()
        assertFalse(state.isSolved)
        assertEquals(3, state.questionNumber)
        state = state.answerRight()
        assertTrue(state.isSolved)
        assertEquals(3, state.solved)
    }

    /** Graded on submit, not live: typing never grades anything and never penalises anything. */
    @Test
    fun `nothing is graded until submit`() {
        val start = session()
        val typed = start.type(start.questionAnswer)
        assertEquals(0, typed.solved)
        assertEquals(0, typed.consecutiveWrong)
        assertFalse(typed.isSolved)
        assertEquals(start.questionText, typed.questionText)
        assertEquals(start.draws, typed.draws)
    }

    /** A typo is correctable, which is the whole reason grading waits for submit. */
    @Test
    fun `a typo can be backspaced away without penalty`() {
        val start = session()
        val fixed = start
            .type(start.questionAnswer)
            .digit(9)
            .backspace()
            .submit()
        assertTrue(fixed.isSolved)
        assertEquals(0, fixed.consecutiveWrong)
    }

    @Test
    fun `submitting an empty entry does nothing`() {
        val start = session()
        assertFalse(start.canSubmit)
        assertEquals(start, start.submit())
    }

    @Test
    fun `the entry is capped and leading zeros still grade correctly`() {
        val start = session()
        val overTyped = (1..10).fold(start) { state, _ -> state.digit(7) }
        assertEquals(ChallengeState.MAX_ENTRY_DIGITS, overTyped.entry.length)

        val withLeadingZero = start.digit(0).type(start.questionAnswer).submit()
        assertTrue(withLeadingZero.isSolved)
    }

    // ---------------------------------------------------------- no repeats

    /** "No repeated problem within one alarm session", at every tier. */
    @Test
    fun `a long session never repeats a problem`() {
        Difficulty.entries.forEach { difficulty ->
            var state = session(difficulty, questionCount = 1, seed = 31L * (difficulty.ordinal + 1))
            repeat(40) { state = state.answerWrong() }
            assertEquals(41, state.asked.size)
            assertEquals(
                "$difficulty repeated a problem within one session",
                state.asked.size,
                state.asked.toSet().size,
            )
        }
    }

    // -------------------------------------------------------- escape hatch

    /** Acceptance criterion: unreachable before five consecutive wrong answers. */
    @Test
    fun `the escape hatch stays locked for the first four failures`() {
        var state = session()
        repeat(4) { attempt ->
            state = state.answerWrong()
            assertEquals(attempt + 1, state.consecutiveWrong)
            assertFalse(
                "the hatch unlocked after ${attempt + 1} failures",
                state.escapeUnlocked,
            )
        }
    }

    @Test
    fun `the escape hatch unlocks on exactly the fifth consecutive failure`() {
        var state = session()
        repeat(5) { state = state.answerWrong() }
        assertEquals(5, state.consecutiveWrong)
        assertEquals(5, ChallengeState.ESCAPE_AFTER_CONSECUTIVE_WRONG)
        assertTrue(state.escapeUnlocked)
    }

    /**
     * Second lock. Even if something outside this type offered the button early — a stale callback,
     * a hand-built state, a future caller that forgets to check — the countdown refuses to start.
     */
    @Test
    fun `starting the countdown is a no-op before the hatch is unlocked`() {
        var state = session()
        repeat(4) { state = state.answerWrong() }
        val attempted = state.startEscape(nowElapsedMs = 1_000L)
        assertFalse(attempted.escapeRunning)
        assertEquals(ChallengeState.NO_DEADLINE, attempted.escapeDeadlineElapsedMs)
        assertFalse(attempted.isEscapeComplete(nowElapsedMs = 10_000_000L))
    }

    @Test
    fun `the countdown runs a full sixty seconds and not a millisecond less`() {
        val unlocked = unlockedSession()
        val started = unlocked.startEscape(nowElapsedMs = 1_000L)
        assertTrue(started.escapeRunning)
        assertEquals(60, started.escapeSecondsLeft(1_000L))
        assertEquals(60_000L, ChallengeState.ESCAPE_COUNTDOWN_MS)

        assertFalse(started.isEscapeComplete(nowElapsedMs = 60_999L))
        assertEquals(1, started.escapeSecondsLeft(60_999L))
        assertTrue(started.isEscapeComplete(nowElapsedMs = 61_000L))
        assertEquals(0, started.escapeSecondsLeft(61_000L))
    }

    /** Leaving or backgrounding resets the countdown: the next attempt is a fresh full minute. */
    @Test
    fun `cancelling the countdown costs the whole minute`() {
        val started = unlockedSession().startEscape(nowElapsedMs = 1_000L)
        val backgrounded = started.cancelEscape()
        assertFalse(backgrounded.escapeRunning)
        assertFalse(backgrounded.isEscapeComplete(nowElapsedMs = 61_000L))

        // Still unlocked, so it can be restarted — from sixty, not from where it left off.
        assertTrue(backgrounded.escapeUnlocked)
        val restarted = backgrounded.startEscape(nowElapsedMs = 50_000L)
        assertEquals(60, restarted.escapeSecondsLeft(50_000L))
        assertFalse(restarted.isEscapeComplete(nowElapsedMs = 61_000L))
        assertTrue(restarted.isEscapeComplete(nowElapsedMs = 110_000L))
    }

    /** A correct answer re-locks the hatch and takes any running countdown with it. */
    @Test
    fun `a correct answer relocks the hatch and cancels the countdown`() {
        val running = unlockedSession().startEscape(nowElapsedMs = 1_000L)
        assertTrue(running.escapeRunning)

        // The keypad is inert while the countdown runs, so cancel first, as the UI's own
        // "back to the problem" button does.
        val solved = running.cancelEscape().answerRight()
        assertEquals(0, solved.consecutiveWrong)
        assertFalse(solved.escapeUnlocked)
        assertFalse(solved.escapeRunning)
    }

    /** Third lock: a deadline cannot outlive the entitlement that created it. */
    @Test
    fun `a countdown cannot complete once the hatch has relocked`() {
        val running = unlockedSession().startEscape(nowElapsedMs = 1_000L)
        val relocked = running.copy(consecutiveWrong = 0)
        assertTrue(relocked.escapeRunning)
        assertFalse(relocked.escapeUnlocked)
        assertFalse(relocked.isEscapeComplete(nowElapsedMs = 61_000L))
    }

    @Test
    fun `starting an already running countdown does not extend or restart it`() {
        val running = unlockedSession().startEscape(nowElapsedMs = 1_000L)
        assertEquals(running, running.startEscape(nowElapsedMs = 30_000L))
    }

    /** The keypad is dead while the countdown runs; there is no way to game both at once. */
    @Test
    fun `the keypad is inert while the countdown runs`() {
        val running = unlockedSession().startEscape(nowElapsedMs = 1_000L)
        assertFalse(running.canSubmit)
        assertEquals(running, running.digit(4))
        assertEquals(running, running.backspace())
        assertEquals(running, running.submit())
    }

    @Test
    fun `escapeSecondsLeft is zero when no countdown is running`() {
        assertEquals(0, session().escapeSecondsLeft(nowElapsedMs = 5_000L))
    }

    // ------------------------------------------- rotation and process death

    /**
     * What the activity's `Bundle` round trip relies on: `(seed, draws)` is the whole random stream,
     * so a restored session is bit-identical rather than merely similar. Rotating the phone cannot
     * hand the user a different — possibly easier — problem, and cannot forgive a failure.
     */
    @Test
    fun `a session replays identically from its seed and draw count`() {
        var original = session(Difficulty.HARD, questionCount = 3, seed = 777L)
        repeat(3) { original = original.answerWrong() }
        original = original.answerRight()

        var replayed = session(Difficulty.HARD, questionCount = 3, seed = 777L)
        repeat(3) { replayed = replayed.answerWrong() }
        replayed = replayed.answerRight()

        assertEquals(original, replayed)
    }

    /**
     * The restore path the activity actually performs — and it now *performs* it rather than
     * mirroring it. This test used to hand-build the restored value field by field, which meant it
     * passed no matter what the activity actually saved: dropping a field from the save path failed
     * nothing (review M2-1's coverage gap). It drives `encode`/`decode` instead, the same pair
     * `onSaveInstanceState` and `readChallenge` call, so a field dropped from either half fails
     * here.
     */
    @Test
    fun `a restored session keeps the problem, the progress and the failure count`() {
        var before = session(Difficulty.MEDIUM, questionCount = 3, seed = 4242L)
        before = before.answerRight()
        repeat(3) { before = before.answerWrong() }
        before = before.digit(1).digit(2)

        val restored = ChallengeState.decode(ChallengeState.encode(before))
        assertEquals(before, restored)
        requireNotNull(restored)
        assertEquals(1, restored.solved)
        assertEquals(3, restored.consecutiveWrong)
        assertFalse(restored.escapeUnlocked)
        assertFalse(restored.isSolved)

        // And it carries on from exactly where it was: the entry is still there, still ungraded.
        assertEquals("12", restored.entry)
        val cleared = restored.backspace().backspace()
        assertEquals("", cleared.entry)
        assertTrue(cleared.answerRight().solved > restored.solved)
    }

    /** Five failures survive a restore, so the hatch is where the user left it — not reset. */
    @Test
    fun `a restore neither forgives failures nor reopens a countdown`() {
        val unlocked = unlockedSession().startEscape(nowElapsedMs = 1_000L)
        val restored = unlocked.copy(escapeDeadlineElapsedMs = ChallengeState.NO_DEADLINE)
        assertTrue(restored.escapeUnlocked)
        assertFalse(restored.escapeRunning)
        assertFalse(restored.isEscapeComplete(nowElapsedMs = 61_000L))
    }

    // ------------------------------------------------- the Bundle round trip

    /**
     * Every field the activity persists, round-tripped through the real pair at every tier and in
     * every interesting shape: mid-entry, in error, with the hatch unlocked, and with a
     * multi-question session part-solved. Drop a field from `encode` and this fails.
     */
    @Test
    fun `a session survives the Bundle round trip at every tier`() {
        Difficulty.entries.forEach { difficulty ->
            var state = session(difficulty, questionCount = 3, seed = difficulty.ordinal * 31L)
            state = state.answerRight()
            repeat(5) { state = state.answerWrong() }
            state = state.digit(7).digit(0).digit(9)

            val restored = ChallengeState.decode(ChallengeState.encode(state))
            assertEquals("$difficulty did not survive the round trip", state, restored)
            requireNotNull(restored)
            // Spelled out, so the assertion above cannot pass by both sides being equally wrong.
            assertEquals(difficulty, restored.difficulty)
            assertEquals(3, restored.questionCount)
            assertEquals(1, restored.solved)
            assertEquals(5, restored.consecutiveWrong)
            assertTrue(restored.escapeUnlocked)
            assertEquals(state.asked, restored.asked)
            assertEquals(state.questionText, restored.questionText)
            assertEquals(state.questionAnswer, restored.questionAnswer)
            assertEquals("709", restored.entry)
            assertEquals(state.draws, restored.draws)
            assertEquals(state.seed, restored.seed)
            // Typing clears the red state, so the error flag is pinned on its own below.
            assertFalse(restored.showError)
        }
    }

    /** The red error state is a persisted field too: a rotation mid-error comes back in error. */
    @Test
    fun `the error state survives the round trip in both positions`() {
        val wrong = session(seed = 8_123L).answerWrong()
        assertTrue(wrong.showError)
        assertTrue(requireNotNull(ChallengeState.decode(ChallengeState.encode(wrong))).showError)

        val typing = wrong.digit(3)
        assertFalse(typing.showError)
        assertFalse(requireNotNull(ChallengeState.decode(ChallengeState.encode(typing))).showError)
    }

    /** The one field that is deliberately dropped, and it comes back cleared rather than running. */
    @Test
    fun `the escape deadline never survives the round trip`() {
        val running = unlockedSession().startEscape(nowElapsedMs = 1_000L)
        assertTrue(running.escapeRunning)

        val restored = requireNotNull(ChallengeState.decode(ChallengeState.encode(running)))
        assertTrue(restored.escapeUnlocked)
        assertFalse(restored.escapeRunning)
        assertEquals(ChallengeState.NO_DEADLINE, restored.escapeDeadlineElapsedMs)
        assertFalse(restored.isEscapeComplete(nowElapsedMs = 61_000L))
    }

    /**
     * Nothing is defaulted. The old reader read the answer as `getInt(key)`, whose absent-key
     * default is `0` — a typable answer — so a `Bundle` that kept the question and lost the answer
     * would have dismissed the alarm for one keypress (review M2-1.2). Here every field is required
     * and a missing one is refused, which makes the activity start a fresh session off the alarm's
     * real tier instead.
     */
    @Test
    fun `a session with any field missing is refused rather than defaulted`() {
        val state = session(Difficulty.HARD, questionCount = 2, seed = 99L).digit(4)
        val fields = ChallengeState.encode(state).split(SEPARATOR)
        assertEquals(12, fields.size)

        // The two fields that may legitimately be empty: the entry (nothing typed yet) and the
        // no-repeat memory (which falls back to the question on screen). Neither can carry a
        // dismissal, which is asserted below. Every other field is mandatory.
        val mayBeEmpty = setOf(ENTRY_FIELD, ASKED_FIELD)

        fields.indices.forEach { index ->
            // Gone entirely, as a truncated field list would be: always refused, every index.
            val dropped = fields.toMutableList().also { it.removeAt(index) }
            assertEquals(
                "field $index removed was accepted",
                null,
                ChallengeState.decode(dropped.joinToString(SEPARATOR)),
            )

            // Blanked, which is how an absent `Bundle` key used to read.
            val blanked = fields.toMutableList().also { it[index] = "" }
            val decoded = ChallengeState.decode(blanked.joinToString(SEPARATOR))
            if (index in mayBeEmpty) {
                requireNotNull(decoded) { "field $index should still be a session" }
                assertFalse("field $index bought a dismissal", decoded.isSolved)
                assertTrue("field $index lost the problem", decoded.questionAnswer > 0)
            } else {
                assertEquals("field $index blanked was accepted", null, decoded)
            }
        }
    }

    /** `0` is a typable answer, so a session claiming one is not a session. */
    @Test
    fun `a session whose answer is zero or negative is refused`() {
        val state = session(seed = 5L)
        listOf("0", "-1").forEach { answer ->
            val fields = ChallengeState.encode(state).split(SEPARATOR).toMutableList()
            fields[ANSWER_FIELD] = answer
            assertEquals(null, ChallengeState.decode(fields.joinToString(SEPARATOR)))
        }
    }

    /**
     * A restored session can never arrive holding the dismissal. Belt to M2-1.3's braces: the
     * activity only dismisses on the submit that *transitions* the session to solved, and a state
     * that is already solved cannot be restored in the first place.
     */
    @Test
    fun `a session that is already solved is refused`() {
        val state = session(questionCount = 2, seed = 11L)
        val fields = ChallengeState.encode(state).split(SEPARATOR).toMutableList()
        fields[SOLVED_FIELD] = "2"
        assertEquals(null, ChallengeState.decode(fields.joinToString(SEPARATOR)))
    }

    /** A crafted entry cannot hold anything the keypad could not have typed. */
    @Test
    fun `a session with an untypable entry is refused`() {
        val state = session(seed = 7L)
        listOf("12a", " 1", "-1", "1234567", "1 2").forEach { entry ->
            val fields = ChallengeState.encode(state).split(SEPARATOR).toMutableList()
            fields[ENTRY_FIELD] = entry
            assertEquals("entry '$entry'", null, ChallengeState.decode(fields.joinToString(SEPARATOR)))
        }
    }

    @Test
    fun `garbage and foreign formats are refused`() {
        listOf(null, "", "   ", "not a session", "2MEDIUM").forEach {
            assertEquals("'$it'", null, ChallengeState.decode(it))
        }
        // The right shape with the wrong version number is still refused.
        val fields = ChallengeState.encode(session(seed = 3L)).split(SEPARATOR).toMutableList()
        fields[0] = "2"
        assertEquals(null, ChallengeState.decode(fields.joinToString(SEPARATOR)))
    }

    /** Problem texts carry spaces and `+ - × ÷`; none of them may collide with the separators. */
    @Test
    fun `problem texts and the no repeat memory round trip verbatim`() {
        Difficulty.entries.forEach { difficulty ->
            var state = session(difficulty, questionCount = 1, seed = 1234L + difficulty.ordinal)
            repeat(12) { state = state.answerWrong() }
            val restored = requireNotNull(ChallengeState.decode(ChallengeState.encode(state)))
            assertEquals(13, restored.asked.size)
            assertEquals(state.asked, restored.asked)
            assertTrue(restored.asked.none { it.isEmpty() })
        }
    }

    private fun unlockedSession(): ChallengeState {
        var state = session(questionCount = 1)
        repeat(ChallengeState.ESCAPE_AFTER_CONSECUTIVE_WRONG) { state = state.answerWrong() }
        assertTrue(state.escapeUnlocked)
        return state
    }
}
