# Math Alarm — Brainstorm

> Working notes for an Android alarm clock that requires solving an arithmetic
> problem to dismiss. Living document: append, strike through, argue with it.
>
> Started: 2026-10-09

---

## 1. The core idea

A standard alarm clock with one twist: **the alarm does not stop until you solve
a math problem.** The point is to force enough cognitive load that you cannot
dismiss it in a half-asleep reflex and fall back to sleep.

The failure mode of every normal alarm is muscle memory — your hand finds the
snooze button before your brain is online. Arithmetic defeats muscle memory
because you cannot do it unconsciously.

### Design north star
Every decision gets judged against one question: **does this make it harder to
go back to sleep?** Not "is this a nice feature." If it does not serve waking
up, it is a distraction.

### Secondary principle
**Never be the reason someone misses something important.** An alarm app that
fails to ring is worse than no app. Reliability outranks every feature.
These two principles conflict (see §6 escape hatch) and the tension is real.

---

## 2. Landscape / prior art

| App | Approach | Takeaway |
|---|---|---|
| Alarmy | Missions: photo of a location, math, shake, typing, barcode | Market leader. Proves demand. Also proves people tolerate aggressive UX. |
| I Can't Wake Up! | Multiple dismissal tasks, chained | Chaining tasks works but gets tedious fast. |
| Puzzle Alarm Clock | Puzzle variety | Variety matters less than reliability. |
| Sleep as Android | Sleep tracking + CAPTCHA dismissal | Broad scope; the dismissal gate is a feature, not the product. |

**Where we fit:** do *one* thing extremely well. Arithmetic gate, rock-solid
firing, zero bloat, no ads, no account, no cloud. The honest pitch is
"it just works and it will not let you cheat."

**What we deliberately won't do:** sleep tracking, social features, accounts,
subscriptions, analytics SDKs.

---

## 3. User journeys

### 3.1 Happy path (setup)
1. Install, open. First-run screen explains why permissions are needed.
2. Grant notifications; grant exact alarm if the OS asks.
3. **Battery optimization prompt** — the most important screen in the app (§6).
4. Set a time. Pick difficulty. Pick how many questions.
5. "Test it now" button fires the alarm in 5 seconds so the user sees the real
   thing once while awake. This builds trust and surfaces OEM problems early.

### 3.2 Happy path (morning)
1. Time hits. Screen wakes, full-screen alarm over the lockscreen, sound ramps up.
2. Question appears with a numeric keypad.
3. User solves it (or N of them). Alarm stops. Screen shows the time and a
   "good morning" state so they do not immediately reopen their phone in bed.

### 3.3 The adversarial path — this is where the design is actually tested
Assume the user *is* the adversary. At 6am they are a hostile actor trying to
defeat software they themselves installed.

| Attack | Defense |
|---|---|
| Press back / home | Intercept; alarm UI stays. Alarm sound is in a foreground service, independent of the activity. |
| Swipe the notification | Non-dismissible foreground service notification. |
| Volume keys down | Intercept volume keys in the alarm activity; service re-asserts volume. |
| Kill from recents | Foreground service + restart on task removal. |
| Power off the phone | Reschedule on `BOOT_COMPLETED`. Can't win against a powered-off phone — accept it. |
| Airplane mode | Irrelevant, we are fully offline by design. |
| Guess the answer | Free-entry keypad, **never multiple choice** (4 options = 25% win rate for a mashed button). |
| Brute-force same question | New question generated on every wrong answer. |
| Change system clock | Listen for `TIME_SET` / `TIMEZONE_CHANGED` and reschedule. |
| Force stop in Settings | Mostly unwinnable. Requires enough deliberate action that the user is awake — arguably a win. |
| Uninstall | Not fighting this. Takes 20 seconds of being awake. |

**Line in the sand:** we make dismissing require *wakefulness*, not
*impossibility*. We do not use Device Admin, do not block Settings, do not hide
the app icon. Those fight the OS, spook users, and risk Play policy trouble.

