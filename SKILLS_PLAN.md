# Plan: Skills — a shared, persona-independent instruction library

## Goal

A **Skill** is a named block of instruction text plus one or more trigger phrases, stored
once in a shared library. Any persona may enable any subset. When an incoming user message
matches the trigger of an *enabled* skill (deterministic text match — no LLM involvement in
the decision), that skill's instruction text is appended to the system prompt **for that one
turn only**. It is never written to history, never reappears on replay, and is counted by the
existing token-budget math.
This is the same "defined once, referenced by many" shape servers already have to personas
(`_serverBases` entries are referenced by a persona's `backendUrl`, never duplicated).
---

## 1. Research findings (what the code actually does today)

Line numbers current at `868c125` + the editor-layout fix.

### 1.1 The shared-list pattern (`_serverBases` / `_cloudApis`)

`AssistantService.kt:463-473` — each shared library is a `MutableStateFlow` plus a
read-only view:
```kotlin
internal val _serverBases = MutableStateFlow<List<ServerConfig>>(emptyList())
val serverBases = _serverBases.asStateFlow()
```
Mutators mutate the flow then persist (`AssistantService.kt:1384-1402`, `1654-1657`):
```kotlin
fun addPersona(persona: Persona) {
    _personas.value = _personas.value + persona
    saveSettings()
}
```
Load/save are centralised (`AssistantService.kt:1303-1319`, `1360-1378`): `saveSettings()`
writes every list back through `SettingsManager`. **A new `_skills` list should plug into
these two functions, not invent a parallel persistence path.**
Naming split: `internal val _x` is the mutable owner, `val x` is what UI/tests read. Tests
reach internals by binding the service and reading `_serverBases.value`.

### 1.2 Trigger-phrase detection (`isImageGenerationRequest`)

`AssistantServiceContext.kt:121-125` — the exact pattern to mirror:
```kotlin
internal fun AssistantService.isImageGenerationRequest(text: String): Boolean {
    val trimmed = text.trim()
    val triggers = listOf("create an image of", "generate an image of", "draw me", "make an image of")
    return triggers.any { trimmed.startsWith(it, ignoreCase = true) }
}
```
Trivial, pure, `startsWith` + `ignoreCase`, no I/O. Its sibling `extractImageGenerationPrompt`
(`:127-136`) reuses the **same literal list** to strip the prefix.

### 1.3 `TurnContext.systemPromptSuffix` — **directly reusable; the key finding**

`AssistantServiceChat.kt:1327-1334`:
```kotlin
internal data class TurnContext(
    val history: List<ChatMessage>? = null,
    val systemPromptSuffix: String = "",
    val sink: TurnSink? = null
) {
    val isSingleChat: Boolean get() = sink == null
    companion object { val Default = TurnContext() }
}
```
Its KDoc (`:1320-1326`) states its purpose verbatim: *"what ONE turn is allowed to see as
context… no extra system-prompt text is added"* by default, and a caller passes a suffix
*"for this turn only"*. That is **precisely** the Skills semantic. It was built for the Rooms
refactor; its only current user is `roomSystemPromptSuffix`
(`AssistantServiceRoomTurns.kt:466-482`, passed at `:301-305`).
**The budget requirement is already satisfied — but only because of a deliberate ordering
that must be preserved.** Direct-Ollama (`AssistantServiceChat.kt:1399-1418`):
```kotlin
// Phase 0: room preamble (if any) rides at the END of the persona's own
// system prompt ... and is appended BEFORE the budget math so resolveHistoryBudget
// accounts for it.
if (turnContext.systemPromptSuffix.isNotBlank()) {
    finalSystemPrompt = "$finalSystemPrompt\n\n${turnContext.systemPromptSuffix}"
}
...
val budget = resolveHistoryBudget(contextWindow, reservedOutput, finalSystemPrompt, modelText)
```
`buildCloudRequest` repeats the same order at `:1675-1692`. So injecting via the suffix gets
token-accounting for free: `resolveHistoryBudget` (`:2501-2515`) sizes history as
`contextWindow - reservedOutput - estimateTokens(systemPrompt) - estimateTokens(message)` and
**throws a user-facing refusal** when fixed parts alone overflow. A skill that would blow the
window surfaces as that existing error, not a silent truncation.
**Conclusion: do not add a second injection mechanism.** Compose skill text into the existing
`systemPromptSuffix`, so the "one turn only, budgeted, unpersisted" guarantees are inherited
rather than re-derived.

### 1.4 Where the send pipeline forks — and a pre-existing asymmetry

`sendTextMessageToServer` (`AssistantServiceChat.kt:656-701`) already takes a `turnContext`
and forwards it to **both** budgeted transports:
- cloud → `performCloudChat(..., turnContext = turnContext)` (`:699`)
- direct Ollama → `performDirectOllamaChat(..., turnContext = turnContext)` (`:726`)
⚠️ **But the voice path at `AssistantServiceChat.kt:343` calls `performDirectOllamaChat(...)`
with NO `turnContext`**, so it silently gets `TurnContext.Default` — a voice turn gets no room
preamble today either. Pre-existing and out of scope to change, but Skills inherits the same
blind spot (Open Question #6).
The non-budgeted paths are gateway/multipart (`:2482-2483`: *"the gateway/multipart paths
deliberately don't come through here: they hand the request to the server, which manages its
own context window"*). Skills inherits that too.
Two properties worth copying: it runs *before* the request is built
(`AssistantServiceChat.kt:679`), and it short-circuits into a different code path rather

### 1.5 Persistence conventions (`SettingsManager.kt`)

One encrypted-prefs JSON key per list, corruption-tolerant reads, versioned suffix:
| List | Key | Reader |
|---|---|---|
| Cloud APIs | `cloud_apis_v2` | `:195-229` (+ legacy `cloud_apis` migration) |
| Custom cloud | `custom_cloud_apis_v1` | `:231-249` |
| Personas | `personas_v2` | `:251-…` (+ legacy migration) |
| Rooms | `rooms_v1` | `:361-374` |
The canonical reader (`:365-374`) returns `emptyList()` when absent — the right default for a
brand-new library, so existing installs need no migration.

### 1.6 Settings UI structure

- Category registry: `SettingsScreens.kt:147-153`, a `List<SettingsCategory>` of
  `(title, icon)`; `SettingsCategory` is a private data class (`:156`).
- Dispatch: `when (category.title)` at `SettingsScreens.kt:356-383`.
- Each category body is a plain composable inside `SettingsCategoryScreen`'s `LazyColumn` —
  and, per the editor-layout fix just landed, those bodies must **not** be nested scrollables.
- `CloudSettings` (`:1296-1386`) is the closest analogue: a `Column` of
  `SettingsSectionHeader` + `SettingsSection` cards, each with up/down `IconButton`s and an
  inline `OutlinedTextField` editing straight through a service call
  (`service.updateCloudApi(index, api.copy(apiKey = it))`).

### 1.7 Persona-side enabling

`Persona` (`Models.kt:77-…`) already carries boolean feature flags exposed as switches —
`webSearchEnabled`, `ragEnabled` (`:103-104`) — and `PersonaEditor` commits the whole object
on a debounce plus dispose flush (`PersonaEditor.kt:300-350`). The editor body is a **plain
`Column`** (the fix just landed) with `verticalArrangement = spacedBy(16.dp)`.
---

## 2. Data model

### 2.1 `Skill` — new, in `Models.kt`

```kotlin
@Serializable
data class Skill(
    val id: String = java.util.UUID.randomUUID().toString(),
    val name: String,
    /** One or more. Matched case-insensitively against the incoming user message. */
    val triggerPhrases: List<String> = emptyList(),
    /** Appended to the system prompt for a single turn when a trigger fires. */
    val instructions: String = ""
)
```
**Why `id` (UUID) and not name-keyed:** `Room` uses a UUID with an explicit KDoc rationale
(`Models.kt:146-151`) — *"so a room can be renamed freely and so a deleted-and-recreated room
can never inherit the old transcript."* A skill is exactly as renameable, and a persona must
not silently re-bind to a different skill because someone renamed one. Contrast
`Room.memberNames`, which is name-keyed *only* because it mirrors the app's name-based persona
identity; there is no such pre-existing identity for skills.
**Why `List<String>` triggers:** the requirement is "one or more", and the existing image
trigger list (`AssistantServiceContext.kt:123`) is already a `List<String>`.

### 2.2 Persistence — `SettingsManager.kt`

```kotlin
fun saveSkills(skills: List<Skill>) {
    prefs.edit().putString("skills_v1", json.encodeToString(skills)).apply()
}
fun getSkills(): List<Skill>? {
    val data = prefs.getString("skills_v1", null)
    if (data != null) {
        return runCatching { json.decodeFromString<List<Skill>>(data) }
            .onFailure { e -> Log.w("SettingsManager", "Corrupted/unreadable JSON in skills_v1 …", e) }
            .getOrNull()
    }
    return emptyList()
}
```

### 2.3 Persona reference

```kotlin
// Persona, alongside webSearchEnabled / ragEnabled
val enabledSkillIds: List<String> = emptyList()
```
**Reference-by-id, not a copy of the instructions** — the whole point of the feature, and the
exact analogue of `Persona.backendUrl` → `_serverBases`. Storing text on the persona would
duplicate the library and need fan-out writes on every skill edit.
Default `emptyList()` keeps every existing persona valid with no migration — kotlinx
serialization supplies the default for absent keys.
than mutating the prompt. Skills want the former, not the latter.

### 2.4 Service surface

```kotlin
internal val _skills = MutableStateFlow<List<Skill>>(emptyList())
val skills = _skills.asStateFlow()
fun addSkill(skill: Skill) / updateSkill(index, skill) / removeSkill(skill) / moveSkillUp / moveSkillDown
```
Each mutates `_skills.value` then calls `saveSettings()` — identical to `addServerBase`
(`:1384`) and `addPersona` (`:1654`).
---

## 3. Matching + injection

### 3.1 Matcher — mirror `isImageGenerationRequest`

```kotlin
internal fun matchedSkills(text: String, skills: List<Skill>): List<Skill> {
    val trimmed = text.trim()
    if (trimmed.isEmpty()) return emptyList()
    return skills.filter { skill ->
        skill.instructions.isNotBlank() &&
            skill.triggerPhrases.any { t -> t.isNotBlank() && trimmed.startsWith(t, ignoreCase = true) }
    }
}
```
`startsWith` + `ignoreCase` matches the existing convention. Returning a **List, not a
Boolean**, is the deliberate divergence: it makes the multi-match question (#1) an explicit,
testable decision rather than an accident of `any { }`.

### 3.2 Injection — reuse `TurnContext.systemPromptSuffix`

Recommended: at the top of `sendTextMessageToServer` (`:656`), compute matched skills and fold
their text into the suffix of whatever `turnContext` was passed in:
```kotlin
val skillSuffix = matchedSkills(inputText, enabledSkillsFor(currentPersona))
             .joinToString("\n\n") { it.instructions }
val effectiveTurnContext = turnContext.withSuffix(turnContext.systemPromptSuffix, skillSuffix)
```
passed on to both `performCloudChat` and `performDirectOllamaChat` exactly as today.
**Why here:**
1. **The suffix is budgeted before use** — appended to `finalSystemPrompt` *before*
   `resolveHistoryBudget` (`:1402` / `:1678`). Satisfies "counted by the existing token-budget
   logic" with **no change to the budget code**.
2. **Inherently per-turn.** `TurnContext` is built per turn and never stored; it cannot leak
   into history, and past turns rebuild their own context.
3. **Never persisted.** `_messages` stores `ChatMessage(role, text, …)` only
   (`Models.kt:29-38`) — no field for a system prompt exists, so nothing can reach disk.
4. **One call site, both transports.** Doing it inside the two builders would duplicate logic
---

### 3.3 Ordering with the room preamble

Rooms and single-chat never share a `TurnContext` (a room turn sets its own `sink`), so no
collision needs special handling. Skill text should be concatenated into the same suffix slot,
after the persona's prompt, so the persona's prompt keeps governing tone while the skill adds
task detail. Per-skill ordering = library order; see #1.
---

## 4. UI

### 4.1 Skills library screen — new `"Skills"` category

- Add `SettingsCategory("Skills", Icons.Default.AutoAwesome)` to `SETTINGS_CATEGORIES`
  (`SettingsScreens.kt:147-153`).
- Add a `"Skills" -> SkillsSettings(service = svc, skills = skills)` arm to the dispatch
  `when` (`SettingsScreens.kt:356-383`).
- New `SkillsSettings` modelled on `CloudSettings` (`:1296-1386`): a plain `Column` of
  `SettingsSectionHeader` + `SettingsSection` cards (no nested scrollable — the page
  `LazyColumn` scrolls), each card showing name, triggers, an `OutlinedTextField` for
  instructions, up/down `IconButton`s and delete; plus a trailing "New skill" row like the
  personas/rooms lists.
- The dialog collects a `skills` value alongside `personas`/`rooms` (`SettingsScreens.kt:~289-307`).

### 4.2 Per-persona selection — inside `PersonaEditor`

A multi-select `FilterChip` row (or dropdown) over the shared library, with each persona's
`enabledSkillIds` checked, committing through the existing debounce + dispose-flush path. The
editor already rebuilds the whole `Persona` (`PersonaEditor.kt:300-333`), so this is one added
field with no new save plumbing.
Per the layout fix this goes into the existing plain `Column`; it must **not** introduce its
own `LazyColumn`/`verticalScroll`.
Key `skills_v1`, mirroring `rooms_v1`. Returns `emptyList()` when absent — **no migration
needed**; no legacy key exists and no install has skills.
Wired into `AssistantService` at the two existing seams only: load at the `:~1303` block,
save at the `:~1360` block.

## 5. Open design questions — decisions needed

**1. Multiple skills match at once (explicitly asked).** Options:
   (a) *Inject all matched instructions, library order* — simplest and most predictable; risk is
       contradictory instructions both landing in one system prompt.
   (b) *First match only* (library order) — deterministic and bounded, but silently ignores a
       legitimate second skill.
   (c) *All matches, with a size cap* — interacts with #4.
   I lean (a) plus a logged warning when >1 fires, since the common case is one trigger.
   **Needs your call.**
**2. Prefix or substring matching?** `isImageGenerationRequest` uses `startsWith` and I mirrored
   it. But "remind me to…" reads more naturally mid-sentence ("hey, remind me to buy milk").
   Substring is more forgiving and more surprising (accidental hits). Prefix keeps consistency.
   **Default: prefix.**
**3. Does the trigger stay in the user's message?** `extractImageGenerationPrompt` strips the
   trigger before use. Should the skill text also see the phrase, or should it be stripped?
   Stripping risks losing user intent; leaving it risks double-instruction.
   **Default: leave the message untouched** (skills are additive instructions, not commands).
**4. Guard-rail against prompt bloat.** `resolveHistoryBudget` will *refuse* the request when
   fixed parts overflow — correct, but poor UX if a 2 KB skill silently breaks every matching
   turn. Add a max-instructions size at save time, a warning when a persona's enabled skills
   exceed some fraction of `numCtx`, or neither? **Default: warn-only at save, no hard limit.**
**5. Skills and image-generation / translator personas.** `sendTextMessageToServer:679`
   short-circuits to `performImageGeneration` before any chat request, and translators build a
   fixed system message that ignores `persona.systemPrompt` entirely (`:1377-1379`). Do skills
   apply to those personas? **Default: no injection on the image short-circuit; flag the
   translator path as needing its own decision.**
**6. Voice turns are a known gap.** `AssistantServiceChat.kt:343` omits `turnContext`, so
   injected text is dropped there (this already affects room preambles). Fix that line so
   spoken triggers work, or stay out of it? **Default: fix it — otherwise a spoken
   "create an image of…" silently never gets its skill.**
**7. Does a skill fire in a Room?** Room turns set their own `systemPromptSuffix` (room
   preamble) and their own `sink`. If Skills computes its suffix in `sendTextMessageToServer`,
   room turns — which run through `runRoomTurn`, not that function — would get **no** skills.
   Inject into `runRoomTurn` too, or declare single-chat only? **Default: inject in both, so a
   persona behaves consistently.**
**8. Deleting a skill leaves dangling ids.** `removeSkill` must prune the id from every
   persona's `enabledSkillIds`, or the UI shows an enabled-but-missing skill. Prune eagerly,
   mirroring how `removePersona` keeps `Room.memberNames` in sync.
**9. Trigger collisions between skills.** Two skills may legitimately share a trigger.
   Harmless under #1(a), ambiguous under #1(b). Ties back to #1.
**10. Built-in seed skills?** An empty library is inert, so the feature is invisible until the
   user authors something. Ship examples, or leave empty? **Default: empty, with placeholder
   text in the empty state.**
---

## 6. Test plan (matching existing conventions)

- **Unit (JVM)**: `matchedSkills` truth table — case-insensitivity, prefix vs non-prefix, blank
  triggers/instructions ignored, multi-match ordering. Pins #1/#2.
- **Unit**: `Skill` JSON round-trip; `getSkills()` on an absent key returns `emptyList()`.
- **Instrumented**: budget integration — a persona with `numCtx` small enough that an enabled
  skill overflows, asserting the existing `resolveHistoryBudget` refusal fires. This is the
  real proof of "can't silently blow past the context window".
- **Instrumented**: suffix is per-turn — present on the matching turn, absent on the next, and
  absent from the persisted `_messages` transcript after a turn that fired it.
- **Instrumented**: `removeSkill` prunes `enabledSkillIds` across personas (covers #8).
- Reuse the bounded-scroll wrapper pattern from `RoomEditorUiPersistenceInstrumentedTest` if any
  editor is rendered standalone.
- Baseline to hold: **141 unit / 72 instrumented**, all green.
---

## 7. Files this would touch

| File | Change |
|---|---|
| `Models.kt` | `Skill` data class; `Persona.enabledSkillIds` |
| `SettingsManager.kt` | `saveSkills` / `getSkills` (`skills_v1`) |
| `AssistantService.kt` | `_skills` flow + accessors, mutators, load/save wiring |
| `AssistantServiceContext.kt` | `matchedSkills` |
| `AssistantServiceChat.kt` | suffix composition in `sendTextMessageToServer`; possibly `:343` |
| `AssistantServiceRoomTurns.kt` | skill suffix in `runRoomTurn` (if #7 = both) |
| `SettingsScreens.kt` | `"Skills"` category, `SkillsSettings`, collect `skills` |
| `PersonaEditor.kt` | multi-select for `enabledSkillIds` |
| `AssistantServiceRooms.kt` | prune ids in `removePersona` |
---

## 8. Status

**Research complete; no implementation code written.** Awaiting review, and specifically a
decision on **Question #1 (multi-match)** plus confirmation of the defaults for #2–#10 before
implementation begins.
   across direct-Ollama and cloud.
Placement relative to the image short-circuit (`:679`) matters: that branch `return@launch`s
before any request is built, so *"create an image of a cat"* would never reach injection.
Recommend computing the match **before** that branch only if image-generator personas are
meant to have skills at all — otherwise after is fine. See #5.
