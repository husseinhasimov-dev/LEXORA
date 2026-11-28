package org.lexora.study

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
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

@Composable
internal fun GrammarHubScreen(
    d: AcademyData,
    guides: Map<String, GrammarGuide>,
    a: AcademyActions,
    onOpenVocabTopic: (String) -> Unit,
) {
    var section by rememberSaveable { mutableStateOf("Topics") }
    var level by rememberSaveable { mutableStateOf("All") }
    var category by rememberSaveable { mutableStateOf("All") }
    var query by rememberSaveable { mutableStateOf("") }

    val categories = remember(guides) { listOf("All") + guides.values.map { it.category }.distinct().sorted() }
    val masteredCount = remember(d.lessons, d.progress) { d.lessons.count { d.progress[it.id]?.status == "Mastered" } }
    val revisions = remember(d.lessons, d.progress, d.journal, d.readings) {
        computePersonalizedGrammarRevisions(d.lessons, d.progress, d.journal, d.readings)
    }

    Page {
        SectionTitle("Grammar Academy", a)
        Hint("${d.lessons.size} topics · $masteredCount mastered · A1–B2 German grammar")
        ChipStrip(
            options = listOf("Topics", "For You", "Reference", "Worksheets"),
            selected = section,
            onSelect = { section = it },
        )

        when (section) {
            "Topics" -> {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    label = { Text("Search grammar topics") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                )
                ChipStrip(listOf("All") + levels, level) { level = it }
                Pick("Category", category, categories) { category = it }

                val filtered = d.lessons.filter { l ->
                    val g = guides[l.id]
                    (level == "All" || l.level == level) &&
                        (category == "All" || g?.category == category) &&
                        (query.isBlank() || l.title.contains(query, true) || l.explanation.contains(query, true))
                }
                Hint("${filtered.size} topics shown")
                if (filtered.isEmpty()) {
                    EmptyStateCard("No matching topics", "Adjust the level, category, or search filter.")
                }
                filtered.forEach { l ->
                    val p = d.progress[l.id] ?: TopicProgress("Not Started", false, null, null, null, 0L, 0)
                    val g = guides[l.id]
                    val qCount = l.allQuestions(g).size
                    Sheet {
                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(Modifier.weight(1f)) {
                                Label(l.level + (g?.let { " · ${it.category}" }.orEmpty()))
                                Text(
                                    l.title,
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.SemiBold,
                                    color = textMain,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                            StatusPill(p.status)
                        }
                        Hint("$qCount exercises · " + (d.lessons.find { it.id == l.prerequisite }?.let { "After ${it.title}" } ?: "Foundation topic"))
                        Secondary("Study & Practice") { a.go("Lesson:" + l.id) }
                    }
                }
            }

            "For You" -> {
                Sheet(background = panelSubtle) {
                    Label("Personalized Revision")
                    Text("Recommended from your real errors, tests, and reading history.", color = textMain, fontSize = 14.sp)
                }
                if (revisions.isEmpty()) {
                    EmptyStateCard(
                        "All caught up",
                        "Complete a lesson or practice round to build personalized revision recommendations.",
                        "Browse Topics",
                    ) { section = "Topics" }
                } else {
                    revisions.forEach { item ->
                        val p = d.progress[item.lesson.id]
                        Sheet {
                            Row(
                                Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Column(Modifier.weight(1f)) {
                                    Label(item.lesson.level)
                                    Text(item.lesson.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, color = textMain)
                                }
                                StatusPill(p?.status ?: "Not Started")
                            }
                            Hint(item.reason)
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Secondary("Open Guide", modifier = Modifier.weight(1f)) { a.go("Lesson:" + item.lesson.id) }
                                Primary(item.suggestedMode, modifier = Modifier.weight(1f)) {
                                    val act = item.lesson.expandedActivity(item.suggestedMode, guides[item.lesson.id], d.journal.size)
                                    a.quiz(act)
                                }
                            }
                        }
                    }
                }
            }

            "Reference" -> {
                GrammarReferenceCenter(d, guides, a, onOpenVocabTopic)
            }

            "Worksheets" -> {
                GrammarWorksheetSection(d, guides, a)
            }
        }
    }
}

