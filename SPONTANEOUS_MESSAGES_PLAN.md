# Plan: Spontaneous Messages — a per-persona, opt-in proactive turn

## Goal

A **local-only, opt-in, default-off** capability where a persona reaches out to the user on
its own — no user turn precedes it — on a configured delivery schedule with a time gap
between messages. The generated message lands in that persona's **normal** history so it
afterwards looks and behaves like any other assistant message, and is surfaced to the user
via a notification that opens straight into that persona's chat.

Hard constraint already decided: **local (Ollama) personas only.** Cloud personas must not be
able to enable this by any path. This mirrors Rooms' autonomous-continuation cloud exclusion
(`RoomReplyPolicy.participatesInAutonomousRounds`, `Models.kt:349`) and the same cloud-cost
caution behind it ("a self-initiated cloud turn is a paid turn nobody asked for").

This document is a plan only. **No implementation code is written here.**

---

## 1. Research findings (what the code actually does today)

Line numbers current at `b58d5f5`.

### 1.1 Background scheduling: none — confirmed

There is **no background-scheduling infrastructure of any kind** in this app:

| Mechanism | Present? |
|---|---|
| `WorkManager` / `androidx.work` / `CoroutineWorker` / `*WorkRequest` | **No** — zero matches across all 873 files |
| `AlarmManager` | **No** |
| `JobScheduler` | **No** |
| `BroadcastReceiver` / `BootReceiver` / `BOOT_COMPLETED` | **No** |
| `WakeLock` | **No** |
| Custom `Application` subclass | **No** — no `android:name` on `<application>` |

Corroborating evidence:

- `app/build.gradle.kts:65-96` — no `androidx.work` dependency.
- `gradle/libs.versions.toml` — no `work` entry in `[versions]` or `[libraries]`.
- `~/.gradle/caches/modules-2/files-2.1/` — **`androidx.work` is not cached**, so adding it
  requires a network resolve on first build.
- `app/src/main/AndroidManifest.xml` — no receiver, no service other than `.AssistantService`.

**What does exist as a long-lived process:** `AssistantService` (`AssistantService.kt:130`),
a plain `Service` declared with `foregroundServiceType="microphone"` (`AndroidManifest.xml:49-53`),
`START_STICKY` (`:2150`), started **and** bound from `MainActivity.onCreate`
(`MainActivity.kt:111-128`) — `startForegroundService` once `RECORD_AUDIO` is granted, else
`startService`, plus `bindService(BIND_AUTO_CREATE)`. The app **never** calls `stopSelf` or
`stopService` (zero matches), so the service runs until the process dies.

`minSdk = 31`, `targetSdk = 37`, `compileSdk = 37` (`app/build.gradle.kts:23-25`).

> **Design consequence (important):** the only way this app currently survives being
> backgrounded is a *microphone-typed* foreground service. That type cannot legally be used
> to run a text-generation timer, and a spontaneous message must never touch the mic. So the
> spontaneous path cannot lean on `AssistantService` being alive — see Open Q1.

### 1.2 Notification channel and infrastructure

One channel, one ID, a fixed set of status strings.

- **Channel:** `CHANNEL_ID = "assistant_service_channel"` (`AssistantService.kt:2720`),
  `NotificationManager.IMPORTANCE_LOW`, created in `onCreate()` (`:1163`) via
  `createNotificationChannel()` (`:2204-2208`).
- **ID:** `NOTIFICATION_ID = 1` (`:2721`) — a single shared ID for everything.
- **Builder:** `createNotification(content)` (`:2210-2219`) — `NotificationCompat.Builder`,
  title `"Voice Assistant"`, small icon `R.drawable.ic_launcher_foreground`,
  `setOngoing(true)`, `PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java),
  FLAG_IMMUTABLE)` — **no extras, no flags**.
- **Updater:** `updateNotification(content)` (`:2221-2224`) → `manager.notify(NOTIFICATION_ID, …)`,
  called from ~40 sites in `AssistantServiceAudio.kt` / `AssistantServiceChat.kt`.
- **Foreground promotion:** `startForeground(NOTIFICATION_ID, createNotification("Ready to help"))`
  at `:2148` (`onStartCommand`) and `:2159` (`promoteToForeground`).
- **Content is a closed set:** `"Ready to help"`, `"Listening..."`, `"Thinking..."`,
  `"Speaking..."`, `"Speaking (Device)..."`, `"Speaking (eSpeak)..."`,
  `"Speaking (streaming TTS)..."`, `"Voice unavailable - check TTS server"`.
- **Runtime permission:** `POST_NOTIFICATIONS` is already declared (`AndroidManifest.xml:9`)
  and requested in the app's **single multi-permission launcher** (`MainVoiceScreen.kt:705-730`,
  launcher at `:458`).

> **Design consequence:** a spontaneous message **must not** reuse this channel or ID.
> Reusing `IMPORTANCE_LOW` makes the message silent; reusing `NOTIFICATION_ID = 1` would
> overwrite the live service status notification; and folding the two into one channel makes
> it impossible to silence spontaneous messages without also silencing the FGS notification.
> A second channel and an ID namespace are required (§4).

### 1.3 Deep-linking: none today

- `MainActivity` — **no** `launchMode` in the manifest, **no** `onNewIntent`, **no**
  `FLAG_ACTIVITY_*`, no `intent-filter` beyond `MAIN`/`LAUNCHER`. Zero matches for all of them.
- `MainScreen(svc)` (`MainVoiceScreen.kt:332`) takes only the service.
- Persona switching is exposed only through in-tree UI state:
  `showPersonaPicker` / `showSettings` are local `rememberSaveable` (`MainVoiceScreen.kt:338`),
  and the switch happens via `service.switchPersona(t)` from `PersonaSelector` (`:1013-1016`).

> **Design consequence:** "tap the notification → open directly into that persona's chat" has
> **no existing plumbing**. It needs new Activity intent handling *and* a way to push a
> target persona from the Activity into the Compose tree (§4.3). This is the single most
> invasive UI change in the feature.
### 1.4 How a message is created + persisted outside a direct user turn

The closest precedent — and the only one — is the **Room autonomous persona-to-persona turn**
in `AssistantServiceRoomTurns.kt`. Its file header states the design rules verbatim:

> *"Each member's turn reuses the EXISTING backend functions … via the Phase 0 [TurnContext],
> so request assembly, budget selection, provider quirks, streaming and Stop-cancellation all
> behave identically. Only the transcript source and destination differ."* (`:9-12`)

The chain:

| Step | Site |
|---|---|
| Append user turn, take `generation = nextChatRequestSeq()`, launch job | `startRoomRound` `:210-256` |
| Initial round over members in order, then autonomous continuation | `runRoomRound` `:274-323` |
| **Cloud excluded from autonomous continuation** | `autonomousSpeakers` `:598-608` + `RoomReplyPolicy.participatesInAutonomousRounds` `Models.kt:349` |
| Build `TurnContext(history, systemPromptSuffix, sink)` + `.withSkills(...)` | `runRoomTurn` `:334-348` |
| Local branch → streaming direct-Ollama call | `runDirectOllamaRoomTurn` `:383-475` |
| Finalize placeholder / append / persist | `:425-437` |
| Remove a blank placeholder on refusal | `rejectRoomTurnReply` `:662-667` |
| Liveness gate | `isRoundLive` `:543-546` |

Two prompts exist that are **exactly** the shape a spontaneous turn needs — "tell this persona
to speak when nobody spoke to it":

