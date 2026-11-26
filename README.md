# LEXORA for Android

LEXORA teaches German vocabulary through English. The debug build runs on Android 8.0 (API 26) and later. The installed app includes its vocabulary database and study engine. Sign-in is optional.

## Notifications in version 0.5.1

From **Profile → Manage notifications**, send an immediate test, schedule an Android test for two minutes from now, or open **Notification Health Check**. Reminders use the phone’s local calendar. Health Check separates the schedule, receiver and posting result. Android can delay inexact alarms. [Notification repair, evidence and Samsung steps](NOTIFICATION_PROGRESS.md)

## Explore in version 0.5.0

Open **Practice & Explore** from Home or Learn. Context Mastery practises catalog sentences, Confusion Lab compares words you mix up, Listening in Context plays German passages, Mini Stories adds interactive reading, and Word Families connects related entries. Practice history and unfinished rounds stay on the device. Each feature works offline. Listening uses an installed offline German voice. [V5 delivery report](V5_PROGRESS.md) · [Content coverage and linguistic evidence](reports/V5-CONTENT.md)

## Install version 0.5.1 on a Samsung phone

1. In your installed LEXORA app, open **Profile → Backup** and save the backup. Keep the installed app and its storage.
2. Copy **delivery/LEXORA-0.5.1.apk** into the phone’s Downloads folder. Open **My Files → Downloads**, tap the APK, and choose **Update** (or **Install** on a fresh device).
3. If prompted to allow this source, allow **My Files** for this installation. You can turn this permission off again afterward. If a Samsung Auto Blocker message appears, follow [Samsung’s instructions for that specific message](https://www.samsung.com/us/support/answer/ANS10003636/).
4. If installation fails, keep the old copy and note the exact error. The package requires Android 8.0 or later and uses the original project’s debug signing certificate. An independently signed LEXORA needs an update signed by its original key. A newer installed version can also block an older APK. With Android Platform Tools and USB debugging on a connected phone, `adb install -r delivery/LEXORA-0.5.1.apk` prints the exact reason. `INSTALL_FAILED_UPDATE_INCOMPATIBLE` usually means a different signer, `INSTALL_FAILED_VERSION_DOWNGRADE` means a newer version is installed, and `INSTALL_FAILED_OLDER_SDK` indicates an older Android version. [Android app signing](https://developer.android.com/studio/publish/app-signing)

## Using V4

**Today’s words** shows your daily target, selected, studied and first successful recalls. Start or continue your new-word session here. **Review words** offers scheduled FSRS reviews and a separate way to practise past vocabulary by its first study date. Choose Today, Last 7 days, Last 30 days, Older than 30 days, All previously studied words or an inclusive custom range. Choose the order and number of words. A date-based session leaves the scheduled due dates intact.

**Weekly goals** tracks how many days you reached your new-word target and how many scheduled reviews you completed, Monday through Sunday. The counts use historical daily targets and scheduled-review labels recorded by V4. Earlier versions did not save all of that information.

Open **Library → TestDaF Academic Dictionary** to browse 73 words in 10 categories. Search in German or English, listen with an installed German voice or add an entry to your learning list. Each entry shares its vocabulary ID and history with the main library. The 6,000-word vocabulary goal is a personal preparation target.

In **Profile → Manage notifications**, create separate local learning and review reminders. Set the time, weekdays, title and message. You can preview, edit, disable or delete reminders. Android 13 and later require notification permission. Android may delay delivery in battery-saving modes.

## Discovery, study and practice

- **Discover words** lets you choose a CEFR level and topic, or an academic category. You control your daily goal. Already-known words leave places open in the learning target. Tap **Previous word** to revisit a classification, change it, then use **Next word** to return through the same sequence. The Library also lets you revise an assessment or the latest FSRS rating.
- When you are ready, tap **Learn selected words**. **Study all words** opens the full selected list on one page with German, English, pronunciation, usage, grammar and available bilingual examples. Scroll to the bottom or use the top shortcut on longer lists. Practice starts only when you tap **Start practice**.
- Choose **Balanced**, **German → English**, or **English → German**. The balanced mode alternates translation directions across entries. Recognition, reveal-answer cards and optional written answers use the studied dictionary sense. Context and sentence gaps appear where available. A written flashcard is compared with the reference, then the learner rates their own recall, allowing equivalent phrasing. Scheduled spelling exercises retain their existing checks.
- A short warm-up separates reading from testing. A missed choice or a Hard/Again flashcard returns later with at least two intervening activities. For small sets, short activities use other words. Additional retrieval practice does not change FSRS due dates. Reopen **Study all words** during practice and return to the same activity and submitted answer.
- **Audio recognition** offers replay and speed controls. It selects four German options with distinct phonetic evidence. An unavailable offline German voice or an unsuitable option set switches to a meaning exercise. A standalone listening round leaves FSRS scheduling untouched.
- **Home** shows the personal 6,000-entry TestDaF TDN 4 preparation target. A word counts toward coverage once if previously known, successfully recalled, or estimated mastered. A first Good/Easy recall or a later successful new-word retry drives the editable daily target. The existing statistics screen still distinguishes first-rated words, scheduled successes and estimated mastery. A selected word is queued. A studied word has an actual FSRS rating. Reading a list alone earns neither a learned word nor a review.
- **Everyday Usage** opens a short explanation. The Library sorts by highest usage, lowest usage or alphabetically. [Scoring formula and corpus limitations](data/README.md#everyday-usage-score)

## What the catalog count means

| Level in the source | Distinct headwords |
| --- | ---: |
| A1 | 820 |
| A2 | 938 |
| B1 | 2,351 |
| B2 | 2,437 |
| **Total** | **6,546** |

The catalog joins German dictionary headwords and English senses with German subtitle-corpus ranks. Wordhoard supplies 2,519 anchored level labels and 4,027 estimated labels. A separate FreeDict/Ding comparison found a matching English gloss for 4,821 entries. Of those, 2,055 also have an anchored level. The project still needs a bilingual sense and level audit to meet the **more than 6,000 verified entries** acceptance criterion. See [vocabulary provenance and reproducibility](data/README.md), [the count report](reports/vocabulary.json), and [the translation audit](reports/translation-audit.json).

## Build the project

Open this directory in Android Studio. Select JDK 17 for Gradle, install Android SDK Platform 35, Build Tools 34.0.0 and Platform Tools in SDK Manager, accept the SDK licenses and sync. The first build needs network access to Google Maven, Maven Central and the Gradle distribution. The checked-in Gradle 8.9 wrapper verifies its distribution checksum.

On Windows, from the project root:

```powershell
.\gradlew.bat :app:assembleDebug
.\gradlew.bat :app:testDebugUnitTest
.\gradlew.bat :app:connectedDebugAndroidTest
```

On macOS or Linux, use `./gradlew`. The debug APK is generated at `app/build/outputs/apk/debug/app-debug.apk`. Connected tests need a test emulator or device. The local backend test has an additional opt-in described in [the cloud guide](cloud/README.md). If a synced folder blocks writes to Gradle’s generated files, set `LEXORA_BUILD_DIR` to a local temporary directory before building. Its `outputs/apk/debug` folder then holds the APK.

For a signed release, use **Build → Generate Signed App Bundle or APK → APK → app**, select or create your private keystore outside this project, select **release** and finish. Store the keystore and passwords privately for future updates. The CLI command `./gradlew :app:assembleRelease` produces an unsigned release APK unless you configure your own signing. The delivered file is the debug build.

## Project map

| Location | Responsibility |
| --- | --- |
| `app/src/main/java/org/lexora/study` | Compose screens, AndroidViewModel, Kotlin StateFlow, serialized database operations, study sessions, FSRS adapter, analytics, account and reminder workers |
| `app/src/main/assets/catalog.sqlite` | Prebuilt offline SQLite dictionary |
| `data` and `tools` | Redistributable lexical snapshot, edited lessons, ingestion, cross-checks and validation |
| `app/src/test` and `app/src/androidTest` | JVM, on-device SQLite, UI, WorkManager and local backend tests |
| `cloud` | Optional Firebase setup, emulator configuration and per-user Firestore rules |
| `reports` | Counts, coverage, verification evidence and delivery status |

SQLite stores words, preferences, sessions and progress together so a rating, card update and session advance can commit in one transaction. Each cloud account has its own database. Word IDs are derived from lemma and part of speech. Learning event IDs are UUIDs. Schema version 3 adds reversible action history and views for effective ratings, assessments and active schedules. It migrates databases from versions 1 and 2 without rewriting their vocabulary, ratings or FSRS cards. Schema version 5 adds an indexed table for practice results and confusion evidence. The earlier schema version 4 applies the academic index and sentence corrections to new and existing profiles with a versioned migration. See the [sentence audit](reports/VOCABULARY-AND-EXAMPLES.md) for current coverage. [Implementation status](reports/STATUS.md)

## Learning and statistics

FSRS-6 runs through the pinned upstream Java-FSRS 1.0.0 library. The four ratings, default parameters, learning steps and relearning step come from that model. Fuzzing is disabled so replaying history gives deterministic schedules. Desired retention defaults to 90%. **Fit initial stability from history** estimates up to four initial weights from delayed first recalls. Each rating group needs at least 50 eligible observations and five of each outcome. The app keeps a change only when it improves held-out log loss. Subsequent review equations use the upstream defaults. Each fitted initial value is stored with its event. [Upstream scheduler](https://github.com/open-spaced-repetition/java-fsrs)

A word counts as studied after its first effective FSRS rating. Level coverage combines previously known words and estimated mastered words. Remaining coverage includes unseen, selected, learning and reviewing words. Reassessing a studied word can park its schedule or return it to selection while retaining its historical ratings. The mastery estimate requires review state, at least 30 days of stability and a review that is still within its scheduled interval. Observed success counts Hard, Good and Easy scheduled responses. Estimated retention averages FSRS retrievability over rated cards. New words, observed review success and manual exercises are recorded separately. Study streaks use the local date recorded with each rating. Study time accrues while a study card is in the foreground.

## Data transfer and privacy

- **JSON backup/restore** merges packaged-word and personal-word events by ID. It includes personal entries, favorites, decks and learning preferences. Existing settings take precedence. Conflicting personal edits or event payloads abort the transaction. An unfinished session stays on its originating device. Backups are readable files.
- **CSV** uses UTF-8 with the exact header `german,english,level,pos,article,topic`. It supports commas, quoted fields, escaped quotes and multiline fields. Exports contain personal vocabulary. Imports skip duplicate German headwords and roll back on invalid rows. Nouns require a capitalized headword and article. Other parts of speech use an empty article. See [a sample import](data/personal-example.csv).
- **Anki text** exports personal entries in a six-column tab-separated file with `#separator:tab`, `#html:false` and column headers. Map German and English to the front and back in Anki’s text importer. LEXORA imports the same six-column layout. Binary APKG packages, media, scheduling data and arbitrary note types are outside this importer. The included round-trip test covers LEXORA’s parser and writer. Anki desktop interoperability needs independent verification.
- The offline profile supports confirmed progress and personal-vocabulary resets. Android’s **Clear storage** removes all local profiles and preferences. Export first if you want to keep a copy.
- No microphone, contacts or broad filesystem access is requested. Android's document picker handles imports and exports. Notifications need permission on Android 13+. Internet and network state support optional account and sync features. Reminders use Android’s inexact wake-up alarms and restore eligible schedules after restart.
- The distributed APK has no configured cloud account. A separately configured build can authenticate and sync packaged-word events. Personal words and decks transfer through local backup. [Cloud setup and quotas](cloud/README.md)

## Verification

Run `python tools/test_catalog.py -v` and `python tools/test_v4_content.py -v` for catalog and V4 content checks. Run `python tools/build_catalog.py` to rebuild the catalog offline from the included normalized source snapshot. Raw-source reconstruction and the independent translation audit are documented in [the data guide](data/README.md). The [V5 delivery report](V5_PROGRESS.md) records current checks and remaining limitations. The [V4 report](reports/STATUS.md) retains the earlier release evidence.