@Composable
internal fun ExpandedLessonScreen(
    d: AcademyData,
    l: Lesson,
    guide: GrammarGuide?,
    a: AcademyActions,
    onOpenVocabTopic: (String) -> Unit,
) {
    val p = d.progress[l.id] ?: TopicProgress("Not Started", false, null, null, null, 0L, 0)
    var practiceSeed by rememberSaveable(l.id) { mutableIntStateOf(0) }
    val allQ = remember(l, guide) { l.allQuestions(guide) }

    Page {
        SectionTitle(l.title, a)
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            BadgePill(l.level)
            guide?.category?.let { BadgePill(it, color = faint, background = panelSubtle) }
            StatusPill(p.status)
        }

        Sheet {
            Label("Core Rule")
            Text(l.explanation, color = textMain, style = MaterialTheme.typography.bodyLarge)
            if (guide != null) {
                ExpandableInfo("Why this rule works", guide.whyItWorks, initiallyExpanded = true)
            }
        }

        Sheet {
            Label("Examples", color = greenText)
            val positives = guide?.positiveExamples?.takeIf { it.isNotEmpty() } ?: listOf(l.example)
            positives.forEach { ex ->
                Text("✓  $ex", color = greenText, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
            }
            val negatives = guide?.negativeExamples.orEmpty()
            if (negatives.isNotEmpty()) {
                HorizontalDivider(color = panelBorder)
                Label("Common Traps", color = amber)
                negatives.forEach { ex ->
                    Text(ex, color = amber, style = MaterialTheme.typography.bodyMedium)
                }
            }
            Hint(guide?.commonMistakes ?: l.mistake)
        }

        if (guide != null) {
            ExpandableInfo("When to use & register", guide.context)
            if (guide.exceptions.isNotBlank()) {
                ExpandableInfo("Key exceptions", guide.exceptions)
            }
            if (guide.comparison.isNotBlank()) {
                ExpandableInfo("Compare with related grammar", guide.comparison)
            }
        }

        if (!p.lesson) {
            SuccessPrimary("Mark lesson studied") { a.write { completeLesson(l) } }
        }

        Sheet {
            Label("Practice & Mastery · ${allQ.size} validated questions")
            fun pct(v: Double?) = v?.let { "${(it * 100).toInt()}%" } ?: "—"
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Metric(pct(p.practice), "Practice", Modifier.weight(1f), accent = if ((p.practice ?: 0.0) >= 0.8) greenText else textMain)
                Metric(pct(p.test), "Assessment", Modifier.weight(1f), accent = if ((p.test ?: 0.0) >= 0.85) greenText else textMain)
                Metric(pct(p.delayed), "Delayed", Modifier.weight(1f), accent = if ((p.delayed ?: 0.0) >= 0.85) greenText else textMain)
            }
            val delayedReady = p.lastAssessment > 0 && d.now - p.lastAssessment >= MasteryRules().delayMs
            Primary("Varied Practice (4 Qs)") {
                practiceSeed++
                a.quiz(l.expandedActivity("Practice", guide, practiceSeed))
            }
            Secondary("Guided Warm-up") { a.quiz(l.expandedActivity("Guided", guide, practiceSeed)) }
            Secondary("Extended Drill (${allQ.size} Qs)") { a.quiz(l.expandedActivity("Extended Drill", guide, practiceSeed)) }
            Secondary("Topic Assessment") { a.quiz(l.expandedActivity("Assessment", guide, practiceSeed)) }
            Secondary(
                if (delayedReady) "Delayed Mastery Review" else "Delayed Review (opens 24h after test)",
                enabled = delayedReady,
            ) { a.quiz(l.expandedActivity("Delayed review", guide, practiceSeed)) }
        }

        val relatedReadings = d.readings.filter { it.grammar == l.id }
        val vocabTopics = guide?.relatedVocabTopics.orEmpty()
        if (relatedReadings.isNotEmpty() || vocabTopics.isNotEmpty()) {
            Sheet {
                Label("Connected Resources")
                relatedReadings.forEach { r ->
                    Secondary("Reading · ${r.title} (${r.level})") { a.go("Read:" + r.id) }
                }
                if (vocabTopics.isNotEmpty()) {
                    Hint("Related vocabulary themes: ${vocabTopics.joinToString(" · ")}")
                    Secondary("Explore related vocabulary") { onOpenVocabTopic(vocabTopics.first()) }
                }
            }
        }
    }
}

