# Persona selection as a single source of truth — plan (research + design)

**Status:** Q7 resolved; **step A built and verified** (service-side only, behaviour-preserving);
**step B not started**. See §9 for the status log.

**Why this exists.** The spontaneous-messages work (step 6) shipped a deep-link path that
switched the service's active persona but left the UI showing the previous one. The fix was
one line — `currentPersona = it` — and it was found only because a test happened to compose
the real screen. That is a symptom. This plan addresses the cause.

---

## 1. What I confirmed

### 1.1 Service side — the selection is a plain field, the list is a flow

| what | where | shape |
|---|---|---|
| the selection | `AssistantService.kt:329` | `internal var currentPersonaName: String? = null` — **plain field, not observable, no setter hook** |
| the persona list | `AssistantService.kt:493-494` | `private val _personas = MutableStateFlow<List<Persona>>(emptyList())` + `val personas = _personas.asStateFlow()` |
| the resolved persona | `AssistantService.kt:2536-2537` | `val currentPersona: Persona? get() = currentPersonaName?.let { name -> _personas.value.find { it.name == name } }` — a derived **getter**, not a flow |

So: the *list* is observable, the *messages* are observable, the *rooms* are observable — and
the **selection is not**. Everything the UI needs to know "which persona is active" is a
pull-only getter over a non-observable field.

**Writers of `currentPersonaName` in main (5, all service-side):**

| where | line | write |
|---|---|---|
| `loadSettings` cold start | `1330-1331` | `switchPersona(loadedPersonas[0])` when the name is null |
| `updatePersona` rename | `1738-1739` | `currentPersonaName = persona.name` when the renamed persona is active |
| `removePersona` active-and-not-last | `1961-1962` | detach (`null`) then `switchPersona(fallback)` |
| `removePersona` active-and-last | `1966` | `null` (the documented no-selection state) |
| `switchPersona` | `2509` | `currentPersonaName = persona.name` |

Plus **49 direct references across 15 instrumented test files** that set it to stage a
scenario. Any change here must keep those compiling.

**The same join is written twice:** `AssistantService.kt:2537` (`currentPersona`) and
`AssistantService.kt:2861` (VAD speech-end resolves the persona fresh:
`currentPersonaName?.let { name -> personas.value.find { it.name == name } } ?: personas.value.firstOrNull() ?: DEFAULT_PERSONAS[0]`).
Two copies already, with **different** fallback policies — the getter returns null, the VAD
path substitutes `firstOrNull()`.

### 1.2 UI side — `currentPersona` is a manually mirrored local `var`

Declared once, seeded imperatively:

```kotlin
// MainVoiceScreen.kt:446
var currentPersona by remember(service) { mutableStateOf(service?.currentPersona ?: DEFAULT_PERSONAS[0]) }
```

and then **assigned at 8 sites**:

| # | line | trigger | assigns | also writes the service? |
|---|---|---|---|---|
| 1 | `446` | initial seed | `service.currentPersona ?: DEFAULT_PERSONAS[0]` | no |
| 2 | `483` | notification tap (deep link) | result of `applySpontaneousDeepLink` | yes (inside that call) |
| 3 | `562` | `LaunchedEffect(personaList)` | re-resolved by `service.currentPersona?.name` | no |
| 4 | `564` | same effect, fallback | `personaList[0]` | yes (`switchPersona`) |
| 5 | `830` | translator button (exists) | the existing translator persona | yes |
| 6 | `834` | translator button (missing) | the `TRANSLATOR_PERSONA` constant | yes (`addPersona` + `switchPersona`) |
| 7 | `919` | language bar | `currentPersona.copy(targetLanguage = …)` — **a local object, not the stored one** | yes (`updatePersona`) |
| 8 | `1065` | persona picker | the tapped persona | yes |

Two of these (6 and 7) put an object into the mirror that is **not the instance held in
`personas`** — the mirror can therefore disagree with the service in ways beyond "wrong
persona".

`service.currentPersona` (the getter) is read only at `MainVoiceScreen.kt:545` and `:559`;
**no test reads it**, so it is free to change internally.

