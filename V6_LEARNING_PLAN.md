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

## Inspection
Kotlin 2.0.21, Compose Material 3 and SQLiteOpenHelper v5. Word IDs are catalog strings. The card table has one FSRS record per word. effective_event resolves corrections. Local backup is JSON. Profile databases are isolated. Existing destinations include Home, Learn, Library, Academic, Explore, Statistics and Profile. V5 has four listening passages, eight mini stories and vocabulary exercises. V5.2 has a daily calendar. No preexisting grammar course, XP ledger or quest engine was found. The supplied checkout includes Gradle 8.9 and JVM and device test sources.
