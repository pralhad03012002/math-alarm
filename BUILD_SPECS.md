# Math Alarm — Build Specs

Execution plan for building the app in **3 modules**, strictly in order.
Design rationale lives in [BRAINSTORM.md](BRAINSTORM.md); this file is the
*build contract* and the *review ledger*.

**Status:** **v1 code-complete and scope-closed (2026-10-14, project owner's decision).** Three modules plus rounds 4b/4c/4d, ten review rounds, 144 unit tests, clean build. Round 4d closes with **MAJOR 4d-1.1 accepted as a known limitation, not fixed** — the failed-start settle is dispatched rather than joined, so it can be lost if the process is frozen before it commits; the false platform justification has been corrected in the code. All other rounds advanced with 0 open blockers/majors. **Nothing has ever been observed to ring on a device** — the emulator plan in §4c/§4d's ledgers is the next step, and no further code rounds are planned.
**Last updated:** 2026-10-09

---

## 0. Locked decisions

Settled; do not relitigate without a note in the Change Log (§7).

| Area | Decision |
|---|---|
| Language / UI | Kotlin + Jetpack Compose, native Android only |
| Build | Gradle wrapper 9.6.0, AGP 9.4.1, Compose compiler plugin 2.4.21 |
| SDK | compileSdk 37, targetSdk 36, minSdk 26 |
| Compose | BOM 2026.09.00, Material3 |
| Editor | VS Code. Android Studio is **never** opened; it only supplies the JDK + SDK |
| Challenge | Arithmetic only. 3 tiers: EASY / MEDIUM / HARD |
| Difficulty config | **Per alarm.** Default MEDIUM × **1** question |
| Snooze | **None in v1.** No snooze button, no snooze state, no snooze fields |
| Volume | **Instant max** on the alarm stream. No ramp |
| Escape hatch | 60-second countdown, unlocked **only after 5 consecutive wrong answers**. The only escape |
| Answer entry | Numeric keypad, free entry. **Never multiple choice** |
| Network | Fully offline. No accounts, no ads, no analytics, no network permission |
| Verification | **Code review only.** Agents must get `assembleDebug` green; they do **not** run the app, boot an emulator, or check UI behavior |

---

## 1. The build loop

For each module, in order, Module 1 → 2 → 3:

```
   ┌─────────────────────────────────────────────┐
   │  1. DEV AGENT    implements the module      │
   │        ↓                                     │
   │  2. REVIEW AGENT reviews, writes findings   │
   │     into this file under that module        │
   │        ↓                                     │
   │  3. DEV AGENT    fixes BLOCKER + MAJOR      │
   │        ↓                                     │
   │  4. back to 2, until none remain open ──────┤
   └─────────────────────────────────────────────┘
                      ↓
              advance to next module
```

### Rules

1. **Serial only.** One module at a time. Never two dev agents at once.
2. **A module advances only when every BLOCKER and MAJOR finding is closed.**
3. **`./gradlew assembleDebug` must exit 0** at the end of every dev round. This
   is a compile gate, not a UI check.
4. **The review agent writes its findings into this file**, appending a new
   round under that module's Review Ledger. It does not fix code.
5. **The dev agent does not grade its own work.** Only a review agent may mark
   a finding closed, on re-review.
6. **Round cap: 3 fix rounds per module.** If BLOCKERs remain after round 3,
   stop and escalate to the user rather than looping further.
7. **Scope discipline.** A dev agent touches only its own module's files plus
   shared files it must. It never implements a later module's features.

### Severity bar

| Severity | Meaning | Gates advancement? |
|---|---|---|
| **BLOCKER** | Won't compile, crashes, or the module's core promise fails (e.g. alarm cannot fire, alarm can be silently killed) | **Yes** |
| **MAJOR** | Real defect: reliability hole, permission missing, leak, race, lost state, wrong behavior on a supported API level | **Yes** |
| **MINOR** | Code quality, naming, dead code, missing test for a non-critical path | No — logged only |
| **NIT** | Style, formatting, preference | No — logged only |

MINOR and NIT findings stay in the ledger as a backlog. They do not block.

---

## 2. Module 1 — Alarm engine

**Goal: an alarm that reliably fires, appears over the lockscreen, makes noise
that cannot be casually silenced, and survives reboot.** No math yet — dismiss
is a plain button. This is the riskiest module; everything else is easier.

### Deliverables

**Data**
- `Alarm` entity — `id`, `hour`, `minute`, `enabled`, `label`, `difficulty`,
  `questionCount`, `vibrate`. **No snooze fields.** Repeat days may be stubbed
  as an empty set for now; Module 3 owns the editing UI.
- Room database, DAO, singleton. Must survive process death and reboot.

**Scheduling**
- `AlarmScheduler` wrapping `AlarmManager`. Uses
  `setAlarmClock()` — not `setExact()`, not `setExactAndAllowWhileIdle()`,
  not WorkManager — so the alarm is exempt from Doze and surfaces the
  next-alarm indicator.
- Computes the next trigger from wall-clock time. For a repeating alarm, store
  hour/minute and **recompute**; never persist an absolute epoch, so DST and
  timezone changes stay correct.
- Cancel + reschedule on alarm change.

**Firing chain**
- `AlarmReceiver` (BroadcastReceiver) → starts `AlarmService` as a foreground
  service → service acquires a wake lock, starts audio + vibration → service
  launches `AlarmRingActivity` via a full-screen intent.
- **The audio lives in the service, not the activity.** Killing the activity
  must not stop the sound. This is the single most important structural rule in
  this module.

**Audio**
- `AudioAttributes` with `USAGE_ALARM` + `CONTENT_TYPE_SONIFICATION`, played on
  `STREAM_ALARM` so it beats Do Not Disturb.
- Set alarm-stream volume to **maximum immediately**. No fade.
- Force output to the speaker so the alarm never plays only into unworn
  Bluetooth headphones.
- Looping. Vibration pattern alongside, honoring the alarm's `vibrate` flag.

**Lockscreen presentation**
- `AlarmRingActivity`: `setShowWhenLocked(true)`, `setTurnScreenOn(true)`,
  `FLAG_KEEP_SCREEN_ON`. **Do NOT call `requestDismissKeyguard`** — on a secure
  device it stacks an authentication bouncer in front of the Dismiss button.
  `setShowWhenLocked(true)` alone already shows the alarm over the keyguard, which is
  what AOSP DeskClock and Google Clock do.
- Shows the current time, the alarm label, and a **plain "Dismiss" button**.
- Dismiss stops the service, releases the wake lock, and for a one-shot alarm
  **auto-disables it** (keeps the row so the user can re-enable; does not delete).

**Persistence across system events** — a `BootReceiver` that reschedules every
enabled alarm on: `BOOT_COMPLETED`, `MY_PACKAGE_REPLACED`, `TIME_SET`,
`TIMEZONE_CHANGED`, `LOCKED_BOOT_COMPLETED`.

**Manifest + permissions** — declare and justify each:
`SCHEDULE_EXACT_ALARM`, `USE_EXACT_ALARM`, `POST_NOTIFICATIONS`,
`USE_FULL_SCREEN_INTENT`, `RECEIVE_BOOT_COMPLETED`, `FOREGROUND_SERVICE`,
the specific foreground-service-type permission, `WAKE_LOCK`, `VIBRATE`.
Service declares `android:foregroundServiceType`. **No INTERNET permission.**

**Debug trigger** — a temporary button on `MainActivity` that schedules an alarm
10 seconds out, so the chain is exercisable without waiting for morning.

### Acceptance criteria
- [ ] `./gradlew assembleDebug` exits 0
- [ ] Audio is owned by the service; no audio reference in the activity
- [ ] `setAlarmClock()` used; no WorkManager anywhere
- [ ] Repeating alarms store wall-clock, not epoch
- [ ] Boot receiver covers all five actions above
- [ ] Foreground service type declared and matched by a permission
- [ ] Wake lock acquired and released on every exit path, including crash
- [ ] No `INTERNET` permission in the merged manifest
- [ ] Unit tests for next-trigger computation, including a DST boundary

### Review Ledger — Module 1
*Review agents append rounds here. Do not edit by hand.*

### Round 1 — 2026-10-09

**Verdict:** 0 blockers, 4 majors, 13 minors, 5 nits. The firing chain is structurally
sound and several of the subtle `AlarmManager`/`PendingIntent` traps were genuinely
avoided, but four real defects gate the module: a permanently corrupted alarm-stream
volume, a keyguard bouncer put in front of the Dismiss button on every secure device, a
destructive Room migration that will silently wipe every alarm on the first schema
change, and a silenced `MediaPlayer` with no recovery.

| # | Severity | File:line | Finding | Why it matters |
|---|---|---|---|---|
| 1.1 | MAJOR | AlarmRinger.kt:82, 90-96 (with AlarmService.kt:50) | `volumeBeforeRinging` is in-memory only. The service returns `START_REDELIVER_INTENT`, so after an OOM kill the redelivered `ACTION_START` runs `forceMaxAlarmVolume()` in a fresh process, reads the current volume — which the dead instance already pinned to **max** — and stores *max* as "the user's prior value". The subsequent dismiss then "restores" max. Force-stop and crash-without-redelivery leave it at max with no restore attempt at all. | The user's alarm-stream volume is permanently rewritten to maximum with no recovery path. §7 added the restore specifically so the app would not "permanently rewrite a system setting the user owns"; as written it does exactly that. Needs a durable pre-ring record (DE-storage `SharedPreferences`, written before the first `setStreamVolume`, cleared only after a successful restore) so a restart cannot mistake max for the user's choice. |
| 1.2 | MAJOR | AlarmRingActivity.kt:98-100 | `requestDismissKeyguard(this, null)` is called unconditionally from `onCreate`. Confirmed against SDK 37 `KeyguardManager.java:862`: "If the Keyguard is secure and the device is not in a trusted state, this will bring up the UI so the user can enter their credentials." Device state: any phone with a PIN/pattern/password and no active trust agent — i.e. the normal case. Result: the bouncer is stacked on top of the alarm activity and the Dismiss button is unreachable until the user authenticates or backs out of the bouncer. | Directly undercuts the module's "appears over the lockscreen" deliverable. `setShowWhenLocked(true)` alone already shows the alarm over the keyguard without authentication, which is what AOSP/Google Clock do. In Module 2 this gets worse: the math keypad would sit behind an authentication prompt at 6am. The call should be dropped, or at minimum gated on `!keyguardManager.isKeyguardSecure`. |
| 1.3 | MAJOR | AlarmDatabase.kt:48 (with :19 `exportSchema = false`) | `.fallbackToDestructiveMigration(dropAllTables = true)` with no `Migration` objects and no exported schema. Scenario: v1 ships; Module 3 (or any later release) adds a column — e.g. a real `repeatDays` edit path, or a `lastFiredAt` for the §4 "last fired successfully" indicator — and bumps `version` to 2. On update, Room drops `alarms` and recreates it empty. The user sees an empty list and **every alarm they relied on simply never rings again**, with no error and no prompt. | This is the silent-failure-at-6am class, triggered by a routine app update rather than an edge case. Replace with explicit `addMigrations(...)` and set `exportSchema = true` (plus `room.schemaLocation`) now, while the schema is still version 1 and a baseline JSON can be captured for free. |
| 1.4 | MAJOR | AlarmRinger.kt:130-155 | No `MediaPlayer.setOnErrorListener`. `buildPlayer()` only handles failures that occur *before* `start()` returns; once playback is running, an asynchronous error — `MEDIA_ERROR_SERVER_DIED` on a mediaserver restart, or a decode/IO error on a `content://` ringtone whose backing file is removed or whose provider is killed — puts the player into the error state and playback stops. Nothing observes that: there is no error listener, no watchdog, the fallback chain is never re-entered, and `isRinging` (line 50, `player != null`) still reports `true`, so even a redelivered `ACTION_START` takes the "already ringing; ignoring duplicate start" branch at AlarmService.kt:77 and does nothing. | The alarm goes silent mid-ring and stays silent, while the service, notification and full-screen activity all continue to claim it is ringing. Vibration keeps going only if `vibrate` is set. Fix: `setOnErrorListener` that releases the failed player and re-runs `candidateSources()` from the next source (ending at `R.raw.fallback_alarm`), and make `isRinging` reflect actual playback. |
| 1.5 | MINOR | AlarmService.kt:148-158 | The KDoc claim that the direct `startActivity` covers the unlocked case is **false**. Confirmed: a foreground service is not on the documented background-activity-launch exemption list, and `AlarmManagerService` explicitly calls `setPendingIntentBackgroundActivityLaunchAllowed(false)` for broadcast alarm operations, so the alarm broadcast grants no BAL privilege. With no visible window the launch is silently blocked by the system — it does **not** throw, so the `runCatching` is safe but catches nothing and provides no actual fallback. | The code is harmless but the comment will mislead the next dev into thinking there is a second path to the alarm UI when there is only one (the full-screen intent). Related gap: there is no `NotificationManager.canUseFullScreenIntent()` check (API 34+) and no check that notifications are enabled — with either denied, the alarm rings with **no UI and no way to dismiss it short of Settings**. Module 3 owns the onboarding, but it must hard-gate scheduling on both, not just ask. |
| 1.6 | MINOR | AlarmService.kt:98-109 (with :42-45) | `handleDismiss` does not check `alarmId == ringingAlarmId`, and calls `stopSelf()` rather than `stopSelf(startId)`. Scenario: alarm A is ringing; the user taps Dismiss; before that intent is processed on the main thread, alarm B fires and its `ACTION_START` runs `handleStart` (startId 3). The dismiss for A (startId 2) is then processed and unconditionally runs `stopAlarm(); stopSelf()`, silencing the alarm B that just started. B's `AlarmManager` registration is already consumed and its row is still enabled, so B never rings. | Two alarms within a few seconds of each other produce a silently missed alarm. `stopSelf(startId)` would decline to stop when a newer start exists, and guarding on `ringingAlarmId` makes the dismiss target-specific. |
| 1.7 | MINOR | AlarmService.kt:98-106, 234-240 | Overlapping alarms: only the dismissed `alarmId` reaches `disableIfOneShot`. Scenario: one-shot alarms A and B both fire at 07:00. One service instance, one ringer, one notification. The user dismisses (B). A's row stays `enabled = true` while its registration has already been consumed, so `AlarmRescheduler.rescheduleAll` on the next boot or `TIME_SET` re-books A for 07:00 **the following day**. | A one-shot alarm the user already dealt with comes back unannounced. The service needs a set of currently-ringing ids and must disable all of them on dismiss. |
| 1.8 | MINOR | AlarmService.kt:95-109 | A one-shot alarm is auto-disabled only on explicit dismiss, never at fire time. Scenario: the alarm fires at 07:00 and the user powers the phone off, force-stops, or the process is OOM-killed before tapping Dismiss. The row stays `enabled = true`, so `rescheduleAll` on the next `BOOT_COMPLETED` re-registers the same one-shot for tomorrow. | Spec §2 says a one-shot "auto-disables" after firing. Disabling at fire time (inside `rescheduleIfRepeating`'s existing DB round-trip, while the service is still foreground and the wake lock is held) is both more faithful to the spec and strictly safer than doing it during teardown. |
| 1.9 | MINOR | AlarmService.kt:100-108 (with AlarmWork.kt:13) | Ordering hazard on the `AlarmWork` process-lifetime scope. `handleDismiss` launches `disableIfOneShot` and then immediately runs `stopAlarm()` — which releases the wake lock — followed by `stopSelf()`. The activity has already called `finish()`. So at the exact moment the DB write is dispatched, the process has no foreground component and no wake lock: the screen is off, autosuspend can freeze the `Dispatchers.IO` thread mid-write, and the now-cached process is killable. If it is killed before `dao.disable` commits, the write is lost and the one-shot alarm rings again tomorrow (same outcome as 1.8). `AlarmScheduler.cancel(alarmId)` on line 239 is lost with it. | Lost state on a path that runs on every single dismiss. Hold the wake lock (or keep the service foreground) until the write completes, or move the write ahead of teardown. Note the reschedule/disable ordering itself is fine — `rescheduleIfRepeating` is a no-op for one-shots and `disableIfOneShot` is a no-op for repeats, so the two cannot interleave destructively. |
| 1.10 | MINOR | AlarmWakeLock.kt:29 | The 10-minute `TIMEOUT_MS` ceiling means that at minute 11 of an unattended alarm the app's own `PARTIAL_WAKE_LOCK` is dropped by the OS while the alarm is still ringing, and nothing re-acquires it. The audio survives anyway, because `AlarmRinger.kt:135` calls `mp.setWakeMode(appContext, PARTIAL_WAKE_LOCK)` and `MediaPlayer` holds its own non-reference-counted, **untimed** lock for the duration of playback — so this is not the silent-death risk it looks like. | Only the vibrate-only case (all audio sources failed, `vibrate = true`) is left depending on `VibratorManagerService`'s own lock. The real cost is that the documented contract in the KDoc ("held from the instant `AlarmReceiver` fires until `AlarmService` is torn down") is not met past 10 minutes. Either re-acquire on a timer while ringing, or correct the comment and state the dependency on `setWakeMode` explicitly so a later refactor does not remove it. |
| 1.11 | MINOR | AlarmScheduler.kt:51-55 | The exact-alarm denial path logs to logcat and returns `null`. Nothing surfaces it. Scenario: an API 31-32 device (where `USE_EXACT_ALARM` does not exist and `SCHEDULE_EXACT_ALARM` is user-revocable) with "Alarms & reminders" turned off. `AlarmRescheduler.rescheduleAll` then logs "rescheduled 0 of N alarms" after every boot and the user is never told — the app looks fine and no alarm ever rings again. | The KDoc says "Fail loudly rather than silently half-working", but a `Log.e` is invisible to the user. `MainActivity` surfaces it for the debug button only. Module 3 owns the permission UX and the diagnostics screen; this needs to be on that list explicitly, plus an `ACTION_SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED` receiver so a later grant triggers a reschedule. |
| 1.12 | MINOR | AlarmRinger.kt:78-88 | `setStreamVolume` throws `SecurityException` when the change would toggle Do Not Disturb and the app lacks notification-policy access (confirmed in SDK 37 `AudioManager.java:1593`). Device state: DND in total-silence / "alarms off" mode. The `runCatching` swallows it and the alarm then plays at whatever the alarm stream is currently set to, which in that mode is muted. `isVolumeFixed()` devices are silently ignored the same way. | BRAINSTORM §6.7 lists silent/DND mode as an explicit threat; the current code cannot distinguish "volume raised" from "volume rejected". At minimum the failure should be recorded so Module 3's diagnostics can show it, since the app cannot fix it without `ACCESS_NOTIFICATION_POLICY`. |
| 1.13 | MINOR | AlarmRinger.kt:130-148 | `mp.prepare()` (and `setDataSource(appContext, uri)` for a `content://` ringtone) runs synchronously on the service main thread, inside `handleStart`. A slow or wedged `MediaProvider` blocks the thread that must also process `ACTION_DISMISS`. | ANR risk on the one component that must stay responsive, and an unresponsive main thread means the alarm cannot be dismissed. `prepareAsync` or a dedicated playback thread (as AOSP DeskClock uses) removes it. |
| 1.14 | MINOR | AlarmRinger.kt:52-59, 101-112, 182-195 | `AlarmRinger.start()` is called inside `handleStart`'s `try`, whose `catch` runs `stopAlarm(); stopSelf()` — i.e. any throw from the ringer **cancels the alarm entirely** rather than letting it ring degraded. Two calls inside `start()` are unguarded: `AudioFocusRequest.Builder...build()` (lines 103-108) and `VibrationEffect.createWaveform(VIBRATION_PATTERN, 0)` (line 186). I verified neither throws as currently configured — SDK 37 `AudioFocusRequest.java:573` only throws when delayed focus or pause-on-duck is set with a null listener, and the waveform/repeat-index pair is valid — so there is no live bug. | The structure inverts the module's priority: a future tweak (adding `setAcceptsDelayedFocusGain`, changing the vibration pattern) turns a cosmetic failure into a dead alarm. Wrap focus and vibration setup so that only a failure to make *any* sound can stop the start path. |
| 1.15 | MINOR | AlarmService.kt:133-144 | The post-fire reschedule computes `ZonedDateTime.now()` inside an async coroutine rather than from the trigger instant that just fired. `NextTrigger` returns the first candidate strictly after `now`, so if the coroutine's `now` lands at or marginally before the nominal trigger time (clock adjustment, an NTP/`TIME_SET` correction arriving during boot, or a trigger delivered a hair early), today's occurrence is re-booked and `AlarmManager` fires it again almost immediately — potentially repeatedly. | Baselining off `triggerAtMillis + 1 minute` instead of "now" removes the whole class. No double-fire or skip was found on the normal path: `candidate.isAfter(now)` correctly excludes the just-consumed occurrence, and the `START_REDELIVER_INTENT` re-entry is idempotent. |
| 1.16 | MINOR | AlarmRingActivity.kt:61-79 | `AlarmRingScreen(label = label, ...)` reads the `label` field once at composition. `label` is a plain field, not Compose state, so `onNewIntent` (lines 70-74) updates `alarmId`/`label` but the UI keeps showing the first alarm's label. With `launchMode="singleTask"` + `FLAG_ACTIVITY_CLEAR_TOP`, a second alarm always reuses the instance. | The lockscreen shows the wrong alarm's name while `dismiss()` (which reads the field at call time) acts on the new one. Hold both in `mutableStateOf`. |
| 1.17 | MINOR | NextTriggerTest.kt (whole file) | 17 tests, and the DST assertions are meaningful rather than accidentally passing (see Verified correct #12). But there is no coverage for: `Alarm.maskToDays`/`daysToMask` round-tripping (the Monday-is-bit-0 convention at Alarm.kt:42-45 is untested in either direction), `DifficultyConverter.fromName` with an unknown stored value (AlarmDatabase.kt:15-16), or `NextTrigger.next` called with `now` exactly equal to a just-fired repeating trigger across a DST boundary. | The day-mask convention is the input to every repeating schedule and is pure, zero-dependency code — exactly the kind of thing that should be covered before Module 3 wires the repeat-day editor to it. |
| 1.18 | NIT | AlarmDatabase.kt:46 | `appContext.createDeviceProtectedStorageContext() ?: appContext` — the platform method is declared non-null, so the elvis branch is unreachable. | Dead code that implies a failure mode that does not exist. |
| 1.19 | NIT | AlarmService.kt:163-166 | `FLAG_ACTIVITY_CLEAR_TOP` is redundant alongside `android:launchMode="singleTask"`. | Noise. |
| 1.20 | NIT | NextTriggerTest.kt:146 | `at(newYork, "2026-11-01T00:30").withEarlierOffsetAtOverlap()` is a no-op: the overlap on 1 November 2026 is 01:00-02:00, so 00:30 is unambiguous. | The call suggests the overlap is wider than it is; the test still asserts the right thing. |
| 1.21 | NIT | AndroidManifest.xml:43-47 | No `android:icon`, so the launcher falls back to the default. Separately, `allowBackup="true"` has no effect on the alarm data: the DB now lives in device-protected storage, which Auto Backup does not cover, so a restored phone comes up with zero alarms and no indication why. | Backup/restore is out of scope for v1 (§5), but the `allowBackup="true"` declaration now promises something it cannot deliver. |
| 1.22 | NIT | AlarmService.kt:212 vs AlarmRingActivity.kt:53, 77 | The `-1L` "no alarm" sentinel is `AlarmService.NO_ALARM` in one file and a bare literal in the other. | One definition, used by both. |

**Verified correct** — these look wrong or risky and are not; do not "fix" them.

1. **`android:exported="false"` on `BootReceiver` and `AlarmReceiver` (AndroidManifest.xml:94, 104).** Confirmed, not refuted, against SDK 37 sources: `ActivityManager.checkComponentPermission` (`ActivityManager.java:5500`) calls `canAccessUnexportedComponents(uid)` (`:5486`, "Root, system server get to do everything") and returns `PERMISSION_GRANTED` **before** it ever consults the `exported` flag. All five of `BOOT_COMPLETED`, `LOCKED_BOOT_COMPLETED`, `MY_PACKAGE_REPLACED`, `TIME_SET` and `TIMEZONE_CHANGED` are sent by `system_server` (`SYSTEM_UID`), so they are delivered. The `AlarmManager` fire broadcast is sent through a `PendingIntent` under the app's own uid, which hits the `isSameApp(uid, owningUid)` branch at `:5513`. `exported="false"` is both correct and the right call for spoofing resistance.
2. **Cancellation genuinely matches the scheduled registration (AlarmScheduler.kt:71-80 vs :95-106).** The classic silent `AlarmManager` bug is not present. `PendingIntentController.getIntentSender` strips `FLAG_NO_CREATE`/`FLAG_UPDATE_CURRENT`/`FLAG_CANCEL_CURRENT` from the lookup key and retains `FLAG_IMMUTABLE`; both call sites pass `FLAG_IMMUTABLE`, neither passes `FLAG_ONE_SHOT`, and `Intent.filterEquals` ignores extras — so the extras baked in at schedule time (and absent at cancel time) are irrelevant. `cancel()` finds and cancels the real registration.
3. **PendingIntent request codes cannot collide (AlarmScheduler.kt:130, 112-117).** `alarmId.toInt()` would need ids >= 2^31, and even then the per-alarm `mathalarm://alarm/$alarmId` data URI makes the intents `filterEquals`-distinct, so two alarms can never share a `PendingIntent`. `FLAG_UPDATE_CURRENT` is the right flag for re-registering the same alarm, and `FLAG_IMMUTABLE` is right because nothing needs the system to fill in extras. The `getActivity` intents (`SHOW_INTENT_REQUEST_CODE = -1`, and `alarmId.toInt()` at AlarmService.kt:188) are a different `PendingIntent` type and target different components, so they do not collide with the broadcast either.
4. **Declaring both `USE_EXACT_ALARM` and `SCHEDULE_EXACT_ALARM` (AndroidManifest.xml:16-19).** Not redundant and not a Play problem: unbounded `USE_EXACT_ALARM` (API 33+, granted at install, non-revocable) plus `SCHEDULE_EXACT_ALARM` capped at `maxSdkVersion="32"` is exactly Google's documented pattern for covering API 31-32, and an alarm clock is the sanctioned `USE_EXACT_ALARM` use case. `canScheduleExactAlarms()` *is* checked before every `setAlarmClock` (AlarmScheduler.kt:51) and is correctly guarded by `SDK_INT >= S` (:89). See 1.11 for the denial path.
5. **Audio focus with no focus-change listener (AlarmRinger.kt:101-112).** Safe and well-reasoned. SDK 37 `AudioFocusRequest.java:573` throws only when `mDelayedFocus || mPausesOnDuck` and the listener is null; the code sets `setWillPauseWhenDucked(false)` and never requests delayed focus, so `build()` cannot throw. Omitting the listener is the right choice — nothing should be able to hand an alarm a reason to go quiet.
6. **`foregroundServiceType="mediaPlayback"` has no time limit.** In SDK 37 `ServiceInfo.java`, only `dataSync`, `shortService` and `mediaProcessing` document a timeout and an `onTimeout` obligation; `FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK` (`:209`) documents none. The service can ring indefinitely and needs no `onTimeout` override. The Android 15 restriction on `BOOT_COMPLETED` receivers starting a `mediaPlayback` FGS does not apply, because `BootReceiver` only schedules alarms.
7. **Starting the foreground service from the alarm broadcast is a documented exemption.** "Your app invokes an exact alarm to complete an action that the user requests" is on the Android 12+ FGS background-start exemption list, so `ContextCompat.startForegroundService` from `AlarmReceiver` is legitimate at API 36, and `startForeground` is correctly the first thing `handleStart` does.
8. **Room in `createDeviceProtectedStorageContext()` does work, and `LOCKED_BOOT_COMPLETED` is live code, not dead code.** Room opens normally under `/data/user_de/<user>/<pkg>/databases/`, which is readable before the first unlock, and all four components carry `directBootAware="true"`, so a phone rebooted at 3am really does reschedule. The privacy cost is acceptable: device-protected storage drops credential encryption but keeps per-app file permissions, so alarm labels are exposed only to root/system or physical extraction, not to other apps. Worth the trade for the deliverable.
9. **The audio fallback chain is right for direct boot (AlarmRinger.kt:157-163).** `RingtoneManager`-derived `content://media/...` URIs genuinely fail pre-unlock (MediaProvider is not direct-boot aware) and the code correctly falls through to `R.raw.fallback_alarm`. I verified the bundled file is a real tone and not a silent placeholder: 32,044-byte RIFF/WAVE, 16-bit mono PCM, 8 kHz, 2.00 s, peak amplitude 30000, 55% of samples above the noise floor. `.wav` is on aapt's no-compress list, so `openRawResourceFd` (line 139) works rather than throwing "probably compressed".
10. **`startService` for dismiss (AlarmRingActivity.kt:108) is allowed.** `ActiveServices.startServiceLocked` skips the background-start check when `r.startRequested` is already true, so delivering `ACTION_DISMISS` to the already-running service cannot throw the API 26+ `IllegalStateException`.
11. **Room hygiene.** No `allowMainThreadQueries`; every `AlarmDao` method is `suspend`; the singleton double-checked lock (AlarmDatabase.kt:40-43) is correct with `@Volatile`. `DifficultyConverter.fromName` (`:15-16`) degrades an unknown stored value to `MEDIUM` instead of throwing, so a hand-edited or downgraded row cannot crash the firing chain.
12. **The DST test dates are real and the assertions are meaningful.** 8 March 2026 is the second Sunday of March and 1 November 2026 is the first Sunday of November — both are genuinely Sundays, so both hardcoded transition dates are correct. The load-bearing assertions are `Duration.ofHours(23)` (NextTriggerTest.kt:105) and `Duration.ofHours(25)` (`:139`) between consecutive 07:00 triggers; those are exactly what would fail if a repeating alarm persisted an epoch and added 24 h, so they are not passing for the wrong reason. The gap case (02:30 -> 03:30, `:118`) and the overlap case (earlier offset, rings once, `:149-154`) both match `ZonedDateTime.of`'s documented behaviour and the `NextTrigger` KDoc.
13. **The structural rule holds.** `AlarmRinger` is `internal` and constructed only at AlarmService.kt:82; `AlarmRingActivity` holds no `MediaPlayer`, `AudioManager`, `Vibrator` or wake lock and can only send `ACTION_DISMISS`. Killing the activity cannot silence the alarm.
14. **Wake lock accounting on the "service never starts" path is correct.** `AlarmReceiver` catches `Throwable` around `startForegroundService` and releases the lock (AlarmReceiver.kt:37-41) — the one path where nothing else would ever release it. Double-release is impossible: `AlarmWakeLock.release()` nulls the field under `@Synchronized` and re-checks `isHeld`. A hard process kill drops the lock with the process.
15. **`BootReceiver`'s `goAsync()` budget is not at risk (BootReceiver.kt:35-44).** The background-broadcast budget is ~60 s, and `rescheduleAll` is one `getAll()` plus two cheap binder calls per alarm; `pending.finish()` is in a `finally`, so even a thrown exception cannot leak the broadcast. The `LOCKED_BOOT_COMPLETED`-then-`BOOT_COMPLETED` double-run is genuinely idempotent — every interleaving of two concurrent `rescheduleAll` passes ends with the alarm registered.
16. **`Intent.ACTION_TIME_CHANGED` is the correct constant for `android.intent.action.TIME_SET`** (BootReceiver.kt:54 vs AndroidManifest.xml:109). The name mismatch is a standard trap and the pairing here is right, so all five manifest actions are matched by `HANDLED_ACTIONS`.
17. **No double-fire or skip on the repeating path.** `candidate.isAfter(now)` (NextTrigger.kt:54) strictly excludes the occurrence that just fired, the `an exact match on the current minute is treated as past` test pins that boundary, and re-entering `handleStart` via `START_REDELIVER_INTENT` recomputes the same next occurrence. See 1.15 for the only residual clock-jitter concern.

**Acceptance criteria audit**
- [x] `./gradlew assembleDebug` exits 0 — independently verified before this review; `testDebugUnitTest` 17/17 also green.
- [x] Audio is owned by the service; no audio reference in the activity — `AlarmRinger` is `internal`, constructed only at AlarmService.kt:82; `AlarmRingActivity` imports no media/vibrator/power API and its only lever on the alarm is `ACTION_DISMISS` (AlarmRingActivity.kt:108).
- [x] `setAlarmClock()` used; no WorkManager anywhere — the single `AlarmManager` call site is AlarmScheduler.kt:58; a grep of `app/src/main` finds `setExact`/`setRepeating`/`WorkManager` only inside the AlarmScheduler.kt:18-19 KDoc.
- [x] Repeating alarms store wall-clock, not epoch — the `Alarm` entity carries only `hour`, `minute` and `repeatDaysMask` (Alarm.kt:22-31) with no trigger column, and every schedule path recomputes through `NextTrigger.nextMillis` (AlarmScheduler.kt:41). Proven by the 23 h / 25 h DST assertions.
- [x] Boot receiver covers all five actions above — AndroidManifest.xml:106-110 and BootReceiver.kt:50-56 agree on all five, with `ACTION_TIME_CHANGED` correctly paired to `android.intent.action.TIME_SET`. `directBootAware="true"` plus the device-protected database make `LOCKED_BOOT_COMPLETED` genuinely functional rather than decorative.
- [x] Foreground service type declared and matched by a permission — `android:foregroundServiceType="mediaPlayback"` (AndroidManifest.xml:86), `FOREGROUND_SERVICE_MEDIA_PLAYBACK` (`:35`), and `FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK` passed to `ServiceCompat.startForeground` (AlarmService.kt:72). No time limit applies to this type.
- [x] Wake lock acquired and released on every exit path, including crash — all four documented paths check out (see Verified correct #14), and the `acquire(TIMEOUT_MS)` ceiling plus death-with-process cover the paths where no code of ours runs again. Caveat, not a failure of this criterion: see 1.10 for the 10-minute ceiling expiring mid-ring and 1.9 for the lock being dropped before the write it was protecting.
- [x] No `INTERNET` permission in the merged manifest — independently verified: exactly 9 permissions, zero `INTERNET`, and no dependency in app/build.gradle.kts that would introduce one.
- [x] Unit tests for next-trigger computation, including a DST boundary — both directions are covered with meaningful assertions and real 2026 transition dates (Verified correct #12). See 1.17 for what is *not* covered.
- [ ] **Deliverable not genuinely met (§2 "Lockscreen presentation"):** "`setShowWhenLocked(true)`, `setTurnScreenOn(true)`, `FLAG_KEEP_SCREEN_ON`, and request keyguard dismissal" is implemented literally, but the unconditional `requestDismissKeyguard` puts an authentication prompt in front of the Dismiss button on every secure device (1.2), so the alarm is not actually dismissable from the lockscreen. The spec line should be revisited along with the fix.
- [ ] **Deliverable only partly met (§2 "Dismiss ... auto-disables a one-shot"):** correct on the happy path, but lost if the process dies in the cached window after `stopSelf()` (1.9), never applied if the alarm is never explicitly dismissed (1.8), and applied to only one of two simultaneously-ringing alarms (1.7).

### Round 2 — 2026-10-09

**Verdict:** **1 new MAJOR, 7 new MINOR, 2 new NIT, 0 new BLOCKER.** Of the 11 Round 1 findings the
dev claimed: **8 CLOSED, 3 PARTIAL, 0 STILL OPEN.** All four Round 1 majors were addressed and three
are genuinely closed; the fourth (1.4) stops one step short. The fixes are good work — the volume
record, the keyguard removal, the schema baseline and the audio-thread move are all correct and
well-reasoned — but moving the audio onto a `HandlerThread` and deferring dismiss teardown behind a
database join added real concurrency to a module that had almost none, and that is where the new
findings cluster. One MAJOR gates advancement: the error-recovery chain walks the source index
forward and has no terminal retry, so an asynchronous `MediaPlayer` failure on the *last* source
leaves the alarm permanently silent — the same outcome 1.4 was raised to prevent, narrowed but not
eliminated.

**Disposition of Round 1 findings**

| # | Round 1 severity | Disposition | Evidence |
|---|---|---|---|
| 1.1 | MAJOR | **CLOSED** (residual MINOR 2.10) | AlarmRinger.kt:155-176. Walked the state machine. *First ring:* no record exists -> `commit()` the real volume -> max. *OOM kill + `START_REDELIVER_INTENT`:* the redelivered start reads a maxed stream but `contains(KEY_PRIOR_VOLUME)` is true (:156), so the record is kept and max is never mistaken for the user's choice — the exact defect, fixed. *Force-stop with no redelivery:* the record survives on disk and the next ring's dismiss restores it, so the volume is no longer rewritten with *no* recovery path. *DND `SecurityException` on restore:* `onSuccess` gates the `remove()` (:171), so a rejected restore keeps the record and the next dismiss retries — correct. *Two alarms back to back:* B's `start()` re-enters `forceMaxAlarmVolume`, finds the record, keeps it; only the single `stop()` restores. The record is written with `commit()` on `createDeviceProtectedStorageContext()` **before** `setStreamVolume` (:134-135), so the ordering requirement holds. No path restores to max. The one residual: a stale record can restore a *pre-previous* value if the user changed the volume between a failed restore and the next ring, and nothing restores outside the ring path — see 2.10. |
| 1.2 | MAJOR | **CLOSED** | `requestDismissKeyguard`/`KeyguardManager` appear nowhere in `app/src/main` except the AlarmRingActivity.kt:85 KDoc that forbids re-adding them. `showOverLockscreen()` (AlarmRingActivity.kt:92-104) is now `setShowWhenLocked(true)` + `setTurnScreenOn(true)` with the API-26 window-flag fallback, plus `FLAG_KEEP_SCREEN_ON`; `android:showWhenLocked="true"` and `android:turnScreenOn="true"` are also declared (AndroidManifest.xml:71,74) so the window manager knows before `onCreate`. The §2 spec bullet was amended to match. The Dismiss button is reachable on a secure device without authentication. |
| 1.3 | MAJOR | **CLOSED** for data loss (claim overstated — MINOR 2.6) | `fallbackToDestructiveMigration` is gone; `exportSchema = true` (AlarmDatabase.kt:40) with `addMigrations(*MIGRATIONS)` (:75) and an empty-but-documented `MIGRATIONS` array (:50). `room.schemaLocation` = `$projectDir/schemas` (app/build.gradle.kts:38), so the baseline lands at `app/schemas/com.mathalarm.data.AlarmDatabase/1.json` — **outside `build/`** and **not gitignored** (.gitignore matches only `build/`, `app/build/`, `.gradle/`). I diffed the committed JSON against the entity: 9 fields, `id INTEGER PK AUTOINCREMENT NOT NULL`, `hour`/`minute`/`enabled`/`repeatDaysMask`/`questionCount`/`vibrate` INTEGER NOT NULL, `label`/`difficulty` TEXT NOT NULL, identityHash `009b4ed9…` — an exact match for Alarm.kt:22-31 and the `DifficultyConverter` TEXT mapping. A Module 3 column addition can no longer wipe the table. It will **not**, however, fail at build time — see 2.6. |
| 1.4 | MAJOR | **PARTIAL** — residual MAJOR 2.1 | Closed: `setOnErrorListener` is installed per player (AlarmRinger.kt:227-230); `isRinging` is now `audioAlive \|\| startPending` (:86), so a dead player no longer reports as ringing and a redelivered `ACTION_START` can restart it. I traced the chain to exhaustion: it **terminates** (the index advances monotonically, bounded by `sources.size`, max 3 attempts — no infinite retry) and it **cannot be re-entered concurrently** (both the error callback and the recovery post land on the single `HandlerThread` looper, `player !== failed` discards stale callbacks at :273, and `releasePlayer` nulls the listener before releasing at :288 so a released player cannot call back). Neither flag sticks: every exit from `startPlaybackFrom` clears `startPending` (:217, :245, :253) and `audioAlive` is set **before** `startPending` is cleared (:244-245) so `isRinging` has no false-low gap on the success path. **Open:** `startPlaybackFrom(failedIndex + 1)` has no terminal case — an async error on the final source exhausts the chain and the alarm stays silent forever. See 2.1. |
| 1.5 | MINOR | **CLOSED** — and the Round 1 prescription for Module 3 is **withdrawn** (see "Pushbacks") | The misleading KDoc is gone: AlarmService.kt:234-245 now states correctly that the full-screen intent is the *only* path to the UI, that `AlarmManagerService` grants no BAL privilege, that the launch is silently dropped rather than throwing, and that the direct `startActivity` is kept as a fast path for the visible-window case. `warnIfNoUiPath` (:259-279) checks `areNotificationsEnabled()` and, on API 34+, `canUseFullScreenIntent()`, and logs at error level with the alarm id. Detection + loud log is the right call; the dev's refusal to hard-gate scheduling is correct and Round 1's guidance was wrong. |
| 1.6 | MINOR | **PARTIAL** — residual MINOR 2.2 | The silence guard is correct and I could not misclassify a *correct* dismiss as stale: `ringingAlarmId` is always the most recent start (AlarmService.kt:95) and the activity's `alarmId` is refreshed by `onNewIntent` (AlarmRingActivity.kt:69-73), so they agree; `START_REDELIVER_INTENT` redelivery re-runs `handleStart` and re-asserts `ringingAlarmId` before any dismiss can arrive, and the changed `startId` is irrelevant to the stale test. **No BLOCKER: there is no path where the alarm keeps ringing and the user cannot stop it.** But `stopSelf(startId)` at :175 runs unconditionally, including on the stale path where the dismiss carries the *newest* `startId` — which stops the service and silences the alarm that is actually ringing. 1.6's own harm, through the teardown path instead of the silence path. See 2.2. |
| 1.9 | MINOR | **CLOSED** (race noted as 2.3) | AlarmService.kt:126-176. The sound stops immediately (`silenceRinger`, :159-163, which no longer touches the wake lock or the notification), and the foreground notification + wake lock are held until the `disableIfOneShot` write joins, with a 10 s ceiling on the *join* rather than on the write (:148), so a slow write still completes. The `finally` at :151-154 is unconditional, and `AlarmWork.scope` is a process-lifetime `SupervisorJob + Dispatchers.IO` (AlarmWork.kt:13) that the service's death cannot cancel. The cached-process-mid-write window 1.9 described is gone. |
| 1.13 | MINOR | **CLOSED** (new main-thread IO as 2.5) | `setDataSource`/`prepare()` now run only inside `startPlaybackFrom`, which is reachable solely via `audioHandler.post` (AlarmRinger.kt:100, :279) on a private `HandlerThread` (:50-51). The service main thread no longer blocks on `MediaProvider`, so `ACTION_DISMISS` stays processable. A *different* blocking IO call was introduced on main by 1.1's fix — see 2.5. |
| 1.14 | MINOR | **CLOSED** (new structural defect as 2.7) | What 1.14 asked for is done, and done well: `start()` guards volume, focus and vibration individually (AlarmRinger.kt:96-105), and because the player now runs on another thread, an audio failure cannot reach `handleStart`'s catch at all. Only the genuinely load-bearing steps — `ensureChannel`, `startForeground`, `AlarmWakeLock.acquire`, `ringer.start()` — remain inside the try (AlarmService.kt:80-103). A nicety can no longer cancel the alarm. The restructuring introduced a new, different hazard on the other side of the catch: 2.7. |
| 1.15 | MINOR | **CLOSED** | `EXTRA_TRIGGER_AT` is baked in at schedule time (AlarmScheduler.kt:113), carried by the receiver (AlarmReceiver.kt:29,38) and consumed at AlarmService.kt:74. `rescheduleBaseline` (:223-230) is `max(triggerAt + 1min, now)` with a `triggerAtMillis <= 0` fallback to `now`, so a start intent without the extra behaves exactly as in Round 1. **No skip and no double-fire:** `trigger + 1min` is strictly after the just-consumed occurrence (alarms are minute-granular) and strictly before the next, because consecutive occurrences of a given hour/minute are at least 23 h apart even across spring-forward. Checked the tomorrow case explicitly — a 23:59 Monday-only repeat baselines to Tuesday 00:00 and `NextTrigger`'s 8-day lookahead (NextTrigger.kt:28,50) still reaches the following Monday. On a redelivered intent `now > trigger + 1min`, so the baseline falls back to `now`, which is still past the consumed occurrence; the reschedule is idempotent under `FLAG_UPDATE_CURRENT`. Round 1 "Verified correct" #2/#3 **re-verified from scratch** below. |
| 1.17 | MINOR | **PARTIAL** | AlarmDayMaskTest.kt adds 10 tests and they are the right ones: the bit-0-is-Monday convention pinned in both directions (:24-29), a per-day single-bit index check against `DayOfWeek.value - 1` (:32-38), an exhaustive `mask -> days -> mask` round trip over **all 128 subsets** (:56-61), Monday-first iteration order (:79-85), the zero/one-shot case (:90-95) and out-of-range bits degrading rather than crashing (:107-111). Two of the three gaps 1.17 named are still open: there is **no test anywhere** for `DifficultyConverter.fromName` with an unknown stored value (`grep` for `DifficultyConverter` in `app/src/test` returns nothing), and no test for `NextTrigger.next` with `now` exactly equal to a just-fired repeating trigger *across* a DST boundary — though the two DST tests do baseline off `saturday.plusMinutes(1)` (NextTriggerTest.kt:101,138), which incidentally exercises 1.15's `+1min` pattern across both transitions. |

**New findings**

| # | Severity | File:line | Finding | Why it matters |
|---|---|---|---|---|
| 2.1 | **MAJOR** | AlarmRinger.kt:279 (with :221, :252-254) | `onPlaybackError` always recovers by `startPlaybackFrom(failedIndex + 1)`. There is no terminal case: when the failing source is the last one, `for (index in fromIndex until sources.size)` has an empty range, the function logs "every audio source from index 3 failed; alarm is silent", clears both flags, and **nothing ever retries** — there is no watchdog and no caller polls `isRinging`. Concrete scenario: phone reboots at 03:00 and sits on the lock screen. At 07:00 the alarm fires in direct boot; the two `RingtoneManager` `content://` sources fail synchronously (pre-unlock, as Round 1 "Verified correct" #9 established) and playback succeeds on `R.raw.fallback_alarm` at index 2. The mediaserver then restarts — `MEDIA_ERROR_SERVER_DIED`, the canonical asynchronous `MediaPlayer` failure — and the listener posts `startPlaybackFrom(3)`. The alarm is silent from that instant until the user notices, while the foreground notification, the full-screen activity and (if `vibrate` is set) the vibration all continue to claim it is ringing. | This is the outcome 1.4 was raised to prevent, narrowed from "any async error" to "an async error on the final source" but not eliminated — and the final source is precisely the one the direct-boot path depends on. Falling *forward* is also the wrong recovery for `MEDIA_ERROR_SERVER_DIED`, which kills every player regardless of source and is cured by re-creating one once the server is back; the correct response there is to retry from index 0. Fix: when `failedIndex + 1 >= sources.size` and `ringRequested` is still true, re-post `startPlaybackFrom(0)` after a short delay under a bounded attempt counter (and clear `startPending` only when the attempts are exhausted). ~3 lines. |
| 2.2 | MINOR | AlarmService.kt:175 (with :127-132, :170-174) | `finishDismiss` correctly skips `stopForeground`/`AlarmWakeLock.release()` when a newer alarm is ringing, but then calls `stopSelf(startId)` unconditionally. On the stale path the dismiss is the *most recent* start, so `stopSelf` is not declined: the service is destroyed, `onDestroy` -> `stopAlarm()` (:179-185) shuts down the ringer, and the live alarm is silenced. Scenario: one-shot alarms A (07:00:00) and B (07:00:05). B's `handleStart` sets `ringingAlarmId = B`; in the few main-loop iterations before the activity's `onNewIntent` refreshes its `alarmId`, the user taps Dismiss, which delivers `ACTION_DISMISS` for A with a startId newer than B's. The stale guard correctly declines to `silenceRinger()`, then `stopSelf` silences B anyway. B's registration is already consumed and its row is still `enabled = true`, so B never rings, and `rescheduleAll` re-books it for tomorrow. | Exactly the harm 1.6 was filed for, re-entered through teardown rather than through `silenceRinger`. The window is only milliseconds wide, which is why this is MINOR rather than MAJOR, but the fix is to move `stopSelf(startId)` inside the `ringingAlarmId == NO_ALARM` branch — a stale dismiss should be a total no-op, including on teardown. |
| 2.3 | MINOR | AlarmService.kt:153 (with :170-174) | The deferred `finishDismiss` is posted from a **process-lifetime** coroutine but gates on the **instance** field `ringingAlarmId`, while the things it releases — `AlarmWakeLock` (an `object`) and `NOTIFICATION_ID` (a constant) — are process-global. Scenario: alarm A is dismissed, the auto-disable write is in flight; the system destroys the service under memory pressure (`onDestroy` -> `stopAlarm()`; `mainHandler.removeCallbacksAndMessages(null)` at :190 cannot remove a message the coroutine has not posted yet). Alarm B fires seconds later and the system creates a **new** `AlarmService` instance, which acquires the wake lock and posts B's notification. A's write then completes and the old instance's `finally` posts `finishDismiss`, which sees the *old* instance's `ringingAlarmId == NO_ALARM`, releases the process-global wake lock while B is ringing, and calls `stopForeground(STOP_FOREGROUND_REMOVE)` for a notification id that now belongs to B. | A deliberate change to Round 1 "Verified correct" #14 turned an immediate release into a deferred one, and the deferral carries stale instance state. The audio would likely survive on `MediaPlayer.setWakeMode`'s own untimed lock (see 1.10), but a vibrate-only alarm would not, and an FGS whose notification is pulled is at risk on Android 14+. Gate the release on process-global state (or have `handleStart` invalidate pending finishes) rather than on one instance's field. |
| 2.4 | MINOR | AlarmRinger.kt:216 (with :240-242) | `ringRequested` is checked once, at the top of `startPlaybackFrom`, and never again — in particular not after the blocking `mp.prepare()`, which is the exact call the `HandlerThread` was introduced for. Scenario: the user taps Dismiss while the audio thread is blocked in `prepare()` on a wedged `MediaProvider`. `stop()` sets `ringRequested = false`, clears the queue, posts `releasePlayer()`, abandons focus and **restores the alarm volume**, and the service tears down. When `prepare()` finally returns, the audio thread goes on to `routeToSpeaker` + `mp.start()` + `audioAlive = true` on an alarm nobody wants; the already-queued `releasePlayer()` runs immediately afterwards so the burst is sub-millisecond and probably inaudible, but the loop will also keep doing blocking `setDataSource`/`prepare()` IO on further sources the caller has already abandoned. | The field exists specifically so "a `stop()` that races a queued start or error-recovery can never bring the sound back up" (:56-57), and at the one point where the race is genuinely wide it is not consulted. Re-check `ringRequested` at the top of each loop iteration and once more between `prepare()` and `start()`. |
| 2.5 | MINOR | AlarmRinger.kt:160 and :171 | 1.1's fix put a synchronous `SharedPreferences.commit()` — a blocking fsync — back on the service main thread. `rememberVolumeBeforeRinging` is reached from `start()` via `forceMaxAlarmVolume()` (:96) **before** `audioHandler.post { startPlaybackFrom(0) }` at :100, so a slow commit delays the sound itself; `restoreAlarmVolume`'s `commit()` (:171) runs on main during dismiss. On a device with a loaded or heavily encrypted filesystem this is tens of milliseconds, occasionally more. | Round 1 raised 1.13 precisely to get blocking IO off the thread that must process `ACTION_DISMISS`, and this reintroduces some of it on the same thread, in the start path's critical section. The ordering requirement ("on disk before `setStreamVolume` runs") is real and `commit()` is the right primitive — but it belongs on the audio thread ahead of the volume change, not on main. |
| 2.6 | MINOR | AlarmDatabase.kt:33-34 | The KDoc asserts that a version bump with no matching migration fails "at build time, Room's annotation processor errors out when the exported schema and the new entities disagree and no migration covers the gap". That is not how Room works: `addMigrations(...)` is a runtime builder call the processor cannot see, so Room validates manual migration coverage **only at runtime**. Only `@AutoMigration(from = 1, to = 2)` is checked at build time, and it is not used here. Scenario: Module 3 adds a `repeatDays` editor column and bumps `version = 2`; `assembleDebug` is green, the APK ships, and on first launch after update `AlarmRescheduler.rescheduleAll` throws `IllegalStateException("A migration from 1 to 2 was required but not found")` inside `BootReceiver`'s catch (BootReceiver.kt:39-40) — logged and swallowed — so **every alarm silently stops being rescheduled** while the data sits intact on disk. | The MAJOR is genuinely closed: the alarms are no longer destroyed. But the stated guardrail does not exist, and the next dev will trust it. Either add `@AutoMigration(from = 1, to = 2)` in the same commit as any version bump (which does fail the build when it cannot infer the change), or correct the KDoc to say the gate is runtime-only and make Module 3's checklist require the migration. |
| 2.7 | MINOR | AlarmService.kt:107 (with :209-230) | 1.14's fix moved three calls outside the try whose catch cancels the alarm. Two are internally guarded (`warnIfNoUiPath` wraps its whole body in `runCatching` at :260; `launchRingActivity` wraps the `startActivity` at :248). `rescheduleIfRepeating` is not: its synchronous prologue — `applicationContext`, `rescheduleBaseline(triggerAtMillis)` with `Instant.ofEpochMilli(...).atZone(...).plusMinutes(1)`, and `AlarmWork.scope.launch` — runs unguarded on the main thread, and the `runCatching` at :214 only covers the coroutine body. A throw there escapes `onStartCommand`, and because `ACTION_START` returns `START_REDELIVER_INTENT` the same intent is redelivered into the same throw — a crash loop instead of Round 1's graceful `stopAlarm(); stopSelf()`. | As with 1.14 itself, **I found no live throw**: a trigger epoch from our own `AlarmScheduler` cannot overflow `plusMinutes`, and `launch` on a `SupervisorJob` scope does not throw. The structural point is the same one 1.14 made, now with a worse failure mode — a nicety that previously degraded the alarm would now kill the process repeatedly. Wrap the call in `runCatching`, or move the prologue inside the coroutine. |
| 2.8 | NIT | AlarmWakeLock.kt:16-21 | The documented release paths are now wrong. Path 1 says "Normal dismiss -> `AlarmService.stopAlarm()` -> release", but the normal dismiss has gone through `finishDismiss` since 1.9's fix and `stopAlarm()` is no longer on that path. The KDoc also omits the new up-to-10 s deferral and still does not state the `MediaPlayer.setWakeMode` dependency that 1.10 identified as load-bearing past the 10-minute ceiling. | This is the one file whose contract the review has twice had to reconstruct by hand; stale comments on it are expensive. |
| 2.9 | NIT | AlarmRinger.kt:30 | The threading KDoc says "`[start]`, `[stop]` and `[shutdown]` are called from the service's main thread", but `stop()` is `private` (:109) and reachable only through `shutdown()`. | Implies a public lever that does not exist. |
| 2.10 | MINOR | AlarmRinger.kt:164 (with :118) | `restoreAlarmVolume()` is called only from `stop()`, so nothing restores the alarm stream outside a ring/dismiss cycle, and the record cannot distinguish "the previous ring never restored" from "the user changed the volume since". Two scenarios: (a) the user force-stops the app to silence a stuck alarm — a very common reaction — and the alarm stream stays at maximum until the *next* alarm is dismissed, up to 24 h for a daily alarm; (b) ring 1 records volume 3, the restore is rejected by a DND policy, the user manually sets the stream to 7, ring 2 keeps the stale record and the dismiss restores 3 rather than 7. | Both are strictly better than Round 1's "permanently maxed with no recovery path", which is why 1.1 is closed. Cheap backlog fix: call `restoreAlarmVolume()` opportunistically on `BOOT_COMPLETED` and on app launch, where a surviving record can only mean a ring that never cleaned up. |

**Pushbacks — both upheld**

1. **"Hard-gating Module 3's scheduling on `canUseFullScreenIntent()` and notifications-enabled is wrong." The dev is right, and Round 1's prescription in finding 1.5 is withdrawn.** The reasoning is sound and I verified the premise: the sound does not depend on the notification at all. `AlarmRinger` plays `USAGE_ALARM` + `CONTENT_TYPE_SONIFICATION` on `STREAM_ALARM` (AlarmRinger.kt:73-76), owned by the service, and a denied `POST_NOTIFICATIONS` does not prevent `startForeground` from promoting the service — the notification is merely suppressed. So with notifications off or FSI denied the alarm still **rings audibly through DND**; what is lost is the UI, not the noise. Refusing to schedule would convert a degraded-but-audible alarm into no alarm at all, which is the silent-failure class this ledger exists to prevent, and it would do so on exactly the devices whose owners are least likely to notice. Detection + loud log (AlarmService.kt:259-279) is the correct Module 1 scope. **Amended guidance for Module 3 (supersedes the last sentence of finding 1.5):** do **not** gate scheduling on either capability. Instead (a) surface both states in the §6 self-diagnostic screen as red rows with a deep link to the relevant Settings page, (b) warn at alarm-save time and at app open if either is denied, naming the consequence ("the alarm will ring but you may have to stop it from Settings"), and (c) schedule regardless. A `Log.e` is invisible to users, so the diagnostics surface is the required part — track it with open minor 1.11, which needs the same treatment for the exact-alarm denial.
2. **"'The `runCatching` catches nothing' was overstated." Upheld.** When the app has a visible, focused window — the user is in the app when the alarm fires, which is the normal case for the Module 1 debug trigger — the app is foreground, background-activity-launch restrictions do not apply, and the direct `startActivity` at AlarmService.kt:248 really does bring up the alarm UI immediately instead of waiting on the notification. Round 1's "catches nothing" was accurate about the `runCatching` (a BAL-blocked launch is dropped, not thrown) but "no actual fallback / dead code" overstated it: it is a fast path plus a no-op, not dead code. The rewritten KDoc at :234-245 says exactly that and is now correct. Keeping the `runCatching` is still right, since `startActivity` can throw in principle.

**Verified correct (Round 2)** — re-confirmed after the dev modified them. Do not "fix" or revert these.

1. **`cancel()` still finds and cancels the real registration, with `EXTRA_TRIGGER_AT` added (AlarmScheduler.kt:72-81 vs :96-116).** Re-verified from scratch. Both sides build their lookup intent from the same `fireIntent(alarmId)` (:122-127), which sets action, the per-alarm `mathalarm://alarm/$alarmId` data URI and the component; `firePendingIntent` only adds **extras** on top. `PendingIntent` identity is `Intent.filterEquals` plus the request code, and `filterEquals` ignores extras entirely, so the new `EXTRA_TRIGGER_AT` cannot affect the match. `FLAG_NO_CREATE` is stripped from the lookup key, both sites pass `FLAG_IMMUTABLE`, neither passes `FLAG_ONE_SHOT`. Round 1 "Verified correct" #2 stands unchanged.
2. **Request codes still cannot collide, and `FLAG_UPDATE_CURRENT` now carries the new extra correctly (AlarmScheduler.kt:56, :140).** `requestCode(alarmId) = alarmId.toInt()` plus the per-alarm data URI keeps two alarms `filterEquals`-distinct. `FLAG_UPDATE_CURRENT` is what makes a re-schedule overwrite the stale `EXTRA_TRIGGER_AT` with the new trigger instant rather than firing with an old one — required for 1.15's fix to work, and correct here. `AlarmRescheduler` additionally does `cancel()` then `schedule()` (AlarmRescheduler.kt:27-28), which destroys the old `PendingIntent` outright. Round 1 "Verified correct" #3 stands.
3. **`rescheduleBaseline()` cannot skip or double-fire.** See the 1.15 row: checked for a daily repeat, a single-day repeat whose next occurrence is a week away, a 23:59 alarm whose `+1min` baseline crosses midnight into a non-matching day, a redelivered intent hours late, and both DST directions. The 8-day lookahead in `NextTrigger.next` (:28, :50) covers the midnight-crossing case.
4. **Wake-lock accounting, re-traced path by path.** `AlarmWakeLock` is a single non-reference-counted lock, `acquire` is a no-op while held (AlarmWakeLock.kt:35) and `release` nulls the field under `@Synchronized` and re-checks `isHeld` (:48-53), so double-release is impossible. (a) *Normal dismiss:* `silenceRinger` no longer releases; `finishDismiss` releases once when `ringingAlarmId == NO_ALARM`, then `onDestroy` -> `stopAlarm` is a no-op. **Exactly once.** (b) *`alarmId == NO_ALARM` dismiss:* `finishDismiss` runs synchronously (AlarmService.kt:134-137) — once. (c) *Stale dismiss:* deliberately does not release, because the newer alarm needs it — correct (but see 2.2 and 2.3). (d) *Start failure / `handleStart` catch:* `stopAlarm()` releases, then `stopSelf(startId)` — once. (e) *`onDestroy`:* `stopAlarm()` releases — once. (f) *Receiver cannot start the service:* `AlarmReceiver.kt:40-43` catches `Throwable` and releases, still the only path where nothing else would — unchanged from Round 1 #14. (g) *Crash / hard kill:* dies with the process, plus the `acquire(TIMEOUT_MS)` 10-minute ceiling. (h) *New 10 s timeout path:* `withTimeoutOrNull` returns null, the `finally` still posts `finishDismiss`, which releases — once. (i) *Two overlapping alarms:* B's `acquire` is a no-op, one lock object, released once by whichever dismiss sees `ringingAlarmId == NO_ALARM`. (j) *Unexpected action:* `stopAlarm()` releases — once. **No path leaks it.** The lock is now held up to 10 s longer than before on every dismiss, which is intended by 1.9's fix and well inside the 10-minute ceiling. One structural caveat worth knowing: the 10 s ceiling bounds the *write*, not the dispatch of the coroutine that implements the ceiling, so a fully starved `Dispatchers.IO` would hold the lock to the 10-minute ceiling — not reachable with this app's handful of IO coroutines.
5. **The `HandlerThread` audio design is sound, and `@Volatile` is sufficient.** Field ownership is clean and I checked every access: `player` is audio-thread-only (`startPlaybackFrom`, `onPlaybackError`, `releasePlayer`); `focusRequest` and `vibrator` are main-thread-only (`start`/`stop` only); only the three flags cross threads and all three are `@Volatile`. No real lock is needed, because the single looper serializes all player work and **every** entry to `startPlaybackFrom` begins with `releasePlayer()` (:215) — so even two concurrently queued starts (a duplicate `start()` racing an error recovery) end with exactly one live player rather than a leak. `stop()` clears the queue before posting `releasePlayer` (:113-114), and `shutdown()` posts `quitSafely` *after* that release so the player cannot outlive the looper. The only gap is the missing `ringRequested` re-check after `prepare()` (2.4).
6. **`isRinging` cannot stick `true` or `false`.** Every exit from `startPlaybackFrom` clears `startPending`; `audioAlive` is set before `startPending` is cleared on success, so there is no false-low window while playback is coming up. `onPlaybackError` does read false transiently between `audioAlive = false` (:268) and `startPending = true` (:278), and a duplicate `handleStart` landing in that window would call `start()` again — which is **harmless**, because the second `startPlaybackFrom` releases the first player before creating its own, `rememberVolumeBeforeRinging` keeps the existing record, and `requestAudioFocus` abandons before re-requesting (:184). No duplicate player, no leaked focus request, no corrupted volume record.
7. **The committed schema baseline is real and in the right place.** `app/schemas/com.mathalarm.data.AlarmDatabase/1.json` is outside `build/`, not gitignored, and byte-for-byte consistent with `Alarm` (verified field by field). Do not delete it, do not move `room.schemaLocation` into a build directory, and do not add `fallbackToDestructiveMigration` back.
8. **The structural rule still holds after the refactor.** `AlarmRinger` is still `internal` and constructed only at AlarmService.kt:96; the new `HandlerThread` lives inside it and is not reachable from the UI layer; `AlarmRingActivity` still imports no media, vibrator or power API and its only lever is `ACTION_DISMISS` (AlarmRingActivity.kt:111). Killing the activity still cannot silence the alarm.

**Acceptance criteria audit (Round 2 — changes only)**
- [x] **Lockscreen presentation deliverable now genuinely met.** The §2 bullet was amended to forbid `requestDismissKeyguard` and the code matches it; the Dismiss button is reachable on a secure device without authentication. Previously the only failing criterion; now closed.
- [x] Wake lock acquired and released on every exit path, including crash — re-traced all ten paths above after the dev's deliberate change to the accounting. Still met, with 2.3 as a low-reachability caveat rather than a leak.
- [x] Unit tests for next-trigger computation, including a DST boundary — unchanged and still green; 27 tests total (17 + 10 new day-mask).
- [ ] **Dismiss ... auto-disables a one-shot — still only partly met**, but now only on open **MINORs**: durability is fixed (1.9), while a one-shot is still never disabled if the alarm is never explicitly dismissed (1.8) and only one of two simultaneously-ringing alarms is disabled (1.7), with 2.2 adding a third narrow route to the same loss. These do not gate.

**DO NOT ADVANCE.** One MAJOR is open (2.1), so the §1 bar — zero BLOCKER and zero MAJOR — is not met.

**Round 3 is a one-item round. Do exactly this and nothing else:** give the error-recovery chain a terminal
case. In `AlarmRinger.onPlaybackError`, when `failedIndex + 1 >= candidateSources().size` and
`ringRequested` is still true, re-post `startPlaybackFrom(0)` after a short delay (1-2 s) under a
bounded attempt counter — a field reset in `start()` and incremented per full pass, giving up after
a handful of passes — and leave `startPending` true across the retry so `isRinging` keeps reporting
the alarm as live while recovery is in progress. Treat `MEDIA_ERROR_SERVER_DIED` as a from-zero
retry rather than a fall-forward, since it is not source-specific. Do not touch anything else:
2.2-2.10 and the Round 1 minors (1.7, 1.8, 1.10, 1.11, 1.12, 1.16, 1.17's two residuals) and nits
(1.18-1.22, 2.8, 2.9) are backlog and must not be fixed in this round. `assembleDebug` and
`testDebugUnitTest` must stay green.

### Round 3 — 2026-10-09

**Verdict: 2.1 CLOSED. 0 open BLOCKER, 0 open MAJOR. 2 new MINOR, 3 new NIT. Module 1 ADVANCES.**
The one-item round did the one item, and did it properly: the fix is the terminal case 2.1 asked
for, the policy was lifted into a pure function so the branch that is unreachable on a JVM and
unreproducible by hand is now actually walked by tests, and the dev's one scope extension is the
same defect wearing a different hat rather than creep. The five new findings are all non-gating —
one millisecond-wide race whose worst outcome is a sub-second audible gap during an already-failing
recovery, one accepted-by-design terminal state, and three documentation/test-structure nits. None
of them is a reliability hole, and none is used to hold the module.

---

**2.1 — MAJOR — CLOSED.**

I traced the headline scenario end to end against the code rather than the commit message.

*Direct boot, 07:00, both `content://` sources fail synchronously.* `startPlaybackFrom(0)`
(AlarmRinger.kt:227) sweeps index 0 and 1 — `setDataSource` throws pre-unlock, each player is
released in the catch at :262 — and lands on `Source.FromRaw(R.raw.fallback_alarm)` at index 2:
`prepare()`/`start()` succeed, `player = mp`, `audioAlive = true`, `startPending = false` (:256-258).
Ringing.

*`MEDIA_ERROR_SERVER_DIED` arrives.* The listener installed at :240 captures the loop's `index`, so
`onPlaybackError(failed, 2, 100, extra)` runs **on the audio looper** (the player was constructed at
:236 on that thread, and `MediaPlayer` delivers callbacks on the creating looper). `audioAlive =
false` (:301); `ringRequested` is true; `player === failed`; `releasePlayer()` at :312 drops the dead
player *and its listener* before anything is queued. `PlaybackRecovery.plan(2, 3, 100, 0)`
(PlaybackRecovery.kt:63): `what == MEDIA_ERROR_SERVER_DIED` so the fall-forward branch is skipped,
`0 >= 3` is false, so `Restart(0, 1500)`. `fromIndex == 0` so `restartPasses` becomes 1 (:320),
`startPending = true` (:321), one `postDelayed` (:324).

*1.5 s later.* `startPlaybackFrom(0)` re-sweeps. A mediaserver restart normally completes well
inside a second, so the raw tone prepares and **the alarm rings again**. This is the exact path that
previously produced `startPlaybackFrom(3)`, an empty `for (index in 3 until 3)` range, and permanent
silence. The regression is dead.

*If the mediaserver is still down at 1.5 s* — the follow-on case that made the scope extension
necessary — all three sources throw synchronously, and the new branch at :272-277 spends the next
pass instead of giving up. Retries land at ~1.5 s, ~3.0 s, ~4.5 s; on the fourth exhaustion
`restartPasses == 3`, so `startPending = false` and the ringer reports honestly.

**Remaining permanent-silence paths: none that the finding or the spec left open.** After the budget
is spent the alarm *is* silent while the notification, activity and vibration still claim otherwise —
but that is precisely the behaviour 2.1 prescribed ("clear `startPending` only when the attempts are
exhausted"), it is logged at error level in those words (:330-334), and no watchdog was requested.
I am not reopening my own prescription. Logged forward as backlog **3.2** instead.

**`plan()` is total.** Three `return`s, the last unconditional — there is no fall-off-the-end for any
`(failedIndex, sourceCount, what, restartPassesUsed)`. `sourceCount == 1`: `next = failedIndex + 1`
is never `< 1`, so every error wraps around; pinned by
`PlaybackRecoveryTest.kt:50` (`a single-source chain still wraps around`). `sourceCount == 0`:
same arithmetic, `Restart(0, 1500)`, and `startPlaybackFrom(0)`'s empty range falls into the bounded
synchronous branch — still terminates. (`candidateSources()` always appends the raw tone at
AlarmRinger.kt:355, so 0 is unreachable in practice; it is simply not a hole.) Negative
`failedIndex` is unreachable: it is always a `for (index in fromIndex until size)` value and
`fromIndex` is only ever `0` or a `plan.fromIndex`, both `>= 0`.

**The `if (plan.fromIndex == 0) restartPasses++` wrap-around test is correct — the dev's argument
holds.** A fall-forward is the *only* other `Restart` producer, and it returns `Restart(next, 0L)`
with `next = failedIndex + 1 >= 1`. So `fromIndex == 0` in a `Restart` can only have come from the
third `return`, i.e. the wrap-around. Verified, and verified for the case the dev raised: **source 0
itself repeatedly dying**. With `MEDIA_ERROR_SERVER_DIED` on index 0, `plan(0, 3, 100, p)` skips the
fall-forward branch and returns `Restart(0, 1500)` every time, so the increment fires every time and
the chain reaches `GiveUp` after three passes. A naive `failedIndex != 0` guard would have declined
to count exactly those passes and spun forever at 1.5 s per lap for the life of the alarm — the dev
is right, and the comment at :317-319 states the reason correctly.

**`restartPasses` cannot be reset mid-ring or carry over stale.** The only reset is AlarmRinger.kt:111,
*inside* `start()`'s posted lambda, and `start()` is reachable only from `AlarmService.handleStart`
(AlarmService.kt:96) — i.e. only from a fresh `ACTION_START` delivery, which is exactly when a fresh
budget is wanted. There is no self-sustaining reset loop: `handleStart` suppresses a duplicate start
while `isRinging` is true (AlarmService.kt:91), and `START_REDELIVER_INTENT` redelivers once after a
kill, not in a cycle. Carry-over is impossible in the other direction too, because the reset is
posted *ahead of* `startPlaybackFrom(0)` on the same looper, so pass 1 of a new ring always starts
from 0 — and a ringer that has been `shutdown()` is never reused (`silenceRinger`/`stopAlarm` null
the field, AlarmService.kt:101-102, :121-122).

**Work is bounded and the queue holds at most one pending recovery.** Within a pass the index
strictly advances (`Restart(failedIndex + 1, 0L)`, and `startPlaybackFrom`'s own loop only moves
forward), so a pass costs at most `sourceCount` attempts; `restartPasses` increments exactly once per
wrap-around, from the async path at :320 and the synchronous path at :273, and both read the same
audio-thread-confined counter, so the two paths **share** one budget rather than each getting their
own. Total: one initial pass plus at most `MAX_RESTART_PASSES` restarts, so at most
`(MAX_RESTART_PASSES + 1) * sourceCount` = 12 attempts over ~4.5 s. (The KDoc says
`MAX_RESTART_PASSES * sourceCount` — off by one pass; see nit **3.3**.) At most one recovery is in
flight: a `postDelayed` is only issued either by `onPlaybackError` — which requires a *live* player,
which means that invocation of `startPlaybackFrom` returned at :259 and therefore issued no
synchronous post — or by the synchronous exhausted branch, which returns immediately afterwards.
Both run on the single audio looper, so they cannot interleave. I found exactly one way to get two
messages queued at once, and it needs an external duplicate `start()`: finding **3.1**, below.

---

**Scope ruling on the synchronous all-sources-failed exit (AlarmRinger.kt:265-280): IN SCOPE, and
necessary. I agree with the dev, and with your reading of it.**

Three reasons, in descending order of weight.

1. **It is the same defect, one step along.** Without it, the 1.5 s retry that lands while the
   mediaserver is still restarting fails synchronously across all three sources, falls out of the
   loop, clears both flags and logs "alarm is silent" — permanent silence with two of three passes
   *unspent*. The fix would have closed the async door and left the synchronous one open two seconds
   behind it, and I would have reopened 2.1 next round against a module that had already used its
   last fix round. Leaving it would have been the worse outcome by a wide margin.
2. **2.1's own citation covers it.** The finding is filed as "AlarmRinger.kt:279 (with :221,
   **:252-254**)", and :252-254 was the synchronous exhausted exit in Round 2's numbering — Round 2's
   own 1.4 row names ":253" as one of the three `startPending` clear sites. The finding body also
   quotes the log line from *that* branch ("every audio source from index 3 failed; alarm is
   silent"). The dev read the citation correctly; this was inside the finding as written.
3. **It is cheap and it does not widen the blast radius.** Five lines, no new state, no new thread,
   no new constant: it reuses `PlaybackRecovery.MAX_RESTART_PASSES`/`RETRY_DELAY_MS` and the same
   `restartPasses` counter, so the two paths cannot each spend a full budget. The comment at :266-271
   explains the reasoning in place.

For the avoidance of doubt: had I ruled this creep, I would still have ruled it **correct** and said
so, because reverting correct code that closes a real silent-alarm hole is a bad trade. As it is,
no revert, no follow-up.

---

**New findings (Round 3)**

| # | Severity | File:line | Finding | Why it matters |
|---|---|---|---|---|
| 3.1 | MINOR | AlarmRinger.kt:110-113 (with :301, :321, :324) | `start()` posts into the audio queue without purging it, unlike `stop()` (:126). A duplicate `ACTION_START` can therefore land *behind* a pending 1.5 s recovery. The window is real but narrow: `handleStart` only falls through to `start()` when `isRinging` is false, and during recovery that is only true between `audioAlive = false` (:301) and `startPending = true` (:321) — a span that contains `releasePlayer()` (a blocking `stop()`/`release()`) and a `candidateSources()` `Settings` read, so milliseconds, not nanoseconds. Scenario: the mediaserver dies; the service is OOM-killed and `START_REDELIVER_INTENT` redelivers `ACTION_START` for the same alarm id in that window. `start()` sets the flags and posts `{restartPasses = 0; startPlaybackFrom(0)}`, due immediately; `onPlaybackError` then queues its retry, due in 1.5 s. The immediate post establishes a healthy player; 1.5 s later the stale retry runs, `releasePlayer()` (:228) kills that healthy player, and the chain is re-walked. | Self-healing and bounded: every entry to `startPlaybackFrom` releases first, so there is never a second live player, no leaked focus request and no corrupted volume record (`requestAudioFocus` abandons first at :197, `rememberVolumeBeforeRinging` keeps the existing record at :169); the budget was legitimately reset, so no premature `GiveUp`. The whole cost is one extra chain restart — a gap of a `setDataSource` + `prepare()` — in a ring that was already failing. MINOR, not MAJOR: no silence, no leak, no stuck flag. One-line fix: `audioHandler.removeCallbacksAndMessages(null)` in `start()` before the post, mirroring `stop()`. |
| 3.2 | MINOR | AlarmRinger.kt:326-335 | When the budget is spent, `isRinging` honestly goes false and the ringer logs that the alarm is silent while the notification still claims it rings — but **nothing observes it**. No caller polls `isRinging`; the foreground notification text, the full-screen activity and the vibration all continue unchanged. After ~4.5 s of failed recovery the user has a silent alarm and a UI that insists otherwise. | Explicitly the behaviour 2.1 prescribed and no watchdog was requested, so this is **not** a reopening and does not gate anything. But the honest flag currently has no consumer. Cheap backlog fix inside Module 1's own surface: on `GiveUp`, start the vibration even when `vibrate == false`, and/or have the service re-post the notification with a "the alarm sound failed" line. Note for whoever takes 1.10/1.11: this belongs with the diagnostics surface Round 2's pushback #1 made a Module 3 requirement. |
| 3.3 | NIT | PlaybackRecovery.kt:58-61 | The termination KDoc states the total attempt bound as `MAX_RESTART_PASSES * sourceCount`. It is `(MAX_RESTART_PASSES + 1) * sourceCount` — the initial pass is not a restart, so three restarts means four passes (12 attempts at `sourceCount == 3`, not 9). The termination *claim* is correct; only the arithmetic is off by one pass. | The test already quietly disagrees with the KDoc: `PlaybackRecoveryTest.kt:158` bounds its step count at `MAX_RESTART_PASSES * (count + 1) + 1`. A stated bound that the test does not believe is a bound nobody can rely on. |
| 3.4 | NIT | PlaybackRecoveryTest.kt:154 (against AlarmRinger.kt:320) | `the recovery chain always terminates` drives the real `PlaybackRecovery.plan`, but re-implements the host's bookkeeping: `if (restart.fromIndex == 0) passes++` is a hand copy of AlarmRinger.kt:320, not a call into it. The copy is faithful today — I diffed the two conditions — and the test does pin the two properties that matter (it terminates, and `passes == MAX_RESTART_PASSES` at the end, so it cannot pass by giving up early). But if the host's increment rule ever changes, the test keeps passing against the old rule. | The counter-advance is half the termination argument and it is the half that lives in untestable Android code. Moving it into `PlaybackRecovery` (e.g. `fun passesAfter(plan: Plan, used: Int): Int`, called from both :273 and :320) would put the whole policy behind the pure function and let the test drive the real rule. |
| 3.5 | NIT | AlarmRinger.kt:313 | `candidateSources()` is re-read inside `onPlaybackError` to get `sourceCount`, so it can return a different list — and a different size — than the one `failedIndex` indexes into. Realistic cause: `RingtoneManager.getDefaultUri(TYPE_ALARM)` returns null before the first unlock and non-null after it, so a mid-ring unlock *grows* the list and shifts every index by one; a ringtone deleted mid-ring *shrinks* it. I walked both directions: the list shrinking below `failedIndex` is harmless (`next < sourceCount` fails, the chain wraps around, which is the right answer anyway) and `startPlaybackFrom`'s `for (index in fromIndex until sources.size)` re-reads the list, so an out-of-range `fromIndex` yields an empty range and the bounded synchronous retry rather than an `IndexOutOfBoundsException`. The list growing can make one fall-forward target the wrong slot — retrying the source that just failed, or skipping the user's real alarm sound. | No silence, no crash, no unbounded loop: the mis-target costs one wasted attempt and the wrap-around reaches the skipped source within 1.5 s. Recording the list (or just its size) in a field at :233 and reading *that* at :313 would remove the ambiguity. Logged only because the next reader will wonder. |

---

**Verified correct (Round 3)** — the new retry code. Re-verified from the source, not from the
commit message. Do not "fix" or revert these.

1. **`PlaybackRecovery.plan` is total and genuinely Android-free.** Three returns, the last
   unconditional; no `(failedIndex, sourceCount, what, restartPassesUsed)` falls off the end,
   including `sourceCount` of 0 and 1. Zero imports, so the policy really is unit-testable on the
   JVM — which is the whole point, since the branch at issue is not reachable on a JVM and not
   reproducible by hand.
2. **The `fromIndex == 0` wrap-around test is sound, and it is what makes the chain finite when
   source 0 is the one dying.** A fall-forward always targets `failedIndex + 1 >= 1`; only the
   wrap-around branch can produce 0. Also checked the inverse mistake: the naive `failedIndex != 0`
   guard the dev rejected really would have spun forever on a repeatedly-dying source 0.
3. **Total work is bounded and the two retry paths share one budget.** At most
   `(MAX_RESTART_PASSES + 1) * sourceCount` attempts over ~4.5 s per ring. The async site (:320) and
   the synchronous site (:273) increment the same audio-thread counter, so neither gets a private
   budget. (Bound is off by one pass in the KDoc — nit 3.3 — but the bound exists.)
4. **At most one recovery is ever queued, and two recoveries cannot double-start playback.** Both
   `postDelayed` sites run on the single audio looper; the async site requires a live player, which
   means that invocation returned at :259 and issued no synchronous post. Even in the one
   two-messages case I did find (3.1, which needs an external duplicate `start()`), there is still
   never a second live player, because **every** entry to `startPlaybackFrom` begins with
   `releasePlayer()` (:228).
5. **The dev's `@Volatile`-is-unnecessary claim on `restartPasses` is correct. I checked every
   site.** Writes: :111 (inside `start()`'s posted lambda), :273, :320. Reads: :272, :313. The reset
   at :111 is the only one that could plausibly have been on the main thread, and it is deliberately
   inside the lambda rather than in `start()`'s body. `startPlaybackFrom` is reachable only from
   :112, :276 and :324 — all audio-looper — and `onPlaybackError` is a `MediaPlayer` callback for a
   player constructed at :236 on the audio thread, so it is delivered on that looper too. **No site
   touches the main thread**, so there is no cross-thread visibility requirement and the comment at
   :71-72 is accurate. Field ownership overall is still clean: `player` and `restartPasses`
   audio-thread-only, `focusRequest`/`vibrator` main-thread-only, and only the three genuinely
   cross-thread flags are `@Volatile`.
6. **`stop()` during the 1.5 s delay is covered twice over, and `startPending` cannot stick true.**
   `stop()` sets all three flags false on the main thread *before* touching the queue (:123-125), so
   `isRinging` is false the instant the caller asks for a stop; `removeCallbacksAndMessages(null)`
   (:126) purges the pending retry; and if a retry were somehow already running,
   `startPlaybackFrom`'s `if (!ringRequested) { startPending = false; return }` (:229-232) bails
   before creating a player. I also chased the one ordering that could strand `startPending == true`
   — a retry already executing when `stop()` lands, reaching :274 *after* `stop()` cleared the flag,
   with its `postDelayed` then discarded by `quitSafely` — and it is unobservable: `shutdown()` is
   only called from `silenceRinger`/`stopAlarm`, both of which null the `ringer` field immediately
   (AlarmService.kt:101-102, :121-122), so nothing can read `isRinging` on a shut-down ringer and
   `handleStart` builds a fresh one.
7. **`isRinging` cannot be stuck false mid-recovery, and the "duplicate start" direction is the
   benign one.** Across the delay `startPending` is deliberately held true (:274, :321), so a
   redelivered `ACTION_START` is correctly treated as a duplicate for at most 1.5 s *while a retry is
   genuinely queued* — not while the alarm is abandoned. On the synchronous path there is no false-low
   window at all (`startPending` was already true on entry and is re-set at :274). On the async path
   the pre-existing transient between :301 and :321 remains, and a start landing there now *starts*
   rather than being swallowed — the safe direction, costing at most the extra restart in 3.1. On
   `GiveUp` the flag is cleared on purpose (:329) so a redelivered start can restart with a fresh
   budget, and it can: the ringer has not been shut down and its thread is still alive.
8. **No `MediaPlayer` is leaked across the delay window, and `shutdown()` strands nothing.**
   `onPlaybackError` calls `releasePlayer()` at :312 *before* queueing, so no dead player (and no
   live listener) survives the delay; `releasePlayer` nulls the listener before releasing (:345) so a
   released player cannot call back; on the synchronous path each failed player is released in the
   catch (:262) and `player` is never assigned on a failure. `shutdown()` ordering is still correct:
   `stop()` purges the queue and posts `releasePlayer()`, then `quitSafely` is posted *after* it, so
   the release is already due when the looper drains and the player cannot outlive the thread.
9. **The constant-drift guard is real, and it is a compile-time guard — which is the right kind.**
   `PlaybackRecoveryTest.kt:25` compares against `android.media.MediaPlayer.MEDIA_ERROR_SERVER_DIED`
   itself. There is no Robolectric and no `unitTests.returnDefaultValues` in `app/build.gradle.kts`
   (only `junit:junit:4.13.2` at :55), so the only reason this test does not die on a stubbed
   android.jar is that the platform field is a `static final int` the Kotlin compiler inlines — i.e.
   the assertion pins the duplicate against the real SDK value at every recompile, and the 37 green
   tests confirm it is reading 100 rather than a 0 default. Genuine guard, not a tautology.
10. **The tests pin the regression rather than passing for the wrong reason.** `an error on the last
    source wraps around instead of falling off the end` (:44) is the exact 2.1 case and would fail
    against the Round 2 code, which returned `Restart(3, ...)`; `server-died retries from zero rather
    than falling forward` (:73) loops every index and would fail against the old fall-forward;
    `the direct-boot scenario recovers instead of going silent` (:91) is the ledger's own scenario
    with named arguments; `restarts are bounded and then give up` (:104) pins both edges of the
    budget, including `MAX_RESTART_PASSES + 7`; and `the recovery chain always terminates` (:144)
    walks `what` × `sourceCount` 1..5 and asserts `passes == MAX_RESTART_PASSES` at the end, so it
    cannot pass by giving up early. The one structural weakness is that it mirrors rather than calls
    the host's counter-advance — nit **3.4**, not a wrong-reason pass.
11. **The synchronous retry branch (:265-280) is correctly written, not just correctly motivated.**
    It reads the shared counter before incrementing, sets `startPending = true` so `isRinging` stays
    consistent with the async path, posts exactly one delayed retry from index 0, and `return`s —
    so the terminal log at :280 is reachable only with the budget spent. Its log text distinguishes
    "retrying the whole chain" from "recovery is exhausted", which is what a bug report off this
    device will need.

---

**Consolidated open backlog — Module 1** (carried forward for Module 2 and Module 3; **nothing here
gates Module 1**, and none of it was expected to be fixed in Round 3)

| # | Severity | Where | One-line summary |
|---|---|---|---|
| 1.7 | MINOR | AlarmService.kt:98-106, 234-240 | Overlapping one-shot alarms: only the dismissed `alarmId` reaches `disableIfOneShot`, so the other stays `enabled` and re-fires. |
| 1.8 | MINOR | AlarmService.kt:95-109 | A one-shot alarm is auto-disabled only on explicit dismiss, never at fire time — power-off/force-stop/OOM before Dismiss leaves the row enabled. |
| 1.10 | MINOR | AlarmWakeLock.kt:29 | The 10-minute `TIMEOUT_MS` drops the app's own wake lock at minute 11 of an unattended alarm and nothing re-acquires it; audio survives only on `MediaPlayer.setWakeMode`. |
| 1.11 | MINOR | AlarmScheduler.kt:51-55 | The exact-alarm denial path only logs. Needs the Module 3 diagnostics surface (see Round 2 pushback #1). |
| 1.12 | MINOR | AlarmRinger.kt:78-88 | `setStreamVolume` throws `SecurityException` under DND without notification-policy access; handled, but unsurfaced. |
| 1.16 | MINOR | AlarmRingActivity.kt:61-79 | `label` is a plain field, not Compose state, so `onNewIntent` does not refresh the displayed label. **Module 3 will touch this screen — fix it there.** |
| 1.17 | MINOR | test sources | Two residual gaps: no test for `DifficultyConverter.fromName` with an unknown stored value; no test for `NextTrigger.next` with `now` exactly on a just-fired repeating trigger across a DST boundary. |
| 2.2 | MINOR | AlarmService.kt:175 | `stopSelf(startId)` runs unconditionally on the stale-dismiss path, so a stale dismiss can still tear down the service and silence the alarm that *is* ringing. Fix: move it inside the `ringingAlarmId == NO_ALARM` branch. **Highest-value item on this list.** |
| 2.3 | MINOR | AlarmService.kt:153, :170-174 | The deferred `finishDismiss` is posted from a process-lifetime coroutine but gates on the *instance* field `ringingAlarmId`, so it can release the process-global wake lock and pull the notification out from under a newer alarm. |
| 2.4 | MINOR | AlarmRinger.kt:216, :240-242 | `ringRequested` is not re-checked after the blocking `prepare()` — the one point where the race is genuinely wide. (Deliberately left in Round 3 on instruction.) |
| 2.5 | MINOR | AlarmRinger.kt:160, :171 | Blocking `SharedPreferences.commit()` on the service main thread, in the start path's critical section. The ordering requirement is real; move it to the audio thread ahead of the volume change. |
| 2.6 | MINOR | AlarmDatabase.kt:33-34 | The KDoc claims a missing migration fails at build time. It does not — `addMigrations` is runtime-only. **Module 3 must add `@AutoMigration(from = 1, to = 2)` in the same commit as any version bump, or every alarm silently stops being rescheduled after update.** |
| 2.7 | MINOR | AlarmService.kt:107, :209-230 | `rescheduleIfRepeating`'s synchronous prologue runs unguarded outside the try; a throw there would crash-loop under `START_REDELIVER_INTENT`. No live throw found — structural. |
| 2.10 | MINOR | AlarmRinger.kt:164, :118 | Nothing restores the alarm stream outside a ring/dismiss cycle, and a stale record cannot be told from a user volume change. Fix: opportunistic `restoreAlarmVolume()` on `BOOT_COMPLETED` and app launch. |
| 3.1 | MINOR | AlarmRinger.kt:110-113 | `start()` does not purge the audio queue, so a duplicate start can leave a stale retry queued behind it; worst case one extra chain restart. One-line fix. |
| 3.2 | MINOR | AlarmRinger.kt:326-335 | Nothing consumes the honest `isRinging == false` after recovery is exhausted; the UI keeps claiming the alarm rings. Pairs with the Module 3 diagnostics surface. |
| 1.18-1.22 | NIT | various | Unreachable elvis on `createDeviceProtectedStorageContext()`; redundant `FLAG_ACTIVITY_CLEAR_TOP`; no-op `withEarlierOffsetAtOverlap()` in a DST test; missing launcher `android:icon` + misleading `allowBackup`; duplicated `-1L` sentinel. |
| 2.8 | NIT | AlarmWakeLock.kt:16-21 | Documented release paths are stale: the normal dismiss goes through `finishDismiss`, not `stopAlarm()`; the 10 s deferral and the `setWakeMode` dependency are undocumented. |
| 2.9 | NIT | AlarmRinger.kt:30 | Threading KDoc advertises `stop()` as externally callable; it is private and reachable only via `shutdown()`. (Deliberately left in Round 3 on instruction.) |
| 3.3 | NIT | PlaybackRecovery.kt:58-61 | Stated attempt bound is off by one pass: `(MAX_RESTART_PASSES + 1) * sourceCount`. |
| 3.4 | NIT | PlaybackRecoveryTest.kt:154 | The termination test mirrors AlarmRinger's wrap-around increment instead of calling it; move the counter-advance into `PlaybackRecovery`. |
| 3.5 | NIT | AlarmRinger.kt:313 | `candidateSources()` is re-read for the count, so `failedIndex` can mis-target by one slot if the list changes mid-ring. Harmless; snapshot the list at :233 instead. |

Still-valid "Verified correct" inventories: Round 1 #1-#15, Round 2 #1-#8, Round 3 #1-#11 above. A
later module that changes `AlarmScheduler` `PendingIntent` identity, the committed
`app/schemas/.../1.json`, the wake-lock accounting, the `HandlerThread` audio design or the
`isRinging` definition must re-earn those, not assume them.

---

**ADVANCE.** Zero open BLOCKER, zero open MAJOR. MAJOR 2.1 is closed on the evidence above, the
dev's scope extension was in scope and necessary, and the five Round 3 findings are two MINOR and
three NIT — logged as backlog, not used to hold the module. Module 1 is done; Module 2 (math gate)
is next. Two backlog items should be picked up by the module that is already in those files rather
than left to rot: **2.2** (one-line fix, the last way a dismiss can silence the wrong alarm) and
**2.6** (Module 3 must ship `@AutoMigration` with any schema bump). **1.16** belongs to whichever
module next edits `AlarmRingActivity` — which is Module 2.
---

## 3. Module 2 — Math gate

**Goal: replace the plain Dismiss button with an arithmetic challenge that
cannot be defeated by a half-asleep reflex.** Depends on Module 1.

### Deliverables

**`ProblemGenerator`** — pure Kotlin, no Android dependencies, fully unit-tested.

Tiers:
- **EASY** — `a + b` (a,b ∈ 2..9); `a - b` (a ∈ 5..18, b < a, result > 0)
- **MEDIUM** — two-digit addition **with a carry**; two-digit subtraction
  **with a borrow**; `a × b` (a,b ∈ 3..12)
- **HARD** — `ab × c`; two-step `a × b + c` / `a × b - c`; clean division
  generated backwards from a known product

Generator rules, all enforced and all unit-tested:
- Reject degenerate operands: no `×0`, `×1`, `×10`, `+0`, and no identical
  operands at EASY
- Answers are always **integers** and always **positive**
- Difficulty verified by **carry/borrow presence**, not digit count — `20 + 30`
  is not MEDIUM
- No repeated problem within one alarm session

**Challenge UI** — replaces the Dismiss button in `AlarmRingActivity`:
- Large numeric keypad, generous touch targets, backspace, explicit submit
- Answer graded on submit, not live, so typos are correctable
- Clock stays visible throughout
- Progress indicator when `questionCount > 1`, e.g. `2 / 3`
- Wrong answer → **prominent visual error state**, a brand-new problem, sound continues
  unbroken. A haptic buzz is **best-effort only** (review M2-1.9, platform-verified): it is
  delivered on `vibrate = false` alarms and silently dropped by the platform on
  `vibrate = true` ones, because `View.performHapticFeedback` is fixed at
  `VibrationAttributes.USAGE_TOUCH` and the vibrator service ignores a lower-importance
  vibration while the ringer's repeating `USAGE_ALARM` waveform plays. Raising the usage
  would require `ui/` to hold a `Vibrator` (forbidden). Emitting it from the service at
  `USAGE_ALARM` is *worse* — equal importance cancels the ringer's own waveform. **The
  visual error state is the required feedback.**
- No time limit, no skip, no auto-dismiss

**Escape hatch** — after **5 consecutive wrong answers** in one session, reveal a
bypass. Tapping it starts a **60-second countdown** with the alarm still at full
volume; the user must remain on screen. On completion the alarm dismisses. Leaving
or backgrounding resets the countdown. This is the **only** escape: no skip
button, no difficulty reduction, no typing alternative.

**Anti-cheat** — within the alarm activity:
- Back, home, and recents must not dismiss the alarm
- Volume keys must not silence it, intercepted in the alarm activity while it has window
  focus. **Service-side volume re-assertion is explicitly deferred, not required here.**
  A `ContentObserver` in `AlarmRinger` would fire on the ringer's own `setStreamVolume`
  calls and fight `restoreAlarmVolume()` on dismiss — the exact bug class review finding
  1.1 was raised about. **Residual hole, wider than first written (review M2-1.7):** the
  notification shade opens over any activity and takes window focus, and its slider targets
  `STREAM_ALARM` while the ringer plays — so this is open on a *fully permissioned* device,
  not only when notifications or `USE_FULL_SCREEN_INTENT` are denied. AVRCP and OEM mute keys
  bypass the window entirely. Diagnostics cannot close this: there is no permission state to
  report. Tracked as a **Module 3 implementation item** — see §4.
- Removing the task must not stop the audio
- Notification **cannot be severed** while ringing: a user swipe re-posts it with the
  full-screen, content and delete intents intact. (Review M2-2.7: literal
  non-dismissibility is impossible on API 34–36 — `setOngoing(true)` stops a swipe only to
  API 33 and no flag restores it. Not-severable is the strongest achievable property, and
  it is what preserves the route back to the keypad.)

### Acceptance criteria
- [ ] `./gradlew assembleDebug` exits 0
- [ ] `ProblemGenerator` has zero Android imports
- [ ] Unit tests cover every tier, every degenerate-operand rule, integer and
      positive guarantees, carry/borrow enforcement, and no-repeat
- [ ] Wrong answer generates a different problem — verified by test
- [ ] Escape hatch unreachable before 5 consecutive failures — verified by test
- [ ] No multiple-choice input anywhere
- [ ] Audio survives activity destruction

### Review Ledger — Module 2
*Review agents append rounds here. Do not edit by hand.*

### Round 1 — 2026-10-09

**Verdict:** 0 blockers, 1 major, 8 minors, 4 nits. The gate is sound where it matters most: I
traced every route to `AlarmService.dismissIntent` and there are exactly the two the dev claims, both
earned. Rotation, process death, `onNewIntent`, an empty entry, a zero or negative `questionCount`
and a rapid double Submit all fail *closed*. The generator was brute-forced against its own validator
over every reachable operand combination at all three tiers: no negative answer, no non-integer
division, no empty random range, no path that can hang or throw. The escape hatch's three locks are
all real and `consecutiveWrong` genuinely resets, so "consecutive" is honest, and the minute is a
full 60 000 ms with no off-by-one in either direction. The one MAJOR is not in Module 2's new logic
at all — it is the consequence of the dev's own third self-disclosure, which is bigger than the
disclosure admits: on API 34+ the user can swipe the foreground-service notification away, and that
notification is the **only** route back to the keypad once `onStop` has fired. The alarm then rings
with no reachable way to dismiss it.

| # | Severity | File:line | Finding | Why it matters |
|---|---|---|---|---|
| M2-1.1 | **MAJOR** | AlarmService.kt:321 (with AndroidManifest.xml:68, AlarmRingActivity.kt:326, MainActivity.kt:50) | **Swiping the notification away severs the only route back to the challenge UI.** `setOngoing(true)` stops a swipe only up to API 33; on API 34–36 (the whole target range) a foreground-service notification is user-dismissible on an unlocked device, and `startForeground` is called exactly once, in `handleStart` — nothing ever re-posts it. Meanwhile `AlarmRingActivity` is `excludeFromRecents="true"` with `taskAffinity=""`, so its task is invisible in recents, and `MainActivity` offers no way to reach it. Scenario: Android 14 phone, unlocked, alarm ringing, user is staring at a HARD × 3 keypad. They swipe the alarm notification out of the shade, then press Home (`onStop`). The service keeps ringing at full volume — correctly — but the keypad is now unreachable from recents, from the launcher and from the shade. There is no route back and therefore no way to solve the math, so there is no way to stop the sound short of Settings → Force stop or a reboot. The escape hatch cannot help either: it lives on the same unreachable screen. | This is the second-worst defect class for this module — *impossible to dismiss when the user is willing to solve it* — and it is reachable with two ordinary gestures on a fully-permissioned device. It also means §3's anti-cheat bullet "Notification is non-dismissible while ringing" is not met on any API level the app actually targets. Module 1 shipped the same structure, but it did not matter there: dismiss was one tap, so the user was never going to leave the screen and come back. Module 2 is the module that makes the user stay, so Module 2 is where the missing route back becomes a defect. Fix options, cheapest first: give the notification a `setDeleteIntent` that re-posts it (a new no-op service action calling `startForeground` again); or re-assert the notification from the service on a timer while `isRinging`; or re-launch the ring activity on delete. Do **not** fix it by dropping `excludeFromRecents` — that just moves the loss to a recents swipe. |
| M2-1.2 | MINOR | AlarmRingActivity.kt:309 (with :297) | `readChallenge` guards `questionText` (`?: return null`) but reads the answer as `saved.getInt(KEY_QUESTION_ANSWER)`, whose absent-key default is **0** — and `0` is a typable answer. Scenario: any future state restore in which the text key survives and the answer key does not (a hand-edited Bundle, a refactor that splits the writes, a third key list drifting from the other two) yields a session whose `questionAnswer` is 0; the user taps `0` once, `canSubmit` is true, `submit()` compares `0 == 0`, `solved` reaches `questionCount` and the alarm dismisses on a single keypress. Not reachable today, because `onSaveInstanceState` writes all eleven keys or returns early (:282) and a `Bundle` is never partially delivered. | The whole restore path is built so that a crafted or truncated `Bundle` cannot buy a dismissal, and this is the one field whose default *is* a valid winning answer. A sentinel that cannot be typed (`Int.MIN_VALUE`, or `saved.get…` + `?: return null` as the text already does) costs one line and removes the class. |
| M2-1.3 | MINOR | AlarmRingActivity.kt:193 | The dismiss predicate is `if (next.isSolved) dismiss()` — "the session is complete" — rather than "this submission completed the session". `submit()` on an empty entry returns `this` unchanged (ChallengeState.kt:100), and `this` is still `isSolved`, so the branch fires on a submission that solved nothing. Scenario: on the last question the user double-taps Submit within one frame. The first tap grades correctly and dismisses; the second re-enters `submitAnswer`, grades an empty entry to a no-op, sees `isSolved`, and sends a **second** `ACTION_DISMISS`. Harmless today (the service's `handleDismiss` is idempotent and Round 2 verified the wake-lock release cannot double-release), but the second dismiss is unearned by construction, and the same branch would hand a free dismissal to any restored or future state that arrives already `isSolved`. | The module's worst defect class is a dismissal that was not earned. The guard should be the thing the function already computes one line later — `next.solved > current.solved && next.isSolved` — so that "dismiss" is caused by *this* correct answer rather than by a property of the state. |
| M2-1.4 | MINOR | ProblemGenerator.kt:154 (with :116-117 and the KDoc at :151-153) | The HARD-division validator is wrong in both directions, and its own KDoc misstates it. (a) It claims "the quotient is itself held to the degenerate-factor rule so `96 ÷ 96` and `80 ÷ 8` can never appear" — but nothing checks the quotient. I verified by exhaustive evaluation: `Problem(DIVIDE, [96, 96], 1)` and `[12, 12] → 1` both **pass** `isWellFormed(.., HARD)`. `80 ÷ 8` is rejected, but by the dividend clause, not the quotient clause. (b) `isAllowedFactor(n[0])` is applied to the *dividend*, which is not a factor: that rejects 8 of the 72 constructible divisor/quotient pairs, including perfectly good problems like `20 ÷ 4 = 5`, `30 ÷ 6 = 5` and `60 ÷ 12 = 5`. Construction never produces a degenerate quotient today (`DIVISION_QUOTIENTS` starts at 4), so no user sees `96 ÷ 96`; the live cost is only that 11 % of division draws are silently thrown away and retried. | The validator is advertised as the independent second layer that stops a construction bug reaching the user (file KDoc :44-48). On the one tier where that promise is load-bearing it does not hold: widen `DIVISION_QUOTIENTS` to include 1 or 10 and `isWellFormed` waves it through, while the hard-coded `n[0] in 100..144` ceiling would start rejecting valid problems the moment `SMALL_FACTORS` goes past 12. Express the rule as the construction does — `isAllowedFactor(answer)` and `n[0] == n[1] * answer` — rather than as a magnitude window. Note the *test* at ProblemGeneratorTest.kt:202 already asserts `isAllowedFactor(quotient)`, so the test is currently stronger than the code it guards. |
| M2-1.5 | MINOR | ChallengeState.kt:155 vs AlarmRingActivity.kt:663 | There are two implementations of the countdown's ceiling arithmetic. `ChallengeState.escapeSecondsLeft` is **dead in production** — grep finds call sites only in `ChallengeStateTest` (:229, :233, :235, :249, :296) — while the number the user actually watches, and the comparison that calls `onElapsed`, come from the private `secondsUntil` in the activity, which no test touches. Scenario: a dev "fixes" the ceiling in `escapeSecondsLeft` (say to floor instead of ceil); all 25 `ChallengeStateTest` cases still pass and the displayed countdown is unchanged, or the inverse — someone edits `secondsUntil` to trigger at `<= 1000` and the suite stays green while the UI fires `onElapsed` a second early. | The four tests that read as "the countdown runs a full sixty seconds and not a millisecond less" pin a copy of the arithmetic the app never executes. The authority check (`isEscapeComplete`) is tested and is what actually gates the dismiss, so this cannot shorten the minute by itself — but the thing under test should be the thing that runs. Delete one copy; the composable can call the state's method. |
| M2-1.6 | MINOR | AlarmRingActivity.kt:164-178 | The tier read has no timeout and no retry. `runCatching` converts a *thrown* read into the MEDIUM × 1 fallback, but a read that never returns is not caught by anything: `challenge` stays `null`, the screen stays on "Getting your problem ready…", and because that branch (:429) composes no keypad, no Submit and no escape button, there is **no way to dismiss the alarm at all**. Scenario: the device-protected `AlarmDatabase` is opened under contention — e.g. a previous dismiss's auto-disable write (`AlarmWork.scope`, AlarmService.kt:140-ish) is still in flight on a slow eMMC during the post-boot I/O storm — and `getById` blocks far longer than the user's patience, or indefinitely on a locked database file. | Fail-closed is right for this window (see "Verified correct" #16), but "closed forever" is not. A `withTimeoutOrNull(5_000)` around the `withContext(Dispatchers.IO)` block makes the documented MEDIUM × 1 fallback guaranteed rather than conditional on the read terminating, which is the difference between a slow start and an alarm nobody can switch off. |
| M2-1.7 | MINOR | AlarmRingActivity.kt:254-262 (and §3's amended anti-cheat bullet) | The volume interception is correct but the **amended §3 text describes the residual hole with the wrong cause**, so it is wider than the amendment admits and the Module 3 routing cannot close it. §3 now says the residual applies "if the alarm UI does not have focus (notifications or `USE_FULL_SCREEN_INTENT` denied)". But the hole is also open on a fully-permissioned device with the challenge screen in front: the notification shade can be pulled down over any activity (hiding it needs signature-level `STATUS_BAR`), which takes window focus away, and the shade's volume row targets the active stream — `STREAM_ALARM` while the ringer is playing — so the user drags the alarm slider to the bottom and the alarm is effectively silent with the keypad still on screen. Bluetooth AVRCP absolute-volume commands and OEM hardware mute keys reach the system the same way, around the window. | Module 3's diagnostics screen can report a *permission* state; it cannot report "the user pulled down the shade", so the amendment's chosen home does not fit this cause. Silencing is not dismissing — the gate still stands, the row is not auto-disabled and the service keeps running — so this does not gate advancement, and the decision to keep a `ContentObserver` out of `AlarmRinger` was right (see the ruling below). But §3's residual sentence should be corrected to say *window focus can be taken away even when every permission is granted*, and the re-assertion should be tracked as a Module 3 implementation item, not as a diagnostics line. |
| M2-1.8 | MINOR | AlarmRingActivity.kt:132-146 | A second alarm arriving while the first is ringing re-reads the tier, so it can **downgrade** the gate mid-ring. `onNewIntent` resets the session whenever `alarmId != previous`, which is the right call on its own terms — carrying four free failures into a new alarm would be worse — but `startSession` then reads the *new* alarm's row. Scenario: alarm A is HARD × 3 and the user has solved two of three questions; alarm B (EASY × 1, the default for a quickly-created alarm) fires in the same minute; `onNewIntent` wipes A's session and opens an EASY × 1 one; a single easy sum now silences both alarms, because the dismiss carries B's id and the service's `handleDismiss` silences the one ringer. | The gate the user bargained for (three hard problems) is replaced by one easy one through nothing the user did. Root cause is the carried-forward Module 1 finding 1.7 — one activity and one ringer for two simultaneous alarms — and that is out of scope for this round, so this is logged as backlog rather than raised as a major. Worth recording so Module 3's alarm list, which is what makes two same-minute alarms easy to create, does not land on top of it unnoticed. |
| M2-1.9 | MINOR | AlarmRingActivity.kt:536 (with AlarmRinger.kt:381) | The wrong-answer haptic is likely to be **silently dropped on exactly the alarms that vibrate**. `LocalHapticFeedback` resolves to `View.performHapticFeedback(LONG_PRESS)`, which is requested at `VibrationAttributes.USAGE_TOUCH`; `AlarmRinger` is already running a repeating waveform at `USAGE_ALARM`. The vibrator service arbitrates concurrent requests by usage importance and ignores the lower-importance one while a higher-importance vibration is ongoing. Scenario: alarm with `vibrate = true`, user submits a wrong answer → the red error state and the new problem appear, but the buzz §3 asks for never reaches the motor. | §3 lists "wrong answer → haptic buzz" as a deliverable, and on the vibrate-enabled path it is probably not delivered. Not gating: the visual error state (:500-520) carries the same information, the alarm is already vibrating so the user is not short of tactile signal, and verification is code-review-only so this is a reasoned prediction rather than an observation. The important half is the inverse, and it checks out — see "Verified correct" #14: the touch-usage haptic cannot preempt or cancel the alarm's vibration. |
| M2-1.10 | NIT | ProblemGenerator.kt:183 | `generate`'s last-resort `verified ?: draw(difficulty, random)` returns a problem that has **not** been through `isWellFormed`, which is the one place in the file that hands out an unvalidated candidate. Unreachable: `verified` is null only if all 300 draws were malformed, and I brute-forced every construction path — only HARD division can produce a malformed candidate at all, and only for 8 of its 72 combinations. Returning `draw(...)` *also* re-draws rather than reusing a known-good candidate. |
| M2-1.11 | NIT | ProblemGenerator.kt:3, ChallengeState.kt:3 | Both `domain/` files import `com.mathalarm.data.Difficulty`, so the pure layer depends on the persistence package. `Difficulty` is a bare annotation-free enum (Alarm.kt:8) so the acceptance criterion "zero Android imports" is literally met and the JVM tests run clean, but the enum's natural home is `domain/`, with `data/` importing it rather than the reverse. |
| M2-1.12 | NIT | ChallengeState.kt:36, :123 | `asked` is a `List<String>` and `withNextProblem` rebuilds `asked.toSet()` on every single draw, so the no-repeat memory is O(n) per problem and the list permits a duplicate the invariant forbids. A `Set` (or `LinkedHashSet`) as the field type would both document the invariant and drop the per-draw copy. Irrelevant at the real sizes (tens to low hundreds of entries). |
| M2-1.13 | NIT | AlarmRingActivity.kt:190 | `submitAnswer` returns `true` — "the answer was right" — when `challenge` is `null`, purely so the caller does not buzz. Unreachable, because the Submit button is composed only inside the `state != null` branch, but a sentinel that reads as "correct" for "there was nothing to grade" is the wrong default in a file where "correct" is the thing that ends the alarm. |

**Verified correct** — these look wrong, risky or too clever and are not. Do not "fix" or simplify them.

1. **MEDIUM subtraction really does stay positive after the borrow (ProblemGenerator.kt:244-252).** I
   brute-forced all 1 620 reachable `(unitsA, unitsB, tensA, tensB)` combinations: the minimum result
   is **1** (e.g. `20 - 19`), never 0 and never negative; both operands are always two-digit; the
   borrow is always present. The dependency the dev wrote — `tensA ∈ 2..9`, `tensB ∈ 1..(tensA-1)` —
   is exactly what carries it, because `a - b = (tensA - tensB) * 10 + (unitsA - unitsB) ≥ 10 - 9 = 1`.
   Do **not** "simplify" `tensB` to `1..9` or widen `tensA` to `1..9`: either change makes negative
   answers constructible, and `isWellFormed` would then silently reject draws instead.
2. **HARD two-step's `c` cap is right and cannot throw (:276-287).** `ceiling = minOf(20, product - 1)`
   with `product ≥ 9` (both factors come from `SMALL_FACTORS`, min 3) means `ceiling ≥ 8`, so
   `random.nextInt(2, ceiling + 1)` always has a non-empty range — no `IllegalArgumentException` is
   reachable — and `c ≤ product - 1` makes the subtracting answer `≥ 1` by construction rather than by
   rejection. Brute-forced both directions over all 81 factor pairs and every `c`: no empty range, no
   non-positive answer, every candidate well-formed.
3. **There is no division anywhere in the generation path (:297-301).** `hardCleanDivision` picks the
   divisor and quotient and *multiplies*; the only `/` in the file is `isWellFormed`'s integrality
   check (:106). A non-integer answer is arithmetically impossible, not merely improbable, and there
   is no rejection sampling to go wrong.
4. **`hasCarry` / `hasBorrow` are genuine digit predicates, and they are also sufficient (:77, :80).**
   `(a % 10) + (b % 10) >= 10` and `(a % 10) < (b % 10)` read the units column, which is the only
   column that can carry or borrow on two-digit operands — so no tens-digit check is missing. The
   Kotlin negative-remainder trap is not live: `isWellFormed` rejects any negative operand at :98
   before either predicate is reached.
5. **The 300-attempt bound cannot hang or throw, and returning a repeat is the right degradation
   (:57, :171-184).** `repeat(MAX_ATTEMPTS)` is a fixed loop, and I checked every `nextInt` call in
   every construction path against every reachable input — all arguments are valid, so no draw can
   throw. Tier space sizes are EASY 189, MEDIUM ≈ 5 346, HARD ≈ 3 600, so a repeat only becomes likely
   after roughly 150 consecutive wrong answers at EASY — hundreds of answers past the point the escape
   hatch opened. Keeping a solvable question on screen beats an alarm with no question, which would be
   undismissable; the dev's call is correct.
6. **`(seed, draws)` is bit-identical, and it is stronger than replaying one stream
   (ChallengeState.kt:118-131, :186).** Each problem is drawn from a *fresh*
   `Random(seed + draw * GOLDEN_GAMMA)`, so a problem depends only on `(seed, drawIndex)` and not on
   how many values earlier draws consumed — which means a future change to `generate`'s rejection
   behaviour cannot retroactively shift an already-restored session. `GOLDEN_GAMMA` is odd, so
   `drawSeed` is injective across the whole `Int` range and two draws can never collide on a sub-seed.
   `start()` uses draw 1 and `withNextProblem` begins at 2, so no sub-seed is ever reused.
7. **All eleven non-deadline fields are in the `Bundle`, and a restore cannot re-roll, forgive or reset
   (AlarmRingActivity.kt:280-294 vs :296-316).** Field-by-field match against `ChallengeState`'s
   twelve properties, the twelfth being the deliberately-omitted deadline. `questionText` and
   `questionAnswer` are stored verbatim, so a restored screen shows the *same problem* rather than a
   regenerated one — rotation cannot fish for an easier question even in principle. `solved`,
   `consecutiveWrong` and `asked` all round-trip. `questionCount` is `coerceAtLeast(1)` on **both** the
   fresh path (ChallengeState.kt:196) and the restored path (:300), so a 0 or negative count can never
   make `isSolved` true at t=0.
8. **The escape deadline's absence from the `Bundle` is correct, and `onStop` cannot be outrun
   (AlarmRingActivity.kt:326-329).** The countdown's `LaunchedEffect` (:628) is tied to *composition*,
   not to lifecycle, so it keeps ticking while the activity is stopped — but `onStop` clears the
   deadline on the same main thread first, so the next tick's `completeEscape` finds
   `escapeRunning == false`, `isEscapeComplete` returns false, and no dismiss happens. I checked for
   any other persistence: the only writes to `escapeDeadlineElapsedMs` are `startEscape` /
   `cancelEscape`, it appears in no `outState.put…`, and `readChallenge` hard-codes `NO_DEADLINE`
   (:313). There is no `SharedPreferences`, no file and no DB column holding it.
9. **The sixty seconds are a full sixty, with no off-by-one in either direction.**
   `isEscapeComplete` is `now >= start + 60_000` (ChallengeState.kt:168-169); both ceiling functions
   return 60 on the first frame and reach 0 only once `remaining <= 0`, so the composable fires
   `onElapsed` at the same instant the authority check would allow it — the display can never run
   ahead of the entitlement. Pinned at ChallengeStateTest.kt:232-235 (`61_000` passes, `60_999` does
   not).
10. **All three escape locks are real, and "consecutive" is honest.** (a) the button exists only inside
    `if (state.escapeUnlocked)` (AlarmRingActivity.kt:552), itself only inside the `Challenge` branch;
    (b) `startEscape` no-ops on `!escapeUnlocked || escapeRunning` (ChallengeState.kt:142-143);
    (c) `isEscapeComplete` re-checks `escapeUnlocked` (:168). The reset is genuine: a correct `submit`
    sets `consecutiveWrong = 0` **and** `escapeDeadlineElapsedMs = NO_DEADLINE` in one `copy`
    (:102-108), so there is no intermediate state in which a running countdown has lost its
    entitlement, and `submit` returns `this` while a countdown runs (:99) so the keypad and the
    countdown can never be driven at once.
11. **Exactly two dismiss call sites, and I could not construct a third.** `AlarmService.dismissIntent`
    is referenced in `app/src/main` only at AlarmRingActivity.kt:363, reached from :193 (all questions
    solved) and :210 (a `isEscapeComplete`-verified countdown). The activity is `exported="false"`
    with `launchMode="singleTask"` (AndroidManifest.xml:69-70), so no other app can hand it an intent;
    both the full-screen and the content `PendingIntent` carry `EXTRA_ALARM_ID` (AlarmService.kt:288,
    :307-312), so tapping the notification re-enters `onNewIntent` with the **same** id and the
    `alarmId != previous` guard (:140) leaves the session untouched. Checked and closed: rotation,
    process death, an empty-string entry (`toIntOrNull` → `null` → `this`), `questionCount` of 0 or
    negative (coerced on both paths), integer overflow in the parsed entry (`MAX_ENTRY_DIGITS = 6`
    caps the entry at 999 999 against a maximum possible answer of 891, so `toIntOrNull` cannot
    overflow), and two rapid Submit taps (pure `submit()` on the main thread; the second sees an empty
    entry — see M2-1.3 for the cosmetic consequence).
12. **No `remember` holds challenge state.** The only `remember`s in the file are the clock's formatter
    and tick (:452-454), `rememberScrollState` (:412), and the countdown's *display* seconds keyed on
    the deadline (:626). Every transition is a pure `ChallengeState` method called from a click handler
    against one activity-scoped `mutableStateOf`, so no recomposition — and no rotation — can
    regenerate a problem or reset `consecutiveWrong`. The dev's structural claim holds.
13. **Swallowing `onKeyDown` genuinely prevents the volume change while the window has focus
    (:254-262).** `DecorView.dispatchKeyEvent` invokes the `Window.Callback` (`Activity.dispatchKeyEvent`)
    **first** and only falls through to `PhoneWindow.onKeyDown` / `onKeyUp` — which is where
    `KEYCODE_VOLUME_*` becomes a `dispatchVolumeKeyEventAsSystemService` call — when the callback
    returns false. Returning `true` short-circuits that, so the stream is never adjusted. Overriding
    `onKeyUp` as well is **necessary, not redundant**, because `PhoneWindow.onKeyUp` has its own volume
    branch; do not delete it. Compose's focus system consumes only Enter / Space / DPAD-centre, so
    volume keys do reach the activity rather than being eaten by a focused `Button`.
    `KEYCODE_VOLUME_MUTE` is the correct third keycode — `KEYCODE_MUTE` is the microphone, not the
    stream. See M2-1.7 for what this scoping cannot cover.
14. **The UI's haptic cannot kill the ringer's vibration, and `ui/` still holds no audio.**
    `LocalHapticFeedback` resolves to `View.performHapticFeedback`, requested at
    `VibrationAttributes.USAGE_TOUCH`, while `AlarmRinger` vibrates at `USAGE_ALARM`
    (AlarmRinger.kt:381); the vibrator service arbitrates by usage importance, so the touch effect is
    dropped rather than preempting the alarm waveform. Using Compose haptics instead of a `Vibrator`
    is the right call precisely because of this. The structural rule that outranks everything else is
    intact: no `MediaPlayer`, `AudioManager`, `AudioAttributes`, `Vibrator`, `Ringtone`, `WakeLock` or
    `AlarmRinger` reference anywhere in `ui/` outside KDoc prose, and the activity's only lever on the
    alarm remains `ACTION_DISMISS`.
15. **Backlog 1.16 is correctly fixed and introduces no recomposition problem (:94-95).** `label` is
    read in composition and now updates when `onNewIntent` re-labels the screen, which is what 1.16
    asked for. `alarmId` is observable but read **only** from non-composable code (`dismiss()` at :363,
    the back-press log at :240), so it subscribes nothing and cannot cause a recomposition, let alone
    a loop; both are written only from `onCreate` / `onNewIntent` on the main thread. Making `alarmId`
    state is harmless but unnecessary — leave it, it costs nothing.
16. **The loading state and the MEDIUM × 1 fallback are fail-closed, and the in-flight-read guard is
    correct (:164-178, :429-432).** While `challenge == null` the only thing composed is the
    "preparing" text — no keypad, no Submit, no escape button, no dismiss path — so the alarm is never
    dismissable for free during the read, and `runCatching` turns a missing row, an absent id or a
    thrown read into MEDIUM × 1 rather than a permanent null. The `loadingFor != id || challenge != null`
    guard (:173) is correct for both completion orderings of two overlapping reads, for a second alarm
    arriving mid-read, and for a restored `Bundle` landing first. See M2-1.6 for the one case it does
    not bound.
17. **The tier tests really do assert the structural property rather than asking the validator.** Spot-
    checked four: ProblemGeneratorTest.kt:84 inlines `(a % 10) + (b % 10) >= 10` instead of calling
    `hasCarry`; :110 inlines `(a % 10) < (b % 10)`; :162 inlines `ab % 10 != 0`; :199 asserts
    `dividend % divisor == 0` directly. A generator and validator that were wrong in the same
    direction would still fail these, so the dev's claim is verified. :202 goes further and asserts
    `isAllowedFactor(quotient)`, which `isWellFormed` itself does not — the test is currently stronger
    than the code it guards (M2-1.4).

**Rulings on the dev's three self-disclosures**

1. **Service-side volume re-assertion deferred — reasoning SOUND, amendment text TOO NARROW.** The
   technical argument is right and I would have made the same call: a `ContentObserver` on
   `Settings.System` volume inside `AlarmRinger` fires on the ringer's **own** `setStreamVolume`
   writes, and `AlarmRinger` already owns a durable pre-ring volume record that finding 1.1 exists to
   protect — an observer that cannot tell its own write from the user's is precisely the 1.1 bug class,
   and it would race `restoreAlarmVolume()` on dismiss. Declining to re-open thrice-reviewed audio
   code in a UI module was correct, and flagging it was correct. Where I part company is the
   *description* of what is left behind: §3 attributes the residual to the UI not having focus
   "(notifications or `USE_FULL_SCREEN_INTENT` denied)", but focus can also be taken away on a fully
   permissioned device — the notification shade opens over any activity and its slider targets
   `STREAM_ALARM` while the ringer plays, and AVRCP / OEM mute keys bypass the window entirely. So the
   hole is wider than the amendment admits, and Module 3 **diagnostics** cannot close it: there is no
   permission state to report. Keep the deferral; correct §3's residual sentence; and track the
   re-assertion as a Module 3 *implementation* item with the self-write filter written into the spec
   (re-assert only when the observed index differs from the index the ringer last wrote, and never
   touch the durable pre-ring record) so whoever implements it does not rediscover 1.1. Logged as
   M2-1.7, MINOR — silencing is not dismissing, so it does not gate.
2. **Rotation during the escape countdown restarts the minute — AGREE, and the alternative was worse.**
   It fails in the harder direction, costs at most one extra minute, and only when the user rotates
   mid-countdown. Persisting an `elapsedRealtime` deadline would let a minute complete while the
   process was dead and nobody was watching, which is the exact thing "the user must remain on screen"
   exists to prevent; and clearing it in `onStop` implements "leaving or backgrounding resets the
   countdown" in one line rather than in a lifecycle state machine. I verified the claim rather than
   taking it: nothing else persists the deadline, and the countdown coroutine cannot outrun the
   `onStop` clear (both on the main thread, and `completeEscape` re-validates). No change needed.
3. **`setOngoing(true)` only holds to API 33 — MATTERS, and more than the disclosure says.** The
   audio-survives half is correct and the alarm is not silenced. But the notification is the *only*
   route back to the challenge UI once `onStop` has fired, because the ring task is excluded from
   recents and `MainActivity` cannot reach it — so swiping it away on API 34+ does not merely weaken a
   cosmetic promise, it can strand a willing user in front of an alarm they have no way to switch off.
   That is M2-1.1, the one MAJOR, and it is the reason this round does not advance. §3's
   "Notification is non-dismissible while ringing" bullet is **not** met on API 34–36.

**What the 49 new tests do not cover** (none of these gate; recorded so the gaps are known)

- **No test exercises `AlarmRingActivity` at all** — not the two dismiss call sites, not
  `onSaveInstanceState` / `readChallenge`, not `onNewIntent`, not the `onStop` cancel. The test named
  `a restored session keeps the problem, the progress and the failure count`
  (ChallengeStateTest.kt:324) hand-builds the restored value, **mirroring** `readChallenge` instead of
  calling it, so dropping a `putX` from `onSaveInstanceState` would not fail anything. (Adding a field
  to `ChallengeState` *would* fail to compile, since it has no default values — the compiler covers
  that one direction only.)
- The countdown arithmetic the user actually sees, `secondsUntil` (AlarmRingActivity.kt:663), is
  untested; the tested copy is unused (M2-1.5).
- `generate`'s last-resort branch (ProblemGenerator.kt:183) is never reached:
  `generate still returns a problem when everything is excluded` exhausts EASY (189 problems against
  4 000 draws) but not MEDIUM (≈ 5 346) or HARD (≈ 3 600), and the branch additionally needs 300
  consecutive *malformed* draws, which construction makes impossible.
- `digit(value)` with `value !in 0..9` (ChallengeState.kt:77) is untested.
- `a problem from one tier is not well formed for another` (ProblemGeneratorTest.kt:272) tests one EASY
  addition only; nothing pins that a MEDIUM or HARD problem is rejected by the other two tiers.
- Nothing pins the division tier's 8-in-72 `isWellFormed` rejection rate as intentional, so widening
  `SMALL_FACTORS` past 12 would silently break the hard-coded `n[0] in 100..144` bound with no test
  failing (M2-1.4).

**Acceptance criteria audit** (§3's seven boxes)

- [x] **`./gradlew assembleDebug` exits 0** — independently verified before this review;
      `testDebugUnitTest` 86/86 green, zero failures, zero `@Ignore`.
- [x] **`ProblemGenerator` has zero Android imports** — the only imports are
      `com.mathalarm.data.Difficulty` and `kotlin.random.Random`. `Difficulty` is a bare enum
      (Alarm.kt:8) so the file is genuinely JVM-pure; the package coupling is NIT M2-1.11, not a breach.
- [x] **Unit tests cover every tier, every degenerate-operand rule, integer and positive guarantees,
      carry/borrow enforcement, and no-repeat** — genuinely, not superficially. The tier tests assert
      the structural property inline rather than through the validator (Verified correct #17), the
      validator is separately pinned against hand-built bad problems (:308-357) so it is not a
      tautology, and `20 + 30` / `50 - 30` are both pinned as *not* MEDIUM. Gaps above are in
      non-critical paths.
- [x] **Wrong answer generates a different problem — verified by test** —
      `a wrong answer always produces a brand new problem` (ChallengeStateTest.kt:67) runs 30
      consecutive wrong answers at all three tiers and asserts the text changes every time;
      `a long session never repeats a problem` (:172) additionally asserts 41 globally distinct
      problems per tier. `submit()` calls `withNextProblem()` unconditionally on a wrong answer, so a
      lucky guess can never be retried against the same question.
- [x] **Escape hatch unreachable before 5 consecutive failures — verified by test** — three tests
      (:189, :202, :215) cover locked-at-four, unlocked-at-exactly-five and `startEscape` no-opping
      early, plus :270 for the relock. All three independent locks and the honesty of the reset are
      verified in code (Verified correct #10).
- [x] **No multiple-choice input anywhere** — a 10-key dialer-order keypad plus backspace and an
      explicit Submit; no `RadioButton`, `Checkbox` or option list exists in the project, and the
      answer is free numeric entry graded only on submit.
- [x] **Audio survives activity destruction** — unchanged and re-verified: the service owns the
      player, the focus request and the vibrator; `ui/` holds none of them and its only lever is
      `ACTION_DISMISS` (Verified correct #14).

Two §3 **deliverable** bullets outside the seven boxes are not met:
**"Notification is non-dismissible while ringing"** fails on API 34–36 and takes the route back to the
keypad with it (**M2-1.1, MAJOR**); **"wrong answer → haptic buzz"** is probably not delivered on
alarms with `vibrate = true` (M2-1.9, MINOR). The amended volume bullet is met within its own
window-focus scope, with the residual mis-described (M2-1.7, MINOR).

**DO NOT ADVANCE.** One MAJOR open (M2-1.1). Per §1 rule 2 this is a one-item round 2: fix M2-1.1 and
nothing else. The eight MINORs and four NITs are backlog and explicitly do **not** gate — M2-1.2 and
M2-1.3 are the two worth folding in if the dev is already in those files, since both are one-line
hardening of the free-dismissal surface.

### Round 2 — 2026-10-09

**Verdict:** 0 blockers, 0 majors, 3 minors, 4 nits. Of the seven findings the dev claimed,
**all seven adjudicated: six CLOSED, one STILL OPEN but unfixable as specified.** MAJOR **M2-1.1
CLOSED** — M2-1.2 **CLOSED**, M2-1.3 **CLOSED**, M2-1.4 **CLOSED**, M2-1.5 **CLOSED**, M2-1.6
**CLOSED**, M2-1.9 **STILL OPEN** (platform limitation verified; §3 needs amending, not the code).
I walked M2-1.1's fix myself rather than reading the dev's walk, because it put a new entry point
into Module 1's thrice-reviewed service: the re-post is built by the same `buildNotification`, so a
second swipe is answered identically; the resurrection guard is a live read of `ringingAlarmId` and
a dead alarm's notification cannot come back; **no start id leaks and the `START_REDELIVER_INTENT`
guarantee survives a swipe** (the mechanism is subtle and is written up in "Verified correct" #1
below, because it looks exactly like a leak and is not); the delayed `stopSelf` cannot fire inside
finding 1.9's protected window and cannot kill a newly-started alarm; the wake lock is never
touched on the new path, so Module 1's ten-path "exactly once" accounting is unchanged. The
`encode`/`decode` session format is fail-closed in every direction I could craft — truncated,
blanked, extra-field, wrong-version, separator-smuggled, negative, over-length-entry, and
already-solved all return `null`, and a successful decode is never easier than the state that was
saved. The modified pre-existing test is **legitimate and net stronger** (Task 2 ruling below). The
three new minors are all one-line hardenings of unreachable paths, exactly the class Round 1 logged
as backlog. **ADVANCE.**

**Disposition of the Round 1 findings claimed closed**

| # | Severity | Disposition | Evidence |
|---|---|---|---|
| M2-1.1 | **MAJOR** | **CLOSED** (residual MINOR M2-2.3) | AlarmService.kt:200-233, :383-416. (a) *Swipe → re-post, and a second swipe behaves identically.* `handleNotificationDeleted` calls `ServiceCompat.startForeground(..., buildNotification(alarmId, ringingLabel), ...)` — the **same** builder, so the re-posted notification carries `setFullScreenIntent` (:411), `setContentIntent` (:412) and `setDeleteIntent` (:413). The delete `PendingIntent` is minted with a fixed request code and `FLAG_UPDATE_CURRENT` and carries no extras, so re-posting cannot mint a divergent one. Swipe *n* is answered exactly like swipe 1, unbounded and user-paced. The notification was *removed* before this post, not updated, so the full-screen intent fires again and the keypad comes back to the front — a bonus, and harmless: the FSI re-enters the `singleTask` activity through `onNewIntent` with the **same** `alarmId`, so the `alarmId != previous` guard (AlarmRingActivity.kt:141) leaves the session, the failure count and any running countdown untouched. `FOREGROUND_SERVICE_IMMEDIATE` (:414) is load-bearing here and is already set: without it the re-post could be deferred up to 10 s. (b) *Dismiss-then-swipe cannot resurrect a dead alarm.* `silenceRinger()` sets `ringingAlarmId = NO_ALARM` synchronously on the main thread (:239) before the deferred teardown, and every read and write of that field is on the main thread, so there is no interleaving. A swipe arriving in finding 1.9's 10 s window reads `NO_ALARM` and re-posts nothing. I checked the inverse too: on the **stale-dismiss** path (:140-145) `silenceRinger` is deliberately *not* called, so `ringingAlarmId` still holds the alarm that is actually ringing and a swipe correctly re-posts *its* notification with *its* label. (c) *Start ids, the delayed stop, and `onDestroy`'s purge* — see "Verified correct" #1 and #2; no leak, the delayed stop cannot kill a live alarm, and the purge is sufficient. (d) *`ringingLabel`* — see "Verified correct" #3; the invariant holds, including across `START_REDELIVER_INTENT`. (e) *Foreground-start privilege* — see "Verified correct" #4; the re-post is an update to an **already-foreground** service, so `ForegroundServiceStartNotAllowedException` is not in play in any state. (f) *Module 1's invariants* — the new handler never touches `AlarmWakeLock`, never calls `ringer`, never writes `ringingAlarmId`, and never calls `stopForeground`, so wake-lock accounting, `isRinging` semantics, the stale-dismiss guard and the 1.9 write window are all untouched. §3's anti-cheat bullet is now met **in substance** — the route back to the keypad can no longer be severed — though not in its literal wording; see M2-2.7. |
| M2-1.2 | MINOR | **CLOSED** (structurally) | ChallengeState.kt:255-298 with AlarmRingActivity.kt:312-320. The eleven `putX` calls are gone; the activity writes one `putString(KEY_SESSION, encode(state))` and reads one `getString`. `decode` has **no defaults at all** — every one of the twelve fields is `?: return null` or an explicit `when … else -> return null`, so the absent-key `0` that was itself a typable winning answer cannot occur, and `questionAnswer <= 0` is additionally refused (:279). `a session with any field missing is refused rather than defaulted` (ChallengeStateTest.kt:436) drives *every* index in both the removed and the blanked shape and asserts the two legitimately-empty fields still cannot carry a dismissal. |
| M2-1.3 | MINOR | **CLOSED** | AlarmRingActivity.kt:217-219. `progressed = next.solved > current.solved; if (progressed && next.isSolved) dismiss()`. I checked the direction that matters more than the one it fixes — **a legitimate final correct answer can never fail to dismiss**: a correct `submit()` always returns `copy(solved = solved + 1, …)` (ChallengeState.kt:102-109), so `next.solved` is exactly `current.solved + 1` and `progressed` is unconditionally true on a correct answer; `questionCount` is fixed for the life of the session and `solved` only ever rises by one, so `isSolved` is reached by a correct answer and by nothing else. There is no path on which a solved session is left ringing. The double-tap it was filed for is now a no-op (second tap sees an empty `entry`, `submit()` returns `this`, `progressed` false). The `ChallengeState` half is belted too: `decode` refuses `solved !in 0 until questionCount` (:277), so a restored state cannot walk in already holding the dismissal — pinned at ChallengeStateTest.kt:485. |
| M2-1.4 | MINOR | **CLOSED** (both directions) | ProblemGenerator.kt:123, :165. I re-brute-forced the predicate independently rather than trusting the test: all **72** constructible divisor/quotient pairs now pass (0 rejected, against 8 before), and `96 ÷ 96 = 1`, `12 ÷ 12 = 1`, `144 ÷ 144 = 1`, `80 ÷ 8 = 10`, `110 ÷ 11 = 10`, `5 ÷ 1 = 5`, `50 ÷ 10 = 5`, `9 ÷ 3 = 3` and the non-integral `13 ÷ 4 = 3` are all refused. The dividend is no longer treated as a factor (`factorsAllowed` is `isAllowedFactor(n[1])` only) and the magnitude window is now the honest `n[0] >= 10` instead of a hard-coded `100..144`, so widening `SMALL_FACTORS` past 12 no longer silently breaks the validator. The KDoc at :157-164 now describes what the code does. Both directions are pinned by new tests (:274, :302). |
| M2-1.5 | MINOR | **CLOSED** | `secondsUntil` is gone — `grep -rn secondsUntil app/src/` returns two **prose** hits and no code. `EscapeCountdown` (AlarmRingActivity.kt:639-651) calls `state.escapeSecondsLeft(…)` for both the initial value and every tick, so the arithmetic the user watches is now the arithmetic ChallengeStateTest.kt:234-249 pins. The composable still only *displays*; `completeEscape`'s `isEscapeComplete` remains the authority. |
| M2-1.6 | MINOR | **CLOSED** (doc overstated — NIT M2-2.4) | AlarmRingActivity.kt:177-197. `withTimeoutOrNull(5_000) { read.await() }` times out the **await**, not the query, so a slow `getById` is not interrupted mid-statement, and the MEDIUM × 1 fallback is now guaranteed rather than conditional on the read terminating: on timeout `alarm` is null and `ChallengeState.start(MEDIUM, 1, …)` is installed, so the keypad always appears. On `read.cancel()`: it cannot corrupt anything — a Room read cancellation lands as a `CancellationSignal` on a read-only statement, which SQLite aborts cleanly, and there is no transaction and no write anywhere on this path. Nor does it leak: without it the `async` child would keep the `lifecycleScope.launch` parent alive under structured concurrency until a wedged query returned, which is precisely what the comment says. If the underlying call is genuinely uninterruptible the IO thread finishes on its own and the (now-cancelled) deferred's value is read by nobody — the `loadingFor != id \|\| challenge != null` guard at :191 has already been passed. The `runCatching` inside the `async` does swallow the `CancellationException` (the usual Kotlin footgun) but nothing awaits that value any more, so it is inert here. |
| M2-1.9 | MINOR | **STILL OPEN — ruled unfixable as §3 is written** | Platform claim independently verified; see ruling 3 below. Stays as backlog with a recommended §3 amendment. Non-gating: MINOR, and the red error state carries the same information. |

**New findings**

| # | Severity | File:line | Finding | Why it matters |
|---|---|---|---|---|
| M2-2.1 | MINOR | AlarmService.kt:201-219 (with :83, :107, :420) | **`handleNotificationDeleted` uses the `NO_ALARM` id sentinel as the "nothing is ringing" predicate**, so "this alarm has no id" and "no alarm is ringing" are the same state. `handleStart` writes `ringingAlarmId = alarmId` unconditionally (:107), including when `intent.getLongExtra(EXTRA_ALARM_ID, NO_ALARM)` returned the `-1L` default — and then starts the ringer. Scenario: an `ACTION_START` reaches the service without `EXTRA_ALARM_ID`. The alarm rings normally, but `ringingAlarmId == NO_ALARM`, so a notification swipe takes the dead-alarm branch: the notification is **not** re-posted (M2-1.1 is re-opened for that ring, with no route back to the keypad once `onStop` fires) *and* a `postDelayed { if (ringingAlarmId == NO_ALARM) stopSelf(startId) }` is armed, which 12 s later destroys the service — `onDestroy` → `stopAlarm()` → `ringer.shutdown()` — and **silences an alarm that is still ringing**. Unreachable today, and I pushed on it: `AlarmScheduler` always writes a real row id into the fire `PendingIntent` (AlarmScheduler.kt:126), `AlarmReceiver` is `exported="false"` (AndroidManifest.xml:94) so the action cannot be spoofed, and the debug trigger upserts a real row at id 999 (MainActivity.kt:129-149). | This is the one place in the new code where an unreachable precondition failure would not merely fail closed — it would silence the alarm, which is the module's worst outcome. The sentinel collision is pre-existing (`handleDismiss` overloads the same value at :147), but the swipe handler is the first consumer where the two meanings diverge in a *harmful* direction. One line: predicate the branch on the ringer rather than on the id — `if (ringer?.isRinging != true)`, or keep a separate `isRinging` flag — and the whole class goes away, including for any future caller of `handleStart`. |
| M2-2.2 | MINOR | ChallengeState.kt:274-281 | **`decode` asserts five consistency relations and not the sixth: `draws` against `asked`.** `asked` is read as `fields[11].split(RECORD_SEPARATOR).filter { it.isNotEmpty() }` and then `ifEmpty { listOf(questionText) }` (:290), with no check that its size equals `draws` and no check that it contains `questionText`. `encode`/`withNextProblem`/`start` keep `asked.size == draws` as a genuine invariant, so this is unreachable through the real save path — but a session restored with a short, long or `questionText`-free `asked` list has an **incomplete no-repeat memory**, which is the one §3 generator rule ("No repeated problem within one alarm session") that lives in the restored state rather than in the generator: `withNextProblem` passes `asked.toSet()` as `exclude`, so the very next draw can re-offer the problem currently on screen, or a problem the user has already seen and whose answer they remember. | Every other invariant in this reader is asserted — version, field count, enum membership, `solved in 0 until questionCount`, `consecutiveWrong >= 0`, `questionAnswer > 0`, `draws >= 1`, a typable entry — which is what makes the one unasserted relation stand out rather than read as a deliberate omission. The failure direction is mild (one easier problem, not a dismissal), which is why this is MINOR and not more. Cheapest version is one line: `if (questionText !in asked) return null`; `asked.size != draws` is stricter but has to be reconciled with the `ifEmpty` fallback at :290. |
| M2-2.3 | MINOR | AlarmService.kt:221-232 (with AndroidManifest.xml:68, MainActivity.kt:47) | **The route back to the keypad is still a single point of failure, and the fix is best-effort by construction.** The re-post is wrapped in `runCatching` whose `onFailure` does nothing but `Log.e` — correctly, since tearing the alarm down because its notification would not re-post is strictly worse. But there is no second route: the ring task is `excludeFromRecents="true"` with `taskAffinity=""` (deliberately kept, per Round 1), and `MainActivity` still offers no way to reach a ringing alarm. So *if* a re-post is refused or suppressed — an OEM notification manager that declines to re-show a user-dismissed ongoing notification, a future platform tightening, a `startForeground` that throws for a reason we have not predicted — the user is back in exactly M2-1.1 with nothing to fall back on and nothing visible to tell them why. Verification here is code-review-only, so I can verify the AOSP path (see "Verified correct" #4) but not every OEM's. | M2-1.1 is genuinely closed on the AOSP semantics, and I am not re-opening it. The residual is that a defect whose whole severity came from "there is exactly one route back" has been fixed by making that one route self-healing rather than by adding a second one. Module 3 is already building the alarm list and the diagnostics screen, so the cheap second route is nearly free: while the service is live, have `MainActivity` show "an alarm is ringing — open it" wired to the same `ringIntent`. Logged for §4, non-gating. |
| M2-2.4 | NIT | AlarmRingActivity.kt:171-173, :183-189 | The KDoc's "a merely slow read cannot be interrupted mid-query" is true of the `withTimeoutOrNull`, but `read.cancel()` on the next line cancels the query anyway, and in any case a read that lands at 5.1 s is **discarded**: the fallback session is already installed, so the `challenge != null` guard at :191 would reject it. The honest statement is "a read slower than 5 s is abandoned and the alarm falls back to MEDIUM × 1", which is the trade-off Round 1 asked for — but it does mean a HARD × 3 alarm is downgraded on a device whose database read takes longer than five seconds, i.e. the one corner of this module where a restore-ish path is *easier* rather than harder. Accepted (an easier gate beats an undismissable alarm) and recorded so nobody reads the comment and believes the real tier always wins. |
| M2-2.5 | NIT | ChallengeState.kt:272, :274 | `decode` puts no length bound on `questionText` or on individual `asked` entries — only `isEmpty` is checked. Unreachable through `encode` (texts are at most `"99 × 12 + 20"`-sized and the `Bundle` has its own transaction ceiling on the way *in*), and the consequence is a badly-laid-out screen rather than a dismissal, so this is cosmetic hardening only. A `questionText.length > 32` guard would make the field as bounded as `entry` already is. |
| M2-2.6 | NIT | ChallengeStateTest.kt:23-29 | The test duplicates `FIELD_SEPARATOR` as a literal and hard-codes four **positional indices** into `encode`'s field list (`SOLVED_FIELD = 5`, `ANSWER_FIELD = 7`, `ENTRY_FIELD = 10`, `ASKED_FIELD = 11`). Reordering `encode`'s `listOf` — a plausible tidy-up, since the order carries no meaning — silently re-points all four, and the corrupt-one-field tests would then be asserting about the wrong fields while still passing. The separator duplication is unavoidable (it is private); the indices are not — deriving them by searching the encoded string for the known value, or making them `internal`, removes the drift. |
| M2-2.7 | NIT | §3 anti-cheat, bullet 4 | §3 still says "**Notification is non-dismissible while ringing**". That is not achievable by any app on API 34-36 — `setOngoing(true)` stops a swipe only to API 33, and there is no flag that restores it. What M2-1.1's fix delivers, and the strongest thing available, is "the notification cannot be *severed*: a swipe re-posts it, with the full-screen, content and delete intents intact". Recommend §3 be amended to say that, so a future reviewer does not re-raise a bullet that is now met in substance and permanently unmeetable as worded. |

**Rulings on the dev's three self-disclosures**

1. **One outstanding start id for the ring's duration — SOUND, and the alternatives really were worse.**
   The dev's two stated reasons both hold and I verified the premise of each. *Why not `stopSelf(startId)`:*
   the swipe delivery carries the **newest** start id, so `stopSelf(startId)` would not be declined — it
   would pass the `getLastStartId() == startId` test and bring the service down while the alarm rings,
   which is precisely the shape of open backlog **2.2** (a teardown call that silences the live alarm),
   and worse, because 2.2's window is milliseconds wide whereas this one would be the entire ring.
   *Why not release anything:* finding **1.9** owns the foreground notification and the wake lock until
   the auto-disable write commits, and `handleNotificationDeleted` touching either would end that window
   early on the newest start id with the write unprotected. Declining both is correct. And the cost the
   dev calls "nothing" really is nothing: see "Verified correct" #1 — an outstanding start id is not a
   resource, the `START_NOT_STICKY` return removes the delivered-start record immediately, and the
   dismiss that follows is newer still and does stop the service. The one case the dev's note glosses is
   that the delayed `stopSelf` in the *not*-ringing branch is **load-bearing rather than tidy**: a swipe
   that lands after the dismiss makes the dismiss's own `stopSelf(startId)` a no-op, so without the
   delayed stop the service would linger until the OS reclaimed it. Worth knowing; does not change the
   ruling.
2. **`decode`'s four added strictnesses — UPHELD, including the one that discards legitimate progress.**
   "Harder, never easier" is true on every path, and I checked it in both halves rather than only the
   advertised one. *A successful decode:* every field is copied verbatim, every added check only
   *narrows* (`questionAnswer > 0`, `solved < questionCount`, a digits-only entry within
   `MAX_ENTRY_DIGITS`, a parsable `Difficulty`), `questionCount` is only ever coerced **upward** to 1,
   and `escapeDeadlineElapsedMs` is always `NO_DEADLINE`, which restarts the minute. There is no path on
   which a restored session owes less, has been forgiven more, or arrives with an open escape hatch than
   the session that was saved — the `solved !in 0 until questionCount` check specifically makes an
   already-solved session unrestorable. *A `null` decode:* the activity starts fresh off the alarm's
   **real** tier, so a HARD × 3 session two questions in becomes three fresh HARD questions —
   dramatically harder — and `consecutiveWrong` resets, which re-locks the hatch. The corrupt-difficulty
   case the dev flags as the costly one is the only place where "harder" is not strictly true, and it
   lands at *equal*, not easier: the old reader defaulted to MEDIUM and kept progress (one MEDIUM
   question left); the new one starts fresh off the real tier, and only if the tier read *also* fails does
   it land on MEDIUM × 1 — one MEDIUM question, the same thing. Everywhere the tier read succeeds it is
   harder. Discarding progress to avoid guessing at a field you could not parse is the right instinct in
   a module whose worst defect class is an unearned dismissal. No change needed; the two residual
   omissions are M2-2.2 and M2-2.5, both unreachable.
3. **M2-1.9 left unmet — the platform claim is TRUE, the refusal was RIGHT, and §3 must be amended.**
   I verified the claim rather than accepting it, on all three legs.
   (a) *Compose.* `HapticFeedback` exposes exactly one method, `performHapticFeedback(HapticFeedbackType)`,
   and `HapticFeedbackType` is an `Int`-wrapping value class of effect constants. There is no attributes,
   usage, amplitude or importance parameter anywhere on the interface, and
   `LocalHapticFeedback` resolves to the `View`-backed implementation. **No usage can be set.**
   (b) *`View`.* The only overloads are `performHapticFeedback(int)` and `performHapticFeedback(int, int)`,
   and the flags are `FLAG_IGNORE_VIEW_SETTING` and the deprecated `FLAG_IGNORE_GLOBAL_SETTING` — both
   about *whether* to buzz, neither about usage. The request travels `ViewRootImpl` → `IWindowSession`
   → the window manager → the vibrator service, which builds the `VibrationAttributes` itself from the
   effect constant (touch usage for `LONG_PRESS`, hardware-feedback usage for a handful of hardware
   constants — both strictly below alarm). The dev's phrasing is exact: **the constant selects only the
   effect; the usage is chosen by the system.**
   (c) *Arbitration.* `AlarmRinger` starts a **repeating** waveform —
   `VibrationEffect.createWaveform(VIBRATION_PATTERN, VIBRATION_REPEAT_INDEX)` with the index at 0
   (AlarmRinger.kt:379-384) — at `VibrationAttributes.USAGE_ALARM`. The vibrator service's
   ongoing-vibration check cancels an ongoing *repeating* vibration only for a newcomer of **equal or
   higher** usage importance, and alarm outranks touch. So the touch-usage haptic is ignored for the
   duration of the ring. Exactly as predicted.
   The only route the dev did not name is a **service-side** haptic — `AlarmRinger` already holds the
   `Vibrator`, so a short buzz emitted from `alarm/` on a new service action would not put a `Vibrator`
   in `ui/` at all. I checked it and it is worse, not better: a one-shot at `USAGE_ALARM` has *equal*
   importance to the ongoing waveform, so it would **cancel** the ringer's vibration and replace it with
   a single buzz, after which the alarm stops vibrating for the rest of the ring — breaking "sound
   continues unbroken" and requiring a restart state machine inside Module 1's thrice-reviewed audio
   code, which Round 1's ruling 1 was explicit about not opening. Refusing to break the audio-ownership
   rule was the right call, and documenting the limitation at the call site
   (AlarmRingActivity.kt:534-543) is the right way to leave it.
   **So: §3's "wrong answer → haptic buzz" bullet is unachievable as written on `vibrate = true` alarms,
   and I am saying so explicitly — it should be amended, not re-assigned to a future round.** Recommended
   replacement for that bullet: *"Wrong answer → prominent visual error state, a brand-new problem, sound
   continues unbroken. A haptic buzz is best-effort: it is delivered on `vibrate = false` alarms and
   dropped by the platform on `vibrate = true` ones, because `View.performHapticFeedback` is fixed at
   `VibrationAttributes.USAGE_TOUCH` and the vibrator service ignores a lower-importance vibration while
   the ringer's `USAGE_ALARM` waveform is playing. Raising the usage would require `ui/` to hold a
   `Vibrator` (forbidden by §2); emitting it from the service at `USAGE_ALARM` would cancel the ringer's
   own waveform. The error state is the required feedback."* The dev's point that the red state carries
   the same information is correct — and on a vibrating alarm the user is not short of tactile signal.

**Task 2 — ruling on the modified pre-existing test: LEGITIMATE, and the suite is net stronger.**

The change is at ProblemGeneratorTest.kt:252, inside
`no tier ever produces a degenerate factor or a zero operand`: for `Operation.DIVIDE` the asserted
factor list went from `problem.operands` (dividend, divisor) to `listOf(problem.operands[1], problem.answer)`
(divisor, quotient). I treated this as the move that can hide a regression and checked it four ways.

- **Was the old assertion right?** No. It demanded `isAllowedFactor(dividend)`, i.e. that no division's
  dividend be a multiple of ten — which forbids `20 ÷ 4 = 5`, `30 ÷ 6 = 5` and `60 ÷ 12 = 5`. A division
  built backwards from a product has two factors, the divisor and the quotient; the dividend **is** the
  product. §3 says "reject degenerate operands: no ×0, ×1, ×10" about factors. So the old line asserted
  a rule the spec does not state, and it was the test half of M2-1.4: it pinned the bug in place, which
  is why the bug survived Round 1's generation sampling.
- **Would the new assertion have caught the old bug?** No — and neither would the old one. The generator
  never *constructs* a degenerate quotient (`DIVISION_QUOTIENTS` is 4..12 minus multiples of ten), so a
  sampling test over generated output passes under both validators. This test is not where M2-1.4 is
  caught, and the dev does not claim it is. The two **new** hand-built validator tests are:
  `the hard division validator rejects a degenerate quotient` (:274) fails under the old code on all
  three of `96 ÷ 96`, `12 ÷ 12`, `80 ÷ 8`, and
  `the hard division validator accepts every constructible pair` (:302) fails under the old code on
  `20 ÷ 4`. Both directions of the finding are now pinned where nothing covered either before.
- **Does it still catch everything the old one did?** Yes, for everything the old one caught *correctly*.
  A zero dividend is still caught — `no tier ever produces a zero or negative answer` (:225-234) asserts
  **every operand > 0** on every tier. A one-digit dividend is still caught — `assertTrue(dividend >= 10)`
  at :203, plus `n[0] >= 10` in the validator. Exactness is still caught at :199-200. The *only* thing
  lost is "the dividend is not a multiple of ten", which is the assertion now known to be wrong.
- **What is gained?** The quotient is now covered by the sampling test as well as by the hand-built one,
  so if construction ever drew a quotient of 1 or 10 it would fail here too. Nothing covered the quotient
  before.

Net: **strictly stronger on the quotient, weaker on exactly one dimension that should never have been
asserted, and unchanged everywhere else because the dropped coverage is duplicated by two other
assertions in the same file.** The KDoc at :236-245 states the reasoning honestly, including that the old
test "demanded that `20 ÷ 4 = 5` never be generated". Not a regression-hider. Approved.

**Verified correct (Round 2)** — these look wrong, risky or too clever and are not. Do not "fix" them.

1. **The swipe delivery leaks no start id, and it does not cost Module 1's `START_REDELIVER_INTENT`
   guarantee** (AlarmService.kt:63-66, :200-233). This is the single most suspicious-looking thing in the
   diff and it is sound, for a reason that is not obvious. `stopSelf(int)` stops the service only when the
   id is the most recent, so an id the handler never retires is not a leak — it is at most a service that
   declines to stop until something newer retires it, and the dismiss is always newer. More subtly, the
   branch returns `START_NOT_STICKY`, which (a) retires its own delivered-start record immediately and
   (b) sets the service record's "stop if killed" flag, because it is the newest start. That flag looks
   like it would cancel the `ACTION_START` redelivery the module depends on — it does not: on a process
   kill the still-outstanding `START_REDELIVER_INTENT` item is moved back onto the pending-start list
   **before** the stop-if-killed decision is taken, and that decision additionally requires the pending
   list to be empty. So the service is restarted and the original `ACTION_START` intent is redelivered,
   exactly as before the fix. (For the same reason, a swipe arriving *after* a dismiss does not resurrect
   anything: `stopSelf(dismissStartId)` drops every delivered start up to and including its own, so the
   `ACTION_START` item is gone and a kill in that window correctly leaves the service stopped.)
   Do not "tidy" the branch by returning `START_REDELIVER_INTENT` (it would redeliver a swipe) or by
   adding a `stopSelf` (ruling 1).
2. **The delayed `stopSelf` cannot fire inside finding 1.9's window and cannot kill a new alarm**
   (:214-217, :436). Three independent reasons, and I checked each. *Ordering:* the not-ringing branch is
   reachable only when the notification is still posted and `ringingAlarmId == NO_ALARM`, which is
   exactly and only the dismiss-teardown window — so the 12 s clock starts *after* the dismiss, and
   `IDLE_STOP_DELAY_MS = DISMISS_WRITE_TIMEOUT_MS + 2_000` is therefore always ≥ 2 s past the write
   ceiling, never merely nominally so. *The guard:* `if (ringingAlarmId == NO_ALARM) stopSelf(startId)`
   re-reads the field at fire time, and `handleStart` sets it before `ringer.start()` with no suspension
   or post in between, so there is no window in which an alarm is ringing and the field reads `NO_ALARM`
   (the one exception is M2-2.1's unreachable no-id case). *Belt:* even if the guard passed,
   `stopSelf(startId)` would be declined because a newer alarm's start id would be the most recent.
   `onDestroy`'s `mainHandler.removeCallbacksAndMessages(null)` is sufficient and is the right tool —
   it removes **all** callbacks and messages regardless of token, and because this message is posted
   synchronously inside `handleNotificationDeleted` it can never be posted *after* `onDestroy` the way
   backlog 2.3's coroutine-posted `finishDismiss` can. The new path is strictly cleaner than 2.3's.
3. **`ringingLabel` cannot be read stale, including across `START_REDELIVER_INTENT`** (:49, :108, :225).
   It is written on the one line after `ringingAlarmId = alarmId` (:107-108) with nothing between them,
   and read on the one path that has already established `ringingAlarmId != NO_ALARM`. The
   fresh-process worry does not materialise: `START_REDELIVER_INTENT` redelivers the **original**
   `ACTION_START` intent, `EXTRA_ALARM_ID` and `EXTRA_ALARM_LABEL` and all, so `handleStart` re-runs and
   re-writes both fields before any swipe can be delivered (the notification does not exist until
   `startForeground` has been called). In a fresh process `ringingAlarmId` is `NO_ALARM`, so the
   duplicate-start early return at :103-106 cannot be taken and the write cannot be skipped. And if the
   label genuinely is empty, `buildNotification` falls back to `R.string.alarm_default_label` (:399), so
   the worst case is a correct notification with the default title. Leaving the field uncleared on
   teardown is harmless for the same reason the read is safe.
4. **The re-post needs no foreground-start privilege it might lack.** `ForegroundServiceStartNotAllowedException`
   and the API 31+/34+ background-start restrictions apply to *promoting* a service that is not already
   in the foreground. The re-post happens while the service **is** foreground — the notification it is
   replacing is that service's own foreground notification, and nothing on this path calls
   `stopForeground` — so `startForeground` here is an update, permitted regardless of the app's process
   state. The type is unchanged (`mediaPlayback`, matched by `FOREGROUND_SERVICE_MEDIA_PLAYBACK` in the
   manifest), so no type-permission or `while-in-use` check is re-run, and `mediaPlayback` carries no FGS
   timeout. The delete `PendingIntent` is a `getService`, which means a `startService` from the system's
   send — allowed here because the app is a running foreground service at that moment in **both**
   branches (finding 1.9 holds the service foreground through the teardown window too). The `runCatching`
   is correct belt-and-braces rather than the load-bearing part; keep it, and keep the decision not to
   tear the alarm down when it fails.
5. **Programmatically removing the notification does not fire the delete intent, so there is no loop.**
   `finishDismiss`'s `stopForeground(STOP_FOREGROUND_REMOVE)` is an app-initiated cancel, which the
   notification service does not treat as a user dismissal and for which it does not send the delete
   intent. (And even if an OEM did send it, the handler would take the `NO_ALARM` branch and arm a
   12 s `stopSelf` on an already-stopped service — benign.) Posting a notification is likewise not a
   delete, so the re-post cannot trigger itself. No re-entrancy.
6. **The `encode`/`decode` format is fail-closed in every direction I could craft, and separator
   smuggling buys nothing** (ChallengeState.kt:223-298). *Shape:* `fields.size != FIELD_COUNT` refuses
   both truncation and extra fields, and `FIELD_COUNT = 12` is pinned against `encode`'s actual output by
   `assertEquals(12, fields.size)` (ChallengeStateTest.kt:439), so adding a field to `encode` without
   bumping the constant fails a test rather than silently refusing every restore. *Version:* a `1` that
   is not `1` refuses, pinned at :510. *Separators:* `\u001F` and `\u001E` cannot occur in any encoded
   value — problem texts are digits, spaces and `+ - × ÷` (Problem.text, ProblemGenerator.kt:19-26),
   `entry` is digits, `difficulty` is an enum name, and the rest are numbers — and
   `problem texts and the no repeat memory round trip verbatim` (:516) drives 13 problems per tier
   through the pair. A *crafted* string carrying separators is not an escalation: it only re-partitions
   fields the crafter already controls, and the field-count check refuses every partition but the exact
   one. *Values:* negative `draws`, negative or over-large `solved`, negative `consecutiveWrong`, a
   non-positive `questionAnswer`, an empty `questionText`, a non-digit or over-long `entry`, and a
   non-`0`/`1` error flag all refuse; `questionCount` is coerced only upward. **No crafted input can
   produce a session that is easier than earned:** fewer questions owed is impossible (`questionCount`
   only rises, and `solved` must be strictly below it), a forgiven failure count is impossible (every
   field is taken verbatim, and refusal resets to zero, which is *harder*), an already-solved session is
   refused outright (:277), and an "unlocked escape hatch" buys only the right to press a button and then
   watch a full fresh minute, because the deadline is never restored (:296, pinned at :417). The
   remaining gaps are `draws`-vs-`asked` (M2-2.2) and field length (M2-2.5), both unreachable and both
   non-easier.
7. **`ChallengeState` still has no default values, and the compile-time guard is real** (:27-50). Zero
   `=` defaults in the primary constructor, so adding a field is a compile error at `decode`'s and
   `start()`'s constructor calls. The guard's known limit is unchanged and now partly covered: it forces
   a decision at `decode` but not at `encode`, and the `encode` side is instead held by the field-count
   assertion (#6) and by `a session survives the Bundle round trip at every tier` (:376), whose
   `assertEquals(state, restored)` is data-class equality over all twelve properties — so **dropping** an
   existing field from `encode` fails it (the count check makes `decode` return null). An added field that
   no test exercises would still slip through the round trip; that is the honest residual, and it is
   smaller than Round 1's, where the restore test mirrored the reader instead of calling it.
8. **`escapeSecondsLeft` as the single ceiling is safe for the composable's capture semantics.**
   `EscapeCountdown`'s `LaunchedEffect(deadlineElapsedMs)` captures the `state` present when the effect
   launched, which would be a stale-capture hazard — except that `escapeSecondsLeft` reads only
   `escapeDeadlineElapsedMs`, which is the effect's key, so a stale capture and a fresh one cannot
   disagree. `remember(deadlineElapsedMs)` re-seeds the displayed value on a cancel-and-restart. The
   display still cannot run ahead of the entitlement: `completeEscape` re-asks
   `isEscapeComplete` (which re-checks `escapeUnlocked`) before dismissing, and falls back to
   `cancelEscape()` rather than leaving a dead countdown on screen.
9. **Module 1's invariants are undisturbed.** Wake lock: `handleNotificationDeleted` contains no
   `AlarmWakeLock` reference in either branch, and the only way it reaches a release is the guarded,
   delayed `stopSelf` → `onDestroy` → `stopAlarm()`, which is Module 1's own release path 2 against a
   non-reference-counted lock whose `release()` re-checks `isHeld` under `@Synchronized` — so the ten-path
   "exactly once" accounting is intact and no eleventh path was created. `isRinging`: the new handler
   never calls into `AlarmRinger`. The stale-dismiss guard at :140 is byte-identical. Finding 1.9's write
   window: nothing on the new path calls `stopForeground` or releases, and the only `stopSelf` is delayed
   past the ceiling (#2). Threading: `ringingAlarmId` and `ringingLabel` are plain fields, and every read
   and write — `onStartCommand`, `handleStart`, `silenceRinger`, `finishDismiss`, `stopAlarm`, the posted
   lambda — is on the main thread, so the absence of `@Volatile` remains correct.

**Consolidated open Module 2 backlog** (carried forward; none of these gate)

| # | Severity | Status | Where it goes |
|---|---|---|---|
| M2-1.7 | MINOR | Open | Volume re-assertion; already an implementation item in §4. Not reopened. |
| M2-1.8 | MINOR | Open | Second alarm mid-ring can downgrade the gate; root cause is Module 1 backlog 1.7. |
| M2-1.9 | MINOR | Open — **unfixable as §3 is written** | Platform-limited (ruling 3). Needs a §3 amendment, not code. |
| M2-1.10 | NIT | Open | `generate`'s last-resort branch hands out an unvalidated candidate. Unreachable. |
| M2-1.11 | NIT | Open | `domain/` imports `data.Difficulty`; the enum's natural home is `domain/`. |
| M2-1.12 | NIT | Open | `asked` is a `List`; a `Set` would document the no-repeat invariant. Now also relevant to M2-2.2. |
| M2-1.13 | NIT | Open | `submitAnswer` returns `true` when `challenge == null`. Unreachable (`canSubmit` gates the button). |
| M2-2.1 | MINOR | New | `NO_ALARM` sentinel doubles as "not ringing" in the swipe handler. |
| M2-2.2 | MINOR | New | `decode` does not relate `draws` to `asked`. |
| M2-2.3 | MINOR | New | One route back to the keypad, best-effort; cheap second route belongs in §4. |
| M2-2.4 | NIT | New | Tier-read KDoc overstates what survives the timeout. |
| M2-2.5 | NIT | New | No length bound on `questionText` / `asked` entries in `decode`. |
| M2-2.6 | NIT | New | Test hard-codes positional field indices into `encode`'s list. |
| M2-2.7 | NIT | New | §3's "notification is non-dismissible" is unmeetable as worded; met in substance. |

Coverage note, recorded rather than raised: the 98 tests still exercise no part of
`AlarmRingActivity` (M2-1.1's re-post path, `onSaveInstanceState`, `onNewIntent`, `onStop` and the two
dismiss call sites are all verified by reading only), and nothing pins the HARD division **quotient
floor of 4** — the validator now correctly accepts anything §3 allows, including `10 ÷ 2 = 5`, so the
floor is a construction choice with no test behind it. Both are inherent to code-review-only
verification of Android lifecycle code; neither gates.

**Acceptance criteria audit** (§3's seven boxes) — all seven met, unchanged from Round 1 except where
the fixes improved them. `assembleDebug` green and 98/98 tests pass with no `@Ignore` (independently
verified: ProblemGenerator 27, ChallengeState 34, NextTrigger 17, PlaybackRecovery 10, AlarmDayMask 10).
`ProblemGenerator` still has zero Android imports; `domain/` has zero; `ui/` has zero audio **code**
references (six prose hits only). Of the two §3 *deliverable* bullets Round 1 found unmet: the
notification one is now met in substance (M2-2.7 is about its wording), and the haptic one is confirmed
unmeetable as written (ruling 3), so §3 needs the amendment rather than the module needing a round 3.

**ADVANCE.** Zero open BLOCKERs, zero open MAJORs. M2-1.1 is genuinely closed and closed without
disturbing Module 1: the re-posted notification carries all three intents, the resurrection guard is a
live read, no start id leaks, the `START_REDELIVER_INTENT` guarantee survives the swipe, the delayed
stop cannot reach into finding 1.9's window or kill a live alarm, and the wake lock is never touched.
The five promoted minors are closed on the evidence above and the sixth, M2-1.9, is a verified platform
limitation that no round 3 could fix. Three new minors and four nits join the backlog and, per §1, do
not gate. **Module 2 is complete; Module 3 is next.** Two spec amendments are owed before or during
Module 3 and should be taken by whoever owns §3: the haptic bullet (ruling 3, replacement text supplied)
and the notification-dismissibility bullet (M2-2.7). The three new minors are worth folding into Module 3
where they are nearly free — M2-2.1 is one line in `AlarmService`, and M2-2.3's second route back is a
line in the alarm list Module 3 is building anyway.

---

## 4. Module 3 — Alarm management + onboarding

**Goal: make it a real app you can configure, and get the permissions and
battery settings right so it actually works.** Depends on Modules 1 and 2.

### Deliverables

**Alarm list** — all alarms with time, label, enabled toggle, difficulty badge;
create, edit, delete; empty state.

**Alarm edit screen** — time picker; label; repeat-day selector (now wired for
real); difficulty tier; question count; vibrate toggle; delete. Saving
reschedules.

**Permissions onboarding** — explains *why* before each request:
notifications → exact alarm → full-screen intent → battery optimization.
Never a bare system dialog with no context.

**Battery-optimization flow** — the most important screen in the app.
Detect manufacturer and deep-link to the right settings page, with
device-specific guidance for Xiaomi/MIUI, Oppo, Vivo, Huawei, Samsung, and a
stock-Android fallback. Must degrade gracefully when an OEM intent is absent.

**Self-diagnostic screen** — green/red status for every permission and battery
setting, so a user with a broken setup can see *why*. Plus "last fired
successfully" so silent failure is visible.

**Test alarm** — fires the real alarm 5 seconds out, so the user experiences it
once while awake.

**Defensive rescheduling** — reconcile all alarms on app open, in addition to
the Module 1 system-event receivers.

**Alarm-stream volume re-assertion** (from review M2-1.7, deferred out of Module 2). Observe
`STREAM_ALARM` while ringing and re-assert max when it is lowered. **Must filter the
ringer's own writes:** re-assert only when the observed index differs from the index
`AlarmRinger` last wrote, and never touch the durable pre-ring record that finding 1.1
created — a naive observer fires on the ringer's own `setStreamVolume` and races
`restoreAlarmVolume()` on dismiss. Silencing is not dismissing, so this is a hardening
item, not a correctness gate.

**Two near-free carry-overs from Module 2's review**, both one-liners the reviewer recommended
folding in here:
- **M2-2.1** — `AlarmService`'s swipe handler uses the `NO_ALARM` id sentinel as its
  "nothing is ringing" predicate. If an `ACTION_START` ever arrived without
  `EXTRA_ALARM_ID`, a swipe would skip the re-post *and* schedule a `stopSelf` that
  silences a live alarm 12 s later. Predicate on `ringer?.isRinging != true` instead.
- **M2-2.3** — the re-post is still the *single* route back to the keypad and it is
  best-effort (`runCatching` + `Log.e`). While the service is live, the alarm list must
  offer "an alarm is ringing — open it" as a second route.

**Remove the Module 1 debug trigger.**

### Acceptance criteria
- [ ] `./gradlew assembleDebug` exits 0
- [ ] Every permission request is preceded by an explanation
- [ ] OEM deep-links wrapped so a missing intent cannot crash the app
- [ ] Deleting an alarm cancels its pending `AlarmManager` registration
- [ ] Editing an alarm cancels the old registration before creating the new one
- [ ] Diagnostic screen reflects real permission state, not cached guesses
- [ ] Debug trigger gone
- [ ] No snooze UI anywhere

### Review Ledger — Module 3
*Review agents append rounds here. Do not edit by hand.*

### Round 1 — 2026-10-09

**Verdict:** 0 blockers, **0 majors**, 11 minors, 7 nits. **ADVANCE.** This is the most careful of the
three dev rounds. The one change that could have re-opened a closed MAJOR — volume re-assertion inside
`AlarmRinger`, Module 1's thrice-reviewed audio code — holds under every interleaving I could construct,
on both of its claimed guards and for the right reasons. The dev's deliberate deviation from Round 2's
`M2-2.1` prescription is **correct and my prescription was incomplete**; see ruling 1. Every one of §4's
eight acceptance boxes is genuinely met and all eleven deliverables are present. The eleven minors are
real but none of them can stop an alarm ringing; the three the dev named itself are ruled acceptable as
backlog. I considered promoting the battery-row severity policy (M3-1.3) to MAJOR and talked myself out
of it on the dev's own reasoning — written up in full below, because the reasoning matters more than the
label.

| # | Severity | File:line | Finding | Why it matters |
|---|---|---|---|---|
| M3-1.1 | MINOR | MainActivity.kt:74-79 (with AlarmRepository.kt:90-93, :110-126) | The `onCreate` reconcile cancels **every** registration, including the test alarm that was just armed — and because the test row is deliberately `enabled = false`, `AlarmRescheduler` cancels it and never re-books it. Scenario: user taps "Test the alarm now", sees "Ringing in 5 seconds…", leaves the app and relaunches it from the launcher inside those five seconds (or the activity is destroyed by an aggressive ROM and recreated with `savedInstanceState == null`). The registration is cancelled, nothing fires, and the user's conclusion is "this phone kills my alarms". The `savedInstanceState != null` guard covers rotation, which is what its KDoc argues, but not a fresh create. | A **false negative on the single most diagnostic feature in the app** — the one button whose entire purpose is to tell the user whether their device eats alarms. It fails in the direction that makes the user distrust a working install, and it is the only place where §4's "defensive rescheduling" and §4's "test alarm" actively fight. One-line fix: skip `AlarmRepository.TEST_ALARM_ID` in `AlarmRescheduler.rescheduleAll`, or have the reconcile leave a test row alone while its booked instant is still in the future. |
| M3-1.2 | MINOR | AlarmRepository.kt:110-126 with MainActivity.kt:204-219 | Nothing stops the test alarm being armed **while a real alarm is ringing**, and it is now one tap from the list screen that M2-2.3's banner deliberately sends people to. Five seconds later `handleStart(-2)` reuses the live ringer and overwrites `ringingAlarmId`, which is Module 1 backlog **1.7** / Module 2 backlog **M2-1.8** exactly: one activity, one ringer, one session for two alarms. Dismissing the test alarm then silences the real one, and `disableIfOneShot` only ever sees `-2`, so a real one-shot stays `enabled` and fires again tomorrow (backlog **1.8**). | M2-1.8 closed with an explicit request that "Module 3's alarm list, which is what makes two same-minute alarms easy to create, does not land on top of it unnoticed". The list itself is fine — creating two same-minute alarms still takes deliberate effort — but the test button reduces the overlap to a single tap. The guard is free: `AlarmListScreen.kt:134` already polls `AlarmService.ringingAlarm()` for the banner, so disable the test button while it is non-null. |
| M3-1.3 | MINOR | Diagnostics.kt:74 with strings.xml:63, :137, :143 | `Check.BATTERY` is `Status.FAIL` unconditionally, and FAIL is the only thing that raises the home-screen banner (`AlarmListScreen.kt:85`, `Diagnostics.kt:101`). So a **fresh install in its default state on stock Android** shows a permanent red banner reading "Something in your setup will stop alarms from ringing" plus a row reading "The system can freeze this app overnight and the alarm will never fire". On a Pixel neither sentence is true: `USE_EXACT_ALARM` is granted at install, `setAlarmClock()` is exempt from Doze and App Standby (BRAINSTORM §6 threat 1 is listed as *already mitigated by it*), and `isIgnoringBatteryOptimizations == false` is the normal working default for every app on the device. `PowerManager` also cannot distinguish "Optimized" (harmless default) from "Restricted" (the state that genuinely blocks a background FGS start), so the flag is not a clean proxy for the dangerous state either. | This is the harm the dev itself identified one function lower — "a permanent banner … would train the user to ignore the banner" (`Diagnostics.kt:96-100`) — and it burns the app's only alert channel, including for `EXACT_ALARM`, the one row that really is fatal. **Why this is not a MAJOR:** I drafted it as one and withdrew it. The obvious fix — pass the already-computed `SystemState.vendor` (`:86`, dropped on the floor at `:43-52`) into `Facts` and WARN on `Vendor.STOCK` — is *worse* than the status quo, because keyword vendor detection (`OemBattery.kt:44-56`) misses Tecno/Infinix, Nothing, Asus and Sony, so it would hand a false **green** to exactly the unlisted ROMs that kill alarms. For an alarm clock, red-when-unsure is the correct asymmetry and the dev chose it deliberately (§7). What is actually defective is narrower: the *copy* states as fact ("will never fire", "will stop an alarm from ringing") what is only true on some ROMs, and the banner fires on a row that is red by default everywhere. Fix the two strings to name the ROM families and the uncertainty, and consider excluding `BATTERY` from `needsAttention` while keeping the row red. |
| M3-1.4 | MINOR | AlarmRinger.kt:288-290 | The re-assert has **no budget**. `PlaybackRecovery` bounded audio recovery for precisely this reason (finding 2.1), and nothing bounds this. Scenario: a ROM volume-ceiling service or an accessibility service that enforces a cap on `STREAM_ALARM` honours our `setStreamVolume(max)` and then writes its own value back; that write persists to `Settings.System`, notifies us, and we write max again. The `current == lastWrittenVolume` filter cannot terminate this, because the observed value genuinely differs every time. | Not a spin and not a hang: every round trip is paced by `AudioService`'s own persist delay, so the cost is a stream of flash writes for the length of the ring (order of 2/s), not a wedged main thread — which is why this is MINOR. Still worth the same treatment `restartPasses` gets: cap the re-asserts per ring and then let the silence stand. §4 already says the honest contract here is best-effort, and silencing is not dismissing: the gate still stands and the row is not auto-disabled. |
| M3-1.5 | MINOR | AlarmHistory.kt:21-29 with AlarmService.kt:125-126 | "Last fired" means *the service came up*, not *sound was produced*. The one case §4's row therefore cannot see is `PlaybackRecovery.Plan.GiveUp`: the alarm fires, all three sources fail, the budget is spent, the user sleeps through — and the diagnostics row is green with a timestamp on it. | **Does not undermine what §4 wanted.** §4 asks for the row "so silent failure is visible", and the dominant silent failure by a wide margin — *never fired at all*, because an OEM manager ate it or exact alarms were never permitted — is exactly what this row distinguishes, with a WARN and copy that points the user at the test alarm. The dev's reasoning for not closing the rest (it needs a consumer for `isRinging`, inside thrice-reviewed audio code) is sound. But the cheapest honest closure is inside the file that already knows the answer: have the `GiveUp` branch at `AlarmRinger.kt:433-443` write a second `AlarmHistory` key and let the row read "fired, but the sound failed". That would also be the first real consumer of backlog **3.2** in three modules. |
| M3-1.6 | MINOR | AlarmListScreen.kt:135-140 | The 1 Hz poll sits in a bare `LaunchedEffect(Unit)`, so it is scoped to **composition, not lifecycle**: it keeps ticking while `MainActivity` is merely stopped — behind the lock screen, behind another app — until the OS freezes the process. | Correctness is fine (see "Verified correct" #4: it cannot miss a live ring and cannot show a stale one), so this is purely a cost finding, and the process freezer bounds it in practice on the target API range. It is still a 1 Hz wakeup the screen does not need: `LifecycleResumeEffect`, or `repeatOnLifecycle(RESUMED)`, is the same number of lines. |
| M3-1.7 | MINOR | AlarmRepository.kt:110-126 | The test alarm hard-codes `Difficulty.MEDIUM` and `questionCount = 1`, so a user who configured HARD × 3 never rehearses the gate they actually set. BRAINSTORM §3.1 puts "Test it now" (step 5) *after* "Pick difficulty. Pick how many questions" (step 4) precisely so the rehearsal is the real thing. | The firing *chain* is what matters for OEM detection, and that is what the dev optimised for, which is why this is MINOR rather than a missed deliverable. But "so the user experiences it once while awake" is partly about discovering that HARD × 3 at 6am is more than they bargained for — better now than on the morning it matters. Two fields: seed the test row from the alarm being tested. |
| M3-1.8 | MINOR | OemBatteryTest.kt:106-114 with AndroidManifest.xml:66-79 | The test the manifest comment points at is tautological: it derives the expected set from `targetsFor` and compares it to `allPackages()`, which *is* `targetsFor` flat-mapped. The ten `<queries>` packages do match `allPackages()` today (I checked by hand: 2 Xiaomi + 3 Oppo + 2 Vivo + 1 Huawei + 2 Samsung = 10, exactly the manifest list), and `allPackages()` has no production caller, so the only thing holding them in sync is the comment. | Scenario: a later round adds a Vivo target in a new package. Every test stays green, API 30+ hides the package from resolution, `firstResolvable` reports "absent", and that deep link silently never appears on any device — the exact failure the `<queries>` block exists to prevent, with no signal. The test is on the unit-test source path and can read `app/src/main/AndroidManifest.xml` directly, or assert the literal ten-element set. |
| M3-1.9 | MINOR | MainActivity.kt:78 with AppSettings.kt:17-22 | `AppSettings.onboardingSeen` does a first-access `SharedPreferences` load — blocking disk IO — on the main thread inside `onCreate`. | Same class as backlog **2.5**, lower stakes: launch jank rather than a delayed `ACTION_DISMISS`, and one small CE-storage file. Recorded because this module got the harder case right (`MainActivity.kt:161-167` deliberately pushes the DE-storage last-fired read to `Dispatchers.IO` and says why), so the easy one is an oversight rather than a policy. |
| M3-1.10 | MINOR | MainActivity.kt:65-68 | Screen and editor state live in plain `mutableStateOf` activity fields with nothing saved, and `MainActivity` declares no `configChanges` and no fixed orientation (`AndroidManifest.xml:87-94`). A rotation therefore destroys the activity, drops a half-finished alarm edit and lands the user back on the list. | Module 2 took the trouble to persist the challenge session into the activity `Bundle` because losing it mid-ring would be serious; losing a half-typed alarm label is not in that class, which is why this is MINOR. But "five screens in one `sealed interface`" is only cheaper than a nav library if the state it replaces is saved — `rememberSaveable`, or `onSaveInstanceState` for `screen`, is a handful of lines. |
| M3-1.11 | MINOR | AlarmRepository.kt:90-93 against :54-78 | `reconcileAll` reads `getAll()` and then cancels and re-schedules from that snapshot, with no mutual exclusion against a concurrent `save`, `setEnabled` or `delete`. A toggle-off that commits between the snapshot and the loop's `schedule` call is re-registered: the row says disabled and a registration still fires. | Structural, logged the way backlog **2.7** was. I found no reachable path — the race window is the few milliseconds of `rescheduleAll` at `onCreate`, before the list has even drawn its first frame, and the list needs its own DB read to render a toggle at all. Recorded because `AlarmRepository` is advertised as "the single place where a row and its registration change together" (`:12-13`) and `reconcileAll` is the one method that breaks that single-writer property. |
| M3-2.1 | NIT | AlarmService.kt:130-136 | The `recordFired` KDoc claims "never on a duplicate or redelivered start". The in-process duplicate guard (`:103-105`) does that; cross-process `START_REDELIVER_INTENT` — the only case in which redelivery actually happens — lands in a fresh instance where `ringingAlarmId` is `NO_ALARM`, skips the early return and re-stamps. Harmless (a timestamp moves a few seconds), but the documented guarantee is wider than the code's. |
| M3-2.2 | NIT | AlarmScheduler.kt:45 and AlarmDao.kt:22 | Both still document themselves as serving "the Module 1 debug trigger", which this module removed. Their real user is now the reserved test-alarm row. |
| M3-2.3 | NIT | BatteryScreen.kt:88 | A failed **stock** launch sets `oemRouteFailed`, which hides the **OEM** button. Practically unreachable — `stockBatterySettings` ends in `appDetails`, which resolves and starts on every Android build, so `ok` is effectively always true — but two independent routes should not share one flag. |
| M3-2.4 | NIT | DiagnosticsScreen.kt:74 | `AlarmService.ringingAlarm()` is read straight in composition with no state wrapper, so unlike the list's poll this card appears only when something else recomposes the screen. In practice `onResume` → `refreshAlarms` → a new `systemState` instance does recompose it, so the route is there when it matters. |
| M3-2.5 | NIT | AlarmScheduleDecisionTest.kt:50-62 | "If an entry point is added without a cancel, this fails" is not true of a hand-enumerated list. The invariant it claims to pin would need the decision functions enumerated reflectively, or a single `Decision` factory everything routes through. |
| M3-2.6 | NIT | AlarmScheduler.kt:84-87 | `reschedule` now has no caller: `AlarmRepository` composes `cancel` + `schedule` itself through `AlarmScheduleDecision`, and `AlarmRescheduler` does the same inline. Dead API in the one file that must not drift. |
| M3-2.7 | NIT | AlarmHistory.kt:69 | `lastFiredAlarmId` has no caller; the diagnostics row shows only the timestamp. Either surface it ("last fired: Work alarm, 06:00") or drop it — note the reserved test row makes `-2` a possible value, which `userAlarms()` cannot resolve to a label. |

---

#### Ruling 1 — the M2-2.1 deviation: **the dev is right, and my Round 2 prescription was incomplete**

Round 2 prescribed predicating `handleNotificationDeleted` on `ringer?.isRinging != true`. The dev kept the
id sentinel as a second clause: `ringer?.isRinging != true && alarmId == NO_ALARM`
(`AlarmService.kt:238`). **I agree with the dev. My guidance was narrower than it should have been, and
following it literally would have traded one hole for another.** The evidence, case by case:

1. **The bug M2-2.1 was raised about is closed.** An `ACTION_START` with no `EXTRA_ALARM_ID` rings with
   `ringingAlarmId == NO_ALARM`. Under the old single-clause predicate the handler took the dead-alarm
   branch: no re-post (M2-1.1 re-opened for that ring) and a delayed `stopSelf` armed against a live
   alarm. Under the conjunction, `isRinging` is true, so the branch is not taken and the notification is
   re-posted. Fixed, and fixed by the clause I asked for.
2. **My prescription regresses the opposite case, and the dev's argument for it is correct.** After
   `PlaybackRecovery.Plan.GiveUp` (`AlarmRinger.kt:433-443`) `startPending` is cleared and `audioAlive`
   is already false, so `isRinging` is honestly `false` while `ringingAlarmId` still holds a live alarm
   that the user is standing in front of. With `isRinging` alone, a swipe in that window takes the dead
   branch: no re-post, **and** `mainHandler.postDelayed` arms a `stopSelf` whose own guard
   (`:251`) would also pass 12 s later — tearing down the foreground service, pulling the notification
   and releasing the wake lock under a user who is mid-problem on a silent-but-live alarm, with the
   notification route gone. That is M2-1.1 again, reached through my own fix. The dev's reading of
   backlog 3.2 is exactly right.
3. **The conjunction introduces no new hole.** It is strictly *narrower* than either clause alone, so
   the only thing it can cost is failing to take the dead branch when the alarm really is dead — i.e.
   leaving a start id outstanding. That costs nothing: `stopSelf(startId)` is a no-op unless `startId`
   is the most recent delivery, the swipe delivery is newer than the start, and the dismiss that follows
   is newer still, so the dismiss's own `stopSelf` stops the service. For both clauses to read "dead"
   while something is live you would need `audioAlive == false`, `startPending == false` **and**
   `ringingAlarmId == NO_ALARM`, which is precisely the dismiss-teardown window the branch was written
   for (`silenceRinger`, `:272-277`, clears the id and nulls the ringer together).
4. **The delayed `stopSelf` guard was changed to match** (`:251`: `ringer?.isRinging != true &&
   ringingAlarmId == NO_ALARM`) and it re-reads `ringingAlarmId` live rather than closing over the
   captured `alarmId`, so an alarm that started during the 12 s delay keeps the service.

Spec note for whoever maintains §3/§4: the correct general statement is *"predicate on the ringer **and**
the id — on neither of them alone"*, because `isRinging` and `ringingAlarmId` fail in opposite
directions. That sentence is what Round 2 should have written.

#### Ruling 2 — the volume watcher: **safe, on both guards, for the stated reasons**

The highest-risk change in the module. I attacked it on every axis the review brief names and it holds.

1. **Self-write filter — verified, and tighter than claimed.** `lastWrittenVolume` is assigned *before*
   every ringer write, in both writers: `forceMaxAlarmVolume` (`:172` before `:173`) and
   `restoreAlarmVolume` (`:210` before `:211`). `reassertAlarmVolume` returns early on
   `current == lastWrittenVolume` (`:281`). The stronger property, which the KDoc does not claim: during
   a ring `lastWrittenVolume` can only ever hold `max` or a value the adopt branch (`:283-287`) read as
   `>= max`, so the filter is **structurally incapable of swallowing a user's lowering** — it can only
   ever match a value at or above maximum. The one failure mode it does not cover is benign: if
   `setStreamVolume` throws under a DND policy, `lastWrittenVolume` is already `max` while the stream is
   not, so the next notification simply retries and fails again in the `runCatching` (`:291-295`).
2. **Main-looper dispatch + `ringRequested` gate — verified, and the ordering claim is correct.**
   The observer is constructed with `Handler(Looper.getMainLooper())` (`:259`), so `ContentObserver`'s
   `dispatchChange` posts to the main looper rather than running inline on the binder thread.
   **Can the watcher undo `restoreAlarmVolume()` on dismiss?** No, and the dev's reasoning about *why*
   unregistering is insufficient is right: `unregisterContentObserver` removes the transport but does
   **not** drain messages already posted to the observer's handler, so a callback that was enqueued
   before `stop()` ran still executes. What saves it is that it cannot execute *inside* `stop()`:
   `stop()` itself runs on the main thread (traced below), so the single-threaded looper serialises the
   queued callback strictly after `stop()` returns, by which time `ringRequested` is false (`:141`, set
   **before** `stopWatchingAlarmVolume` at `:147` and before `restoreAlarmVolume` at `:153`) and the
   callback bails at `:277`. The flag-first ordering is what closes it; I confirmed the flag write
   precedes both the unregister and the restore.
3. **Main-thread-only is real, so Round 2's `@Volatile` analysis is intact.** Traced every caller:
   `start` and `shutdown` are called only from `AlarmService.handleStart` (`:111`), `silenceRinger`
   (`:273`) and `stopAlarm` (`:294`), which are reached only from `onStartCommand` and `onDestroy` —
   service lifecycle callbacks, main thread. `stop()` is private and reachable only via `shutdown()`.
   Nothing calls into the ringer from `AlarmWork.scope`; `finishDismiss` is bounced back through
   `mainHandler.post` (`AlarmService.kt:190`) and does not touch the ringer at all. So `lastWrittenVolume` and
   `volumeObserver` are written and read on exactly one thread and need no `@Volatile`, while
   `ringRequested` stays `@Volatile` because the audio `HandlerThread` reads it. The threading KDoc
   (`:86-88`) is accurate.
4. **The re-assert cannot spin in the normal case.** Write max → `Settings.System` notification →
   `current == lastWrittenVolume` → early return. The platform helps too: `SettingsProvider` does not
   notify on an unchanged value, so a write that is clamped or rejected produces no further callback.
   The one unbounded shape is an external component that honours our write and then reverts it — logged
   as **M3-1.4**, and paced by `AudioService`'s persist delay rather than being a CPU spin.
5. **The durable pre-ring record (finding 1.1) cannot be corrupted by this path.** Verified by
   enumeration, not by trusting the comment: `rememberVolumeBeforeRinging` has exactly one caller,
   `forceMaxAlarmVolume` (`:169`), which has exactly one caller, `start()` (`:122`); `volumeStore`
   appears at `:49`, `:194`, `:198`, `:204`, `:212` and nowhere in the watcher, which reads and writes
   `AudioManager` only. `restoreAlarmVolume` is still called from `stop()` alone.
6. **Two alarms back to back.** Dismiss A → `silenceRinger` nulls the ringer, so B gets a fresh
   `AlarmRinger` with its own `ringRequested`; any callback still queued against A's observer sees A's
   flag false forever. Alarm B *without* a dismiss (same ringer reused): `startWatchingAlarmVolume`
   early-returns on the existing observer (`:257`) so there is never a second registration, and
   `rememberVolumeBeforeRinging` sees the surviving record and keeps it (`:194-197`) rather than
   recording the maxed value — finding 1.1's guard doing its job through the new path.
7. **DND `SecurityException` on the re-assert write.** Caught at `:291`, logged, no state corruption;
   the record survives, so the dismiss still restores. Surfaced to the user by the `ALARM_VOLUME`
   diagnostics row, which is the remediation backlog **1.12** asked for.
8. **`START_REDELIVER_INTENT` into a fresh process mid-ring.** New ringer, `lastWrittenVolume` back to
   `NO_RECORDED_VOLUME`, `volumeObserver` null; the DE-storage record is present and is kept, so the
   user's real pre-ring volume survives and a later dismiss restores it. If `AudioManager` is
   unavailable, `forceMaxAlarmVolume` and `startWatchingAlarmVolume` both early-return and no observer
   exists — no half-armed state.
9. **No leak.** The observer's lifetime is exactly the ringer's: every `ringer = null` is paired with
   `shutdown()` in the same two methods, and a ringer is never restarted after `stop()`, so there is no
   path that leaves a registered observer with `ringRequested == true`.

Honest residual, correctly documented by the dev at `:250-254`: the re-assert lands a fraction of a
second after the user's drag because `AudioService` persists on a delay, and a ROM that does not persist
the alarm stream never notifies at all. Best-effort is the right contract for a hardening item.
One further residual the dev did not note: the observer is registered on `Settings.System.CONTENT_URI`
with `notifyForDescendants = true`, so *every* `Settings.System` write during a ring costs a main-thread
hop and a `getStreamVolume` binder call. Low frequency, bounded by the ring, and the filter makes it
harmless — recorded here rather than as a finding.

#### Ruling 3 — the companion mirrors, `recordFired`, the reserved `-2` row

- **`liveAlarmId` / `liveAlarmLabel` are a genuine status mirror.** Written in exactly three places
  (`:109-110`, `:276`, `:297`), all on the main thread, all alongside the instance fields they mirror;
  read in exactly two (`AlarmListScreen.kt:134/137`, `DiagnosticsScreen.kt:74`), both on the main
  thread, both through `ringingAlarm()`. **Cannot be read stale or torn:** the id and the label are
  written in the same main-thread statement pair and the only reader is also on the main thread, so no
  interleaving exists; and `ringingAlarm()` gates on the id, so the deliberately-uncleared label is
  only ever read while the id is set, with every id-setting write also setting the label. Nothing in
  the service's lifecycle reads them — the stale-dismiss guard at `:164` still uses the instance
  `ringingAlarmId`, the resurrection guard at `:237` still uses the instance field, and `AlarmWakeLock`
  is untouched (three call sites, unchanged: `AlarmReceiver.kt:34/43`, `AlarmService.kt:101`, `:287`,
  `:299`). The ten-path wake-lock accounting is undisturbed, and the mirrors cannot disturb it because
  nothing branches on them.
- **`recordFired` cannot reach the catch that cancels the alarm.** It sits outside the `try`
  (`:125-126`) and is itself wrapped in `runCatching`, so finding **1.14**'s rule holds; the only work
  it does synchronously is `applicationContext` and a `launch` into a `SupervisorJob` scope, and the
  body is wrapped again inside the coroutine (`:142-143`). **No `SharedPreferences` IO on the main
  thread:** `AlarmWork.scope` is `Dispatchers.IO`, `AlarmHistory.prefs` is first touched there, and the
  write is `apply()` rather than the `commit()` that backlog **2.5** is about. Backlog **2.7** is
  neither fixed nor worsened — `rescheduleIfRepeating` at `:122` is still the unguarded synchronous
  prologue outside the try, and the new line was correctly placed after it *with* a guard.
- **The reserved `-2` row collides with nothing.** `AlarmScheduler.requestCode` is `alarmId.toInt()`
  → `-2`, distinct from `SHOW_INTENT_REQUEST_CODE = -1` and `REQUEST_NOTIFICATION_DELETED = 0x4A1B`;
  the `-1` collision would not matter anyway, since `showIntent()` is a `getActivity` `PendingIntent`
  and the fire intents are `getBroadcast`, which are different identities. The data URI
  `mathalarm://alarm/-2` is unique. `NO_ALARM` is `-1L`, so every sentinel comparison in `AlarmService`
  (`:139`, `:164`, `:171`, `:238`, `:251`, `:285`, `:325`) treats `-2` as a real alarm, which is what
  the test alarm needs: it fires, stamps "last fired", reads its row for the tier, is not repeating so
  nothing is re-booked, and `disableIfOneShot` cancels and disables it. `userAlarms()` filters it
  (`AlarmRepository.kt:44`), and the editor can therefore never open it. The schema is
  `INTEGER PRIMARY KEY AUTOINCREMENT` (committed `1.json`), so ids are positive and `-2` can neither
  collide nor move `sqlite_sequence`; Room's documented "0 means not-set" rule on an `autoGenerate`
  primitive key is what lets `save()` keep using `upsert` for new rows and bind `-2` literally here.
- **`AlarmRepository` is a real single mutation point, with one documented exception.** Every row
  mutation in the app goes through it or through `AlarmService.disableIfOneShot`, which changes the row
  **and** cancels the registration in the same function (`:553-559`) — consistent with the invariant
  even though it predates the class. `AlarmRescheduler` touches registrations only, never rows.
  `cancelFirst` is honoured at all three `applyDecision`/`delete` call sites (`:60`, `:68`, `:76`) and
  the one non-`Decision` path, `scheduleTestAlarm`, cancels by hand before scheduling (`:124-125`).
  The exception is `reconcileAll`, which is the single-writer break logged as **M3-1.11**.
- **`SystemState.query` genuinely re-queries.** No field is memoised, no `SystemState` instance is
  cached, and `MainActivity.onResume` → `refreshAlarms` → `query` runs on every return from a Settings
  page, a permission dialog or an OEM screen, off the main thread (`MainActivity.kt:163-167`). The one
  `remember`ed system fact, `hasOemBatterySettings` (`BatteryScreen.kt:57`), is a ROM property that
  cannot change while the process lives — not a permission.
- **OEM resolution is correct.** `resolveActivity(intent, 0)` **without** `MATCH_DEFAULT_ONLY` is right:
  these are explicit components in other packages with no `CATEGORY_DEFAULT`, and
  `MATCH_DEFAULT_ONLY` would reject every one of them. The `<queries>` block is genuinely required —
  API 30+ hides other packages from resolution entirely, so without it every check would report
  "absent" on every device. The ten declared packages match `OemBattery.allPackages()` exactly today;
  what is missing is anything that keeps them matching (**M3-1.8**). And because `resolveActivity`
  reports a non-exported activity as resolvable, the second wrapper in `launch` is not belt-and-braces,
  it is load-bearing.
- **`AlarmHistory` as DE-storage prefs rather than a column: sound call.** Backlog **2.6** is exactly
  right that the migration gate is runtime-only, and the failure it describes — a forgotten
  `Migration(1, 2)` compiling green and then stopping every reschedule after an update — is a silent,
  total, post-update alarm failure. One timestamp does not buy that risk. DE storage is the correct
  choice for the same reason the alarm table uses it, and `version = 1` / `MIGRATIONS = emptyArray()` /
  the committed `1.json` are all untouched. The semantics gap is **M3-1.5** and does not undermine what
  §4 wanted.

---

#### Verified correct

1. **The volume watcher, on both guards and against every interleaving I could build** — ruling 2
   above. Self-write filter structurally incapable of swallowing a user lowering; flag-before-unregister
   ordering closes the dismiss race that unregistering alone cannot; the durable pre-ring record from
   finding 1.1 is provably untouched by this path; main-thread-only confirmed by caller trace, so
   Round 2's `@Volatile` analysis stands.
2. **The M2-2.1 deviation** — ruling 1. Both clauses, correct, and the single-clause prescription I gave
   in Round 2 would have re-opened M2-1.1 through the `GiveUp` window.
3. **M2-2.3's second route is real and byte-identical.** `AlarmService.openRingingIntent` (`:536-545`)
   is the single factory used by the notification's full-screen *and* content `PendingIntent`s
   (`:420-425` via `ringIntent`, `:402-403`), by the list banner and by the diagnostics card — same
   component, action, flags and extras — so re-entering the `singleTask` activity cannot look like a
   different alarm to `onNewIntent` and cannot wipe a session with questions already solved. It also
   quietly fixes the worst case of a denied notification permission: the launcher → list → "Open it"
   path runs `startActivity` from a *visible* activity, so it is not subject to the
   background-activity-launch block that silently drops `launchRingActivity` (finding 1.5). That is the
   first route back to the keypad in three modules that does not depend on the notification existing.
4. **The ringing banner cannot miss a live ring or show a dead one.** `liveAlarmId` is set inside the
   `try` before `start(vibrate)`, so a throw there reaches the catch → `stopAlarm` → cleared; the
   normal dismiss clears it in `silenceRinger`; `onDestroy` clears it in `stopAlarm`; process death
   clears it with the process. The only "stale" case is deliberate and correct: after `GiveUp` the id
   stays set, so a silently-failed ring **keeps** its route back rather than losing it.
5. **`recordFired` is placed and guarded correctly** — outside the cancelling catch, double-wrapped, on
   `Dispatchers.IO`, `apply()` not `commit()`, and after the duplicate-start early return so an
   in-process duplicate does not re-stamp. Findings **1.14** and backlog **2.5** both respected.
6. **Module 1's structural rule survived a module that added five screens.** `ui/` holds no
   `MediaPlayer`, `AudioManager`, `AudioAttributes`, `Vibrator`, `Ringtone`, `WakeLock` or `AlarmRinger`
   reference; the alarm-stream *read* the diagnostics screen needs goes through `AlarmStreamStatus` in
   `alarm/` (read-only by construction, and explicit that the stream has exactly one writer), reached
   through `SystemState` in `system/`. That is the right shape, and it is how finding 1.1's
   single-writer invariant stays true.
7. **`AlarmScheduleDecision` is the right amount of abstraction for the two scheduling criteria.** Pure,
   no Android imports, `cancelFirst` unconditionally true, modelled as data so a test can assert it —
   and it keeps `AlarmRepository` from ever reconstructing a `PendingIntent`, which is the classic way a
   silent `AlarmManager` cancel bug gets written. Six tests pin the behaviour §4 asks for.
8. **Delete ordering is deliberately right.** `delete` cancels *before* `dao.delete` even though
   cancelling after would also work today, and says why (`:17-21`) — the registration is keyed on the
   id, so the current order cannot rot into a leaked registration for a row that no longer exists.
9. **The test alarm's row design is careful.** Stored `enabled = false` so no reconcile or boot can
   resurrect it for tomorrow at the same wall-clock minute, under a reserved negative id so it can
   never collide with an `AUTOINCREMENT` id and is filtered out of the list, and still booked because
   `scheduleAt` does not consult `enabled`. The failure report (`list_test_alarm_failed`) names the
   exact-alarm denial, which is the most useful thing a failed test can say.
10. **Permission requests are all explained first, with no side-effect requests.** Exactly one
    `rememberLauncherForActivityResult` exists in the whole app (`OnboardingScreen.kt:56-58`) and it is
    launched only from step 1's own button, under a card stating the consequence. Nothing is requested
    on screen entry or as a navigation side effect, and the onboarding copy at
    `strings.xml:100` even names the recovery path ("you would have to open the app to stop it") that
    #3 makes real.
11. **Nothing gates scheduling on any permission.** Module 1 round 2's pushback #1 is honoured
    throughout: onboarding is skippable per-step and whole-screen, the editor warns and still saves,
    and `Diagnostics` keeps notifications and FSI at WARN. That ruling was right and this module
    implemented it rather than drifting back towards the hard gate.
12. **`Diagnostics` is pure and its policy is written down and tested.** Twelve tests, including the
    stable-order and one-row-per-check invariants, and the `fullScreenIntentApplicable` omission below
    API 34 rather than a spurious pass.
13. **`OemBattery` is pure, sub-brands are mapped to ROM families, and a wrong guess is free** — the
    target fails to resolve and the user gets the stock route plus written steps that are shown
    unconditionally, not only on failure.
14. **The notification's delete `PendingIntent` carries no alarm id and a fixed request code**, so the
    re-post handler reads the alarm ringing *now* rather than whichever one the `PendingIntent` was
    minted for — still correct after this module's changes.

---

#### §4 acceptance criteria audit — all eight met

| Box | Verdict | Evidence |
|---|---|---|
| `./gradlew assembleDebug` exits 0 | **Met** | Independently verified: green, zero warnings; 126/126 tests pass (98 pre-existing unaltered + 28 new), no `@Ignore`. |
| Every permission request preceded by an explanation | **Met** | Verified correct #10. One runtime request in the app, behind its own explained card; every Settings deep link likewise. No request on entry or as a navigation side effect. |
| OEM deep-links wrapped so a missing intent cannot crash | **Met, twice over** | `firstResolvable` (`SettingsIntents.kt:42-47`) *and* a catch-all `launch` (`:55-67`), which is load-bearing rather than redundant because `resolveActivity` reports non-exported activities as resolvable. `oemBatterySettings` appends the stock routes ending in `appDetails`, so the candidate list is never empty, and a `false` return degrades to written per-OEM steps that are shown unconditionally. |
| Deleting an alarm cancels its pending registration | **Met, and unit-tested** | `AlarmRepository.delete` cancels before `dao.delete`, via `AlarmScheduler.cancel` only; `forDelete().scheduleAfter == false`. Pinned by `AlarmScheduleDecisionTest`. |
| Editing cancels the old registration before creating the new one | **Met, and unit-tested** | `applyDecision` (`:130-136`) cancels then schedules, sequentially on one IO thread; `cancelFirst` true at every call site; `cancel` reuses Module 1's thrice-verified `PendingIntent` identity rather than rebuilding an intent. |
| Diagnostics reflects real permission state, not cached guesses | **Met** | `SystemState.query` caches nothing, no instance is memoised, and `onResume` re-runs it off the main thread on every return from Settings. |
| Debug trigger gone | **Met** | Grep clean; two stale KDoc mentions only (**M3-2.2**). |
| No snooze UI anywhere | **Met** | Zero case-insensitive `snooze` hits in code or resources outside one prose line in `Alarm.kt:13` stating there are none. |

**Eleven deliverables:** alarm list ✓ (time, label, toggle, difficulty badge, create/edit/delete, real
empty state); editor ✓ (time picker, label, repeat days wired through `daysToMask`/`maskToDays`, tier,
question count clamped 1..10, vibrate, delete with confirmation, saving reschedules); onboarding ✓ in
§4's order, each step explained first; battery flow ✓ (five vendors plus sub-brands, stock fallback,
written steps always visible, graceful degradation); diagnostics ✓ (seven rows plus last-fired);
test alarm ✓ (with **M3-1.1/1.2/1.7** against it); defensive rescheduling ✓ but first-create only
(**M3-1.1**); volume re-assertion ✓ with both required guards (ruling 2); **M2-2.1** ✓ (ruling 1);
**M2-2.3** ✓ on two screens with a byte-identical intent; debug trigger removed ✓.

---

#### The three stated gaps — rulings

The dev named all three rather than hiding them, which is the right behaviour and made them quick to
adjudicate. **All three are acceptable as backlog; none gates.**

1. **Backlog 2.10 — no opportunistic volume restore outside a ring cycle. Acceptable, and the dev's
   reason is better than my original fix suggestion.** Round 3's cheap fix proposed calling
   `restoreAlarmVolume()` on app launch; the dev objected that this puts an audio-stream *write* on a
   UI-triggered path next to `ui/`, and §2's structural rule exists to keep exactly that out — the rule
   that has survived three reviews and underwrites finding 1.1's single-writer invariant. That
   objection is sound. The half of my suggestion that does not have the problem is `BOOT_COMPLETED`:
   `BootReceiver`/`AlarmRescheduler` are already in `alarm/`, already on `AlarmWork.scope`, and are
   already the "clean up after a surprise restart" path, where a surviving record can only mean a ring
   that never cleaned up. Non-gating either way, because finding 1.1's durable record means the volume
   is always recoverable on the next dismiss — worst case is loud alarms for up to a day, not a lost
   alarm. **Recommendation:** re-home it on the boot path rather than app launch, and keep it MINOR.
2. **Backlog 3.2 — nothing consumes `isRinging == false` after `GiveUp`. Acceptable, and the module
   improved it without touching it.** The observation the dev offers is correct and I verified it: the
   banner keys off `liveAlarmId`, not `isRinging`, so a silently-failed ring **keeps** its route back to
   the keypad instead of losing it. That is the safe direction, and it is the first thing in three
   modules that makes a `GiveUp` ring survivable from the UI at all. What remains open is only the
   original shape — an honest flag with no consumer — and **M3-1.5** names the cheapest closure (a
   second `AlarmHistory` key written from the `GiveUp` branch), which would serve backlog 3.2 and §4's
   "last fired" row at the same time. Stays MINOR.
3. **No Compose or `AlarmRepository` tests. Acceptable, and consistent with §0.** §0 locks verification
   to code review, and closing this would need Robolectric (a dependency) or an instrumentation run (a
   device) — neither of which this project has. The dev did the available half properly: both
   scheduling criteria are pinned in the pure `AlarmScheduleDecision`, and `Diagnostics` and
   `OemBattery` are pure precisely so their policy is testable. **But record the growth honestly**, as
   Module 2's round 2 did for `AlarmRingActivity`: the untested surface is now three activities' worth
   of lifecycle code plus `AlarmRepository`, and in particular the cancel-then-schedule *ordering*
   (as distinct from the decision), `reconcileAll`, the `-2` row's lifecycle and every `onResume`
   re-query are verified by reading only. That is the single largest residual risk in the project and
   it is a verification-strategy limitation, not a code defect.

---

#### Consolidated open backlog — all three modules

Nothing below gates. Grouped by where it lives, highest value first within each group.

**Can still silence or mis-target a live alarm (take these first)**

| # | Sev | Where | Summary |
|---|---|---|---|
| 2.2 | MINOR | AlarmService.kt:175 | `stopSelf(startId)` runs unconditionally on the stale-dismiss path, so a stale dismiss can still tear down the service and silence the alarm that *is* ringing. One line; move it inside the `ringingAlarmId == NO_ALARM` branch. **Flagged "highest-value item" in Module 1 round 3 and three modules have now walked past it.** |
| 2.3 | MINOR | AlarmService.kt:153, :170-174 | Deferred `finishDismiss` is posted from a process-lifetime coroutine but gates on the instance field, so it can release the process-global wake lock and pull the notification out from under a newer alarm. |
| 1.7 | MINOR | AlarmService.kt:98-106, 234-240 | Overlapping alarms: only the dismissed id reaches `disableIfOneShot`, so the other stays `enabled` and re-fires. |
| M2-1.8 | MINOR | AlarmRingActivity.kt:132-146 | A second alarm mid-ring can **downgrade** the gate (HARD × 3 replaced by EASY × 1). Root cause is 1.7. |
| M3-1.2 | MINOR | AlarmRepository.kt:110-126 | The test alarm makes an overlap one tap, landing on 1.7 / 1.8 / M2-1.8. Free guard: the list already polls `ringingAlarm()`. |
| 1.8 | MINOR | AlarmService.kt:95-109 | A one-shot alarm is auto-disabled only on explicit dismiss, never at fire time. |
| 2.4 | MINOR | AlarmRinger.kt:216, :240-242 | `ringRequested` is not re-checked after the blocking `prepare()` — the widest remaining start/stop race. |
| 3.1 | MINOR | AlarmRinger.kt:110-113 | `start()` does not purge the audio queue; a duplicate start can leave a stale retry queued. |

**Silent-failure visibility**

| # | Sev | Where | Summary |
|---|---|---|---|
| 3.2 | MINOR | AlarmRinger.kt:326-335 | Nothing consumes the honest `isRinging == false` after recovery is exhausted. Improved but not closed — see ruling on gap 2. |
| M3-1.5 | MINOR | AlarmHistory.kt:21-29 | "Last fired" cannot see a `GiveUp` ring. Closing it also gives 3.2 its first consumer. |
| M3-1.1 | MINOR | MainActivity.kt:74-79 | The `onCreate` reconcile cancels the just-armed test alarm — a false negative on the app's own diagnostic. |
| M3-1.3 | MINOR | Diagnostics.kt:74, strings.xml:63/143 | Battery FAIL copy over-claims on stock Android and the home-screen banner fires on it by default everywhere. |
| M3-1.7 | MINOR | AlarmRepository.kt:110-126 | The test alarm rehearses MEDIUM × 1, not the tier the user configured. |
| 1.11 | MINOR | AlarmScheduler.kt:51-55 | The exact-alarm denial path only logs — **now also surfaced** by the diagnostics EXACT_ALARM row and the list banner. Closeable. |
| 1.12 | MINOR | AlarmRinger.kt:78-88 | DND can reject `setStreamVolume` — **now surfaced** by the ALARM_VOLUME row. Closeable. |

**Volume and audio**

| # | Sev | Where | Summary |
|---|---|---|---|
| 2.10 | MINOR | AlarmRinger.kt:164, :118 | No restore outside a ring cycle; a stale record cannot be told from a user volume change. Re-home the fix on `BOOT_COMPLETED`, not app launch (ruling on gap 1). |
| M3-1.4 | MINOR | AlarmRinger.kt:288-290 | The re-assert has no budget; an external reverter produces an unbounded slow write loop. |
| 1.10 | MINOR | AlarmWakeLock.kt:29 | The 10-minute timeout drops the app's wake lock at minute 11 of an unattended alarm; audio survives only on `MediaPlayer.setWakeMode`. |

**Data, threading and structure**

| # | Sev | Where | Summary |
|---|---|---|---|
| 2.6 | MINOR | AlarmDatabase.kt:33-34 | The migration gate is runtime-only, not build-time. **Respected by this module** (`AlarmHistory` avoided the bump) but the KDoc is still wrong and the next schema change still needs the migration in the same commit. |
| 2.5 | MINOR | AlarmRinger.kt:160, :171 | Blocking `commit()` on the service main thread in the start path. |
| M3-1.9 | MINOR | MainActivity.kt:78 | Main-thread prefs load in `onCreate`. |
| 2.7 | MINOR | AlarmService.kt:107, :209-230 | `rescheduleIfRepeating`'s synchronous prologue is unguarded outside the try. Structural; unchanged by this module. |
| M3-1.11 | MINOR | AlarmRepository.kt:90-93 | `reconcileAll` breaks the repository's single-writer property. Structural; no reachable path found. |
| M3-1.8 | MINOR | OemBatteryTest.kt:106-114 | The `<queries>`/`allPackages` sync test is tautological; the manifest can drift silently. |
| 1.16 | MINOR | — | **Closed in Module 2** (label is Compose state). |
| 1.17 | MINOR | test sources | `DifficultyConverter.fromName` with an unknown value; `NextTrigger.next` on a just-fired repeating trigger across DST. |
| M2-1.9 | MINOR | — | Open, **unfixable as §3 is written**; needs a §3 amendment, not code. |
| M3-1.10 | MINOR | MainActivity.kt:65-68 | Rotation drops the in-progress alarm edit. |
| M2-2.2 | MINOR | — | `decode` does not relate `draws` to `asked`. |
| M2-1.7 / M2-2.1 / M2-2.3 | MINOR | — | **All three closed by this module** (ruling 1, ruling 2, Verified correct #3). |

**NITs** — 1.18-1.22, 2.8, 2.9, 3.3, 3.4, 3.5, M2-1.10 … M2-1.13, M2-2.4 … M2-2.7, M3-2.1 … M3-2.7.
Two are worth a minute of someone's time before any release: **1.21** (no launcher `android:icon`, and
`allowBackup="true"` is misleading given the alarm table and both prefs files that matter are in
device-protected storage, which auto-backup does not cover) and **M3-2.6/M3-2.7** (dead API in
`AlarmScheduler` and `AlarmHistory`).

**Spec amendments still owed** (unchanged from Module 2 round 2, and now joined by one more): §3's
haptic bullet (M2-1.9 ruling 3, replacement text supplied in that round), §3's
notification-dismissibility bullet (M2-2.7), and §4's M2-2.1 bullet, which should read *"predicate on
the ringer **and** the id"* rather than on the ringer alone (ruling 1).

**Still-valid "Verified correct" inventories:** Module 1 rounds 1-3, Module 2 rounds 1-2, and Module 3
round 1 above. A later change to `AlarmScheduler`'s `PendingIntent` identity, the committed
`app/schemas/.../1.json`, the wake-lock accounting, the `HandlerThread` audio design, the `isRinging`
definition, **or the main-thread-only property of `lastWrittenVolume`/`volumeObserver`** must re-earn
those, not assume them.

---

#### v1 readiness

**With all three modules reviewed, the code is in good shape and the project is not ready to ship —
for one reason that no amount of further review can fix: nobody has ever heard this alarm ring.**
§0 locked verification to code review, and that was the right call for building, but it means every
claim in these three ledgers — the full-screen intent appearing over a secure keyguard, `setAlarmClock`
surviving an overnight idle, `MediaPlayer` on `STREAM_ALARM` routing to the built-in speaker, the
notification re-post landing on API 34+, the keypad being usable at 6am — is a reasoned argument about
documented platform behaviour, not an observation. The test-alarm button exists precisely to convert
the most important of those into observations, and it is one of the first things I would exercise.

**What I would not ship v1 without:**

1. **One real device, one armed test alarm, and one overnight run.** In that order. The test alarm
   proves the chain while awake; the overnight run is the only thing that tests the actual product
   claim. Fix **M3-1.1** first, or the test can lie to you in the discouraging direction.
2. **BRAINSTORM §6's watchdog alarm, or an explicit decision to drop it.** §6 lists "a watchdog alarm a
   few seconds after the primary, cancelled by the primary firing — cheap insurance against a single
   missed registration" as a mitigation. It is not in §5's out-of-scope list and it was never assigned
   to a module, so it has fallen through the gap between the design doc and the build contract. It is
   the **only** §6 mitigation that is entirely absent: `setAlarmClock` ✓, boot/time/zone receivers ✓,
   wall-clock recompute ✓, `USAGE_ALARM` ✓, speaker forcing ✓, OEM onboarding ✓, test alarm ✓,
   last-fired ✓, defensive reconcile ✓, diagnostics ✓ — watchdog ✗. Either implement it or put it in §5
   with a reason, but do not let it stay invisible.
3. **Backlog 2.2.** One line, and it is still the last way a stale dismiss can silence the alarm that
   is actually ringing. It has been labelled the highest-value backlog item since Module 1 round 3 and
   has now survived two more modules.
4. **A decision about simultaneous alarms (1.7 / 1.8 / M2-1.8 / M3-1.2).** One ringer, one activity and
   one session for two alarms is now reachable from a UI that makes two alarms easy and a test button
   that makes an overlap one tap. The cheap version is to refuse the overlap (queue or ignore the second
   alarm, and disable the test button while something rings) rather than to make the engine
   multi-alarm. Shipping without *some* decision here means the one-shot that silently stays `enabled`
   and re-fires tomorrow is a real user report waiting to happen.
5. **Shipping hygiene: a launcher icon, and `allowBackup` set deliberately rather than by default**
   (NIT 1.21). Small, but an alarm clock with the default Android icon is not a v1.

**The single highest-risk thing remaining is unchanged from BRAINSTORM §6.1: the OEM battery killer, and
the fact that everything this app can do about it is advisory.** Detect the vendor, deep-link to the
autostart list, print the steps, stamp "last fired" — that is the entire arsenal, and none of it
*prevents* anything. If MIUI's autostart toggle is off, no line of code in this repository stops the
6am alarm being missed, and the only signal the user gets afterwards is an amber "Last fired" row they
have to go looking for on a screen they have no reason to open. That risk is compounded by the two
items above: there is no watchdog registration to catch a single dropped alarm, and nothing in three
modules has ever been observed to ring on a real ROM. Second-highest is the inverse-coverage problem:
the lifecycle code with the highest consequence (three activities, the service's ten paths, the
repository's ordering) is exactly the code with zero automated tests, by design — so the first real
device run is carrying far more weight than a first device run normally does.

---

**ADVANCE.** Zero open BLOCKER, zero open MAJOR. The volume re-assertion is safe on both of its claimed
guards and does not re-open finding 1.1; the `M2-2.1` deviation is correct and my own Round 2 guidance
was the narrower, worse version of it; `M2-2.3` landed with a byte-identical intent on two screens and
incidentally gave the denied-notification case its first working route back to the keypad; all eight of
§4's acceptance boxes and all eleven deliverables are met; and the `-2` reserved row collides with
nothing. The eleven minors and seven nits are backlog and, per §1, do not gate — though **M3-1.1** and
**M3-1.2** both attack the test alarm, which is the instrument v1 readiness depends on, and are worth
fixing before anyone trusts what that button tells them. **Module 3 is complete; all three modules are
done.** The build contract is satisfied. What stands between this and v1 is not another review round —
it is a device.

---

### Final hardening round review — 2026-10-11

**Verdict:** 0 blockers, **0 majors**, 2 minors, 3 nits. **ADVANCE.** All five §4b items are
implemented as specified and none of them regresses anything the six prior rounds verified. The
highest-risk change (item 2) turns out to *remove* a wake-lock hazard rather than create one; the
refusal path touches none of the state it claims not to touch; and the `refuseStart` notification
re-post is harmless for a reason stronger than the dev's own argument. I re-traced all ten wake-lock
paths, enumerated `AlarmStartPolicy.forStart` exhaustively by hand, and checked the icon geometry
arithmetically. The five findings below are real but narrow, two of them are documentation accuracy,
and none of them can stop an alarm ringing on any path a user will meet before the first device run.

| # | Severity | File:line | Finding | Why it matters |
|---|---|---|---|---|
| M3-3.1 | MINOR | AlarmReceiver.kt:40-44 (with AlarmWakeLock.kt:33-45, AlarmService.kt:98-101) | The receiver's catch calls `AlarmWakeLock.release()` **unconditionally**, and the lock is a single non-reference-counted process-global object. Scenario: alarm A is ringing; the 06:05 alarm B is delivered and `startForegroundService` throws (a transient system refusal, or the service record in a bad state); B's `acquire` was a no-op, so the catch releases **A's** lock. Item 3 did not create this path — two deliveries a few minutes apart were always possible — but refusal makes a concurrent second delivery a *designed-for* case, so it is worth having on the record. | Bounded in practice, which is why it is MINOR: `MediaPlayer.setWakeMode(PARTIAL_WAKE_LOCK)` (AlarmRinger.kt:346) holds the CPU while audio is actually playing, and an alarm-broadcast start is exempt from background-start restrictions so the throw is rare. The exposed residue is the live-but-silent window (audio dead, `vibrate = false`), where nothing else holds the CPU. One-line fix available and already expressible: `if (AlarmService.ringingAlarm() == null) AlarmWakeLock.release()`. |
| M3-3.2 | MINOR | AlarmStartPolicy.kt:26-28, :78 (with AlarmRinger.kt:433-442) | The policy's own justification for the id clause — "the cost of a wrong `REFUSE` is one alarm not ringing while the user is already standing in front of another one that is" — is false in exactly the state that clause exists for. After `PlaybackRecovery.Plan.GiveUp` the live alarm is **silent** with `ringingAlarmId` still set, and a *different* alarm arriving then is refused, so the user's backup alarm is consumed without ringing. Scenario: the 06:00 alarm exhausts audio recovery; the 06:15 backup is refused and, being a one-shot, auto-disabled by `settleRefusedAlarm`. | Low reachability — `GiveUp` needs the bundled `res/raw/fallback_alarm.wav` to keep failing — and vibration is **not** cancelled by `GiveUp`, so a `vibrate = true` alarm is still physically alerting. The right answer is the deferred watchdog, **not** a policy change: dropping the id clause re-opens the strictly worse hole (a live-but-silent alarm losing its ringer and its route back to the keypad) that the clause was added for. Recorded so the watchdog decision can see it. |
| M3-3.3 | NIT | AlarmService.kt:174-178, :205-207 | A refusal leaves no user-visible trace: one `Log.e`, no `AlarmHistory` stamp (deliberate), and a refused one-shot simply reads as "off" in the list. With no snooze in v1, three one-shot backup alarms is a plausible pattern, and the morning after the user finds two of them switched off with nothing saying why. | Behaviourally correct per the chosen policy and honest about state; only the explanation is missing. A "did not ring — another alarm was already ringing" line next to the row, or a counterpart to the last-fired record, would close it. |
| M3-3.4 | NIT | AlarmStartPolicy.kt:8 and §7's 2026-10-11 overlap row | Both claim backlog **1.8** is closed. Only its **overlap route** is: the refusal path does now auto-disable a refused one-shot. 1.8's original route is untouched — an alarm fires, the phone is powered off or the process is OOM-killed before Dismiss, the row stays `enabled`, and `AlarmRescheduler` re-books it for tomorrow at the same wall-clock minute. | 1.7, M2-1.8 and M3-1.2 are genuinely and completely closed; 1.8 must stay **open** in the consolidated backlog with its scope narrowed to "fired but never dismissed". A ledger that marks it closed is how a real defect gets lost. |
| M3-3.5 | NIT | AndroidManifest.xml:109-110 | "Note this also turns off device-to-device transfer for the app" is an unverified platform claim. On API 31+ (targetSdk 36) cloud backup and D2D transfer are configured **separately** — `android:dataExtractionRules` carries its own `<device-transfer>` section precisely because the two are independent — so `allowBackup="false"` alone may not cover D2D. | Harmless either way, because the storage reasoning that *does* matter checks out (see verified #5): the only thing in CE storage is `app_settings`/`onboarding_seen`, so a D2D transfer that did happen would skip the walkthrough and restore no alarms. Either soften the sentence to "cloud backup off; D2D not separately configured" or add a `dataExtractionRules` file excluding everything, which makes it true. |

---

#### Task 1 — item 2 and the wake lock: **no regression, and the lock cannot leak**

The premise of the question is worth correcting first. **Item 2 moved only `stopSelf(startId)`.**
`stopForeground` and `AlarmWakeLock.release()` were *already* inside the `ringingAlarmId == NO_ALARM`
guard before this round — Module 1 round 2 filed 2.2 in exactly those words ("`finishDismiss`
correctly skips `stopForeground`/`AlarmWakeLock.release()` when a newer alarm is ringing, but then
calls `stopSelf(startId)` unconditionally"). So no release that used to happen has stopped happening.
What the change removed is a release that was happening **wrongly**: the unconditional `stopSelf` on
the stale path destroyed the service, and `onDestroy` -> `stopAlarm` then released the lock *and*
shut down the ringer of the alarm that really was ringing. Item 2 is a net reduction in wake-lock
risk, not an addition.

All ten paths, re-traced against the current code:

1. **Normal dismiss** — `silenceRinger` clears the id (AlarmService.kt:362-367), `finishDismiss`
   passes its guard and releases once (:395-397), `onDestroy` -> `stopAlarm` is then a no-op because
   `AlarmWakeLock.release` nulls the field under `@Synchronized` and re-checks `isHeld`
   (AlarmWakeLock.kt:47-54). **Exactly once.**
2. **`alarmId == NO_ALARM` dismiss** — `finishDismiss` runs synchronously (:261-264). **Once.**
3. **Stale dismiss** (both shapes: stale at entry, :254, and stale at teardown, :387) — deliberately
   does **not** release, and must not: the live alarm is holding that same single lock object (its own
   `acquire` was a no-op), so releasing here would drop the lock out from under a ringing alarm. The
   release is deferred to the live alarm's own dismiss. **Zero here, once there.**
4. **Start failure / `handleStart` catch** — `stopAlarm()` then `stopSelf(startId)` (:128-134).
   **Once.** Note this catch is now *unreachable for a different alarm*: `REFUSE` returns at :99-102,
   before the `try`, so a second alarm's failed start can no longer tear down a live one.
5. **`onDestroy`** — `mainHandler.removeCallbacksAndMessages(null)` then `stopAlarm()` (:410-416).
   **Once**, and this is the backstop the stale path now depends on.
6. **Receiver cannot start the service** — AlarmReceiver.kt:40-44 releases. **Once**, and the only
   path where nothing else would. See **M3-3.1** for the cross-alarm case.
7. **Crash / hard kill** — the lock dies with the process, and `acquire(TIMEOUT_MS)` puts a
   10-minute OS-side ceiling under everything (AlarmWakeLock.kt:29, :43).
8. **10 s write-timeout path** — `withTimeoutOrNull` returns null, the `finally` still posts
   `finishDismiss` (:278-281), which releases if nothing is ringing. **Once.**
9. **Two overlapping alarms** — now a refusal. B's `acquire` is a no-op, B never reaches
   `silenceRinger`, `stopAlarm` or `finishDismiss`, and the one lock is released once by A's dismiss.
10. **The new refusal path** — `refuseStart` (:173-190) and `settleRefusedAlarm` (:209-217) contain
    **no `AlarmWakeLock` reference at all** (grep-verified: the only call sites in the whole app are
    AlarmReceiver.kt:34, :43 and AlarmService.kt:117, :396, :407). Neither acquires nor releases.

**Can the lock now leak?** No. Take the worst case the question names: a stale dismiss skips the
release, so the lock's fate is tied to the live alarm B, and B's dismiss never arrives.
 - *Process killed / force-stopped* — a `PowerManager.WakeLock` is released by the OS when its process
   dies. Covered.
 - *User powers the phone off* — the lock goes with the kernel. Covered.
 - *Service destroyed any other way* (system reclaim, `stopSelf` from another delivery, app
   uninstall/update) — `onDestroy` -> `stopAlarm` -> `release`, path 5. Covered, and item 2 did not
   touch `onDestroy`.
 - *Nothing of ours ever runs again* — `acquire(10 min)` is the ceiling. Covered (and the 10-minute
   drop at minute 11 of an unattended ring is pre-existing backlog **1.10**, unchanged by this round).
There is also no state in which the service survives with the lock held and **nothing** live: every
transition that clears `ringingAlarmId` (`silenceRinger`, `stopAlarm`) is either followed by
`finishDismiss` on the same dismiss or is itself a release, and `handleNotificationDeleted`'s
`IDLE_STOP_DELAY_MS` path (:340-343) still clears a lingering start id 12 s later. Double release
remains structurally impossible.

#### Task 2 — the refusal path

**`forStart` is total, and an alarm can never be an overlap with itself.** The `when` has an `else`,
so totality is trivial; what matters is the ordering, and it is right. Both `incoming == ringing`
cases are decided *before* the refusal clause is reached (AlarmStartPolicy.kt:70, :75), so no input
with `incoming == ringing` can ever produce `REFUSE` — structurally, not by case analysis. Hand
enumeration over `{NO_ALARM, test id, a real id, another real id} x {NO_ALARM, ...} x {ringing,
not}`: same id + ringing -> `DUPLICATE`; same id + not ringing -> `RING`; different ids with either
liveness signal set -> `REFUSE`; different ids with neither -> `RING`. `AlarmStartPolicyTest` pins
the same thing with a triple-nested sweep asserting precisely that invariant (:84-108), which is the
right shape for this decision. 12 tests, no `@Ignore`, 138 total.

The redelivery case the question flags is safe **twice over**. In-process, a redelivered
`START_REDELIVER_INTENT` for the ringing alarm hits :70 -> `DUPLICATE` while recovery is in flight
(`startPending` keeps `isRinging` true across a `Restart`, AlarmRinger.kt:428) and :75 -> `RING` once
recovery has given up (`startPending = false`, :436) — the latter reusing the existing ringer with a
fresh recovery budget, which is real recovery and not a no-op. Cross-process, the service is a new
instance with `ringingAlarmId == NO_ALARM` and `ringer == null`, so the redelivered intent falls to
the `else` -> `RING`. There is no input shape in which an alarm's own redelivery is refused.

**`rescheduleIfRepeating` / `disableIfOneShot` are genuinely disjoint and order-independent.** They
branch on the same immutable predicate from the same row: `:439` acts only when
`alarm.isRepeating && alarm.enabled`, `:669` returns early when `alarm.isRepeating`. Neither writes
`repeatDaysMask`, so neither can flip the other's predicate; `dao.disable` writes only `enabled`, and
by then the repeating branch has already declined. They run as two separate coroutines on
`AlarmWork.scope`, so they *are* concurrent — and that is fine for exactly this reason. A refused
**repeating** alarm books its next occurrence off `rescheduleBaseline(triggerAtMillis)` (:446-453),
i.e. the instant that just fired + 1 minute, so it rings tomorrow and cannot re-book the occurrence
it just consumed. A refused **one-shot** is `disable`d *and* `cancel`led (:666-672), so
`AlarmRescheduler` sees `enabled = false`, cancels nothing of substance and never re-books it —
backlog 1.8's phantom-tomorrow harm is closed on this path. The reserved test row (`-2`) goes down
the one-shot branch and is additionally skipped by `rescheduleAll`, so it cannot come back either.
Both calls are guarded by `if (alarmId == NO_ALARM) return` (:210), so an id-less refused start
settles nothing, which is correct — there is nothing to settle.

**Ruling on the `refuseStart` notification re-post: harmless.** Four independent reasons, in
increasing strength:

1. *It cannot mis-target.* It is built from `ringingAlarmId` / `ringingLabel` (:184), never from the
   refused id, and those two fields are written together on the main thread at :123-124 — which is
   also where the originally-posted notification's values came from. `handleStart` runs start to
   finish on the main thread, so no delivery can interleave between the `startForeground` at :110 and
   the field writes at :123; and a `REFUSE` cannot be computed during a teardown window, because
   after `stopForeground` both liveness signals are clear and the outcome would be `RING`. In every
   reachable `REFUSE`, including the id-less-ring case (`ringingAlarmId == NO_ALARM`, `isRinging`
   true), the re-post reproduces byte-identical content to what is already showing: same
   `NOTIFICATION_ID`, same request code `alarmId.toInt()`, same action/component/flags/extras, so
   `FLAG_UPDATE_CURRENT` mints an identical `PendingIntent` and the shade updates in place.
2. *The dangerous version of this was avoided.* The genuine mis-targeting risk on this path was never
   the notification — it was `launchRingActivity`, which would have sent an `openRingingIntent`
   carrying the **refused** id into the `singleTask` activity, tripped `alarmId != previous`
   (AlarmRingActivity.kt:141) and wiped a session with questions solved and failures counted.
   `refuseStart` does not call it, and does not call `warnIfNoUiPath` or `recordFired` either. That is
   the right cut.
3. *A re-triggered full-screen intent cannot disturb a live challenge session.* Even granting the ROM
   behaviour, the FSI carries the **live** alarm's id, so `onNewIntent` takes the `alarmId == previous`
   branch and `challenge` — problem, entry, `consecutiveWrong`, no-repeat memory — is left untouched;
   `startSession` is not called, so `consecutiveWrong` cannot be reset. An intent delivered to the
   already-resumed top instance of a `singleTask` activity (`AndroidManifest.xml:140`, with
   `CLEAR_TOP`) goes through `onPause` -> `onNewIntent` -> `onResume`, not `onStop`, so the escape
   deadline — cleared **only** in `onStop` (AlarmRingActivity.kt:331-334) — survives. And in the
   worst case where a ROM does cycle the activity through `onStop`, the single consequence is the
   countdown restarting at 60 s, which is the direction that makes the alarm *harder* to escape, is
   already what home/recents/screen-off do, and is what BUILD_SPECS §3 asks for.
4. *If the activity was backgrounded, being pulled back is the correct destination anyway* — the alarm
   it opens is the one that is actually ringing, and this is the same behaviour M2-1.1's swipe
   re-post has had since Module 2 round 2. Being a new *caller* of it changes the trigger, not the
   operation.

On the obligation it exists for: a delivery that arrived via `startForegroundService` carries a
per-start `fgRequired` expectation, and calling `startForeground` with the already-posted notification
is the cheapest honest way to clear it; the `runCatching` wrapper (:179-187) is the same best-effort
shape as the swipe handler's, and a throw there is no worse than not having re-posted at all.

**State the refusal path does not touch — claim verified.** Reading `refuseStart` and
`settleRefusedAlarm` line by line: no write to `ringingAlarmId`, `ringingLabel`, `liveAlarmId` or
`liveAlarmLabel` (the only writes in the file remain :123-126, :365-366 and :404-405); no reference to
`ringer` and therefore nothing that can touch audio, volume, focus or vibration; no `stopSelf`; no
`AlarmWakeLock`. The outstanding start id is correctly judged free: the live alarm's own dismiss is a
newer delivery, so *its* `stopSelf(startId)` is the most recent and does stop the service, which
discards every pending redelivery with it.

#### Task 3 — item 1, item 5, item 4

**Item 1 — skipping the cancel is genuinely free.** Verified on all three legs the KDoc claims.
(a) *The registration is consumed or re-armed.* `scheduleTestAlarm` books `now + 5 s` via
`scheduleAt` (AlarmRepository.kt:114-130), which does not consult `enabled`, and re-arming calls
`scheduler.cancel(TEST_ALARM_ID)` **before** `scheduleAt` (:128-129), so two arms cannot leave two
registrations; `setAlarmClock` is exempt from Doze and App Standby, so a 5-second registration is
delivered. (b) *If it fires, it is settled.* Both the dismiss path (`disableIfOneShot`, :666-672) and
the new refusal path (`settleRefusedAlarm`) end in `AlarmScheduler.cancel(-2)` for a non-repeating
row, and the test row is always non-repeating (`repeatDaysMask = 0`). (c) *Nothing survives a reboot.*
`AlarmManager` registrations are process- and boot-scoped, so `BOOT_COMPLETED` /
`LOCKED_BOOT_COMPLETED` have nothing stale to clear, and force-stop drops an app's alarms as well. The
one remaining theoretical case — a 5-second registration straddling `MY_PACKAGE_REPLACED` or
`TIME_SET` — is a 5-second window whose worst outcome is one extra test ring, not a resurrected
alarm, because the row stays `enabled = false` and `AlarmRescheduler` never re-books a skipped row.
The two corrected KDocs (AlarmRescheduler.kt:16-27, AlarmRepository.kt:86-91 and :98-113) now match
the code exactly, including the log line's `skipped` accounting (:50).

**Item 5 — the `allowBackup="false"` reasoning is sound, and the storage claim is the part that
matters.** Verified by grep rather than by trusting the comment: the alarm database
(`AlarmDatabase.kt:70-71`), the last-fired record (`AlarmHistory.kt:45-46`) and the pre-ring volume
record (`AlarmRinger.kt:50-51`) all go through `createDeviceProtectedStorageContext()`, which puts
them in `/data/user_de/...`; Auto Backup operates on the credential-encrypted data directory and does
not cover device-protected storage. The only credential-encrypted file in the app is
`AppSettings`' `app_settings` (AppSettings.kt:18), whose only key is `onboarding_seen` (:15). So the
comment's "worst possible subset" characterisation is exactly right: a restore would have skipped the
permissions walkthrough the new device genuinely needs while restoring no alarms at all. The
forward-looking half of the argument is the better half and also correct — if alarm rows ever move to
CE storage, this flag being `false` is what stops a restore manufacturing a list of alarms that look
armed and are not, and there is no restore-side hook that could re-book them before the user opens
the app. Only the D2D sentence overreaches (**M3-3.5**).

**Item 4 — the icon XML is valid and mask-safe by arithmetic; visual quality is unverified.**
 - *Valid.* Three layers, all `108x108` vectors with matching viewports; the clock face
   `M32,58 A22,22 0 1,1 76,58 A22,22 0 1,1 32,58 Z` is a legitimate two-semicircle circle —
   `|(76,58) - (32,58)| = 44 = 2r`, so each arc is exactly a semicircle about `(54,58)` with no
   radius-too-small correction and no ambiguity from the flags. Stroked paths with no `fillColor` are
   well-formed. `<monochrome>` is an API 33 attribute, and `AdaptiveIconDrawable` inflation on API
   26-32 skips child tags it does not know, which is why the platform icon template ships exactly
   this file at low `minSdk`. `mipmap-anydpi-v26` with no density variants is correct at `minSdk 26`:
   every supported device matches the `-v26` qualifier and a vector needs no density buckets.
 - *Mask-safe.* The mask lives inside the central `72x72`, i.e. a circle of radius 36 about `(54,54)`.
   Clock face: centre `(54,58)`, `r = 22`, stroke 7 -> outer radius 25.5, and `4 + 25.5 = 29.5`.
   Bell caps: endpoint `(30,34)` is `sqrt(24^2 + 20^2) = 31.24` from the mask centre, plus the 4-unit
   round cap -> **35.24**, mirrored at `(78,34)`. Hands: 13 and 17.9. So the extreme point sits
   **0.76 units inside** a circular mask — nothing is clipped, and every non-circular mask
   (squircle, rounded square, teardrop) contains the inscribed radius-36 circle, so they are safe
   too. The dev's stated numbers (29.5 and 35.2) are right.
 - *Unverified, plainly:* **this icon has never been rendered.** No emulator (§0), so contrast of
   `#FFF4F6FB` on `#FF1D2433`, legibility at 48 dp in a dense drawer, how the bell caps look 0.76
   units from a circular mask edge once antialiasing is applied, parallax behaviour on launchers that
   animate the foreground layer, and the themed-icon tint of the monochrome layer on Android 13+ are
   all unobserved. The arithmetic says it will not be clipped; it does not say it looks good.

#### Verified correct (this round)

1. **Item 1** — `rescheduleAll` skips `TEST_ALARM_ID` in both directions (AlarmRescheduler.kt:41-45),
   the skip is counted honestly in the log line, and `BootReceiver` inherits it through the same
   function. M3-1.1 **CLOSED**.
2. **Item 2** — `stopForeground`, `AlarmWakeLock.release()` and `stopSelf(startId)` are all inside the
   `ringingAlarmId == NO_ALARM` branch (AlarmService.kt:386-398). Backlog **2.2 CLOSED**, and with it
   the last route by which a stale dismiss could silence a live alarm.
3. **Item 3** — the policy is pure, total, correctly ordered, and wired exactly once on the main
   thread (AlarmService.kt:98-101); the refusal is a true no-op against ringing state; the refused
   alarm is settled, never lost; the test-alarm block is three layers deep (poll-driven button at
   AlarmListScreen.kt:134-138/251-273, re-check at MainActivity.kt:213-216, service refusal) and only
   the first two can explain themselves, which is the stated reason they exist. Backlog **1.7**,
   **M2-1.8** and **M3-1.2 CLOSED**; **1.8** partially (see M3-3.4).
4. **Items 4 and 5** — `android:icon` and `allowBackup="false"` are set with the reasoning recorded in
   place (AndroidManifest.xml:80-117). NIT 1.21 **CLOSED**.
5. **Nothing else moved.** `@Database(version = 1)`, `MIGRATIONS = emptyArray()`, a single committed
   `1.json`; `ui/` still contains zero audio, vibrator, wake-lock or `AlarmRinger` *code* references
   (the seven grep hits are all prose in KDoc); no new permission, no network permission, no new
   dependency; `AlarmRinger`, `PlaybackRecovery`, `ChallengeState`, `ProblemGenerator`, `NextTrigger`,
   `AlarmScheduler`'s `PendingIntent` identity and the volume re-assertion are untouched, so the six
   prior rounds' ~40 verified items stand.

#### v1 readiness — superseding Module 3's

**The five items were the right five, and they are done. What stands between this and v1 is now
exactly one thing, and it is the same thing it was before: nobody has ever heard this alarm ring.**
Module 3's list had five entries; four are closed (M3-1.1, backlog 2.2, the overlap decision, icon +
`allowBackup`). The fifth, the watchdog, is deliberately held open pending the first device run. Code
review has nothing left to contribute that would change the odds of the next overnight alarm firing.

**The first device run, in this order.** Stop at the first step that fails, and fix that before
trusting anything after it.

1. **Install, open, walk the onboarding through.** Grant notifications and full-screen intent, take
   the battery-optimization exemption, and check the diagnostics screen. *Stop and fix if:* exact
   alarms read as denied on a Pixel-class device (that is `USE_EXACT_ALARM` not being honoured and
   every later step is meaningless), or the launcher icon is clipped, illegible or invisible in the
   drawer (item 4 has never been rendered).
2. **Tap "Test the alarm now" with the screen locked.** Five seconds later you should get sound at
   full alarm volume, vibration, and the keypad over the lock screen without a PIN prompt. *Stop and
   fix if:* nothing rings (the firing chain is broken — check logcat for `AlarmReceiver`/`AlarmService`
   before suspecting the OEM), or a bouncer appears over the keypad, or the sound stops when you
   press Home or swipe the notification away.
3. **Solve the math and dismiss.** Then re-check the diagnostics "last fired" row. *Stop and fix if:*
   the row is empty after a successful ring (the one instrument you will have overnight is lying), or
   the alarm-stream volume is left at max afterwards.
4. **Arm two alarms one minute apart and let the first ring while you ignore it.** This is the only
   way to observe item 3. Expect: the second alarm never rings, the keypad keeps showing the *first*
   alarm with its own tier and its own failure count, the notification does not change, and afterwards
   the second one-shot reads as off. *Stop and fix if:* the keypad's label or difficulty changes
   mid-session, the escape countdown restarts, or dismissing silences nothing.
5. **One real overnight run — a one-shot, phone unplugged, screen off, app not opened after arming.**
   This is the actual product claim and the only step that tests it. *Stop and fix if:* it fires late,
   fires silently, or does not fire. Any of those three means **build the watchdog before anything
   else** (§4b's deferral says exactly this), and a miss on an OEM ROM means the battery/autostart
   path in onboarding needs to be louder, not that the engine is wrong.
6. **Repeat step 5 twice more, including one run after a reboot late in the evening.** A single
   success is not evidence against an OEM killer; it is one sample.

**The highest-risk thing remaining is still BRAINSTORM §6.1's OEM battery killer, and everything this
app can do about it is advisory.** Unchanged, and this round did not touch it. Second is the absence
of the watchdog, which is now the only §6 mitigation missing and is precisely what would convert a
single dropped registration from a missed morning into a few-seconds-late one. Third, and new this
round, is that two of the five changes (the icon, and the refusal path's behaviour under a live-but-
silent alarm, **M3-3.2**) are reasoned rather than observed — step 4 above is how you observe the one
that matters.

**ADVANCE.** Zero open BLOCKER, zero open MAJOR. Items 1, 2, 3, 4 and 5 are all implemented as §4b
specifies; item 2 strictly reduces wake-lock risk rather than regressing it; the refusal path is a
true no-op against every piece of state it claims not to touch, and its notification re-post cannot
mis-target or disturb a live challenge session. The two minors and three nits are backlog and do not
gate. **The build contract and the final hardening round are both complete. The next thing this
project needs is not a review — it is one night with a real phone.**

---

## 4b. Post-review v1 hardening (final round)

All three modules advanced with 0 open blockers and 0 open majors. Scope of the final round is
**the five readiness items only** (user decision, 2026-10-11); the rest of the consolidated
backlog stays backlog.

1. **M3-1.1** — `onCreate`'s reconcile cancels the just-armed test alarm, and because the test row
   is `enabled = false`, `AlarmRescheduler` never re-books it. The test alarm then silently fails
   and the user concludes their phone kills alarms — **a false negative on the app's own
   diagnostic, in the discouraging direction.** Skip `TEST_ALARM_ID` in `rescheduleAll`.
2. **Backlog 2.2** — `stopSelf()` rather than `stopSelf(startId)` plus a missing `ringingAlarmId`
   guard: the last remaining way a stale dismiss can silence a live alarm. Flagged highest-value
   since Module 1 round 3 and carried past two modules.
3. **Overlap policy: refuse it** (user decision). A second alarm arriving while one rings is
   refused rather than allowed to overwrite the ringing state, and the test alarm is blocked
   while a real alarm rings. Closes backlog 1.7, 1.8, M2-1.8 and M3-1.2 together, without
   making Module 1's service multi-alarm — the review rounds showed that service is where new
   bugs come from.
4. **Launcher icon** — absent; `android:icon` is not set on `<application>`.
5. **`allowBackup`** — currently `true` by default, never a deliberate choice. Decide and
   document. Note the alarm-specific hazard: a restored row says `enabled` while no
   `AlarmManager` registration exists on the new device, so the alarm looks armed and never
   fires. Module 3's reconcile-on-app-open self-heals that, **but only if the user opens the
   app**, which someone restoring a phone has no reason to do before bed.

**Watchdog alarm — deferred pending the first device run** (user decision, 2026-10-11). It is
BRAINSTORM §6's only mitigation entirely absent, and it fell through the gap between the design
doc and this contract rather than being rejected. It is **not** out of scope; it is a decision
held open until firing has been observed on real hardware. If the first overnight run shows any
missed or late firing, build it before anything else.

## 4c. Round: recovery-exhausted takeover (M3-3.2)

**One defect, one fix.** User decision 2026-10-14: fix the overlap bug before the emulator run,
so the emulator exercises the corrected policy.

### The defect
`AlarmRinger.GiveUp` clears `isRinging` but deliberately leaves `AlarmService.ringingAlarmId`
set. `AlarmStartPolicy.forStart`'s refusal clause is `ringingAlarmId != NO_ALARM ||
ringerIsRinging`, so a **silent, recovery-exhausted alarm still refuses the next alarm** — and
nothing rings at all.

Timeline (alarm A 06:00, alarm B 06:05, both one-shots, `vibrate = false`, media stack wedged):

| Time | Event | `ringingAlarmId` | `isRinging` | Sound |
|---|---|---|---|---|
| 06:00:00 | A fires; all three sources fail | A | true | silent |
| 06:00:04.6 | 3 retry passes spent → `GiveUp` | **A** | **false** | **silent** |
| 06:05:00 | B fires → `forStart(B, A, false)` → **REFUSE** | A | false | silent |
| 06:05:00 | B's registration already consumed → auto-disabled, reads "off" | A | false | silent |

Probability is low (needs the bundled `res/raw` WAV to keep failing, i.e. a broken media stack,
not a deleted ringtone). Consequence is total: two alarms set, neither rings.

### The fix
Give the policy a **third signal** that distinguishes "live and ringing" from "live but audio
recovery gave up". When recovery is exhausted, a *different* incoming alarm takes over (`RING`)
instead of being refused — it can at least try a fresh ringer with a fresh retry budget.

### Constraints
- **Do NOT drop the `ringingAlarmId != NO_ALARM` clause.** Module 3's reviewer was explicit that
  this re-opens a worse hole: during the start window `isRinging` can be transiently false while
  the id is set, and a second alarm could then take over mid-start.
- A takeover must stop/replace the dead ringer rather than leak it, and must not double-release
  the single process-global wake lock (Module 1's ten-path accounting).
- **CORRECTED 2026-10-14 (review 4c-1.1).** The original text here claimed A's
  `rescheduleIfRepeating` / `disableIfOneShot` / `recordFired` all run at A's own start, so a
  takeover need not re-settle A. **That is false for `disableIfOneShot`**, which has exactly two
  callers — `settleRefusedAlarm` and `handleDismiss` — and is never called at start. A one-shot is
  disabled at *dismiss*, not at start. So a taken-over one-shot keeps `enabled = true` with no
  registration, and `AlarmRescheduler.rescheduleAll` re-books every enabled row — **A rings
  unbidden the next morning.** That is verbatim backlog 1.8's harm, which
  `settleRefusedAlarm`'s own KDoc exists to prevent. A takeover **must** settle the overwritten
  id's row: run the `disableIfOneShot` half against the previous `ringingAlarmId`, captured
  before the takeover overwrites it. `rescheduleIfRepeating` genuinely did run at A's start, so
  repeating alarms need nothing; the reserved test row is already `enabled = false`.
- The exhausted flag must reset on a fresh `start()`, and must not survive into a new ring.
- `incoming == ringing` must keep being decided **before** any refusal or takeover clause — an
  alarm can never be an overlap with itself.
- Keep the policy decision in pure `domain/` code, JVM-testable, as `AlarmStartPolicy` already is.

### Acceptance criteria
- [ ] `./gradlew assembleDebug` exits 0
- [ ] `./gradlew testDebugUnitTest` passes; all **138** existing tests still pass, none weakened
- [ ] A unit test reproducing the table above: exhausted + different alarm → takeover, not refusal
- [ ] A unit test that a *non*-exhausted ringing alarm still refuses a different alarm
- [ ] A unit test that `incoming == ringing` is unaffected in both exhausted and ringing states
- [ ] `ui/` still holds zero audio **code** references
- [ ] No schema change, no new dependency, no `INTERNET`, no snooze
- [ ] **A taken-over one-shot ends up disabled, exactly as a dismissed or refused one does**
      (added 2026-10-14; all seven original criteria passed while the round still re-opened
      backlog 1.8, because they described only the policy decision and said nothing about the
      taken-over alarm's row)

### Review Ledger — Round 4c
*Review agents append here. Do not edit by hand.*

### Round 1 — 2026-10-14

Reviewed: **0 blockers, 1 major, 2 minors, 4 nits**. **DO NOT ADVANCE** — all seven §4c acceptance
boxes are met and the policy change itself is correct, minimal and well-guarded, but the takeover
path never settles the alarm it takes over, which re-opens backlog **1.8** for the one state §4c
created. The defect is not eliminated by this round; for two one-shots it is *transferred* from
alarm B to alarm A, and in one respect made worse (A re-arms and rings on a morning the user never
set an alarm for, instead of merely reading "off").

The policy file, the ringer signal and the reuse of the existing `AlarmRinger` instance are all
sound and are listed under *Verified correct* below. The gating finding is in `AlarmService`, not in
`AlarmStartPolicy`.

| # | Severity | File:line | Finding | Why it matters |
|---|---|---|---|---|
| **4c-1.1** | **MAJOR** | `alarm/AlarmService.kt:131-143` | **A taken-over one-shot alarm is never auto-disabled, so it stays `enabled` with no `AlarmManager` registration and the next reconcile re-books it.** `disableIfOneShot` has exactly two callers — `handleDismiss:286` and `settleRefusedAlarm:230` — and the takeover branch calls neither for the overwritten id. The comment at `:137` ("it ran its own rescheduleIfRepeating / **disableIfOneShot** / recordFired at its own start") is factually wrong: `handleStart:152-158` calls `warnIfNoUiPath`, `launchRingActivity`, `rescheduleIfRepeating` and `recordFired`, and **not** `disableIfOneShot` — a one-shot is disabled at *dismiss*, never at start. §4c's timeline (A and B both one-shots): A fires 06:00, exhausts recovery, B takes over 06:05, B is solved and dismissed → `handleDismiss(B)` disables B only. A's row is left `enabled = true`. A can no longer receive a dismiss (`handleDismiss:270` marks it stale, and `AlarmRingActivity.onNewIntent` has already switched the keypad to B), so nothing ever disables it. | This is precisely the harm `settleRefusedAlarm`'s own KDoc at `:211-213` was written to prevent — "hand them a phantom one (a one-shot stays `enabled`, and `AlarmRescheduler` re-books it for tomorrow at the same wall-clock minute — backlog **1.8**'s harm, arriving on a morning the user never set an alarm for)" — and `AlarmRescheduler.rescheduleAll:48` plus `NextTrigger.next` ("one-shot: today if the time is still ahead, otherwise tomorrow") make it concrete: opening the app, or any boot, re-arms A for 06:00 **the next day** and it rings. Until then the list shows A as armed while no registration exists. `AlarmStartPolicy.kt:33-35`'s claim that "the three harms above all stay closed" is false for harm 2 (`:17-18`, backlog 1.8). Fix shape: capture `ringingAlarmId` before `:139` overwrites it and, on the takeover branch only, run the `disableIfOneShot` half of `settleRefusedAlarm` against it (the repeating half already happened at A's own start). ~5 lines, no new abstraction. **Note: BUILD_SPECS §4c constraint 3 asserts the same false premise and needs correcting with the code.** |
| **4c-1.2** | MINOR | `alarm/AlarmRinger.kt:402-403`, `:453-454`, `:137` | **`recoveryExhausted` can be left stale-true on a fresh ring.** The two exhaustion sites write `startPending = false` and `recoveryExhausted = true` as two separate statements. In the window between them `isRinging` is already false, so a redelivered `ACTION_START` for the *same* alarm is decided `RING` (`AlarmStartPolicy.kt:86`), `start()` clears the flag on the main thread at `:137`, and the audio thread then executes `recoveryExhausted = true` into the new ring. The flag is harmless while that ring reports `isRinging` — but `onPlaybackError:425-445` leaves both `audioAlive` and `startPending` false across `releasePlayer()`, `candidateSources()` (two `RingtoneManager` lookups) and `Log.e`, and a third alarm arriving in that window would then satisfy `ringerRecoveryExhausted && !ringerIsRinging` and **take over a healthy, recovering ring** — the one outcome the policy's cost asymmetry (`AlarmStartPolicy.kt:26-28`) is built to prevent. | Compound of two sub-millisecond windows, so the probability is negligible and this is not gating; but it is the only way the new clause can produce a wrong `RING`, and the `ringingAlarmId != NO_ALARM` clause cannot catch it because the new clause is evaluated first. One-line close: also assign `recoveryExhausted = false` inside `start()`'s posted lambda, next to `restartPasses = 0`. The lambda runs on the audio thread strictly after the in-flight exhaustion statements complete, so it closes the interleaving the main-thread clear cannot. Keep the main-thread clear as well — it is what covers the delivery that arrives before the lambda runs. |
| **4c-1.3** | MINOR | `domain/AlarmStartPolicy.kt:96` | **The new clause lets an *id-less* start take over a live ringer**, which the pre-existing test at `AlarmStartPolicyTest.kt:53-58` was written to forbid. `forStart(NO_ALARM, 7L, false, true)` returns `RING`, so `handleStart:139-141` sets `ringingAlarmId = liveAlarmId = NO_ALARM` while a ringer runs. That state is read as "nothing live" by `finishDismiss:403` (any dismiss then tears the service down), and `ringingAlarm()` returns null, so the list's test-alarm button and `MainActivity.fireTestAlarm:213` both unblock. `AlarmRingActivity` is launched with `NO_ALARM` and falls back to MEDIUM × 1. | Unreachable from production today: the only `ACTION_START` producer is `AlarmReceiver:38`, which always supplies `EXTRA_ALARM_ID`, and the component is `exported="false"`. Logged because review **M2-2.1** established that `NO_ALARM` doubling as "this start carried no id" is a real hazard class worth guarding structurally rather than by reachability. One extra conjunct (`incomingAlarmId != NO_ALARM &&`) closes it and loses nothing. |
| **4c-1.4** | NIT | `alarm/AlarmRinger.kt:74-77` | The field KDoc states the ordering guarantee absolutely ("a main-thread reader that sees this true is guaranteed to see `isRinging` false"). It holds **within one start generation only** — `audioAlive = false` (`:388`/`:425`) and `startPending = false` precede both writes in program order, and volatile accesses are totally ordered, so the claim is sound for a single ring — but it does not survive the `start()` interleaving in 4c-1.2. | The sentence is the reason a future reader might think `&& !ringerIsRinging` at `AlarmStartPolicy.kt:96` is redundant and delete it. It is not redundant; it is the load-bearing guard. Qualify the claim rather than strengthening the code. |
| **4c-1.5** | NIT | `test/domain/AlarmStartPolicyTest.kt:132-136` | The sweep's comment claims it proves "the `ringingAlarmId != NO_ALARM` clause is still what decides the not-ringing case, which is why dropping it was never needed". It does not: the assertion is differential against `before`, computed from the same function, so deleting that clause from the refusal would change `before` and `after` together for every `takenOver` cell and the sweep would still pass. | The property *is* pinned — by the pre-existing totality test at `:147-171`, which asserts `forStart(8L, 7L, false)` is `REFUSE` through the 3-arg default. The claim is true of the suite and false of the test it is written in; move or reword it. |
| **4c-1.6** | NIT | `domain/AlarmStartPolicy.kt:71-76`, `:30-35`, `:88-95`, `alarm/AlarmService.kt:131-138` | The §4c rationale is written out three times, in the class KDoc, at the clause, and again at the service call site — ~41 comment lines for ~8 statements. | See the over-engineering subsection: the comments are justified in principle for a policy whose wrong `RING` silences a live alarm, but the triplication is not. One canonical home plus two pointers. |
| **4c-1.7** | NIT | `alarm/AlarmWakeLock.kt:29`, `AlarmService.kt:125` | A takeover does not refresh the wake lock's `TIMEOUT_MS` ceiling: `acquire` returns early while held, so B inherits what is left of A's 10 minutes (5 minutes in §4c's timeline). | Not a leak and not new behaviour — a single ring longer than 10 minutes already loses the lock, and `MediaPlayer.setWakeMode(PARTIAL_WAKE_LOCK)` (`AlarmRinger.kt:362`) plus `FLAG_KEEP_SCREEN_ON` (`AlarmRingActivity.kt:360`) keep the alarm alive past it. Recorded only so the asymmetry is on the record before the device run. |

#### Over-engineering (ponytail-review)

The change is genuinely lean in code: no new class, no new interface, no new dependency, no queue, no
per-alarm state, no callback across the thread boundary, and the takeover reuses the existing `RING`
path rather than adding one. `private set` on the new property is the right minimum. Two findings,
and two instincts deliberately **rejected**:

- `AlarmStartPolicy.kt:30-35` + `:88-95` + `AlarmService.kt:131-138`: `shrink:` the same §4c argument
  three times. Keep it at the clause (`:88-95`), where a would-be deleter actually stands; replace
  the other two with a one-line pointer. **~10 lines.**
- `AlarmRinger.kt:403` / `:454`: the two trailing `// after startPending, never before` comments stay.
  Six words each, sitting exactly where a reorder would be typed. Earned.
- **Rejected — `AlarmStartPolicy.kt:96`, `&& !ringerIsRinging`.** The obvious cut: the ringer's write
  ordering is supposed to make it unreachable, and test `:104-112` admits as much ("this state is
  unreachable in practice"). Do not cut it. Finding 4c-1.2 shows the ordering argument does not
  survive a `start()` interleaving, and this conjunct is the only thing between that stale flag and a
  takeover of a live ring. The belt is load-bearing; the braces are the comment.
- **Rejected — the `= false` default on `ringerRecoveryExhausted` (`:75`).** It looks like dead
  flexibility: one production call site, which always passes the argument explicitly. It stays. It is
  the conservative value in the only direction that matters (a missing argument costs a takeover,
  never grants a wrong one), and it is what kept all 12 pre-existing tests byte-identical, which in a
  review-gated process is evidence of no regression rather than convenience. With exactly one
  production call site the "a future caller forgets it" hazard is bounded to a new call site that a
  review would see anyway.
- Not a finding: the multi-line `forStart(...)` call at `AlarmService.kt:101-106`. Four arguments, two
  of them safe-call comparisons; there is no shorter honest form inside the line limit.

`net: -10 lines possible.`

#### Verified correct

- **The memory-ordering claim is sound but not load-bearing.** At both exhaustion sites `audioAlive = false`
  (`:388`, `:425`, and again in `releasePlayer:468`) and `startPending = false` precede
  `recoveryExhausted = true` in program order; volatile accesses are totally ordered, so a reader that
  sees the flag true sees **both** halves of `isRinging` false, not just `startPending`. The claim is
  therefore true per start generation. It is also not what protects anything: were it violated, the
  `&& !ringerIsRinging` conjunct yields `REFUSE`, the conservative outcome. The explicit conjunct is
  the guard; the ordering is a nicety (see 4c-1.2, 4c-1.4).
- **The non-atomic double read in `AlarmService.kt:104-105` is safe.** `isRinging` is read at t1 and
  `recoveryExhausted` at t2 > t1. The dangerous pair is (exhausted = true, isRinging = false) on a
  healthy ring; it cannot arise within a generation, because `isRinging == false` at t1 means the ring
  is either unstarted, exhausted, or inside `onPlaybackError`'s gap — and in that gap the flag only
  turns true if the plan really is `GiveUp`. A `Restart` plan leaves it false and the
  `ringingAlarmId != NO_ALARM` clause refuses.
- **Clause placement.** The new clause is after both `incoming == ringingAlarmId` branches (`:81`,
  `:86`) and before the refusal (`:99`), which is unchanged. An alarm can never be an overlap with
  itself in either state, and the refusal clause is intact, so §4c constraints 1 and 5 hold
  structurally, not just by test.
- **Reusing the exhausted `AlarmRinger` instance leaks nothing and double-registers nothing**, which is
  what makes "zero new executable statements for the takeover" true:
  - the durable pre-ring volume record survives — `rememberVolumeBeforeRinging:210` returns early when
    `KEY_PRIOR_VOLUME` is already present, so B's `forceMaxAlarmVolume` does **not** overwrite the
    user's real volume with the maxed value A left on the stream. Finding **1.1** is not re-opened.
  - `startWatchingAlarmVolume:273` returns early when `volumeObserver != null` — no second
    `ContentObserver` and no orphan.
  - `requestAudioFocus:320` calls `abandonAudioFocus()` first, for exactly this case ("a reused ringer
    (alarm B starting while A rings) must not leak A's request").
  - `startPlaybackFrom:351` opens with `releasePlayer()`, and at both exhaustion sites `player` is
    already null (released at `:436` or in the per-source catch at `:385`), so no player and no error
    listener survives into B's ring.
  - `restartPasses = 0` runs inside the posted lambda, staying audio-thread-confined as Module 1
    round 3 verified; `stopVibration()` runs unconditionally before the conditional restart, so A's
    vibration stops even when B has `vibrate = false`; the `HandlerThread` is still alive because
    `shutdown()` was never called.
  - `startForeground` reuses `NOTIFICATION_ID`, so B's notification replaces A's rather than adding one.
- **Wake lock: exactly once on every path, re-traced.** A's `AlarmReceiver.acquire` holds it; B's
  `acquire` is a no-op (`isHeld` early return, `setReferenceCounted(false)`); the takeover branch
  contains no `AlarmWakeLock` reference. B dismissed → `silenceRinger` clears the id, `finishDismiss`
  passes its `NO_ALARM` guard and releases once. B force-stopped or the process killed mid-takeover →
  the lock dies with the process, and the 10-minute ceiling covers a crash. Killed mid-takeover with
  redelivery → both outstanding `START_REDELIVER_INTENT` items come back, A rings first on a fresh
  ringer and B is refused and settled, which is pre-§4c behaviour and still rings something.
- **A dismiss for A arriving after B took over cannot tear down B's ring.** `handleDismiss:270` marks
  it stale and skips `silenceRinger`; `finishDismiss:403` sees `ringingAlarmId == B` and neither stops
  the service nor releases the lock. The stale path still runs `disableIfOneShot(A)`, which is the one
  route by which A *would* be settled correctly — it just cannot be reached, because the keypad has
  already switched to B (see 4c-1.1).
- **A is not re-settled.** Every settling call below the ringer start takes the incoming id:
  `warnIfNoUiPath(alarmId)`, `launchRingActivity(alarmId, label)`, `rescheduleIfRepeating(alarmId, …)`,
  `recordFired(alarmId)`. No double `recordFired` and no double reschedule. A **repeating** taken-over
  alarm ends in a sane state (its next occurrence was booked at its own start); only the one-shot case
  is wrong (4c-1.1). The reserved test row is also safe: it is already `enabled = false` and
  `AlarmRescheduler` skips it in both directions.
- **The test alarm cannot be used to take over an exhausted real alarm.** `ringingAlarm()` reads
  `liveAlarmId`, which stays set through exhaustion, so the list button stays disabled and
  `fireTestAlarm:213` refuses. The sweep's `(-2L, 7L, false, true) → RING` cell is reachable only
  through a path the UI blocks.
- **Exactly one production call site** for `forStart` (`AlarmService.kt:101`) and for
  `recoveryExhausted` (`AlarmService.kt:105`). `false` is the safe default in both directions.
- **The mid-challenge session loss on takeover is acceptable; no handling needed.**
  `AlarmRingActivity.onNewIntent:141`'s `alarmId != previous` guard nulls the challenge and starts a
  fresh session for B. That is *required*, not incidental: B has its own tier and question count, and
  carrying A's `consecutiveWrong` over would hand B up to four free failures (the guard's own stated
  reason). An escape countdown in progress is also discarded — in the direction that makes dismissal
  **harder**, matching the project's existing convention for the un-persisted deadline, so it cannot
  be abused. Weighed against the alternative (nothing rings at all) this is strictly better, and the
  user was in front of a *silent* keypad when it happened. Ruled a sound judgement call.
- **Touching `AlarmRinger` was necessary**, despite the final hardening round listing it untouched —
  that was a description of that round's scope, not a contract. The service cannot infer exhaustion:
  `PlaybackRecovery.plan`'s verdict exists only on the audio thread, and the three alternatives are all
  worse. Timing it out in the service re-introduces a watchdog whose threshold would have to
  distinguish the start window — the one thing §4c constraint 1 forbids re-opening. A ringer→service
  callback is more code and puts a cross-thread call into the service, which is where Module 1's
  reviews found the bugs. Mirroring `restartPasses` across the boundary would destroy the
  thread-confinement that Module 1 round 3 verified and that justifies its deliberate lack of
  `@Volatile`. One publish-only volatile boolean written at the two sites that already exist is the
  minimum honest signal.
- **Test quality: the five new cases pin rules, not accidents.** `:104-112` pins the `&& !ringerIsRinging`
  conjunct, which is the one actually load-bearing piece. `:114-122` pins both `incoming == ringing`
  branches under both flag values. `:124-145` is a real differential property over 32 cells — it proves
  exhaustion changes outcomes in exactly the predicted set and nowhere else, which is the strongest
  claim available in pure code — subject to 4c-1.5 on what its comment says it proves. The 12
  pre-existing cases are byte-identical, so none was weakened.
- **Not covered by any test** (all acceptable, none gating): the ringer side has no test at all, so the
  write ordering, the clear in `start()` and the flag's whole lifecycle are asserted by comment only —
  which is where 4c-1.2 lives; and nothing tests what the takeover does to the overwritten alarm's
  **row**, which is where 4c-1.1 lives. Both are service/Android-level and consistent with the
  project's existing (accepted) absence of instrumentation tests, but the gap is exactly where this
  round's defect is.

#### §4c acceptance criteria audit

| # | Criterion | Verdict |
|---|---|---|
| 1 | `./gradlew assembleDebug` exits 0 | **Met** (independently verified before this review; clean build, no warnings) |
| 2 | `testDebugUnitTest` passes; all 138 existing tests still pass, none weakened | **Met.** 143 total. The 12 pre-existing `AlarmStartPolicyTest` cases are byte-identical — the `= false` default is what bought that — and no `@Ignore` exists anywhere |
| 3 | A unit test reproducing the §4c table: exhausted + different alarm → takeover | **Met** — `AlarmStartPolicyTest.kt:86-93`, named arguments, `(2L, 1L, isRinging = false, exhausted = true) → RING` |
| 4 | A unit test that a *non*-exhausted ringing alarm still refuses a different alarm | **Met** — `:95-102`, both the real-alarm and the test-row id |
| 5 | A unit test that `incoming == ringing` is unaffected in both exhausted and ringing states | **Met** — `:114-122`, all four combinations under both flag values |
| 6 | `ui/` still holds zero audio **code** references | **Met** — 8 hits, all prose in KDoc or comments (`AlarmRingActivity`, `Scaffolding`, `DiagnosticsScreen`); no import, no type, no call |
| 7 | No schema change, no new dependency, no `INTERNET`, no snooze | **Met** — `version = 1`, `MIGRATIONS = emptyArray()`, `app/schemas/` still holds only `1.json`; no manifest or Gradle change |

**All seven boxes pass, and the round still does not advance.** That is the finding to carry forward:
the acceptance criteria describe the *policy decision* and say nothing about the taken-over alarm's
row, so a change that satisfies all seven can still re-open backlog 1.8. Criterion 8, had §4c had
one, is "a taken-over one-shot ends up disabled, exactly as a dismissed or refused one does".

#### Verdict

**DO NOT ADVANCE** — 1 open MAJOR (4c-1.1), 0 open BLOCKERs. Round 2 is a one-item round: settle the
taken-over alarm's row in `handleStart`'s takeover branch, and correct §4c constraint 3 and the
`AlarmService.kt:137` / `AlarmStartPolicy.kt:33-35` comments that assert the premise it was built on.
4c-1.2 and 4c-1.3 are one line each and are worth taking in the same round while the file is open;
they do not gate.

### Round 2 — 2026-10-14

Reviewed: **0 blockers, 0 majors, 3 minors, 1 nit**. **ADVANCE** — all **eight** §4c acceptance boxes
pass, and this time box 8 is the one that was actually in doubt. 4c-1.1 (MAJOR) is **CLOSED**, and
4c-1.2 / 4c-1.3 / 4c-1.4 / 4c-1.5 are **CLOSED** as well; nothing from round 1 is left open. The
takeover now ends with the overwritten alarm's row in exactly the state a dismiss or a refusal would
leave it, through literally the same function (`disableIfOneShot`), and the one-line expression that
decides *which* row gets settled is correct on every reachable outcome — I walked all four branches
rather than trusting the dev's note, including the opposite-direction hazard the round was at risk of
(settling a row that should stay armed). It cannot happen: a non-`NO_ALARM` argument is reachable
only from the §4c clause.

The three minors are a durability hole on the catch path (narrow, compound, and strictly smaller than
the pre-existing hole sitting next to it), the same catch path's pre-existing failure to settle the
*incoming* alarm at all, and the one testable seam the dev's "criterion 8 is untestable" argument
missed. None gates. The deliberate deviation on call placement is **upheld** (Task 2 below), and the
missed ponytail target is **accepted** (over-engineering subsection below).

| # | Round 1 finding | Disposition | Evidence |
|---|---|---|---|
| **4c-1.1** | MAJOR — taken-over one-shot never auto-disabled | **CLOSED** | `AlarmService.kt:243-250` `settleTakenOverAlarm`, called at `:114` with the pre-overwrite `ringingAlarmId`. End to end: `disableIfOneShot:699-705` → `dao.disable(id)` (`AlarmDao.kt:29-30`, `UPDATE alarms SET enabled = 0`) + `AlarmScheduler.cancel(id)` (`:72-81`). Same function the dismiss (`:303`) and refusal (`:229`) paths call, so "exactly as a dismissed or refused one does" is literally true rather than an analogy. `AlarmRescheduler.rescheduleAll:48` only re-books `alarm.enabled`, so the phantom is gone. The false-premise comments in both files and §4c constraint 3 are corrected |
| **4c-1.2** | MINOR — `recoveryExhausted` stale-true into a fresh ring | **CLOSED** | Second clear at `AlarmRinger.kt:150`, inside `start()`'s posted lambda, with the main-thread clear kept at `:139`. Ordering verified: the lambda's body runs `recoveryExhausted = false` **before** `startPlaybackFrom(0)` at `:151`, so a *fresh* synchronous exhaustion (`:407-408`, reached from inside that same call) always lands after the clear and is never wrongly cleared. A previous generation's in-flight exhaustion write cannot outlive the lambda, because both run on the one audio `HandlerThread`. One residual interleaving survives and is logged as 4c-2.4 (NIT, no fix recommended) |
| **4c-1.3** | MINOR — id-less start could take over | **CLOSED** | `AlarmStartPolicy.kt:96` now reads `incomingAlarmId != NO_ALARM && ringerRecoveryExhausted && !ringerIsRinging`, pinned by the new test `AlarmStartPolicyTest.kt:114-121` (the one test the suite grew by, 143 → 144) and by the sweep's `incoming != NO_ALARM` term at `:149` |
| **4c-1.4** | NIT — field KDoc overstated its ordering guarantee | **CLOSED** | `AlarmRinger.kt:74-79` now says "**within one start generation**", states that it does *not* order against a new `start`, cites 4c-1.2, and names `&& !ringerIsRinging` as the real guard that must not be deleted. One sentence is still marginally over-strong — 4c-2.4 |
| **4c-1.5** | NIT — sweep comment claimed more than it proved | **CLOSED** | `AlarmStartPolicyTest.kt:141-147` now says the sweep is differential and *delegates* the `ringingAlarmId != NO_ALARM` claim to `the decision is total…` at `:160-184` through the 3-arg default. That is accurate: that test computes `nothingLive = ringing == NO_ALARM && !isRinging` and demands `REFUSE` otherwise |

#### Task 1 — the ternary, walked branch by branch

`settleTakenOverAlarm(if (alarmId != ringingAlarmId) ringingAlarmId else NO_ALARM)` at
`AlarmService.kt:114` is reached only for `DUPLICATE` and `RING` (the `REFUSE` return is at `:107-110`).
Four reachable outcomes, exhaustively:

1. **`DUPLICATE`** (`AlarmStartPolicy.kt:78`) requires `incomingAlarmId == ringingAlarmId` → ternary
   yields `NO_ALARM` → `:244` returns. No write.
2. **Policy branch 2** (`:83`, `incoming == ringing`, not ringing — the same alarm re-asserting itself,
   and the fresh `NO_ALARM/NO_ALARM` start) also has `alarmId == ringingAlarmId` → `NO_ALARM`. No write.
   This is the branch that matters most: it is the one that *does* overwrite nothing and must not settle
   the row it is about to ring.
3. **The §4c clause** (`:96`): `incoming != ringing` (branches 1-2 took every equal case), so the
   ternary yields `ringingAlarmId`. Non-`NO_ALARM` **only here** — and if `ringingAlarmId` happened to be
   `NO_ALARM` (an exhausted ringer whose id was already cleared), `:244` returns. This is the true
   takeover and the write is correct.
4. **Final `else`** (`:101`) requires `ringingAlarmId == NO_ALARM && !ringerIsRinging`, so the ternary
   yields `NO_ALARM`. No write.

So **no non-takeover id can ever reach the disable**, and the opposite-direction MAJOR (disabling a row
that should stay armed) does not exist. Two supporting facts: `ringingAlarmId` is read at `:103` and
again at `:114` on the same main-thread pass with nothing in between that writes it, and the only
`ACTION_START` producer in the app is `AlarmReceiver.kt:38` (`exported="false"`), so every id that can
sit in `ringingAlarmId` arrived on a consumed `AlarmManager` registration — which is what makes "off"
the honest row.

**Repeating alarms.** `disableIfOneShot:702` returns on `alarm.isRepeating`, and the takeover branch
calls `rescheduleIfRepeating` only for the **incoming** id (`:153`). The overwritten repeating alarm
therefore keeps the single next occurrence it booked at its own start: **no double-booking, no lost
occurrence.** Verified against the mid-ring-edit edge too (a row edited one-shot → repeating mid-ring
is left alone by the early return; repeating → one-shot is disabled, which is honest since its
occurrence is spent).

**The reserved test row (`-2`), both directions.** As the *overwritten* id: `disableIfOneShot(-2)`
finds the row (`AlarmRepository.kt:116-126`, `enabled = false`, `repeatDaysMask = 0`), re-writes
`enabled = 0` (a no-op) and calls `cancel(-2)`, whose `FLAG_NO_CREATE` lookup finds nothing because
that registration was consumed by the delivery that is ringing — and `scheduleTestAlarm:128` cancels
before every booking, so there is never a second `-2` registration to destroy. `requestCode(-2) = -2`
plus the per-alarm `mathalarm://alarm/-2` data URI keep it from touching any other alarm's
`PendingIntent`. `AlarmRescheduler.kt:42-45` still skips `-2` in both directions, so M3-1.1 is
untouched. As the *incoming* id: the takeover passes the real alarm's id, and the test button cannot
be reached anyway (`ringingAlarm()` reads `liveAlarmId`, which survives exhaustion).

#### Task 2 — ruling on the placement deviation (settle *before* the `try`): **upheld**

The dev's reasoning is correct and I would have asked for this placement if the sketch had said
otherwise. Three checks:

1. **The catch really does swallow a post-try call.** `AlarmService.kt:143-149` ends in `return` after
   `stopAlarm()`/`stopSelf(startId)`. A settle placed after the `try` would be skipped on exactly the
   deliveries where the overwritten alarm's row is least likely to be fixed by anything else — and A's
   registration is consumed whether or not B's start succeeds, so A needs settling on that path too.
2. **It keeps finding 1.14's rule intact.** 1.14 is that a catch which cancels the alarm must not be
   reachable from a non-essential step. Settling a *different* alarm's row is not essential to this
   alarm ringing; inside the `try` it would be. Before the `try` it is unreachable from the catch, which
   is the stronger form of what 1.14 asks.
3. **"Off" is the honest row on the throw path.** If B's `startForeground` or ringer throws, neither
   alarm rings and A reads off. The alternative is A reading *armed* with no registration, which
   `AlarmRescheduler.rescheduleAll` + `NextTrigger` convert into a ring on a morning the user never
   set an alarm for (backlog 1.8's harm, verbatim). A silent failure the user can see in the list beats
   a phantom ring they cannot predict, the row is kept and re-enableable per §2, and `AlarmHistory`
   already recorded A as fired at A's own start, so no information is destroyed. No failure mode was
   traded for a quieter one: on that path the user's loss (nothing rang) is identical either way, and
   only the *future* harm differs.

The one real cost of the placement is 4c-2.1 below — the write is in flight precisely when the catch
path makes the process cached — and it is smaller than the hole it replaces.

| # | Severity | File:line | Finding | Why it matters |
|---|---|---|---|---|
| **4c-2.1** | MINOR | `alarm/AlarmService.kt:114`, `:243-250`, `:143-149` | **The takeover's disable write is dispatched and never joined, and the catch path can freeze it.** `settleTakenOverAlarm` launches on `AlarmWork.scope` (`Dispatchers.IO`) and returns immediately. In the happy path this is safe for the reason the dev gives: the service goes foreground for B's ring and holds the wake lock for minutes, so a one-row Room update and a `PendingIntent` cancel commit long before anything is frozen. On the **failure** path it is not: A exhausts recovery, B takes over, `:114` dispatches A's disable, then `ensureChannel`/`startForeground`/`buildNotification`/`ringer.start` throws (`ForegroundServiceStartNotAllowedException`, a dead notification manager, an `IllegalStateException` from the handler thread), the catch runs `stopAlarm()` — `AlarmWakeLock.release()` + `stopForeground` — and `stopSelf(startId)`. With the screen off the process is now cached with no wake lock, and autosuspend can freeze the write mid-flight. That is finding **1.9**'s hazard exactly, and 1.9 is why the *dismiss* path joins its write under the lock (`:300-315`). This one does not join. | If the write is lost, A stays `enabled` with its registration consumed and `rescheduleAll` re-books it: backlog 1.8's phantom, i.e. round 1's MAJOR surviving for this one sub-case. Not gating, because the precondition is a conjunction of three unlikely events (wedged media stack through four passes → a second alarm inside that silent ring → a throwing start), because on that path nothing rang either way, and because a system kill rather than a `stopSelf` is self-healing (`START_REDELIVER_INTENT` replays both intents; A rings on a fresh ringer and B is refused and settled). Cheapest honest close, if it is ever taken: in the catch, before `stopAlarm()`, `join` the settle the way `handleDismiss` does — or simply accept it and record it, which is what I recommend, because 4c-2.2 is the larger half of the same path and has stood for four rounds |
| **4c-2.2** | MINOR | `alarm/AlarmService.kt:143-149` | **Pre-existing, found in this pass: the catch path never settles the *incoming* alarm's row at all.** Every `ACTION_START` that throws inside the `try` returns without `rescheduleIfRepeating(alarmId, …)` and without `disableIfOneShot(alarmId)` — `:151-157` are all below the catch's `return`. The delivery has already consumed that alarm's registration. | A **one-shot** that fails to start stays `enabled` with no registration → `rescheduleAll` re-books it for the same wall-clock minute tomorrow (1.8's phantom). A **repeating** alarm that fails to start never books its next occurrence and therefore **never rings again** until an app open or a boot runs the reconcile — the worse of the two, and the only route I found in this pass by which a repeating alarm can silently stop existing. Not introduced by §4c (it predates the takeover and applies to every failed start since Module 1), out of this round's one-defect scope, and bounded by reconcile-on-open; logged so it is on the record with 4c-2.1, which belongs to the same five lines. Shape, if taken: move `settleRefusedAlarm(alarmId, triggerAtMillis)` into the catch before `stopAlarm()` — the semantics already match ("its registration is gone either way") |
| **4c-2.3** | MINOR | `domain/AlarmStartPolicy.kt:96`, `alarm/AlarmService.kt:114` | **The id-selection invariant has no test, and there is a seam for one** (see Task 4). The invariant I had to verify by hand above — "a non-`NO_ALARM` id reaches `settleTakenOverAlarm` **iff** the §4c clause fired" — holds only because the other two `RING` branches either have `incoming == ringing` or an unset `ringingAlarmId`. Nothing pins that. A future branch added to `forStart` that returns `RING` with a *set* `ringingAlarmId` and no overwrite (for instance a "ring the test alarm anyway" exception) would make `:114` silently disable a row that is still armed, and all 144 tests would stay green. | This is the opposite-direction harm of 4c-1.1 — a user's armed one-shot reading off and never ringing — so the direction of a future mistake here is the dangerous one, not the conservative one. Closable in pure `domain/` without Robolectric: expose the selection beside the policy (e.g. `fun settleIdFor(outcome, incomingAlarmId, ringingAlarmId): Long`), have `AlarmService:114` call it, and sweep it against `forStart` over the existing 4x4x2x2 grid asserting non-`NO_ALARM` exactly on the §4c cells. That is a cross-check against a second function, not a restatement of the ternary. Logged as a MINOR ("missing test for a non-critical path" per §1) and not required: the code is correct today, and the ponytail cost is a domain function plus ~12 test lines |
| **4c-2.4** | NIT | `alarm/AlarmRinger.kt:74-79`, `:430`, `:458-459` | 4c-1.2's residual: a previous generation's `startPending = false` / `recoveryExhausted = true` can still land **after** a new generation has begun, in one interleaving the second clear cannot cover. `onPlaybackError:430` sets `audioAlive = false` while `startPending` is already false, so `isRinging` is false mid-recovery; a redelivered start for the *same* alarm is then decided `RING` (`AlarmStartPolicy.kt:83`), `start()` sets `startPending = true` and posts its lambda; the old `onPlaybackError` then continues to `GiveUp` and writes `startPending = false` + `recoveryExhausted = true` into the new generation; a *third* alarm arriving before the posted lambda runs sees exhausted-and-not-ringing and takes over. So the KDoc's "`&& !ringerIsRinging` is the real guard" is not absolute — in this interleaving the old generation clears the very flag the guard reads. | Deliberately **not** worth code: the window is the few microseconds between one audio-thread statement and the next queued message, it needs a third `ACTION_START` inside it, and the harm is bounded to the mild direction — alarm C takes the ringer from an alarm whose audio was already dead and mid-restart, C rings, and C's row is settled correctly. Closing it properly needs a generation token (an `Int` stamped per `start()` and checked before every volatile write), which is more machinery than the window is worth in a file whose thread-confinement is itself a verified invariant. Recommended action: qualify that one sentence in the KDoc, nothing else |

#### Task 4 — criterion 8 has no unit test: the dev's claim is **correct**, with one qualification

Verified, not taken on trust: `app/build.gradle.kts:55` is the only test dependency
(`testImplementation("junit:junit:4.13.2")`) — no Robolectric, no `androidx.test`, no
`androidTestImplementation` line at all, and `app/src` contains exactly `main` and `test`, so there is
no instrumentation source set to put such a test in. `settleTakenOverAlarm` is a `private` member of
an Android `Service`, and its terminal effects are `AlarmDao.disable` (Room, needs a real DB) and
`AlarmScheduler.cancel` (`PendingIntent` + `AlarmManager`, needs a real framework). New dependencies
are forbidden by criterion 7. **The row-settling itself genuinely cannot have a JVM unit test**, and
the dev saying so plainly rather than writing a test that asserts nothing is the right call.

The qualification: the dev's argument that extracting the ternary "would produce a test that restates
the ternary" is not quite right. Extracted into `domain/` and swept *against `forStart`*, it pins a
real invariant — the one that stands between this round and the opposite-direction MAJOR — and that
invariant is currently unpinned. That is 4c-2.3, and it is a MINOR, not a gate: it would cover the
*selection*, never the Room/`AlarmManager` effect, so criterion 8 would still not be a unit test.

**Criterion 8 is therefore verifiable only on the device/emulator run.** Exact steps, to be run
*before* the §4b device-run list's step 4 (which exercises the ordinary refusal path):

1. §4c's precondition cannot occur on demand — the third audio source is the bundled
   `res/raw/fallback_alarm.wav`, which does not fail on a healthy device — so exhaustion must be
   forced. On a scratch working copy that is **never committed**, make `AlarmRinger.candidateSources()`
   return a single unplayable source (e.g. `Source.FromUri(Uri.parse("content://invalid/0"))`) and
   leave `PlaybackRecovery`'s constants alone, so the real 3-pass budget is spent.
2. Arm two **one-shot** alarms: A at T+1 min with `vibrate = false`, B at T+6 min. Lock the screen.
3. A fires. Expect silence, then at ~4.6 s `AlarmRinger … recovery is exhausted; alarm is silent` in
   logcat. Confirm the notification and keypad are still A's.
4. B fires. Expect **no** `alarm … refused` line from `AlarmService`, and B's label/tier on the keypad
   (still silent under the hack). This is the takeover.
5. Solve B's math and dismiss. Then check the list: **A reads off and B reads off.**
6. Confirm the row, not just the UI: `adb shell run-as com.mathalarm …` on the Room DB (or Database
   Inspector) must show `enabled = 0` for A's id. This is the criterion, verbatim.
7. Force-stop and reopen the app (runs the reconcile), then reboot. **Neither alarm may come back
   armed, and nothing may ring at A's or B's wall-clock minute the following day.** A ring here is
   backlog 1.8 re-opened.
8. Revert the step-1 hack and re-run §4b's device-run step 4 to confirm the ordinary refusal path is
   unchanged.

Steps 5-7 are also the only observation of 4c-2.1: if A reads *armed* at step 5 after a clean
takeover, the unjoined write was lost and 4c-2.1 is upgraded on evidence.

#### Over-engineering (ponytail-review)

Round 1 asked for about **−10** comment lines; the round delivered **+20** (+10 code, +10 comment) and
said so. **Accepted, with one small shrink still on the table.** The accounting is honest and the
reasons hold:

- The triplication round 1 actually objected to **is gone**: `AlarmStartPolicy.kt:30-32` and
  `AlarmService.kt:135-137` are now 3-line pointers to one canonical argument, down from 6 and 8 lines.
  That is the −10 round 1 asked for; it was spent, not skipped.
- The canonical clause comment growing 8 → 11 lines (`AlarmStartPolicy.kt:85-95`) is **earned, and I
  would reject cutting it.** Round 1's own MAJOR came from a *comment* asserting a false premise, and
  the three per-conjunct lines are now the only place a would-be deleter learns why each conjunct
  exists. They are also *true*: deleting any one of the three — `&& !ringerIsRinging`,
  `incomingAlarmId != NO_ALARM &&`, or `ringingAlarmId != NO_ALARM ||` — makes the suite fail, verified
  by mutation before this review. A comment whose every claim is test-pinned at three lines per
  conjunct is cheaper than the round it would otherwise cost.
- `AlarmRinger.kt:74-79` and the two 2-line clears in `start()` sit exactly where a reorder or a
  "that's redundant" deletion would be typed. Earned.

What is still cuttable, both NIT and neither required:

- `AlarmService.kt:234-238`: `shrink:` the helper KDoc's first paragraph restates the 1.8 harm that
  `settleRefusedAlarm`'s KDoc already spells out 20 lines above (`:207-212`). Two lines — "a §4c
  takeover consumed this row's registration and it can never be dismissed, so a one-shot left
  `enabled` is backlog 1.8's phantom (4c-1.1)" — keep everything load-bearing. The second paragraph
  (`:240-242`, why only the `disableIfOneShot` half) must stay word for word; it is the anti-regression
  note for the one mistake a future editor would make here. **−4 lines.** The dev's offer to cut the
  KDoc to 3 lines is the right instinct; 9 lines for an 8-line method in a file where every private
  method carries one is not an abuse, so this is a preference, not a finding.
- `AlarmService.kt:224-232` + `:243-250`: `shrink:` the two settle helpers differ only in a log string
  and one call. `settleRefusedAlarm` could be `rescheduleIfRepeating(…)` plus a shared
  `disableOneShotLater(alarmId, what)`. **−4 lines.** Deliberately **not recommended**: round 1 asked
  for "~5 lines, no new abstraction", the duplication is two lines of boilerplate `launch`/`runCatching`
  rather than logic, and the two call sites carry different log text because they *are* different
  events. Listed for completeness and to be explicit that I considered it and said no.
- **Rejected again — the `= false` default on `ringerRecoveryExhausted`** and **`&& !ringerIsRinging`**.
  Both now mutation-proven load-bearing (the default is what keeps the 12 pre-existing cases
  byte-identical; the conjunct's deletion fails the suite). Round 1's rejections stand, with evidence
  rather than argument behind them.

`net: -4 lines possible.` Comment volume is justified at its current size; the only honest cut is one
duplicated paragraph, and it does not gate.

#### Verified correct (Round 2)

- **Criterion 8's end state, structurally.** `settleTakenOverAlarm` → `disableIfOneShot` →
  `dao.disable` + `AlarmScheduler.cancel` is the *same* call chain as a dismiss and a refusal, so the
  three paths cannot drift apart. A deleted row (`getById` null) returns early; an already-disabled row
  re-writes the same value; a consumed registration makes `cancel` a no-op via `FLAG_NO_CREATE`.
- **The ternary is correct on all four reachable outcomes, and non-`NO_ALARM` only on the §4c clause**
  (walked above). No non-takeover row can be disabled.
- **Repeating alarms are safe from double-booking**; the overwritten row keeps exactly the one
  occurrence booked at its own start.
- **The reserved test row is safe in both directions**, including `requestCode`/data-URI identity and
  `rescheduleAll`'s skip.
- **No race between `settleTakenOverAlarm` and `handleDismiss`'s `disableIfOneShot`.** The one
  interleaving that reaches both — a queued dismiss for A arriving after B took over — runs
  `handleDismiss:287` → stale, so no `silenceRinger`, then its own `disableIfOneShot(A)` concurrently
  with the settle's on `Dispatchers.IO`. Both are idempotent and write the same value (`enabled = 0`),
  `AlarmScheduler.cancel` is idempotent and one of the two simply finds no `PendingIntent`, and the
  dismiss's `finally` → `finishDismiss:420` sees `ringingAlarmId == B` and declines teardown, so
  backlog 2.2's guard still absorbs it. Worst case is one redundant `UPDATE`.
- **4c-1.2's new clear cannot swallow a fresh exhaustion.** `recoveryExhausted = false` at `:150`
  precedes `startPlaybackFrom(0)` at `:151` in the same lambda on the same thread, and both exhaustion
  sites (`:407-408`, `:458-459`) are downstream of that call or of a later audio-thread message. A
  same-generation exhaustion is therefore always observed.
- **Module 1's ten-path wake-lock accounting is untouched.** `settleTakenOverAlarm` contains no
  `AlarmWakeLock` reference, no `stopSelf`, no `stopForeground`; the takeover still rides A's single
  held lock (`acquire` early-returns while held, `setReferenceCounted(false)`), and B's dismiss
  releases it exactly once through `finishDismiss`.
- **Backlog 2.2's teardown guard is untouched** — `finishDismiss:420-431` still has all three teardown
  statements inside the `ringingAlarmId == NO_ALARM` branch — and **the `TEST_ALARM_ID` skip**
  (`AlarmRescheduler.kt:42-45`) is unchanged and unreachable from the new code.
- **Nothing was added to the critical start window.** `:114` is one `applicationContext` read and a
  `launch` on an already-constructed process-scope dispatcher, before `startForeground` but
  non-blocking, so the alarm-clock broadcast's foreground deadline is not eaten.
- **The suite grew by exactly one test and weakened none.** 144 `@Test` methods across 9 files, 144
  reported in `app/build/test-results/testDebugUnitTest/`, `AlarmStartPolicyTest` at 18 (the 12
  pre-existing cases at `:16-82` byte-identical), no `@Ignore`.
- **`AlarmStartPolicy` is still the only decision-maker and still pure.** One production call site
  (`AlarmService.kt:101`), one `recoveryExhausted` reader (`:105`), the clause still after both
  `incoming == ringing` branches and before an unchanged refusal — §4c constraints 1 and 5 hold
  structurally.

#### §4c acceptance criteria audit (all eight)

| # | Criterion | Verdict |
|---|---|---|
| 1 | `./gradlew assembleDebug` exits 0 | **Met** — independently verified before this review (clean build, green) |
| 2 | `testDebugUnitTest` passes; all 138 existing pass, none weakened | **Met** — 144/144, re-counted from both the sources and the JUnit XML; 143 → 144 is the single new case for 4c-1.3; the 12 pre-existing policy cases are byte-identical; no `@Ignore` anywhere |
| 3 | Unit test reproducing §4c's table: exhausted + different alarm → takeover | **Met** — `AlarmStartPolicyTest.kt:86-93` |
| 4 | Unit test that a *non*-exhausted ringing alarm still refuses a different alarm | **Met** — `:95-102`, real id and the `-2` row |
| 5 | Unit test that `incoming == ringing` is unaffected in both states | **Met** — `:123-131`, all four combinations under both flag values; `:104-112` additionally pins the exhausted-but-ringing refusal |
| 6 | `ui/` holds zero audio **code** references | **Met** — independently verified; hits are prose only |
| 7 | No schema change, no new dependency, no `INTERNET`, no snooze | **Met** — `version = 1`, `app/schemas/` holds only `1.json`, `junit:junit:4.13.2` remains the only test dependency, no manifest change |
| 8 | **A taken-over one-shot ends up disabled, exactly as a dismissed or refused one does** | **Met by construction, not by test.** Same `disableIfOneShot` call chain as both other paths; the id handed to it is correct on every reachable outcome (walked above); repeating rows and the `-2` row are exempt in the right direction. No JVM test is possible (Task 4), so this box is **confirmed on the emulator run only**, by the 8 steps in Task 4 — in particular the DB check at step 6 and the reboot at step 7. The selection half is testable and untested: 4c-2.3 |

#### Consolidated open 4c backlog

| # | Severity | Status |
|---|---|---|
| 4c-1.6 | NIT | Out of scope this round (triplicated rationale — substantially addressed anyway: one canonical home plus two 3-line pointers) |
| 4c-1.7 | NIT | Open — a takeover does not refresh the wake lock's 10-minute ceiling. Unchanged, recorded before the device run |
| 4c-2.1 | MINOR | Open — the takeover's disable write is unjoined and the catch path can freeze it |
| 4c-2.2 | MINOR | Open (pre-existing) — the catch path settles neither alarm's row; a failed repeating start never re-books |
| 4c-2.3 | MINOR | Open — no test pins "only a §4c takeover reaches `settleTakenOverAlarm`"; a `domain/` seam exists |
| 4c-2.4 | NIT | Open — residual cross-generation volatile interleaving; no code change recommended, one KDoc sentence to qualify |
| ponytail | NIT | `net: -4` — one duplicated paragraph in `settleTakenOverAlarm`'s KDoc |

Zero open BLOCKER, zero open MAJOR. 4c-1.1 through 4c-1.5 are closed.

#### Verdict

**ADVANCE.** The round did the one thing it was sent to do and did it in the smallest shape available:
nine lines of service code, one new private helper, no new abstraction, no new state, no new
dependency, and the policy file net zero. The MAJOR is genuinely closed rather than relocated — I
checked the one way this fix could have inverted into a worse bug (settling a row that should stay
armed) and it cannot happen on any reachable outcome. The deliberate placement deviation is the better
engineering call, and the missed comment target bought accuracy that round 1's own MAJOR proves is
worth more than ten lines.

What remains is three minors, one nit and the ponytail paragraph, none of which gates, and one honest
asterisk the ledger should carry forward: **criterion 8 is the first acceptance box in this project
that no amount of code review can close.** It is argued, not observed. Step 4 of §4b's device-run list
is now step 4 plus the eight steps in Task 4 above, and if A reads *armed* after a clean takeover,
4c-2.1 is the finding that was underrated — not 4c-1.1 re-opened.

## 4d. Round: settle the row on the failed-start path (4c-2.2)

**One defect, one fix.** User decision 2026-10-14: take this before the emulator run. Found
incidentally by 4c review round 2; it **predates §4c entirely**.

### The defect
`AlarmService.handleStart`'s cancelling `catch` does `stopAlarm(); stopSelf(startId); return`. The
settling calls — `rescheduleIfRepeating`, `recordFired` — sit **below that return**, so a start that
throws settles the incoming row not at all. The `AlarmManager` registration was already consumed by
the delivery either way.

Repeating daily 06:00 alarm, `startForeground` refused on Tuesday:

| Time | Event | Next registration | List shows | Rings? |
|---|---|---|---|---|
| Tue 06:00:00.0 | Fires; broadcast consumed; receiver takes the wake lock; service starts | — | **On, daily** | — |
| Tue 06:00:00.1 | `startForeground` throws | — | On, daily | — |
| Tue 06:00:00.1 | `catch` → `stopAlarm()` → `stopSelf` → **`return`** | — | On, daily | **no** |
| Tue 06:00:00.1 | `rescheduleIfRepeating` is below the return — never runs | **none** | On, daily | no |
| Wed / Thu / Fri … | Nothing is scheduled | none | On, daily | **no** |
| Eventually | App open (`reconcileAll`) or reboot (`BootReceiver`) re-books it | booked | On, daily | works again |

**The alarm is permanently dead while the UI insists it is armed**, and the only self-heals are
opening the app or rebooting — neither of which a user does *because* of a fault they cannot see.
One-shot variant: the row stays `enabled` with no registration, so `rescheduleAll` later re-books it
and it rings on a morning the user never set.

### The fix
`settleRefusedAlarm(alarmId, triggerAtMillis)` inside the catch. It already does exactly the right
thing for both row types: `rescheduleIfRepeating` books the repeating alarm's next occurrence, and
`disableIfOneShot` disables the one-shot. They are disjoint on `isRepeating`, so order is irrelevant.

### Constraints
- **Known weakness, must be addressed explicitly, not silently inherited.** This write lands on a
  path that is *about to* `stopAlarm()` (releasing the wake lock) and `stopSelf()` (making the
  process cached) — which is finding **1.9**'s hazard and the reason the dismiss path *joins* its
  write. Either join it, or state plainly in the report why not. Do not just dispatch and hope.
- Do not move the settle *above* the try: `ringingAlarmId` must not be disturbed, and §4c's
  `settleTakenOverAlarm` call at `:114` already occupies that position for a different row.
- Must not double-book: the success path still calls `rescheduleIfRepeating(alarmId, …)` below the
  try, so the catch path and the success path must be mutually exclusive (the catch `return`s).
- Must not disturb §4c's takeover settle, Module 1's ten-path wake-lock accounting, backlog 2.2's
  teardown guard, or the `TEST_ALARM_ID` skip.

### Acceptance criteria
- [ ] `./gradlew assembleDebug` exits 0
- [ ] `./gradlew testDebugUnitTest` passes; all **144** existing tests still pass, none weakened
- [ ] A failed start for a **repeating** alarm still books its next occurrence
- [ ] A failed start for a **one-shot** leaves the row disabled, not armed-without-registration
- [ ] The success path is unchanged — no double reschedule, no double `recordFired`
- [ ] The write's durability on the teardown path is either guaranteed or explicitly documented
- [ ] No schema change, no new dependency, no `INTERNET`, no snooze; `ui/` zero audio code refs

### Review Ledger — Round 4d
*Review agents append here. Do not edit by hand.*

### Round 1 — 2026-10-14

**0 blockers, 1 major, 2 minors, 1 nit. DO NOT ADVANCE.** The code change is right and is as small as
it could be — exactly one executable line, `settleRefusedAlarm(alarmId, triggerAtMillis)` at
`AlarmService.kt:151`, in the catch, before `stopAlarm()`, with the catch still ending in `return`.
4c-2.2's defect is genuinely closed on every branch I could reach: a repeating alarm whose start
throws now books its next occurrence, a one-shot ends up disabled rather than armed-without-a-
registration, the success path is byte-identical, and no row can be settled twice. Six of the seven
boxes pass. **The gating finding is the recorded reasoning, not the line.** §4d's constraint 1 and
criterion 6 exist precisely to force an honest decision about finding 1.9's durability hazard on this
path, and the decision was taken on a platform premise that the AOSP source contradicts in three
separate places — so the one join that review 4c-2.1 itself named as the "cheapest honest close" is
now recorded in this file as impossible, on a mechanism that does not exist. That is the same shape as
4c-1.1 and as §4c constraint 3: a comment asserting a false premise, with the code shaped around it.

| # | Severity | Location | Finding | Why it matters |
|---|---|---|---|---|
| **4d-1.1** | **MAJOR** | `alarm/AlarmService.kt:229-237` (the Durability KDoc), with `:151-153` and the §6 row | **The platform claim the "dispatch, not join" decision rests on is false in all three of its load-bearing parts.** Checked against AOSP `ActiveServices.java` + `ActivityManagerConstants.java` on `android14-release`, `android15-release`, `android16-release` and `main` — the relevant ordering and constants are identical on all four. (a) *"the likeliest reason this catch runs is `startForeground` failing, so delaying `stopSelf` would leave an unmet `startForegroundService` obligation outstanding"* — a `startForeground` that fails **inside the system has already discharged the obligation**. `setServiceForegroundInnerLocked` runs `r.fgRequired = false; r.fgWaiting = false; mServiceFGAnrTimer.cancel(r);` (`ActiveServices.java:2248-2251`, android16-release) **before every throw site a restriction / permission / type failure can reach**: the BOOT_COMPLETED `ForegroundServiceStartNotAllowedException` (`:2255`), the BG-FGS-start restriction (`:2571`), the API-34 FGS-type policy exceptions (`throw fgsTypeResult.second`, `:2612`), the app-op `SecurityException`, `StartForegroundCalledOnStoppedServiceException` and the FGS time-limit exception. AOSP's own comment at `:2618` says it outright — *"now that we've enforced the startForegroundService() contract"*. No timer is armed, so there is nothing to time out and nothing to be killed by. (b) *"the immediate `stopSelf(startId)` is what discharges it"* (ledger wording) — it does the opposite. Where the obligation genuinely **is** unmet (`ensureChannel()` / `buildNotification()` threw, so `startForeground` was never reached), `bringDownServiceLocked` sees `r.fgRequired` still true and posts `SERVICE_FOREGROUND_CRASH_MSG` (`:6361-6386`) → `serviceForegroundCrash` (`:7653-7661`) → `crashApplicationWithTypeWithExtras(…ForegroundServiceDidNotStartInTimeException…)`. The immediate `stopSelf` **is** what detonates the crash. (c) *"delaying `stopSelf` by up to 10 s parks on the platform's own FGS-start deadline"* — the deadline is **30 s**, not 10: `DEFAULT_SERVICE_START_FOREGROUND_TIMEOUT_MS = 30 * 1000`, plus a further `DEFAULT_SERVICE_START_FOREGROUND_ANR_DELAY_MS = 10 * 1000` before the ANR is raised, identical on API 34/35/36 and main. `DISMISS_WRITE_TIMEOUT_MS` is 10 s, so even in the unmet case a `handleDismiss`-shaped join has ~20 s of headroom. | **Concrete scenario.** Daily 06:00 repeating alarm. The API-34 FGS-type check denies `mediaPlayback`, or the BG-FGS-start restriction denies the start on an OEM build where the alarm-clock temp-allowlist does not apply; `ServiceCompat.startForeground` throws. `fgRequired` is already false and the FGS timer already cancelled, so the join 4c-2.1 recommended is **fully available**. The code instead dispatches and immediately runs `stopAlarm()` → `AlarmWakeLock.release()` → `stopSelf(startId)`. 06:00, screen off, no wake lock held anywhere in the process, no foreground component, process cached: autosuspend freezes the `Dispatchers.IO` thread with `AlarmScheduler.schedule` not yet done. That is finding **1.9** verbatim, and §4d's own timeline table resumes from "Wed / Thu / Fri … nothing is scheduled". The write that exists to close 4c-2.2 is lost in the exact case the comment claims it cannot be protected. The second harm is to the record: 4c-2.1 named this join as the cheapest honest close, and this round closes it as impossible on a mechanism that does not exist, so nobody will revisit it. **Smallest fix:** the shape already in this file — dispatch the settle, and move `stopAlarm(); stopSelf(startId)` into a `mainHandler.post { }` from the settle's `finally` under a `withTimeoutOrNull` ceiling of ≤10 s, exactly as `handleDismiss:318-334` does. Any ceiling under ~25 s is safe even in the unmet-obligation sub-case, where the process is crash-killed by `stopSelf` whenever it happens, so deferring cannot make that worse in kind. **If the dispatch is kept instead**, the KDoc and the §6 row must be rewritten to the true reasons — there is no *foreground* state to hold once `startForeground` has failed, and the unmet-obligation sub-case is crash-killed on `stopSelf` regardless — including the honest admission that the **wake lock is holdable** and would protect the write, so this is a complexity trade, not a platform constraint |
| **4d-1.2** | MINOR | `alarm/AlarmService.kt:152-153` (**pre-existing**, surfaced by 4d-1.1's research) | On the sub-case where the FGS obligation really is unmet — `ensureChannel()` or `buildNotification()` threw before `startForeground` was reached — the catch's `stopSelf(startId)` makes the *system* crash the process: `bringDownServiceLocked` with `r.fgRequired` still true → `SERVICE_FOREGROUND_CRASH_MSG` → `ForegroundServiceDidNotStartInTimeException` (`ActiveServices.java:6361-6386`, `:7653-7661`). Scenario: a background app update swaps the APK and `getString(R.string.alarm_channel_name)` inside `ensureChannel()` throws `Resources.NotFoundException` at 06:00 — a real-world cause of this catch. | The alarm does not ring *and* the user gets an "app keeps stopping" crash, with no logcat trail that points at the real cause. Not introduced by this round (the `stopSelf` predates Module 1's last review) and outside 4d's one-item scope, so logged rather than gating. It also means the newly dispatched write has effectively no chance of committing on this sub-case: the crash reaches the main thread within milliseconds of the `launch`. The honest closes are a minimal fallback `startForeground` before giving up, or accepting the crash and saying so in the KDoc instead of claiming `stopSelf` discharges the obligation |
| **4d-1.3** | MINOR | `alarm/AlarmService.kt:243-251` (**pre-existing**; Task 3's ruling) | **Ruled genuinely pre-existing and benign; routing the catch through it does not make it newly matter.** The two halves each `getById` independently on `Dispatchers.IO`, so a row edited one-shot↔repeating between the reads can be acted on by both. Worst reachable interleaving: half 1 reads `isRepeating && enabled` and books the next occurrence, the user flips the row to one-shot, half 2 reads `!isRepeating` and runs `dao.disable` + `AlarmScheduler.cancel` — leaving a **disabled row with a live registration**, i.e. one spurious ring, after which `rescheduleIfRepeating` finds `enabled == false` and it goes quiet for good. The mirror interleaving resolves to "off", which is honest. | Requires the user to be editing that alarm's repeat days inside the millisecond window between two IO reads, at the moment that alarm's start threw at 06:00 — not reachable in practice, and identical in character on the four-rounds-old `refuseStart` path. The new call site adds a second, rarer trigger and no new state. **Cheapest close is one read, not ordering or a transaction:** `settleRefusedAlarm` could do a single `getById` and branch on `isRepeating`, which removes the race *and* a DB round-trip. Explicitly **not** recommended inside 4d — it touches the refusal path, and 4d is a one-item round |
| **4d-1.4** | NIT | `alarm/AlarmService.kt:151` (with `:498-505`) | The new call is the last thing in the catch that can throw, and it *can*: `rescheduleBaseline` (`:498`) runs synchronously before the `launch`, and `Instant.ofEpochMilli(triggerAtMillis).atZone(…).plusMinutes(1)` throws `DateTimeException` / `ArithmeticException` for an out-of-range `EXTRA_TRIGGER_AT`. A throw there escapes `handleStart` → `onStartCommand`, and under `START_REDELIVER_INTENT` the same intent returns: backlog **2.7**'s redelivery-crash-loop shape. | Not reachable with our own extras — `AlarmScheduler.firePendingIntent:113` bakes in a real epoch-millis instant, the service is not exported, and the success path at `:159` makes the identical call — so there is no new class of risk and no live bug. Recorded only because finding **1.14**'s rule now has a one-line gap *inside* the alarm-cancelling structure itself: `runCatching { settleRefusedAlarm(…) }.onFailure { Log.e(…) }` closes it in one line, which is exactly what the `recordFired` call site at `:162-163` already does |

#### Over-engineering (ponytail-review)

- `AlarmService.kt:146-150`: `shrink:` the 5-line call-site comment restates §4d's defect narrative,
  which already exists in the §4d spec body, the §6 row, the dev's ledger entry and — from the other
  direction — the method's own KDoc two screens down. Two lines carry everything load-bearing: "the
  registration is spent whether or not it rang, so the row still has to be settled (review 4c-2.2)"
  plus the ordering note, "before `stopAlarm()`, so the write starts while the wake lock is held".
  **−3 lines.** Non-gating.
- `AlarmService.kt:229-237`: the 9-line durability paragraph is **requested prose** — §4d constraint 1
  demands the decision be stated, and 4c's MAJOR came from a comment asserting a false premise, so an
  accurate recorded decision has demonstrated value in this file. **Keep it, do not trim it.** The
  objection is not its length: it is 4d-1.1, that the content is wrong. A comment that is wrong is
  worth less than no comment, and the rewrite 4d-1.1 asks for will not be shorter.
- `AlarmService.kt:226-227`: the 2-line "Called from [refuseStart] and from [handleStart]'s cancelling
  `catch`" paragraph is the anti-regression note that keeps the next editor from splitting the helper
  per call site. **Keep.**
- **Checked and absent, as the dev claimed it declined them:** no `Job`-returning settle, no
  `settleFailedStartAlarm` duplicate of `settleRefusedAlarm`, no `postDelayed` half-join (the only
  `postDelayed` in the file is the pre-existing M2-2.1 idle stop at `:392`, untouched), and no new
  class, field, flag, constant, dependency or test double. The diff adds **exactly one executable
  line**; the other 18 are comment and KDoc.

`net: -3 lines possible.` One executable line to stop a repeating alarm dying permanently is about as
lean as a fix gets; the only honest cut is the duplicated narrative at the call site.

#### Verified correct

- **Exactly one executable line, in the right place.** `:151`, inside the catch, above `stopAlarm()`
  so the dispatch happens while the wake lock is still held; the catch still ends in `return`. The six
  `AlarmWakeLock` call sites and Module 1's ten-path accounting are untouched, backlog 2.2's teardown
  guard at `finishDismiss:438-450` is untouched, and `AlarmRescheduler`'s `TEST_ALARM_ID` skip is
  unreachable from the new code.
- **The catch and §4c's takeover settle can never act on the same row — proved, not argued.**
  `settleTakenOverAlarm(if (alarmId != ringingAlarmId) ringingAlarmId else NO_ALARM)` at `:114` passes
  a non-sentinel id only when `alarmId != ringingAlarmId`, and then passes `ringingAlarmId`; the catch
  always acts on `alarmId`. The two arguments are unequal by construction in that branch, and in the
  complementary branch the takeover settle is handed `NO_ALARM` and returns at its first line. Walked
  on all five reachable `AlarmStartPolicy` outcomes (`DUPLICATE`, both `incoming == ringing` branches,
  the §4c clause, the final `else`); on the `else` fresh-start branch `ringingAlarmId` is `NO_ALARM`,
  so the ternary's true arm is also a no-op.
- **No double-settle with the success path, and no double `recordFired`.** `rescheduleIfRepeating` and
  `recordFired` sit at `:157-163`, below the `try`; the catch `return`s above them. Criterion 5 holds
  byte-for-byte — the diff's only non-comment change is inside the catch.
- **The `DUPLICATE` early return reaches neither the catch nor the post-try settle** — it is a plain
  `return` inside the `try`, and a `return` does not transfer to a `catch`. And where the catch *is*
  reached on a duplicate delivery (a throw from `AlarmWakeLock.acquire`, which sits above the duplicate
  check), the settle is **idempotent, not a double-book**: the redelivered intent carries the *same*
  `EXTRA_TRIGGER_AT`, so `rescheduleBaseline` yields the same baseline, `NextTrigger` the same
  occurrence, and `requestCode(alarmId)` + `FLAG_UPDATE_CURRENT` replaces the identical registration
  in place. The one-shot half disables a row that the eventual dismiss would have disabled anyway.
- **The repeating alarm's next occurrence is genuinely booked on the failure path.** Walked
  `:151` → `rescheduleIfRepeating:484` → `rescheduleBaseline:498` → `getById` → `isRepeating && enabled`
  → `AlarmScheduler.schedule`. The row **is** still `enabled`: nothing on this path disables it
  (`disableIfOneShot` early-returns for a repeating row), and the §4c settle acts on a different id.
  `EXTRA_TRIGGER_AT` **is** present on this delivery — baked in at `AlarmScheduler.kt:113`, forwarded
  by `AlarmReceiver.kt:29,38`, read at `AlarmService.kt:94`. The baseline is computed **synchronously
  on the main thread** before the `launch`, so it cannot drift with coroutine scheduling. And it cannot
  land before the consumed occurrence: `trigger + 1 min` is strictly after it (alarms are
  minute-granular), `NextTrigger` returns the first candidate strictly after the baseline, consecutive
  occurrences of one hour/minute are ≥23 h apart, and the `triggerAtMillis <= 0` arm falls back to
  `now` — which is still past the occurrence that just fired. Finding 1.15 stays closed on this path.
- **`START_REDELIVER_INTENT` replay is idempotent.** If the process dies before `stopSelf(startId)`,
  the same intent re-enters `handleStart` with the same `EXTRA_TRIGGER_AT`; the reschedule recomputes
  from wall clock to the same instant and `FLAG_UPDATE_CURRENT` + the per-alarm `requestCode` and data
  URI replace the registration rather than adding one. A second `disableIfOneShot` re-writes
  `enabled = 0` and finds no `PendingIntent` to cancel.
- **`TEST_ALARM_ID` (-2) and the id-less case are no-ops in the right direction.** The `-2` row is
  stored `enabled = false, repeatDaysMask = 0`, so `rescheduleIfRepeating` books nothing and
  `disableIfOneShot` re-writes the same `enabled = 0` and cancels an already-consumed registration.
  An `ACTION_START` with no id gives `alarmId == NO_ALARM` and `settleRefusedAlarm` returns at its
  first line — correct, because there is no row to settle.
- **No partial settle on a stable row.** The halves are disjoint on `isRepeating`, so the four
  commit/not-commit combinations collapse to exactly two end states: fully settled, or the pre-fix row.
  The only partial state inside `disableIfOneShot` is `dao.disable` committing without
  `AlarmScheduler.cancel`, and the cancel is already belt-and-braces here — the registration this
  delivery consumed is gone, so `FLAG_NO_CREATE` finds nothing. The dispatch therefore introduces no
  state the old code could not reach, which is the half of the dev's argument that **does** hold.
- **Criterion 7, re-verified from the tree:** `version = 1` with only `app/schemas/.../1.json`, no
  `INTERNET` in the manifest, `junit:junit:4.13.2` still the only test dependency, no manifest change
  at all, no `snooze` outside a comment saying there deliberately is none, and `ui/`'s audio hits are
  prose in KDoc only.
- **144 tests, none weakened.** Re-counted 144 `@Test` across 9 files (34/27/18/17/12/10/10/10/6), no
  `@Ignore`. The dev's claim that the only pure logic involved is already pinned checks out:
  `AlarmDayMaskTest.kt:93,101` asserts `isRepeating` in both directions, which is the disjointness the
  two halves rely on.

#### §4d acceptance criteria audit (all seven)

| # | Criterion | Verdict |
|---|---|---|
| 1 | `./gradlew assembleDebug` exits 0 | **Met** — independently verified before this review |
| 2 | `testDebugUnitTest` passes; all 144 pass, none weakened | **Met** — 144 re-counted from the sources, no `@Ignore`, no test deleted or relaxed, and no new test (correctly: the catch is reachable only through `AlarmService` and its effects end in Room and `AlarmManager`, with `junit` the only test dependency) |
| 3 | A failed start for a **repeating** alarm still books its next occurrence | **Met in the code** — traced end to end (see *Verified correct*): row still `enabled`, `EXTRA_TRIGGER_AT` present, baseline computed on the main thread, no re-book of the consumed occurrence. The residual is durability, not logic: see 4d-1.1 |
| 4 | A failed start for a **one-shot** leaves the row disabled, not armed-without-registration | **Met in the code** — same `disableIfOneShot` chain as the dismiss, the refusal and §4c's takeover, so the four cannot drift. Same durability residual |
| 5 | The success path is unchanged — no double reschedule, no double `recordFired` | **Met** — the diff's only executable change is inside the catch, and the catch `return`s above `:157-163` |
| 6 | The write's durability on the teardown path is either guaranteed or explicitly documented | **NOT MET — 4d-1.1.** It is documented, at length and deliberately, but the documentation is false in three independent places, and the "guaranteed" branch **is** achievable here: the FGS obligation is already discharged in the case the comment names as most likely, the deadline is 30 s rather than 10, and `handleDismiss`'s join shape is sitting in the same file. This is the box §4d's constraint 1 was written to protect, and it is the one the round did not clear |
| 7 | No schema change, no new dependency, no `INTERNET`, no snooze; `ui/` zero audio code refs | **Met** — re-verified from the tree |

#### Final readiness delta

Both §4c and §4d landed in the firing chain's *failure* paths, so what changed for the first device run
is mostly what to watch when something goes wrong rather than when it goes right.

- **The happy path is unchanged by both rounds.** Nothing in §4c or §4d touches a start that succeeds,
  so the original device-run plan — arm, let it ring, solve, dismiss, re-check the list and the
  next-alarm indicator — stands exactly as written. Neither round moved the alarm-clock broadcast's
  foreground deadline: §4c added one `applicationContext` read and a `launch` before `startForeground`,
  and §4d added nothing at all inside the `try`.
- **Two of the three failure paths out of `handleStart` now settle their row**, and the one that still
  does not is the id-less start (nothing identifies a row to settle). So after this run the list and
  the next-alarm indicator should be *honest* after any refused, taken-over or failed start — that is
  now the thing worth checking, and it is checkable only on a device.
- **Newly worth observing on the emulator**, beyond §4c's eight steps:
  1. **Force the catch.** Revoke `POST_NOTIFICATIONS`, or run `adb shell cmd appops set <pkg> START_FOREGROUND ignore`, or temporarily point `foregroundServiceType` at a type the app has no permission for, then fire the test alarm. Expect `E/AlarmService: failed to start alarm …`, then — within a second or two — a *new* `I/AlarmScheduler: alarm <id> scheduled for …` for a repeating row, or `enabled = 0` for a one-shot. If the reschedule log never appears, 4d-1.1's lost-write window is **observed** rather than argued, and the MAJOR is confirmed on evidence rather than on source reading.
  2. **Run 1 with the screen off and the device in Doze** (`adb shell dumpsys deviceidle force-idle`) rather than awake. That is the configuration in which the dispatched write races autosuspend; an awake emulator will almost always let it commit and will therefore *not* reproduce 4d-1.1.
  3. **Check logcat for `ForegroundServiceDidNotStartInTimeException` / "did not then call Service.startForeground"** on any failed start. Its presence identifies 4d-1.2's sub-case (the throw happened before `startForeground` was reached) and distinguishes it from the restriction sub-case, which produces no such crash. Worth knowing which one the emulator actually hits, because the two have different fixes.
  4. **`adb shell dumpsys alarm | grep mathalarm` after a failed repeating start**, then again after a reboot. The first tells you whether §4d's write landed; the second tells you whether `BootReceiver`'s reconcile healed it anyway, which is the self-heal both 4c-2.2 and this round lean on.
- **Nothing in §4d needs a new emulator step for the *success* path**, and criterion 6 is the only box
  in this round that a device can settle. Criterion 8 of §4c remains the only box in the project that
  no unit test can close; §4d adds criteria 3, 4 and 6 to that list — three of its seven boxes are
  argued from the source, and step 1 above is what turns them into observations.

**DO NOT ADVANCE.** 0 blockers, 1 open **MAJOR** (4d-1.1), 2 minors, 1 nit. The §1 bar is zero open
blockers and zero open majors. The one-line code change should **stay as it is** — it closes 4c-2.2
and it is the right line in the right place; round 2 is a one-item round on the durability decision:
either adopt `handleDismiss`'s deferred-teardown shape in the catch, or keep the dispatch and rewrite
the KDoc (`:229-237`), the dev ledger row and the §6 row to the reasons that are actually true.

## 4e. Scope closed

**Project owner's decision, 2026-10-14: stop here and work with what exists.** No further review
rounds. This section records what is deliberately left open so nothing reads as finished that is not.

### Accepted, not fixed
- **4d-1.1 (MAJOR)** — the failed-start settle is dispatched, not joined, so it can be lost if the
  process is frozen before it commits. One transient `startForeground` failure can therefore cost a
  repeating alarm more than one morning, until reconcile-on-open or a boot heals it. The code is
  correct and strictly better than the status quo; what was wrong was the *justification*, and that
  has been corrected in `AlarmService.settleRefusedAlarm`'s KDoc against real AOSP. The join shape,
  and the emulator step that would turn this from argument into observation, are both recorded in
  the Round 4d ledger. **§4d acceptance criterion 6 is the one unmet box in the project.**
- **The watchdog alarm** — deferred pending a device run (§4b). Still BRAINSTORM §6's only absent
  mitigation, and still the thing to build first if any firing is late or missed.
- **The consolidated backlog** across all rounds — minors and nits, none of which change whether the
  alarm rings. 4d-1.2 (a `stopSelf` crash-kill when the FGS obligation is genuinely unmet) and
  4c-2.1 (the takeover settle's identical unjoined write) are the two worth looking at first.

### Verified only by argument, never observed
§4c criterion 8 and §4d criteria 3, 4 and 6. Each is correct by source-level trace and by sharing a
call chain with paths that *are* tested, but none is reachable from a JVM unit test, and this project
has no instrumentation tests by design (§0). The emulator steps in the Round 4c and 4d ledgers are
what convert them.

### The honest summary
The Android platform contract is satisfied as far as code review can establish. Whether a *particular
phone* honours that contract is untested, and the single largest risk — OEM battery killers — is one
no amount of further review can reduce, because every mitigation this app has is advisory.

## 5. Explicitly out of scope for v1

Snooze · multiple puzzle types (memory, typing, QR, shake) · difficulty
auto-escalation · custom alarm sounds · gradual volume · themes · stats and
streaks · sleep tracking · widgets · Wear OS · backup/restore · accounts ·
cloud sync · analytics

---

## 6. Progress log

| Date | Module | Round | Event |
|---|---|---|---|
| 2026-10-09 | — | — | Spec created. Toolchain verified: clean-shell `assembleDebug` exits 0, 11 MB Compose APK |
| 2026-10-09 | 1 | dev 1 | Alarm engine implemented: Room store, `AlarmScheduler` (`setAlarmClock`), receiver -> foreground service -> full-screen `AlarmRingActivity`, boot/time/timezone rescheduling, debug 10 s trigger. `assembleDebug` exits 0; `testDebugUnitTest` 17/17 green |
| 2026-10-09 | 1 | review 1 | Reviewed: **0 blockers, 4 majors, 13 minors, 5 nits**. Architecture sound; `exported="false"` receivers, cancel/schedule PendingIntent equality, listener-less audio focus, `mediaPlayback` with no FGS timeout and the direct-boot audio fallback all verified correct. Majors gating advancement: alarm-stream volume permanently pinned at max across a `START_REDELIVER_INTENT` restart (1.1); unconditional `requestDismissKeyguard` puts the bouncer over the Dismiss button on every secure device (1.2); `fallbackToDestructiveMigration` will silently wipe all alarms on the first schema change (1.3); no `MediaPlayer` error listener, so a mid-ring playback error silences the alarm permanently (1.4) |
| 2026-10-09 | 1 | dev 2 | Round-2 fixes: all 4 majors (1.1 durable DE-storage pre-ring volume record; 1.2 `requestDismissKeyguard` removed; 1.3 `exportSchema = true` + committed `app/schemas/.../1.json` and destructive fallback removed; 1.4 `setOnErrorListener` re-entering the source fallback chain) plus minors 1.5, 1.6, 1.9, 1.13, 1.14, 1.15 and 1.17. `assembleDebug` exits 0; `testDebugUnitTest` 27/27 green (17 pre-existing + 10 new day-mask tests) |
| 2026-10-09 | 1 | review 2 | Reviewed: **1 new major, 7 new minors, 2 new nits**; of the 11 Round 1 findings claimed, **8 closed, 3 partial, 0 still open**. All four Round 1 majors addressed; 1.1/1.2/1.3 genuinely closed (durable DE-storage volume record survives `START_REDELIVER_INTENT`, force-stop and a DND-rejected restore; keyguard bouncer gone; committed `1.json` matches the entity and is outside `build/`). 1.4 partial: the recovery chain terminates and is re-entrancy-safe, but `startPlaybackFrom(failedIndex + 1)` has no terminal case, so an async `MEDIA_ERROR_SERVER_DIED` on the last source — the one direct boot relies on — leaves the alarm permanently silent (**2.1, MAJOR**). Both dev pushbacks upheld: Round 1's "hard-gate Module 3 scheduling on `canUseFullScreenIntent()`/notifications" guidance **withdrawn** (`USAGE_ALARM` on `STREAM_ALARM` rings without any FSI, so refusing to schedule would turn a degraded alarm into no alarm); the direct `startActivity` is a real foreground fast path, not dead code. Wake-lock accounting re-traced across all ten paths — no leak. **DO NOT ADVANCE**; round 3 is a one-item round: fix 2.1 only |
| 2026-10-09 | 1 | dev 3 | Round-3 one-item round: MAJOR **2.1** fixed and nothing else. `onPlaybackError` now routes through a new pure `PlaybackRecovery.plan(failedIndex, sourceCount, what, restartPassesUsed)`: a source-specific error still falls forward, but an exhausted chain **and every `MEDIA_ERROR_SERVER_DIED`** (not source-specific) wrap around to `audioHandler.postDelayed({ startPlaybackFrom(0) }, 1500)` under a 3-pass bound, with `startPending` held true across the delay so `isRinging` never reads false mid-recovery. The synchronous all-sources-failed exit of `startPlaybackFrom` spends the same budget rather than going silent. Exhausted budget = `Log.e` + honest `isRinging == false`. `assembleDebug` exits 0; `testDebugUnitTest` 37/37 green (27 pre-existing + 10 new `PlaybackRecoveryTest`) |
| 2026-10-09 | 1 | review 3 | Reviewed: **MAJOR 2.1 CLOSED; 0 open blockers, 0 open majors; 2 new minors, 3 new nits**. Traced the direct-boot scenario end to end: `MEDIA_ERROR_SERVER_DIED` on the raw tone at index 2 now wraps to `Restart(0, 1500)` and the alarm rings again after one 1.5 s retry. `PlaybackRecovery.plan` is total (including `sourceCount` 0 and 1); the `fromIndex == 0` wrap-around test is sound, and it is what keeps the chain finite when source 0 itself keeps dying; `restartPasses` is reset only inside `start()`'s posted lambda and every read/write is on the audio looper, so the deliberate absence of `@Volatile` is correct; the async and synchronous retry paths share one budget, bounded at `(MAX_RESTART_PASSES + 1) * sourceCount` attempts over ~4.5 s, with at most one recovery queued; no player leaks across the delay and `shutdown()` strands nothing. Dev's scope extension to the **synchronous** all-sources-failed exit ruled **in scope and necessary** — same defect one step along, and 2.1's own citation listed `:252-254`. New findings all non-gating: **3.1** `start()` does not purge the audio queue (worst case one extra chain restart), **3.2** nothing consumes the honest `isRinging == false` after `GiveUp`, **3.3-3.5** off-by-one-pass KDoc bound, termination test mirrors rather than calls the host's counter-advance, and `candidateSources()` re-read for the count. **ADVANCE to Module 2** |
| 2026-10-09 | 2 | dev 1 | Math gate implemented. Pure `domain/ProblemGenerator.kt` (3 tiers, constructive generation + an `isWellFormed` validator that decides MEDIUM by actual carry/borrow, division built backwards from a product) and pure `domain/ChallengeState.kt` (immutable session: problem, entry, solved, consecutive-wrong, no-repeat memory, escape gate). `AlarmRingActivity`'s Dismiss button replaced with a large numeric keypad graded on submit, a live clock, a `2 / 3` progress line, haptic + red error + a brand-new problem on every wrong answer, and the 5-failure / 60-second escape hatch. Session state hoisted into one activity-scoped `mutableStateOf` saved to the activity `Bundle`; escape deadline deliberately never persisted. Anti-cheat: `OnBackPressedCallback`, volume-key consumption. No audio class is referenced anywhere in `ui/`. Backlog **1.16** fixed (label/alarmId now Compose state). `assembleDebug` exits 0 with no warnings; `testDebugUnitTest` 86/86 green (37 pre-existing + 24 `ProblemGeneratorTest` + 25 `ChallengeStateTest`) |
| 2026-10-09 | 2 | review 1 | Reviewed: **0 blockers, 1 major, 8 minors, 4 nits**. Every route to `AlarmService.dismissIntent` traced: exactly the two claimed call sites, both earned, and rotation, process death, `onNewIntent`, an empty entry, `questionCount <= 0`, a 6-digit-capped entry and two rapid Submit taps all fail closed. `ProblemGenerator` brute-forced against its own validator over every reachable operand combination: MEDIUM subtraction bottoms out at **1** after the borrow, HARD two-step's `c` cap can neither throw nor go non-positive, no division exists in the generation path, and the 300-attempt bound can neither hang nor throw. All three escape locks real, `consecutiveWrong` resets honestly, the minute is a full 60 000 ms, and the deadline is persisted nowhere. The one gating MAJOR is the dev's own third self-disclosure, larger than disclosed: on API 34+ the foreground-service notification is swipeable and is the **only** route back to the keypad once `onStop` fires (`excludeFromRecents`, no path from `MainActivity`), so swipe-away + Home strands a willing user in front of an alarm they cannot dismiss (**M2-1.1**). Disclosure 1 (volume re-assertion deferred) **upheld** — the `ContentObserver` really would re-open finding 1.1 — but §3's residual text is mis-scoped: the notification shade takes window focus even when every permission is granted, and diagnostics cannot close that (M2-1.7). Disclosure 2 (rotation restarts the minute) **upheld** and verified. **DO NOT ADVANCE**; round 2 is a one-item round: M2-1.1 only |
| 2026-10-09 | 2 | dev 2 | Round-2 fixes: MAJOR **M2-1.1** plus the promoted **M2-1.6** and minors **M2-1.2/1.3/1.4/1.5**. The alarm notification now carries a `setDeleteIntent` pointing at a new internal `AlarmService` action that re-posts it via `startForeground` while `ringingAlarmId` is set, so an API 34+ swipe can no longer sever the only route back to the keypad; the action never touches the wake lock, never writes `ringingAlarmId`, and never stops the service while an alarm is live. The tier read is raced against a 5 s timeout so the MEDIUM × 1 fallback survives a *hung* `getById`, not just a throwing one. The dismiss predicate became "this submit transitioned the session to solved". The HARD-division validator now checks the quotient (`96 ÷ 96`, `12 ÷ 12`, `80 ÷ 8` rejected) and no longer applies the factor rule to the dividend (`20 ÷ 4`, `30 ÷ 6`, `60 ÷ 12` accepted — all 72 constructible pairs pass). The countdown ceiling collapsed onto the tested `ChallengeState.escapeSecondsLeft`, which the composable now calls. The eleven `putX` calls became one encoded session string with a pure `ChallengeState.encode`/`decode` pair, so the `Bundle` round trip is genuinely unit-tested and no field has a default any more. M2-1.9 investigated and **left**: there is no way to raise a Compose/`View` haptic above `USAGE_TOUCH` without the UI holding a `Vibrator`. `assembleDebug` exits 0; `testDebugUnitTest` 98/98 green (86 pre-existing + 12 new) |
| 2026-10-09 | 2 | review 2 | Reviewed: **MAJOR M2-1.1 CLOSED; 0 open blockers, 0 open majors; 3 new minors, 4 new nits**. Of the 7 findings claimed, **6 CLOSED** (M2-1.1, M2-1.2, M2-1.3, M2-1.4, M2-1.5, M2-1.6) and **1 STILL OPEN but unfixable as specified** (M2-1.9). Walked the new `setDeleteIntent` path independently: the re-post reuses `buildNotification`, so the full-screen, content and delete intents all survive and a second swipe is answered identically; the resurrection guard is a live `ringingAlarmId` read; **no start id leaks and `START_REDELIVER_INTENT` survives the swipe** (the `START_NOT_STICKY` stop-if-killed flag is neutralised because the outstanding start item is re-queued before that decision); the delayed `stopSelf` is always ≥ 2 s past finding 1.9's write ceiling and is guarded and purged; the wake lock is never touched, so Module 1's ten-path accounting is intact. `encode`/`decode` fail closed on every crafted input — truncated, blanked, extra-field, wrong-version, separator-smuggled, negative, over-long entry and already-solved all return `null`, and a successful decode is never easier than what was saved. The modified `ProblemGeneratorTest` assertion ruled **legitimate and net stronger**: the old line asserted the dividend, which §3 does not require and which pinned M2-1.4 in place, and everything it caught correctly is still caught by two other assertions in the same file. M2-1.9's platform claim **verified true** on all three legs (Compose exposes no attributes, `View.performHapticFeedback` has the system choose the usage, `USAGE_ALARM` outranks `USAGE_TOUCH` against a repeating waveform) — §3's haptic bullet is unachievable as written and needs amending; a service-side buzz at `USAGE_ALARM` would cancel the ringer's own vibration, so it is worse. New minors: **M2-2.1** `NO_ALARM` doubles as "not ringing" in the swipe handler, **M2-2.2** `decode` does not relate `draws` to `asked`, **M2-2.3** still exactly one best-effort route back to the keypad. **ADVANCE to Module 3** |
| 2026-10-09 | 3 | dev 1 | Alarm management + onboarding implemented. `MainActivity` is now the real app host (alarm list / editor / onboarding / battery / diagnostics, five screens in one `sealed interface` with no navigation dependency); the Module 1 debug trigger is **gone**. New `data/AlarmRepository` is the single place a row and its registration change together, routing every mutation through pure `domain/AlarmScheduleDecision` whose `cancelFirst` is unconditionally true — delete cancels *before* the row is removed, save/toggle cancel before scheduling. New pure `domain/OemBattery` (vendor detection + explicit targets for Xiaomi/Oppo/Vivo/Huawei/Samsung, sub-brands mapped to their ROM family) and `domain/Diagnostics` (severity policy: exact-alarm and battery denial are FAIL, notifications and FSI denial are **WARN**, pinning round 2's withdrawn-hard-gate ruling). New `system/SystemState` queries live permission state with no cache, re-run on every `onResume`; new `system/SettingsIntents` resolve-checks every OEM deep link via `resolveActivity` (with a manifest `<queries>` block, without which API 30+ hides the packages) **and** still wraps the launch, degrading to written per-OEM steps. Onboarding explains every step before any system dialog and gates scheduling on nothing. Test alarm fires the real chain 5 s out from a reserved `enabled = false` row, so no reconcile or boot can resurrect it. Defensive reconcile on first create only. **Volume re-assertion implemented** in `AlarmRinger`: a `Settings.System` `ContentObserver` dispatched on the **main looper**, filtered by `lastWrittenVolume` (recorded before every ringer write) and gated on `ringRequested`, which `stop()` clears before it unregisters and before `restoreAlarmVolume()` — the durable pre-ring record is never touched on that path. **M2-2.1** fixed (`ringer?.isRinging != true && ringingAlarmId == NO_ALARM`, both clauses, so neither the no-id case nor a post-`GiveUp` ring loses its route back). **M2-2.3** fixed: list and diagnostics both offer "an alarm is ringing — open it" via a new `AlarmService.openRingingIntent`, byte-identical to the notification's own full-screen intent. "Last fired" is a device-protected `SharedPreferences` stamp, **not** a schema change — `version = 1`, empty `MIGRATIONS` and the committed `1.json` are untouched (backlog 2.6). `ui/` still holds zero audio **code** references (7 prose hits only). `assembleDebug` exits 0 with no warnings; `testDebugUnitTest` **126/126** green (98 pre-existing + 28 new: 10 `OemBatteryTest`, 12 `DiagnosticsTest`, 6 `AlarmScheduleDecisionTest`), nothing weakened, deleted or `@Ignore`d |
| 2026-10-09 | 3 | review 1 | Reviewed: **0 blockers, 0 majors, 11 minors, 7 nits**. **ADVANCE** — Module 3 and the project's build contract are complete. The volume re-assertion in `AlarmRinger` holds on both claimed guards: the self-write filter is structurally incapable of swallowing a user's lowering (during a ring `lastWrittenVolume` only ever holds `max` or more), and the flag-before-unregister ordering in `stop()` closes the dismiss race that `unregisterContentObserver` alone cannot, because a dispatched message survives it while the single-threaded main looper cannot interleave it with `stop()` — finding **1.1**'s durable record is provably untouched by the watcher, and `lastWrittenVolume`/`volumeObserver` are confirmed main-thread-only by caller trace, so round 2's `@Volatile` analysis stands. The dev's deliberate **M2-2.1** deviation (both clauses) is **ruled correct and the round-2 prescription incomplete**: `isRinging` alone would re-open M2-1.1 through the post-`GiveUp` window, where the id is live and `isRinging` honestly false. **M2-2.3** landed byte-identically on two screens and incidentally gave the denied-notification case its first working route back to the keypad. All eight §4 acceptance boxes and all eleven deliverables met; the reserved `-2` test row collides with no request code, data URI, sentinel or list filter; `recordFired` cannot reach the cancelling catch and does no main-thread IO; `AlarmHistory`-as-DE-prefs ruled a sound call against backlog **2.6**. All three self-declared gaps (**2.10**, **3.2**, no Compose/repository tests) ruled acceptable as backlog, with 2.10's fix re-homed onto the boot path rather than app launch. Highest-value minors: **M3-1.1/M3-1.2**, which both attack the test alarm — the instrument v1 readiness depends on. v1 readiness: the single highest risk is unchanged from BRAINSTORM §6.1 (the OEM battery killer, against which every mitigation this app has is advisory), compounded by BRAINSTORM §6's **watchdog alarm** never having been assigned to a module or listed in §5, and by nothing in three modules ever having been observed to ring |
| 2026-10-11 | — | hardening | **Final v1 hardening round — the five §4b items and nothing else.** **M3-1.1:** `AlarmRescheduler.rescheduleAll` now skips `AlarmRepository.TEST_ALARM_ID` in both directions, so opening the app (or an activity recreation) inside the test alarm's five-second lead can no longer cancel the alarm under test; the row stays `enabled = false` for its original reason and the reconcile's KDoc, which claimed the opposite, was corrected. **Backlog 2.2:** `stopSelf(startId)` moved inside `finishDismiss`'s `ringingAlarmId == NO_ALARM` branch, so a stale dismiss is now a total no-op including teardown; `handleNotificationDeleted`'s two-clause guard is untouched. **Overlap policy — refuse:** new pure `domain/AlarmStartPolicy.forStart` (id **and** ringer, in ruling 1's shape) decides RING / DUPLICATE / REFUSE once per start; `AlarmService.refuseStart` leaves `ringingAlarmId`, `ringingLabel`, the mirrors, the ringer, the wake lock and `stopSelf` alone and re-posts the *live* alarm's notification to satisfy any `startForegroundService` obligation, while `settleRefusedAlarm` still books a repeating alarm's next occurrence and still auto-disables a one-shot. The test alarm is blocked three ways (disabled button, `MainActivity.fireTestAlarm` pre-check for the gap between poll ticks, and the service's own refusal). No per-alarm state, no queue. **Launcher icon:** adaptive `mipmap-anydpi-v26/ic_launcher.xml` with vector background / foreground / monochrome layers, artwork inside the mask-safe radius, no raster asset and no dependency. **`allowBackup`:** now `false`, deliberately, with the reasoning written into the manifest. `assembleDebug` exits 0 with no warnings; `testDebugUnitTest` **138/138** green (126 pre-existing + 12 `AlarmStartPolicyTest`), nothing weakened, deleted or `@Ignore`d |
| 2026-10-11 | — | review final | Reviewed the hardening round: **0 blockers, 0 majors, 2 minors, 3 nits**. **ADVANCE** — all five §4b items land as specified. Item 2 does **not** regress the wake lock: `stopForeground`/`release()` were already inside the `NO_ALARM` guard (Module 1 round 2 said so when it filed 2.2), so only `stopSelf` moved, and what the change removed was a release that fired *wrongly* via `onDestroy` -> `stopAlarm` while another alarm rang. All ten paths re-traced: each still releases exactly once, the stale path's deliberate non-release is covered by `onDestroy`, process death and the `acquire(10 min)` ceiling, and the refusal path contains no `AlarmWakeLock` reference at all. `AlarmStartPolicy.forStart` is total and decides both `incoming == ringing` cases *before* the refusal clause, so an alarm can never be refused as an overlap with itself — redelivery is `DUPLICATE` in-process and `RING` in a fresh instance. `settleRefusedAlarm`'s two calls are disjoint on `isRepeating` (which neither writes) and order-independent; a refused repeating alarm books its next occurrence off trigger+1min and a refused one-shot is disabled *and* cancelled, so no phantom tomorrow. **The `refuseStart` notification re-post is ruled harmless:** built from `ringingAlarmId`/`ringingLabel` on the main thread with no interleaving possible, byte-identical to what is posted, and even a ROM-re-triggered FSI carries the *live* id, so `onNewIntent`'s `alarmId != previous` guard is not tripped, `consecutiveWrong` cannot reset and the escape deadline (cleared only in `onStop`) survives; the dangerous version of this path — `launchRingActivity` with the refused id — is correctly not called. Item 1's skipped cancel is free on all three legs; item 5's device-protected-storage claim verified by grep (only `onboarding_seen` is in CE storage); item 4's icon is valid XML and mask-safe by arithmetic (extreme point 35.24 of 36) but **has never been rendered**. New: MINOR M3-3.1 (`AlarmReceiver`'s catch releases the process-global lock even when another alarm is live), MINOR M3-3.2 (`REFUSE` also suppresses a backup alarm while the live one is silent after `GiveUp`), NITs M3-3.3/3.4/3.5. Backlog **2.2**, **1.7**, **M2-1.8**, **M3-1.1**, **M3-1.2** and NIT **1.21** closed; **1.8** only partially (its "fired but never dismissed" route stays open). 138 tests, no `@Ignore`. **Build contract and final hardening round complete; the next step is a device, in the six-step order in the ledger.** |
| 2026-10-14 | — | 4c dev | **One-defect round: MINOR M3-3.2, the recovery-exhausted refusal.** `AlarmRinger` now publishes a third signal, `recoveryExhausted` — set on the audio thread at both exhaustion sites (`Plan.GiveUp` and the synchronous all-sources-failed exit), always *after* `startPending` goes false so a reader can never observe exhausted-and-ringing, and cleared by `start()` on the main thread before playback is posted so it cannot survive into a new ring. `AlarmStartPolicy.forStart` takes it as a fourth parameter (defaulting to `false`, the conservative value) and gains one clause, `ringerRecoveryExhausted && !ringerIsRinging -> RING`, placed after both `incoming == ringing` cases and *before* the refusal, which keeps its `ringingAlarmId != NO_ALARM` clause intact — the new clause is strictly narrower, not a replacement. The takeover itself needed no new code: `handleStart`'s existing RING path reuses the ringer (so the dead one is replaced, not leaked), `start()` already releases any player, re-requests focus and resets the retry budget, `AlarmWakeLock.acquire` is a no-op while held so the single process-global lock is neither leaked nor double-released, and every call below the ringer start is made with the *incoming* alarm's id, so the taken-over alarm is not re-settled (its own `rescheduleIfRepeating`/`disableIfOneShot`/`recordFired` ran at its own start). 12 production lines of code across three files. `assembleDebug` exits 0 with no warnings; `testDebugUnitTest` **143/143** green (138 pre-existing, all untouched, + 5 new `AlarmStartPolicyTest` cases), nothing weakened, deleted or `@Ignore`d |
| 2026-10-14 | — | 4c review 1 | Reviewed the recovery-exhausted takeover: **0 blockers, 1 major, 2 minors, 4 nits**. **DO NOT ADVANCE.** All seven §4c acceptance boxes are met and the policy change itself is right: the new clause sits after both `incoming == ringing` branches and before an unchanged refusal, the ordering of the two volatile writes is sound per start generation (`audioAlive` and `startPending` both go false first), the non-atomic double read in `handleStart` is safe, and reusing the exhausted `AlarmRinger` leaks nothing — the durable pre-ring volume record survives because `rememberVolumeBeforeRinging` refuses to overwrite an existing one, the `ContentObserver` early-returns when already registered, `requestAudioFocus` abandons first, and `startPlaybackFrom` opens with `releasePlayer()`. Wake lock still exactly once on all ten paths; a dismiss for the taken-over alarm cannot tear down the new ring. Touching `AlarmRinger` ruled **necessary** (the service cannot infer exhaustion without a watchdog, a cross-thread callback or mirroring `restartPasses`, all worse); the mid-challenge session reset ruled **acceptable** (B needs its own tier and must not inherit A's failures, and a lost escape countdown only makes dismissal harder). The gating **MAJOR 4c-1.1** is in `AlarmService`, not the policy: the takeover branch never settles the alarm it overwrites, and `disableIfOneShot` does **not** run at start — it has exactly two callers, `handleDismiss` and `settleRefusedAlarm`, and the takeover calls neither. A taken-over one-shot is left `enabled` with no registration, so `AlarmRescheduler` re-books it for the next morning and it rings: precisely the phantom `settleRefusedAlarm`'s own KDoc describes, backlog **1.8** re-opened for the new state. For two one-shots the §4c defect is transferred from B to A, not removed. **§4c constraint 3 and the comments at `AlarmService.kt:137` / `AlarmStartPolicy.kt:33-35` assert the false premise and need correcting with the code.** Minors: **4c-1.2** `recoveryExhausted` can be left stale-true across a `start()` interleaving between the two exhaustion writes (one-line close: clear it in the posted lambda too), **4c-1.3** the new clause lets an id-less start take over a live ringer. ponytail-review: lean in code — no new class, dependency, queue or callback — but the §4c rationale is written out three times (~10 lines cuttable); the `&& !ringerIsRinging` conjunct and the `= false` default were both considered for deletion and **kept**, the conjunct because 4c-1.2 shows it is the load-bearing guard rather than redundant with the write ordering. Round 2 is a one-item round: 4c-1.1 |
| 2026-10-14 | — | 4c dev 2 | **One-item round: MAJOR 4c-1.1, the taken-over one-shot's row.** `handleStart` now captures `ringingAlarmId` before the takeover overwrites it and hands it to a new `settleTakenOverAlarm`, which runs **only** the `disableIfOneShot` half of `settleRefusedAlarm` — `rescheduleIfRepeating` genuinely did run at that alarm's own start, so re-running it would book a second occurrence. The call sits *before* the `try`, not after it, so the cancelling catch cannot skip it; a non-`NO_ALARM` argument identifies a takeover and nothing else, because the policy's other two `RING` branches both have `alarmId == ringingAlarmId` or no live id. Repeating alarms and the reserved test row are unaffected (`disableIfOneShot` returns early for a repeating row; the test row is already `enabled = false` and `AlarmRescheduler` skips it). Also taken, one line each: **4c-1.2** `recoveryExhausted = false` is now cleared a *second* time inside `start()`'s posted lambda, on the audio thread, strictly after any in-flight exhaustion write (the main-thread clear stays, for a delivery arriving before the lambda runs); **4c-1.3** the takeover clause gained an `incomingAlarmId != NO_ALARM` conjunct so an id-less start can never become the live id; **4c-1.4** / **4c-1.5** the two comments that overstated what they guarantee. The triplicated §4c rationale is down to one full statement at the clause plus two 3-line pointers, and the comments asserting the false `disableIfOneShot`-at-start premise are gone. `&& !ringerIsRinging` and the `= false` default both **kept**, as the review directed. +9 lines of production code, net 0 in `AlarmStartPolicy.kt`. `assembleDebug` exits 0; `testDebugUnitTest` **144/144** green (143 pre-existing, none weakened, deleted or `@Ignore`d, + 1 new case pinning 4c-1.3) — criterion 8's row settling has no JVM test: it ends in Room and `AlarmManager`, and the project has JUnit only |
| 2026-10-14 | — | 4c review 2 | Re-reviewed the takeover round: **0 blockers, 0 majors, 3 minors, 1 nit**. **ADVANCE** — all **eight** §4c boxes pass and nothing from round 1 is open. **4c-1.1 CLOSED**: `settleTakenOverAlarm` (`AlarmService.kt:243-250`, called at `:114` with the pre-overwrite id) reaches `dao.disable` + `AlarmScheduler.cancel` through the *same* `disableIfOneShot` the dismiss and refusal paths call, so the three cannot drift; I walked the `if (alarmId != ringingAlarmId) ringingAlarmId else NO_ALARM` selector on all four reachable outcomes (`DUPLICATE`, both `incoming == ringing` branches, the §4c clause, the final `else`) and a non-`NO_ALARM` id is reachable **only** from the §4c clause — so the opposite-direction MAJOR, disabling a row that should stay armed, does not exist; repeating rows keep exactly the one occurrence booked at their own start (no double-booking) and the reserved `-2` row is a no-op in both directions. **4c-1.2 CLOSED** (the audio-thread clear at `AlarmRinger.kt:150` precedes `startPlaybackFrom`, so a fresh exhaustion can never be swallowed), **4c-1.3**, **4c-1.4**, **4c-1.5 CLOSED**. The dev's deliberate deviation — settling *before* the `try` — is **upheld**: the catch at `:143-149` `return`s, the overwritten alarm's registration is consumed whether or not the new one starts, "off" is the honest row where the alternative is backlog 1.8's phantom ring, and the call stays unreachable from the catch, which is the stronger form of finding 1.14. The missed ponytail target is **accepted**: the triplication round 1 objected to is gone (one canonical argument plus two 3-line pointers), and the clause comment's growth to 11 lines is earned because mutation testing proves all three conjuncts load-bearing and round 1's MAJOR came from a comment asserting a false premise; `net: -4` remains (one duplicated KDoc paragraph). New: **4c-2.1** MINOR the takeover's disable write is unjoined and the catch path can freeze it (finding 1.9's hazard, compound precondition); **4c-2.2** MINOR pre-existing — the catch path settles *neither* row, so a failed repeating start never re-books and never rings again until a reconcile; **4c-2.3** MINOR no test pins "only a §4c takeover reaches the settle", and a `domain/` seam exists; **4c-2.4** NIT residual cross-generation volatile interleaving, no fix recommended. **Criterion 8 cannot have a JVM unit test** — verified: `junit:junit:4.13.2` is the only test dependency, there is no `androidTest` source set, the helper is a private `Service` member and its effects end in Room and `AlarmManager` — so it is confirmed on the emulator only, by the eight steps recorded in the ledger |
| 2026-10-14 | — | 4d dev 1 | **One-item round: 4c-2.2, the failed-start path's unsettled row.** `handleStart`'s cancelling `catch` now calls `settleRefusedAlarm(alarmId, triggerAtMillis)` before `stopAlarm()`, so a start that throws re-books a repeating alarm's next occurrence and auto-disables a one-shot instead of leaving the row armed with its registration spent. One new statement; the rest of the diff is the comment and the KDoc that records the durability decision. **The write is deliberately dispatched, not joined** (4c-2.1's hazard, stated rather than inherited silently): `handleDismiss`'s join holds the *foreground notification* plus the wake lock, and the likeliest cause of this catch is `startForeground` failing, so there may be no foreground state to hold — delaying `stopSelf` with an unmet `startForegroundService` obligation outstanding invites the platform's own FGS timeout to kill the process, losing the write anyway and adding a crash, and it would also move the wake-lock release off the synchronous main-thread path that Module 1's ten-path item 4 audits. Residual points *back to the status quo*, never to a new harm: a lost write leaves exactly today's behaviour (no reschedule / no auto-disable), still healed by reconcile-on-open or a boot, so the dispatch is strictly better than the nothing it replaces. No double-settle: the success path's `rescheduleIfRepeating` is below the try and the catch `return`s; the §4c takeover settle took the *other* id (`NO_ALARM` whenever `alarmId == ringingAlarmId`), so no row is settled twice. No new test — the catch is reachable only through `AlarmService` and its effects end in Room and `AlarmManager`, with `junit` the only test dependency; the one piece of logic involved (the `isRepeating` disjointness of the two halves) is already pinned by `AlarmDayMaskTest`. `assembleDebug` exits 0; `testDebugUnitTest` 144/144 green, none weakened |

---

## 7. Change log

| Date | Change | Reason |
|---|---|---|
| 2026-10-09 | Snooze cut entirely from v1 | User decision — removes a whole state machine and is truer to the premise |
| 2026-10-09 | Instant max volume, no ramp | User decision — maximum reliability over gentleness |
| 2026-10-09 | Escape hatch gated at 5 consecutive failures | User decision — keeps math as the primary path while retaining a failsafe |
| 2026-10-09 | Default difficulty MEDIUM × 1 question | User decision — gentler default, less early frustration |
| 2026-10-09 | Verification is code review only | User decision — no emulator, no UI checking; compile gate still enforced |
| 2026-10-09 | Room + KSP **kept**, no fallback needed | KSP 2.3.12 drives AGP 9.4.1's built-in Kotlin 2.4.21 without complaint; `kspDebugKotlin` generates `AlarmDao_Impl`/`AlarmDatabase_Impl` and the build is warning-free. The DataStore fallback was not needed |
| 2026-10-09 | Alarm database lives in **device-protected storage**; firing chain declared `directBootAware` | `LOCKED_BOOT_COMPLETED` is in the spec's required action list, but it is delivered before the first unlock, when credential-encrypted storage is unreadable. Without this the handler would be dead code and a phone rebooted overnight would never reschedule |
| 2026-10-09 | Foreground service type = `mediaPlayback` | API 37 still has no alarm-specific `FOREGROUND_SERVICE_TYPE_*`; `mediaPlayback` is the closest honest fit for a looping alarm tone and imposes no runtime prerequisite. Matched by `FOREGROUND_SERVICE_MEDIA_PLAYBACK` |
| 2026-10-09 | Bundled `res/raw/fallback_alarm.wav` as a last-resort tone | The default alarm URI resolves through `Settings`/`MediaStore`, neither of which is readable in direct boot, and a user can delete the configured ringtone. A silent alarm is a total module failure, so the chain ends in a resource that is always readable |
| 2026-10-09 | Alarm-stream volume restored on dismiss | Instant max while ringing is unchanged; restoring afterwards keeps the app from permanently rewriting a system setting the user owns |
| 2026-10-09 | Transient-exclusive audio focus requested while ringing, with no focus-change listener | Pauses music playing in the room. No listener on purpose: losing focus must never be able to silence an alarm |
| 2026-10-09 | §2 amended: `requestDismissKeyguard` is now explicitly forbidden | Review finding 1.2 — the original spec line required it, but on any device with a PIN it puts an auth bouncer over the Dismiss button, so the alarm is not dismissable from the lockscreen. Spec was wrong, not the implementation |
| 2026-10-09 | Pre-ring alarm-stream volume recorded in device-protected `SharedPreferences` (`alarm_volume`), `commit()`-ed before the first `setStreamVolume` | Review finding 1.1 — an in-memory field let a `START_REDELIVER_INTENT` restart record the already-maxed volume as the user's own, permanently rewriting a system setting. A stale record is never overwritten and is cleared only by a successful restore |
| 2026-10-09 | Room schema export enabled (`exportSchema = true`, `room.schemaLocation` -> `app/schemas/`, version-1 JSON committed) and `fallbackToDestructiveMigration` removed; `addMigrations(*MIGRATIONS)` is now the only migration path | Review finding 1.3 — the first schema change would otherwise drop the `alarms` table and silently stop every alarm. A missing migration is now a loud failure instead of data loss |
| 2026-10-09 | `MediaPlayer` work moved onto a private `HandlerThread`, with an error listener that re-enters the source fallback chain at the next source | Review findings 1.4 and 1.13 — `prepare()` on the service main thread is an ANR on the one thread that must process dismiss, and an async playback error silenced the alarm permanently with `isRinging` still reporting `true`. `isRinging` now tracks actual playback, not `player != null` |
| 2026-10-09 | Dismiss holds the foreground notification and the wake lock until the one-shot auto-disable write commits (10 s ceiling), and always uses `stopSelf(startId)` with a `ringingAlarmId` guard | Review findings 1.6 and 1.9 — the write used to be dispatched exactly as the process became cached and freezable, and an unconditional `stopSelf()` could silence a second alarm that had just started |
| 2026-10-09 | The booked trigger instant is carried through the fire `PendingIntent` (`EXTRA_TRIGGER_AT`) and the post-fire reschedule baselines off it | Review finding 1.15 — baselining off `now()` inside a coroutine could re-book the occurrence that just fired. Extras are ignored by `Intent.filterEquals`, so cancel-side `PendingIntent` matching is unaffected |
| 2026-10-09 | Async playback failure now recovers with a bounded, delayed restart of the whole source chain (`PlaybackRecovery`), and `MEDIA_ERROR_SERVER_DIED` retries from index 0 instead of falling forward | Review finding 2.1 — `startPlaybackFrom(failedIndex + 1)` had no terminal case, so an async error on the *last* source (the bundled tone that direct boot depends on) left an empty loop range and the alarm permanently silent while the notification, the full-screen activity and the vibration all still claimed it was ringing. `MEDIA_ERROR_SERVER_DIED` is not source-specific, so falling forward was also the wrong recovery for it. The retry policy is a pure function so it is unit-testable off-device |
| 2026-10-09 | Challenge session state hoisted into one activity-scoped `mutableStateOf` in `AlarmRingActivity`, persisted field-by-field into the activity `Bundle`, with the random stream stored as `(seed, draws)` rather than a `Random` | A `Random` is not serializable and the session has to survive rotation and process death. `(seed, draws)` replays the stream exactly, so a restored session shows the *same* problem and keeps the same failure count — rotating the phone cannot fish for an easier question or forgive a wrong answer. Holding the state outside composition is also what makes it impossible for a recomposition to regenerate the problem |
| 2026-10-09 | The escape countdown deadline is the one piece of session state that is **never** persisted, and is cleared in `onStop()` | BUILD_SPECS §3 requires the user to stay on screen for the full minute. The deadline is an `elapsedRealtime` instant, which keeps advancing while the process is dead, so persisting it would let a countdown complete that nobody watched. Dropping it on every stop also implements "leaving or backgrounding resets the countdown" in one line. Cost: a rotation during the countdown also restarts the minute — strictly harder, never easier |
| 2026-10-09 | Wrong-answer haptic goes through Compose `LocalHapticFeedback`, not `Vibrator` | BUILD_SPECS §2/§3's structural rule is that the UI layer holds no audio or vibration object. `View.performHapticFeedback` gives the buzz without the activity ever touching a `Vibrator`, so the ringer's own vibration stays the service's business and the UI cannot interfere with it |
| 2026-10-09 | Tier/question-count read from the existing `alarms` row at ring time; no new columns, no schema bump | `difficulty` and `questionCount` already exist from Module 1, and backlog **2.6** makes any version bump require a migration. The read goes through the device-protected `AlarmDatabase`, so it also works before the first unlock after a reboot; a missing row or a failed read falls back to the §0 default of MEDIUM × 1 rather than leaving the alarm undismissable |
| 2026-10-09 | `× 10` rejection generalised to "no factor that is a multiple of ten"; EASY subtraction floors its subtrahend at 2; HARD clean division floors its quotient at 4 | `30 × 7` is `3 × 7` with a zero typed after it — the same reflex the ×10 rule exists to kill. `18 - 1` and `18 - 0` are the additive twins of the `+ 0` rule. `9 ÷ 3` is not a HARD problem; flooring the quotient keeps every dividend at two digits or more. All three are strict subsets of what §3 allows |
| 2026-10-09 | §3 amended: service-side volume re-assertion deferred out of Module 2 | The dev agent declined it with sound reasoning and flagged it rather than claiming it. Implementing it means a `ContentObserver` inside `AlarmRinger` — Module 1's thrice-reviewed audio code — which would fire on the ringer's own volume writes and race the finding-1.1 durable volume record. Volume-key interception in the activity is the Module 2 requirement; the residual focus-less case routes to Module 3 diagnostics |
| 2026-10-09 | §3's residual-volume wording corrected and §4 given a volume re-assertion item | Review M2-1.7: my deferral text blamed denied permissions, but the notification shade's slider targets `STREAM_ALARM` on a fully permissioned device, so diagnostics alone cannot close it. Re-assertion moves to Module 3 as an implementation item with the self-write filter specified |
| 2026-10-09 | The alarm notification carries a `setDeleteIntent` that re-posts it, via a new internal `AlarmService` action | Review **M2-1.1** — `setOngoing(true)` only blocks a swipe up to API 33, and on API 34-36 the notification is the *only* route back to the challenge UI (`excludeFromRecents`, no `taskAffinity`, nothing in `MainActivity`), so swipe + Home stranded a willing user in front of an undismissable alarm. The handler reads `ringingAlarmId` rather than an id baked into the `PendingIntent`, so it cannot resurrect a notification for an alarm that has stopped; it never acquires or releases the wake lock; and it never calls `stopSelf` while an alarm is live, because that id is the newest start id. `excludeFromRecents` was deliberately **not** dropped — that would only move the loss to a recents swipe |
| 2026-10-09 | The ring-time tier read is raced against a 5 s timeout | Review **M2-1.6** — `runCatching` turns a *thrown* read into the MEDIUM × 1 fallback but cannot turn a *hung* one, and the loading branch composes no keypad, no Submit and no escape button, so a wedged `getById` meant an alarm with no way to dismiss it at all. The await is what is timed out, not the query, so a merely slow read still completes |
| 2026-10-09 | Dismiss fires on "this submit transitioned the session to solved", not on "the session is solved" | Review **M2-1.3** — `submit()` on an empty entry returns the same value, which may already be solved, so a double-tap on the last question sent a second unearned `ACTION_DISMISS` and any state arriving already solved would have been handed a free one. An unearned dismissal is this module's worst defect class, so the cause has to be the correct answer |
| 2026-10-09 | HARD division is validated by its **quotient**, and the dividend is no longer treated as a factor | Review **M2-1.4** — the validator was wrong in both directions and its KDoc described a check that did not exist: `96 ÷ 96 = 1` and `12 ÷ 12 = 1` passed, while `isAllowedFactor(dividend)` discarded 8 of the 72 constructible divisor/quotient pairs (`20 ÷ 4`, `30 ÷ 6`, `60 ÷ 12`). The dividend requirement is now stated as "at least two digits" instead of a hard-coded `100..144` window that would have started rejecting valid problems the moment `SMALL_FACTORS` widened. Both directions are pinned by tests |
| 2026-10-09 | One countdown ceiling, and it is the tested one | Review **M2-1.5** — `ChallengeState.escapeSecondsLeft` was tested but dead in production while the activity's private `secondsUntil`, the number the user actually watches, was untested. The composable now calls the state's method and the duplicate is gone |
| 2026-10-09 | The session `Bundle` is one encoded string, written and read by a pure `ChallengeState.encode` / `decode` pair | The eleven `putX` calls were paired with a test that *hand-built* the restored value, so dropping a field from the save path failed nothing. The field list is now pure Kotlin and the real round trip is unit-tested off-device. It also removes review **M2-1.2** structurally: nothing has a default, so the absent-key `0` that was a typable winning answer cannot occur, and a decode additionally refuses a non-positive answer, an already-solved session and an entry the keypad could not have typed. Any field missing, blank or unparsable yields `null`, which starts a fresh session off the alarm's real tier — strictly harder, never easier |
| 2026-10-09 | Wrong-answer haptic left as a `USAGE_TOUCH` Compose haptic, with the limitation documented in place | Review **M2-1.9** — the prediction checks out and there is no fix available within the rules. `View.performHapticFeedback` (what `LocalHapticFeedback` resolves to) is requested at `VibrationAttributes.USAGE_TOUCH`, the vibrator service ignores a new vibration of lower usage importance than one already playing, and neither Compose nor `View` exposes `VibrationAttributes`. Raising the usage means the UI holding a `Vibrator`, which is the one rule that outranks every review finding, so the buzz stays undelivered on `vibrate = true` alarms and delivered on `vibrate = false` ones. The red error state carries the same information either way |
| 2026-10-09 | §3 haptic bullet rewritten as best-effort | Review M2-1.9, platform-verified on three legs: Compose exposes no vibration attributes, `View.performHapticFeedback` lets the *system* pick the usage, and `USAGE_ALARM` outranks `USAGE_TOUCH` against a repeating waveform. The bullet was unachievable as written; the visual error state is now the stated requirement |
| 2026-10-09 | §3 "non-dismissible notification" → "cannot be severed" | Review M2-2.7: literal non-dismissibility is impossible for any app on API 34–36. Not-severable (a swipe re-posts it) is the strongest achievable property and is what actually preserves the route back to the keypad |
| 2026-10-09 | §4 gained M2-2.1 and M2-2.3 | Both one-liners landing in files Module 3 is already touching; folding them in rather than leaving them as orphan backlog |
| 2026-10-09 | `MainActivity` rewritten as the app host; Module 1 debug trigger deleted | §4. Five screens behind one `sealed interface` in a `mutableStateOf` rather than a navigation library — no deep links and no nested back stacks, so a new dependency could not be justified against the fixed build config |
| 2026-10-09 | Every alarm mutation goes through `AlarmRepository` + pure `AlarmScheduleDecision`, and `cancelFirst` is unconditional | §4's two scheduling criteria are now pinned by unit tests instead of by a code read. There is deliberately no "has the trigger moved?" fast path: a changed day mask, a re-enable or a label edit all leave a registration computed from the old row, and one extra binder call removes the whole stale-registration class. Cancellation is `AlarmScheduler.cancel` and nothing else — its `PendingIntent` identity is thrice-verified and nothing here reconstructs an intent |
| 2026-10-09 | Delete cancels the registration **before** removing the row | The registration is keyed on the alarm id. Cancelling afterwards would work today and would be one refactor away from a leaked registration firing an alarm the user deleted, with `AlarmService` then finding no row |
| 2026-10-09 | "Last fired successfully" stored in device-protected `SharedPreferences`, **not** a new column | Backlog **2.6**: the migration gate is runtime-only, so a forgotten `Migration(1, 2)` compiles green and then stops every alarm being rescheduled after an update. One timestamp does not justify taking that on, so `@Database(version = 1)`, the empty `MIGRATIONS` array and the committed `app/schemas/.../1.json` are all untouched. Device-protected for the same reason the alarm table is, and written from `AlarmWork.scope` rather than the service main thread (backlog 2.5) |
| 2026-10-09 | Onboarding explains every step before any system dialog, and gates scheduling on nothing | §4, plus Module 1 round 2 pushback #1, whose replacement guidance is what is implemented: `USAGE_ALARM` on `STREAM_ALARM` rings audibly through DND with no FSI and no notification, so refusing to schedule would convert a degraded-but-audible alarm into no alarm. Every step is skippable, the honest consequence is printed on the card, and the state is surfaced in diagnostics instead |
| 2026-10-09 | Notifications-denied and FSI-denied are **WARN** in diagnostics; exact-alarm and battery are **FAIL** | Same ruling, now pinned by `DiagnosticsTest`. A FAIL on the first two would be factually wrong and is the first step back towards the hard gate that was withdrawn; the second two genuinely stop an alarm ringing (backlog **1.11**, BRAINSTORM §6.2) |
| 2026-10-09 | Every OEM deep link is resolve-checked *and* launch-wrapped, with a manifest `<queries>` block | §4: "a missing intent cannot crash the app". `resolveActivity` is useless for an explicit component in another package on API 30+ without declared visibility, so without `<queries>` every check would report "absent" and the links would never be offered; and an activity can resolve and still refuse to start (not exported, permission-guarded, disabled on that ROM), so `ActivityNotFoundException`/`SecurityException` come back as `false`. Written per-OEM steps are always shown, not only on failure |
| 2026-10-09 | `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` added — the only new permission | BRAINSTORM §5.4/§6.2. Without it the single yes/no exemption prompt cannot be launched at all. User-initiated and explained first, which is the condition attached to it, and the app only ever *reports* `isIgnoringBatteryOptimizations` — it never gates anything on it. Still no network permission and no new dependency |
| 2026-10-09 | The test alarm is a reserved **`enabled = false`** row at id `-2` | It has to be persisted (the firing chain reads the tier and writes the one-shot auto-disable) but must never come back tomorrow morning: disabled means `AlarmRescheduler` and `BootReceiver` cancel any leftover registration and never re-book one, while `scheduleAt` does not consult `enabled` so the one 5-second registration is still booked. The negative id keeps it out of the list and out of `AUTOINCREMENT` range |
| 2026-10-09 | Defensive reconcile runs on first create only, not on every `onCreate` | A reconcile *cancels and re-books* every registration, so running it on a configuration change would let a rotation cancel the five-second test alarm the user had just armed |
| 2026-10-09 | Alarm-stream volume re-assertion implemented in `AlarmRinger`, with a main-looper `ContentObserver` and a self-write filter | §4 / review **M2-1.7**. Two independent guards, both required. (1) `lastWrittenVolume` is set *before* every `setStreamVolume` the ringer makes, in both `forceMaxAlarmVolume` and `restoreAlarmVolume`, and an observation equal to it is ignored — without this the ringer's own write re-triggers the observer and the dismiss restore is instantly undone, which is the finding-1.1 bug class the deferral was about. (2) The observer is dispatched on the **main looper**, the same thread `start()`/`stop()` run on, and `stop()` clears `ringRequested` *first*, then unregisters, then restores — so a callback already queued when `stop()` ran cannot interleave with the restore and returns without touching the stream. Unregistering alone is not sufficient, because an already-dispatched message survives it. The durable pre-ring record is read and written on neither path. Honest limit: `AudioService` persists stream volume on a short delay, so the re-assert lands a fraction of a second late, and a ROM that does not persist the alarm stream will not notify at all — acceptable, because silencing is not dismissing |
| 2026-10-09 | **M2-2.1** fixed with *both* clauses: `ringer?.isRinging != true && ringingAlarmId == NO_ALARM` | The review prescribed predicating on the ringer, which closes the harmful divergence (an `ACTION_START` with no id rings while the sentinel says nothing is ringing, so a swipe skipped the re-post and armed a `stopSelf` that would silence a live alarm). The id clause is **kept** rather than dropped because `isRinging` alone regresses the opposite case: after `PlaybackRecovery.Plan.GiveUp` (backlog **3.2**) `isRinging` is honestly false while an alarm is still nominally live, and that ring must keep its route back to the keypad. The branch is now taken only when neither says anything is live — exactly and only the dismiss-teardown window it was written for. The delayed `stopSelf` guard was changed to match |
| 2026-10-09 | **M2-2.3** fixed: the alarm list and the diagnostics screen both offer "an alarm is ringing — open it" | A second, independent route back to the keypad, so the best-effort notification re-post is no longer the only one. It sends a new `AlarmService.openRingingIntent`, which the instance `ringIntent` now delegates to, so the route and the notification's own full-screen `PendingIntent` carry a byte-identical intent and `onNewIntent`'s `alarmId != previous` guard leaves a session with solved questions and counted failures untouched. Liveness is a process-global volatile mirror of `ringingAlarmId`, written only alongside it and never an input to any alarm-lifecycle decision |
| 2026-10-11 | Watchdog alarm deferred pending the first device run | User decision. It was never assigned to a module and is not out of scope — it is held open deliberately. Module 3's review named it one of five things v1 should not ship without |
| 2026-10-11 | Overlap policy: refuse a second concurrent alarm | User decision, matching Module 3's review recommendation. Closes backlog 1.7/1.8/M2-1.8/M3-1.2 in one cheap change instead of making Module 1's thrice-reviewed service multi-alarm |
| 2026-10-11 | Final round scoped to the five readiness items | User decision. The §1 bar (zero blockers/majors) is already met in all three modules; the remaining backlog is largely KDoc accuracy and naming, which does not change whether the alarm rings |
| 2026-10-11 | The test alarm is exempt from `rescheduleAll`, cancel included | Review **M3-1.1**. The row is `enabled = false` so nothing can resurrect it tomorrow, which also meant the reconcile cancelled it and never re-booked it — a false negative on the app's own diagnostic, in the discouraging direction. Skipping the cancel costs nothing: a test registration is five seconds out, re-arming cancels first, and no `AlarmManager` registration survives a reboot |
| 2026-10-11 | A stale dismiss no longer calls `stopSelf` either | Backlog **2.2**, open since Module 1 round 3. On the stale path the dismiss carries the *newest* start id, so `stopSelf` was not declined: the service died, `onDestroy` -> `stopAlarm` shut down the ringer, and the alarm that really was ringing went silent. A stale dismiss is now a total no-op; the outstanding start id costs nothing because the live alarm's own dismiss is a newer delivery |
| 2026-10-11 | A second alarm arriving mid-ring is refused, by a pure `AlarmStartPolicy` | §4b item 3 (user decision). Refusing — rather than overwriting the ringing id, label and ringer — closes backlog **1.7**/**1.8**, **M2-1.8** and **M3-1.2** together without giving the service per-alarm state or a queue. The refused alarm is unrung, not lost: a repeating one still books its next occurrence, a one-shot is still auto-disabled (exactly what a dismiss would have done), and the refusal is logged at error level rather than surfaced in the UI — the only *user-initiated* overlap, the test alarm, is refused visibly instead |
| 2026-10-11 | Test alarm blocked while any alarm is ringing | Review **M3-1.2**. The button is disabled off the list's existing one-second poll, `fireTestAlarm` re-checks to cover the gap between ticks, and `AlarmService` refuses the start regardless. The on-screen reason is the point of the first two: a button that silently does nothing is worse than one that says why |
| 2026-10-11 | Adaptive launcher icon added (`android:icon`), vector-only | §4b item 4. Background / foreground / monochrome layers as vector drawables — no raster asset and no new dependency. The alarm-clock motif matches `ic_alarm.xml` but is redrawn for 108 units rather than scaled: a 24dp glyph's 2-unit ring becomes a hairline, and its bells would fall outside the circle a mask leaves visible |
| 2026-10-11 | `allowBackup` set to **false**, deliberately | §4b item 5. A restored row says `enabled` while the new device holds no `AlarmManager` registration, so the alarm looks armed and silently never fires — and the reconcile that heals it only runs if the user opens the app, which someone restoring a phone has no reason to do before bed. Nothing of value is given up: the alarm database and the last-fired record are both in device-protected storage, which auto-backup does not cover, so the only restorable file was `app_settings` — i.e. a new device that skips the permissions walkthrough and restores no alarms. Reasoning written into the manifest |
| 2026-10-14 | §4c added: recovery-exhausted takeover round | User decision — fix the one real overlap bug before the emulator run so the run exercises the corrected policy. Coding agent runs under `/ponytail:ponytail` (minimal-solution bias); review agent under `/ponytail:ponytail-review` |
| 2026-10-14 | A *different* alarm takes over a ringer whose audio recovery is exhausted, instead of being refused | §4c, review MINOR **M3-3.2**. `Plan.GiveUp` deliberately leaves `ringingAlarmId` set while `isRinging` goes false, and the refusal clause tested both — so a silent, permanently-dead ring refused the next alarm and *neither* alarm made a sound, the second one auto-disabling itself on the way out. The policy now gets a third signal from the ringer rather than inferring liveness from two, and that one state hands the ringer over: a fresh ringer with a fresh retry budget is strictly better than guaranteed silence. Not a return to taking over generally — the signal is false during the start window and while anything is actually ringing, so backlog **1.7**/**1.8**, **M2-1.8** and **M3-1.2** all stay closed |
| 2026-10-14 | §4c constraint 3 corrected, criterion 8 added | Review 4c-1.1. My constraint asserted `disableIfOneShot` runs at alarm start; it does not — only `settleRefusedAlarm` and `handleDismiss` call it. The coding agent implemented the spec faithfully, so the takeover transferred backlog 1.8's phantom-alarm harm from the refused alarm to the overwritten one. Criterion 8 is the box whose absence let all seven pass on a round that did not advance |
| 2026-10-14 | A takeover settles the overwritten alarm's row | Review **MAJOR 4c-1.1**. `disableIfOneShot` is called at *dismiss*, never at start, so the round-1 takeover left alarm A `enabled` with its registration consumed — `AlarmRescheduler.rescheduleAll` re-books every enabled row and `NextTrigger` rolls a past one-shot to tomorrow, so opening the app or any boot re-armed A and it rang on a morning nobody set. Backlog **1.8**, re-opened for the one state §4c created and now closed for it: the takeover branch runs the auto-disable half against the id it overwrites. Only that half — the reschedule already happened at A's own start, so re-running it would double-book a repeating alarm. A taken-over one-shot now ends disabled, exactly as a dismissed or refused one does (criterion 8) |
| 2026-10-14 | §4d added: settle the row on the failed-start path | User decision. 4c-2.2, found incidentally during 4c review 2 and predating §4c: a repeating alarm whose start throws is never rebooked and dies permanently while the list shows it armed. Declined in the same pass: the `gradle.properties` portability fix and the −4-line comment trim |
| 2026-10-14 | A failed start settles the incoming alarm's row | Review **4c-2.2**. The cancelling `catch` returned above every settling call, so a repeating alarm whose start threw never booked its next occurrence and silently stopped existing while the list showed it armed, and a one-shot stayed `enabled` with no registration for `rescheduleAll` to re-book as a phantom. `settleRefusedAlarm` already had the right semantics for both — the registration is spent either way — so the catch now calls it. Kept unjoined on purpose (4c-2.1): the dismiss path's join is unavailable where `startForeground` itself may have failed, and a lost write is only the pre-fix behaviour, so there is nothing to trade |
| 2026-10-14 | The §4d durability rationale is wrong on the platform, and the join it rules out is available | Review **MAJOR 4d-1.1**. The dev's reason for not joining the failed-start settle — an unmet `startForegroundService` obligation plus a ~10 s platform timeout kill — does not hold. AOSP `ActiveServices.setServiceForegroundInnerLocked` clears `r.fgRequired` and cancels the FGS timer (`:2248-2251`) **before** every throw site a restriction, app-op, permission or API-34 FGS-type failure can reach, so a `startForeground` that throws has already discharged the obligation; where it genuinely is unmet, `bringDownServiceLocked` (`:6361-6386`) makes the *immediate* `stopSelf` crash the process rather than discharge anything; and the deadline is `DEFAULT_SERVICE_START_FOREGROUND_TIMEOUT_MS = 30 s` (+10 s before the ANR), not 10 s, identical on API 34/35/36. Verified on `android14/15/16-release` and `main`. The one-line fix itself stands; the recorded decision and the preceding §6 row do not. Either take `handleDismiss`'s deferred-teardown join in the catch, or keep the dispatch and say the true thing: there is no *foreground* state to hold once `startForeground` has failed, the wake lock **is** holdable, and this is a complexity trade rather than a platform constraint |
| 2026-10-14 | Scope closed; §4e added | Project owner's decision to stop. 4d-1.1 accepted as a known limitation rather than fixed; its false platform justification corrected in code against AOSP so no future reader rebuilds on it. No further review rounds planned |
