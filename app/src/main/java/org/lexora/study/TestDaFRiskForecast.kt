package org.lexora.study

import org.json.JSONArray
import org.json.JSONObject
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Locale
import kotlin.math.roundToInt

data class ExamMilestone(
    val id: String,
    val title: String,
    val dueDate: String,
    val completed: Boolean = false,
)

data class TestDaFConfig(
    val targetDate: LocalDate?,
    val format: String = "Digital TestDaF",
    val targetLevel: String = "TDN4 in all 4 skills",
    val weeklyHours: Double = 5.0,
    val restDays: Set<DayOfWeek> = emptySet(),
    val milestones: List<ExamMilestone> = emptyList(),
) {
    fun daysUntil(today: LocalDate): Long? = targetDate?.let { ChronoUnit.DAYS.between(today, it) }
    fun isExpired(today: LocalDate): Boolean = targetDate != null && targetDate.isBefore(today)
}

enum class SkillPrepStatus(val label: String, val priorityWeight: Int) {
    BELOW_TARGET("Below Target", 100),
    INSUFFICIENT_EVIDENCE("Insufficient Evidence", 90),
    NEEDS_REASSESSMENT("Needs Reassessment", 80),
    FOUNDATION_BUILDING("Foundation Building", 70),
    APPROACHING_TARGET("Approaching Target", 45),
    TARGET_RANGE_INDICATED("Target Range Indicated", 10),
}

data class SkillReadinessPanel(
    val skill: String,
    val status: SkillPrepStatus,
    val latest: TestDaFAssessmentRecord?,
    val history: List<TestDaFAssessmentRecord>,
    val comparableTrendDeltaPct: Int?,
    val trendNote: String,
    val confidenceNote: String,
    val nextRecommendedDiagnostic: String,
    val daysSinceLatest: Long?,
)

enum class RiskLevel(val label: String) {
    LOW("Low"),
    MODERATE("Moderate"),
    HIGH("High"),
    UNKNOWN("Unknown / Insufficient Evidence"),
}

data class RiskAssessment(
    val title: String,
    val level: RiskLevel,
    val summary: String,
    val reasons: List<String>,
)

data class RiskAnalysisReport(
    val learningRisk: RiskAssessment,
    val scheduleRisk: RiskAssessment,
    val examReadinessRisk: RiskAssessment,
    val weakestSkill: SkillReadinessPanel,
    val priorityOrder: List<SkillReadinessPanel>,
)

data class ForecastScenario(
    val name: String,
    val windowLabel: String,
    val assumptions: String,
)

data class ReadinessForecast(
    val sufficient: Boolean,
    val headline: String,
    val disclaimer: String,
    val scenarios: List<ForecastScenario>,
    val missingRequirements: List<String>,
)

data class VelocityTrack(
    val title: String,
    val summary: String,
    val metrics: List<Pair<String, String>>,
    val comparabilityNote: String,
)

data class LearningVelocityReport(
    val windowDays: Int,
    val activityGrowth: VelocityTrack,
    val performanceGrowth: VelocityTrack,
    val examReadinessGrowth: VelocityTrack,
)

data class MissionControlBundle(
    val config: TestDaFConfig,
    val panels: List<SkillReadinessPanel>,
    val risk: RiskAnalysisReport,
    val forecast: ReadinessForecast,
    val velocity7: LearningVelocityReport,
    val velocity30: LearningVelocityReport,
    val velocity90: LearningVelocityReport,
)

private val monthYearFmt = DateTimeFormatter.ofPattern("MMMM yyyy", Locale.ENGLISH)

