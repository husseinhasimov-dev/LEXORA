# LEXORA V6 learning plan
Base: GitHub V5.2, 2fa6535, org.lexora.study, debug build 7. The feature/lexora-v6-learning branch lives in the V6 checkout. The outer workspace holds separate uncommitted V5.1 files.

| Phase | Dependency | Acceptance check |
|---|---|---|
| Evidence and migration | V5 SQLite database | Upgrade a populated database, compare events, cards, settings and catalog. Write one local evidence record and one eligible credit per activity. |
| Reading | Catalog and evidence | Choose actual passages by topic, level and personal vocabulary. Inspect, collect, bookmark, answer and resume. |
| Inbox | Catalog IDs | Deduplicate, select a subset and enter the normal Study All and FSRS flow. |
| Grammar | Authored curriculum and evidence | Teach first, grade keyed exercises, persist assessment and require delayed success for mastery. |
| XP | Evaluated attempts and effective vocabulary history | Award genuine work beyond the daily goal, retain a trace and prevent duplicate rewards. |
| Roadmap and quests | Evidence | Use topic progress and workload to recommend optional steps. Provide editable plans, rest days and extra practice. |
| Recovery and journal | Evidence | Trace corrections and XP, distinguish reported activity, inspect date ranges. |
| Release | All phases | Run host and Android tests, upgrade an emulator, launch and exercise screens, verify signing and document limits. |

## Phase checkpoints

1. The V5.2 baseline built and passed its JVM tests. Commit 0927a04 added schema 6, the shared evidence model, local backup support and the initial plan.
2. Commit b5dbac0 introduced the offline reading provider, context lookup, recommendation inputs and resumable question rounds.
3. The same commit connected collected entries to original vocabulary IDs. Saved entries carry no FSRS review or learning credit.
4. The A1–B2 curriculum contains 38 topics, with four keyed items per topic. Commit 269c388 connected lessons and assessments to the interface.
5. XP uses effective vocabulary ratings and an idempotent local credit ledger. The first device suite checked retries, rollback, unlimited accumulated awards and backup.
6. The roadmap and daily quests use the same results. The plan can include self-reported speaking or writing and graded listening.
7. Recovery and the journal share stable question IDs, responses, content versions and skill-specific outcomes. The journal includes dated notes, plans and recognized achievements.
8. Android 15 initially passed 57 repository/regression tests and two screen walkthroughs. The walkthrough exposed a lost tap during a bookmark save. Queued writes corrected it. Five host content checks passed after correcting a dictionary annotation. Final candidate verification and upgrade results are recorded in V6_DELIVERY.md.

## Inspection
Kotlin 2.0.21, Compose Material 3 and SQLiteOpenHelper v5. Word IDs are catalog strings. The card table has one FSRS record per word. effective_event resolves corrections. Local backup is JSON. Profile databases are isolated. Existing destinations include Home, Learn, Library, Academic, Explore, Statistics and Profile. V5 has four listening passages, eight mini stories and vocabulary exercises. V5.2 has a daily calendar. No preexisting grammar course, XP ledger or quest engine was found. The supplied checkout includes Gradle 8.9 and JVM and device test sources.