---

## 4. The challenge system

### 4.1 Difficulty tiers (v1 = arithmetic only)

**Easy** — single-digit, mental, ~2 seconds
- `a + b` where a,b ∈ 2..9
- `a - b` where a ∈ 5..18, b < a, result > 0

**Medium** — needs actual attention, ~5-10 seconds
- `ab + cd` two-digit addition with a carry
- `a × b` where a,b ∈ 3..12 (times tables)
- `ab - cd` two-digit subtraction with a borrow

**Hard** — cannot be done asleep, ~15-30 seconds
- `ab × c` e.g. 37 × 8
- Two-step: `a × b + c`, `a × b - c`
- Clean division: build from a known product so the result is an integer

### 4.2 Generator rules (learned the hard way by other apps)
- **Reject degenerate problems.** No `× 0`, no `× 1`, no `× 10`, no `+ 0`,
  no identical operands at easy tier. These feel like a bug, not a challenge.
- **Guarantee integer answers.** Always generate division backwards from a product.
- **Guarantee a positive result.** Nobody does signed arithmetic at 6am.
- **No repeats** within a single alarm session.
- **Verify difficulty by carry/borrow presence**, not just digit count —
  `20 + 30` is not medium.

### 4.3 Interaction details
- Large numeric keypad. Big touch targets — fingers are clumsy when asleep.
- Answer evaluated on submit, not live, so you can correct a typo.
- Wrong answer: haptic buzz, red flash, **new question**, sound keeps playing.
- No time limit, no auto-dismiss, no "skip." The alarm has infinite patience.
- Show progress when multiple questions are required: `2 / 3`.
- Keep the clock visible the whole time — seeing 6:47am is itself motivating.

### 4.4 Snooze
Snooze is the enemy of the entire premise, but banning it outright makes the app
unusable for real life. Compromise:
- Snooze requires solving **one easy** question. Dismiss requires the full set.
  Asymmetry nudges toward getting up.
- Configurable max snooze count, default 3, then snooze disappears.
- Snooze is optional per alarm; a "no snooze" alarm is allowed.

**Open question:** should snooze difficulty escalate each time? Tempting, but
it may just produce rage. Needs real-world testing. *(see §9)*

---

## 5. Architecture sketch (native Kotlin)

```
┌─────────────────────────────────────────────────┐
│ UI (Jetpack Compose)                            │
│  AlarmListScreen · AlarmEditScreen              │
│  AlarmRingActivity  ← full-screen, over lockscreen│
│  OnboardingScreen (permissions + battery)       │
└──────────────┬──────────────────────────────────┘
               │
┌──────────────▼──────────────────────────────────┐
│ Domain                                          │
│  ProblemGenerator (pure, trivially unit-testable)│
│  AlarmScheduler  (wraps AlarmManager)           │
└──────────────┬──────────────────────────────────┘
               │
┌──────────────▼──────────────────────────────────┐
│ Platform                                        │
│  AlarmReceiver      (BroadcastReceiver)         │
│  AlarmService       (Foreground, holds sound)   │
│  BootReceiver       (BOOT_COMPLETED, TIME_SET)  │
│  Room DB            (alarms table)              │
└─────────────────────────────────────────────────┘
```

### 5.1 The firing chain
`AlarmManager.setAlarmClock()` → `AlarmReceiver.onReceive()` → start
`AlarmService` as foreground → service acquires wake lock, starts audio +
vibration → service launches `AlarmRingActivity` via full-screen intent.

Critical: **the sound lives in the service, not the activity.** If the user kills
the activity, the sound must survive. The activity is just a face on the service.