fun evaluateSkillPanel(
    skill: String,
    records: List<TestDaFAssessmentRecord>,
    today: LocalDate,
    staleDays: Long = 45L,
): SkillReadinessPanel {
    val sorted = records.filter { it.skill == skill }.sortedByDescending { it.assessedAt }
    // Prefer higher reliability when picking latest exam-relevant benchmark, while always displaying the newest record
    val latest = sorted.firstOrNull()
    val evaluated = sorted.filter { it.evidenceType != EvidenceReliability.SELF_REPORTED }
    val latestEvaluated = evaluated.firstOrNull()
    val latestExamGrade = sorted.firstOrNull { it.supportsTargetIndication }

    val latestDate = latest?.let { runCatching { LocalDate.parse(it.day) }.getOrNull() }
    val daysSince = latestDate?.let { ChronoUnit.DAYS.between(it, today).coerceAtLeast(0L) }

    val status = when {
        latestEvaluated == null -> SkillPrepStatus.INSUFFICIENT_EVIDENCE
        daysSince != null && daysSince > staleDays -> SkillPrepStatus.NEEDS_REASSESSMENT
        !latestEvaluated.isExamRelevant -> SkillPrepStatus.FOUNDATION_BUILDING
        latestExamGrade != null && isTdn4OrAbove(latestExamGrade) &&
            (runCatching { ChronoUnit.DAYS.between(LocalDate.parse(latestExamGrade.day), today) }.getOrDefault(0L) <= staleDays) ->
            SkillPrepStatus.TARGET_RANGE_INDICATED
        evaluated.filter { it.isExamRelevant && it.evidenceType == EvidenceReliability.AI_EVALUATED }.take(2).let { ai ->
            ai.size >= 2 && ai.all { it.ratio >= 0.78 }
        } -> SkillPrepStatus.TARGET_RANGE_INDICATED
        latestEvaluated.tdnLevel == "TDN3" || latestEvaluated.ratio in 0.60..0.749 -> SkillPrepStatus.APPROACHING_TARGET
        latestEvaluated.ratio >= 0.75 && !latestEvaluated.supportsTargetIndication -> SkillPrepStatus.APPROACHING_TARGET
        else -> SkillPrepStatus.BELOW_TARGET
    }

    // Comparable trend: compare only records with matching difficulty band & non-self-reported evidence
    val comparable = if (latestEvaluated != null) {
        evaluated.filter { it.difficulty == latestEvaluated.difficulty && it.evidenceType == latestEvaluated.evidenceType }
    } else emptyList()

    val (deltaPct, trendNote) = when {
        comparable.size >= 2 -> {
            val newestPct = (comparable.first().ratio * 100.0).roundToInt()
            val olderPct = (comparable.last().ratio * 100.0).roundToInt()
            val diff = newestPct - olderPct
            val sign = if (diff > 0) "+" else ""
            diff to "$sign${diff} pts across ${comparable.size} comparable ${latestEvaluated!!.difficulty} assessments"
        }
        evaluated.size >= 2 -> null to "Assessments span different levels or scoring formats; shown separately to avoid false comparisons."
        evaluated.size == 1 -> null to "One graded assessment logged — add another comparable check to measure trend."
        sorted.isNotEmpty() -> null to "Only self-reported practice recorded — does not establish verified proficiency."
        else -> null to "No assessments recorded for $skill yet."
    }

    val confidence = when {
        latestExamGrade != null && latestExamGrade.evidenceType == EvidenceReliability.OFFICIAL_EXAM ->
            "High confidence · Official Digital TestDaF result"
        latestExamGrade != null ->
            "Moderate–High confidence · Exam-like diagnostic"
        latestEvaluated?.evidenceType == EvidenceReliability.AI_EVALUATED ->
            "Moderate confidence · Rubric/AI-evaluated practice"
        latestEvaluated != null ->
            "Low–Moderate confidence · Automatically graded practice (${latestEvaluated.difficulty})"
        sorted.isNotEmpty() ->
            "Low confidence · Self-reported practice only"
        else -> "No graded evidence"
    }

    val nextAction = when (status) {
        SkillPrepStatus.INSUFFICIENT_EVIDENCE ->
            "Log a timed Digital TestDaF $skill diagnostic (or B2 practice) to establish a baseline."
        SkillPrepStatus.NEEDS_REASSESSMENT ->
            "Latest $skill evidence is ${daysSince ?: staleDays}+ days old — take a fresh timed diagnostic."
        SkillPrepStatus.FOUNDATION_BUILDING ->
            "Build from ${latestEvaluated?.difficulty ?: "current"} level up to B2/TestDaF-level $skill tasks."
        SkillPrepStatus.BELOW_TARGET ->
            "Prioritize focused $skill drills and review rubric errors before re-testing."
        SkillPrepStatus.APPROACHING_TARGET ->
            if (latestExamGrade == null) "Complete an exam-like Digital TestDaF $skill diagnostic to confirm TDN4 range."
            else "Close remaining gaps in $skill with timed TestDaF tasks."
        SkillPrepStatus.TARGET_RANGE_INDICATED ->
            "Maintain $skill readiness with periodic timed check-ins every 3–4 weeks."
    }

    return SkillReadinessPanel(
        skill = skill,
        status = status,
        latest = latest,
        history = sorted,
        comparableTrendDeltaPct = deltaPct,
        trendNote = trendNote,
        confidenceNote = confidence,
        nextRecommendedDiagnostic = nextAction,
        daysSinceLatest = daysSince,
    )
}