```kotlin
// AssistantServiceRoomTurns.kt:493-498 — instruction as the last "user" turn
internal fun roomTurnInstruction(persona: Persona, room: Room) =
    "It is your turn to speak in ${...}. Reply in character as ${persona.name} with ONE
     message addressed to the other participants and the user. React to the latest messages
     in the transcript above and move the conversation forward. Never write anyone else's
     lines, and never repeat a line that is already in the transcript."

// AssistantServiceRoomTurns.kt:509-525 — "here is your situation" system-prompt preamble
internal fun roomSystemPromptSuffix(room: Room, persona: Persona): String
```

**Persistence:**

- Room: `saveRoomHistory()` (`AssistantServiceRooms.kt:45-55`) — snapshot on Main, chain
  behind `roomHistoryIoJob`, write on `Dispatchers.IO`, keyed by
  `roomMessagesOwnerId ?: _activeRoomId.value`.
- Single chat: `persistSettings()` (`AssistantService.kt:1374-1401`) calls
  `settingsManager.savePersonaMessages(messagesOwnerName ?: currentPersonaName, _messages.value)`
  (`:1394-1396`) — **keyed by persona NAME**, debounced (`saveSettings` `:1366-1372`),
  flushed synchronously in `onDestroy` (`:2163-2170`).
- `SettingsManager.savePersonaMessages` (`SettingsManager.kt:320-340`) — caps at
  `MAX_PERSONA_MESSAGES = 200` (`:31`), evicting from the oldest end and deleting evicted
  audio/image files first. `getPersonaMessages` (`:342-356`) is corruption-tolerant.

**Critical structural fact:** `_messages` (`AssistantService.kt:139`) holds **only the ACTIVE
persona's** transcript, tracked by `messagesOwnerName` (`:351`). A spontaneous message for a
*non-active* persona therefore cannot go through `SingleChatSink` (`TurnSink.kt:61-87`), which
writes straight into `service._messages.value` — it would leak into whatever persona the user
is currently looking at.

### 1.5 The `TurnContext` / `TurnSink` injection points (the thing to reuse)

```kotlin
// AssistantServiceChat.kt:1347-1371
internal data class TurnContext(
    val history: List<ChatMessage>? = null,      // null ⇒ single-chat `_messages`
    val systemPromptSuffix: String = "",         // one-turn-only, never persisted
    val sink: TurnSink? = null                   // null ⇒ SingleChatSink
) {
    val isSingleChat: Boolean get() = sink == null
    companion object { val Default = TurnContext() }
}

// TurnSink.kt:32-50   — size/append/updateAt/removeAt/peek
// TurnSink.kt:96      — sinkOrDefault(service) = sink ?: service.singleChatSink()
```

Three properties matter for this feature:

1. **The suffix is token-budgeted for free.** Both request builders append it to the system
   prompt *before* `resolveHistoryBudget`: direct-Ollama at `AssistantServiceChat.kt:1419-1438`,
   cloud at `:1696-1712`. Overflow throws the existing user-facing refusal
   (`resolveHistoryBudget` `:2521-2537`). Injecting via the suffix inherits all of this.
2. **Skills use the same suffix slot**, via `TurnContext.withSkills(userText, persona, skills)`
   (`AssistantServiceContext.kt:286-302`), gated by `shouldInjectSkills` (`:245-248`) =
   `!isImageGenerator && !isTranslator && !isImageGenerationRequestText(text)`.
3. **`isSingleChat == false`** (i.e. a non-null sink) disables the per-chunk persona guard at
   `AssistantServiceChat.kt:1603` (`turnContext.isSingleChat && !isChatContextCurrent(…)`).
   That is correct for a room (speaker ≠ active persona) and is *also* correct for a
   spontaneous turn (target persona need not be the active one) — so supplying a sink gives
   the right guard semantics by construction.

**Streaming placeholder machinery** (`AssistantServiceChat.kt:1526-1569`): `appendPlaceholderIfNeeded`
→ `sink.append(ChatMessage("assistant", ""))` + `adoptStreamedPlaceholder(generation, idx)`,
then `updatePlaceholder()` per chunk, ownership-gated. This is service-global state on purpose
(`TurnSink.kt:11-15`), and it exists to animate a bubble.

> **Design consequence:** with no UI attached there is nothing to animate, so the streaming
> placeholder is pure overhead for a spontaneous turn. This argues for a **non-streaming**
> request, which in turn means the path is *structurally* similar to `runRoomTurn` but does
> **not** reuse `performDirectOllamaChat` verbatim. See Open Q1.

### 1.6 Routing guard already available — reuse it, do not write a second one

`Persona.roomEligibility(ollamaServerNames, cloudProviderNames)` (`Models.kt:278-308`) already
decomposes the routing decision and returns:

```kotlin
sealed class RoomEligibility {
    data class Eligible(val cloud: Boolean) : RoomEligibility()
    data class Blocked(val reason: String)   : RoomEligibility()
}
```

Its internal decomposition is precisely what this feature needs:

```kotlin
val routesToCloud  = isCloud && displayServer != null && displayServer in cloudProviderNames
val isDirectOllama = displayServer != null && displayServer in ollamaServerNames
```

- `Eligible(cloud = false)` ⇒ a `[Server] model` naming a **configured Ollama server** —
  exactly a reachable-local persona.
- `Blocked("Only reachable through the gateway…")` ⇒ bare model name routed through the
  gateway, which is history-blind — **must also be excluded**, because a spontaneous message
  needs the persona's real history as context.

So the eligibility predicate for this feature is simply `roomEligibility(...) is Eligible(cloud = false)`.

**⚠ The cloud flag is *derived*, not user-settable.** `PersonaEditor.kt:301-309` recomputes
`isCloud` on every debounce:

```kotlin
val isCloudModel = cloudApis.any { it.name == providerName } ||
                   customCloudApis.any { it.name == providerName }
val updated = persona.copy(..., isCloud = isCloudModel, ...)
```

A persona therefore **becomes cloud purely by changing its model string**, without the user
ever visiting the spontaneous section. This is the decisive reason the cloud exclusion cannot
live only in the editor UI — it needs a backend guard too (§6).

### 1.7 Persona field + editor-toggle pattern (what a new toggle must follow)

**Model** — `Persona` (`Models.kt:77-123`), `@Serializable` via kotlinx.serialization, every
recent field defaulted:

```kotlin
val allowGatewayFailover: Boolean = false,           // :91
val enableThinking:      Boolean = true,             // :102
val webSearchEnabled:    Boolean = false,            // :103
val ragEnabled:          Boolean = false,            // :104
val enabledSkillIds:     List<String> = emptyList(), // :115
val iconImageUri:        String? = null,             // :121
```

`enabledSkillIds`'s KDoc (`:105-114`) documents the backward-compat contract explicitly:
*"Defaulted, so personas persisted before Skills existed decode unchanged."* New fields ride
the `personas_v2` key (`SettingsManager.kt:251-256`) with **no migration**, and ride the backup
automatically (`BackupData.personas`, `exportBackup` `:618-666`, `importBackup` `:668-700`)
because the Json is `encodeDefaults=true` + `ignoreUnknownKeys=true`.

**Editor** — `PersonaEditor.kt`. A boolean toggle is a **four-place edit**, and getting it
wrong is a documented live bug:

