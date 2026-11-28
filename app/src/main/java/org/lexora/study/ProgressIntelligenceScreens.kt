package org.lexora.study

import android.app.DatePickerDialog
import androidx.compose.foundation.layout.*
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.time.LocalDate
import java.util.Locale
import java.util.UUID
import kotlin.math.roundToInt

@Composable
internal fun AnalyticsOverviewTab(
    analytics: AnalyticsBundle,
    window: AnalyticsWindow,
    customFrom: LocalDate,
    customTo: LocalDate,
    onWindowChange: (AnalyticsWindow) -> Unit,
    onCustomRangeChange: (LocalDate, LocalDate) -> Unit,
    state: ScreenState,
    onSelectCalendarDay: (String) -> Unit,
    onOpenWord: (String) -> Unit,
    onLogManualStudy: (skill: String, minutes: Int, note: String) -> Unit,
) {
    val ctx = LocalContext.current
    val act = analytics.activity
    var logDialog by rememberSaveable { mutableStateOf(false) }

    Sheet {
        Label("Time Window")
        ChipStrip(
            options = AnalyticsWindow.entries.map { it.label },
            selected = window.label,
            onSelect = { lbl ->
                AnalyticsWindow.entries.firstOrNull { it.label == lbl }?.let(onWindowChange)
            },
        )
        Hint("${act.range.from} to ${act.range.to}")
        if (window == AnalyticsWindow.CUSTOM) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Secondary("From · $customFrom", modifier = Modifier.weight(1f)) {
                    DatePickerDialog(
                        ctx,
                        { _, y, m, d -> onCustomRangeChange(LocalDate.of(y, m + 1, d), customTo) },
                        customFrom.year, customFrom.monthValue - 1, customFrom.dayOfMonth,
                    ).show()
                }
                Secondary("To · $customTo", modifier = Modifier.weight(1f)) {
                    DatePickerDialog(
                        ctx,
                        { _, y, m, d -> onCustomRangeChange(customFrom, LocalDate.of(y, m + 1, d)) },
                        customTo.year, customTo.monthValue - 1, customTo.dayOfMonth,
                    ).show()
                }
            }
        }
    }

    // Key Study Activity KPIs
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Metric(formatStudyTime(act.todayMinutes), "Today", Modifier.weight(1f), accent = greenText)
        Metric(formatStudyTime(act.weeklyMinutes), "Last 7 days", Modifier.weight(1f), accent = blue)
        Metric(formatStudyTime(act.monthlyMinutes), "Last 30 days", Modifier.weight(1f))
    }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Metric("${act.currentStreak}d", "Current streak", Modifier.weight(1f), accent = if (act.currentStreak > 0) greenText else textMain)
        Metric("${act.longestStreak}d", "Longest streak", Modifier.weight(1f))
        Metric(act.weeklyConsistencyPct?.let { "$it%" } ?: "—", "Consistency", Modifier.weight(1f), accent = blue)
    }

    Sheet {
        Label("Observed vs. Manually Reported Time")
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Metric(formatStudyTime(act.rangeObservedMinutes), "Observed active", Modifier.weight(1f), accent = blue)
            Metric(formatStudyTime(act.rangeReportedMinutes), "Self-reported", Modifier.weight(1f), accent = amber)
        }
        Hint("Total all-time: ${formatStudyTime(act.totalActiveMinutesAllTime)} (${formatStudyTime(act.observedActiveMinutesAllTime)} observed · ${formatStudyTime(act.reportedMinutesAllTime)} reported)")
        val avgTxt = act.averageMinutesPerActiveDay?.let { String.format(Locale.ENGLISH, "%.1f min / active day", it) } ?: "No active days in this window"
        Hint("Average: $avgTxt · ${act.sessionsCompletedInRange} sessions · ${act.activeDaysInRange} active days")
        if (act.configuredRestDays.isNotEmpty()) {
            Hint("Rest days configured: ${act.configuredRestDays.joinToString { it.name.take(3).lowercase().replaceFirstChar(Char::uppercase) }} · ${act.restDaysTakenInRange} taken in range")
        }
        if (act.plannedMinutesInRange > 0) {
            BreakdownBarRow(
                label = "Planned vs Actual (${act.range.window.label})",
                valueText = "${formatStudyTime(act.actualMinutesInRange)} / ${formatStudyTime(act.plannedMinutesInRange)}",
                fraction = act.actualMinutesInRange.toFloat() / act.plannedMinutesInRange.coerceAtLeast(1),
                barColor = if (act.actualMinutesInRange >= act.plannedMinutesInRange) green else blue,
            )
        }
        Secondary("Log offline / Speaking / Writing study") { logDialog = true }
    }

    val timePoints = remember(act.dailySeries) {
        act.dailySeries.map { pt ->
            ChartBarPoint(
                label = pt.day.substring(5),
                fullDate = pt.day,
                value = pt.totalMinutes.toFloat(),
                formattedValue = "${pt.totalMinutes.roundToInt()} min (${pt.observedMinutes.roundToInt()} obs / ${pt.reportedMinutes.roundToInt()} rep)",
            )
        }
    }
    InteractiveBarChart(
        title = "Daily Active Study Time",
        unit = "minutes",
        points = timePoints,
        accent = blue,
        emptyMessage = "Complete a study round or log offline practice to see your daily study time chart.",
    )

    val dailyGoal = state.prefs["goal"]?.toIntOrNull()?.coerceAtLeast(1) ?: 10
    StudyCalendarAndTable(
        records = state.dailyHistory,
        selectedDay = state.selectedCalendarDay,
        selectedDayWords = state.selectedDayWords,
        dailyGoal = dailyGoal,
        onSelectDay = onSelectCalendarDay,
        onOpenWord = onOpenWord,
    )

    if (logDialog) {
        var skill by rememberSaveable { mutableStateOf("Speaking") }
        var mins by rememberSaveable { mutableStateOf("15") }
        var note by rememberSaveable { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { logDialog = false },
            title = { Text("Log manually reported study") },
            text = {
                Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Hint("Stored separately from automatically observed app interaction time.")
                    Pick("Skill", skill, listOf("Speaking", "Writing", "Listening", "Reading", "Grammar", "Vocabulary")) { skill = it }
                    OutlinedTextField(
                        value = mins,
                        onValueChange = { mins = it.filter(Char::isDigit).take(3) },
                        label = { Text("Minutes (1–300)") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    )
                    OutlinedTextField(
                        value = note,
                        onValueChange = { note = it.take(180) },
                        label = { Text("What did you practice? (optional)") },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        mins.toIntOrNull()?.takeIf { it in 1..300 }?.let {
                            onLogManualStudy(skill, it, note)
                            logDialog = false
                        }
                    },
                    enabled = mins.toIntOrNull() in 1..300,
                ) { Text("Save log") }
            },
            dismissButton = { TextButton({ logDialog = false }) { Text("Cancel") } },
        )
    }
}