@Composable
private fun GrammarReferenceCenter(
    d: AcademyData,
    guides: Map<String, GrammarGuide>,
    a: AcademyActions,
    onOpenVocabTopic: (String) -> Unit,
) {
    var selectedTable by rememberSaveable { mutableStateOf(GrammarReferenceTables.all.first().id) }
    val table = GrammarReferenceTables.all.first { it.id == selectedTable }

    ChipStrip(
        options = GrammarReferenceTables.all.map { it.title.substringBefore(" (") },
        selected = table.title.substringBefore(" ("),
        onSelect = { label ->
            selectedTable = GrammarReferenceTables.all.first { it.title.substringBefore(" (") == label }.id
        },
    )

    Sheet {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(table.title, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, color = textMain)
            BadgePill(table.levelRange)
        }
        Hint(table.summary)
        Column(
            Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .clip(RoundedCornerShape(12.dp))
                .border(1.dp, panelBorder, RoundedCornerShape(12.dp)),
        ) {
            Row(Modifier.background(panelSubtle).padding(horizontal = 10.dp, vertical = 8.dp)) {
                table.headers.forEachIndexed { idx, h ->
                    Text(
                        h,
                        modifier = Modifier.width(if (idx == 0) 104.dp else 138.dp).padding(end = 8.dp),
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        color = blue,
                    )
                }
            }
            table.rows.forEachIndexed { rIdx, cells ->
                Row(
                    Modifier
                        .background(if (rIdx % 2 == 0) panel else panelSubtle)
                        .padding(horizontal = 10.dp, vertical = 8.dp),
                ) {
                    cells.forEachIndexed { cIdx, cell ->
                        Text(
                            cell,
                            modifier = Modifier.width(if (cIdx == 0) 104.dp else 138.dp).padding(end = 8.dp),
                            fontSize = 12.sp,
                            fontWeight = if (cIdx == 0) FontWeight.SemiBold else FontWeight.Normal,
                            color = if (cIdx == 0) greenText else textMain,
                        )
                    }
                }
            }
        }
    }

    Sheet {
        Label("Common Mistake Quick Reference")
        val sampleMistakes = guides.values.take(8)
        sampleMistakes.forEach { g ->
            val title = d.lessons.find { it.id == g.id }?.title ?: g.id
            Column(Modifier.fillMaxWidth().background(panelSubtle, RoundedCornerShape(10.dp)).padding(10.dp)) {
                Text(title, fontWeight = FontWeight.SemiBold, color = blue, fontSize = 13.sp)
                g.negativeExamples.firstOrNull()?.let { Text(it, color = amber, fontSize = 12.sp) }
            }
        }
    }
}