private fun isTdn4OrAbove(r: TestDaFAssessmentRecord): Boolean {
    if (r.tdnLevel == "TDN4" || r.tdnLevel == "TDN5") return true
    if (r.tdnLevel == "Below TDN3" || r.tdnLevel == "TDN3") return false
    return r.ratio >= 0.75
}

fun evaluateRisks(
    config: TestDaFConfig,
    panels: List<SkillReadinessPanel>,
    analytics: AnalyticsBundle,
    today: LocalDate,
): RiskAnalysisReport {
    val prioritized = panels.sortedWith(
        compareByDescending<SkillReadinessPanel> { it.status.priorityWeight }
            .thenBy { it.latest?.ratio ?: -1.0 },
    )
    val weakest = prioritized.first()
    val daysLeft = config.daysUntil(today)

    // 1. Learning Risk (performance trajectory across skills, grammar, reading, retention)
    val learningReasons = mutableListOf<String>()
    val evaluatedCount = panels.count { it.history.any { r -> r.evidenceType != EvidenceReliability.SELF_REPORTED } }
    val decliningSkills = panels.filter { (it.comparableTrendDeltaPct ?: 0) <= -8 }
    val belowTargetSkills = panels.filter { it.status == SkillPrepStatus.BELOW_TARGET }
    val lowGrammar = analytics.grammar.overallAccuracyPct?.let { it < 70 } == true
    val lowReading = analytics.reading.averageComprehensionPct?.let { it < 65 } == true
    val lowRetention = analytics.vocabulary.averageReviewSuccessPct?.let { it < 75 } == true

    val hasAnyLearningData = evaluatedCount > 0 || analytics.grammar.exercisesAttempted >= 4 ||
        analytics.reading.passagesCompleted >= 1 || analytics.vocabulary.newlyStudied >= 5

    val learningLevel = when {
        !hasAnyLearningData -> {
            learningReasons += "Not enough graded vocabulary, grammar, reading, or skill assessments to evaluate learning trajectory."
            RiskLevel.UNKNOWN
        }
        decliningSkills.isNotEmpty() || belowTargetSkills.size >= 2 || (lowGrammar && lowReading) -> {
            if (decliningSkills.isNotEmpty()) learningReasons += "Comparable score decline detected in: ${decliningSkills.joinToString { it.skill }}."
            if (belowTargetSkills.isNotEmpty()) learningReasons += "Below-target performance in: ${belowTargetSkills.joinToString { it.skill }}."
            if (lowGrammar) learningReasons += "Grammar accuracy is ${analytics.grammar.overallAccuracyPct}% (below 70% threshold)."
            if (lowReading) learningReasons += "Reading comprehension average is ${analytics.reading.averageComprehensionPct}%."
            RiskLevel.HIGH
        }
        belowTargetSkills.size == 1 || lowGrammar || lowReading || lowRetention -> {
            if (belowTargetSkills.isNotEmpty()) learningReasons += "${belowTargetSkills.first().skill} is currently below target range."
            if (lowGrammar) learningReasons += "Grammar exercise accuracy (${analytics.grammar.overallAccuracyPct}%) needs consolidation."
            if (lowReading) learningReasons += "Reading comprehension (${analytics.reading.averageComprehensionPct}%) needs reinforcement."
            if (lowRetention) learningReasons += "FSRS review recall rate (${analytics.vocabulary.averageReviewSuccessPct}%) is below 75%."
            RiskLevel.MODERATE
        }
        else -> {
            learningReasons += "Recorded practice and assessment trends are stable or improving across active skills."
            RiskLevel.LOW
        }
    }

    // 2. Schedule Risk (study pace vs planned hours, consistency, FSRS backlog, time to exam)
    val scheduleReasons = mutableListOf<String>()
    val plannedWeeklyMin = (config.weeklyHours * 60.0).roundToInt().coerceAtLeast(30)
    val actualWeeklyMin = analytics.activity.weeklyMinutes
    val completionRatio = actualWeeklyMin.toDouble() / plannedWeeklyMin.toDouble()
    val dueBacklog = analytics.vocabulary.currentlyDue

    val scheduleLevel = when {
        config.targetDate == null -> {
            scheduleReasons += "No target Digital TestDaF date is set yet. Configure an exam date to evaluate schedule pressure."
            if (completionRatio < 0.6) scheduleReasons += "Weekly study logged: ${formatStudyTime(actualWeeklyMin)} vs ${formatStudyTime(plannedWeeklyMin)} planned."
            RiskLevel.UNKNOWN
        }
        config.isExpired(today) -> {
            scheduleReasons += "Target exam date (${config.targetDate}) has already passed — update your exam date."
            RiskLevel.HIGH
        }
        (daysLeft != null && daysLeft <= 45 && completionRatio < 0.70) ||
            (completionRatio < 0.45 && analytics.activity.activeDaysInRange < 3) ||
            dueBacklog >= 80 -> {
            scheduleReasons += "Actual study last 7 days (${formatStudyTime(actualWeeklyMin)}) is well below weekly plan (${formatStudyTime(plannedWeeklyMin)})."
            if (daysLeft != null) scheduleReasons += "$daysLeft days remain until your target exam (${config.targetDate})."
            if (dueBacklog >= 80) scheduleReasons += "Large FSRS review backlog: $dueBacklog cards currently due."
            RiskLevel.HIGH
        }
        completionRatio < 0.80 || dueBacklog >= 30 || (analytics.activity.weeklyConsistencyPct ?: 100) < 60 -> {
            scheduleReasons += "Weekly study pace is ${(completionRatio * 100).roundToInt()}% of your ${formatStudyTime(plannedWeeklyMin)} plan."
            if (dueBacklog >= 30) scheduleReasons += "$dueBacklog FSRS reviews due — clear reviews before adding heavy new-word volume."
            analytics.activity.weeklyConsistencyPct?.let {
                if (it < 60) scheduleReasons += "Planned study-day consistency is $it%."
            }
            RiskLevel.MODERATE
        }
        else -> {
            scheduleReasons += "Weekly study pace (${formatStudyTime(actualWeeklyMin)} / ${formatStudyTime(plannedWeeklyMin)}) and review queue ($dueBacklog due) are on track."
            RiskLevel.LOW
        }
    }

    // 3. Exam Readiness Risk (strictly governed by ALL FOUR required TDN4 skills — weakest skill wins!)
    val examReasons = mutableListOf<String>()
    val allTarget = panels.all { it.status == SkillPrepStatus.TARGET_RANGE_INDICATED }
    val unassessed = panels.filter { it.status == SkillPrepStatus.INSUFFICIENT_EVIDENCE }
    val stale = panels.filter { it.status == SkillPrepStatus.NEEDS_REASSESSMENT }
    val below = panels.filter { it.status == SkillPrepStatus.BELOW_TARGET || it.status == SkillPrepStatus.FOUNDATION_BUILDING }

    val examLevel = when {
        allTarget -> {
            examReasons += "All four required skills (Lesen, Hören, Schreiben, Sprechen) have recent exam-level evidence in the TDN4 range."
            RiskLevel.LOW
        }
        unassessed.size == 4 -> {
            examReasons += "None of the four Digital TestDaF skills have graded exam-relevant diagnostic evidence yet."
            RiskLevel.UNKNOWN
        }
        below.isNotEmpty() && (daysLeft == null || daysLeft <= 90) -> {
            examReasons += "All four skills must reach TDN4 independently. Weakest priority: ${weakest.skill} (${weakest.status.label})."
            if (below.isNotEmpty()) examReasons += "Below-target/foundation skills: ${below.joinToString { it.skill }}."
            if (unassessed.isNotEmpty()) examReasons += "Unassessed skills: ${unassessed.joinToString { it.skill }}."
            RiskLevel.HIGH
        }
        unassessed.isNotEmpty() && daysLeft != null && daysLeft <= 60 -> {
            examReasons += "Only $daysLeft days remain and ${unassessed.joinToString { it.skill }} still lack diagnostic evidence."
            RiskLevel.HIGH
        }
        unassessed.isNotEmpty() && below.isEmpty() && stale.isEmpty() -> {
            examReasons += "Remaining unassessed skill(s): ${unassessed.joinToString { it.skill }}. Cannot confirm 4-skill readiness until all four are diagnosed."
            RiskLevel.UNKNOWN
        }
        else -> {
            examReasons += "Weakest skill priority: ${weakest.skill} (${weakest.status.label}). Strong performance in one skill cannot offset another for TDN4."
            if (stale.isNotEmpty()) examReasons += "Needs reassessment (>45 days old): ${stale.joinToString { it.skill }}."
            RiskLevel.MODERATE
        }
    }

    return RiskAnalysisReport(
        learningRisk = RiskAssessment("Learning Risk", learningLevel, learningReasons.first(), learningReasons),
        scheduleRisk = RiskAssessment("Schedule Risk", scheduleLevel, scheduleReasons.first(), scheduleReasons),
        examReadinessRisk = RiskAssessment("Exam Readiness Risk", examLevel, examReasons.first(), examReasons),
        weakestSkill = weakest,
        priorityOrder = prioritized,
    )
}

