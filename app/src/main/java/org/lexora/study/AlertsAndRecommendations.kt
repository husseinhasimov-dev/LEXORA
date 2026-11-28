package org.lexora.study

import java.time.LocalDate
import java.time.temporal.ChronoUnit
import java.util.UUID
import kotlin.math.roundToInt

enum class AlertCategory(val label: String) {
    PERFORMANCE("Performance"),
    STUDY_PLANNING("Study Planning"),
    EXAM("Exam"),
    POSITIVE("Positive Insights"),
}

enum class AlertSeverity(val label: String, val sortOrder: Int) {
    HIGH("High", 1),
    MEDIUM("Medium", 2),
    INFO("Info", 3),
    POSITIVE("Positive", 4),
}

data class LearningAlert(
    val fingerprint: String,
    val category: AlertCategory,
    val severity: AlertSeverity,
    val title: String,
    val explanation: String,
    val evidence: String,
    val period: String,
    val suggestedAction: String,
    val destinationRoute: String,
    val acknowledged: Boolean = false,
)

data class StudyPlanProposal(
    val fingerprint: String,
    val title: String,
    val skill: String,
    val metric: String,
    val target: Int,
    val rationale: String,
    val impactNote: String,
    val destinationRoute: String,
)

/**
 * Deterministic, offline-first Alert Engine.
 * Produces explainable alerts across all 4 categories while preventing noise:
 * - Never warns from 1 isolated bad session
 * - Never treats missing data as proven failure
 * - Deduplicates by stable fingerprint and respects dismissals
 */
