package org.lexora.study

import android.app.DatePickerDialog
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.format.TextStyle
import java.util.Locale
import java.util.UUID
import kotlin.math.roundToInt

@Composable
internal fun TestDaFMissionControlTab(
    mission: MissionControlBundle,
    today: LocalDate,
    onSaveConfig: (TestDaFConfig) -> Unit,
    onAddAssessment: (TestDaFAssessmentRecord) -> Unit,
    onDeleteAssessment: (String) -> Unit,
) {
    val ctx = LocalContext.current
    val cfg = mission.config
    val daysLeft = cfg.daysUntil(today)
    var showConfig by rememberSaveable { mutableStateOf(false) }
    var showLog by rememberSaveable { mutableStateOf(false) }
    var logInitialSkill by rememberSaveable { mutableStateOf("Lesen") }

    // Header + Countdown
    Sheet(accentBorder = blueBorder) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Label("Digital TestDaF Mission Control")
            BadgePill("TDN4 × 4 Skills", color = blue, background = blueSurface)
        }
        val countdownTitle = when {
            cfg.targetDate == null -> "Set your target exam date"
            daysLeft != null && daysLeft < 0 -> "Exam date passed (${-daysLeft}d ago)"
            daysLeft == 0L -> "Exam Day — Today!"
            else -> "$daysLeft days remaining"
        }
        Text(
            countdownTitle,
            fontSize = 24.sp,
            fontWeight = FontWeight.Bold,
            color = if (daysLeft != null && daysLeft < 0) coral else textMain,
        )
        Hint(
            (cfg.targetDate?.let { "Target date: $it" } ?: "No exam date configured") +
                " · ${String.format(Locale.ENGLISH, "%.1f", cfg.weeklyHours)} hrs/week" +
                if (cfg.restDays.isEmpty()) " · No rest days" else " · Rest: ${cfg.restDays.joinToString { it.getDisplayName(TextStyle.SHORT, Locale.ENGLISH) }}",
        )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Secondary("Exam Setup", modifier = Modifier.weight(1f)) { showConfig = true }
            Primary("Log Assessment", modifier = Modifier.weight(1f)) {
                logInitialSkill = mission.risk.weakestSkill.skill
                showLog = true
            }
        }
    }

    // Four Independent Skill Panels — NEVER replaced by a single average!
    Label("Four Required Skills (Tracked Independently)")
    Hint("All four competencies must reach TDN4 independently. A strong Lesen score never offsets Sprechen.")

    mission.panels.forEach { panel ->
        var showHistory by rememberSaveable(panel.skill) { mutableStateOf(false) }
        Sheet {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(panel.skill, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold, color = textMain)
                    Text(panel.confidenceNote, fontSize = 12.sp, color = faint)
                }
                StatusPill(panel.status.label)
            }
            val latest = panel.latest
            if (latest != null) {
                val pct = (latest.ratio * 100.0).roundToInt()
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Metric(
                        latest.tdnLevel ?: "$pct%",
                        "Latest (${latest.difficulty})",
                        Modifier.weight(1f),
                        accent = if (panel.status == SkillPrepStatus.TARGET_RANGE_INDICATED) greenText else blue,
                    )
                    Metric(latest.day, latest.format, Modifier.weight(1f))
                }
                Hint("Evidence tier: ${latest.evidenceType.label} · ${panel.trendNote}")
            } else {
                Hint(panel.trendNote)
            }
            ExpandableInfo("Next recommended diagnostic", panel.nextRecommendedDiagnostic)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                TextButton({ logInitialSkill = panel.skill; showLog = true }) { Text("+ Log ${panel.skill}") }
                if (panel.history.isNotEmpty()) {
                    TextButton({ showHistory = !showHistory }) {
                        Text(if (showHistory) "Hide history" else "History (${panel.history.size})")
                    }
                }
            }
            if (showHistory) {
                panel.history.take(6).forEach { rec ->
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                "${rec.day} · ${rec.tdnLevel ?: "${(rec.ratio * 100).roundToInt()}%"} (${rec.difficulty})",
                                fontSize = 13.sp,
                                color = textMain,
                                fontWeight = FontWeight.Medium,
                            )
                            Text(
                                "${rec.evidenceType.label} · ${rec.format}" + if (rec.notes.isNotBlank()) " · ${rec.notes}" else "",
                                fontSize = 11.sp,
                                color = faint,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        if (rec.sourceEventId == null) {
                            TextButton({ onDeleteAssessment(rec.id) }) { Text("Delete", color = coral) }
                        }
                    }
                }
            }
        }
    }

    // Advanced TestDaF Risk Engine (3 Distinct Risks)
    Label("Advanced TestDaF Risk Analysis")
    Sheet(background = panelSubtle) {
        Text(
            "Weakest Skill Priority: ${mission.risk.weakestSkill.skill} (${mission.risk.weakestSkill.status.label})",
            fontWeight = FontWeight.SemiBold,
            color = amber,
        )
        Hint("No invented pass probabilities. Each risk category is evaluated strictly from evidence.")
    }
    listOf(mission.risk.learningRisk, mission.risk.scheduleRisk, mission.risk.examReadinessRisk).forEach { r ->
        Sheet {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text(r.title, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, color = textMain)
                StatusPill(r.level.label)
            }
            Hint(r.summary)
            if (r.reasons.size > 1) {
                ExpandableInfo("Full rationale (${r.reasons.size} factors)", r.reasons.joinToString("\n• ", prefix = "• "))
            }
        }
    }

    // Conservative Readiness Date Forecasting
    Label("Conservative Readiness Forecast")
    val fc = mission.forecast
    Sheet {
        Text(fc.headline, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, color = if (fc.sufficient) greenText else amber)
        Hint(fc.disclaimer)
        if (fc.sufficient) {
            fc.scenarios.forEach { sc ->
                Column(Modifier.fillMaxWidth().padding(top = 4.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(sc.name, fontWeight = FontWeight.SemiBold, color = blue, fontSize = 13.sp)
                        Text(sc.windowLabel, fontWeight = FontWeight.Bold, color = textMain, fontSize = 13.sp)
                    }
                    Text(sc.assumptions, fontSize = 12.sp, color = faint)
                }
            }
        }
        if (fc.missingRequirements.isNotEmpty()) {
            HorizontalDivider(color = panelBorder)
            Label("Required for higher forecast reliability", color = amber)
            fc.missingRequirements.forEach { req ->
                Text("• $req", fontSize = 13.sp, color = textMain)
            }
        }
    }

    // Preparation Milestones
    if (cfg.milestones.isNotEmpty()) {
        Sheet {
            Label("Preparation Milestones")
            cfg.milestones.forEach { m ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(m.title, color = if (m.completed) greenText else textMain, fontWeight = FontWeight.Medium)
                        Text("Due ${m.dueDate}", fontSize = 12.sp, color = faint)
                    }
                    TextButton({
                        val updated = cfg.milestones.map { if (it.id == m.id) it.copy(completed = !it.completed) else it }
                        onSaveConfig(cfg.copy(milestones = updated))
                    }) { Text(if (m.completed) "Completed ✓" else "Mark done") }
                }
            }
        }
    }

    if (showConfig) {
        ExamSetupDialog(cfg, today, { showConfig = false }) { updated ->
            onSaveConfig(updated)
            showConfig = false
        }
    }
    if (showLog) {
        LogAssessmentDialog(logInitialSkill, today, { showLog = false }) { rec ->
            onAddAssessment(rec)
            showLog = false
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ExamSetupDialog(
    initial: TestDaFConfig,
    today: LocalDate,
    onDismiss: () -> Unit,
    onSave: (TestDaFConfig) -> Unit,
) {
    val ctx = LocalContext.current
    var dateStr by rememberSaveable { mutableStateOf(initial.targetDate?.toString().orEmpty()) }
    var weeklyHours by rememberSaveable { mutableStateOf(String.format(Locale.ENGLISH, "%.1f", initial.weeklyHours)) }
    var restDays by remember { mutableStateOf(initial.restDays) }
    var milestones by remember { mutableStateOf(initial.milestones) }
    var newMilestoneTitle by rememberSaveable { mutableStateOf("") }
    var newMilestoneDate by rememberSaveable { mutableStateOf(today.plusDays(30).toString()) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Digital TestDaF Setup") },
        text = {
            Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Hint("Target: Digital TestDaF — TDN4 in Lesen, Hören, Schreiben, and Sprechen.")
                Secondary("Exam Date · ${dateStr.ifEmpty { "Not set" }}") {
                    val base = dateStr.takeIf { it.isNotBlank() }?.let { runCatching { LocalDate.parse(it) }.getOrNull() } ?: today.plusMonths(4)
                    DatePickerDialog(ctx, { _, y, m, d -> dateStr = LocalDate.of(y, m + 1, d).toString() }, base.year, base.monthValue - 1, base.dayOfMonth).show()
                }
                if (dateStr.isNotBlank()) TextButton({ dateStr = "" }) { Text("Clear exam date") }
                OutlinedTextField(
                    value = weeklyHours,
                    onValueChange = { weeklyHours = it.filter { c -> c.isDigit() || c == '.' }.take(4) },
                    label = { Text("Weekly study hours (1–40)") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                )
                Label("Preferred Rest Days")
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    DayOfWeek.entries.forEach { d ->
                        val isRest = d in restDays
                        FilterChip(
                            selected = isRest,
                            onClick = { restDays = if (isRest) restDays - d else restDays + d },
                            label = { Text(d.getDisplayName(TextStyle.SHORT, Locale.ENGLISH)) },
                        )
                    }
                }
                Label("Optional Milestone")
                OutlinedTextField(
                    value = newMilestoneTitle,
                    onValueChange = { newMilestoneTitle = it.take(80) },
                    label = { Text("Milestone title (e.g. Full B2 Mock)") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                )
                Secondary("Milestone Date · $newMilestoneDate") {
                    val base = runCatching { LocalDate.parse(newMilestoneDate) }.getOrDefault(today.plusDays(30))
                    DatePickerDialog(ctx, { _, y, m, d -> newMilestoneDate = LocalDate.of(y, m + 1, d).toString() }, base.year, base.monthValue - 1, base.dayOfMonth).show()
                }
                TextButton(
                    onClick = {
                        milestones = milestones + ExamMilestone(UUID.randomUUID().toString(), newMilestoneTitle.trim(), newMilestoneDate, false)
                        newMilestoneTitle = ""
                    },
                    enabled = newMilestoneTitle.isNotBlank(),
                ) { Text("+ Add milestone") }
                milestones.forEach { m ->
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Text("${m.title} (${m.dueDate})", Modifier.weight(1f), fontSize = 12.sp)
                        TextButton({ milestones = milestones.filterNot { it.id == m.id } }) { Text("Remove", color = coral) }
                    }
                }
            }
        },
        confirmButton = {
            val hrs = weeklyHours.toDoubleOrNull()
            TextButton(
                onClick = {
                    val parsedDate = dateStr.takeIf { it.isNotBlank() }?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
                    onSave(
                        initial.copy(
                            targetDate = parsedDate,
                            weeklyHours = (hrs ?: 5.0).coerceIn(1.0, 40.0),
                            restDays = restDays,
                            milestones = milestones,
                        ),
                    )
                },
                enabled = hrs != null && hrs in 1.0..40.0,
            ) { Text("Save") }
        },
        dismissButton = { TextButton(onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun LogAssessmentDialog(
    initialSkill: String,
    today: LocalDate,
    onDismiss: () -> Unit,
    onSave: (TestDaFAssessmentRecord) -> Unit,
) {
    val ctx = LocalContext.current
    var skill by rememberSaveable { mutableStateOf(initialSkill) }
    var evidence by rememberSaveable { mutableStateOf(EvidenceReliability.EXAM_DIAGNOSTIC.label) }
    var difficulty by rememberSaveable { mutableStateOf("TestDaF") }
    var format by rememberSaveable { mutableStateOf("Digital TestDaF") }
    var scoreStr by rememberSaveable { mutableStateOf("15") }
    var maxScoreStr by rememberSaveable { mutableStateOf("20") }
    var tdn by rememberSaveable { mutableStateOf("Unrated") }
    var dateStr by rememberSaveable { mutableStateOf(today.toString()) }
    var notes by rememberSaveable { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Log Skill Assessment") },
        text = {
            Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Pick("Skill", skill, testDaFSkills) { skill = it }
                Pick("Evidence type", evidence, EvidenceReliability.entries.map { it.label }) { evidence = it }
                Pick("Difficulty", difficulty, listOf("A1", "A2", "B1", "B2", "C1", "TestDaF")) { difficulty = it }
                Pick("Format", format, listOf("Digital TestDaF", "Timed Mock Exam", "Practice Task", "Tutor / AI Evaluation")) { format = it }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = scoreStr,
                        onValueChange = { scoreStr = it.filter { c -> c.isDigit() || c == '.' }.take(5) },
                        label = { Text("Score") },
                        modifier = Modifier.weight(1f),
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    )
                    OutlinedTextField(
                        value = maxScoreStr,
                        onValueChange = { maxScoreStr = it.filter { c -> c.isDigit() || c == '.' }.take(5) },
                        label = { Text("Max") },
                        modifier = Modifier.weight(1f),
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    )
                }
                Pick("TDN Band (optional)", tdn, listOf("Unrated", "Below TDN3", "TDN3", "TDN4", "TDN5")) { tdn = it }
                Secondary("Date · $dateStr") {
                    val base = runCatching { LocalDate.parse(dateStr) }.getOrDefault(today)
                    DatePickerDialog(ctx, { _, y, m, d -> dateStr = LocalDate.of(y, m + 1, d).toString() }, base.year, base.monthValue - 1, base.dayOfMonth).show()
                }
                OutlinedTextField(
                    value = notes,
                    onValueChange = { notes = it.take(240) },
                    label = { Text("Rubric / feedback notes") },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            val s = scoreStr.toDoubleOrNull()
            val m = maxScoreStr.toDoubleOrNull()
            val valid = s != null && m != null && m > 0.0 && s in 0.0..m
            TextButton(
                onClick = {
                    val tier = EvidenceReliability.entries.first { it.label == evidence }
                    val parsed = runCatching { LocalDate.parse(dateStr) }.getOrDefault(today)
                    val millis = parsed.atStartOfDay(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()
                    onSave(
                        TestDaFAssessmentRecord(
                            skill = skill,
                            evidenceType = tier,
                            score = s!!,
                            maxScore = m!!,
                            tdnLevel = tdn.takeIf { it != "Unrated" },
                            difficulty = difficulty,
                            format = format,
                            assessedAt = millis,
                            day = parsed.toString(),
                            notes = notes,
                        ),
                    )
                },
                enabled = valid,
            ) { Text("Save") }
        },
        dismissButton = { TextButton(onDismiss) { Text("Cancel") } },
    )
}

@Composable
internal fun AlertsCenterTab(
    alerts: List<LearningAlert>,
    categoryPrefs: Map<AlertCategory, Boolean>,
    onToggleCategoryPref: (AlertCategory, Boolean) -> Unit,
    onAcknowledge: (String) -> Unit,
    onDismiss: (String) -> Unit,
    onRestoreDismissed: () -> Unit,
    onNavigate: (String) -> Unit,
) {
    var selectedCategory by rememberSaveable { mutableStateOf("All") }
    var showPrefs by rememberSaveable { mutableStateOf(false) }

    val filtered = remember(alerts, selectedCategory) {
        if (selectedCategory == "All") alerts
        else alerts.filter { it.category.label == selectedCategory }
    }

    Sheet {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Label("Learning Intelligence Alerts")
                Text("${alerts.size} active insight(s)", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, color = textMain)
            }
            TextButton({ showPrefs = true }) { Text("Preferences") }
        }
        ChipStrip(listOf("All") + AlertCategory.entries.map { it.label }, selectedCategory) { selectedCategory = it }
    }

    if (filtered.isEmpty()) {
        EmptyStateCard(
            "No alerts in this category",
            "All meaningful performance, planning, exam, and positive alerts appear here as you study.",
            "Restore dismissed alerts",
            onRestoreDismissed,
        )
    } else {
        filtered.forEach { alert ->
            val border = when (alert.severity) {
                AlertSeverity.HIGH -> coral
                AlertSeverity.MEDIUM -> amber
                AlertSeverity.POSITIVE -> greenBorder
                AlertSeverity.INFO -> panelBorder
            }
            Sheet(accentBorder = border) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    StatusPill(alert.severity.label)
                    BadgePill(alert.category.label, color = faint, background = panelSubtle)
                    if (alert.acknowledged) BadgePill("Seen", color = greenText, background = greenBg)
                }
                Text(alert.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, color = textMain)
                Text(alert.explanation, fontSize = 14.sp, color = textMain)
                Hint("Evidence: ${alert.evidence} · Period: ${alert.period}")
                Hint("Next step: ${alert.suggestedAction}", color = blue)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Primary("Open", modifier = Modifier.weight(1f)) { onNavigate(alert.destinationRoute) }
                    if (!alert.acknowledged) {
                        Secondary("Acknowledge", modifier = Modifier.weight(1f)) { onAcknowledge(alert.fingerprint) }
                    }
                    TextButton({ onDismiss(alert.fingerprint) }) { Text("Dismiss", color = faint) }
                }
            }
        }
        TextButton(onRestoreDismissed) { Text("Restore dismissed alerts") }
    }

    if (showPrefs) {
        AlertDialog(
            onDismissRequest = { showPrefs = false },
            title = { Text("Alert Notification Preferences") },
            text = {
                Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Hint("All meaningful alerts remain visible in-app. Choose which categories may send Android phone notifications when enabled.")
                    AlertCategory.entries.forEach { cat ->
                        val enabled = categoryPrefs[cat] ?: (cat == AlertCategory.EXAM || cat == AlertCategory.STUDY_PLANNING)
                        SwitchRow(cat.label, enabled) { onToggleCategoryPref(cat, it) }
                    }
                }
            },
            confirmButton = { TextButton({ showPrefs = false }) { Text("Done") } },
        )
    }
}
