package com.mathalarm.domain

import com.mathalarm.domain.AlarmStartPolicy.NO_ALARM
import com.mathalarm.domain.AlarmStartPolicy.Outcome
import com.mathalarm.domain.AlarmStartPolicy.forStart
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The overlap policy (BUILD_SPECS §4b item 3). These tests pin the two directions that matter:
 * a second alarm can never take the ringer from a live one, and nothing here can refuse an alarm
 * that is merely re-asserting itself.
 */
class AlarmStartPolicyTest {

    @Test
    fun `first alarm on a fresh service rings`() {
        assertEquals(Outcome.RING, forStart(7L, NO_ALARM, false))
    }

    @Test
    fun `a second alarm arriving while another rings is refused`() {
        assertEquals(Outcome.REFUSE, forStart(8L, 7L, true))
    }

    @Test
    fun `the test alarm is refused while a real alarm rings`() {
        // The reserved test row id. M3-1.2: this was one tap away, and five seconds later the
        // service reused the live ringer and overwrote the ringing id.
        assertEquals(Outcome.REFUSE, forStart(-2L, 7L, true))
    }

    @Test
    fun `a real alarm is refused while the test alarm rings`() {
        // Symmetry matters: whoever got there first keeps the ringer, test alarm included.
        assertEquals(Outcome.REFUSE, forStart(7L, -2L, true))
    }

    @Test
    fun `a duplicate start for the ringing alarm is ignored, not refused`() {
        // START_REDELIVER_INTENT redelivery, or two deliveries of the same broadcast. Module 1
        // relies on this not restarting the ringer and not re-stamping "last fired".
        assertEquals(Outcome.DUPLICATE, forStart(7L, 7L, true))
    }

    @Test
    fun `the same alarm re-asserting itself after the audio died rings again`() {
        // PlaybackRecovery.Plan.GiveUp leaves isRinging honestly false with the id still live.
        // A redelivered start for *that* alarm is recovery and must not be refused.
        assertEquals(Outcome.RING, forStart(7L, 7L, false))
    }

    @Test
    fun `a start with no id is refused while a real alarm rings`() {
        // The id sentinel doubles as "this start carried no EXTRA_ALARM_ID" (review M2-2.1), so an
        // id-less start must not be able to steal a live ringer either.
        assertEquals(Outcome.REFUSE, forStart(NO_ALARM, 7L, true))
    }

    @Test
    fun `a real alarm is refused while an id-less alarm is actually ringing`() {
        // The case the ringer clause exists for: nothing is recorded as ringing, but audio is
        // playing. Predicating on the id alone would hand the ringer over.
        assertEquals(Outcome.REFUSE, forStart(7L, NO_ALARM, true))
    }

    @Test
    fun `a real alarm rings once the previous one has been silenced`() {
        // silenceRinger clears the id and nulls the ringer together, so this is the state a dismiss
        // leaves behind. A refusal here would be the dangerous failure: no alarm at all.
        assertEquals(Outcome.RING, forStart(8L, NO_ALARM, false))
    }

    @Test
    fun `an id-less start rings when nothing at all is live`() {
        assertEquals(Outcome.RING, forStart(NO_ALARM, NO_ALARM, false))
    }

    @Test
    fun `an id-less duplicate of an id-less ring is ignored`() {
        assertEquals(Outcome.DUPLICATE, forStart(NO_ALARM, NO_ALARM, true))
    }

    // ------------------------------------------- BUILD_SPECS §4c: exhausted-recovery takeover

    @Test
    fun `an exhausted ringer is taken over by a different alarm, not left to refuse it`() {
        // §4c's table. Alarm A (06:00) rang, all three sources failed, three retry passes were
        // spent: GiveUp left isRinging false with A's id still set. Alarm B arrives at 06:05. The
        // old policy refused it, so neither alarm made a sound and B auto-disabled itself.
        assertEquals(Outcome.RING, forStart(incomingAlarmId = 2L, ringingAlarmId = 1L,
            ringerIsRinging = false, ringerRecoveryExhausted = true))
    }