@Composable
private fun GrammarWorksheetSection(
    d: AcademyData,
    guides: Map<String, GrammarGuide>,
    a: AcademyActions,
) {
    var wsLevel by rememberSaveable { mutableStateOf("A1") }
    var wsCategory by rememberSaveable { mutableStateOf("All") }
    var active by rememberSaveable { mutableStateOf(false) }
    var step by rememberSaveable { mutableIntStateOf(0) }
    var score by rememberSaveable { mutableIntStateOf(0) }
    var submitted by rememberSaveable { mutableStateOf<String?>(null) }
    var typed by rememberSaveable { mutableStateOf("") }

    val categories = remember(guides) { listOf("All") + guides.values.map { it.category }.distinct().sorted() }
    val worksheetItems = remember(d.lessons, guides, wsLevel, wsCategory) {
        d.lessons.filter { l ->
            l.level == wsLevel && (wsCategory == "All" || guides[l.id]?.category == wsCategory)
        }.flatMap { l ->
            l.allQuestions(guides[l.id]).take(2).map { l to it }
        }.take(8)
    }

    if (!active) {
        Sheet {
            Label("In-App Revision Worksheets")
            Hint("Mix multiple grammar topics in one interactive worksheet with instant answer keys and explanations.")
            ChipStrip(levels, wsLevel) { wsLevel = it }
            Pick("Category", wsCategory, categories) { wsCategory = it }
            Hint("${worksheetItems.size} graded questions ready in this worksheet")
            Primary("Start $wsLevel Worksheet", enabled = worksheetItems.isNotEmpty()) {
                step = 0; score = 0; submitted = null; typed = ""; active = true
            }
        }
        return
    }

    if (step >= worksheetItems.size) {
        val pct = if (worksheetItems.isNotEmpty()) (score * 100) / worksheetItems.size else 0
        Sheet(accentBorder = if (pct >= 80) greenBorder else panelBorder) {
            Label("Worksheet Complete", color = if (pct >= 80) greenText else blue)
            Text("$score / ${worksheetItems.size} correct ($pct%)", fontSize = 22.sp, fontWeight = FontWeight.Bold, color = if (pct >= 80) greenText else textMain)
            Hint("Your worksheet score is logged as a Grammar revision activity.")
            Primary("New Worksheet") { active = false }
        }
        return
    }

    val (lesson, q) = worksheetItems[step]
    Sheet {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Label("${lesson.level} · ${lesson.title}")
            BadgePill("${step + 1}/${worksheetItems.size}")
        }
        Progress((step + 1f) / worksheetItems.size)
        Text(q.text, style = MaterialTheme.typography.titleMedium, color = textMain)
        Hint(q.type)

        if (submitted == null) {
            if (q.distractors.isNotEmpty()) {
                val options = remember(q.text) { (q.distractors + q.key).shuffled(kotlin.random.Random(q.text.hashCode())) }
                options.forEach { opt ->
                    Secondary(opt) {
                        submitted = opt
                        val ok = q.accepts(opt)
                        if (ok) score++
                        a.write {
                            record(
                                Evidence(
                                    id = "ws/${lesson.id}/${now()}/$step",
                                    skill = "Grammar",
                                    activity = "Worksheet answer",
                                    content = lesson.id,
                                    level = lesson.level,
                                    attempted = 1,
                                    correct = if (ok) 1 else 0,
                                    answer = opt,
                                    grammar = lesson.id,
                                ),
                            )
                        }
                    }
                }
            } else {
                OutlinedTextField(
                    value = typed,
                    onValueChange = { typed = it },
                    label = { Text("Your German answer") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                )
                Primary("Check Answer", enabled = typed.isNotBlank()) {
                    val value = typed.trim()
                    submitted = value
                    val ok = q.accepts(value)
                    if (ok) score++
                    a.write {
                        record(
                            Evidence(
                                id = "ws/${lesson.id}/${now()}/$step",
                                skill = "Grammar",
                                activity = "Worksheet answer",
                                content = lesson.id,
                                level = lesson.level,
                                attempted = 1,
                                correct = if (ok) 1 else 0,
                                answer = value,
                                grammar = lesson.id,
                            ),
                        )
                    }
                }
            }
            TextButton({ active = false }) { Text("Exit worksheet") }
        } else {
            val ok = q.accepts(submitted!!)
            Sheet(
                background = if (ok) greenBg else coralBg,
                accentBorder = if (ok) greenBorder else coral,
            ) {
                Text(
                    if (ok) "Correct ✓" else "Expected: ${q.key}",
                    fontWeight = FontWeight.Bold,
                    color = if (ok) greenText else coral,
                )
                Hint(q.why, color = textMain)
            }
            Primary(if (step + 1 < worksheetItems.size) "Next Question" else "Finish Worksheet") {
                step++
                submitted = null
                typed = ""
            }
        }
    }
}
