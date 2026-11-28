# LEXORA V6.0 delivery

Open **Home → Learning Academy** for the new tools. The existing Learn, Library, Statistics, Profile, Academic Dictionary, Practice & Explore and reminder screens remain available.

## Install on Samsung

1. In your current app, open **Profile → Backup** and save a copy of your progress.
2. If your installed version came from GitHub, copy **LEXORA-0.6.0.apk** to Downloads on your phone. If you installed an earlier APK built locally in this workspace, use **LEXORA-0.6.0-local.apk**.
3. In **My Files → Downloads**, tap the APK and choose **Update**. If Android requests permission, allow My Files to install this update.
4. If Android reports an incompatible package, keep the installed app and its data. Try the other signed copy. A certificate mismatch requires an APK signed by the key used for that installation.

The files are in `C:\Users\husse\OneDrive\Desktop\German\LEXORA\V6\delivery`. Both are debug builds of package `org.lexora.study`, version `0.6.0`, build 8. They support Android 8.0 and later and include ARM64, ARMv7, x86 and x86-64. APK Signature Scheme v2 was verified for both.

| Installation source | APK | APK SHA-256 | Signing certificate SHA-256 |
|---|---|---|---|
| Published GitHub V5.2, tag v0.5.2-b7 | LEXORA-0.6.0.apk | aeb6357f26fbae68d0ad2148f820393c8c52056eefd56d31b7555e47ce63af38 | 3db7ee4ad906a139415bf6b98a52d3f23e765c263a66bf24f026e1de07a3c762 |
| Earlier locally delivered APKs | LEXORA-0.6.0-local.apk | 176466fc6a34a050649497ebf8ca3dd1f5e329c6abfe2b488202c459a9a71b8e | 8a5e3a2a1a2c4a85e9f253b212e92ed9467f84a659792aa50ed517a4841fa396 |

## What the learner can do

- **Reading.** Select estimated level, topic, length and vocabulary mix. Choose targets from the inbox or dictionary. The offline provider ranks real texts using catalog matches, knowledge state, FSRS, error history and comprehension results. Each result shows its length and target coverage. Read paragraph by paragraph, adjust the type, use an installed German voice, bookmark and resume. Tap a word or open the accessible paragraph word list. The nine extended texts link specific quoted structures to grammar lessons.
- **Inbox and discovery.** Save a word with its date, sentence and passage. Search, filter, sort, label, prioritize, archive and batch-select entries. Unresolved forms retain their original context. Match them to a dictionary entry or create a personal entry with a checked meaning. Study selected entries using the existing Study All and recall flow. Words already under review can receive extra practice.
- **Grammar.** Follow prerequisites or open any A1–B2 topic directly. Study an explanation, example and common mistake, then take guided practice, independent practice, a keyed assessment and delayed review. Mastery requires a completed lesson, at least 80% across the most recent five practice rounds (or the available rounds), an assessment of at least 85% and a successful review at least 24 hours later. Mastered topics return for review after 30 days.
- **XP.** Each qualifying academy completion has a stable reward key. The transaction includes both the evidence and eligible credit. A repeated submission or immediate replay cannot collect the first award again. A distinct delayed retrieval can earn reduced credit. There is no daily ceiling. A first successful new-word rating earns 3 XP, a successful scheduled review earns 2, and a first successful retry of a missed new word can earn 3. Vocabulary credit follows the existing corrections and undo history.
- **Roadmap and quests.** Recommended lessons show prerequisites, topic status and assessed weaknesses. Daily plans account for available days, weekly minutes, new-word goals, due workload, recent reading results and grammar weaknesses. Optional listening, speaking and writing tasks can rotate through longer study days and the last four weeks before the entered exam date. Edit an automatic task or add a one-time, daily recurring or bonus task. Skill coverage, graded answers, recorded interaction time and completed reviews appear separately. Continue Learning remains available after completion.
- **Recovery and journal.** Revisit missed passage questions, target grammar weaknesses or return to the vocabulary Mistake Lab. A later keyed success on a previously missed question appears in recovery counts. Browse daily, weekly and monthly journal entries, write personal notes, inspect XP reasons, and see recognized achievements. Manually reported activities carry their own evidence label.

## Content inventory

Reading Academy includes **17 passages and 42 comprehension questions**. There are three A1, four A2, four B1 and six B2 passages. Eight mini stories come from V5. The nine additions are listed below. Counts use the app's German word tokenizer.

| Estimated level | New passage | Words |
|---|---|---:|
| A1 | Der letzte Einkauf | 152 |
| A2 | Ein Raum für die Lerngruppe | 156 |
| A2 | Der erste Termin im Sportverein | 158 |
| B1 | Wo der Schatten fehlt | 167 |
| B1 | Zwei Namen für dieselbe Straße | 163 |
| B2 | Ein Modell am Ausleihschalter | 350 |
| B2 | Wie viel kostet eine offene Werkstatt? | 726 |
| B2 | Vom Ergebnis zur Zusammenfassung | 167 |
| B2 | Wer wartet vor der Mensa? | 1,253 |

Grammar includes **38 topics and 152 authored exercise items**. A1 has 13 topics, A2 has 10, B1 has eight and B2 has seven. Each topic has four items. The two-question assessment combines its assessment item with the independent-practice item. The delayed review uses a separate item. The complete curriculum, prerequisites, examples and keys are in `app/src/main/assets/academy-grammar.tsv`.

