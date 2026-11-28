package org.lexora.study

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import kotlin.math.roundToInt

enum class AnalyticsWindow(val label: String) {
    LAST_7("7d"),
    LAST_30("30d"),
    LAST_90("90d"),
    ALL_TIME("All"),
    CUSTOM("Custom"),
}

data class DateRangeSpec(
    val window: AnalyticsWindow = AnalyticsWindow.LAST_30,
    val from: LocalDate,
    val to: LocalDate,
)

fun resolveDateRange(
    window: AnalyticsWindow,
    today: LocalDate,
    earliestDataDay: LocalDate? = null,
    customFrom: LocalDate? = null,
    customTo: LocalDate? = null,
): DateRangeSpec = when (window) {
    AnalyticsWindow.LAST_7 -> DateRangeSpec(window, today.minusDays(6), today)
    AnalyticsWindow.LAST_30 -> DateRangeSpec(window, today.minusDays(29), today)
    AnalyticsWindow.LAST_90 -> DateRangeSpec(window, today.minusDays(89), today)
    AnalyticsWindow.ALL_TIME -> DateRangeSpec(window, earliestDataDay ?: today.minusDays(29), today)
    AnalyticsWindow.CUSTOM -> {
        val f = customFrom ?: today.minusDays(13)
        val t = customTo ?: today
        if (t.isBefore(f)) DateRangeSpec(window, t, f) else DateRangeSpec(window, f, t)
    }
}

/**
 * Pure XP growth formula: ((Current - Previous) / Previous) * 100.
 * Returns null when previous <= 0 to avoid division by zero ("No comparable previous period").
 */
fun xpGrowthPercent(current: Long, previous: Long): Double? {
    if (previous <= 0L) return null
    return ((current - previous).toDouble() / previous.toDouble()) * 100.0
}

fun formatGrowth(pct: Double?): String {
    if (pct == null) return "No comparable previous period"
    val rounded = (pct * 10.0).roundToInt() / 10.0
    val sign = if (rounded > 0.0) "+" else ""
    return "$sign${rounded}%"
}

data class DailyActivityPoint(
    val day: String,
    val observedMinutes: Double,
    val reportedMinutes: Double,
    val xp: Long,
    val reviews: Int,
    val learned: Int,
    val sessions: Int,
) {
    val totalMinutes: Double get() = observedMinutes + reportedMinutes
    val active: Boolean get() = xp > 0 || reviews > 0 || learned > 0 || totalMinutes > 0.0 || sessions > 0
}

data class StudyActivityAnalytics(
    val range: DateRangeSpec,
    val totalActiveMinutesAllTime: Int,
    val observedActiveMinutesAllTime: Int,
    val reportedMinutesAllTime: Int,
    val todayMinutes: Int,
    val weeklyMinutes: Int,
    val monthlyMinutes: Int,
    val rangeObservedMinutes: Int,
    val rangeReportedMinutes: Int,
    val averageMinutesPerActiveDay: Double?,
    val sessionsCompletedInRange: Int,
    val activeDaysInRange: Int,
    val currentStreak: Int,
    val longestStreak: Int,
    val configuredRestDays: Set<DayOfWeek>,
    val restDaysTakenInRange: Int,
    val weeklyConsistencyPct: Int?,
    val plannedMinutesInRange: Int,
    val actualMinutesInRange: Int,
    val dailySeries: List<DailyActivityPoint>,
)

data class XpAnalytics(
    val totalXp: Long,
    val todayXp: Long,
    val weeklyXp: Long,
    val previousWeekXp: Long,
    val monthlyXp: Long,
    val previousMonthXp: Long,
    val rangeXp: Long,
    val level: Long,
    val xpInCurrentLevel: Long,
    val xpToNextLevel: Long,
    val xpByActivity: Map<String, Long>,
    val xpBySkill: Map<String, Long>,
    val xpPerActiveHour: Double?,
    val highestXpDay: Pair<String, Long>?,
    val dailyGrowthPct: Double?,
    val weeklyGrowthPct: Double?,
    val monthlyGrowthPct: Double?,
    val rollingSeries: List<Pair<String, Long>>,
    val achievements: List<String>,
)

data class CefrVocabStat(
    val level: String,
    val total: Int,
    val known: Int,
    val selected: Int,
    val learning: Int,
    val reviewing: Int,
    val stable: Int,
)

data class TopicVocabStat(
    val topic: String,
    val total: Int,
    val studied: Int,
    val stable: Int,
)

data class VocabularyAnalytics(
    val totalCatalog: Int,
    val collectedInbox: Int,
    val inboxBacklog: Int,
    val inboxUnresolved: Int,
    val selectedForLearning: Int,
    val newlyStudied: Int,
    val inFsrsReview: Int,
    val currentlyDue: Int,
    val stableFsrs: Int,
    val knownCount: Int,
    val unknownEstimate: Int,
    val averageReviewSuccessPct: Int?,
    val predictedRetentionPct: Int?,
    val retentionTrend: List<Pair<String, Int>>,
    val frequentlyMissed: List<Pair<String, Int>>,
    val byCefr: List<CefrVocabStat>,
    val byTopic: List<TopicVocabStat>,
    val academicTotal: Int,
    val academicCovered: Int,
)

