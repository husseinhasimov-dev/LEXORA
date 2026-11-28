package org.lexora.study

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.UUID

internal data class IntelligenceSnapshot(
    val catalog: GrammarCatalog,
    val academy: AcademyData,
    val analytics: AnalyticsBundle,
    val mission: MissionControlBundle,
    val alerts: List<LearningAlert>,
    val proposals: List<StudyPlanProposal>,
    val alertCategoryPrefs: Map<AlertCategory, Boolean>,
)

@Composable
internal fun HomeV61(
    state: ScreenState,
    vm: StudyModel,
    snapshot: IntelligenceSnapshot?,
    onOpenFinder: () -> Unit,
    onDiscover: () -> Unit,
    onScheduled: () -> Unit,
    onResume: () -> Unit,
    onNavigate: (String) -> Unit,
) {
    val stats = state.stats
    val goal = state.prefs["goal"]?.toIntOrNull()?.coerceAtLeast(1) ?: 10
    val available = state.session == null && !state.busy
    val reviewing = state.session?.let { it.mode == Mode.Review || it.batch == "date-review" } == true
    val dateLabel = remember { LocalDate.now().format(DateTimeFormatter.ofPattern("EEE, MMM d", Locale.ENGLISH)) }

    Page {
        // Clean compact header with Feature Finder
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Label("Lexora")
                Text(dateLabel, fontSize = 13.sp, color = faint)
            }
            OutlinedButton(
                onClick = onOpenFinder,
                shape = RoundedCornerShape(12.dp),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
            ) {
                Icon(Icons.Default.Search, contentDescription = "Find feature", tint = blue, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))
                Text("Find", fontSize = 13.sp, color = blue)
            }
        }

        Heading("Your study day")

        // 1. Today's Main Objective
        Sheet(accentBorder = if (state.goalCounts.today >= goal) greenBorder else panelBorder) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Label("Today's Main Objective", color = if (state.goalCounts.today >= goal) greenText else blue)
                if (state.goalCounts.today >= goal) BadgePill("Goal met ✓", color = greenText, background = greenBg)
            }
            Text(
                "${state.goalCounts.today} / $goal words recalled",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
                color = textMain,
            )
            Progress(state.goalCounts.today.toFloat() / goal, color = green)
            Hint("${state.selected.size} in learning list · ${stats.due} FSRS reviews due")
            if (state.session != null && !reviewing) {
                Primary("Continue ${state.session.mode.label.lowercase()}", !state.busy, onClick = onResume)
            } else if (state.selected.isNotEmpty()) {
                Primary("Learn selected words", available) { onResume(); vm.run { startSelected() } }
            } else {
                Primary("Discover new words", available, onClick = onDiscover)
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                if (stats.due > 0) {
                    TextButton(onScheduled, enabled = available) { Text("Review ${stats.due} due") }
                }
                EditDailyGoal(goal, !state.busy) { vm.preference("goal", it.toString()) }
            }
        }

        // 2. Continue Learning (Quick Jump)
        val lastReading = snapshot?.academy?.lastRead?.let { id -> snapshot.academy.readings.find { it.id == id } }
        val nextGrammar = snapshot?.academy?.lessons?.firstOrNull {
            snapshot.academy.progress[it.id]?.status in listOf("Needs Review", "Practice Needed", "In Progress")
        } ?: snapshot?.academy?.lessons?.firstOrNull { snapshot.academy.progress[it.id]?.status != "Mastered" }

        Sheet {
            Label("Continue Learning")
            Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Secondary(
                    lastReading?.let { "Read · ${it.title}" } ?: "Reading Academy",
                    modifier = Modifier.weight(1f).fillMaxHeight(),
                ) {
                    if (lastReading != null) onNavigate("Learn:Read:${lastReading.id}") else onNavigate("Learn:Reading")
                }
                Secondary(
                    nextGrammar?.let { "Grammar · ${it.title}" } ?: "Grammar Academy",
                    modifier = Modifier.weight(1f).fillMaxHeight(),
                ) {
                    if (nextGrammar != null) onNavigate("Learn:Lesson:${nextGrammar.id}") else onNavigate("Learn:Grammar")
                }
            }
        }

        // 3. Today's Tasks Summary
        if (snapshot != null) {
            val quests = snapshot.academy.quests
            val required = quests.filterNot { it.first.bonus }
            val doneCount = required.count { it.second >= it.first.target }
            Sheet {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Label("Today's Tasks")
                    BadgePill(
                        "$doneCount / ${required.size} done",
                        color = if (required.isNotEmpty() && doneCount == required.size) greenText else blue,
                        background = if (required.isNotEmpty() && doneCount == required.size) greenBg else blueSurface,
                    )
                }
                if (required.isEmpty()) {
                    Hint("Rest day or no scheduled quests. Extra study earns unlimited XP.")
                } else {
                    Progress(doneCount.toFloat() / required.size.coerceAtLeast(1), color = green)
                    required.filter { it.second < it.first.target }.take(2).forEach { (q, cur) ->
                        Text("• ${q.title} ($cur/${q.target})", fontSize = 13.sp, color = textMain, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
                TextButton({ onNavigate("Roadmap:Quests") }) { Text("Open all daily quests →") }
            }

            // 4. One Next Smart Recommendation
            val topProp = snapshot.proposals.firstOrNull()
            if (topProp != null) {
                Sheet(accentBorder = blueBorder) {
                    Label("Recommended Next Step")
                    Text(topProp.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, color = textMain)
                    Hint(topProp.rationale)
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Primary("Open", modifier = Modifier.weight(1f)) { onNavigate(topProp.destinationRoute) }
                        Secondary("Smart Plan", modifier = Modifier.weight(1f)) { onNavigate("Roadmap:Recommendations") }
                    }
                }
            }

            // 5. Compact Progress Snapshot Strip (taps navigate directly into Progress)
            val alertCount = snapshot.alerts.count { it.severity == AlertSeverity.HIGH || it.severity == AlertSeverity.MEDIUM }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Metric("${snapshot.analytics.xp.totalXp}", "Total XP", Modifier.weight(1f).clickable { onNavigate("Progress:XP") }, accent = greenText)
                Metric("${snapshot.analytics.activity.currentStreak}d", "Streak", Modifier.weight(1f).clickable { onNavigate("Progress:Analytics") }, accent = blue)
                Metric(
                    snapshot.mission.config.daysUntil(LocalDate.now())?.let { if (it >= 0) "${it}d" else "Past" } ?: "Set",
                    "TestDaF",
                    Modifier.weight(1f).clickable { onNavigate("Progress:TestDaF") },
                    accent = amber,
                )
                Metric(
                    "${snapshot.alerts.size}",
                    if (alertCount > 0) "Alerts ($alertCount!)" else "Alerts",
                    Modifier.weight(1f).clickable { onNavigate("Progress:Alerts") },
                    accent = if (alertCount > 0) coral else textMain,
                )
            }
        }
    }
}