/**
 * Conservative, conditional readiness window forecasting.
 * NEVER converts XP or raw study hours directly into TDN4 readiness.
 * Requires comparable exam-relevant assessment evidence spanning >= 14 days across >= 2 skills,
 * or recent exam-diagnostic/official coverage across all 4 skills.
 */
fun computeReadinessForecast(
    config: TestDaFConfig,
    panels: List<SkillReadinessPanel>,
    today: LocalDate,
): ReadinessForecast {
    val disclaimer = "Conditional preparation estimate based on comparable skill assessments — NOT a guarantee of official Digital TestDaF results."
    val missing = mutableListOf<String>()

    val examGradePanels = panels.filter { p -> p.history.any { it.isExamRelevant } }
    val unassessedNames = panels.filter { p -> p.history.none { it.isExamRelevant } }.map { it.skill }
    if (unassessedNames.isNotEmpty()) {
        missing += "Add B2/TestDaF diagnostic evidence for: ${unassessedNames.joinToString(", ")}."
    }

    val longitudinalSkills = panels.filter { p ->
        val rel = p.history.filter { it.isExamRelevant }.sortedBy { it.assessedAt }
        if (rel.size < 2) false
        else {
            val d1 = runCatching { LocalDate.parse(rel.first().day) }.getOrNull()
            val d2 = runCatching { LocalDate.parse(rel.last().day) }.getOrNull()
            d1 != null && d2 != null && ChronoUnit.DAYS.between(d1, d2) >= 14L
        }
    }

    val allFourInTarget = panels.all { it.status == SkillPrepStatus.TARGET_RANGE_INDICATED }
    val allFourDiagnosed = panels.all { p -> p.history.any { it.supportsTargetIndication } }

    if (!allFourInTarget && !(allFourDiagnosed || (examGradePanels.size >= 2 && longitudinalSkills.isNotEmpty()))) {
        if (longitudinalSkills.isEmpty() && !allFourDiagnosed) {
            missing += "Record at least two comparable B2/TestDaF assessments at least 14 days apart to measure real score progression."
        }
        return ReadinessForecast(
            sufficient = false,
            headline = "Insufficient evidence for a reliable readiness forecast.",
            disclaimer = disclaimer,
            scenarios = emptyList(),
            missingRequirements = missing.distinct(),
        )
    }

    if (allFourInTarget) {
        val currentMonth = YearMonth.from(today)
        val nextMonth = currentMonth.plusMonths(1)
        val windowStr = "${currentMonth.format(monthYearFmt)} – ${nextMonth.format(monthYearFmt)}"
        return ReadinessForecast(
            sufficient = true,
            headline = "Current evidence indicates TDN4 target range across all four skills.",
            disclaimer = disclaimer,
            scenarios = listOf(
                ForecastScenario("Current Learning Pace", windowStr, "Maintain balanced timed practice across all 4 skills."),
            ),
            missingRequirements = emptyList(),
        )
    }

    // Estimate remaining gap based strictly on weakest evaluated skill score vs 75% TDN4 threshold
    val worstRatio = panels.mapNotNull { p ->
        p.history.firstOrNull { it.isExamRelevant }?.ratio
    }.minOrNull() ?: 0.50
    val gapPct = ((0.78 - worstRatio) * 100.0).coerceIn(5.0, 55.0)

    // Estimate monthly gain rate from comparable longitudinal assessments (conservative bounds: 2–6 pts/month)
    val observedMonthlyGains = longitudinalSkills.mapNotNull { p ->
        val rel = p.history.filter { it.isExamRelevant }.sortedBy { it.assessedAt }
        val d1 = runCatching { LocalDate.parse(rel.first().day) }.getOrNull() ?: return@mapNotNull null
        val d2 = runCatching { LocalDate.parse(rel.last().day) }.getOrNull() ?: return@mapNotNull null
        val months = (ChronoUnit.DAYS.between(d1, d2) / 30.0).coerceAtLeast(0.5)
        ((rel.last().ratio - rel.first().ratio) * 100.0) / months
    }
    val currentMonthlyRate = (observedMonthlyGains.average().takeIf { !it.isNaN() && it > 0.5 } ?: 3.5).coerceIn(2.0, 6.0)
    val conservativeRate = (currentMonthlyRate * 0.65).coerceAtLeast(1.5)
    val improvedRate = (currentMonthlyRate * 1.30).coerceAtMost(7.5)

    // Add extra buffer months for any unassessed skills
    val missingSkillBuffer = unassessedNames.size * 1.5

    fun windowFor(rate: Double): String {
        val months = (gapPct / rate) + missingSkillBuffer
        val lowMonths = months.roundToInt().coerceIn(1, 30)
        val highMonths = (lowMonths + 2).coerceAtMost(36)
        val start = YearMonth.from(today).plusMonths(lowMonths.toLong())
        val end = YearMonth.from(today).plusMonths(highMonths.toLong())
        return "${start.format(monthYearFmt)} – ${end.format(monthYearFmt)}"
    }

    val currentWindow = windowFor(currentMonthlyRate)
    return ReadinessForecast(
        sufficient = true,
        headline = "Estimated readiness window (Current Pace): $currentWindow",
        disclaimer = disclaimer,
        scenarios = listOf(
            ForecastScenario(
                "Conservative Progress",
                windowFor(conservativeRate),
                "Assumes slower diagnostic improvement (~${conservativeRate.roundToInt()} pts/month) and full 4-skill remediation.",
            ),
            ForecastScenario(
                "Current Learning Pace",
                currentWindow,
                "Projects current comparable assessment pace (~${currentMonthlyRate.roundToInt()} pts/month) toward TDN4 across all 4 skills.",
            ),
            ForecastScenario(
                "Improved Study Plan",
                windowFor(improvedRate),
                "Assumes focused weakest-skill diagnostics + consistent ${config.weeklyHours.roundToInt()}h+/week targeted practice.",
            ),
        ),
        missingRequirements = if (unassessedNames.isNotEmpty()) {
            listOf("Forecast uncertainty remains elevated until ${unassessedNames.joinToString(", ")} are formally diagnosed.")
        } else emptyList(),
    )
}