data class RepeatedReadingStat(
    val passageId: String,
    val title: String,
    val level: String,
    val firstPct: Int,
    val latestPct: Int,
    val attempts: Int,
) {
    val deltaPct: Int get() = latestPct - firstPct
}

data class ReadingHistoryItem(
    val day: String,
    val passageId: String,
    val title: String,
    val level: String,
    val topic: String,
    val correct: Int,
    val attempted: Int,
    val words: Int,
) {
    val pct: Int get() = if (attempted > 0) (correct * 100) / attempted else 0
}

data class ReadingAnalytics(
    val passagesCompleted: Int,
    val uniquePassagesCompleted: Int,
    val totalWordsRead: Int,
    val totalActiveReadingSeconds: Long,
    val averageComprehensionPct: Int?,
    val byCefr: Map<String, Pair<Int, Int>>,
    val byTopic: Map<String, Pair<Int, Int>>,
    val vocabCollectedFromReading: Int,
    val vocabPerPassage: Double?,
    val readingSpeedWpm: Int?,
    val repeatedImprovements: List<RepeatedReadingStat>,
    val strongestTheme: String?,
    val weakestTheme: String?,
    val history: List<ReadingHistoryItem>,
)

data class GrammarAnalytics(
    val totalTopics: Int,
    val topicsStarted: Int,
    val lessonsCompleted: Int,
    val exercisesAttempted: Int,
    val exercisesCorrect: Int,
    val overallAccuracyPct: Int?,
    val testCount: Int,
    val averageTestPct: Int?,
    val masteryCounts: Map<String, Int>,
    val frequentMistakes: List<Pair<String, Int>>,
    val weakAreas: List<String>,
    val recentImprovements: List<String>,
    val needsDelayedReview: List<String>,
    val accuracyByCategory: Map<String, Int>,
    val progressByCefr: Map<String, Pair<Int, Int>>,
    val activeGrammarSeconds: Long,
)

data class ListeningAnalytics(
    val tasksCompleted: Int,
    val answersAttempted: Int,
    val answersCorrect: Int,
    val accuracyPct: Int?,
    val activeSeconds: Long,
    val byLevel: Map<String, Pair<Int, Int>>,
)

data class SpeakingAnalytics(
    val tasksAttempted: Int,
    val selfReportedCount: Int,
    val selfReportedMinutes: Int,
    val assessedCount: Int,
    val latestAssessedPct: Int?,
    val latestEvidenceLabel: String?,
)

data class WritingAnalytics(
    val tasksCompleted: Int,
    val selfReportedCount: Int,
    val selfReportedMinutes: Int,
    val assessedCount: Int,
    val latestAssessedPct: Int?,
    val latestEvidenceLabel: String?,
    val recentRubricNotes: List<Pair<String, String>>,
)

data class AnalyticsBundle(
    val activity: StudyActivityAnalytics,
    val xp: XpAnalytics,
    val vocabulary: VocabularyAnalytics,
    val reading: ReadingAnalytics,
    val grammar: GrammarAnalytics,
    val listening: ListeningAnalytics,
    val speaking: SpeakingAnalytics,
    val writing: WritingAnalytics,
)

/**
 * Pure streak computation respecting optional configured rest days (if a rest day has no study,
 * it does not break an active streak, though rest days themselves do not inflate the active-day count).
 */
fun computeStreaks(
    activeDays: Set<LocalDate>,
    today: LocalDate,
    restDays: Set<DayOfWeek> = emptySet(),
): Pair<Int, Int> {
    if (activeDays.isEmpty()) return 0 to 0
    val sorted = activeDays.sorted()
    var longest = 0
    var run = 0
    var prev: LocalDate? = null
    for (d in sorted) {
        if (prev == null) {
            run = 1
        } else {
            val gap = ChronoUnit.DAYS.between(prev, d)
            if (gap == 1L) {
                run++
            } else if (gap > 1L) {
                val allRest = (1L until gap).all { prev!!.plusDays(it).dayOfWeek in restDays }
                run = if (allRest) run + 1 else 1
            }
        }
        if (run > longest) longest = run
        prev = d
    }

    var current = 0
    var cursor = today
    // Allow today to still be in progress without breaking yesterday's streak
    if (cursor !in activeDays) {
        cursor = cursor.minusDays(1)
        while (cursor.dayOfWeek in restDays && cursor !in activeDays && ChronoUnit.DAYS.between(cursor, today) <= 7) {
            cursor = cursor.minusDays(1)
        }
    }
    while (true) {
        if (cursor in activeDays) {
            current++
            cursor = cursor.minusDays(1)
        } else if (cursor.dayOfWeek in restDays && current > 0) {
            // Look back up to 6 days to see if an active day existed before rest day(s)
            var probe = cursor.minusDays(1)
            var foundActive = false
            while (ChronoUnit.DAYS.between(probe, cursor) <= 6) {
                if (probe in activeDays) {
                    foundActive = true
                    break
                }
                if (probe.dayOfWeek !in restDays) break
                probe = probe.minusDays(1)
            }
            if (foundActive) cursor = cursor.minusDays(1) else break
        } else {
            break
        }
    }
    return current to maxOf(longest, current)
}

