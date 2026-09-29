# AGENTS.md — OriginOS FCM Guardian

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

The Orchestrator model is **not fixed by this repository**. The parent agent is always the model + reasoning effort that the user currently selected for the main Codex thread.

Examples:

```text
main thread = GPT-5.6 Sol / high
→ GPT-5.6 Sol / high is the Orchestrator for that turn/session state

main thread switched to GPT-6 Astra / medium
→ GPT-6 Astra / medium becomes the Orchestrator for subsequent work
```

Do not rewrite `.codex/config.toml` to pin the primary model. Do not assume that the parent is GPT-6. Respect the user's current main-thread model selection.

A stronger parent model is recommended when the current task involves architecture, ambiguous requirements, conflicting evidence, or first-time high-risk device decisions, but ordinary project work may be orchestrated by GPT-5.6 Sol when it is sufficient.

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
worker   != GPT-5.6
utility  != Luna
reviewer != GPT-5.6
```

Those are only common starting pairings. Do not permanently bind a role to a model unless a future verified reason requires it.

## 3.1 Dynamic parent rule

The primary Codex thread is always the **Orchestrator**.

Its model and reasoning effort are whatever the user currently selected for the main thread. This repository must not pin the parent to GPT-6 or any other model.

Examples:

```text
main thread = GPT-5.6 Sol / high
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

Common starting model: **GPT-5.6 Sol / medium-high**.

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

Common starting model: **GPT-5.6 Luna / low**.

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

Common starting model: **GPT-5.6 Sol / high** for core safety-sensitive work.

A lightweight review may use a cheaper model. If the review becomes difficult, keep the reviewer role and upgrade the model/reasoning before changing roles.

## 4.5 `default`

Use for ordinary delegated work that does not cleanly fit another specialist role.

---

# 5. Dynamic model routing policy

The parent routes by **difficulty, ambiguity, risk, and context size**. Defaults, not bindings:

| Task profile | Typical model | Reasoning |
|---|---|---|
| deterministic / repetitive | GPT-5.6 Luna | low |
| read-heavy exploration / ordinary local work | GPT-5.6 Terra | medium |
| serious implementation / lifecycle / rollback | GPT-5.6 Sol | medium-high |
| architecture / conflicting evidence / first-write risk | current parent or stronger main-thread model | high as needed |

Select the **lowest-cost model confidently sufficient on the first attempt**.

Do not use a blind escalation ladder such as “always try Luna, then Terra, then Sol”. That wastes tokens and may create bad partial work. Route directly based on the actual task.

Examples:

```text
rename fixtures → utility + Luna / low
large call-graph search → explorer + Terra / medium
difficult vivo Provider analysis → explorer + GPT-5.6 Sol / high
RepairEngine + rollback → worker + GPT-5.6 Sol / high
first-write safety review → reviewer + GPT-5.6 Sol / high → parent approval
```

The parent may work directly when delegation costs more context than it saves.

---

# 6. Escalation without losing specialization

A subagent that discovers the task is materially harder, riskier, or more ambiguous than expected must **stop and return a bounded handoff** rather than guessing.

The handoff should include established facts, unresolved points, inspected files/commands/evidence, why escalation is needed, and the recommended next role/capability.

## 6.1 Same task type, higher difficulty → preserve role and upgrade capability

Examples:

```text
explorer + Terra cannot resolve a complex Binder/provider chain
→ re-delegate as explorer + stronger model / higher reasoning

reviewer + Terra finds a complicated rollback state machine
→ re-delegate as reviewer + GPT-5.6 Sol / high

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

If the current parent is not strong enough for the decision, persist state and recommend that the user switch the main thread to a stronger model/reasoning level rather than repeatedly failing with child agents.

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

# 12. Development phase gating

Follow `OriginOS_FCM_Guardian_Development_Spec.md`.

Order:

```text
Phase 0 — OriginOS 7 read-only investigation
Phase 1 — Core ADB / Shizuku detection and repair
Phase 2 — Autostart management, auto-protection, rollback
Phase 3 — Real FCM health testing
Phase 4 — Third-party tracing / Deep Monitoring
Phase 5 — UI, reports, polish
```

Do not jump ahead because a later feature is interesting.

Especially:

- no deep logging before repair is reliable;
- no complex UI before state and rollback correctness;
- no automated vivo-private write before manual verification;
- no phase completion until acceptance criteria pass.

If the user explicitly changes priorities, update `PROJECT_STATE.md`.

---

# 13. Real-device safety and parent approval

The phone is a real user device. Treat system mutations as production-sensitive.

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

The first execution of a new mutation class must be approved/reviewed by the **parent Orchestrator**.

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
- always-on foreground service in Daily Protection mode;
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

按用户最新澄清（2026-09-28），每有关键节点进展或完成时，用三条短句报告以下内容。
用户要的是方便跟踪项目的粗略工程判断，不是统计概率论文；普通澄清不必反复套完整简报。

- **可行性概率**：给出当前核心目标（FCM 长连接可靠性改善及目标应用被推送拉起处理）的
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

用户再次明确（2026-09-28）：对外只围绕一个最终结果汇报——OriginOS 锁屏、目标 App
已退出时，FCM 能使其启动并处理消息，用户最终收到通知。默认不展开多维概率或长篇
机制分析；详细证据留在 docs，除非用户追问。内部仍保留证据等级、实际条件和修复因果边界。

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