### 5.2 Key API choices and why
| Choice | Reason |
|---|---|
| `setAlarmClock()` not `setExact()` | Exempt from Doze, surfaces the next-alarm icon, signals intent to the OS. |
| **Not** WorkManager | Minimum ~15min periodic, no exactness guarantee. Wrong tool. |
| `USAGE_ALARM` audio attributes | Routes to the alarm volume stream, which bypasses Do Not Disturb. |
| Foreground service | Keeps the process alive and audible; Android 14+ requires a declared service type. |
| Full-screen intent | The only way to appear over the lockscreen. |
| Room | Alarms must survive reboot; needs durable storage. |
| Reschedule-on-boot | `AlarmManager` registrations are wiped by reboot. Non-negotiable. |

### 5.3 Data model (first cut)
```kotlin
data class Alarm(
    val id: Long,
    val hour: Int,
    val minute: Int,
    val enabled: Boolean,
    val label: String,
    val repeatDays: Set<DayOfWeek>,  // empty = one-shot
    val difficulty: Difficulty,       // EASY, MEDIUM, HARD
    val questionCount: Int,           // default 1
    val snoozeEnabled: Boolean,
    val snoozeMinutes: Int,
    val maxSnoozes: Int,
    val soundUri: String?,
    val vibrate: Boolean,
    val gradualVolume: Boolean,
)
```

### 5.4 Permissions
| Permission | Why | Notes |
|---|---|---|
| `POST_NOTIFICATIONS` | Foreground service notification | Runtime ask, Android 13+ |
| `SCHEDULE_EXACT_ALARM` / `USE_EXACT_ALARM` | Exact firing | Android 12+ restricted this; genuine alarm clocks are the allowed use case |
| `USE_FULL_SCREEN_INTENT` | Lockscreen alarm UI | Android 14 restricted it to alarm/calling apps |
| `RECEIVE_BOOT_COMPLETED` | Reschedule after reboot | |
| `FOREGROUND_SERVICE` + type | Keep audio alive | Android 14 requires a declared type |
| `WAKE_LOCK` | Turn the screen on | |
| `VIBRATE` | Vibration | |
| `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` | OEM killers | Must be user-initiated, well explained |

Verify each against current API levels during implementation rather than
trusting this table — Android tightens this area almost every release.

---

## 6. Reliability — the actual hard problem

Writing the math UI is an afternoon. **Making the alarm fire on a Xiaomi with
aggressive battery saving six hours after the app was last opened is the whole
engineering problem.**

### Threats
1. **Doze / App Standby** — mitigated by `setAlarmClock()`.
2. **OEM battery managers** — Xiaomi/MIUI, Oppo/ColorOS, Vivo, Huawei, Samsung
   each kill background processes more aggressively than stock Android, in ways
   that are undocumented and version-specific. This is the #1 source of
   one-star reviews for every alarm app on the Play Store.
3. **Reboot** — wipes all scheduled alarms.
4. **App update** — may need `MY_PACKAGE_REPLACED` handling.
5. **Clock / timezone change** — recompute next trigger.
6. **DST transitions** — a 7am daily repeat must stay 7am wall-clock. Store
   wall-clock time and recompute, never store an absolute epoch for repeats.
7. **Silent / DND mode** — `USAGE_ALARM` should get through; verify per-OEM.
8. **Bluetooth headphones connected** — do not let the alarm play only into
   headphones the user is not wearing. Force speaker output.

### Mitigations
- Onboarding step that detects the manufacturer and deep-links into the right
  battery settings screen with device-specific instructions.
- "Test alarm" button, and a **"last fired successfully" indicator** so silent
  failure is visible.
- Reschedule defensively on app open, boot, time change, and package replace.
- A **watchdog alarm** a few seconds after the primary, cancelled by the primary
  firing. Cheap insurance against a single missed registration.
- Self-diagnostic screen: lists every permission and battery setting with a
  green/red status, so a user with a broken setup can see *why*.

### The escape hatch problem
What if the user genuinely cannot solve the math — sick, injured, phone
malfunctioning, or just a question harder than they can handle at 4am?