1. Local state: `var ragEnabled by remember(persona) { mutableStateOf(persona.ragEnabled) }` (`:156`)
2. The debounced commit's `LaunchedEffect(...)` **key list** (`:293`)
3. **Both** `persona.copy(...)` sites — the dispose-snapshot (`:222-248`) *and* the debounced
   commit (`:306-341`)
4. The `Switch` itself, with a title and an explanatory subtitle (`:886-897`)

The KDoc at `:362-382` (`setSkillEnabled`) records the failure mode: a field toggled by a
service call but omitted from the debounce/copies gets **silently written back at its open-time
value** whenever any other field changes or the editor closes inside the 500 ms window. So a new
toggle must be tracked locally *and* listed in all three places.

**Side-effecting toggle precedent** — `setSkillEnabledForPersona(name, skillId, enabled)`
(`AssistantService.kt:1828`) mutates `_personas` and `saveSettings()` immediately.

> **Historical trap to avoid:** `Room.autoReplyCap` (`Models.kt:209`) uses `Int?` where `null`
> means "inherit global default" and `0` means "no continuation". Commit `c755070` fixed a real
> defect where `0` was unreachable because the blank-field marker collided with it. Any nullable
> schedule field must keep *"unset"* and *"explicit zero/off"* apart, or mirror
> `ROOM_CAP_INHERIT` (`RoomScreens.kt:163`) and its explicit parser.

### 1.8 Skills — why the *pattern* transfers but the *trigger* does not

`matchedSkills(text, skills)` (`AssistantServiceContext.kt:165-172`) is a deterministic
**prefix match against the user's own words**:

```kotlin
skill.triggerPhrases.any { trigger ->
    trigger.isNotBlank() && trimmed.startsWith(trigger.trim(), ignoreCase = true) }
```

A spontaneous turn has **no user message**. `matchedSkills("")` returns `emptyList()`, so Skills
can never fire here — and `withSkills` takes a `userText` parameter that has no legitimate
argument. Worse, the Rooms comment at `AssistantServiceRoomTurns.kt:336-343` explicitly warns
against matching against a synthesized turn instruction, because that would make *"every skill
with a common-word trigger fire on every turn"*.

> **Conclusion:** Skills' *shape* (defined once, referenced by many, injected via
> `systemPromptSuffix`, one-turn-only, budgeted, never persisted) is exactly right to copy.
> Skills' *trigger mechanism* is unusable. See §2.3.


---

## 2. Design decisions

### 2.1 What "delivery schedule" and "time gap" concretely mean

#### The honest Android picture

| Constraint | Effect on this feature |
|---|---|
| WorkManager's minimum **periodic** interval is **15 minutes** | Any "gap" below 15 min is *not* achievable via periodic work — full stop. |
| WorkManager **does not guarantee exact timing**; it batches and defers for battery | A "9:00 AM" setting will not reliably produce a 9:00 AM message. |
| **Doze / App Standby** defer work into maintenance windows | On an idle device, a run can slip by hours. |
| **Expedited work** quota (~30 min/day on API 31+) | Cannot be the normal path. |
| `setExactAndAllowWhileIdle` needs `SCHEDULE_EXACT_ALARM`, is user-revocable, aggressively throttled under Doze | Wrong tool for a multi-second inference job; would also need a wakelock. |
| Local-Ollama inference may take **10–60 s** | Not a fit for expedited / quota-limited execution. |

**Conclusion: do not promise exact times.** The settings must be phrased as *preferences*, and
the UI must state the limitation out loud rather than imply precision the platform cannot deliver.

#### Proposed settings (on `Persona`)

```kotlin
val spontaneousEnabled:        Boolean = false,   // master opt-in, default OFF
val spontaneousInstruction:    String  = "",      // §2.3; "" ⇒ app default framing
val spontaneousGapMinutes:     Int     = 0,       // MINIMUM spacing between messages
val spontaneousWindowStartMin: Int?    = null,    // minutes-of-day, null ⇒ no lower bound
val spontaneousWindowEndMin:   Int?    = null,    // minutes-of-day, null ⇒ no upper bound
val spontaneousMaxPerDay:      Int     = 0        // 0 ⇒ policy default (§2.4)
```

**Semantics — a window that *permits* and a gap that *floors*:**

- **`spontaneousGapMinutes`** = *"at minimum this much time must pass between two spontaneous
  messages"* — a **floor**, never "every N minutes on the dot".
- **`spontaneousWindowStart/EndMin`** = *"only ever send between these times of day"* — a
  **permission**, not a schedule. `null` = anytime.