Reading round base rewards are 25, 45 and 70 for texts below 350 words, from 350 words and from 700 words respectively. Guided grammar, practice, assessment and delayed review have bases of 5, 12, 30 and 50. The calculation scales eligible credit by keyed accuracy and estimated difficulty. Eligible repeated delayed retrieval uses a 0.5 novelty factor. Level 1 starts at zero, with another level each 100 XP.

## Storage, migration and V6.1

Schema 5 upgrades to 6 by adding `academy_event`, `academy_credit`, `academy_inbox`, `academy_reader`, `academy_quest` and the correction-aware `academy_vocab` view. Existing catalog rows, events, assessments, cards, study sessions, decks, preferences and reminder settings retain their identities. The profile database supplies user isolation. Local backup and restore include the academy records. V6 academy evidence moves between devices through local backup.

Academy evidence stores schema and content versions, UTC time, local date and zone, skill, evidence method, confidence and assessment type. Graded work includes actual outcomes, stable question identifiers and a rubric version. Rounds can include their start time, grammar topics and measured question-interaction intervals. Word IDs and FSRS ratings remain available in the vocabulary records. Local XP transactions identify the originating evidence. Planning snapshots preserve available time and the stated exam date when a daily plan is generated.

## Verification

On Android 11, both the locally signed V5.2 build and the actual published V5.2 APK accepted their corresponding V6 APK as an update. Each comparison used a populated QA profile. All 6,546 vocabulary rows, 2,948 content rows, 17 learning events, one FSRS card including its due date, and three preferences including the saved session matched before and after. SQLite integrity and foreign-key checks passed. The packaged vocabulary catalog SHA-256 is `e419aaeba1943a155c0e0a137ebd06fb15b0621a4f28bff5669ca95a423bdc5c`.

The on-screen restart check submitted a real reading answer, force-stopped the app, reopened it at the saved feedback and finished the passage. Automated walkthroughs also saved a word, started a real vocabulary session, completed grammar practice and a quest, then entered additional reading practice. The repository tests exercised FSRS scheduling, a 100-entry inbox with a 35-word intake, delayed mastery, repeat rewards, rollback, backup and restart.

| Final check | Result |
|---|---|
| 61 instrumented tests on Android 11, published-release signing key | 61 passed, zero failed or skipped |
| Same 61 tests on Android 15, older local signing key | 61 passed, zero failed or skipped |
| JVM unit tests | 15 passed, zero failed or skipped |
| Offline content checks | 5 passed |
| Reading process-restart walkthrough | Saved answer resumed and passage finished |
| APK verification | Both APKs verified with v2 signatures and matched their final Gradle outputs |

The 61 Android tests include German offline TTS, notifications, local authentication and sync through Firebase emulators, V5 discovery and review regressions, migration, academy repositories and Compose screen walkthroughs. The first run of the older dashboard test on the populated Android 11 profile found that its saved vocabulary session disabled the date-review button. The test now opens its own profile so its starting condition is explicit. Both final suites passed. The production cloud service was outside these local tests.

The delivery folder contains a verification subfolder with the two raw Android runs, JVM XML, signing checks, migration hashes, screenshots and the machine-readable results. The migration comparison script checks all ten preexisting data tables listed in the migration evidence.

## Files changed from V5.2

- Project and delivery files are `V6_LEARNING_PLAN.md`, `V6_DELIVERY.md` and `app/build.gradle.kts`.
- In `app/src/main/assets/` the new files are `academy-grammar.tsv`, `academy-readings.json` and `academy-readings-extended.json`.
- In `app/src/main/java/org/lexora/study/` the new files are `AcademyContent.kt`, `AcademyPlanner.kt`, `AcademyProgressScreens.kt`, `AcademyReadingScreens.kt`, `AcademyRecords.kt`, `AcademyScreen.kt` and `AcademyStudy.kt`. Existing integration files changed are `Database.kt`, `ExploreData.kt`, `MainActivity.kt`, `StudyModel.kt`, `StudyRepository.kt` and `Transfer.kt`.
- In `app/src/androidTest/java/org/lexora/study/` the changed files are `AcademyJourneyTest.kt`, `AcademyTest.kt`, `IsolatedStudyUi.kt`, `ExploreTest.kt`, `RepositoryTest.kt` and `V4NavigationTest.kt`. JVM tests are in `app/src/test/java/org/lexora/study/AcademyRulesTest.kt`.
- In `scripts/` the new files are `check_v6_upgrade.py`, `smoke_v6_restart.py` and `test_academy_content.py`.

## Limits and review

- The offline library covers 12 topics across the collection. Some combinations of level, length, topic or target words have no exact match. The closest results show what differs. The collection has no C1 passages or online generation provider.
- Vocabulary familiarity estimates use identifiable dictionary matches. Explicitly annotated inflections are supported. Other ambiguous or unmatched forms can be collected provisionally. General-purpose German morphological analysis remains outside this version.
- The educational material and CEFR labels await independent linguistic review. Grammar mastery is a local study indicator based on a small exercise bank. Written exercises grade keyed responses. Speaking and writing tasks can be logged as self-reported.
- Question interaction time uses short foreground intervals. The app has no reliable measurement for silent reading time. Official assessments, AI grading, TestDaF scores, readiness dates, probabilities and alerts belong to future work.
- Physical installation on the user's Samsung remains unverified.

The development branch is `feature/lexora-v6-learning`. The stable base is `2fa6535` on main. The earlier workspace with uncommitted V5.1 work is still outside the V6 checkout. Review the changed files and the test evidence, try the matching APK on the phone, then use GitHub's merge control when ready. Until then the stable main branch and published V5.2 release remain available. [Draft pull request](https://github.com/husseinhasimov-dev/LEXORA/pull/2)
