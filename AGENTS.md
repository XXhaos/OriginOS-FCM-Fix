# AGENTS.md — OriginOS FCM Guardian

## Latest workflow override — no speculative failure gate (2026-10-05)

The user confirms there is currently no reported missed-message incident with automatic protection
actually operating. Continue from the accepted working baseline. The earlier Telegram incident was
protection OFF with stopped=true, followed by successful recovery; do not reuse it as evidence of
failure under enabled protection. Daily feedback is follow-up, not a prerequisite for independent
development or delivery. Reopen an accepted behavior only for a concrete report, observed failure or
relevant code change. Lack of a reported fault means no known fault; it is not universal efficacy proof.
Keep unimplemented GMS repair/reconnect/boot privilege capabilities separate from current defects.
When a capability lacks a supported implementation path, record it as deferred and advance another
ready item; do not require the user to produce a failure or repeat accepted tests to continue.

## Latest workflow override — local-only completion (2026-10-05)

The user explicitly ended cross-computer development. From this decision onward, do not write or
update handoff records, including `docs/CODEX_HANDOFF.md` and `docs/CROSS_COMPUTER_HANDOFF.md`,
or prepare migration packages. Keep existing handoff files as historical records; do not delete them.
This supersedes all mandatory handoff-reading/writing and cross-computer steps below. Resume from
`docs/PROJECT_STATE.md` and `docs/ACCELERATED_PLAN.md`; maintain only concise current status, necessary
task/defect and test/device evidence. Preserve local changes and private files; local-only is not
permission to delete backups, skip tests, commit or push. Avoid repetitive status-document copies.

## 1. Purpose

This repository contains **OriginOS FCM Guardian**, an Android project for vivo / iQOO China-ROM devices, with OriginOS 7 as the primary target.

The project aims to improve and diagnose FCM reliability without Root, using Shizuku / shell privileges where necessary, while minimizing battery impact and preserving full rollback capability.

This file defines **how Codex must work in this repository**, including long-term project memory, multi-agent orchestration, dynamic model routing, device safety, testing, and handoff rules.

Do **not** duplicate the full product design here. The canonical product and technical plan is:

`OriginOS_FCM_Guardian_Development_Spec.md`

When instructions appear to conflict:

1. safety rules in this `AGENTS.md` win;
2. otherwise, the latest verified real-device behavior wins;
3. update the development spec when verified device behavior invalidates an old assumption;
4. never silently invent a new architecture or device behavior.

---

# 2. Parent-agent role: dynamically selected Orchestrator

The primary Codex thread is the **project Orchestrator**.

The project default/recommendation is **GPT-6.1 Sol / high** (user decision, 2026-09-30). The parent agent is always the model + reasoning effort that the user currently selected for the main Codex thread; that explicit choice overrides the recommendation. Updating this file does not change a live thread or pin the main model in `.codex/config.toml`.

Examples:

```text
main thread = GPT-6.1 Sol / high
→ GPT-6.1 Sol / high is the Orchestrator for that turn/session state

main thread switched to GPT-6 Astra / medium
→ GPT-6 Astra / medium becomes the Orchestrator for subsequent work
```

Do not rewrite `.codex/config.toml` to pin the primary model. Do not assume that the parent is GPT-6. Respect the user's current main-thread model selection.

Routine orchestration uses the GPT-6.1 Sol / high recommendation. The six triggers in §5.1 route the relevant analysis/review to **GPT-6 Astra**, without silently changing the user-selected parent. Reuse the resulting decision/evidence when returning routine implementation to Sol.

The parent agent is primarily responsible for:

```text
understanding the current project state
architecture and high-risk decisions
task decomposition
selecting subagent role
selecting subagent model
selecting reasoning effort
controlling concurrency
reviewing subagent output
real-device safety approval
phase acceptance
final integration and verification
persisting project state
```

The parent agent should **not** perform every routine task itself when a cheaper model can safely complete the work.

The goal is not to maximize the number of agents. The goal is to reserve expensive reasoning for work that actually needs it.

Changing the main-thread model between user turns is allowed and expected. Project continuity comes from repository state files (`PROJECT_STATE.md`, `CODEX_HANDOFF.md`, `DEVICE_FINDINGS.md`, etc.), not from assuming one model remains parent forever.

---

# 3. Multi-agent orchestration model

Use **stable specialist roles + dynamic per-delegation model routing**.

The project must keep these concepts separate:

```text
role      = what kind of specialist is needed
model     = how much capability/cost this specific delegated task needs
reasoning = how much deliberation this specific delegated task needs
```

A role is the specialist identity. A model is the execution engine for one delegated task.

Therefore:

```text
explorer != Terra
worker   != Sol
utility  != Luna
reviewer != Sol
```

Those are only common starting pairings. Do not permanently bind a role to a model unless a future verified reason requires it.

## 3.1 Dynamic parent rule

The primary Codex thread is always the **Orchestrator**.

Its model and reasoning effort are whatever the user currently selected for the main thread. GPT-6.1 Sol / high is the recommended default, not a forced setting. The Astra escalation rules below apply to delegated analysis/review even when a different parent remains selected.

Examples:

```text
main thread = GPT-6.1 Sol / high
→ that model is the current Orchestrator

user later switches main thread to GPT-6 Astra / medium
→ GPT-6 Astra / medium becomes the Orchestrator for subsequent work
```

Project continuity comes from repository state files, not from keeping the same parent model forever.

## 3.2 When child-model selection happens

Child-model selection happens **at each delegation boundary during execution**, not once at project initialization or once for the whole user turn. A single user request may therefore trigger multiple independent routing decisions as investigation, implementation, review, and cleanup tasks appear.

An already-running child does not hot-swap models. If capability is insufficient, it stops with a bounded handoff and the parent re-routes the work.

## 3.3 Routing order: role first, model second, reasoning third

For every meaningful delegation decide in this order:

```text
task type → specialist role
difficulty / risk / ambiguity → model
required deliberation → reasoning effort
```