- **Hard clamp to ≥ 15 minutes** in the editor (WorkManager's periodic floor), with the UI
  saying so rather than accepting a value it will silently round up.

**Why a window + floor rather than a fixed interval only:**
- A window is how a user expresses the thing they actually want ("never at 3 AM") without the
  app claiming it will fire at 3 AM.
- A floor interval is the only sub-unit the platform can even approximately honor.
- Together they are decoupled: "roughly hourly, but only 8 AM–10 PM" is not expressible as a
  single fixed interval at all.

**Required UI copy (explicit, do not soften):**

> *"Android decides when background work actually runs. Messages will arrive **after** your
> minimum gap and **within** the times you choose, but they can be later than you expect —
> especially while the phone is idle, in Battery Saver, or off the charger. This is a limit
> of how Android handles background work, not a bug."*

Avoid the word "schedule" in the UI. Call it a **delivery window** and a **minimum gap**.

### 2.2 Scheduling mechanism

**Recommended: self-rescheduling `OneTimeWorkRequest`**, one unique job per eligible persona
(`uniqueName = "spontaneous/<personaName>"`), `Constraints.NONE`.

On each run the worker, in order:

1. **Resolve & guard** (§2.4) — persona still exists, still enabled, still local, model set,
   work-name key still valid. Precondition gone → `Result.success()` and **do not** re-arm.
2. **Check the gap** — `now - lastSentAt >= gap`. Not due → `Result.success()` **and re-arm**
   for the next moment that could be due.
3. **Check the window** — outside `start..end` (handle `start > end` as a midnight-wrapping
   window; `null` = unrestricted) → `Result.success()` **and re-arm** at the next window-open
   slot via `setInitialDelay`.
4. **Preflight reachability** of the configured Ollama server (cheap probe). Unreachable → §2.5.
5. **Generate** (§3), **persist** (§5), **notify** (§4).
6. **Record** `lastSentAt` / `sentToday`, then **re-arm** with a computed `initialDelay`
   (gap + jitter, clamped into the window).

**Why this over `PeriodicWorkRequest`:**
- Expresses *windows* exactly (periodic work cannot), and skips out-of-window cycles entirely
  rather than waking up ~14×/night to do nothing — measurably better for battery.
- Expresses *sub-15-minute gaps* correctly (the periodic path has a 15-minute floor; the
  initial-delay path does not).
- Avoids burning Doze quota on no-op runs.

**Trade-off, stated honestly:** WorkManager still will not run the job at the exact chosen
minute — it runs at the next convenient opportunity after the delay. This design is
*batch-friendly and window-honest*, not precise.

**Why WorkManager rather than AlarmManager or leaning on the FGS:**
- It is the only candidate here with **automatic reschedule on reboot** — this app currently has
  **no boot receiver**, so anything else would silently stop working after a restart.
- It survives process death.
- It is the Android-recommended API for exactly this class of job.
- It needs **no custom `Application` class**: its default initializer runs via an
  `androidx.startup` `ContentProvider` — which is why the app can keep having none.

**Stagger:** several enabled personas get a small random delay jitter, so three enabled
personas do not fire three simultaneous notifications (which reads as a bug).

**New dependency:** `androidx.work:work-runtime-ktx` in `gradle/libs.versions.toml` +
`app/build.gradle.kts`. Not currently in the local Gradle cache — first build needs network.

### 2.3 What prompts the model to generate a message

A spontaneous turn has no user message. Three candidates:

| Option | Verdict |
|---|---|
| **(a) Fixed generic instruction** (a constant, like `roomTurnInstruction`) | Cheap, zero config, but every persona speaks in the same register; no way to say "only about X". |
| **(b) Reuse the Skills pattern** (trigger phrases + instruction) | **Not possible** — `matchedSkills` is a prefix match on *user words* (§1.8); there are none, and matching synthesized text is explicitly forbidden by the Rooms comment at `AssistantServiceRoomTurns.kt:336-343`. |
| **(c) Per-persona configurable instruction, blank = app default** | **Recommended.** Mirrors `systemPrompt` being per-persona; rides `TurnContext.systemPromptSuffix`; budgeted for free; never persisted to history. |

**Recommendation: (c)**, via `spontaneousInstruction: String = ""`.

Injected structure (all via `TurnContext.systemPromptSuffix`, appended *after* the persona's
own system prompt — the persona's prompt keeps governing, per `roomSystemPromptSuffix`'s stated
contract at `AssistantServiceRoomTurns.kt:500-507`):

```
[framing sentence — always present, app-authored, non-overridable]
  - You are messaging the user on your own; they did not ask you anything.
  - Speak ONLY as <name>. Never write the user's lines or invent one.
  - Never claim the user said something they did not say.
  - One message only. Plain message, no header, no "…" tail.
  - Current time, and how long since the last message in this conversation.
  - If you have nothing worth saying, return nothing.
[persona's own spontaneousInstruction, if non-blank]
[recent history, supplied via TurnContext.history — NOT the suffix]
```

**Trade-offs to name:**
- A free-text field can be pointed *at* the system prompt. Mitigation: it is only ever
  **appended**, never substituted, so it cannot displace the persona's identity — same contract
  as `roomSystemPromptSuffix`.
- The framing sentence must be app-authored, because *it* carries the guardrails; the persona's
  text only steers topic and tone.
- Blank-by-default is right: a new toggle should behave predictably before any configuration.

**Silence — recommended, default on.** A persona that *must* speak every cycle will spam even
when there is nothing to say, and that is the single biggest "this feels creepy" failure mode.
Implement as an explicit sentinel the app strips (empty output, or a short marker), with the
instruction stating silence is allowed. Cost: needs a parser + a unit test (the house already
tests parsers — `SuggestedRepliesParserTest`). Risk: right after enabling, a user may see
nothing and think it is broken — which is why the editor needs **"Send me a message now"**
(Open Q5) to prove the pipeline works.


### 2.4 Cloud exclusion — both enforcement points

Same cloud-cost caution already applied to Rooms. Two deliberately redundant layers.

**Layer 1 — persona editor UI (so it is never even offered)**

In `PersonaEditor.kt`, the spontaneous section renders **only** when the persona is currently
local. Compute the way the editor already computes cloud identity (`:301-309`) and the way
routing decides (`roomEligibility`'s `isDirectOllama`):

```
eligibleForSpontaneous =
    isDirectOllamaModel(model, cloudApis, customCloudApis, ollamaBases)  // not cloud, not gateway-only
    && !isTranslator && !isImageGenerator                                 // same exclusions as shouldInjectSkills
    && model.isNotBlank()
```

- **Eligible** → render the `Switch`, the window, the gap, the instruction field.
- **Not eligible** → render an explanatory row instead, reusing the `RoomEligibility.Blocked`
  reason-text pattern so wording is shared: *"Spontaneous messages are available for local
  (Ollama) personas only. This persona's model routes to <provider>, which would be a paid
  turn nobody asked for."*
- Critically: **when not eligible the committed `Persona` copy must write
  `spontaneousEnabled = false`** (layer 2 enforces this too), so switching a persona to a cloud
  model and closing the editor necessarily turns the feature off.

**Layer 2 — backend guard (survives any path that bypasses the UI)**

The UI cannot be the only gate, because `isCloud` is **derived from the model string** and can
change with no editor visit (§1.6). Guard at the write chokepoint *and* the exec site:

- **Write chokepoint** — `AssistantService.updatePersona(index, persona)`
  (`AssistantService.kt:1678`) is the one function every editor commit flows through (both the
  debounced commit and the dispose flush call `onSaveRef.value` → `onSave` → `updatePersona`).
  Sanitize there: if the persona is not `Eligible(cloud = false)`, force
  `spontaneousEnabled = false` before storing. This makes *every* write path safe — editor,
  backup import, or a future API.
- **Exec site** — the scheduler re-evaluates `roomEligibility(...) is Eligible(cloud = false)`
  **on every run**, immediately before generating (§2.2 step 1). This covers a model changed to
  cloud *after* the toggle was set, with no editor interaction at all. A job for a now-
  ineligible persona returns `Result.success()` and cancels itself.
- **One shared predicate** — not a hand-rolled check in each place. Recommend
  `Persona.spontaneousEligibility(ollamaServerNames, cloudProviderNames): RoomEligibility`
  living next to `roomEligibility` in `Models.kt`, so the rule exists exactly once (the same
  reasoning `shouldInjectSkills` states at `AssistantServiceContext.kt:243`).

**Layer 3 (recommended) — a `RoomReplyPolicy`-style policy object.** Mirror
`RoomReplyPolicy.participatesInAutonomousRounds(cloud)` (`Models.kt:349`) with a `SpontaneousPolicy`
holding the default cap, the 15-minute floor, and the eligibility rule — so constants are not
scattered and get unit-tested the way `RoomModelTest.kt:138-151` tests the cap-0 case.

Exact locations are tabulated in §6.

### 2.5 Asleep / offline / unreachable local server

A local Ollama persona is only reachable when the phone can reach that server. Three distinct
failure classes must not be conflated:

| Class | Detection | Action |
|---|---|---|
| **(i) Not due** | gap not elapsed, or outside window | `Result.success()`, **re-arm** at the correct next slot. Not a failure. |
| **(ii) Precondition gone** | persona deleted/renamed, toggle off, became cloud, became gateway-only, model blank | `Result.success()`, **cancel** — do not re-arm. |
| **(iii) Infrastructure** | no network, host unreachable, connect timeout, 5xx | see below |

**For (iii): skip the cycle silently — do NOT retry aggressively.**

- **One bounded attempt**, short connect timeout (a phone on cellular, talking to a laptop that
  may be asleep).
- **Never `Result.retry()` with WorkManager's default exponential backoff** (30 s doubling). A
  laptop that is off will not come back in 30 s, 60 s or 120 s; tight retries burn Doze quota
  and battery for nothing. Record the failure and **re-arm for the next normally-due slot**
  (respecting the gap) — "try again the next time we were going to try anyway."
- **No notification on failure.** A missed check-in is invisible; only *sent* messages notify.
- **No catch-up.** After any outcome, compute the next fire time **from now**, never from the
  missed slot. Otherwise a phone off for two days wakes up and fires 20 messages at once —
  precisely the failure that would make the feature feel broken.
- **Optional diagnostic:** persist `lastFailureAt` + reason and show a quiet
  *"couldn't reach <server> at last attempt"* line in the persona editor, so the feature is
  debuggable. No notification — silence is the correct user experience for a background miss.

**Doze specifics:** when WorkManager finally runs after a Doze window the job is already "late";
the **window gate is re-checked at run time**, not only at scheduling time, so a message must
not land at 3 AM merely because the phone woke then.


### 2.6 Where in the turn pipeline it routes

**Reuse `TurnContext`/`TurnSink` — do not build a parallel injection path.** Concretely:

New `AssistantServiceSpontaneous.kt`, deliberately structured like `AssistantServiceRoomTurns.kt`
(its header comment is the template: *only the transcript source and destination differ*):

```
TurnContext(
    history            = <persona's own persisted history, via getPersonaMessages(name)>,
    systemPromptSuffix = <framing + spontaneousInstruction + current time / silence-since>,
    sink               = PersonaHistorySink(personaName)
)
```

- **`PersonaHistorySink`** — a `TurnSink` implementation in the shape of `RoomTurnSink`
  (`AssistantServiceRoomTurns.kt:108-195`), but writing to
  `settingsManager.savePersonaMessages(...)` for that persona rather than to `_roomMessages`,
  because only one persona's transcript is ever in `_messages` (§1.4). It must:
  - hold its own in-memory copy of that persona's history, read once at turn start;
  - `append`/`updateAt`/`peek` against that copy, then persist on `Dispatchers.IO` behind a
    serializing job — the same discipline as `saveRoomHistory` (`AssistantServiceRooms.kt:45-55`);
  - implement an `isAttached()`-style guard (`RoomTurnSink.kt:136-153`) so a turn for a persona
    deleted or renamed mid-flight **refuses** its writes (`append` returns `-1`, mirroring
    `:153`) rather than misfiling the message.
- **Supplying a non-null `sink` makes `isSingleChat == false`**, exactly what we want: it
  disables the active-persona guard at `AssistantServiceChat.kt:1603`, since a spontaneous
  turn's target need not be the active persona. Same reason it works for rooms.
- **Skills are deliberately NOT fired** on this turn — there is no user text to match against
  (§1.8).
- **Budget:** the suffix is appended before `resolveHistoryBudget` in the request builder, so
  token accounting and the overflow refusal are inherited, not re-derived (§1.5).

**Generation / sequencing — a background job must not claim the shared chat sequence.**
`nextChatRequestSeq()` (`AssistantService.kt:370`) is shared across single chat, voice and
rooms; bumping it from a spontaneous turn would *supersede a user's in-flight turn*. Since the
recommended worker executes without the service (§1.1), it never touches `chatRequestSeq` at
all, and cannot cancel or collide with the user. State this explicitly as a reason for the
architectural choice.

**Foreground case:** if the app *is* open and the user is mid-turn on the same persona, the
spontaneous message is appended after the in-flight exchange. Because the sink does a
read-modify-write of its own snapshot behind a serializing job, ordering is preserved and
nothing is overwritten — the spontaneous message simply appears after the current exchange.
Accept this as correct and natural; do not add cross-turn locking in v1.

**Execution path (Open Q1):** a **non-streaming** request, because there is no bubble to animate
and no H3 placeholder to manage. That means the path shares *request assembly and budgeting*
with `performDirectOllamaChat` but is not literally that function. Recommend **extracting the
shared assembly** (final system prompt = persona prompt + suffix + date-time + context prefix;
then `resolveHistoryBudget`) into one pure helper used by both — the house pattern:
`resolveHistoryBudget` was already extracted once precisely so the rule *"exists exactly once"*
(`AssistantServiceChat.kt:2500-2521`).

**Non-goals for v1:** no TTS, no audio focus, no `_state` writes, no microphone, no writes to
the FGS notification (§4.2).


---

## 3. Data model

### 3.1 New fields on `Persona` (all defaulted — existing personas unaffected)

```kotlin
// Models.kt, appended to the Persona data class after backgroundImageUri (:122)

/** Master opt-in. Default OFF: a brand-new or legacy persona never messages anyone. */
val spontaneousEnabled:        Boolean = false,
/** "" ⇒ the app's default framing (§2.3). Appended to the system prompt, never substituted. */
val spontaneousInstruction:    String  = "",
/** Minimum spacing between two spontaneous messages, in minutes.
 *  0 ⇒ use the policy default floor. NOT "no gap" — see the trap below. */
val spontaneousGapMinutes:     Int     = 0,
/** Minutes-of-day. null ⇒ no lower/upper bound (a real value, not an inherit marker). */
val spontaneousWindowStartMin: Int?    = null,
val spontaneousWindowEndMin:   Int?    = null,
/** Per-day cap. 0 ⇒ policy default. */
val spontaneousMaxPerDay:      Int     = 0
```

**Backward compatibility, verified:** every field has a default, so pre-existing `personas_v2`
JSON decodes unchanged — the contract `enabledSkillIds` already states (`Models.kt:111-113`).
**No migration.** No `BackupData` change either: `BackupData.personas: List<Persona>` serializes
the new fields automatically (`encodeDefaults=true`), and `ignoreUnknownKeys` lets an older
build import a newer backup (`SettingsManager.kt:1013-1023` documents exactly this property for
`rooms`/`skills`).

**Avoid the `autoReplyCap` trap** (§1.7): `Room.autoReplyCap` used `Int?` where `null` meant
"inherit" and `0` meant "no continuation", and commit `c755070` fixed a real defect where `0`
was unreachable because the blank-field marker collided with it. Here:
- `null` on window fields means *"no bound set"* — a genuine value, not an inherit marker, so
  no sentinel constant is needed.
- `0` on `spontaneousGapMinutes` means *"policy default floor"*, **never** *"no gap"*, and is
  clamped in the editor so the distinction can't be reached from the UI.

### 3.2 Where the schedule state lives

`lastSentAt: Long` and `sentTodayCount: Int` / `sentTodayDate: String` are **runtime** state,
not persona configuration. Recommend a `SettingsManager` entry
(`spontaneous_state_v1`, `Map<String, SpontaneousState>` keyed by persona name) rather than
putting them on `Persona`, because:

- they are written on every send while the persona is *not* being edited — keeping them off
  `Persona` avoids a stale editor snapshot writing back an old `lastSentAt` and re-firing
  immediately, which is the exact defect class `PersonaEditor.kt:362-382` documents;
- they are not meaningful off-device, unlike the settings they accompany (same reasoning as
  the artwork-path caveat at `SettingsManager.kt:623-626`).

### 3.3 Notification model additions

- Second channel constant, e.g. `CHANNEL_ID_SPONTANEOUS = "spontaneous_messages"`,
  `IMPORTANCE_DEFAULT`, created alongside the existing one inside `createNotificationChannel()`
  (`AssistantService.kt:2204`) so it exists before any message can be posted.
- Notification ID namespace separate from `NOTIFICATION_ID = 1`, stable per persona (a
  re-notification updates rather than stacks), never colliding with the FGS notification.

---

## 4. Notification UX

### 4.1 What kind of notification

- **Separate channel** (rationale in §1.2): `IMPORTANCE_DEFAULT` so it is audible/heads-up
  capable, independently dismissible, and independently silencable.
- **Never `setOngoing(true)`** — it must be dismissible.
- **`BigTextStyle`** showing the full message; content text = first line / `~120 chars`.
- **Title = persona name.** Optionally the persona icon (`Persona.iconImageUri`, `Models.kt:121`)
  as large icon — requires decoding the file to a `Bitmap`; feasible but recommend deferring
  (Open Q7).
- **`setAutoCancel(true)`** — tapping dismisses.
- **Grouping:** collapse a burst into one group instead of stacking five heads-up banners.

### 4.2 What it must never do

Mirroring `allowPlayback = false` for room turns (`AssistantServiceRoomTurns.kt:349-367`):

- **No audio.** Never speak, never TTS, never touch audio focus, never set `_state`.
- **Never mutate the FGS notification** (do not call `updateNotification`).
- **Never claim `chatRequestSeq`** (§2.6) — must not supersede a user's in-flight turn.
- **Never grab the mic** — and therefore never run as a microphone-typed foreground service.


### 4.3 Tap → open directly into that persona's chat

This needs new plumbing — none exists today (§1.3). Recommended shape:

1. **New channel**, and a `PendingIntent` to `MainActivity` carrying
   `EXTRA_TARGET_PERSONA_NAME` (and optionally a message index), built with
   `FLAG_ACTIVITY_NEW_TASK | FLAG_ACTIVITY_CLEAR_TOP | FLAG_ACTIVITY_SINGLE_TOP | FLAG_IMMUTABLE`.
2. **Manifest:** add `android:launchMode="singleTop"` to `MainActivity` so a tap while the
   activity is backgrounded routes to `onNewIntent` instead of stacking a second instance.
   This is a global behavior change — the existing `bindService` in `onCreate`
   (`MainActivity.kt:111-128`) survives `onNewIntent` (no re-`onCreate`), so no rebinding logic
   is needed, but it must be **tested** (Open Q3).
3. **`onNewIntent` override** in `MainActivity` reads the extra; `onCreate` reads it too, since
   a cold start arrives there.
4. **Push into Compose:** the Activity holds a `MutableStateFlow<String?>` pending-target that
   `MainScreen` collects in a `LaunchedEffect`, then:
   - `service.switchPersona(target)` — safe at any time: it `stopEverything()`s first and swaps
     history correctly (`AssistantService.kt:2348-2413`);
   - force `showPersonaPicker = false` and `showSettings = false`;
   - clear the pending target so it fires exactly once.
5. **Foreground + mid-turn caveat:** `switchPersona` stops playback and bumps the generation, so
   tapping a notification during a live turn *will* interrupt it. Same as tapping a persona in
   the selector — defensible, but a conscious choice (Open Q4).

**Fallback if `singleTop` proves disruptive:** use `NEW_TASK | CLEAR_TOP` without `singleTop`
and accept a second Activity instance on top. It works, but stacks in recents and is a visibly
worse experience. Recommended: do `singleTop` properly.

---

## 5. Persistence of the generated message

The message must "look and behave like any other message afterward", so it is stored as an
ordinary `ChatMessage("assistant", text, ...)` in that persona's normal history — no marker
fields required for correctness.

1. **Sink write** (§2.6): `PersonaHistorySink` appends the final `ChatMessage` to its snapshot
   of `getPersonaMessages(personaName)` and persists via
   `settingsManager.savePersonaMessages(personaName, snapshot)` on `Dispatchers.IO`, serialized
   behind a per-persona IO job — the same chain discipline as `saveRoomHistory`
   (`AssistantServiceRooms.kt:45-55`) and `historyIoJob`.
2. **Reuses the existing 200-message cap** for free (`SettingsManager.kt:320-340`), so
   spontaneous messages participate in normal eviction including audio/image file cleanup.
3. **Role is `"assistant"`** — fed verbatim to the model as history, which is exactly right:
   the next turn sees the spontaneous message as something *it* said. `ChatMessage.role` is a
   plain `String` (`Models.kt:31`), so **do not** overload it with a new value — a marker would
   have to be a *new nullable field*, and the requirement is that it behave like any other
   message. Recommend **no visible marker in v1** (Open Q8).
4. **History as context:** the turn reads the same persisted list it is about to append to, so
   the model sees the real recent conversation and can react to it — "been a while, how did that
   thing go?" rather than a blind greeting. The date-time line is already injected by the
   request builder (`AssistantServiceChat.kt:1402-1403`), and the framing adds "no one has
   spoken to you since <timestamp>".
5. **Active-persona consistency:** if the target persona is the active one, its history is also
   in `_messages`. `switchPersona` reloads from disk on entry (`AssistantService.kt:2406-2412`),
   so a message written while the user was elsewhere appears when they switch back. If they are
   *already* looking at it, the sink must not diverge from `_messages` — recommend writing
   through `_messages` when `messagesOwnerName == personaName`, and through the detached
   snapshot otherwise. This is the `isAttached()` branching pattern (`RoomTurnSink.kt:136-147`)
   applied to a different owner check.


---

## 6. Cloud-exclusion enforcement — exact locations (the hard constraint)

Restated compactly, because it is the constraint already decided:

| # | Location | What it does |
|---|---|---|
| **1** | `PersonaEditor.kt` — spontaneous section render gate | Section renders only when the persona is local-and-directly-Ollama; otherwise an explanatory row (wording shared with `RoomEligibility.Blocked`). |
| **2** | `PersonaEditor.kt` — debounced commit + dispose snapshot | When the persona is not eligible, commit `spontaneousEnabled = false` explicitly, so a model change + close always turns it off. |
| **3** | `AssistantService.updatePersona` (`AssistantService.kt:1678`) | **Backend write guard.** The single chokepoint every editor commit and bulk update flows through. Sanitize `spontaneousEnabled = false` for non-eligible personas before storing. Catches backup import and any future non-UI path. |
| **4** | Scheduler preflight, every run (§2.2 step 1) | Re-evaluate `roomEligibility(...) is Eligible(cloud = false)` **at generation time**; cancel the job if no longer eligible. Catches a model changed to cloud *after* the toggle was set. |
| **5** | `Models.kt` — one shared `Persona.spontaneousEligibility(...)` predicate | The rule exists exactly once (mirrors `shouldInjectSkills`'s stated intent, `AssistantServiceContext.kt:243`), used by 1–4 so the layers can never drift apart. |
| **6** | `SpontaneousPolicy` (next to `RoomReplyPolicy`, `Models.kt:325`) | Holds `isEligible(cloud)`, the 15-minute floor, the default gap and cap — unit-tested alongside `RoomModelTest`. |

Layers 1–2 are the UI-side guard the requirement specifies; 3–5 are the backend guard that
survives any path bypassing the UI. **Both are required**, because `isCloud` is derived from
the model string and can flip with no editor visit (§1.6).

---

## 7. Scheduling & Android background-execution integration (summary)

- **Mechanism:** self-rescheduling `OneTimeWorkRequest`, one per eligible persona, unique name
  `spontaneous/<personaName>`, `setInitialDelay(gap + jitter)` clamped into the window.
- **New dependency:** `androidx.work:work-runtime-ktx`. Default initializer runs via
  `androidx.startup`, so **no `Application` class needed**.
- **Manifest:** no new permissions (`INTERNET`, `ACCESS_NETWORK_STATE`, `POST_NOTIFICATIONS`
  already declared). **No `SCHEDULE_EXACT_ALARM`** — deliberately not using exact alarms.
  **No FGS-type change** — the worker never starts the foreground service.
- **Reboot survival:** free from WorkManager, and notable because the app has no boot receiver.
- **Explicitly not done:** exact-alarm scheduling, expedited work, holding a wakelock across
  inference, or leaning on the existing microphone FGS.
- **Honest limitation, stated in the UI (§2.1):** timing is best-effort; window and gap are
  preferences, not a guaranteed schedule; Doze, battery saver and batching can all delay a run.

---

## 8. Proposed implementation order

1. **Data model + policy** — new `Persona` fields, `SpontaneousPolicy`, `spontaneousEligibility`,
   `SpontaneousState` store. JVM tests: defaults round-trip, eligibility cases, window arithmetic
   (incl. midnight wrap), gap clamp, cap-0 semantics.
2. **Backend write guard** — sanitize in `updatePersona`; test that a cloud persona cannot hold
   `spontaneousEnabled = true` through the write path.
3. **Editor UI** — section, gate, four-place toggle wiring, clamp, "Send me a message now"
   (Open Q5). Compose test following `SkillUiInstrumentedTest` /
   `RoomEditorUiPersistenceInstrumentedTest`, including the dispose-flush path — the house has
   previously shipped a bug that bypassed exactly this (`PersonaEditor.kt:362-382`).
4. **Turn pipeline** — `PersonaHistorySink`, shared request-assembly extraction, non-streaming
   generation. Instrumented test against the loopback `OllamaSuggestionStub.kt`.
5. **Worker + scheduling** — WorkManager wiring, guards, re-arm/cancel rules, no-catch-up,
   jitter. Test the decision logic as pure functions; one end-to-end run instrumented.
6. **Notification + deep link** — channel, notification, `singleTop`, `onNewIntent`, pending
   target → `switchPersona`. Instrumented test for tap-through, cold and warm.
7. **Backup round-trip** — assert the new fields survive export/import
   (`SettingsManagerCorruptionTest` style).


---

## 9. Open design questions

Named explicitly — not only the ones in the brief.

**Architectural**

1. **O1 — Worker-owned request vs. reusing `performDirectOllamaChat` whole.**
   The recommended design executes the model call **inside the worker, non-streaming**, and
   shares only request assembly + budget (§2.6). The alternative is to start
   `AssistantService` from the worker and reuse the *entire* pipeline, streaming and H3
   placeholders included. Cost/benefit: the latter reuses more but **requires starting a
   foreground service from the background** — the only available type being `microphone` (wrong,
   and policy-hostile) or `dataSync`/`specialUse` (each with its own background-start and
   store-policy burden) — and it would give the H3 machinery state with no UI attached to
   consume it. **Recommendation: worker-owned, non-streaming** — but this is the biggest
   architectural decision and should be confirmed before step 4.

2. **O2 — Should a spontaneous turn ever write through the live `_messages`?**
   §5 proposes branching the sink on `messagesOwnerName == personaName`. Worth the complexity,
   or should it *always* write the detached snapshot and rely on `switchPersona`'s reload? The
   branch only covers "the user is staring at this persona right now".

3. **O3 — `launchMode="singleTop"` is a global Activity change.** It affects every future
   intent into `MainActivity`, not just notifications. Needs explicit sign-off plus a test pass
   over service binding across `onNewIntent`. Acceptable, or prefer the simpler
   (stacked-instance) fallback?

4. **O4 — Interruption semantics on tap.** Tapping while a turn is in flight on *another*
   persona calls `switchPersona` → `stopEverything()`, cancelling that turn. Same as tapping a
   persona in the selector, but arrived unbidden. Alternatives: auto-switch only when the app
   is backgrounded; or, if already foregrounded elsewhere, open without switching.

**Behavioral / product**

5. **O5 — Is a manual "Send me a message now" button in scope?** Strongly recommended: it makes
   the feature testable without waiting for a schedule, doubles as a debug affordance for
   unreachable-server issues (§2.5), and is how the silent-output path (§2.3) proves it works.

6. **O6 — How much silence is acceptable?** If the model may decline, a user could enable the
   feature and receive nothing for days. Should the editor show a count of messages actually
   sent ("last message: 2 days ago") so silence is legible? Or should there be a floor
   guaranteeing at least one message per window?

7. **O7 — Persona icon in the notification.** Nice-to-have; adds bitmap decoding on the worker
   thread. Defer?

8. **O8 — Should the message be marked in history at all?** The brief requires it to look and
   behave like any other message. Recommend **no marker in v1**. If per-message UI is ever
   wanted, `ChatMessage` needs a new `Boolean` field — note it is fed verbatim to the model, so
   a marker must never become a `role`.

9. **O9 — Interaction with Rooms.** If a persona is in a room *and* has spontaneous messages
   on, both can run concurrently. Nothing collides (the worker does not touch `chatRequestSeq`),
   but the user could see a spontaneous message in the persona's private history while a room
   runs. Probably acceptable — rooms and personal chats are separate transcripts by design.

10. **O10 — Multiple personas enabled simultaneously.** Jitter staggers them (§2.2), but should
    there be a *global* daily budget across all personas, not just per-persona
    `spontaneousMaxPerDay`? Six enabled personas could otherwise mean six notifications an hour.

11. **O11 — A persona with empty history.** A brand-new persona has no prior conversation to
    react to. Does it introduce itself? Skip? The framing must handle "no prior messages"
    explicitly — otherwise the model is told "no one has spoken to you since <nothing>".

12. **O12 — Translation / image-generator personas.** Excluded by `spontaneousEligibility`
    (§2.6, mirroring `shouldInjectSkills` at `AssistantServiceContext.kt:245`) — but confirm the
    intent: a translator has no conversational history to speak from, and an image generator's
    output needs a path for the generated image that has no UI to attach to.

13. **O13 — Per-persona gap only, or also a global default?** Rooms solved this with
    `Room.autoReplyCap: Int?` where `null` = inherit (`Models.kt:209`). The plan uses
    `0 = policy default`, which is simpler but must keep unset/0 distinct — the exact defect
    fixed in `c755070`. Confirm the sentinel semantics before step 1.

14. **O14 — Does the notification open the *message* (scroll to it) or just the persona?**
    Requires an index/id plus scroll-to on `ChatList`. Nice, not in v1.

---

**Status:** research complete, plan written. **Stopping here for review — no implementation
code written.**

---

## 10. Implementation status — Phase 1 (data model + cloud guard): DONE

Built and verified. No scheduling, no worker, no UI yet.

**Delivered**

| Plan location | Where it actually landed |
|---|---|
| #5 shared predicate | `Persona.spontaneousEligibility(...)` — `Models.kt`, delegates to `roomEligibility` |
| #6 policy + #3 guard | `SpontaneousPolicy` — `Models.kt` (constants, `effectiveGapMinutes`, `effectiveMaxPerDay`, `sanitize`) |
| #3 write chokepoint | `SettingsManager.savePersonas` — **and this is the one that actually closes the import path** (see errata) |
| #3 in-memory | `AssistantService.addPersona` + `updatePersona` |
| +#7 load-time | `AssistantService.loadSettings` (supplementary; hand-edited storage) |
| — | `persistSettings` reordered: `saveOllamaBases` now precedes `savePersonas` |
| #1 render gate | **Deferred to the UI phase** (no section exists to gate yet) |
| #4 exec-time preflight | **Deferred to the worker phase**; predicate is ready and tested |
| #2 editor commit | **Deliberately not duplicated** — see errata |

**Verification:** `assembleDebug` builds; 240 JVM unit tests pass (24 new in
`SpontaneousGuardTest`); the guard is mutation-verified (disabling the `savePersonas`
sanitize fails exactly the 4 persistence tests and no others).

### Phase 2 (step 2) — write-guard verification at the service level: DONE

The guard code itself shipped in phase 1; what phase 2 adds is proof that the **in-memory**
write path applies it, which only a bound service can show.
`SpontaneousWriteGuardInstrumentedTest` (6 tests, on-device, Redmi Note 9 Pro / API 33):

- the probe provider is genuinely cloud-registered (non-vacuity guard, so a cloud test cannot
  pass as gateway-only);
- `addPersona` strips an enabled cloud persona and keeps an enabled local one;
- `updatePersona` strips the flag when a **local persona's model is switched to a cloud
  provider** — the realistic sequence, since `isCloud` is derived from the model string;
- `updatePersona` keeps the flag on an unrelated edit (a guard that stripped everything would
  pass the above);
- a persona pointed at an unconfigured server cannot keep the flag.

**Mutation-verified:** disabling the `addPersona` + `updatePersona` sanitize fails exactly
those 3 guard-dependent tests, leaving the 3 controls green.

### Phase 3 (step 3) — the persona editor UI: DONE

**Delivered**

- `SpontaneousScreens.kt` (new) — the pure gate `spontaneousSectionEligibility`, the slider
  maps (`spontaneousGapFromSlider`, `spontaneousWindowMinuteFromSlider`), the summaries, and
  `PersonaSpontaneousSection` (master toggle, optional instruction, gap slider, an optional
  daily window with start/end sliders, the "send me a message now" button, and the honest
  Android-timing note). Test tags: `spontaneousMasterSwitch`, `spontaneousWindowSwitch`,
  `spontaneousSendNow`.
- `PersonaEditor.kt` — the four-place wiring (local state, the debounce key list, BOTH
  `persona.copy` sites, and the section itself), plus **plan location #2** now that the
  section exists: the commit writes `spontaneousEnabled && spontaneousAllowed`.
- `AssistantService.sendSpontaneousMessageNow` + `SpontaneousNowOutcome` — see the stub note.
- `Models.kt` — `SpontaneousPolicy.effectiveGapMinutes(Int)` overload so the editor reads the
  same rule without fabricating a Persona.

**The gate reads ONE decision.** `spontaneousEligibility` is computed once in the editor from
`service.ollamaServerNames()` / `service.cloudProviderNames()` and threaded into *both* the
render gate and the commit, so they cannot disagree. `spontaneousSectionEligibility` also
re-derives `isCloud` from the live model text — otherwise a just-typed cloud model would be
reported with the *gateway* reason (the verdict was right, the explanation was misleading).

**Verification**

- 254 JVM unit tests, 0 failures (14 new in `SpontaneousUiTest`).
- 6 on-device write-guard tests, 0 failures (unchanged).
- 4 on-device editor tests, 0 failures — including
  `gate is correct on the first frame before collectors deliver`, which freezes the compose
  clock so no `LaunchedEffect` collector has delivered and asserts the controls are in the
  tree anyway.
- **Mutation-verified (the important one):** re-pointing the gate at the editor's
  async-collected snapshots makes that test fail with exactly the predicted symptom (the
  toggle's node does not exist, because the gate rendered the refusal) — while the other
  three still pass, because only the frozen-frame test examines the pre-delivery window.

### Known deliberate stub — the "send me a message now" button

The button is real, enabled, and wired to a real service entry point, but
`sendSpontaneousMessageNow` currently performs the **full guard chain** (persona exists →
opted in → eligible against live lists) and then returns `Accepted` *without sending*: the
delivery path is step 4. The editor says so plainly ("This persona can send — delivery itself
arrives with the next step.") rather than reporting a send that did not happen. Step 4 changes
only that function body; the UI wiring behind it is final.


### Phase 4 (step 4) — the turn pipeline: DONE

**Delivered**

- `AssistantServiceSpontaneous.kt` (new) — `PersonaHistorySink` (dual-mode by
  `messagesOwnerName`, fail-closed when the persona is gone), the framing
  (`spontaneousTurnInstruction`, `spontaneousSystemPromptSuffix`, `spontaneousContextLine`),
  the silence parser (`parseSpontaneousReply`), the non-streaming completion
  (`performSpontaneousOllamaCall`), and `runSpontaneousTurn`.
- Shared assembly: `composeSystemPrompt` + `AssistantService.budgetedHistory`, now used by
  BOTH existing budgeted builders (`performDirectOllamaChat`, `buildCloudRequest`) as well as
  the new turn; `resolveHistoryBudget` / `estimateTokens` widened `private` → `internal`.
- `sendSpontaneousMessageNow` dispatches on `serviceScope` and catches with silence-by-design.

**The turn may end in silence or log-only failure.** Both write nothing — a background message
that could not be produced must not leave an error bubble in a conversation the user never
asked about. `runSpontaneousTurn` lets transport errors *propagate* (the caller classifies);
the dispatch site catches silently, as the future worker will re-arm without retrying.

**Two bugs found by the tests, both fixed.** The sentinel was stated in the instruction turn
but not in the system prompt — the wire test asserted the prompt carried it and failed, which
is exactly the failure mode a model obeying the prompt and ignoring the turn would produce.
And `parseSpontaneousReply`'s "is only the sentinel ⇒ send nothing" rule is a documented
*compromise*, not a settled answer: it is deliberately lenient about output that merely
mentions the sentinel, so a friendly check-in mentioning silence is never swallowed. That
tradeoff is named in the KDoc and pinned by a test; it is NOT mutation-verified, because
mutation-verifying a lenient rule means proving the lenient branch fires, which it does by
construction.

**Verification**

- 263 JVM unit tests, 0 failures (9 new in `SpontaneousTurnTest`, incl. a byte-for-byte
  regression anchor on the extraction's prompt order).
- 4 on-device turn tests, 0 failures (`SpontaneousTurnInstrumentedTest`: lands in that
  persona's history only, silence writes nothing, wire is non-streaming with the persona's
  framing, unreachable server raises-but-writes-nothing).
- All prior verdicts re-checked: 254 other unit tests, 6 write-guard + 4 editor-gate
  on-device tests still green; `assembleDebug` builds.

### Still a stub — what step 4 deliberately left for step 5

`runSpontaneousTurn` is only reachable through the manual "send now" button. There is no
scheduler, no `SpontaneousState` store, no notification. The button's toast is now accurate
("is writing — a message will appear in their chat", plus the silence caveat).



### Errata — two corrections to §6 above

1. **`AssistantService.updatePersona` does NOT catch backup import.** §6 originally claimed
   it did. `importBackup` writes personas through `SettingsManager.savePersonas` directly and
   never reaches `updatePersona`. The guard therefore had to go in `savePersonas`, which is
   the only writer of the `personas_v2` key, and that is where it now lives — with an
   end-to-end test (`importBackup cannot smuggle a cloud persona in as enabled`) proving the
   path is closed.

2. **Plan location #2 (a second eligibility check inside the editor commit) was not
   implemented, on purpose.** The editor has no UI to set this flag yet, and its only
   persistence route is `onSave` → `addPersona`/`updatePersona` → `savePersonas`, all three of
   which now sanitize. Adding a fourth derivation inside the composable would be *less*
   authoritative than those: `PersonaEditor` collects its server lists asynchronously, so a
   check running before the first emission would evaluate against empty sets and could
   false-positive strip a legitimately-enabled local persona. The gate `#1` will use the
   tested `spontaneousEligibility` predicate once there is a section to render.

