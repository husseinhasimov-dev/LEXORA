package org.lexora.study

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.UUID

class MainActivity : ComponentActivity() {
    private val model: StudyModel by viewModels()
    private lateinit var voice: Voice
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = androidx.activity.SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = androidx.activity.SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
        )
        voice = Voice(this)
        setContent { LexoraApp(model, voice) }
    }
    override fun onDestroy() { voice.close(); super.onDestroy() }
}

@Composable
fun LexoraApp(model: StudyModel, voice: Voice) {
    MaterialTheme(colorScheme = LexoraColorScheme) {
        App(model, voice)
    }
}

@Composable
fun App(vm: StudyModel, voice: Voice) {
    val state by vm.state.collectAsStateWithLifecycle()
    var tab by rememberSaveable { mutableStateOf("Home") }
    var learnRoute by rememberSaveable { mutableStateOf("Vocab") }
    var learnBackStack by rememberSaveable { mutableStateOf(listOf<String>()) }
    var roadmapSection by rememberSaveable { mutableStateOf("Roadmap") }
    var progressSection by rememberSaveable { mutableStateOf("Analytics") }
    var practice by rememberSaveable { mutableStateOf(false) }
    var discoverRequested by rememberSaveable { mutableStateOf(false) }
    var previewRequested by rememberSaveable { mutableStateOf(false) }
    var openedWelcome by rememberSaveable { mutableStateOf(true) }
    var showFinder by rememberSaveable { mutableStateOf(false) }

    // Intelligence & Academy shared state
    var revision by remember { mutableIntStateOf(0) }
    val writeLock = remember { Mutex() }
    val scope = rememberCoroutineScope()
    var academyError by rememberSaveable { mutableStateOf("") }
    var analyticsWindow by rememberSaveable { mutableStateOf(AnalyticsWindow.LAST_30) }
    var customFromStr by rememberSaveable { mutableStateOf(LocalDate.now().minusDays(13).toString()) }
    var customToStr by rememberSaveable { mutableStateOf(LocalDate.now().toString()) }
    var journalFrom by rememberSaveable { mutableStateOf(LocalDate.now().minusDays(6).toString()) }
    var journalTo by rememberSaveable { mutableStateOf(LocalDate.now().toString()) }

    val owner = Cloud.uid()
    var resourcesCache by remember(owner) { mutableStateOf<Triple<List<Reading>, GrammarCatalog, ReadingIndex>?>(null) }
    var wordVersion by remember { mutableLongStateOf(-1L) }
    var snapshot by remember { mutableStateOf<IntelligenceSnapshot?>(null) }

    LaunchedEffect(
        state.ready, owner, revision, state.stats.started, state.stats.due, state.stats.todayReview,
        analyticsWindow, customFromStr, customToStr, journalFrom, journalTo,
    ) {
        if (!state.ready) return@LaunchedEffect
        withContext(Dispatchers.Main.immediate) {
            try {
                val old = resourcesCache
                val ver = vm.academyQuery { db.long("SELECT coalesce((SELECT max(rowid) FROM event),0)*1000000+(SELECT count(*) FROM word)") }
                val res = if (old != null && ver == wordVersion) old else vm.academyQuery {
                    Triple(
                        old?.first ?: OfflineReadings.passages(vm.app),
                        old?.second ?: loadGrammarCatalog(vm.app),
                        readingIndex(),
                    )
                }.also { resourcesCache = it; wordVersion = ver }
                val customFrom = runCatching { LocalDate.parse(customFromStr) }.getOrDefault(LocalDate.now().minusDays(13))
                val customTo = runCatching { LocalDate.parse(customToStr) }.getOrDefault(LocalDate.now())
                snapshot = vm.academyQuery {
                    val acData = academyData(res.first, res.second.lessons, res.third, journalFrom, journalTo)
                    val an = computeAnalytics(analyticsWindow, res.first, res.second.lessons, res.second.guides, customFrom, customTo)
                    val mc = computeMissionControl(an, res.first)
                    val today = LocalDate.parse(day())
                    val pendingQuests = acData.quests.count { !it.first.bonus && it.second < it.first.target }
                    val alerts = generateLearningAlerts(an, mc, alertStates(), today, pendingQuests)
                    val proposals = generateStudyPlanProposals(an, mc, proposalDecisions(), today)
                    val catPrefs = AlertCategory.entries.associateWith { cat ->
                        val v = pref(alertCategoryPrefKey(cat))
                        if (v.isEmpty()) cat == AlertCategory.EXAM || cat == AlertCategory.STUDY_PLANNING else v == "true"
                    }
                    IntelligenceSnapshot(res.second, acData, an, mc, alerts, proposals, catPrefs)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                academyError = e.localizedMessage ?: "Could not load learning intelligence"
            }
        }
    }

    fun writeAcademy(op: StudyRepository.() -> Unit, done: () -> Unit = {}) {
        scope.launch(Dispatchers.Main.immediate) {
            writeLock.withLock {
                try {
                    vm.academyQuery { db.tx { op() } }
                    revision++
                    vm.run { null }
                    done()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    academyError = e.localizedMessage ?: "Could not save"
                }
            }
        }
    }

    val bars = listOf(
        "Home" to Icons.Default.Home,
        "Learn" to Icons.Default.PlayArrow,
        "Roadmap" to Icons.Default.Star,
        "Progress" to Icons.Default.DateRange,
        "Profile" to Icons.Default.Person,
    )

    val session = state.session
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val active = if (practice && tab == "Learn") session?.current else null
    DisposableEffect(active, lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> vm.timing(active)
                Lifecycle.Event.ON_PAUSE -> vm.timing(null)
                else -> Unit
            }
        }
        if (lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) vm.timing(active)
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer); vm.timing(null) }
    }
    LaunchedEffect(state.ready, session == null) { if (state.ready && session == null && !state.busy) practice = false }

    fun begin(mode: Mode, f: Filters = Filters()) {
        if (state.busy) return
        tab = "Learn"; practice = true; vm.start(mode, f)
    }

    fun pushLearn(next: String) {
        if (next == "JumpTestDaF") {
            tab = "Progress"; progressSection = "TestDaF"; return
        }
        if (learnRoute != next) {
            learnBackStack = (learnBackStack + learnRoute).takeLast(12)
            learnRoute = next
        }
    }

    fun popLearn() {
        voice.stop()
        if (learnBackStack.isNotEmpty()) {
            learnRoute = learnBackStack.last()
            learnBackStack = learnBackStack.dropLast(1)
        } else {
            learnRoute = "Vocab"
        }
    }

    fun navigate(route: String) {
        practice = false
        voice.stop()
        val head = route.substringBefore(':')
        val tail = route.substringAfter(':', "")
        when (head) {
            "Home" -> tab = "Home"
            "Learn" -> {
                tab = "Learn"
                when (tail) {
                    "Review" -> begin(Mode.Review)
                    "Lab" -> begin(Mode.Lab)
                    "Vocab", "" -> { learnBackStack = emptyList(); learnRoute = "Vocab" }
                    "Library", "Academic", "Explore", "Reading", "Grammar", "Inbox", "DiscoverAcademy", "Recovery" -> pushLearn(tail)
                    else -> if (tail.startsWith("Read:") || tail.startsWith("Lesson:") || tail.startsWith("Quiz:")) pushLearn(tail)
                }
            }
            "Roadmap" -> {
                tab = "Roadmap"
                roadmapSection = when (tail) {
                    "Quests" -> "Quests"
                    "Recommendations", "Smart Plan" -> "Smart Plan"
                    else -> "Roadmap"
                }
            }
            "Progress" -> {
                tab = "Progress"
                progressSection = when (tail) {
                    "XP" -> "XP & Growth"
                    "Skills" -> "Skills"
                    "TestDaF" -> "TestDaF"
                    "Alerts" -> "Alerts"
                    "Journal" -> "Journal"
                    else -> "Analytics"
                }
            }
            "Profile" -> tab = "Profile"
        }
    }

    val academyActions = remember {
        AcademyActions(
            go = { next ->
                when (next) {
                    "Quests" -> navigate("Roadmap:Quests")
                    "Journal" -> navigate("Progress:Journal")
                    "Discover" -> { tab = "Learn"; pushLearn("DiscoverAcademy") }
                    else -> { tab = "Learn"; pushLearn(next) }
                }
            },
            back = { popLearn() },
            edit = { op, done -> writeAcademy(op, done) },
            study = { tab = "Learn"; practice = true },
            detail = vm::detail,
            explore = { navigate("Learn:Explore") },
            lab = { begin(Mode.Lab) },
            review = { begin(Mode.Review) },
        )
    }

    BackHandler(enabled = state.detail != null || practice || (tab == "Learn" && learnRoute != "Vocab") || tab != "Home") {
        when {
            state.detail != null -> vm.detail(null)
            practice -> practice = false
            tab == "Learn" && learnRoute != "Vocab" -> popLearn()
            else -> tab = "Home"
        }
    }

    Scaffold(
        containerColor = ink,
        bottomBar = {
            if (!practice || session == null) {
                NavigationBar(containerColor = Color(0xff101b29)) {
                    bars.forEach { (name, icon) ->
                        val selected = name == tab
                        NavigationBarItem(
                            selected = selected,
                            onClick = {
                                voice.stop()
                                practice = false
                                if (name == tab && name == "Learn") {
                                    learnBackStack = emptyList()
                                    learnRoute = "Vocab"
                                }
                                tab = name
                            },
                            icon = { Icon(icon, contentDescription = name) },
                            label = { Text(name, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                            colors = NavigationBarItemDefaults.colors(
                                indicatorColor = blueSurface,
                                selectedIconColor = blue,
                                selectedTextColor = blue,
                                unselectedIconColor = faint,
                                unselectedTextColor = faint,
                            ),
                        )
                    }
                }
            }
        },
    ) { inner ->
        Box(Modifier.fillMaxSize().padding(inner)) {
            if (!state.ready) {
                Box(Modifier.align(Alignment.Center).padding(24.dp)) {
                    if (state.error.isEmpty()) CircularProgressIndicator(color = blue)
                    else Primary("Retry opening library") { vm.clear(); vm.run { null } }
                }
            } else {
                when (tab) {
                    "Home" -> HomeV61(
                        state = state,
                        vm = vm,
                        snapshot = snapshot,
                        onOpenFinder = { showFinder = true },
                        onDiscover = { tab = "Learn"; learnRoute = "Vocab"; practice = false; discoverRequested = true },
                        onScheduled = { begin(Mode.Review) },
                        onResume = { tab = "Learn"; practice = true },
                        onNavigate = ::navigate,
                    )
                    "Learn" -> if (practice && session != null && state.word != null) {
                        Exercise(state, vm, voice) { practice = false }
                    } else {
                        LearnHubV61(
                            state = state,
                            vm = vm,
                            voice = voice,
                            snapshot = snapshot,
                            subRoute = learnRoute,
                            onSubRoute = ::pushLearn,
                            onBegin = ::begin,
                            onResume = { practice = true },
                            previewRequested = previewRequested,
                            consumePreview = { previewRequested = false },
                            discoverRequested = discoverRequested,
                            consumeDiscovery = { discoverRequested = false },
                            academyActions = academyActions,
                            onOpenVocabTopic = { topicName ->
                                vm.filters(Filters(topic = topicName))
                                pushLearn("Library")
                            },
                        )
                    }
                    "Roadmap" -> RoadmapHubV61(
                        snapshot = snapshot,
                        initialSection = roadmapSection,
                        academyActions = academyActions,
                        onAcceptProposal = { p, bonus -> writeAcademy({ acceptProposal(p, bonus) }) },
                        onRejectProposal = { p -> writeAcademy({ rejectProposal(p) }) },
                        onNavigate = ::navigate,
                    )
                    "Progress" -> ProgressHubV61(
                        state = state,
                        snapshot = snapshot,
                        initialSection = progressSection,
                        analyticsWindow = analyticsWindow,
                        customFrom = runCatching { LocalDate.parse(customFromStr) }.getOrDefault(LocalDate.now().minusDays(13)),
                        customTo = runCatching { LocalDate.parse(customToStr) }.getOrDefault(LocalDate.now()),
                        onWindowChange = { analyticsWindow = it },
                        onCustomRangeChange = { f, t -> customFromStr = f.toString(); customToStr = t.toString() },
                        onSelectCalendarDay = vm::selectCalendarDay,
                        onOpenWord = vm::detail,
                        onLogManualStudy = { skill, mins, note ->
                            writeAcademy({
                                record(
                                    Evidence(
                                        id = "manual/${UUID.randomUUID()}",
                                        skill = skill,
                                        activity = "Reported task",
                                        content = note.ifBlank { "$skill practice ($mins min)" },
                                        method = "self-reported",
                                        activeMs = mins * 60_000L,
                                        answer = note.takeIf { it.isNotBlank() },
                                    ),
                                )
                            })
                        },
                        onSaveExamConfig = { cfg -> writeAcademy({ saveTestDaFConfig(cfg) }) },
                        onAddAssessment = { rec -> writeAcademy({ saveTestDaFAssessment(rec) }) },
                        onDeleteAssessment = { id -> writeAcademy({ deleteTestDaFAssessment(id) }) },
                        onToggleAlertPref = { cat, enabled -> writeAcademy({ set(alertCategoryPrefKey(cat), enabled.toString()) }) },
                        onAcknowledgeAlert = { fp -> writeAcademy({ setAlertState(fp, "acknowledged") }) },
                        onDismissAlert = { fp -> writeAcademy({ setAlertState(fp, "dismissed") }) },
                        onRestoreAlerts = { writeAcademy({ clearAlertStates() }) },
                        journalFrom = journalFrom,
                        journalTo = journalTo,
                        onJournalRange = { f, t -> journalFrom = f; journalTo = t },
                        academyActions = academyActions,
                        onNavigate = ::navigate,
                    )
                    "Profile" -> Profile(state, vm) { begin(Mode.Placement) }
                }
            }
        }
    }

    if (showFinder) {
        FeatureFinderDialog({ showFinder = false }) { route -> navigate(route) }
    }

    if (state.ready && state.prefs["welcome"] == "true" && openedWelcome) {
        AlertDialog(
            onDismissRequest = { openedWelcome = false; vm.preference("welcome", "false") },
            title = { Text("Where would you like to start?") },
            text = { Text("Try eight vocabulary questions to choose a starting level, or begin at A1. You can change your level in Profile.") },
            confirmButton = {
                TextButton({ openedWelcome = false; vm.preference("welcome", "false"); begin(Mode.Placement) }) {
                    Text("Try assessment")
                }
            },
            dismissButton = {
                TextButton({ openedWelcome = false; vm.preference("welcome", "false") }) { Text("Start at A1") }
            },
        )
    }

    if (state.detail != null) WordDetail(state, vm, voice)

    val message = state.error.ifEmpty { state.notice }.ifEmpty { academyError }
    if (message.isNotEmpty()) {
        AlertDialog(
            onDismissRequest = { vm.clear(); academyError = "" },
            title = { Text(if (state.error.isNotEmpty() || academyError.isNotEmpty()) "Couldn’t finish" else "Study update") },
            text = { Text(message) },
            confirmButton = {
                TextButton({
                    vm.clear(); academyError = ""
                    if (message == selectionReady && state.selected.isNotEmpty()) {
                        tab = "Learn"; learnRoute = "Vocab"; practice = false; previewRequested = true
                    }
                }) { Text(if (message == selectionReady && state.selected.isNotEmpty()) "Review selection" else "Done") }
            },
        )
    }
}

// Preserved composable entry points for existing UI tests & compatibility
@Composable
fun Home(
    state: ScreenState,
    vm: StudyModel,
    discover: () -> Unit,
    scheduled: () -> Unit,
    resume: () -> Unit,
    academic: () -> Unit,
    explore: () -> Unit = {},
    openStats: () -> Unit = {},
    academy: () -> Unit = {},
) {
    val stats = state.stats
    val goal = state.prefs.getValue("goal").toInt()
    val available = state.session == null && !state.busy
    val reviewing = state.session?.let { it.mode == Mode.Review || it.batch == "date-review" } == true
    var dateReview by rememberSaveable { mutableStateOf(false) }
    Page {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Label("Lexora"); Hint(LocalDate.now().format(DateTimeFormatter.ofPattern("EEE, MMM d", Locale.ENGLISH)))
        }
        Heading("Your study day")
        Sheet {
            Text("Today’s words", style = MaterialTheme.typography.headlineSmall)
            Text("${state.goalCounts.today} / $goal successfully recalled", style = MaterialTheme.typography.titleMedium)
            Progress(state.goalCounts.today.toFloat() / goal)
            if (state.session != null && !reviewing) Primary("Continue ${state.session.mode.label.lowercase()}", !state.busy, onClick = resume)
            else if (state.selected.isNotEmpty()) Primary("Learn selected words", available) { resume(); vm.run { startSelected() } }
            else Primary("Discover words", available, onClick = discover)
            EditDailyGoal(goal, !state.busy) { vm.preference("goal", it.toString()) }
        }
        Sheet {
            Text("Review words", style = MaterialTheme.typography.headlineSmall)
            if (reviewing) Primary("Continue review", !state.busy, onClick = resume) else Primary("Review by date", available) { dateReview = true }
            Secondary("Scheduled reviews · ${stats.due} due", available && stats.due > 0, onClick = scheduled)
        }
        Secondary("Learning Academy", onClick = academy)
        Secondary("TestDaF Academic Dictionary", onClick = academic)
        Secondary("Practice & Explore", onClick = explore)
        Secondary("Study calendar & daily table", onClick = openStats)
        PreparationGoal(state.goalCounts)
    }
    if (dateReview) ReviewChooser({ dateReview = false }) { range, order, size, from, to ->
        resume(); vm.run { startDateReview(range, order, size, from, to) }
    }
}