fun buildVelocityReport(
    windowDays: Int,
    dailySeriesAll: List<DailyActivityPoint>,
    assessments: List<TestDaFAssessmentRecord>,
    readingHistory: List<ReadingHistoryItem>,
    today: LocalDate,
): LearningVelocityReport {
    val curStart = today.minusDays(windowDays - 1L).toString()
    val prevStart = today.minusDays(windowDays * 2L - 1L).toString()
    val prevEnd = today.minusDays(windowDays.toLong()).toString()
    val todayStr = today.toString()

    val curDays = dailySeriesAll.filter { it.day in curStart..todayStr }
    val prevDays = dailySeriesAll.filter { it.day in prevStart..prevEnd }

    val curXp = curDays.sumOf { it.xp }
    val prevXp = prevDays.sumOf { it.xp }
    val curMin = curDays.sumOf { it.totalMinutes }.roundToInt().toLong()
    val prevMin = prevDays.sumOf { it.totalMinutes }.roundToInt().toLong()
    val curActive = curDays.count { it.active }
    val prevActive = prevDays.count { it.active }

    val activity = VelocityTrack(
        title = "Activity Growth (${windowDays}d)",
        summary = "Measures how much you studied — distinct from verified German proficiency.",
        metrics = listOf(
            "XP Growth" to "${formatGrowth(xpGrowthPercent(curXp, prevXp))} ($curXp XP)",
            "Study Time Growth" to "${formatGrowth(xpGrowthPercent(curMin, prevMin))} (${formatStudyTime(curMin.toInt())})",
            "Active Days" to "$curActive days (prev: $prevActive)",
        ),
        comparabilityNote = "XP and study hours reflect effort and consistency, not exam-level mastery.",
    )

    val curLearned = curDays.sumOf { it.learned }.toLong()
    val prevLearned = prevDays.sumOf { it.learned }.toLong()
    val curRead = readingHistory.filter { it.day in curStart..todayStr }
    val prevRead = readingHistory.filter { it.day in prevStart..prevEnd }
    val curLevels = curRead.map { it.level }.toSet()
    val prevLevels = prevRead.map { it.level }.toSet()
    val readingDeltaText = when {
        curRead.isEmpty() || prevRead.isEmpty() -> "Need reading rounds in both periods"
        curLevels != prevLevels -> "Different CEFR levels (${prevLevels.joinToString("/")} vs ${curLevels.joinToString("/")}) — shown separately"
        else -> {
            val cPct = (curRead.sumOf { it.correct } * 100) / curRead.sumOf { it.attempted }.coerceAtLeast(1)
            val pPct = (prevRead.sumOf { it.correct } * 100) / prevRead.sumOf { it.attempted }.coerceAtLeast(1)
            val d = cPct - pPct
            "${if (d > 0) "+" else ""}$d pts ($pPct% → $cPct%)"
        }
    }

    val performance = VelocityTrack(
        title = "Learning Performance Growth (${windowDays}d)",
        summary = "Tracks accuracy and acquisition changes in comparable learning tasks.",
        metrics = listOf(
            "Vocabulary Acquired" to "$curLearned words (prev: $prevLearned)",
            "Reading Accuracy" to readingDeltaText,
            "Scheduled Reviews" to "${curDays.sumOf { it.reviews }} (prev: ${prevDays.sumOf { it.reviews }})",
        ),
        comparabilityNote = "Results from different CEFR levels are never blended into a single percentage change.",
    )

    val curExam = assessments.filter { it.isExamRelevant && it.day in curStart..todayStr }
    val prevExam = assessments.filter { it.isExamRelevant && it.day in prevStart..prevEnd }
    val examMetrics = testDaFSkills.map { sk ->
        val c = curExam.filter { it.skill == sk }
        val p = prevExam.filter { it.skill == sk }
        val value = when {
            c.isEmpty() -> "No B2/TestDaF check in last ${windowDays}d"
            p.isEmpty() -> "Latest: ${(c.first().ratio * 100).roundToInt()}% (${c.first().difficulty})"
            c.first().difficulty != p.first().difficulty -> "Difficulty changed (${p.first().difficulty} → ${c.first().difficulty})"
            else -> {
                val cPct = (c.first().ratio * 100).roundToInt()
                val pPct = (p.first().ratio * 100).roundToInt()
                val d = cPct - pPct
                "${if (d > 0) "+" else ""}$d pts ($pPct% → $cPct%)"
            }
        }
        sk to value
    }
    val readiness = VelocityTrack(
        title = "Exam Readiness Growth (${windowDays}d)",
        summary = "Tracks comparable B2/TestDaF assessment evidence per required skill.",
        metrics = examMetrics,
        comparabilityNote = "Only B2, C1, or Digital TestDaF assessments contribute to readiness growth.",
    )

    return LearningVelocityReport(windowDays, activity, performance, readiness)
}