@Composable
internal fun XpAndVelocityTab(
    analytics: AnalyticsBundle,
    mission: MissionControlBundle,
) {
    val xp = analytics.xp
    var velWindow by rememberSaveable { mutableIntStateOf(30) }
    val velocity = when (velWindow) {
        7 -> mission.velocity7
        90 -> mission.velocity90
        else -> mission.velocity30
    }

    Sheet(accentBorder = greenBorder) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Label("Unlimited XP Economy", color = greenText)
            BadgePill("Level ${xp.level}", color = greenText, background = greenBg)
        }
        Text("${xp.totalXp} XP", fontSize = 30.sp, fontWeight = FontWeight.Bold, color = textMain)
        Progress(xp.xpInCurrentLevel / 100f, color = green)
        Hint("${xp.xpToNextLevel} XP to Level ${xp.level + 1} · No daily XP cap")
    }

    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Metric("${xp.todayXp}", "Today XP", Modifier.weight(1f), accent = greenText)
        Metric("${xp.weeklyXp}", "7-day XP", Modifier.weight(1f), accent = blue)
        Metric("${xp.monthlyXp}", "30-day XP", Modifier.weight(1f))
    }

    Sheet {
        Label("XP Growth (Period Comparisons)")
        Hint("Formula: ((Current − Previous) / Previous) × 100. Distinct from verified German proficiency.")
        GrowthRow("Daily XP (today vs yesterday)", xp.dailyGrowthPct, "${xp.todayXp} XP today")
        GrowthRow("Weekly XP (7d vs prior 7d)", xp.weeklyGrowthPct, "${xp.weeklyXp} vs ${xp.previousWeekXp} XP")
        GrowthRow("Monthly XP (30d vs prior 30d)", xp.monthlyGrowthPct, "${xp.monthlyXp} vs ${xp.previousMonthXp} XP")
        HorizontalDivider(color = panelBorder)
        val xpHr = xp.xpPerActiveHour?.let { String.format(Locale.ENGLISH, "%.0f XP / active hr", it) } ?: "Need recorded active time"
        val bestDay = xp.highestXpDay?.let { "${it.second} XP (${it.first})" } ?: "No XP recorded yet"
        Hint("Efficiency: $xpHr · Best day: $bestDay")
    }

    val xpPoints = remember(xp.rollingSeries) {
        xp.rollingSeries.map { (d, v) ->
            ChartBarPoint(d.substring(5), d, v.toFloat(), "$v XP")
        }
    }
    InteractiveBarChart(
        title = "XP Earned Over Time",
        unit = "XP / day",
        points = xpPoints,
        accent = green,
        emptyMessage = "Earn XP from vocabulary recall, reading comprehension, and grammar drills.",
    )

    if (xp.xpBySkill.isNotEmpty() || xp.xpByActivity.isNotEmpty()) {
        Sheet {
            Label("XP Breakdown (${analytics.activity.range.window.label})")
            val maxSkill = xp.xpBySkill.values.maxOrNull()?.coerceAtLeast(1L) ?: 1L
            xp.xpBySkill.forEach { (skill, amount) ->
                BreakdownBarRow(skill, "$amount XP", amount.toFloat() / maxSkill, barColor = blue)
            }
            if (xp.xpByActivity.isNotEmpty()) {
                HorizontalDivider(color = panelBorder)
                Label("By Activity")
                val maxAct = xp.xpByActivity.values.maxOrNull()?.coerceAtLeast(1L) ?: 1L
                xp.xpByActivity.forEach { (actName, amount) ->
                    BreakdownBarRow(actName, "$amount XP", amount.toFloat() / maxAct, barColor = green)
                }
            }
        }
    }

    // Learning Velocity — strictly separated into Activity, Performance, and Exam Readiness
    Sheet {
        Label("Learning Velocity Intelligence")
        Hint("Activity, learning performance, and exam readiness are tracked separately so volume never masquerades as mastery.")
        ChipStrip(listOf("7d", "30d", "90d"), "${velWindow}d") {
            velWindow = it.removeSuffix("d").toInt()
        }
    }
    VelocityTrackCard(velocity.activityGrowth, accent = blue)
    VelocityTrackCard(velocity.performanceGrowth, accent = greenText)
    VelocityTrackCard(velocity.examReadinessGrowth, accent = amber)

    if (xp.achievements.isNotEmpty()) {
        Sheet {
            Label("Achievements (${xp.achievements.size})", color = greenText)
            xp.achievements.forEach { badge ->
                Text("✓  $badge", color = textMain, fontSize = 14.sp)
            }
        }
    }
}