Options considered:
- No escape at all → risks real harm, and violates §1's second principle.
- Big "I give up" button → defeats the product entirely.
- **Leaning toward:** after many consecutive failures (say 10+), offer an
  escape that is *slow and deliberate* — e.g. a 60-second countdown you must
  watch, or typing a long sentence. By the time it completes you are awake,
  which is the actual goal anyway.

*(Unresolved. See §9.)*

---

## 7. v1 scope

**v1 = prove the hard part.** The riskiest assumption is "the alarm fires
reliably." Validate that before building features on top of it.

### In
- [ ] Single alarm: set time, enable/disable
- [ ] Fires reliably — foreground service, full-screen over lockscreen, wake screen
- [ ] Arithmetic gate with 3 difficulty tiers, numeric keypad
- [ ] Wrong answer → new question, sound continues
- [ ] Survives reboot
- [ ] Survives back / home / recents / volume keys
- [ ] Battery-optimization onboarding
- [ ] "Test alarm in 5 seconds" button

### Out (deferred, not rejected)
Multiple alarms · repeat-day scheduling · custom sounds · gradual volume ·
snooze rules · themes · stats and streaks · other puzzle types (memory, typing,
QR/barcode, shake) · difficulty auto-escalation · sleep tracking · widgets ·
Wear OS · backup/restore

### Done means
A real phone, on a real night, with the app untouched for 8 hours, wakes you up
and will not let you dismiss it without arithmetic. Three nights running.

---

## 8. Risks

| Risk | Severity | Mitigation |
|---|---|---|
| OEM battery killers prevent firing | **Critical** | Onboarding, watchdog, diagnostics, test on a real Xiaomi/Samsung if possible |
| Play Store rejects exact-alarm or full-screen-intent usage | High | We are a literal alarm clock — the sanctioned use case. Declare honestly. |
| Android tightens background rules again | Medium | Keep the platform layer thin and isolated behind `AlarmScheduler` |
| Users find it annoying and uninstall | Medium | That is partly the point; make difficulty genuinely configurable |
| Someone misses something important because of the escape hatch (or lack of one) | High | Resolve §6 before any public release |
| Emulator passes, real devices fail | High | Test on physical hardware. The emulator does not reproduce OEM behavior. |

---

## 9. Open questions

1. **Escape hatch** — what is the failsafe after repeated failures? *(§6)*
2. **Snooze escalation** — does rising difficulty motivate or just enrage?
3. **Where does the user set difficulty** — per alarm, or one global setting?
   Per-alarm is more flexible; global is simpler. Probably per-alarm.
4. **Multiple questions by default** — is 1 enough to defeat sleep inertia,
   or should the default be 2-3?
5. **Should the question appear before the sound peaks**, so a fast solver gets
   a gentler wake-up? Or does that incentivize rushing?
6. **Minimum Android version?** Lower = more users, but more legacy
   alarm/permission branches to handle. Likely API 26+ (Android 8).
7. **Does the alarm sound ramp up, and over how long?** Gradual is kinder but
   gives more time to fall back asleep.
8. **What happens to a one-shot alarm after it fires** — auto-disable, or delete?

---

## 10. Decisions made

| Date | Decision | Rationale |
|---|---|---|
| 2026-10-09 | Arithmetic only in v1, 3 tiers | Keeps scope tight, matches the core idea; other puzzle types fit the same interface later |
| 2026-10-09 | "Strong but polite" anti-cheat | Full-screen + foreground service + boot persistence + key interception; no Device Admin, no uninstall blocking, no OS-fighting |
| 2026-10-09 | Native Kotlin + Jetpack Compose | All critical behavior is native Android API surface; cross-platform would need platform channels for the hard 70% anyway, and iOS cannot support this app class at all |
| 2026-10-09 | Working skeleton before features | Reliability is the risky unknown; validate it first |
| 2026-10-09 | Free-entry keypad, never multiple choice | 4-option MCQ is 25% solvable by random tapping |
| 2026-10-09 | Fully offline, no accounts, no ads, no analytics | Differentiator and simplification |