Do not choose a model first and force the task into a mismatched role.

## 3.4 The fallback is insurance, not normal routing

`.codex/config.toml` defines a default subagent model and reasoning effort only as a safety fallback.

For meaningful delegated work, the parent should explicitly choose model + reasoning at spawn time.

Do not intentionally rely on the fallback for normal routing.

---

# 4. Stable specialist roles

Use Codex built-in roles where possible. The role defines professional scope and behavioral constraints; changing the model must not erase those constraints.

## 4.1 `explorer` — investigation specialist

Use for:

```text
reading large parts of the repository
searching call relationships
studying reference projects
reviewing long ADB / logcat output
finding relevant Android APIs
locating implementation points
collecting and compressing context for the parent
comparing files without editing them
```

Expected behavior:

- primarily read-only;
- return concise findings instead of huge dumps;
- cite exact files, symbols, commands, or line ranges when useful;
- separate verified facts from hypotheses;
- stop and escalate rather than turning an investigation into an unapproved implementation.

Common starting model: **GPT-5.6 Terra / medium**.

For unusually difficult Android framework or OriginOS reverse-engineering analysis, the parent may keep the **explorer role** and use a stronger model/reasoning level.

## 4.2 `worker` — implementation specialist

Use for:

```text
Android feature implementation
cross-file changes
Shizuku integration
AppOps / Doze detectors and repair code
Room / WorkManager / Firebase implementation
Compose screens
refactors
tests
bug fixes
```

Expected behavior:

- work within a clearly defined scope;
- do not silently redesign architecture;
- do not independently approve a new real-device mutation;
- build/test affected code before returning when practical;
- report changed files, tests, assumptions, and remaining risk.

Common starting model: **GPT-6.1 Sol / medium-high**.

For very narrow, low-risk implementation, a cheaper capable model may be used. For complex lifecycle, rollback, concurrency, or cross-module work, increase model capability/reasoning while keeping the worker role.

## 4.3 `utility` — deterministic mechanical specialist

Custom role defined in `.codex/agents/utility.toml`.

Use for:

```text
mkdir
file moves / copies / renames
grep / find / simple searches
formatting
fixture organization
simple deterministic configuration edits
mechanical repetitive edits
running clearly specified low-risk commands
```

Do not use for architecture, ambiguous coding, OriginOS reverse engineering, or deciding whether a vivo-private system write is safe.

Common starting model: **GPT-6 Luna / low**.

If the work remains mechanical but becomes too large or error-prone for Luna, keep the utility role and use Terra. If the work becomes semantic implementation, change to worker instead.

## 4.4 `reviewer` — independent verification specialist

Custom role defined in `.codex/agents/reviewer.toml`.

Use after meaningful implementation to inspect:

```text
correctness
rollback completeness
security / shell injection risk
state modeling errors
race conditions
Android lifecycle mistakes
battery regressions
test gaps
AGENTS.md violations
phase acceptance gaps
```

Reviewer should normally be read-only and return findings to the parent rather than silently rewriting implementation.

Common starting model: **GPT-6.1 Sol / high** for routine independent implementation review; **GPT-6 Astra / high** when §5.1 is triggered, including phase-level final audit and a proposed new system write.

A lightweight review may use a cheaper model. If the review becomes difficult, keep the reviewer role and upgrade the model/reasoning before changing roles.

## 4.5 `default`

Use for ordinary delegated work that does not cleanly fit another specialist role.

---

# 5. Dynamic model routing policy

Updated 2026-09-30 at the user's request. Defaults describe workload routing, not permanent role/model bindings.

| Task profile | Preferred model | Reasoning |
|---|---|---|
| parent / orchestration | GPT-6.1 Sol | high; user's selected main-thread setting overrides |
| routine implementation | GPT-6.1 Sol | medium for clear bounded work; high for lifecycle/concurrency/rollback complexity |
| routine independent review | GPT-6.1 Sol | high |
| deterministic / mechanical work | GPT-6 Luna | low |
| inexpensive read-heavy or larger mechanical work | GPT-5.6 Terra | medium |
| any escalation trigger below | GPT-6 Astra | high; increase only for demonstrated need |