@Composable
private fun GrowthRow(label: String, pct: Double?, detail: String) {
    val color = when {
        pct == null -> faint
        pct > 0 -> greenText
        pct < 0 -> coral
        else -> blue
    }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(label, Modifier.weight(1f), fontSize = 13.sp, color = textMain)
            Text(formatGrowth(pct), fontSize = 12.sp, fontWeight = FontWeight.Bold, color = color)
        }
        Text(detail, fontSize = 11.sp, color = faint)
    }
}

@Composable
private fun VelocityTrackCard(track: VelocityTrack, accent: androidx.compose.ui.graphics.Color) {
    Sheet {
        Label(track.title, color = accent)
        Hint(track.summary)
        track.metrics.forEach { (k, v) ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(k, Modifier.weight(0.45f), fontSize = 13.sp, fontWeight = FontWeight.Medium, color = faint)
                Text(v, Modifier.weight(0.55f), fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = textMain)
            }
        }
        ExpandableInfo("Comparability note", track.comparabilityNote)
    }
}

@Composable
internal fun SkillsAnalyticsTab(analytics: AnalyticsBundle) {
    var focus by rememberSaveable { mutableStateOf("Vocabulary") }
    ChipStrip(listOf("Vocabulary", "Reading", "Grammar", "L / S / W"), focus) { focus = it }

    when (focus) {
        "Vocabulary" -> {
            val v = analytics.vocabulary
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Metric("${v.newlyStudied}", "Studied", Modifier.weight(1f), accent = blue)
                Metric("${v.stableFsrs}", "Stable FSRS", Modifier.weight(1f), accent = greenText)
                Metric("${v.currentlyDue}", "Due now", Modifier.weight(1f), accent = if (v.currentlyDue > 25) amber else textMain)
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Metric("${v.collectedInbox}", "Inbox total", Modifier.weight(1f))
                Metric("${v.inboxBacklog}", "Inbox backlog", Modifier.weight(1f), accent = amber)
                Metric("${v.selectedForLearning}", "Queued", Modifier.weight(1f))
            }
            Sheet {
                Label("Knowledge & Retention Estimates")
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Metric(v.predictedRetentionPct?.let { "$it%" } ?: "—", "Predicted recall", Modifier.weight(1f), accent = greenText)
                    Metric(v.averageReviewSuccessPct?.let { "$it%" } ?: "—", "Review success", Modifier.weight(1f), accent = blue)
                }
                Hint("Known/stable: ${v.knownCount} · Remaining catalog: ${v.unknownEstimate} · Academic covered: ${v.academicCovered}/${v.academicTotal}")
                Hint("Collected or bookmarked inbox words (${v.collectedInbox}, ${v.inboxUnresolved} unmatched) are NEVER counted as mastered.")
            }
            InteractiveTrendLineChart(
                title = "Review Retention Trend",
                unit = "% recalled",
                points = v.retentionTrend.map { (d, pct) -> ChartBarPoint(d.substring(5), d, pct.toFloat(), "$pct%") },
                lineColor = green,
                emptyMessage = "Complete scheduled FSRS reviews on at least 2 days to plot retention trends.",
            )
            Sheet {
                Label("Vocabulary by CEFR")
                v.byCefr.forEach { c ->
                    val covered = c.known + c.learning + c.reviewing + c.stable
                    BreakdownBarRow(
                        label = c.level,
                        valueText = "$covered / ${c.total}",
                        fraction = covered.toFloat() / c.total.coerceAtLeast(1),
                        barColor = green,
                        subtext = "Known ${c.known} · Stable ${c.stable} · Learning ${c.learning} · Reviewing ${c.reviewing}",
                    )
                }
            }
            if (v.byTopic.isNotEmpty()) {
                Sheet {
                    Label("Top Vocabulary Topics")
                    v.byTopic.take(8).forEach { t ->
                        BreakdownBarRow(
                            label = t.topic,
                            valueText = "${t.studied} / ${t.total}",
                            fraction = t.studied.toFloat() / t.total.coerceAtLeast(1),
                            barColor = blue,
                        )
                    }
                }
            }
            if (v.frequentlyMissed.isNotEmpty()) {
                Sheet {
                    Label("Frequently Missed Words", color = amber)
                    v.frequentlyMissed.forEach { (w, cnt) ->
                        Text("$w · $cnt misses", fontSize = 14.sp, color = textMain)
                    }
                }
            }
        }

        "Reading" -> {
            val r = analytics.reading
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Metric("${r.passagesCompleted}", "Completed", Modifier.weight(1f), accent = blue)
                Metric("${r.totalWordsRead}", "Words read", Modifier.weight(1f), accent = greenText)
                Metric(r.averageComprehensionPct?.let { "$it%" } ?: "—", "Accuracy", Modifier.weight(1f), accent = greenText)
            }
            Sheet {
                Label("Reading Metrics")
                Hint("Active question time: ${r.totalActiveReadingSeconds}s · Vocab collected from reading: ${r.vocabCollectedFromReading}")
                r.vocabPerPassage?.let { Hint(String.format(Locale.ENGLISH, "Avg vocabulary collected per passage: %.1f", it)) }
                r.readingSpeedWpm?.let {
                    Hint("Estimated interaction pace: ~$it words/min (speed is NOT a proxy for comprehension)")
                } ?: Hint("Reading speed requires recorded timed reading rounds.")
                r.strongestTheme?.let { Text("Strongest theme: $it", color = greenText, fontSize = 13.sp) }
                r.weakestTheme?.let { Text("Weakest theme: $it", color = amber, fontSize = 13.sp) }
            }
            if (r.byCefr.isNotEmpty()) {
                Sheet {
                    Label("Comprehension by CEFR (kept separate by difficulty)")
                    r.byCefr.forEach { (lv, pair) ->
                        val pct = if (pair.second > 0) (pair.first * 100) / pair.second else 0
                        BreakdownBarRow(lv, "$pct% (${pair.first}/${pair.second})", pct / 100f, barColor = blue)
                    }
                }
            }
            if (r.repeatedImprovements.isNotEmpty()) {
                Sheet {
                    Label("Repeated-Reading Improvement", color = greenText)
                    r.repeatedImprovements.forEach { rep ->
                        Text("${rep.title} (${rep.level}): ${rep.firstPct}% → ${rep.latestPct}% (${rep.attempts} attempts)", fontSize = 13.sp, color = textMain)
                    }
                }
            }
            if (r.history.isEmpty()) {
                EmptyStateCard("No reading rounds completed yet", "Finish a Reading Academy passage to track comprehension by level and theme.")
            } else {
                Sheet {
                    Label("Recent Reading History")
                    r.history.take(8).forEach { h ->
                        Text("${h.day} · ${h.title} (${h.level}) — ${h.correct}/${h.attempted} (${h.pct}%)", fontSize = 13.sp, color = textMain)
                    }
                }
            }
        }

        "Grammar" -> {
            val g = analytics.grammar
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Metric("${g.topicsStarted}/${g.totalTopics}", "Started", Modifier.weight(1f), accent = blue)
                Metric("${g.masteryCounts["Mastered"] ?: 0}", "Mastered", Modifier.weight(1f), accent = greenText)
                Metric(g.overallAccuracyPct?.let { "$it%" } ?: "—", "Accuracy", Modifier.weight(1f), accent = greenText)
            }
            Sheet {
                Label("Grammar Progress")
                Hint("Lessons studied: ${g.lessonsCompleted} · Exercises attempted: ${g.exercisesAttempted} · Tests: ${g.testCount} (avg ${g.averageTestPct?.let { "$it%" } ?: "—"}) · Active time: ${g.activeGrammarSeconds}s")
                g.progressByCefr.forEach { (lv, pair) ->
                    BreakdownBarRow(lv, "${pair.first} / ${pair.second} mastered", pair.first.toFloat() / pair.second.coerceAtLeast(1), barColor = green)
                }
            }
            if (g.accuracyByCategory.isNotEmpty()) {
                Sheet {
                    Label("Accuracy by Grammar Category")
                    g.accuracyByCategory.forEach { (cat, pct) ->
                        BreakdownBarRow(cat, "$pct%", pct / 100f, barColor = if (pct >= 80) green else amber)
                    }
                }
            }
            if (g.weakAreas.isNotEmpty() || g.needsDelayedReview.isNotEmpty() || g.recentImprovements.isNotEmpty()) {
                Sheet {
                    Label("Focus Areas & Improvements")
                    if (g.needsDelayedReview.isNotEmpty()) Text("Delayed review due: ${g.needsDelayedReview.joinToString()}", color = blue, fontSize = 13.sp)
                    if (g.weakAreas.isNotEmpty()) Text("Needs practice: ${g.weakAreas.joinToString()}", color = amber, fontSize = 13.sp)
                    if (g.recentImprovements.isNotEmpty()) Text("Improved: ${g.recentImprovements.joinToString()}", color = greenText, fontSize = 13.sp)
                }
            }
        }

        else -> {
            val l = analytics.listening
            val sp = analytics.speaking
            val wr = analytics.writing
            Sheet {
                Label("Listening (Hören)")
                if (l.tasksCompleted == 0 && l.answersAttempted == 0) {
                    Hint("No listening tasks recorded yet. Try Listening in Context under Learn → Skills & Explore.")
                } else {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Metric("${l.tasksCompleted}", "Completed", Modifier.weight(1f), accent = blue)
                        Metric(l.accuracyPct?.let { "$it%" } ?: "—", "Accuracy", Modifier.weight(1f), accent = greenText)
                        Metric("${l.activeSeconds}s", "Active time", Modifier.weight(1f))
                    }
                    l.byLevel.forEach { (lv, p) ->
                        val pct = if (p.second > 0) (p.first * 100) / p.second else 0
                        BreakdownBarRow("Level $lv", "$pct%", pct / 100f, barColor = blue)
                    }
                }
            }
            Sheet {
                Label("Speaking (Sprechen)")
                Hint("Self-reported practice is strictly separated from assessed speaking results. LEXORA never invents automatic fluency scores.")
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Metric("${sp.selfReportedCount}", "Self-reported", Modifier.weight(1f), accent = amber)
                    Metric("${sp.assessedCount}", "Assessed", Modifier.weight(1f), accent = blue)
                    Metric(sp.latestAssessedPct?.let { "$it%" } ?: "—", "Latest score", Modifier.weight(1f), accent = greenText)
                }
                if (sp.selfReportedMinutes > 0) Hint("Self-reported speaking practice: ${formatStudyTime(sp.selfReportedMinutes)}")
                sp.latestEvidenceLabel?.let { Hint("Latest evaluated evidence: $it") }
            }
            Sheet {
                Label("Writing (Schreiben)")
                Hint("Evaluated writing assessments remain distinct from self-reported writing practice.")
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Metric("${wr.selfReportedCount}", "Self-reported", Modifier.weight(1f), accent = amber)
                    Metric("${wr.assessedCount}", "Assessed", Modifier.weight(1f), accent = blue)
                    Metric(wr.latestAssessedPct?.let { "$it%" } ?: "—", "Latest score", Modifier.weight(1f), accent = greenText)
                }
                if (wr.selfReportedMinutes > 0) Hint("Self-reported writing practice: ${formatStudyTime(wr.selfReportedMinutes)}")
                wr.recentRubricNotes.forEach { (d, note) ->
                    Text("$d · $note", fontSize = 12.sp, color = faint)
                }
            }
        }
    }
}