internal fun StudyRepository.computeAnalytics(
    window: AnalyticsWindow,
    readings: List<Reading>,
    lessons: List<Lesson>,
    guides: Map<String, GrammarGuide>,
    customFrom: LocalDate? = null,
    customTo: LocalDate? = null,
): AnalyticsBundle {
    val today = LocalDate.parse(day())
    val todayStr = today.toString()

    // Earliest recorded day across all tables
    val earliestStr = listOfNotNull(
        db.str("SELECT min(day) FROM effective_event WHERE length(day)=10"),
        db.str("SELECT min(day) FROM academy_event WHERE length(day)=10"),
        db.str("SELECT min(date(at/1000,'unixepoch','localtime')) FROM exploration"),
    ).minOrNull()
    val earliestDate = earliestStr?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
    val range = resolveDateRange(window, today, earliestDate, customFrom, customTo)
    val fromStr = range.from.toString()
    val toStr = range.to.toString()

    // Study schedule / rest days config
    val studyDaysPref = pref("academy.days").ifEmpty { "1,2,3,4,5,6,7" }
    val plannedDayNumbers = if (studyDaysPref == "rest") emptySet()
    else studyDaysPref.split(",").mapNotNull { it.toIntOrNull() }.filter { it in 1..7 }.toSet()
    val configuredRestDays = DayOfWeek.entries.filter { it.value !in plannedDayNumbers }.toSet()
    val weeklyMinutesTarget = (pref("v61.exam.weeklyHours").toDoubleOrNull()?.let { (it * 60.0).roundToInt() })
        ?: pref("academy.weekly").toIntOrNull() ?: 150
    val minutesPerPlannedDay = if (plannedDayNumbers.isEmpty()) 0 else weeklyMinutesTarget / plannedDayNumbers.size

    // 1. Observed vs Manually Reported study time by day
    val vocabTimeMsByDay = db.rows("SELECT day, coalesce(sum(score),0) FROM effective_event WHERE kind='time' GROUP BY day") {
        it.getString(0) to it.getLong(1)
    }.toMap()
    val academyObservedMsByDay = db.rows(
        "SELECT day, coalesce(sum(active_ms),0) FROM academy_event WHERE active_ms IS NOT NULL AND method!='self-reported' GROUP BY day",
    ) { it.getString(0) to it.getLong(1) }.toMap()
    val reportedMsByDay = db.rows(
        "SELECT day, coalesce(sum(active_ms),0) FROM academy_event WHERE active_ms IS NOT NULL AND method='self-reported' GROUP BY day",
    ) { it.getString(0) to it.getLong(1) }.toMap()

    // 2. XP by day (unlimited! academy_credit + academy_vocab)
    val creditXpByDay = db.rows(
        "SELECT e.day, coalesce(sum(c.amount),0) FROM academy_credit c JOIN academy_event e ON e.id=c.event_id GROUP BY e.day",
    ) { it.getString(0) to it.getLong(1) }.toMap()
    val vocabXpByDay = db.rows("SELECT day, coalesce(sum(xp),0) FROM academy_vocab GROUP BY day") {
        it.getString(0) to it.getLong(1)
    }.toMap()

    // 3. Reviews, learned words, sessions by day
    val reviewsByDay = db.rows("SELECT day, count(*) FROM $ratings WHERE kind='review' GROUP BY day") {
        it.getString(0) to it.getInt(1)
    }.toMap()
    val learnedByDay = db.rows("SELECT firstDay, count(*) FROM $successDays GROUP BY firstDay") {
        it.getString(0) to it.getInt(1)
    }.toMap()
    val sessionsByDay = db.rows(
        """SELECT day, count(*) FROM (
            SELECT day FROM academy_event WHERE activity IN ('Comprehension','Practice','Assessment','Delayed review','Guided','Extended Drill','Reported task')
            UNION ALL SELECT date(at/1000,'unixepoch','localtime') day FROM exploration WHERE part='Completed'
            UNION ALL SELECT DISTINCT day FROM effective_event WHERE kind IN ('new','review')
        ) GROUP BY day""",
    ) { it.getString(0) to it.getInt(1) }.toMap()

    val allRecordedDays = (
        vocabTimeMsByDay.keys + academyObservedMsByDay.keys + reportedMsByDay.keys +
            creditXpByDay.keys + vocabXpByDay.keys + reviewsByDay.keys + learnedByDay.keys + sessionsByDay.keys
        ).filter { it.length == 10 }.toSet()

    fun observedMin(d: String) = ((vocabTimeMsByDay[d] ?: 0L) + (academyObservedMsByDay[d] ?: 0L)) / 60_000.0
    fun reportedMin(d: String) = (reportedMsByDay[d] ?: 0L) / 60_000.0
    fun dayXp(d: String) = (creditXpByDay[d] ?: 0L) + (vocabXpByDay[d] ?: 0L)

    val allActiveLocalDates = allRecordedDays.mapNotNull { d ->
        val hasAct = dayXp(d) > 0 || (reviewsByDay[d] ?: 0) > 0 || (learnedByDay[d] ?: 0) > 0 ||
            observedMin(d) > 0.0 || reportedMin(d) > 0.0 || (sessionsByDay[d] ?: 0) > 0
        if (hasAct) runCatching { LocalDate.parse(d) }.getOrNull() else null
    }.toSet()
    val (currentStreak, longestStreak) = computeStreaks(allActiveLocalDates, today, configuredRestDays)

    val spanDays = ChronoUnit.DAYS.between(range.from, range.to).coerceIn(0, 3650).toInt()
    val dailySeries = (0..spanDays).map { offset ->
        val d = range.from.plusDays(offset.toLong()).toString()
        DailyActivityPoint(
            day = d,
            observedMinutes = observedMin(d),
            reportedMinutes = reportedMin(d),
            xp = dayXp(d),
            reviews = reviewsByDay[d] ?: 0,
            learned = learnedByDay[d] ?: 0,
            sessions = sessionsByDay[d] ?: 0,
        )
    }

    val rangeObservedMin = dailySeries.sumOf { it.observedMinutes }.roundToInt()
    val rangeReportedMin = dailySeries.sumOf { it.reportedMinutes }.roundToInt()
    val rangeActiveDays = dailySeries.count { it.active }
    val avgPerActive = if (rangeActiveDays > 0) dailySeries.sumOf { it.totalMinutes } / rangeActiveDays else null

    val plannedDatesInRange = (0..spanDays).map { range.from.plusDays(it.toLong()) }
        .filter { !it.isAfter(today) && it.dayOfWeek !in configuredRestDays }
    val restDatesInRange = (0..spanDays).map { range.from.plusDays(it.toLong()) }
        .filter { !it.isAfter(today) && it.dayOfWeek in configuredRestDays }
    val restDaysTaken = restDatesInRange.count { it !in allActiveLocalDates }
    val plannedDaysHit = plannedDatesInRange.count { it in allActiveLocalDates }
    val consistencyPct = if (plannedDatesInRange.isEmpty()) null else (plannedDaysHit * 100) / plannedDatesInRange.size
    val plannedMinutesInRange = plannedDatesInRange.size * minutesPerPlannedDay

    val totalObservedAll = (vocabTimeMsByDay.values.sum() + academyObservedMsByDay.values.sum()) / 60_000.0
    val totalReportedAll = reportedMsByDay.values.sum() / 60_000.0
    val weekStart = today.minusDays(6).toString()
    val monthStart = today.minusDays(29).toString()

    val activityAnalytics = StudyActivityAnalytics(
        range = range,
        totalActiveMinutesAllTime = (totalObservedAll + totalReportedAll).roundToInt(),
        observedActiveMinutesAllTime = totalObservedAll.roundToInt(),
        reportedMinutesAllTime = totalReportedAll.roundToInt(),
        todayMinutes = (observedMin(todayStr) + reportedMin(todayStr)).roundToInt(),
        weeklyMinutes = allRecordedDays.filter { it in weekStart..todayStr }.sumOf { observedMin(it) + reportedMin(it) }.roundToInt(),
        monthlyMinutes = allRecordedDays.filter { it in monthStart..todayStr }.sumOf { observedMin(it) + reportedMin(it) }.roundToInt(),
        rangeObservedMinutes = rangeObservedMin,
        rangeReportedMinutes = rangeReportedMin,
        averageMinutesPerActiveDay = avgPerActive,
        sessionsCompletedInRange = dailySeries.sumOf { it.sessions },
        activeDaysInRange = rangeActiveDays,
        currentStreak = currentStreak,
        longestStreak = longestStreak,
        configuredRestDays = configuredRestDays,
        restDaysTakenInRange = restDaysTaken,
        weeklyConsistencyPct = consistencyPct,
        plannedMinutesInRange = plannedMinutesInRange,
        actualMinutesInRange = rangeObservedMin + rangeReportedMin,
        dailySeries = dailySeries,
    )

    // XP Analytics (Unlimited XP Economy)
    val totalXp = academyXp()
    val todayXp = dayXp(todayStr)
    val yesterdayXp = dayXp(today.minusDays(1).toString())
    val curWeekXp = (0L..6L).sumOf { dayXp(today.minusDays(it).toString()) }
    val prevWeekXp = (7L..13L).sumOf { dayXp(today.minusDays(it).toString()) }
    val curMonthXp = (0L..29L).sumOf { dayXp(today.minusDays(it).toString()) }
    val prevMonthXp = (30L..59L).sumOf { dayXp(today.minusDays(it).toString()) }
    val rangeXp = dailySeries.sumOf { it.xp }

    val xpByActivity = linkedMapOf<String, Long>()
    db.rows(
        """SELECT e.activity, coalesce(sum(c.amount),0) FROM academy_credit c
           JOIN academy_event e ON e.id=c.event_id WHERE e.day BETWEEN ? AND ? GROUP BY e.activity""",
        listOf(fromStr, toStr),
    ) { it.getString(0) to it.getLong(1) }.forEach { (k, v) -> if (v > 0) xpByActivity[k] = (xpByActivity[k] ?: 0L) + v }
    db.rows(
        "SELECT kind, coalesce(sum(xp),0) FROM academy_vocab WHERE day BETWEEN ? AND ? GROUP BY kind",
        listOf(fromStr, toStr),
    ) { it.getString(0) to it.getLong(1) }.forEach { (k, v) ->
        if (v > 0) {
            val label = when (k) {
                "new" -> "New vocabulary"
                "review" -> "Scheduled FSRS review"
                else -> k
            }
            xpByActivity[label] = (xpByActivity[label] ?: 0L) + v
        }
    }

    val xpBySkill = linkedMapOf<String, Long>()
    db.rows(
        """SELECT e.skill, coalesce(sum(c.amount),0) FROM academy_credit c
           JOIN academy_event e ON e.id=c.event_id WHERE e.day BETWEEN ? AND ? GROUP BY e.skill""",
        listOf(fromStr, toStr),
    ) { it.getString(0) to it.getLong(1) }.forEach { (k, v) -> if (v > 0) xpBySkill[k] = (xpBySkill[k] ?: 0L) + v }
    val vocabRangeXp = db.long("SELECT coalesce(sum(xp),0) FROM academy_vocab WHERE day BETWEEN ? AND ?", fromStr, toStr)
    if (vocabRangeXp > 0) xpBySkill["Vocabulary"] = (xpBySkill["Vocabulary"] ?: 0L) + vocabRangeXp

    val xpPerHour = if (totalObservedAll >= 1.0) totalXp.toDouble() / (totalObservedAll / 60.0) else null
    val highestXpDay = allRecordedDays.map { it to dayXp(it) }.filter { it.second > 0L }.maxByOrNull { it.second }
    val earnedBadges = db.rows("SELECT content FROM academy_event WHERE activity='Achievement recognized' ORDER BY at DESC, id DESC") {
        it.getString(0)
    }

    val xpAnalytics = XpAnalytics(
        totalXp = totalXp,
        todayXp = todayXp,
        weeklyXp = curWeekXp,
        previousWeekXp = prevWeekXp,
        monthlyXp = curMonthXp,
        previousMonthXp = prevMonthXp,
        rangeXp = rangeXp,
        level = AcademyXp.level(totalXp),
        xpInCurrentLevel = totalXp % 100L,
        xpToNextLevel = 100L - (totalXp % 100L),
        xpByActivity = xpByActivity.entries.sortedByDescending { it.value }.associate { it.key to it.value },
        xpBySkill = xpBySkill.entries.sortedByDescending { it.value }.associate { it.key to it.value },
        xpPerActiveHour = xpPerHour,
        highestXpDay = highestXpDay,
        dailyGrowthPct = xpGrowthPercent(todayXp, yesterdayXp),
        weeklyGrowthPct = xpGrowthPercent(curWeekXp, prevWeekXp),
        monthlyGrowthPct = xpGrowthPercent(curMonthXp, prevMonthXp),
        rollingSeries = dailySeries.map { it.day to it.xp },
        achievements = earnedBadges,
    )

    // Vocabulary Analytics
    val coreStats = stats()
    val inboxList = inbox()
    val inboxActive = inboxList.filterNot { it.archived }
    val inboxUnresolved = inboxActive.count { it.id == null }
    val inboxBacklog = inboxActive.count { it.status == "Collected" }
    val byCefrVocab = coreStats.coverage.map { c ->
        CefrVocabStat(c.level, c.total, c.known, c.selected, c.learning, c.reviewing, c.mastered)
    }
    val byTopicVocab = db.rows(
        """SELECT w.topic, count(*),
           sum(CASE WHEN c.word IS NOT NULL OR a.grade=4 THEN 1 ELSE 0 END),
           sum(CASE WHEN c.state='REVIEW' AND c.stability>=30 AND c.due>=? THEN 1 ELSE 0 END)
           FROM word w LEFT JOIN active_card c ON c.word=w.id LEFT JOIN assessment a ON a.word=w.id
           GROUP BY w.topic ORDER BY 3 DESC, w.topic LIMIT 12""",
        listOf(now().toString()),
    ) { TopicVocabStat(it.getString(0), it.getInt(1), it.getInt(2), it.getInt(3)) }
    val academicTotal = db.long("SELECT count(*) FROM content_v4 WHERE category!=''").toInt()
    val academicCovered = db.long(
        """SELECT count(*) FROM content_v4 x LEFT JOIN active_card c ON c.word=x.word
           LEFT JOIN assessment a ON a.word=x.word
           WHERE x.category!='' AND (c.word IS NOT NULL OR a.grade=4)""",
    ).toInt()
    val retentionTrend = db.rows(
        """SELECT day, round(avg(CASE WHEN score>=2 THEN 100.0 ELSE 0.0 END))
           FROM $ratings WHERE kind='review' AND day BETWEEN ? AND ? GROUP BY day ORDER BY day""",
        listOf(fromStr, toStr),
    ) { it.getString(0) to it.getInt(1) }
    val freqMissedVocab = db.rows(
        """SELECT w.lemma, count(*) FROM effective_event e JOIN word w ON w.id=e.word
           WHERE e.kind='answer' AND e.score=0 GROUP BY e.word ORDER BY count(*) DESC, max(e.at) DESC LIMIT 8""",
    ) { it.getString(0) to it.getInt(1) }

    val knownTotal = coreStats.coverage.sumOf { it.known + it.mastered }
    val selectedTotal = coreStats.coverage.sumOf { it.selected }
    val reviewingTotal = coreStats.coverage.sumOf { it.reviewing + it.mastered }
    val vocabAnalytics = VocabularyAnalytics(
        totalCatalog = coreStats.total,
        collectedInbox = inboxActive.size,
        inboxBacklog = inboxBacklog,
        inboxUnresolved = inboxUnresolved,
        selectedForLearning = selectedTotal,
        newlyStudied = coreStats.started,
        inFsrsReview = reviewingTotal,
        currentlyDue = coreStats.due,
        stableFsrs = coreStats.mastered,
        knownCount = knownTotal,
        unknownEstimate = (coreStats.total - knownTotal).coerceAtLeast(0),
        averageReviewSuccessPct = coreStats.success,
        predictedRetentionPct = coreStats.retention,
        retentionTrend = retentionTrend,
        frequentlyMissed = freqMissedVocab,
        byCefr = byCefrVocab,
        byTopic = byTopicVocab,
        academicTotal = academicTotal,
        academicCovered = academicCovered,
    )

    // Reading Analytics
    val readingById = readings.associateBy { it.id }
    val readingRounds = db.rows(
        """SELECT day, content, level, attempted, correct, coalesce(active_ms,0), at
           FROM academy_event WHERE skill='Reading' AND activity='Comprehension' AND attempted>0
           ORDER BY at ASC, rowid ASC""",
    ) { c ->
        listOf(
            c.getString(0), c.getString(1), c.getString(2).orEmpty(),
            c.getInt(3).toString(), c.getInt(4).toString(), c.getLong(5).toString(),
        )
    }
    val readingAnswerMs = db.long(
        "SELECT coalesce(sum(active_ms),0) FROM academy_event WHERE skill='Reading' AND active_ms IS NOT NULL",
    )
    val readingHistory = readingRounds.map { r ->
        val p = readingById[r[1]]
        ReadingHistoryItem(
            day = r[0],
            passageId = r[1],
            title = p?.title ?: r[1],
            level = p?.level ?: r[2].ifEmpty { "?" },
            topic = p?.topic ?: "General",
            correct = r[4].toInt(),
            attempted = r[3].toInt(),
            words = p?.tokens?.size ?: 0,
        )
    }
    val totalWordsRead = readingHistory.sumOf { it.words }
    val readCorrectSum = readingHistory.sumOf { it.correct }
    val readAttemptedSum = readingHistory.sumOf { it.attempted }
    val avgCompPct = if (readAttemptedSum > 0) (readCorrectSum * 100) / readAttemptedSum else null
    val readByCefr = readingHistory.groupBy { it.level }.mapValues { (_, items) ->
        items.sumOf { it.correct } to items.sumOf { it.attempted }
    }
    val readByTopic = readingHistory.groupBy { it.topic }.mapValues { (_, items) ->
        items.sumOf { it.correct } to items.sumOf { it.attempted }
    }
    val vocabFromReading = db.long("SELECT count(*) FROM academy_inbox WHERE passage!='Discovery'").toInt()
    val uniquePassages = readingHistory.map { it.passageId }.distinct().size
    val vocabPerPassage = if (uniquePassages > 0) vocabFromReading.toDouble() / uniquePassages else null
    // Speed only computed when recorded active reading time is meaningful (>= 15 seconds)
    val readingSpeedWpm = if (readingAnswerMs >= 15_000L && totalWordsRead > 0) {
        ((totalWordsRead.toDouble() / (readingAnswerMs / 60_000.0)).roundToInt()).coerceIn(10, 600)
    } else null
    val repeatedStats = readingHistory.groupBy { it.passageId }.values.filter { it.size >= 2 }.map { list ->
        val first = list.first()
        val last = list.last()
        RepeatedReadingStat(first.passageId, first.title, first.level, first.pct, last.pct, list.size)
    }
    val scoredTopics = readByTopic.filterValues { it.second >= 2 }.mapValues { (_, p) -> (p.first * 100) / p.second }
    val strongestTheme = scoredTopics.maxByOrNull { it.value }?.let { "${it.key} (${it.value}%)" }
    val weakestTheme = scoredTopics.minByOrNull { it.value }?.takeIf { scoredTopics.size >= 2 || it.value < 80 }?.let { "${it.key} (${it.value}%)" }

    val readingAnalytics = ReadingAnalytics(
        passagesCompleted = readingHistory.size,
        uniquePassagesCompleted = uniquePassages,
        totalWordsRead = totalWordsRead,
        totalActiveReadingSeconds = readingAnswerMs / 1000L,
        averageComprehensionPct = avgCompPct,
        byCefr = readByCefr,
        byTopic = readByTopic,
        vocabCollectedFromReading = vocabFromReading,
        vocabPerPassage = vocabPerPassage,
        readingSpeedWpm = readingSpeedWpm,
        repeatedImprovements = repeatedStats,
        strongestTheme = strongestTheme,
        weakestTheme = weakestTheme,
        history = readingHistory.reversed(),
    )

    // Grammar Analytics
    val topicProgressMap = lessons.associate { it.id to topicProgress(it.id) }
    val masteryCounts = topicProgressMap.values.groupingBy { it.status }.eachCount()
    val grammarExAttempted = db.long(
        "SELECT coalesce(sum(attempted),0) FROM academy_event WHERE skill='Grammar' AND activity LIKE '%answer%'",
    ).toInt()
    val grammarExCorrect = db.long(
        "SELECT coalesce(sum(correct),0) FROM academy_event WHERE skill='Grammar' AND activity LIKE '%answer%'",
    ).toInt()
    val grammarTests = db.rows(
        "SELECT content, attempted, correct FROM academy_event WHERE skill='Grammar' AND activity='Assessment' AND attempted>0",
    ) { Triple(it.getString(0), it.getInt(1), it.getInt(2)) }
    val avgGrammarTest = if (grammarTests.isNotEmpty()) {
        val c = grammarTests.sumOf { it.third }
        val a = grammarTests.sumOf { it.second }
        if (a > 0) (c * 100) / a else null
    } else null

    val grammarActiveMs = db.long("SELECT coalesce(sum(active_ms),0) FROM academy_event WHERE skill='Grammar' AND active_ms IS NOT NULL")
    val freqGrammarMistakes = topicProgressMap.entries
        .filter { it.value.errors > 0 }
        .sortedByDescending { it.value.errors }
        .take(6)
        .map { (id, p) -> (lessons.find { it.id == id }?.title ?: id) to p.errors }
    val weakGrammar = topicProgressMap.entries
        .filter { it.value.status == "Practice Needed" || (it.value.practice != null && it.value.practice!! < 0.75) }
        .map { (id, _) -> lessons.find { it.id == id }?.title ?: id }
    val needsDelayed = topicProgressMap.entries
        .filter { it.value.status == "Needs Review" }
        .map { (id, _) -> lessons.find { it.id == id }?.title ?: id }

    // Recent improvements: topics whose latest practice/test accuracy exceeds their earliest
    val recentGrammarImprovements = mutableListOf<String>()
    val grammarRoundsByTopic = db.rows(
        "SELECT grammar, attempted, correct FROM academy_event WHERE skill='Grammar' AND grammar IS NOT NULL AND activity IN ('Practice','Assessment','Delayed review') AND attempted>0 ORDER BY at ASC",
    ) { Triple(it.getString(0), it.getInt(1), it.getInt(2)) }.groupBy { it.first }
    grammarRoundsByTopic.forEach { (tid, rows) ->
        if (rows.size >= 2) {
            val firstPct = (rows.first().third * 100) / rows.first().second
            val lastPct = (rows.last().third * 100) / rows.last().second
            if (lastPct > firstPct) {
                val title = lessons.find { it.id == tid }?.title ?: tid
                recentGrammarImprovements += "$title ($firstPct% → $lastPct%)"
            }
        }
    }

    val accByCat = linkedMapOf<String, Pair<Int, Int>>()
    db.rows(
        "SELECT grammar, coalesce(sum(correct),0), coalesce(sum(attempted),0) FROM academy_event WHERE skill='Grammar' AND grammar IS NOT NULL AND activity LIKE '%answer%' GROUP BY grammar",
    ) { Triple(it.getString(0), it.getInt(1), it.getInt(2)) }.forEach { (gid, corr, att) ->
        val cat = guides[gid]?.category ?: "General"
        val prev = accByCat[cat] ?: (0 to 0)
        accByCat[cat] = (prev.first + corr) to (prev.second + att)
    }
    val accuracyByCategory = accByCat.filterValues { it.second > 0 }.mapValues { (_, p) -> (p.first * 100) / p.second }
    val grammarByCefr = levels.associateWith { lv ->
        val group = lessons.filter { it.level == lv }
        group.count { topicProgressMap[it.id]?.status == "Mastered" } to group.size
    }

    val grammarAnalytics = GrammarAnalytics(
        totalTopics = lessons.size,
        topicsStarted = topicProgressMap.values.count { it.status != "Not Started" },
        lessonsCompleted = topicProgressMap.values.count { it.lesson },
        exercisesAttempted = grammarExAttempted,
        exercisesCorrect = grammarExCorrect,
        overallAccuracyPct = if (grammarExAttempted > 0) (grammarExCorrect * 100) / grammarExAttempted else null,
        testCount = grammarTests.size,
        averageTestPct = avgGrammarTest,
        masteryCounts = masteryCounts,
        frequentMistakes = freqGrammarMistakes,
        weakAreas = weakGrammar,
        recentImprovements = recentGrammarImprovements,
        needsDelayedReview = needsDelayed,
        accuracyByCategory = accuracyByCategory,
        progressByCefr = grammarByCefr,
        activeGrammarSeconds = grammarActiveMs / 1000L,
    )

    // Listening, Speaking, Writing Analytics
    val listeningCompleted = db.long("SELECT count(*) FROM exploration WHERE mode='Listening in Context' AND part='Completed'").toInt()
    val listeningAnswers = db.rows("SELECT item, correct FROM exploration WHERE mode='Listening in Context' AND part!='Completed'") {
        it.getString(0) to it.getInt(1)
    }
    val listeningActiveMs = db.long("SELECT coalesce(sum(active_ms),0) FROM academy_event WHERE skill='Listening' AND active_ms IS NOT NULL")
    val listeningByLevel = linkedMapOf<String, Pair<Int, Int>>()
    val listeningAssessments = testDaFAssessments().filter { it.skill == "Hören" }
    listeningAssessments.forEach { a ->
        val prev = listeningByLevel[a.difficulty] ?: (0 to 0)
        listeningByLevel[a.difficulty] = (prev.first + a.score.roundToInt()) to (prev.second + a.maxScore.roundToInt())
    }
    val listeningCorrect = listeningAnswers.count { it.second == 1 }
    val listeningAnalytics = ListeningAnalytics(
        tasksCompleted = listeningCompleted + listeningAssessments.size,
        answersAttempted = listeningAnswers.size,
        answersCorrect = listeningCorrect,
        accuracyPct = if (listeningAnswers.isNotEmpty()) (listeningCorrect * 100) / listeningAnswers.size else null,
        activeSeconds = listeningActiveMs / 1000L,
        byLevel = listeningByLevel,
    )

    val allAssessments = testDaFAssessments()
    val speakingSelfCount = db.long("SELECT count(*) FROM academy_event WHERE skill='Speaking' AND method='self-reported'").toInt()
    val speakingSelfMin = (db.long("SELECT coalesce(sum(active_ms),0) FROM academy_event WHERE skill='Speaking' AND method='self-reported'") / 60_000L).toInt()
    val speakingAssessed = allAssessments.filter { it.skill == "Sprechen" && it.evidenceType != EvidenceReliability.SELF_REPORTED }
    val speakingLatest = speakingAssessed.firstOrNull()
    val speakingAnalytics = SpeakingAnalytics(
        tasksAttempted = speakingSelfCount + allAssessments.count { it.skill == "Sprechen" },
        selfReportedCount = speakingSelfCount + allAssessments.count { it.skill == "Sprechen" && it.evidenceType == EvidenceReliability.SELF_REPORTED },
        selfReportedMinutes = speakingSelfMin,
        assessedCount = speakingAssessed.size,
        latestAssessedPct = speakingLatest?.let { (it.ratio * 100.0).roundToInt() },
        latestEvidenceLabel = speakingLatest?.evidenceType?.label,
    )

    val writingSelfCount = db.long("SELECT count(*) FROM academy_event WHERE skill='Writing' AND method='self-reported'").toInt()
    val writingSelfMin = (db.long("SELECT coalesce(sum(active_ms),0) FROM academy_event WHERE skill='Writing' AND method='self-reported'") / 60_000L).toInt()
    val writingAssessed = allAssessments.filter { it.skill == "Schreiben" && it.evidenceType != EvidenceReliability.SELF_REPORTED }
    val writingLatest = writingAssessed.firstOrNull()
    val writingNotes = allAssessments.filter { it.skill == "Schreiben" && it.notes.isNotBlank() }.take(5).map { it.day to it.notes }
    val writingAnalytics = WritingAnalytics(
        tasksCompleted = writingSelfCount + allAssessments.count { it.skill == "Schreiben" },
        selfReportedCount = writingSelfCount + allAssessments.count { it.skill == "Schreiben" && it.evidenceType == EvidenceReliability.SELF_REPORTED },
        selfReportedMinutes = writingSelfMin,
        assessedCount = writingAssessed.size,
        latestAssessedPct = writingLatest?.let { (it.ratio * 100.0).roundToInt() },
        latestEvidenceLabel = writingLatest?.evidenceType?.label,
        recentRubricNotes = writingNotes,
    )

    return AnalyticsBundle(
        activity = activityAnalytics,
        xp = xpAnalytics,
        vocabulary = vocabAnalytics,
        reading = readingAnalytics,
        grammar = grammarAnalytics,
        listening = listeningAnalytics,
        speaking = speakingAnalytics,
        writing = writingAnalytics,
    )
}