@Composable
internal fun LearnHubV61(
    state: ScreenState,
    vm: StudyModel,
    voice: Voice,
    snapshot: IntelligenceSnapshot?,
    subRoute: String,
    onSubRoute: (String) -> Unit,
    onBegin: (Mode, Filters) -> Unit,
    onResume: () -> Unit,
    previewRequested: Boolean,
    consumePreview: () -> Unit,
    discoverRequested: Boolean,
    consumeDiscovery: () -> Unit,
    academyActions: AcademyActions?,
    onOpenVocabTopic: (String) -> Unit,
) {
    // Sub-screens inside Learn
    when {
        subRoute == "Library" -> {
            Column(Modifier.fillMaxSize()) {
                TextButton({ onSubRoute("Vocab") }, Modifier.padding(horizontal = 12.dp, vertical = 4.dp)) { Text("‹ Back to Learn") }
                Library(state, vm, voice) { onSubRoute("Academic") }
            }
            return
        }
        subRoute == "Academic" -> {
            AcademicScreen(state, vm, voice) { onSubRoute("Vocab") }
            return
        }
        subRoute == "Explore" -> {
            ExploreScreen(state, vm, voice) { onSubRoute("Skills") }
            return
        }
        snapshot != null && academyActions != null -> {
            val d = snapshot.academy
            when {
                subRoute == "Reading" -> { ReadingBuilder(d, academyActions); return }
                subRoute.startsWith("Read:") -> {
                    d.readings.find { it.id == subRoute.substringAfter(':') }?.let {
                        Reader(d, academyActions, voice, it); return
                    }
                }
                subRoute == "Inbox" -> { InboxScreen(d, academyActions); return }
                subRoute == "DiscoverAcademy" -> { AcademyDiscover(d, academyActions); return }
                subRoute == "Grammar" -> {
                    GrammarHubScreen(d, snapshot.catalog.guides, academyActions, onOpenVocabTopic); return
                }
                subRoute.startsWith("Lesson:") -> {
                    d.lessons.find { it.id == subRoute.substringAfter(':') }?.let { l ->
                        ExpandedLessonScreen(d, l, snapshot.catalog.guides[l.id], academyActions, onOpenVocabTopic); return
                    }
                }
                subRoute.startsWith("Quiz:") -> {
                    val parts = subRoute.substringAfter(':').split('/')
                    if (parts.size >= 3) {
                        val (skill, id, mode) = parts
                        val activity = if (skill == "Reading") {
                            d.readings.first { it.id == id }.activity()
                        } else {
                            val lesson = d.lessons.first { it.id == id }
                            lesson.expandedActivity(mode, snapshot.catalog.guides[id])
                        }
                        AcademyQuiz(d, academyActions, activity)
                        return
                    }
                }
                subRoute == "Recovery" -> { RecoveryScreen(d, academyActions); return }
            }
        }
    }

    var section by rememberSaveable(subRoute) {
        mutableStateOf(if (subRoute in listOf("Vocab", "ReadingTab", "GrammarTab", "Skills")) subRoute else "Vocab")
    }
    var dateReview by rememberSaveable { mutableStateOf(false) }
    var level by rememberSaveable { mutableStateOf("All") }
    var deck by rememberSaveable { mutableStateOf("All") }
    var status by rememberSaveable { mutableStateOf("All") }
    var topic by rememberSaveable { mutableStateOf("All") }
    val available = state.session == null && !state.busy

    Page {
        Label("Learn")
        Heading("Study Hub")
        ChipStrip(
            options = listOf("Vocabulary", "Reading", "Grammar", "Skills & Explore"),
            selected = when (section) {
                "ReadingTab" -> "Reading"
                "GrammarTab" -> "Grammar"
                "Skills" -> "Skills & Explore"
                else -> "Vocabulary"
            },
            onSelect = {
                section = when (it) {
                    "Reading" -> "ReadingTab"
                    "Grammar" -> "GrammarTab"
                    "Skills & Explore" -> "Skills"
                    else -> "Vocab"
                }
            },
        )

        if (state.session != null) {
            Sheet(accentBorder = blueBorder) {
                Label("Active Session")
                Text(state.session.mode.label, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Primary("Resume Session", !state.busy, onClick = onResume)
                var confirm by remember { mutableStateOf(false) }
                TextButton({ confirm = true }) { Text("End session") }
                if (confirm) Confirm("End this session?", "Completed ratings and answers are already saved.", { confirm = false }) { vm.run { save(null); null } }
            }
        }

        when (section) {
            "Vocab" -> {
                DailyLearning(state, vm, onResume, onBegin, previewRequested, consumePreview, discoverRequested, consumeDiscovery)
                Sheet {
                    Label("Vocabulary Tools")
                    Secondary("Vocabulary Library (${state.stats.total} words)") { onSubRoute("Library") }
                    Secondary("TestDaF Academic Dictionary") { onSubRoute("Academic") }
                    Secondary("Unknown Words Inbox (${snapshot?.academy?.inbox?.count { !it.archived } ?: 0})") { onSubRoute("Inbox") }
                    Secondary("Review by Study Date", available) { dateReview = true }
                    Secondary("Filter & Batch Discover") { onSubRoute("DiscoverAcademy") }
                }
                ExpandableInfo("More vocabulary modes", "Quick 5-word drills, Custom filtered decks, and Placement assessment.")
                Sheet {
                    Label("Custom & Quick Practice")
                    Pick("Time limit (min)", state.prefs["duration"] ?: "0", listOf("0", "5", "10", "15")) { vm.preference("duration", it) }
                    Secondary("Quick Study (5 words)", available) { onBegin(Mode.Quick, Filters()) }
                    Pick("Level", level, listOf("All") + levels) { level = it }
                    Pick("Group", status, listOf("All", "Favorites", "Missed", "Learning", "Known")) { status = it }
                    Pick("Topic", topic, listOf("All") + state.topics) { topic = it }
                    if (state.decks.isNotEmpty()) Pick("Deck", deck, listOf("All") + state.decks) { deck = it }
                    Secondary("Start Custom Study", available) { onBegin(Mode.Custom, Filters(level = level, deck = deck, topic = topic, status = status)) }
                }
            }

            "ReadingTab" -> {
                val r = snapshot?.analytics?.reading
                Sheet {
                    Label("Reading Academy")
                    Text("${snapshot?.academy?.readings?.size ?: 17} graded German passages (A1–B2)", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Hint("Tap words to inspect or save to your Inbox, resume by paragraph, and check comprehension.")
                    if (r != null) {
                        Hint("Completed: ${r.passagesCompleted} · Words read: ${r.totalWordsRead} · Avg comprehension: ${r.averageComprehensionPct?.let { "$it%" } ?: "—"}")
                    }
                    Primary("Open Reading Academy") { onSubRoute("Reading") }
                    Secondary("Unknown Words Inbox") { onSubRoute("Inbox") }
                    Secondary("Mistake Recovery") { onSubRoute("Recovery") }
                }
            }

            "GrammarTab" -> {
                val g = snapshot?.analytics?.grammar
                Sheet {
                    Label("Expanded Grammar Academy")
                    Text("${snapshot?.catalog?.totalTopics ?: 50} topics · ${snapshot?.catalog?.totalExercises ?: 376} validated exercises", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Hint("Deep English explanations, positive/negative contrasts, conjugation & declension tables, and interactive revision worksheets.")
                    if (g != null) {
                        Hint("Mastered: ${g.masteryCounts["Mastered"] ?: 0}/${g.totalTopics} · Accuracy: ${g.overallAccuracyPct?.let { "$it%" } ?: "—"}")
                    }
                    Primary("Open Grammar Academy & Resource Center") { onSubRoute("Grammar") }
                }
            }

            "Skills" -> {
                Sheet {
                    Label("Listening, Context & Word Skills")
                    Text("Practice & Explore", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Hint("Listening in Context, Context Mastery, Confusion Lab, and Word Families.")
                    Primary("Open Practice & Explore") { onSubRoute("Explore") }
                    Secondary("Mistake Recovery Hub") { onSubRoute("Recovery") }
                }
                Sheet {
                    Label("Speaking & Writing Practice")
                    Hint("Log structured Digital TestDaF Speaking or Writing practice & rubric assessments in TestDaF Mission Control.")
                    Secondary("Open Speaking / Writing in TestDaF") { onSubRoute("JumpTestDaF") }
                }
            }
        }
    }

    if (dateReview) {
        ReviewChooser({ dateReview = false }) { range, order, size, from, to ->
            onResume(); vm.run { startDateReview(range, order, size, from, to) }
        }
    }
}

@Composable
internal fun RoadmapHubV61(
    snapshot: IntelligenceSnapshot?,
    initialSection: String,
    academyActions: AcademyActions?,
    onAcceptProposal: (StudyPlanProposal, Boolean) -> Unit,
    onRejectProposal: (StudyPlanProposal) -> Unit,
    onNavigate: (String) -> Unit,
) {
    var section by rememberSaveable(initialSection) { mutableStateOf(initialSection) }
    var level by rememberSaveable { mutableStateOf("A1") }

    if (snapshot == null || academyActions == null) {
        Page { Heading("Roadmap"); CircularProgressIndicator() }
        return
    }

    if (section == "Quests") {
        Column(Modifier.fillMaxSize()) {
            Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                ChipStrip(listOf("Roadmap", "Quests", "Smart Plan"), "Quests") { section = it }
            }
            QuestsScreen(snapshot.academy, academyActions)
        }
        return
    }

    Page {
        Label("Plan & Progress")
        Heading("Roadmap & Plan")
        ChipStrip(listOf("Roadmap", "Quests", "Smart Plan"), section) { section = it }

        if (section == "Roadmap") {
            val d = snapshot.academy
            ChipStrip(levels, level) { level = it }
            val group = d.lessons.filter { it.level == level }
            val mastered = group.count { d.progress[it.id]?.status == "Mastered" }
            Sheet {
                Label("$level Grammar Path")
                BreakdownBarRow("$level Topics Mastered", "$mastered / ${group.size}", mastered.toFloat() / group.size.coerceAtLeast(1), barColor = green)
                val next = group.firstOrNull { d.progress[it.id]?.status in listOf("Needs Review", "Practice Needed") }
                    ?: group.firstOrNull { d.progress[it.id]?.status != "Mastered" }
                if (next != null) {
                    Primary("Continue · ${next.title}") { onNavigate("Learn:Lesson:${next.id}") }
                }
            }
            group.forEach { l ->
                val p = d.progress[l.id] ?: TopicProgress("Not Started", false, null, null, null, 0L, 0)
                Sheet {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(l.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, color = textMain)
                            Hint(d.lessons.find { it.id == l.prerequisite }?.let { "Prerequisite: ${it.title}" } ?: "Start here")
                        }
                        StatusPill(p.status)
                    }
                    Secondary("Open Lesson") { onNavigate("Learn:Lesson:${l.id}") }
                }
            }
            Sheet {
                Label("Four-Skill Practice Shortcuts")
                Secondary("Vocabulary Discovery") { onNavigate("Learn:Vocab") }
                Secondary("Reading Comprehension") { onNavigate("Learn:Reading") }
                Secondary("Listening in Context") { onNavigate("Learn:Explore") }
                Secondary("TestDaF Speaking & Writing") { onNavigate("Progress:TestDaF") }
            }
        } else {
            // Smart Plan Recommendations
            Sheet(background = panelSubtle) {
                Label("Adaptive Study Plan Proposals")
                Hint("Generated locally from your Risk Engine, FSRS backlog, and assessment evidence. Proposals never override your schedule or rest days without your approval.")
            }
            if (snapshot.proposals.isEmpty()) {
                EmptyStateCard(
                    "No pending plan adjustments",
                    "Your current plan is aligned with your recorded data, or you have already reviewed all current suggestions.",
                    "Open Daily Quests",
                ) { section = "Quests" }
            } else {
                snapshot.proposals.forEach { prop ->
                    Sheet(accentBorder = blueBorder) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                            BadgePill(prop.skill)
                            BadgePill("Target: ${prop.target}", color = greenText, background = greenBg)
                        }
                        Text(prop.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, color = textMain)
                        Text(prop.rationale, fontSize = 14.sp, color = textMain)
                        Hint(prop.impactNote)
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            SuccessPrimary("Accept", modifier = Modifier.weight(1f)) { onAcceptProposal(prop, false) }
                            Secondary("Bonus", modifier = Modifier.weight(1f)) { onAcceptProposal(prop, true) }
                        }
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            TextButton({ onNavigate(prop.destinationRoute) }) { Text("Open skill") }
                            TextButton({ onRejectProposal(prop) }) { Text("Reject", color = faint) }
                        }
                    }
                }
            }
        }
    }
}