internal fun StudyRepository.loadTestDaFConfig(): TestDaFConfig {
    val examStr = pref("academy.exam")
    val targetDate = examStr.takeIf { it.isNotBlank() }?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
    val weeklyHours = pref("v61.exam.weeklyHours").toDoubleOrNull()
        ?: ((pref("academy.weekly").toIntOrNull() ?: 300) / 60.0)
    val daysPref = pref("academy.days").ifEmpty { "1,2,3,4,5,6,7" }
    val activeDayNums = if (daysPref == "rest") emptySet()
    else daysPref.split(",").mapNotNull { it.toIntOrNull() }.toSet()
    val restDays = DayOfWeek.entries.filter { it.value !in activeDayNums }.toSet()

    val milestones = pref("v61.exam.milestones").takeIf { it.isNotBlank() }?.let { raw ->
        runCatching {
            val arr = JSONArray(raw)
            List(arr.length()) { i ->
                val o = arr.getJSONObject(i)
                ExamMilestone(o.getString("id"), o.getString("title"), o.getString("dueDate"), o.optBoolean("completed"))
            }
        }.getOrDefault(emptyList())
    }.orEmpty()

    return TestDaFConfig(
        targetDate = targetDate,
        weeklyHours = weeklyHours.coerceIn(1.0, 45.0),
        restDays = restDays,
        milestones = milestones,
    )
}