fun generateLearningAlerts(
    analytics: AnalyticsBundle,
    mission: MissionControlBundle,
    dismissedStates: Map<String, Pair<String, Long>>,
    today: LocalDate,
    pendingQuestCount: Int = 0,
): List<LearningAlert> {
    val raw = mutableListOf<LearningAlert>()

    fun add(
        fingerprint: String,
        category: AlertCategory,
        severity: AlertSeverity,
        title: String,
        explanation: String,
        evidence: String,
        period: String,
        suggestedAction: String,
        route: String,
    ) {
        val state = dismissedStates[fingerprint]?.first
        if (state == "dismissed") return
        raw += LearningAlert(
            fingerprint = fingerprint,
            category = category,
            severity = severity,
            title = title,
            explanation = explanation,
            evidence = evidence,
            period = period,
            suggestedAction = suggestedAction,
            destinationRoute = route,
            acknowledged = state == "acknowledged",
        )
    }

    // ---------- 1. PERFORMANCE ALERTS ----------
    if (analytics.grammar.exercisesAttempted >= 6 && (analytics.grammar.overallAccuracyPct ?: 100) < 70) {
        add(
            fingerprint = "perf:grammar-accuracy:${analytics.grammar.overallAccuracyPct}",
            category = AlertCategory.PERFORMANCE,
            severity = AlertSeverity.MEDIUM,
            title = "Grammar accuracy needs reinforcement",
            explanation = "Your recent grammar exercises show recurring accuracy gaps across ${analytics.grammar.weakAreas.size.coerceAtLeast(1)} topic area(s).",
            evidence = "${analytics.grammar.exercisesCorrect}/${analytics.grammar.exercisesAttempted} correct (${analytics.grammar.overallAccuracyPct}%)",
            period = "Recorded Grammar Academy attempts",
            suggestedAction = "Open Grammar Academy → For You for targeted drills.",
            route = "Learn:Grammar",
        )
    }

    if (analytics.vocabulary.frequentlyMissed.size >= 3) {
        val topMissed = analytics.vocabulary.frequentlyMissed.take(3).joinToString(", ") { "${it.first} (×${it.second})" }
        add(
            fingerprint = "perf:vocab-repeated-misses:${analytics.vocabulary.frequentlyMissed.first().first}",
            category = AlertCategory.PERFORMANCE,
            severity = AlertSeverity.MEDIUM,
            title = "Repeated vocabulary mistakes detected",
            explanation = "Several vocabulary items have been missed repeatedly in recall and written exercises.",
            evidence = "Most missed: $topMissed",
            period = "Vocabulary exercise history",
            suggestedAction = "Run a focused Mistake Lab session to repair these words.",
            route = "Learn:Lab",
        )
    }

    // Reading decline: require at least 2 reading rounds to avoid warning on a single bad session
    if (analytics.reading.history.size >= 2) {
        val latest = analytics.reading.history.first()
        val priorSameLevel = analytics.reading.history.drop(1).firstOrNull { it.level == latest.level }
        if (priorSameLevel != null && latest.pct <= priorSameLevel.pct - 20 && latest.pct < 70) {
            add(
                fingerprint = "perf:reading-decline:${latest.level}:${latest.day}",
                category = AlertCategory.PERFORMANCE,
                severity = AlertSeverity.MEDIUM,
                title = "Reading comprehension dipped at ${latest.level}",
                explanation = "Comprehension on ${latest.title} dropped compared with your previous ${latest.level} passage.",
                evidence = "${priorSameLevel.pct}% → ${latest.pct}% (${latest.correct}/${latest.attempted})",
                period = "${priorSameLevel.day} to ${latest.day}",
                suggestedAction = "Revisit missed questions in Mistake Recovery or try a shorter ${latest.level} passage.",
                route = "Learn:Reading",
            )
        }
    }

    if (analytics.listening.answersAttempted >= 4 && (analytics.listening.accuracyPct ?: 100) < 70) {
        add(
            fingerprint = "perf:listening-difficulty:${analytics.listening.accuracyPct}",
            category = AlertCategory.PERFORMANCE,
            severity = AlertSeverity.MEDIUM,
            title = "Listening comprehension below 70%",
            explanation = "Graded listening answers show recurring difficulty recognizing spoken German details.",
            evidence = "${analytics.listening.answersCorrect}/${analytics.listening.answersAttempted} correct (${analytics.listening.accuracyPct}%)",
            period = "Listening in Context history",
            suggestedAction = "Replay passages at 0.85× speed and review transcripts.",
            route = "Learn:Explore",
        )
    }

    // Weak Speaking / Writing evidence (careful: missing data is NOT labeled as failure!)
    for (skill in listOf("Sprechen", "Schreiben")) {
        val panel = mission.panels.first { it.skill == skill }
        if (panel.status == SkillPrepStatus.BELOW_TARGET) {
            add(
                fingerprint = "perf:skill-below:$skill:${panel.latest?.day}",
                category = AlertCategory.PERFORMANCE,
                severity = AlertSeverity.HIGH,
                title = "$skill assessment below TDN4 target",
                explanation = "Recent evaluated $skill performance is below the Digital TestDaF TDN4 benchmark.",
                evidence = panel.latest?.let { "${(it.ratio * 100).roundToInt()}% (${it.evidenceType.label})" } ?: "Below target",
                period = panel.latest?.day ?: today.toString(),
                suggestedAction = "Schedule targeted $skill practice tasks and review rubric feedback.",
                route = "Progress:TestDaF",
            )
        }
    }

    // Stalled progress check (only if user has prior activity and 5+ inactive days)
    if (analytics.activity.totalActiveMinutesAllTime > 0 && analytics.activity.currentStreak == 0 && analytics.activity.weeklyMinutes == 0) {
        add(
            fingerprint = "perf:stalled:${today.year}-${today.monthValue}-${today.dayOfMonth / 5}",
            category = AlertCategory.PERFORMANCE,
            severity = AlertSeverity.INFO,
            title = "Study momentum paused this week",
            explanation = "No active study time has been recorded over the past 7 days.",
            evidence = "0 min active in last 7 days · Best streak: ${analytics.activity.longestStreak}d",
            period = "Last 7 days",
            suggestedAction = "Start a short 5-minute review or reading round to restart momentum.",
            route = "Learn:Vocab",
        )
    }

    // ---------- 2. STUDY PLANNING ALERTS ----------
    val plannedMin = (mission.config.weeklyHours * 60.0).roundToInt()
    if (plannedMin > 0 && analytics.activity.weeklyMinutes < (plannedMin * 0.65).roundToInt()) {
        add(
            fingerprint = "plan:behind-weekly:${today.year}-W${today.dayOfYear / 7}",
            category = AlertCategory.STUDY_PLANNING,
            severity = AlertSeverity.MEDIUM,
            title = "Behind weekly study plan",
            explanation = "Completed study time over the last 7 days is trailing your configured weekly study target.",
            evidence = "${formatStudyTime(analytics.activity.weeklyMinutes)} logged vs ${formatStudyTime(plannedMin)} planned",
            period = "Last 7 days",
            suggestedAction = "Complete a focused session today or adjust your weekly hours in Mission Control.",
            route = "Roadmap:Quests",
        )
    }

    if (analytics.vocabulary.currentlyDue >= 25) {
        add(
            fingerprint = "plan:fsrs-backlog:${analytics.vocabulary.currentlyDue / 10}",
            category = AlertCategory.STUDY_PLANNING,
            severity = if (analytics.vocabulary.currentlyDue >= 60) AlertSeverity.HIGH else AlertSeverity.MEDIUM,
            title = "Growing FSRS review backlog",
            explanation = "Clearing due spaced-repetition cards protects long-term retention before adding new words.",
            evidence = "${analytics.vocabulary.currentlyDue} cards currently due",
            period = "Today ($today)",
            suggestedAction = "Open Scheduled Reviews and clear a batch of due cards.",
            route = "Learn:Review",
        )
    }

    if (analytics.grammar.needsDelayedReview.isNotEmpty()) {
        val topicsList = analytics.grammar.needsDelayedReview.take(3).joinToString(", ")
        add(
            fingerprint = "plan:grammar-delayed:${analytics.grammar.needsDelayedReview.size}",
            category = AlertCategory.STUDY_PLANNING,
            severity = AlertSeverity.INFO,
            title = "Grammar delayed reviews ready",
            explanation = "${analytics.grammar.needsDelayedReview.size} grammar topic(s) are ready for delayed retrieval to earn or renew mastery.",
            evidence = "Ready: $topicsList",
            period = "As of $today",
            suggestedAction = "Complete the delayed review in Grammar Academy.",
            route = "Learn:Grammar",
        )
    }

    if (pendingQuestCount > 0) {
        add(
            fingerprint = "plan:pending-quests:$today",
            category = AlertCategory.STUDY_PLANNING,
            severity = AlertSeverity.INFO,
            title = "$pendingQuestCount daily quest(s) remaining today",
            explanation = "Scheduled learning objectives for today are still open.",
            evidence = "$pendingQuestCount unfinished task(s) on $today",
            period = "Today",
            suggestedAction = "View today's tasks in Roadmap → Quests.",
            route = "Roadmap:Quests",
        )
    }

    // Approaching milestones
    mission.config.milestones.filter { !it.completed }.forEach { m ->
        val due = runCatching { LocalDate.parse(m.dueDate) }.getOrNull()
        if (due != null) {
            val daysTo = ChronoUnit.DAYS.between(today, due)
            if (daysTo in 0L..14L) {
                add(
                    fingerprint = "plan:milestone:${m.id}",
                    category = AlertCategory.STUDY_PLANNING,
                    severity = if (daysTo <= 3L) AlertSeverity.HIGH else AlertSeverity.MEDIUM,
                    title = "Milestone approaching: ${m.title}",
                    explanation = "Your preparation milestone is due in $daysTo day(s).",
                    evidence = "Due ${m.dueDate}",
                    period = "Next 14 days",
                    suggestedAction = "Check milestone progress in TestDaF Mission Control.",
                    route = "Progress:TestDaF",
                )
            }
        }
    }

    // ---------- 3. EXAM ALERTS ----------
    val daysToExam = mission.config.daysUntil(today)
    if (daysToExam != null) {
        when {
            daysToExam < 0 -> add(
                fingerprint = "exam:expired:${mission.config.targetDate}",
                category = AlertCategory.EXAM,
                severity = AlertSeverity.HIGH,
                title = "Target Digital TestDaF date has passed",
                explanation = "Your configured exam date (${mission.config.targetDate}) is in the past.",
                evidence = "${-daysToExam} days ago",
                period = "Exam configuration",
                suggestedAction = "Set a new exam date or log your official examination result.",
                route = "Progress:TestDaF",
            )
            daysToExam <= 60 -> add(
                fingerprint = "exam:countdown:${daysToExam / 7}",
                category = AlertCategory.EXAM,
                severity = if (daysToExam <= 21) AlertSeverity.HIGH else AlertSeverity.MEDIUM,
                title = "Digital TestDaF in $daysToExam days",
                explanation = "Target date ${mission.config.targetDate} is approaching. Shift emphasis toward timed 4-skill exam diagnostics.",
                evidence = "Weakest priority skill: ${mission.risk.weakestSkill.skill} (${mission.risk.weakestSkill.status.label})",
                period = "$daysToExam days remaining",
                suggestedAction = "Open Mission Control to review all four skills.",
                route = "Progress:TestDaF",
            )
        }
    }

    val missingEvidenceSkills = mission.panels.filter {
        it.status == SkillPrepStatus.INSUFFICIENT_EVIDENCE || it.status == SkillPrepStatus.NEEDS_REASSESSMENT
    }
    if (missingEvidenceSkills.isNotEmpty()) {
        val names = missingEvidenceSkills.joinToString(", ") { it.skill }
        add(
            fingerprint = "exam:missing-evidence:${names}",
            category = AlertCategory.EXAM,
            severity = if (daysToExam != null && daysToExam <= 60) AlertSeverity.HIGH else AlertSeverity.INFO,
            title = "Diagnostic evidence needed ($names)",
            explanation = "Missing or outdated data is not treated as failure, but all four Digital TestDaF skills need recent diagnostics before readiness can be verified.",
            evidence = missingEvidenceSkills.joinToString(" · ") { "${it.skill}: ${it.status.label}" },
            period = "Four-skill readiness check",
            suggestedAction = "Log an exam-like diagnostic or practice assessment in Mission Control.",
            route = "Progress:TestDaF",
        )
    }

    if (mission.risk.examReadinessRisk.level == RiskLevel.HIGH) {
        add(
            fingerprint = "exam:high-risk:${mission.risk.weakestSkill.skill}",
            category = AlertCategory.EXAM,
            severity = AlertSeverity.HIGH,
            title = "Elevated Exam Readiness Risk",
            explanation = mission.risk.examReadinessRisk.summary,
            evidence = "Weakest skill: ${mission.risk.weakestSkill.skill} (${mission.risk.weakestSkill.status.label})",
            period = "Current risk evaluation",
            suggestedAction = "Review the smart study plan recommendations to prioritize ${mission.risk.weakestSkill.skill}.",
            route = "Roadmap:Recommendations",
        )
    }

    // ---------- 4. POSITIVE INSIGHTS ----------
    if (analytics.reading.repeatedImprovements.any { it.deltaPct >= 10 }) {
        val best = analytics.reading.repeatedImprovements.maxBy { it.deltaPct }
        add(
            fingerprint = "pos:reading-improve:${best.passageId}:${best.latestPct}",
            category = AlertCategory.POSITIVE,
            severity = AlertSeverity.POSITIVE,
            title = "Reading comprehension improved (+${best.deltaPct} pts)",
            explanation = "Repeated reading of '${best.title}' (${best.level}) yielded measurable comprehension gains.",
            evidence = "${best.firstPct}% → ${best.latestPct}% across ${best.attempts} rounds",
            period = "Reading history",
            suggestedAction = "Try another passage at ${best.level} or step up one level.",
            route = "Learn:Reading",
        )
    }

    if ((analytics.vocabulary.averageReviewSuccessPct ?: 0) >= 85 && analytics.vocabulary.inFsrsReview >= 5) {
        add(
            fingerprint = "pos:strong-retention:${analytics.vocabulary.averageReviewSuccessPct}",
            category = AlertCategory.POSITIVE,
            severity = AlertSeverity.POSITIVE,
            title = "Strong FSRS retention (${analytics.vocabulary.averageReviewSuccessPct}%)",
            explanation = "Your scheduled vocabulary recall accuracy is holding steady above 85%.",
            evidence = "${analytics.vocabulary.stableFsrs} stable words · ${analytics.vocabulary.inFsrsReview} in FSRS review",
            period = "All scheduled reviews",
            suggestedAction = "Keep clearing reviews daily to preserve long-term stability.",
            route = "Progress:Skills",
        )
    }

    val masteredGrammar = analytics.grammar.masteryCounts["Mastered"] ?: 0
    if (masteredGrammar > 0) {
        add(
            fingerprint = "pos:grammar-mastery:$masteredGrammar",
            category = AlertCategory.POSITIVE,
            severity = AlertSeverity.POSITIVE,
            title = "$masteredGrammar grammar topic(s) mastered",
            explanation = "You passed lessons, practice, assessment, and 24-hour delayed retrieval.",
            evidence = "$masteredGrammar / ${analytics.grammar.totalTopics} topics mastered",
            period = "Grammar Academy",
            suggestedAction = "Unlock the next CEFR grammar milestone in Roadmap.",
            route = "Learn:Grammar",
        )
    }

    if (analytics.activity.currentStreak >= 3) {
        add(
            fingerprint = "pos:streak:${analytics.activity.currentStreak / 3}",
            category = AlertCategory.POSITIVE,
            severity = AlertSeverity.POSITIVE,
            title = "${analytics.activity.currentStreak}-day study streak",
            explanation = "Consistent daily study is building strong retention habits (respecting your rest days).",
            evidence = "Current: ${analytics.activity.currentStreak}d · Best: ${analytics.activity.longestStreak}d",
            period = "Current streak",
            suggestedAction = "Keep your momentum going with today's learning objective.",
            route = "Progress:Analytics",
        )
    }

    if (analytics.xp.weeklyGrowthPct != null && analytics.xp.weeklyGrowthPct >= 15.0 && analytics.xp.weeklyXp >= 50) {
        add(
            fingerprint = "pos:xp-growth:${today.year}-W${today.dayOfYear / 7}",
            category = AlertCategory.POSITIVE,
            severity = AlertSeverity.POSITIVE,
            title = "Weekly study XP up ${formatGrowth(analytics.xp.weeklyGrowthPct)}",
            explanation = "Your study activity grew substantially compared to the previous 7 days.",
            evidence = "${analytics.xp.weeklyXp} XP this week vs ${analytics.xp.previousWeekXp} XP prior week",
            period = "Last 7 days vs prior 7 days",
            suggestedAction = "Inspect full XP & Velocity trends in Progress.",
            route = "Progress:XP",
        )
    }

    return raw.distinctBy { it.fingerprint }.sortedBy { it.severity.sortOrder }
}