@Composable
internal fun ProgressHubV61(
    state: ScreenState,
    snapshot: IntelligenceSnapshot?,
    initialSection: String,
    analyticsWindow: AnalyticsWindow,
    customFrom: LocalDate,
    customTo: LocalDate,
    onWindowChange: (AnalyticsWindow) -> Unit,
    onCustomRangeChange: (LocalDate, LocalDate) -> Unit,
    onSelectCalendarDay: (String) -> Unit,
    onOpenWord: (String) -> Unit,
    onLogManualStudy: (String, Int, String) -> Unit,
    onSaveExamConfig: (TestDaFConfig) -> Unit,
    onAddAssessment: (TestDaFAssessmentRecord) -> Unit,
    onDeleteAssessment: (String) -> Unit,
    onToggleAlertPref: (AlertCategory, Boolean) -> Unit,
    onAcknowledgeAlert: (String) -> Unit,
    onDismissAlert: (String) -> Unit,
    onRestoreAlerts: () -> Unit,
    journalFrom: String,
    journalTo: String,
    onJournalRange: (String, String) -> Unit,
    academyActions: AcademyActions?,
    onNavigate: (String) -> Unit,
) {
    var section by rememberSaveable(initialSection) { mutableStateOf(initialSection) }
    val today = remember { LocalDate.now() }

    if (snapshot == null || academyActions == null) {
        Page { Heading("Progress"); CircularProgressIndicator() }
        return
    }

    if (section == "Journal") {
        Column(Modifier.fillMaxSize()) {
            Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                ChipStrip(listOf("Analytics", "XP & Growth", "Skills", "TestDaF", "Alerts", "Journal"), "Journal") { section = it }
            }
            JournalScreen(snapshot.academy, academyActions, journalFrom, journalTo, onJournalRange)
        }
        return
    }

    Page {
        Label("Learning Intelligence")
        Heading("Progress")
        ChipStrip(
            options = listOf("Analytics", "XP & Growth", "Skills", "TestDaF", "Alerts", "Journal"),
            selected = section,
            onSelect = { section = it },
        )

        when (section) {
            "Analytics" -> AnalyticsOverviewTab(
                analytics = snapshot.analytics,
                window = analyticsWindow,
                customFrom = customFrom,
                customTo = customTo,
                onWindowChange = onWindowChange,
                onCustomRangeChange = onCustomRangeChange,
                state = state,
                onSelectCalendarDay = onSelectCalendarDay,
                onOpenWord = onOpenWord,
                onLogManualStudy = onLogManualStudy,
            )
            "XP & Growth" -> XpAndVelocityTab(snapshot.analytics, snapshot.mission)
            "Skills" -> SkillsAnalyticsTab(snapshot.analytics)
            "TestDaF" -> TestDaFMissionControlTab(
                mission = snapshot.mission,
                today = today,
                onSaveConfig = onSaveExamConfig,
                onAddAssessment = onAddAssessment,
                onDeleteAssessment = onDeleteAssessment,
            )
            "Alerts" -> AlertsCenterTab(
                alerts = snapshot.alerts,
                categoryPrefs = snapshot.alertCategoryPrefs,
                onToggleCategoryPref = onToggleAlertPref,
                onAcknowledge = onAcknowledgeAlert,
                onDismiss = onDismissAlert,
                onRestoreDismissed = onRestoreAlerts,
                onNavigate = onNavigate,
            )
        }
    }
}