**No test composes `MainScreen` today** (verified across `app/src/test` and
`app/src/androidTest`). The mirror is currently exercised by nothing.

### 1.3 The diagnosis: confirmed, and the real reason `switchPersona` could forget

Confirmed on both counts:

- `currentPersona` **is** a manually-mirrored local variable. It is not derived from, or
  collected from, any observable. Every path that changes the active persona must *separately*
  remember to write it.
- `switchPersona` **does not** publish `_personas` (`AssistantService.kt:2459-2529` — it
  writes `currentPersonaName`, runs `stopEverything()`, and swaps `_messages`). It does not
  need to: the *list* did not change.

The sharper framing, and the reason this is a class of bug rather than one bug:

> Nothing owns the selection. The service publishes the list and the messages; the selection
> is a plain field with a pull-only getter, and "make the UI follow" is therefore a **UI-side
> obligation discharged by hand**. `switchPersona` mutating name + messages is complete for
> every consumer *except* a UI mirror nobody told it about — and the picker only worked
> because its `onPersonaSelected` lambda happened to set both. The deep link set neither, so
> the divergence was silent and only a full-screen composition could have seen it.

**The codebase already solved this exact problem once — for rooms.** `activeRoomId` is a
`MutableStateFlow` (`:524-525`) with a derived `activeRoom` (`:527`), written only from the
service (`AssistantServiceRooms.kt:177, 208, 234`), and the UI has **exactly one collector and
zero imperative assignments** (`MainVoiceScreen.kt:379` declaration, `:434` collector). Persona
selection is the outlier, not the norm. This plan is "do for personas what rooms already do".

---

## 2. Goal and non-goals

**Goal.** Make "which persona is active" a single value owned by the service and **derived**
by every consumer, so that any current or future path that changes the selection — the picker,
a deep link, a notification, a restore, something added next year — is reflected in the UI with
nothing to remember and no way to forget.

**Non-goals** (deliberately out of scope, so the change stays verifiable):

- Changing *when* a switch happens, what it interrupts, or the `stopEverything()` semantics.
- Making the room selection, the history-owner bookkeeping (`messagesOwnerName`) or the
  mid-swap transient observable. They are separate state with separate rules.
- Any UI redesign. The header, HUD, picker and translator bar keep their current behaviour
  and their existing parameters.

---

## 3. Design

### 3.1 Service: give the selection an owner (recommended shape)

Three additions, all additive:

**(a) Make the name observable behind the existing field.** Keep the name and the type so all
49 test references and every service-internal read keep working unchanged; back it with a
`MutableStateFlow`:

```
private val _currentPersonaName = MutableStateFlow<String?>(null)
internal var currentPersonaName: String?      // get() = _currentPersonaName.value
    set(value) { _currentPersonaName.value = value }   // every existing write now publishes
```

This is the key move: **every existing write site becomes a publish without being touched**,
which is what makes the change additive rather than a rewrite of the 5 writers.

**(b) Extract the join as a pure function** (so it is JVM-testable and exists once):

```
internal fun resolveCurrentPersona(name: String?, personas: List<Persona>): Persona?
```

covering: null name -> null; name not in list -> null; empty list -> null; otherwise the
instance from the list. `AssistantService.currentPersona` (`:2536`) becomes a one-line delegate
to it, and the VAD path (`:2861`) stops re-implementing it.

**(c) Expose the derived selection as a flow** for the UI and for tests:

```
val currentPersonaFlow: StateFlow<Persona?> =
    combine(_currentPersonaName, _personas) { name, list -> resolveCurrentPersona(name, list) }
        .distinctUntilChanged()
        .stateIn(serviceScope, SharingStarted.Eagerly, currentPersona)
```

Notes on this shape, all deliberate:

- **`Eagerly`** (not `WhileSubscribed`): the value must be current whether or not anyone is
  collecting, because tests read `.value` and because a late subscriber (the UI after rotation)
  must not see a stale or null value. Cost: one always-running collector on the existing
  `serviceScope` — negligible next to the health-check loop already there.