internal fun StudyRepository.saveTestDaFConfig(config: TestDaFConfig) = db.tx {
    set("academy.exam", config.targetDate?.toString().orEmpty())
    set("v61.exam.weeklyHours", String.format(Locale.ENGLISH, "%.1f", config.weeklyHours))
    set("academy.weekly", (config.weeklyHours * 60.0).roundToInt().coerceIn(30, 3000).toString())
    val activeDays = DayOfWeek.entries.filter { it !in config.restDays }.map { it.value }.sorted()
    set("academy.days", if (activeDays.isEmpty()) "rest" else activeDays.joinToString(","))
    val arr = JSONArray()
    config.milestones.forEach { m ->
        arr.put(JSONObject().put("id", m.id).put("title", m.title).put("dueDate", m.dueDate).put("completed", m.completed))
    }
    set("v61.exam.milestones", arr.toString())
    set("academy.plan." + day(), "") // refresh today's auto plan on schedule change
}

internal fun StudyRepository.computeMissionControl(
    analytics: AnalyticsBundle,
    readings: List<Reading>,
): MissionControlBundle {
    val today = LocalDate.parse(day())
    val config = loadTestDaFConfig()
    val stored = testDaFAssessments().toMutableList()

    // Augment with auto-graded Reading Academy rounds (as AUTO_PRACTICE, never pretending to be Official!)
    val readingById = readings.associateBy { it.id }
    val existingSourceIds = stored.mapNotNull { it.sourceEventId }.toSet()
    db.rows(
        "SELECT id, day, at, content, level, attempted, correct FROM academy_event WHERE skill='Reading' AND activity='Comprehension' AND attempted>0 ORDER BY at DESC LIMIT 30",
    ) { c ->
        val id = c.getString(0)
        if (id !in existingSourceIds) {
            val p = readingById[c.getString(3)]
            TestDaFAssessmentRecord(
                id = "auto-read-$id",
                skill = "Lesen",
                evidenceType = EvidenceReliability.AUTO_PRACTICE,
                score = c.getInt(6).toDouble(),
                maxScore = c.getInt(5).toDouble(),
                tdnLevel = null,
                difficulty = p?.level ?: c.getString(4).orEmpty().ifEmpty { "B1" },
                format = "Reading Academy",
                rubricVersion = "key-v1",
                assessedAt = c.getLong(2),
                day = c.getString(1),
                notes = p?.title ?: c.getString(3),
                sourceEventId = id,
            )
        } else null
    }.filterNotNull().forEach { stored += it }

    // Augment with Listening in Context completed rounds (as AUTO_PRACTICE)
    db.rows(
        """SELECT item, date(max(at)/1000,'unixepoch','localtime'), max(at), count(*), sum(correct)
           FROM exploration WHERE mode='Listening in Context' AND part!='Completed'
           GROUP BY item, date(at/1000,'unixepoch','localtime') ORDER BY 3 DESC LIMIT 20""",
    ) { c ->
        val srcId = "auto-listen-${c.getString(0)}-${c.getString(1)}"
        if (srcId !in existingSourceIds) {
            TestDaFAssessmentRecord(
                id = srcId,
                skill = "Hören",
                evidenceType = EvidenceReliability.AUTO_PRACTICE,
                score = c.getInt(4).toDouble(),
                maxScore = c.getInt(3).toDouble(),
                tdnLevel = null,
                difficulty = "B1",
                format = "Listening in Context",
                rubricVersion = "key-v1",
                assessedAt = c.getLong(2),
                day = c.getString(1),
                notes = c.getString(0),
                sourceEventId = srcId,
            )
        } else null
    }.filterNotNull().forEach { stored += it }

    val panels = testDaFSkills.map { skill -> evaluateSkillPanel(skill, stored, today) }
    val risk = evaluateRisks(config, panels, analytics, today)
    val forecast = computeReadinessForecast(config, panels, today)

    val allTimeAnalytics = computeAnalytics(AnalyticsWindow.ALL_TIME, readings, emptyList(), emptyMap())
    val v7 = buildVelocityReport(7, allTimeAnalytics.activity.dailySeries, stored, analytics.reading.history, today)
    val v30 = buildVelocityReport(30, allTimeAnalytics.activity.dailySeries, stored, analytics.reading.history, today)
    val v90 = buildVelocityReport(90, allTimeAnalytics.activity.dailySeries, stored, analytics.reading.history, today)

    return MissionControlBundle(config, panels, risk, forecast, v7, v30, v90)
}