    @Test
    fun `a ringing alarm that has not exhausted recovery still refuses a different alarm`() {
        // The whole of §4b item 3 is unchanged for every state except the exhausted one.
        assertEquals(Outcome.REFUSE, forStart(8L, 7L, ringerIsRinging = true,
            ringerRecoveryExhausted = false))
        assertEquals(Outcome.REFUSE, forStart(-2L, 7L, ringerIsRinging = true,
            ringerRecoveryExhausted = false))
    }

    @Test
    fun `an exhausted flag is ignored while the ringer still reports ringing`() {
        // The start window §4c forbids re-opening: the id is set while isRinging is transiently
        // false, and a stale exhausted flag must not let a second alarm take over mid-start. The
        // ringer clears the flag before it posts playback, so this state is unreachable in practice;
        // the policy refuses it anyway rather than trusting that.
        assertEquals(Outcome.REFUSE, forStart(8L, 7L, ringerIsRinging = true,
            ringerRecoveryExhausted = true))
    }

    @Test
    fun `an id-less start cannot take over even an exhausted ringer`() {
        // The takeover clause must not make NO_ALARM the live id: `finishDismiss` would then read
        // "nothing live" and tear down on any dismiss, and `fireTestAlarm` would unblock
        // (review 4c-1.3, M2-2.1's hazard class). Pins the `incomingAlarmId != NO_ALARM` conjunct.
        assertEquals(Outcome.REFUSE, forStart(NO_ALARM, 7L, ringerIsRinging = false,
            ringerRecoveryExhausted = true))
    }

    @Test
    fun `an alarm is never an overlap with itself in either the ringing or the exhausted state`() {
        for (exhausted in listOf(true, false)) {
            assertEquals(Outcome.DUPLICATE, forStart(7L, 7L, true, exhausted))
            assertEquals(Outcome.RING, forStart(7L, 7L, false, exhausted))
            assertEquals(Outcome.DUPLICATE, forStart(NO_ALARM, NO_ALARM, true, exhausted))
            assertEquals(Outcome.RING, forStart(NO_ALARM, NO_ALARM, false, exhausted))
        }
    }

    @Test
    fun `exhaustion changes exactly one thing and nothing else`() {
        val ids = listOf(NO_ALARM, -2L, 7L, 8L)
        for (incoming in ids) {
            for (ringing in ids) {
                for (isRinging in listOf(true, false)) {
                    val before = forStart(incoming, ringing, isRinging, false)
                    val after = forStart(incoming, ringing, isRinging, true)
                    // A live-but-silent *other* alarm's id is handed over to an alarm that has
                    // one; everything else — the same alarm, anything actually ringing, a cleared
                    // id and an id-less incoming start — is untouched. This is differential
                    // against `before`, computed from the same function, so it pins where
                    // exhaustion *changes* the answer and not the refusal clause itself; that the
                    // `ringingAlarmId != NO_ALARM` clause still decides the not-ringing case is
                    // pinned by `the decision is total...` below, through the 3-arg default.
                    val takenOver = incoming != ringing && !isRinging &&
                        ringing != NO_ALARM && incoming != NO_ALARM
                    assertEquals(
                        "exhausted($incoming, $ringing, $isRinging)",
                        if (takenOver) Outcome.RING else before,
                        after,
                    )
                }
            }
        }
    }

    @Test
    fun `the decision is total and never refuses an alarm that is already the ringing one`() {
        val ids = listOf(NO_ALARM, -2L, 0L, 1L, 7L, Long.MAX_VALUE)
        for (incoming in ids) {
            for (ringing in ids) {
                for (isRinging in listOf(true, false)) {
                    val outcome = forStart(incoming, ringing, isRinging)
                    if (incoming == ringing) {
                        assertEquals(
                            "an alarm must never be an overlap with itself",
                            if (isRinging) Outcome.DUPLICATE else Outcome.RING,
                            outcome,
                        )
                    } else {
                        // Different alarms: ring only when nothing whatsoever is live.
                        val nothingLive = ringing == NO_ALARM && !isRinging
                        assertEquals(
                            if (nothingLive) Outcome.RING else Outcome.REFUSE,
                            outcome,
                        )
                    }
                }
            }
        }
    }
}