These are project engineering choices, not claims that one model guarantees correct diagnosis.
Official OpenAI documentation checked 2026-09-30:
[GPT-6.1 Sol](https://developers.openai.com/api/docs/models/gpt-6.1-sol) and
[GPT-6 Astra](https://developers.openai.com/api/docs/models/gpt-6-astra).
The active host's callable model/effort list controls actual availability.

## 5.1 Required Astra escalation triggers

Route the relevant causal analysis, design decision or independent review to GPT-6 Astra when:

1. Multiple plausible causal explanations remain and the next decision depends on choosing between them.
2. Two valid attempts fail to resolve the same substantive problem.
3. A previously unvalidated OEM-private mechanism is discovered.
4. A new real-device system write is proposed, before executing it.
5. Architecture or the meaning/classification of evidence is being changed.
6. A phase-level final audit or acceptance decision is being conducted.

Do not wait for two failed attempts when another trigger already applies. A valid attempt has
usable inputs/environment, an explicit hypothesis or fix and meaningful verification; the second
must add discriminating evidence rather than repeat an equivalent path. Downloads, network
outages, missing permissions, offline devices, command/tool formatting errors and mere failure
to reproduce a symptom do not count as substantive model failures. Record the two approaches
and their results in a bounded handoff; do not extend investigation indefinitely.

A new system write includes a newly introduced mutation class, an unvalidated OEM path or a
changed target/rollback/evidence assumption. A previously reviewed same-path repetition with
fresh identity/baseline does not by itself require a new model escalation. Ordinary code editing,
builds and read-only ADB checks are not phone system writes.

A phase audit is the exit/scope/acceptance judgment, not every routine build or document edit.
For a discovered OEM-private mechanism, inexpensive agents may collect read-only raw facts,
but Astra reviews interpretation and any proposed use before it informs a risky decision.

## 5.2 How to escalate and return

- If the selected parent is already GPT-6 Astra, keep its user-selected reasoning setting and
  arrange independent review as required. Do not claim a particular active model without metadata.
- Otherwise delegate the triggered task to Astra, pass existing evidence/failures and keep the
  selected parent responsible for integration and final device-safety approval.
- Do not silently switch a live parent or edit configuration to force one. If the needed Astra
  capability is unavailable, preserve the exact blocker and continue safe independent preparation;
  do not execute the dependent new write or close the phase by silently substituting a cheap model.
- After the Astra decision/review is resolved, return bounded routine work to Sol and mechanical
  work to Luna/Terra. Escalation does not require moving the entire project to Astra permanently.
- A stronger model never replaces authorization, fresh device identity/baseline, verified readback,
  rollback or the user's no-lock/no-reboot boundary.

Choose the lowest-cost sufficient model within the applicable routing class. Do not use a blind
Luna → Terra → Sol retry ladder or downgrade an active Astra trigger to save cost.
The parent may perform small routine tasks directly when delegation costs more than it saves.
The existing Terra fallback in configuration is insurance for low-risk tasks, not authority to
route complex implementation or an Astra-triggered review to Terra.

Examples:

```text
rename fixtures → utility + GPT-6 Luna / low
large bounded read-only search → explorer + GPT-5.6 Terra / medium
routine implementation → worker + GPT-6.1 Sol / medium
lifecycle/rollback implementation → worker + GPT-6.1 Sol / high
new vivo Provider semantics → explorer + GPT-6 Astra / high
first system-write review → reviewer + GPT-6 Astra / high → parent safety approval
phase final audit → reviewer + GPT-6 Astra / high
```

---

# 6. Escalation without losing specialization

A subagent that discovers the task is materially harder, riskier, or more ambiguous than expected must **stop and return a bounded handoff** rather than guessing.

The handoff should include established facts, unresolved points, inspected files/commands/evidence, why escalation is needed, and the recommended next role/capability.

## 6.1 Same task type, higher difficulty → preserve role and upgrade capability

Examples:

```text
explorer + Terra cannot resolve a complex Binder/provider chain
→ re-delegate causal/OEM interpretation as explorer + GPT-6 Astra / high (§5.1)

reviewer + Terra finds a complicated rollback state machine
→ reviewer + GPT-6.1 Sol / high; use GPT-6 Astra when §5.1 is triggered

utility + Luna is still doing deterministic bulk work but the operation is too large/error-prone
→ utility + Terra
```

This preserves the role's expert instructions and professional boundary.

## 6.2 Task nature changed → change role

Examples:

```text
explorer finishes investigation and the next step is implementation
→ worker

reviewer finds a bug and the parent decides to fix it
→ worker gets a new implementation task

utility discovers that a mechanical edit actually changes Android lifecycle semantics
→ stop utility and re-route to worker
```

Do not keep the old role merely for continuity when the nature of work has changed.

## 6.3 Architecture or new real-device risk → return to parent

If the issue becomes:

```text
architecture-changing
ambiguous product behavior
conflicting verified evidence
new class of vivo-private write
first execution of a risky mutation
```

return the decision to the parent Orchestrator.

Apply §5.1: obtain GPT-6 Astra analysis/review of the relevant decision before dependent risky execution. The selected parent retains final authority. If Astra is unavailable, preserve a bounded handoff and continue independent safe work; do not repeat failed child attempts or pretend the parent was switched.

## 6.4 Re-routing must reuse prior work

When replacing a child with a stronger model or different role, pass forward the useful bounded findings from the previous child.

Do not make the new agent reread the entire repository or repeat the same failed investigation unless the evidence itself is suspect.

---

# 7. Concurrency rules

The project default is a maximum of **4 open spawned-agent threads**, excluding the parent.

Concurrency is useful for independent work such as:

```text
explorer A: inspect OriginOS Toolkit implementation
explorer B: inspect current repository implementation
reviewer: inspect an already completed independent diff
utility: organize read-only fixtures
```

Avoid concurrency when agents would edit overlapping files.

## Hard rule

Do not allow multiple writable subagents to modify the same file set concurrently.

Before spawning parallel writable work, partition ownership clearly, for example:

```text
worker A → privileged/ only
worker B → fcmtest/ only
```

If ownership cannot be cleanly partitioned, serialize the tasks.

Reviewer and explorer should normally remain read-only while a worker is writing.

---

# 8. Token and context efficiency

## 8.0 Persistent priority: finish promptly with bounded scope

The user explicitly requires fast completion. Apply this rule to every subsequent turn:

- Start from the recorded next action and define a concrete, finite acceptance target.
  Complete implementation, applicable verification, cleanup and documentation as one work
  package; do not turn routine steps into separate user turns or repeated approvals.
- Prioritize the shortest path to the user's core outcome: FCM reliability and recipient
  execution. Do not expand frameworks, optional features, fault combinations or research
  merely because additional work is possible.
- Reuse passed tests and valid device evidence. Repeat checks only after relevant changes,
  failures, evidence drift or a specific unresolved acceptance risk. Batch independent work
  with clear ownership; run device mutations and their dependent checks sequentially.
- Before device acceptance, identify the exact outstanding item, prior evidence and the
  concrete change/failure that invalidates it (if it was already passed). A newer version,
  connected phone, generic "continue", or host-only branch alone is not a repeat trigger.
  User-accepted UI remains accepted. Explain any necessary delta briefly before acting.
  Close completed items in the current queue immediately; historical queues are not tasks.
  With no justified outstanding item, advance a concrete implementation defect or explain
  the actual blocker; do not substitute another acceptance/review cycle for development.
- Bound each investigation and name its stopping condition. Once a path is unsupported,
  record the limitation and use the authorized fallback instead of retrying equivalent work.
- When a phase runs long, immediately maintain a short exit checklist: passed items,
  unsupported/degraded items and the exact remaining blockers. Finish that list without
  adding optional work. A physical-access blocker is not a reason to invent more groundwork.
- Proceed autonomously within existing authorization. Do not pause for routine engineering
  choices or ask the user to repeat a settled preference. Report progress briefly and candidly.
- Speed never permits skipping first-write review, fresh identity/baseline checks, exact
  rollback, evidence persistence or the user's no-lock/no-reboot rule. Do not relabel an
  unverified result as passed or promise a completion time without evidence.

Multi-agent work can consume **more total tokens** than a single agent. Use it to reduce expensive-parent context and improve specialization, not merely to increase agent count.

## 8.1 Do not over-delegate tiny work

A tiny task should remain one task.

Bad:

```text
spawn one agent to mkdir
spawn another to rename one file
spawn another to run formatter
```

Better:

```text
one utility task containing all related mechanical operations
```

## 8.2 Use explorers to compress large contexts

When the parent would otherwise read thousands of lines:

```text
spawn explorer + Terra
→ read/search relevant material
→ return a bounded summary with exact references
→ persist durable findings if required
```

Do not paste enormous raw logs into the parent context.

Store raw outputs as fixtures / diagnostic files where appropriate and summarize them.

## 8.3 Read only relevant project history

Do not read the full append-only `WORKLOG.md` on every session.

Use:

- `PROJECT_STATE.md` for current state;
- `CODEX_HANDOFF.md` for exact resume point;
- search or recent tail of `WORKLOG.md` only when needed.

## 8.4 Persist before context is lost

A useful conclusion that exists only in a model context is considered at risk.

Persist it before moving on.

---

# 9. Mandatory reading order at the start of every primary session

Before substantial work, the parent reads:

1. `AGENTS.md`
2. `docs/PROJECT_STATE.md`
3. `docs/CODEX_HANDOFF.md`
4. only the relevant phase/sections of `OriginOS_FCM_Guardian_Development_Spec.md`
5. `docs/DEVICE_FINDINGS.md` when device behavior matters
6. relevant `docs/DECISIONS.md` entries when architecture matters
7. `git status` and relevant diff

Do not reread the complete development spec on every small continuation if `PROJECT_STATE.md` identifies the active phase and the relevant sections are known.

Do not assume chat history is complete.

Repository files are the persistent source of truth.

---

# 10. Project memory system

Important conclusions must not exist only in chat or one Codex session.

## 10.1 `docs/PROJECT_STATE.md`

Single compact snapshot of the current project.

Keep it concise, ideally below roughly 200 lines.

It must contain:

```text
Current phase
Current milestone
Current device / OriginOS build
Current app build status
Implemented
Verified on real hardware
Implemented but not verified
Known blockers
Known regressions
Immediate next task
Exact resume point
Last updated
```

Update it after meaningful milestones or changes in project reality.

Do not use it as an append-only history.

---

## 10.2 `docs/CODEX_HANDOFF.md`

Fast recovery after interruption, context exhaustion, quota exhaustion, or a new primary session.

Keep it short and overwrite as needed.

Use:

```text
# Current Handoff

## Where we are
<phase + exact task>

## Last completed action
...

## Current working tree
...

## Verification performed
...

## Open issue
...

## Next exact action
...

## Do not repeat
...

## Important cautions
...
```

When context is getting low, update this file **before starting another task**.

---

## 10.3 `docs/DEVICE_FINDINGS.md`

Durable record of real vivo / OriginOS observations.

For important findings record:

```text
Date
Device model
Android version
OriginOS version
Build ID / fingerprint
Observation source: PC ADB / Shizuku / Android API / UI
Probe / command
Relevant result
Interpretation
Confidence: VERIFIED / PARTIAL / UNKNOWN
Persistence after reboot: YES / NO / NOT_TESTED
Persistence after OTA: YES / NO / NOT_TESTED
Related implementation
```

Examples:

- GMS AppOps output format;
- DeviceIdle behavior;
- `com.vivo.pem` existence;
- `vivo_pem_ignore_list` behavior;
- OriginOS 7 autostart backend;
- shell write capability;
- PC ADB vs Shizuku capability;
- reboot/OTA persistence;
- real FCM delay observations.

Never promote an internet claim or older OriginOS behavior to `VERIFIED` for the target device without testing.

---

## 10.4 `docs/DECISIONS.md`

Short ADR-style records for important settled design decisions.

Format:

```text
## YYYY-MM-DD — Decision title
Status: ACTIVE / SUPERSEDED

Decision:
...

Why:
...

Evidence:
...

Consequences:
...
```

Use only for meaningful architectural decisions, not ordinary edits.

---

## 10.5 `docs/WORKLOG.md`

Append-only concise engineering history.

Entry format:

```text
## YYYY-MM-DD HH:mm — task
Phase:
Goal:
Changes:
Files:
Commands/tests:
Real-device result:
New finding:
Remaining issue:
Next:
```

Do not automatically load the whole file once it grows large.

---

## 10.6 `docs/TEST_MATRIX.md`

Track verification status, not just implementation status.

At minimum:

```text
Feature
Unit tested
Build tested
PC ADB tested
Shizuku tested
Real device tested
Reboot tested
OTA tested
Result
Device build
Notes
```

Code compiling does not mean a device-dependent feature is done.

---

## 10.7 `THIRD_PARTY_NOTICES.md`

Maintain when third-party code is directly copied, modified, vendored, or materially incorporated.

Do not evade licenses by asking an AI to rewrite protected implementation.

---

# 11. Context-loss and interruption protocol

The project must be recoverable without previous conversation context.

## Before a long or risky task

Ensure `CODEX_HANDOFF.md` records:

```text
current phase
intended operation
current device/build
baseline / rollback location
expected verification
```

## After a meaningful milestone

Update as applicable:

1. `PROJECT_STATE.md`
2. `CODEX_HANDOFF.md`
3. `WORKLOG.md`
4. `DEVICE_FINDINGS.md` if device knowledge changed
5. `TEST_MATRIX.md` if verification changed
6. `DECISIONS.md` if architecture changed

## If quota/context/time is nearly exhausted

Do not start another implementation unit.

First make the repository resumable:

```text
save findings
record unfinished work
record exact next action
record commands already tried and results
record important uncommitted files / diffs
update handoff and project state
```

A new primary session should be able to continue without asking the user to reconstruct history.

---

# 12. Dependency-based development and phase acceptance

Follow `OriginOS_FCM_Guardian_Development_Spec.md`.

Latest scheduling decision (2026-10-04): the user explicitly authorizes independent offline work
across Phase1–5 while the phone is normally disconnected. Phase numbers describe responsibilities,
not a global serial lock. This supersedes the old Phase5-last development restriction, not safety,
per-feature dependencies, device acceptance or the original notification-reliability goal.

Responsibilities:

```text
Phase 0 — OriginOS 7 read-only investigation
Phase 1 — Core ADB / Shizuku detection and repair
Phase 2 — Autostart management, auto-protection, rollback
Phase 3 — Real FCM health testing
Phase 4 — Third-party tracing / Deep Monitoring
Phase 5 — optional visual/report polish (basic usable UI accompanies Phase1–3)
```

Follow the eligible work packages in `docs/ACCELERATED_PLAN.md`. Known core defects take priority;
when a core item only awaits device evidence or has a recorded unsupported path, implement useful,
independent Phase4/5 slices instead of treating phone access as a prerequisite for all development.
Shared files/contracts still require serialized ownership. Do not invent optional work just to fill
a phase, or reopen cancelled/unsupported experiments solely because scheduling is parallel.

Especially:

- no new logging framework or sources without a concrete need and bounded approved contract;
- existing diagnostic correctness and bounded report-format work may proceed offline;
- no complex UI before state and rollback correctness;
- no automated vivo-private write before manual verification;
- a supported manual effect remains a prerequisite for its corresponding automatic capability;
- host verification is not device, notification-delivery or battery acceptance;
- no phase completion until acceptance criteria pass.

Maintain one device-acceptance queue and upgrade once to the latest host-verified candidate after
fresh connection confirmation. Phone absence blocks those checks, not unrelated host packages.

If the user explicitly changes priorities, update `PROJECT_STATE.md`.

---

# 13. Real-device safety and parent approval

The phone is a real user device. Treat system mutations as production-sensitive.

## 13.0 Device availability default (user instruction, 2026-10-02)

Unless the user explicitly says the phone is connected for the current work, assume it is
unavailable. Do not poll ADB, inspect or operate the phone, or repeatedly request a connection.
Continue independent local implementation, host tests, builds and reviews; record device-only
acceptance as pending. A past connection or historical device snapshot is not current access.
When the user later confirms connection, refresh only the baseline needed for the queued
acceptance. This rule persists across sessions and computers and does not expand mutation authority.

Maintain `docs/DEVICE_ACCEPTANCE_QUEUE.md` as the single device-acceptance batch queue. Every
offline package adds or updates affected pending checks, candidate build, dependencies, required
user interaction and acceptance criteria. On explicit connection, batch ready independent checks
with one appropriate verified build, preserve unready/conditional items, record results/evidence and
cleanup, then tell the user when unplugging is safe. Reuse valid passes; reopen only affected checks
with a stated reason. Read this queue when resuming or switching computers. It does not replace the
development roadmap or authorize new device mutations/reboots; no continuous USB connection required.

## 13.1 Phase 0 is read-only

Until Phase 0 is explicitly complete:

- no AppOps writes;
- no DeviceIdle writes;
- no PEM writes;
- no autostart writes;
- no package disabling;
- no destructive settings changes.

## 13.2 First-write approval gate

For **every new class of device mutation**, before the first real-device execution there must be:

```text
verified read/probe
original value captured
rollback procedure
exact write command/API
read-back verification plan
known device/build recorded
risk reviewed by parent agent
```

The first execution of a new mutation class requires **GPT-6 Astra independent safety review (§5.1)** and final approval by the **parent Orchestrator**. This model requirement does not expand the user's device-mutation authorization.

Examples:

```text
cmd appops set ...
cmd deviceidle whitelist +...
settings put ... vivo private key
content update ... vivo provider
private service / binder mutation
```

A Luna/Terra subagent must never independently decide to “try” a new vivo-private write command.

Workers may implement mutation code, but the parent controls first real-device execution.

## 13.3 Hard prohibitions

Do not use:

- Root;
- Magisk;
- LSPosed;
- global Doze disable as normal fix;
- `dumpsys deviceidle disable` as permanent solution;
- disabling `com.vivo.pem`;
- disabling vivo core services;
- permanent wakelocks;
- always-on foreground service in ordinary Daily Protection mode, except the explicitly user-approved
  opt-in Telegram automatic-recovery service described in §15;
- blanket unrestricted battery for apps;
- blanket Doze whitelist;
- unknown provider/database writes;
- overwrite of `vivo_pem_ignore_list` without preserving the complete original value;
- destructive git operations to erase user work.

If a feature requires breaking these rules, stop and document the limitation.

---

## 13.4 UI text entry and input-method checks

Before entering English, package names, URLs, identifiers or commands in a phone/computer UI,
first ensure the active input method is in English/direct Latin input mode. Prefer native
paste when supported. Verify the resulting field text exactly before submitting, navigating
or changing a setting. If an IME converts letters/punctuation or chooses a suggestion, stop,
correct the field and recheck; never continue on an assumed value. Do not change the default
keyboard or disable IME protections merely to automate entry.

## 13.5 Persistent installation interaction authorization (2026-10-03)

The user explicitly authorizes the agent to operate the phone installation confirmation for
Guardian and its necessary companion test APKs: inspect the current installer, select the required
allow checkbox directly above the install button, confirm installation, then verify the installed
package/version. Do not repeatedly ask the user to perform this routine confirmation. This does not
authorize unrelated software, blanket permission changes, disabling installer protections, or device
access when the user has not confirmed connection (§13.0). If the actual dialog differs materially or
requires a credential/biometric, stop and ask rather than guessing.

## 13.6 Exact UI labels in user instructions (2026-10-03)

Before giving a phone navigation path, verify the current installed version's visible labels or
matching source. Quote the exact button label, distinguish a section/card heading from a button
and destination title, and do not substitute an internal feature name or an older version's label.
For v13, Settings → 工作模式 card → 内置连接 button opens 工作模式 · 内置启动; these are not interchangeable.

## 13.7 Routine test authorization (2026-10-03)

The user explicitly authorizes routine in-scope project tests without asking for each test again.
This includes the disclosed finite FCM Toolbox force-stop followed by Guardian manual stopped-state
recovery, with the target restored afterward. Record the exact target, original state and outcome.
This is not blanket authorization to erase data, reboot the phone, disturb unrelated applications or
shared services, or bypass the first-write gate. Device availability still follows §13.0; new mutation
classes still require independent review and parent approval before execution.

# 14. Evidence levels

Never represent inference as fact.

For OriginOS/private mechanisms:

```text
VERIFIED
PARTIAL
READ_ONLY
READ_WRITE_VERIFIED
NEEDS_VALIDATION
UNSUPPORTED
UNKNOWN
```

For notification/FCM evidence:

```text
CONFIRMED
HIGH_CONFIDENCE_INFERENCE
LOW_CONFIDENCE_INFERENCE
UNKNOWN
```

Examples:

- NotificationListener observed `onNotificationPosted` → confirmed notification posted.
- GMS-related event + Telegram UID activation + Telegram notification → high-confidence related chain.
- Guardian cannot directly read Telegram's internal `FirebaseMessagingService` → never claim confirmed third-party FCM delivery without direct evidence.

---

# 15. Daily Protection vs Deep Monitoring

Keep the two modes separate.

## Daily Protection

Latest recovery scheduling decision (2026-10-04): the user explicitly removes the normal automatic
recovery quotas (5-minute minimum / 3 attempts per hour). Production startup and admission now
fresh-check the current exact durable lease, not the historical budget. Preserve legacy records
unchanged for compatibility; do not clear, normalize or migrate authority to remove a quota.
Keep the sole owner, serial single-flight execution, fresh identity/connection/write-ticket checks,
pending-result read-only reconciliation and existing best-effort 30-second polling. False flags
must not cause writes, and failures must not introduce an immediate retry loop. This supersedes
historical quota-preservation requirements below, not explicit OFF, revocation or storage safety.
See docs/RECOVERY_FREQUENCY_POLICY.md. Never equate uncapped attempts with guaranteed delivery.

Latest backend decision (2026-10-03): user abandoned Guardian-owned pairing/startup and chose external
Shizuku as the sole active backend, with GKD-like explicit authorization interaction. This supersedes
older internal-only/app-owned activation requirements below. Follow EXTERNAL_SHIZUKU_V18 and the active
ACCELERATED_PLAN; cancelled D03/internal recovery/policy3 work is not accepted capability. Preserve all
private records/keys and external policy2 semantics. Permission authorization is not protection consent;
explicit revocation retains the existing stop rule. Guardian may wait after boot for externally restored
Shizuku, but must not claim it can make that external shell service survive/restart without Root.
No automatic wireless settings writes or shared-service manipulation for this external-only route.

Latest product scope (2026-10-01): stability first, then minimum independent Home/Apps navigation;
Settings/Diagnostics are secondary. Core startup, protection, app management and basic usable UI
belong to Phase1–3, not optional Phase5 polish. Read docs/PRODUCT_ROADMAP.md and the single active
docs/ACCELERATED_PLAN.md queue with status/handoff. Old R1/R2 queues are archived, not current tasks.
Planning acceptance is not implementation or field acceptance. Preserve attention-vs-consent separation,
live privilege/readiness semantics, journal/reconciliation, active monitor Stop and private screenshots.

Latest explicit product override (2026-10-04): configured applications are persistent settings.
The Home master switch controls running intent; global OFF, unavailable privilege and ordinary
failure must not silently cancel those selections. Only an explicit per-app OFF removes that
configuration. OFF still synchronously revokes future execution and must be durably fail-closed;
retained configuration is not current write authority. Architecture design requires a single atomic
authority schema3, distinct configured-vs-runnable reads, independent master history acknowledgement,
identity invalidation and interrupted-storage gates. Read docs/MASTER_SWITCH_APP_SELECTION.md.
This supersedes older global Stop-clears-all product behavior, not identity/journal/permission gates.
Never reconstruct already-retired consent from backups. Historical v44 stopping caller is UNKNOWN;
do not claim lifecycle/GKD/system causality or restored FCM benefit from this redesign.

Latest lifecycle requirement (2026-10-01): the user explicitly prioritizes one-time opt-in,
boot/reboot startup and recovery of actual Guardian protection, plus app-owned privilege activation.
The old no-boot/session-only rule below describes shipped v5, not the desired final product.
Implementing durable consent/restart is now in scope, but legacy session grants must not silently
become durable write consent. Preserve explicit stop/revocation, fresh identity/journal checks,
cross-process authority locks, no Root/wakelock/fake accessibility/VPN, and user-hidden notifications.
An app process starting is not privileged readiness. No new device mutation or agent-initiated
phone reboot is authorized by this product requirement; apply the existing first-write gates.
See docs/PRIVILEGE_STARTUP_PLAN.md; architecture and implementation review precede deployment.

Latest user override (2026-10-01): user rejects persistent notification drawer clutter. Platform FGS
notification submission remains mandatory, but user notification suppression/dismissal must not itself
revoke explicit protection consent. Preserve in-app status/stop and OS Task Manager stop controls.
Use the normal channel settings UI; do not fake accessibility/media roles or bypass platform service
requirements. Revised architecture approved by independent Astra review; final code/device checks
are still required.

2026-10-01 explicit user exception: Guardian-only foreground service with user-controlled notification
visibility is allowed for opt-in Telegram automatic stopped-flag recovery. Target apps must not be
kept resident. No permanent wakelock, exact-alarm loop, blanket exemption, boot restart or silent
re-enable. The initial version is a foreground-user-started, non-sticky session; process loss/reboot
requires explicit re-enable. Best-effort 30-second reads may be delayed by suspend/Doze/OEM policy;
never promise continuous protection or a hard receipt deadline. Persistent consent must not be
fabricated from a manual recovery confirmation; record automatic provenance separately. Disabling
revokes future operations, but cannot undo a command already dispatched. Existing daily read-only
inspection remains separate. This supersedes the no-FGS default only for this approved service.

Default priorities:

```text
low battery use
no continuous logcat
no persistent foreground service
event-driven checks
~daily WorkManager fallback
minimum necessary repair
normal/optimized Guardian battery policy
```

## Deep Monitoring

Manual debugging mode only.

Allowed while explicitly enabled:

```text
filtered system-event tracing
foreground service if required
Guardian may recommend autostart + unrestricted battery
Shizuku required
structured trace persistence
```

It must never silently enable itself.

Stopping it must stop its foreground tracing infrastructure.

---

# 16. Architecture rules

Preserve this dependency direction:

```text
UI
↓
Domain / use cases
↓
Detector / Repair / Policy
↓
PrivilegedExecutor
↓
ShizukuExecutor / Android APIs
```

Do not scatter shell strings through ViewModels or UI code.

Use allowlisted operations such as `SafeCommand`.

All privileged writes follow:

```text
probe
→ baseline/journal
→ write
→ read-back verify
→ persist result
→ rollback available
```

No generic arbitrary-shell UI.

---

# 17. Open-source reuse policy

Before implementing a generic subsystem, inspect the relevant references in the development spec.

Key references include:

```text
cameleonnbss/originos-toolkit
RikkaApps/Shizuku-API
AppOpsNext
spike0en/better_vivo
judemanutd/AutoStarter
Firebase Android Quickstart
notification-logger
Awesome OriginOS / Awesome Shizuku
```

Priority:

```text
official Android / Firebase / Shizuku behavior
→ target OriginOS 7 real-device evidence
→ mature OriginOS-specific project
→ generic Android/Shizuku project
→ forum/community reports
```

Do not fork a large general-purpose project merely to save a small implementation effort.

Reuse mature infrastructure; keep Guardian focused.

---

# 18. Build and verification discipline

Once the Android project exists, inspect and use its actual Gradle tasks.

After relevant code changes, normally perform applicable checks such as:

```bash
./gradlew test
./gradlew lint
./gradlew assembleDebug
```

If those exact tasks are not appropriate, use the correct project-specific equivalents.

For device-dependent work:

```text
record exact build/fingerprint
verify new operation via PC ADB when introducing it
verify Shizuku separately
verify read-back
verify rollback
verify reboot persistence when relevant
update TEST_MATRIX.md
```

Never call a device behavior verified based only on compilation or emulator success.

---

# 19. Review workflow

For meaningful core implementation, use this preferred loop:

```text
parent defines scope
↓
worker implements
↓
worker builds/tests
↓
reviewer independently inspects diff
↓
parent evaluates findings
↓
worker/parent fixes if needed
↓
final build/tests
↓
real-device validation if applicable
↓
parent accepts milestone
↓
state docs updated
```

Do not spawn a reviewer for trivial one-line mechanical edits unless risk warrants it.

Reviewer findings should be prioritized by severity and include exact evidence.

---

# 20. Git and working-tree discipline

Before editing:

```text
inspect git status
understand existing uncommitted changes
do not overwrite unrelated user work
```

Do not use destructive operations such as:

```text
git reset --hard
git clean -fd
force checkout of user changes
```

unless explicitly requested.

Keep changes scoped.

Do not create commits, tags, branches, or push unless requested or explicitly part of the active workflow.

---

# 21. Documentation discipline

## AGENTS.md

Keep stable.

Update only for persistent repository-wide working/safety/orchestration rules.

Do not store ordinary progress here.

## Development spec

Update when:

- verified device behavior invalidates an architectural assumption;
- the user changes scope;
- a phase design materially changes.

## PROJECT_STATE.md

Reflect reality, not intention.

If code exists but the real phone has not verified it, write:

`IMPLEMENTED / NOT DEVICE VERIFIED`

Do not mark complete.

---

# 22. Communication with the user

For substantial work:

- briefly state current phase and immediate goal;
- surface important real-device findings early;
- distinguish fact, hypothesis, and unresolved behavior;
- do not hide failed experiments;
- do not claim success before verification.

Ordinary technical decisions should be made from the project plan without unnecessary interruption.

Ask the user only when a genuine product decision, irreversible choice, missing credential, or required physical interaction cannot be resolved safely from the repository and device state.

When work must stop due to context, quota, device access, or a hard blocker:

1. persist repository state first;
2. then report the precise blocker and next action.

Do not ask the user to repeat information already stored in project files.

## 22.1 Mandatory concise milestone report

按用户汇报偏好及最新目标澄清（2026-09-30），每有关键节点进展或完成时，用三条短句报告以下内容。
用户要的是方便跟踪项目的粗略工程判断，不是统计概率论文；普通澄清不必反复套完整简报。

- **可行性概率**：给出当前核心目标（真实应用在日常屏幕/供电/网络条件下减少漏收与异常延迟）的
  工程估计概率或区间，标注“工程估计，非实测成功率”；不得用检测界面的可实现性代替
  修复效果的可行性。证据不足时说明不确定性，不承诺必成，不制造精确数字；没有新证据
  不上调概率，评估变化须说明关键依据并更新 `docs/FEASIBILITY_ASSESSMENT.md`。
  可以给有依据的主观粗略区间，不要求先有统计模型；变化理由对外最多一句，详细依据留文档。
  必须明确估计对象和支撑依据，不得机械复制历史区间。已验证的限定能力直接报告事实；
  确实无法形成负责任的粗估时才简短说明证据不足；不能为了填写概率而制造数字，
  也不能把阶段推进当作修复成功率上升。
- **当前进度**：当前 Phase、本轮完成项；没有明确验收分母时不虚构完成百分比。
- **当前状态**：验证结果或主要未验证项，以及下一步；本轮仅改文档时说明工程状态未变。

Keep the entire closing report short and direct. Distinguish implemented, tested and
device-verified outcomes; never substitute test counts for actual FCM efficacy.

用户最新明确（2026-09-30）：核心前提是目标App开启自启动、主/附属进程均不在后台，
目标是FCM到达后按需启动、处理并发布通知。App常驻时正常按用户工作前提处理，不再
反复验收或用常驻保活替代修复；屏幕/供电/Wi-Fi/移动网络均围绕无进程场景取样。
第三方消息是否经FCM、是否由推送拉起必须依证据判断，不预设。默认不展开多维
概率或长篇机制分析；详细证据留在docs，除非用户追问。范围收敛不代表核心效果已经达标。

---

# 23. Definition of done for a task

A task is complete only when applicable items are true:

```text
implementation finished
code builds
relevant tests pass
real-device verification completed when required
new device mutations have verified rollback
new device findings persisted
PROJECT_STATE.md current
CODEX_HANDOFF.md current
TEST_MATRIX.md current
important conclusions not trapped in model context
```

---

# 24. Bootstrap files

If missing at project start, create:

```text
docs/PROJECT_STATE.md
docs/CODEX_HANDOFF.md
docs/DEVICE_FINDINGS.md
docs/DECISIONS.md
docs/WORKLOG.md
docs/TEST_MATRIX.md
THIRD_PARTY_NOTICES.md
```

## PROJECT_STATE.md template

```markdown
# Project State

Last updated:

## Current phase
Phase 0

## Current milestone
Not started

## Target device
Not yet connected

## Implemented
- None

## Verified on real device
- None

## Implemented but not verified
- None

## Blockers
- None

## Known regressions
- None

## Immediate next task
Complete Phase 0 read-only investigation.

## Exact resume point
Read the relevant development-spec sections and connect the target OriginOS 7 device.
```

## CODEX_HANDOFF.md template

```markdown
# Current Handoff

## Where we are
Project bootstrap.

## Last completed action
Repository initialized.

## Current working tree
No meaningful implementation yet.

## Verification performed
None.

## Open issue
Phase 0 has not started.

## Next exact action
Begin read-only device investigation.

## Do not repeat
None.

## Important cautions
Phase 0 is read-only. Do not change device state.
```

## DEVICE_FINDINGS.md template

```markdown
# Device Findings

Only verified or clearly labeled partial findings belong here.

No device findings yet.
```

## DECISIONS.md template

```markdown
# Engineering Decisions

No recorded decisions yet.
```

## WORKLOG.md template

```markdown
# Engineering Worklog
```

## TEST_MATRIX.md template

```markdown
# Test Matrix

| Feature | Build | Unit | PC ADB | Shizuku | Real device | Reboot | OTA | Build/Fingerprint | Result | Notes |
|---|---|---|---|---|---|---|---|---|---|---|
```

---

# 25. Primary-session resume checklist

```text
[ ] Read AGENTS.md
[ ] Read PROJECT_STATE.md
[ ] Read CODEX_HANDOFF.md
[ ] Read only relevant development-spec sections
[ ] Read DEVICE_FINDINGS.md if device behavior matters
[ ] Read relevant DECISIONS.md entries if needed
[ ] Check git status / relevant diff
[ ] Confirm device/build if doing device work
[ ] Confirm active phase acceptance criteria
[ ] Treat the currently selected main-thread model/reasoning as the Orchestrator; do not assume GPT-6
[ ] Apply §5: default preference Sol6.1 high, manual selection wins, check all six Astra triggers
[ ] Decide whether work should stay in parent or be delegated
[ ] For each delegation choose task role first, then model + reasoning explicitly
[ ] Continue from the recorded next action
```

Do not restart completed investigations unless evidence is missing, contradictory, or invalidated by a new device/build.

---

# 26. Preferred orchestration examples

## Phase 0 investigation

```text
Current main-thread model = Orchestrator
  ├─ explorer + suitable model
  │    inspect OriginOS Toolkit / AutoStarter references
  ├─ explorer + suitable model
  │    summarize and interpret read-only ADB outputs
  └─ utility + low-cost suitable model
       organize captured fixtures

If investigation becomes harder, keep the explorer role and upgrade its model/reasoning.
Parent compares evidence, records DEVICE_FINDINGS, and decides Phase 1 safe scope.
```

## Phase 1 implementation

```text
Current main-thread model = Orchestrator
  ↓ define safe operation and acceptance criteria
worker + suitable implementation model
  ↓ implement PrivilegedExecutor / detector / repair / rollback
reviewer + suitable review model
  ↓ routine Sol6.1 review; new system write requires independent Astra safety review (§5.1)
  ↓ check correctness, safety, rollback, tests
Parent
  ↓ approve first real-device write
Real-device verification
  ↓
Persist state
```

## Mechanical refactor

```text
Parent
  ↓ tightly specified task
utility + Luna / low
  ↓ deterministic edits
Parent or worker verifies build/diff
```

---

# 27. Core project philosophy

The project should remain:

```text
safe
→ evidence-driven
→ reversible
→ low-power by default
→ deeply observable when explicitly requested
→ economical with expensive-model context
→ resumable after any interruption
```

The two most important engineering rules are:

> **A useful conclusion that is not persisted in the repository may be lost. Persist it before moving on.**

> **Use the cheapest model that is confidently sufficient, but never trade away real-device safety or correctness to save tokens.**