/**
 * Generates actionable, non-intrusive study plan proposals from Risk Engine + Analytics.
 * Proposals are NEVER applied silently — user accepts, edits, or rejects them.
 */
fun generateStudyPlanProposals(
    analytics: AnalyticsBundle,
    mission: MissionControlBundle,
    decisions: Map<String, Pair<String, Long>>,
    today: LocalDate,
): List<StudyPlanProposal> {
    val proposals = mutableListOf<StudyPlanProposal>()
    val isRestDay = today.dayOfWeek in mission.config.restDays

    fun offer(p: StudyPlanProposal) {
        val prior = decisions[p.fingerprint]
        // Do not re-spam a proposal rejected or accepted within the last 7 days
        if (prior != null && (prior.first == "rejected" || prior.first == "accepted")) return
        proposals += p
    }

    // 1. Weakest TestDaF skill priority
    val weakest = mission.risk.weakestSkill
    val questSkill = when (weakest.skill) {
        "Lesen" -> "Reading"
        "Hören" -> "Listening"
        "Schreiben" -> "Writing"
        else -> "Speaking"
    }
    val metric = when (questSkill) {
        "Reading" -> "Comprehension"
        "Listening" -> "Listening in Context"
        else -> "Self-reported"
    }
    if (weakest.status != SkillPrepStatus.TARGET_RANGE_INDICATED) {
        val actionTitle = if (weakest.status == SkillPrepStatus.INSUFFICIENT_EVIDENCE) {
            "Complete a ${weakest.skill} diagnostic task"
        } else {
            "Focused ${weakest.skill} practice session"
        }
        offer(
            StudyPlanProposal(
                fingerprint = "prop:weakest:${weakest.skill}:${weakest.status.name}",
                title = actionTitle,
                skill = questSkill,
                metric = metric,
                target = 1,
                rationale = "${weakest.skill} is your highest-priority Digital TestDaF skill (${weakest.status.label}).",
                impactNote = if (isRestDay) "Today is a configured rest day — optional bonus task only." else "Adds 1 targeted task without altering your unlimited XP or rest days.",
                destinationRoute = "Progress:TestDaF",
            ),
        )
    }

    // 2. FSRS Review backlog relief
    if (analytics.vocabulary.currentlyDue >= 20) {
        val batch = analytics.vocabulary.currentlyDue.coerceAtMost(40)
        offer(
            StudyPlanProposal(
                fingerprint = "prop:fsrs-backlog:${batch / 10}",
                title = "Clear $batch due FSRS reviews",
                skill = "Vocabulary",
                metric = "Scheduled reviews",
                target = batch,
                rationale = "${analytics.vocabulary.currentlyDue} vocabulary cards are overdue or due today.",
                impactNote = "Prioritizes memory stability before adding new words.",
                destinationRoute = "Learn:Review",
            ),
        )
    }

    // 3. Targeted Grammar remediation
    if (analytics.grammar.weakAreas.isNotEmpty() || analytics.grammar.needsDelayedReview.isNotEmpty()) {
        val focusTopic = analytics.grammar.needsDelayedReview.firstOrNull() ?: analytics.grammar.weakAreas.first()
        offer(
            StudyPlanProposal(
                fingerprint = "prop:grammar:$focusTopic",
                title = "Practice grammar: $focusTopic",
                skill = "Grammar",
                metric = "Practice",
                target = 1,
                rationale = if (analytics.grammar.needsDelayedReview.isNotEmpty())
                    "$focusTopic is ready for delayed mastery review."
                else "$focusTopic showed recent exercise errors.",
                impactNote = "Strengthens accuracy for Reading, Writing, and Speaking.",
                destinationRoute = "Learn:Grammar",
            ),
        )
    }

    // 4. Adaptive Reading difficulty recommendation
    val avgComp = analytics.reading.averageComprehensionPct
    if (avgComp != null && analytics.reading.passagesCompleted >= 2) {
        if (avgComp < 60) {
            offer(
                StudyPlanProposal(
                    fingerprint = "prop:reading-stepdown",
                    title = "Read one accessible passage for fluency",
                    skill = "Reading",
                    metric = "Comprehension",
                    target = 1,
                    rationale = "Recent reading comprehension averages $avgComp% — stepping down one CEFR level rebuilds confidence and vocabulary coverage.",
                    impactNote = "Keeps reading productive instead of frustrating.",
                    destinationRoute = "Learn:Reading",
                ),
            )
        } else if (avgComp >= 85) {
            offer(
                StudyPlanProposal(
                    fingerprint = "prop:reading-stepup",
                    title = "Challenge yourself with a higher-level passage",
                    skill = "Reading",
                    metric = "Comprehension",
                    target = 1,
                    rationale = "Your comprehension accuracy is consistently strong ($avgComp%) — ready for denser B1/B2 academic texts.",
                    impactNote = "Builds TestDaF Reading stamina.",
                    destinationRoute = "Learn:Reading",
                ),
            )
        }
    }

    return proposals.take(4)
}

internal fun StudyRepository.acceptProposal(p: StudyPlanProposal, asBonus: Boolean = false) = db.tx {
    val q = Quest(
        id = "rec-${UUID.randomUUID()}",
        title = p.title,
        skill = p.skill,
        metric = p.metric,
        target = p.target,
        date = day(),
        recurring = false,
        bonus = asBonus,
    )
    saveQuest(q)
    recordProposalDecision(p.fingerprint, p.title, p.skill, p.metric, p.target, p.rationale, "accepted")
}

internal fun StudyRepository.rejectProposal(p: StudyPlanProposal) = db.tx {
    recordProposalDecision(p.fingerprint, p.title, p.skill, p.metric, p.target, p.rationale, "rejected")
}

val alertCategoryPrefKey = { cat: AlertCategory -> "v61.alert.cat.${cat.name}" }