private data class FeatureShortcut(val title: String, val subtitle: String, val route: String, val keywords: String)

private val allFeatureShortcuts = listOf(
    FeatureShortcut("Daily Vocabulary", "Discover & learn new German words", "Learn:Vocab", "words new discover flashcard"),
    FeatureShortcut("Scheduled FSRS Reviews", "Review due spaced-repetition cards", "Learn:Review", "fsrs due cards memory"),
    FeatureShortcut("Vocabulary Library", "Browse and filter 6,500+ words", "Learn:Library", "dictionary search words"),
    FeatureShortcut("TestDaF Academic Dictionary", "University & academic vocabulary", "Learn:Academic", "academic university words"),
    FeatureShortcut("Unknown Words Inbox", "Words saved while reading", "Learn:Inbox", "saved reading words"),
    FeatureShortcut("Reading Academy", "A1–B2 German passages & comprehension", "Learn:Reading", "passages texts stories"),
    FeatureShortcut("Grammar Academy", "50 topics, guides, drills & mastery", "Learn:Grammar", "grammar rules exercises"),
    FeatureShortcut("Grammar Reference Tables", "Articles, cases, verbs & connectors", "Learn:Grammar", "conjugation declension table"),
    FeatureShortcut("Listening & Explore", "Listening in Context, Confusion Lab, Families", "Learn:Explore", "audio listening confusion"),
    FeatureShortcut("Mistake Lab & Recovery", "Repair missed vocabulary & questions", "Learn:Lab", "errors wrong mistakes"),
    FeatureShortcut("Learning Roadmap", "A1–B2 structured progression", "Roadmap:Roadmap", "path curriculum cefr"),
    FeatureShortcut("Daily Quests & Schedule", "Daily goals, weekly minutes, rest days", "Roadmap:Quests", "tasks plan schedule"),
    FeatureShortcut("Smart Study Plan", "Adaptive proposals from Risk Engine", "Roadmap:Recommendations", "suggestions adaptive"),
    FeatureShortcut("Study Activity Analytics", "Time, streaks, calendar & table", "Progress:Analytics", "stats time calendar"),
    FeatureShortcut("XP & Learning Velocity", "Unlimited XP, XP growth & velocity", "Progress:XP", "xp level growth trend"),
    FeatureShortcut("Skill Analytics", "Vocabulary, Reading, Grammar, L/S/W", "Progress:Skills", "skills accuracy retention"),
    FeatureShortcut("TestDaF Mission Control", "4-skill TDN4 readiness, Risk Engine & Forecast", "Progress:TestDaF", "exam tdn4 risk forecast countdown"),
    FeatureShortcut("Learning Alerts Center", "Performance, planning, exam & positive insights", "Progress:Alerts", "warnings notifications alerts"),
    FeatureShortcut("Learning Journal", "Activity log, notes & achievements", "Progress:Journal", "notes history log"),
    FeatureShortcut("Profile, Notifications & Backup", "Goals, reminders, local JSON/CSV backup", "Profile:Main", "settings export restore notifications"),
)

@Composable
internal fun FeatureFinderDialog(onDismiss: () -> Unit, onSelect: (String) -> Unit) {
    var query by rememberSaveable { mutableStateOf("") }
    val hits = remember(query) {
        if (query.isBlank()) allFeatureShortcuts
        else allFeatureShortcuts.filter {
            it.title.contains(query, true) || it.subtitle.contains(query, true) || it.keywords.contains(query, true)
        }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Find a Feature") },
        text = {
            Column(Modifier.fillMaxWidth().heightIn(max = 440.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    label = { Text("Search (e.g. TestDaF, Grammar, Alerts)") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                )
                Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    hits.forEach { item ->
                        Column(
                            Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(12.dp))
                                .background(panelSubtle)
                                .border(1.dp, panelBorder, RoundedCornerShape(12.dp))
                                .clickable { onSelect(item.route); onDismiss() }
                                .padding(horizontal = 12.dp, vertical = 10.dp),
                        ) {
                            Text(item.title, fontWeight = FontWeight.SemiBold, color = blue, fontSize = 14.sp)
                            Text(item.subtitle, color = faint, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                    if (hits.isEmpty()) Hint("No matching features found.")
                }
            }
        },
        confirmButton = { TextButton(onDismiss) { Text("Close") } },
    )
}