@Composable
fun Learn(
    state: ScreenState,
    vm: StudyModel,
    begin: (Mode, Filters) -> Unit,
    resume: () -> Unit,
    previewRequested: Boolean,
    consumePreview: () -> Unit,
    discoverRequested: Boolean,
    explore: () -> Unit,
    academy: () -> Unit = {},
    consumeDiscovery: () -> Unit,
) {
    var level by rememberSaveable { mutableStateOf("All") }
    var deck by rememberSaveable { mutableStateOf("All") }
    var status by rememberSaveable { mutableStateOf("All") }
    var topic by rememberSaveable { mutableStateOf("All") }
    Page {
        Label("Practice"); Heading("Learn")
        Secondary("Learning Academy", onClick = academy)
        if (state.session != null) Sheet {
            Text(state.session.mode.label)
            Primary("Resume session", !state.busy, onClick = resume)
        }
        DailyLearning(state, vm, resume, begin, previewRequested, consumePreview, discoverRequested, consumeDiscovery)
        Secondary("Practice & Explore", onClick = explore)
        for (mode in listOf(Mode.Quick, Mode.Custom, Mode.Placement)) {
            Sheet {
                Text(mode.label, style = MaterialTheme.typography.titleLarge)
                if (mode == Mode.Custom) {
                    Pick("Level", level, listOf("All") + levels) { level = it }
                    Pick("Deck", deck, listOf("All") + state.decks) { deck = it }
                    Pick("Group", status, listOf("All", "Favorites", "Missed", "Learning", "Known")) { status = it }
                    Pick("Topic", topic, listOf("All") + state.topics) { topic = it }
                }
                Secondary("Start ${mode.label.lowercase()}", !state.busy && state.session == null) {
                    begin(mode, Filters(level = level, deck = deck, topic = topic, status = status))
                }
            }
        }
    }
}

@Composable
fun Confirm(title: String, body: String, dismiss: () -> Unit, accept: () -> Unit) {
    AlertDialog(
        onDismissRequest = dismiss,
        title = { Text(title) },
        text = { Text(body) },
        confirmButton = { TextButton({ dismiss(); accept() }) { Text("Confirm") } },
        dismissButton = { TextButton(dismiss) { Text("Cancel") } },
    )
}