- **`distinctUntilChanged`** on the resolved `Persona`: suppresses re-emissions when an
  unrelated persona is edited, and when the resolved persona is unchanged.
- **Declaration order**: must sit after `serviceScope` (`:133`) and `_personas` (`:493`) in the
  class body, since a property initialiser runs at construction.
- **The synchronous getter stays.** Service-internal logic (`sendTextToServer`, VAD, the `:2537`
  getter) keeps reading the current value with no dispatch delay; the flow is for observation.
- **Keep it nullable.** "No persona selected" is a real state the code already models
  (`removePersona`'s last-persona branch) and the UI already has a fallback for it.

### 3.2 UI: one `val`, derived — nothing to assign

```
// MainVoiceScreen.kt — replaces the `var` at :446
val currentPersona = service?.currentPersonaFlow?.collectAsState()?.value
    ?: DEFAULT_PERSONAS[0]        // service not yet bound, or no selection: today's seed
```

and **all 8 assignment sites are deleted**. Because it is a `val` derived from the service,
`currentPersona = ...` anywhere in the file becomes a **compile error** — the invariant is
enforced by the compiler, not by a comment or a test.

The fallback mirrors today's behaviour exactly: `service?.currentPersona ?: DEFAULT_PERSONAS[0]`
at `:446` and, in practice, `MainScreen` returns early while `service == null`
(`MainVoiceScreen.kt:788-791`), so the fallback only satisfies the type.


### 3.3 Disposition of every existing site

**UI assignments (all deleted; the *actions* stay):**

| # | line | what happens to it |
|---|---|---|
| 1 | `446` | replaced by the derived `val` |
| 2 | `483` | assignment deleted. `applySpontaneousDeepLink` no longer needs to return the persona for mirroring (Q3) — it already calls `switchPersona`, which publishes. |
| 3 | `562` | **effect deleted.** Its only job was keeping the mirror in sync after a rename; the rename already publishes the new name (`:1738-1739`), so the derived value follows on its own. |
| 4 | `564` | the mirror half deleted; the *fallback switch* is reconciliation, not mirroring — Q2 |
| 5 | `830` | assignment deleted; `switchPersona(t)` stays |
| 6 | `834` | assignment deleted; `addPersona` + `switchPersona` stay (the value appears once it is in the list — `addPersona` is synchronous on `_personas`) |
| 7 | `919` | assignment deleted; `updatePersona(idx, copy(targetLanguage = lang))` stays and, by republishing the list, makes the derived value show the new language. This also removes one of the two places where the UI showed a **non-stored** persona object (the other is row 6). |
| 8 | `1065` | assignment deleted; `switchPersona(it)` and the translator `clearMessages()` stay |

**Service writers (code untouched, new behaviour):** all five — `1330`, `1738`, `1961/1962`,
`1966`, `2509` — publish automatically once 3.1(a) lands.

**Other readers of `service.currentPersona`:** `:545` becomes the reconciliation predicate
(Q2); `:559` disappears with effect #3. No test reads the getter, so it can be re-implemented
behind the scenes.

**`switchPersona` callers (all keep their call, lose their mirroring):** `MainVoiceScreen:546`
(reconciliation), `:565` (fallback), `:831`/`:836` (translator), `:1067` (picker),
`AssistantServiceSpontaneous.kt:344` (deep link), plus `AssistantService:1331` (cold-start
seed) and `:1962` (delete-active fallback) — the last two are service-internal and unchanged.

### 3.4 Performance / recomposition — the honest analysis

`currentPersona` is read in a lot of places (header, HUD, chat body, message-id keys,
suggestion keys, background image, translator bar), so it is worth being precise:

- **`_personas` is written on every editor keystroke.** `PersonaEditor.kt:335` keys a
  `LaunchedEffect` on ~28 fields and calls `onSave` -> `updatePersona` -> `_personas.value = ...`.
- **Today that already recomposes the screen when the *active* persona is edited**: the
  `LaunchedEffect(personaList)` at `:551` assigns `currentPersona = updated`, a new instance,
  which is a state change. Editing a *different* persona is already free, because `find`
  returns an equal value and `mutableStateOf` compares structurally.
- **After the change it is no worse, and slightly better:** `collectAsState` stores the resolved
  `Persona` in a `mutableStateOf`, so equal values do not recompose, and `distinctUntilChanged`
  drops emissions where the active persona is unchanged. What goes away is the per-keystroke
  `find` + reassign effect at `:551`.
- **The one genuine new cost** is a single always-on `combine` collector (`Eagerly`, Q4).
- **Follow-up worth considering, not required:** project the derived state down to what the
  chrome actually needs (name, colours, icon, `isCloud`, `isTranslator`, `isImageGenerator`)
  instead of the whole `Persona`, so editing the *active* persona's prompt stops recomposing the
  screen. Recommended *later*, if measurement shows it matters — it splits "what to display"
  from "what to send", which is a bigger design question than this plan.

---

## 4. Why the service layer becomes sufficient to prove the UI

This is the part that closes the gap the deleted composition test left, so it is worth stating
as an argument rather than an assertion:

1. After the change, `currentPersona` in `MainScreen` is a **`val` assigned exactly once** from
   `collectAsState()` on `service.currentPersonaFlow`. No branch, no cache, no second writer,
   no other source. **Any attempt to write it is a compile error.**
2. Therefore "the screen shows persona X" reduces to "`currentPersonaFlow.value` resolves to X".
   The step from a `StateFlow` to a recomposition is Compose's contract, not application logic.
3. So a deterministic, Compose-free test of the flow **is** a proof of what the UI displays —
   and it is exactly the proof that was missing when `switchPersona` and the deep link could
   silently diverge from a local variable.

Stated plainly, what the argument does **not** cover: that `MainScreen` passes the derived value
to `Header`/the body (visible in code, unchanged by this work), and how the null fallback looks.
Those stay untested, and no flaky composition test is reintroduced to chase them.


---

## 5. Test plan

**JVM (new, pure):** `resolveCurrentPersona` — null name; name not in list; empty list; the
stored instance returned (identity, not a copy); a duplicated name returns the first. This is
the whole of the join logic and it needs no Android.

**Instrumented, no Compose (new) — `PersonaSelectionInstrumentedTest`:** assert
`service.currentPersonaFlow` after each real path, via a small `awaitFlowValue` helper:

| scenario | expected flow value |
|---|---|
| `switchPersona(b)` | `b` |
| rename the active persona via `updatePersona` | the renamed persona (name followed, selection kept) |
| delete the active persona | the fallback `removePersona` picks |
| delete the last persona | `null` (the documented no-selection state) |
| `applySpontaneousDeepLink(name)` | that persona — **the regression the spontaneous work found** |
| `applySpontaneousDeepLink(ghost)` | unchanged (tap consumed, nothing moves) |
| `addPersona` (not selected) | unchanged |
| mid-swap: switch, before the history IO settles | the flow names the incoming persona while `messagesOwnerName` is still the outgoing one (documents the pre-existing transient, does not fix it) |

Plus a **control** test: the flow is `null` before any selection, so the assertions above cannot
pass by default-equality.

**Deliberately not reintroduced:** a full-`MainScreen` composition test. It was written, it
found the bug, it was mutation-verified standalone, and it could not run reliably inside the
suite; the argument in §4 is what replaces it.

**Regression guard for the class, not the instance:** the `val` derivation is compile-enforced,
so "someone forgets to mirror" is unrepresentable. The remaining hole a *test* could close —
a future author adding a new `var` mirror — is a review/lint concern, not a unit test; a short
KDoc note on `MainScreen`'s derivation is the proportionate mitigation.

**Blast radius to re-run green:** the 15 instrumented files that set `currentPersonaName`,
`PersonaSwitchStopsPlaybackInstrumentedTest`, `CloudVoiceHistoryInstrumentedTest`,
`SpontaneousDeepLinkInstrumentedTest`, `SpontaneousWriteGuardInstrumentedTest`,
`SkillInjectionInstrumentedTest`, the editor UI tests, plus the full JVM suite. `switchPersona`'s
history semantics are the thing most likely to be disturbed by a careless edit here — not the flow.


---

## 6. Open design questions

**Q1 — What does the service expose: the resolved `Persona?` (recommended) or only the name?**
**Option A (recommended):** `currentPersonaFlow: StateFlow<Persona?>`, as in §3.1(c). The join
exists once, the two existing copies (`:2537`, `:2861`) collapse into it, and a test asserts
exactly the value the UI displays.
**Option B (lower-risk alternative):** expose only `currentPersonaNameFlow: StateFlow<String?>`
and let the UI join it against the `personaList` it already collects
(`personaList.find { it.name == name }`), which is literally how rooms work today
(`activeRoom = rooms.find { it.id == activeRoomId }`, `:807`). Smaller service diff, but the join
and its fallback policy stay written out in the UI — and they already disagree between the two
service copies (null vs `firstOrNull()`), so a third copy would make that worse.
**Recommendation: A.** One owner is the point; B preserves the duplication that caused the drift.

**Q2 — Where does "the selection no longer resolves" get reconciled?**
Today the UI does it (`LaunchedEffect(personaList)` -> `switchPersona(personaList[0])`).
(i) Keep a **write-only** effect in `MainScreen`: a few lines, no mirroring, fires only when the
resolved value is null and the list is non-empty. (ii) Make it a **service invariant** (re-point
on every `_personas` write when the name does not resolve): cleaner in principle, but it would
call `switchPersona` — with its history side effects — from list-write paths including every
editor keystroke, and risks re-entrancy with `updatePersona`. (iii) Delete it and let the derived
value stay null: the screen would then need a display-only fallback and the service and the
screen could disagree about who is active — reject.
**Recommendation: (i)**, with (ii) noted as a possible follow-up if non-UI consumers ever need
the invariant too.

**Q3 — What should `applySpontaneousDeepLink` return?**
Today `Persona?`, purely so the UI could mirror it; nothing needs that after the change. Keep it,
narrow it to `Boolean`, or make it `Unit`?
**Recommendation: keep `Persona?`.** It costs nothing, and
`SpontaneousDeepLinkInstrumentedTest` asserts the return today, so changing it means touching a
verified file for cosmetics.

**Q4 — Is the always-on collector acceptable, and `Eagerly` vs `WhileSubscribed`?**
`Eagerly` costs one coroutine on the existing `serviceScope` for the service's lifetime.
`WhileSubscribed` saves that but makes `.value` stale between subscribers — which breaks the
"tests read `.value`" property and risks a stale first frame after a rotation.
**Recommendation: `Eagerly`.** Flagged because it is a deliberate, permanent cost.

**Q5 — Should `switchPersona` early-return when the persona is already active?**
Not a new issue, but this change makes it more visible: `applySpontaneousDeepLink` always calls
`switchPersona`, so re-selecting the active persona still bumps generations, saves its history and
re-loads it. The picker's behaviour arguably *wants* the `stopEverything()` even on a re-tap.
**Recommendation: leave as-is here** and note it — changing it alters switch semantics for every
caller, which is a separate decision.

**Q6 — Should `messagesOwnerName` become observable too?**
No. The UI observes `messages`, and the mid-swap owner is deliberately service-internal: its
divergence from the name *is* the mechanism that protects history writes. The transient where the
header names the incoming persona while the outgoing messages are still on screen is
**pre-existing** (the picker already moves the mirror immediately) and is neither fixed nor
worsened here. Explicitly out of scope.

**Q7 — Does anything depend on the mirror holding a *non-stored* object? — RESOLVED, no.**
Traced both sites concretely rather than assuming.

*Site 6, `:834` — the translator button's `currentPersona = TRANSLATOR_PERSONA`:*
`TRANSLATOR_PERSONA` (`Models.kt:671-680`) is `name = "Translator"`, `isTranslator = true`, and
does **not** set `spontaneousEnabled`, so it is `false` and `SpontaneousPolicy.sanitize`
(`Models.kt:542-551`) takes its `else persona` branch — returning the **identical instance**.
The lambda is:
```
currentPersona = TRANSLATOR_PERSONA       // (A) assignment
service?.addPersona(TRANSLATOR_PERSONA)   // (B) append, synchronous
service?.switchPersona(TRANSLATOR_PERSONA)// (C) currentPersonaName = "Translator", synchronous
```
So at the instant of (A) it is **not yet** in `_personas.value`, but (B) and (C) both complete
**inside the same synchronous lambda**, before any recomposition: by the time anything renders,
the list contains a persona named `"Translator"` and the selection name matches it. **The object
is genuinely present by the time it can be observed**, just one statement later than the
assignment — which is exactly why the derived version is equivalent: it simply waits for (B) and
(C) to publish, in the same frame. No default persona carries the name `"Translator"`.

*Site 7, `:919` — the language bar's `currentPersona.copy(targetLanguage = lang)`:*
```
if (idx != -1) service?.updatePersona(idx, currentPersona.copy(targetLanguage = lang))  // stores
currentPersona = currentPersona.copy(targetLanguage = lang)                             // a SECOND copy
```
`updatePersona` publishes `_personas.value = current` **synchronously**, so an equal object with
the new language is already stored before the local assignment runs. The local assignment is
therefore **redundant** — it exists only to save one frame. `LanguageBar`'s `currentLanguage` is
a plain `String` parameter (`PersonaEditor.kt:1134`) with no state of its own, so nothing is lost.
The `idx == -1` branch is the one behavioural difference: today the mirror updated while nothing
was stored (a silent UI/service divergence); derived, the screen keeps the stored language —
which is the truth — and the Q2 reconciliation re-points the selection.

**Conclusion: neither site is a synthetic object that would fail a lookup, and neither needs
special handling.** Both are followed, synchronously and in the same frame, by a service write
that puts an equal persona in `_personas.value`. Two incidental improvements fall out: the UI can
no longer display a language the service never saved, and the UI passes the *stored* instance into
`sendTextToServer` / `stopRecording` / `replayMessageAudio` instead of a local copy.
One unrelated observation, not addressed here: `addPersona` has **no name-uniqueness check**
(server configs do reject duplicate URLs — `:1458`, `:1537`, `:1576`), so a duplicate name
currently lets the UI's mirror and the service's getter disagree; derived, both agree on the
first match.


---

## 7. Sequencing and risk

**Recommended: three steps, each independently verifiable, each landing green.**

**Step A — service only, purely additive (no UI change, no behaviour change).** Add
`_currentPersonaName` + the `var` facade, `resolveCurrentPersona`, `currentPersonaFlow`, and
delegate `:2537`/`:2861` to the resolver. Add the JVM resolver tests and the instrumented flow
tests. Verify with the full JVM suite, the full device suite and `assembleDebug`, with the UI
untouched — so any failure is unambiguously the flow's, and nothing can be blamed on the UI.
*Why first:* it is the entire proof surface, and it reverts alone.

**Step B — the UI derives instead of mirroring.** Delete the 8 assignments, make `currentPersona`
a `val`, reduce `LaunchedEffect(personaList)` to the reconciliation write (Q2). This is the step
that changes observable behaviour, verified by the existing persona-switch and cloud-history
device tests plus a manual pass over the four switch paths (picker, translator, deep link,
language change).

*Why separate:* if something regresses, the candidates are this step or step A — two, not nine.

**Step C — cleanup, only if it earns its place.** Dedupe the remaining `currentPersona` readers,
revisit the header/body parameter plumbing, and consider the projection in §3.4. Not needed for
correctness; explicitly deferrable.

**Why not one commit.** Persona selection is read across the whole voice screen and written by 8
UI sites. A single commit would make a regression indistinguishable between "the flow is wrong"
and "a call site lost its write". Step A alone is a no-behaviour-change commit that could ship on
its own if B needed more thought.

**Rollback.** Step A is additive (nothing reads the flow yet) and reverts to identical behaviour.
Step B is mechanical (8 deletions plus one `val`) and reverts to the current mirror. Neither
touches persistence, so there is no data migration and no backup-format impact.

---

## 8. What changes, at a glance

| | today | after |
|---|---|---|
| who owns the selection | nobody — a plain field plus a mirror per call site | the service (`_currentPersonaName` -> `currentPersonaFlow`) |
| how the UI reads it | `var` seeded once, assigned at 8 sites | one `val` via `collectAsState` |
| forgetting to update the UI | silent; unobservable without composing the screen | impossible — compile error |
| forgetting to publish | silent (the deep-link bug) | caught by a Compose-free service test |
| rotation | re-seeds `remember(service)` from the getter | re-derives from the flow |
| what the mirror can hold | sometimes a non-stored object (`:834`, `:919`) | always the stored instance |
| rooms | already done this way | unchanged — it is the model being copied |

**Stopping here for review — no implementation code written.**

---

## 9. Status log

### Step A — DONE and verified (no UI change)

**Delivered**

- `Models.kt` — `internal fun resolveCurrentPersona(name: String?, personas: List<Persona>): Persona?`,
  the one join, with the consequences of name-based/first-match resolution documented.
- `AssistantService.kt`:
  - `private val _currentPersonaName = MutableStateFlow<String?>(null)`, with `currentPersonaName`
    kept as a `var` of the same type/name backed by it — so **every existing writer publishes
    without being touched**, and all 49 test references compile unchanged;
  - `val currentPersonaFlow: StateFlow<Persona?> = combine(_currentPersonaName, _personas) { … }
    .distinctUntilChanged().stateIn(serviceScope, SharingStarted.Eagerly, null)`;
  - `currentPersona` and the VAD speech-end path (`:2911`) delegate to `resolveCurrentPersona`,
    so the join exists once instead of twice.
- New tests: `PersonaSelectionResolutionTest` (JVM, 7 cases) and
  `PersonaSelectionInstrumentedTest` (10 cases, **no Compose**).

**Verified — additive and behaviour-preserving, nothing before the UI layer moved**

| check | result |
|---|---|
| JVM suite | **305 tests** (298 + 7 new), 0 failures |
| device suite | **131 tests** (121 + 10 new), 0 failures, 0 errors, 0 skipped |
| the 49 references across 15 files | compile and pass **unchanged** — no test edited |
| `assembleDebug` | builds |
| UI untouched | `MainVoiceScreen.kt` has **no** `currentPersonaFlow` consumer; the `var` mirror at `:446` is still intact (that is step B) |

**Mutation-verified, so the new tests are not decoration**

| mutation | result |
|---|---|
| `currentPersonaName`'s setter writes to a detached field (writes no longer publish) | **9 of 10 fail** — only `deleting the last persona leaves the flow with no selection` survives, correctly, because it asserts null |
| the flow watches only the name (`map` on `_currentPersonaName` instead of the `combine`) | **exactly 1 fails** — `an edit to the active persona is reflected in the flow`; the 9 name-driven ones stay green |

**Two notes from building it.** A test method name containing a comma is rejected by D8
(`cannot be represented in dex format`) — the same constraint the spontaneous tests hit, fixed by
renaming two tests. And one first-draft assertion was wrong rather than the code: `awaitSelection`
waits on the persona *name*, which an edit does not change, so it returned before the emission
arrived; the edit test now waits on the content it actually asserts.

**Behaviour change in step A: none.** `currentPersonaName` has the same type, name and
read/write semantics; the only additions are a derived flow nothing consumes yet and the
delegated join. Step B (the UI deriving instead of mirroring) has not been started.

### Step B — DONE and verified (the UI derives; no local mirror remains)

**Delivered** — `MainVoiceScreen.kt` only, plus three new tests.

- `currentPersona` is now a `val`, derived:
  `personaSource = service?.currentPersonaFlow ?: emptyFlow()` →
  `collectAsState(initial = null).value ?: DEFAULT_PERSONAS[0]`. The fallback is display-only
  and covers the two states that never render (no binder yet, no selection).
- **All eight assignment sites deleted**, per §3.3: the `remember(service)` seed; the deep
  link's `?.let { currentPersona = it }`; both halves of `LaunchedEffect(personaList)`
  (deleted outright); the translator button's two; the language bar's local `copy(...)`; the
  picker's.
- The old `LaunchedEffect(service)` tail that seeded the service from the UI mirror is gone;
  its job is now the **reconciliation effect**, which is the single write left and writes
  nothing local: `if (personaList.isEmpty()) return; if (bound.currentPersona != null) return;
  bound.switchPersona(personaList.first())`. It also fixes a latent bug — the old seed used
  `DEFAULT_PERSONAS[0]`, which could name a persona that is not in the list.
- `personaColor` and everything else derived from `currentPersona` is unchanged.

**Per-call-site verification — each one specifically, not "the app still runs"**

| # | site | how it is verified |
|---|---|---|
| 1 | seed (was `remember(service)`) | **on-device**: forced a config change (`wm density` and back) → Activity recreated → header still `Translator`, language bar still `Target: French`. The derived value re-resolves from the service, which is what the old seed existed for. |
| 2 | deep link | **on-device end-to-end**: `am start --es spontaneousTargetPersona Friend` on the running app → header `Assistant` → **`Friend`**; the "intent delivered to running instance" warning independently confirms `singleTop`/`onNewIntent`. Second tap moved it to another persona; a tap for a nonexistent name moved nothing. Service layer: `a deep link moves the flow`, `a deep link for a missing persona moves nothing`. |
| 3 | `LaunchedEffect(personaList)` mirror | **deleted**; its only job was following a rename, and `the flow follows a rename of the active persona` pins that the service publishes the new name. |
| 4 | fallback `personaList[0]` | replaced by the reconciliation effect; its predicate state is pinned by `a selection naming a persona that is not in the list does not resolve`. |
| 5 | translator button, existing translator | **on-device**: header menu → Translator → header stayed `Translator` and the bar kept `Target: French`. |
| 6 | translator button, missing translator | `the translator add-and-select sequence resolves to a real persona` — the Q7 case, pinned: the added persona is stored and resolution returns **the stored instance**, not the constant. Not exercised destructively on the device (it would delete the user's translators). |
| 7 | language bar | **on-device end-to-end**: `Target: Greek` → tapped the bar → `French` → bar shows `Target: French`, and it survived a later re-selection and a recreation (so the value really reached the service, not a local copy). Service layer: `the language bar call reaches the flow with no local copy`. |
| 8 | picker | **on-device end-to-end**: header `Assistant (Voice)` → tapped → picker → `Eva` → header **`Eva`**. Service layer: `the flow follows a switch`. |

**The invariant is now compiler-enforced, and that was checked rather than assumed.** The
mutation that reproduces the old bug — adding `currentPersona = DEFAULT_PERSONAS[0]` back
next to the declaration — fails to compile: `'val' cannot be reassigned`. So "forgetting to
update the screen" is not a reachable state, which is the whole reason the flaky composition
test is not needed.

**Suites** (all on `--rerun-tasks`, after the mutation was reverted)

| check | result |
|---|---|
| JVM | **305 tests**, 0 failures |
| device | **134 tests** (131 + 3 new), 0 failures, 0 errors, 0 skipped |
| `assembleDebug` | builds |
| app on device | cold-launched, service up, **zero** `FATAL EXCEPTION` across every interaction above |

**Two things this step does NOT cover, stated rather than implied.** The reconciliation
effect's *action* (switch to the first persona) is not covered by an automated test — its
predicate state is, and the effect is three lines. And `MainScreen` still has no composition
test, so "the header is passed the derived value" rests on the code being one expression
rather than on a test; the on-device dumps above are the evidence that it renders and follows
a switch.

**Also observed on the device, unrelated to this change:** the persona picker lists
**two** entries named `Translator`, which is the duplicate-name case Q7 flagged — `addPersona`
has no uniqueness check. Worth a separate look; it is not caused by, and not fixed by, this
work.


