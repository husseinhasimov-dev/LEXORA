package org.lexora.study

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.YearMonth
import kotlin.math.max

data class DailyRecord(
    val day: String,
    val learned: Int = 0,
    val newWords: Int = 0,
    val reviews: Int = 0,
    val minutes: Int = 0,
    val target: Int = 10,
) {
    val goalMet: Boolean get() = target > 0 && learned >= target
    val hasActivity: Boolean get() = learned > 0 || newWords > 0 || reviews > 0 || minutes > 0
}

data class HistorySummary(
    val totalLearned: Int = 0,
    val totalNew: Int = 0,
    val totalReviews: Int = 0,
    val totalMinutes: Int = 0,
    val activeDays: Int = 0,
    val goalsMetDays: Int = 0,
    val bestStreak: Int = 0,
    val averagePerActiveDay: Double = 0.0,
    val bestDay: DailyRecord? = null,
)

data class MonthSummary(
    val month: YearMonth,
    val learned: Int = 0,
    val newWords: Int = 0,
    val reviews: Int = 0,
    val minutes: Int = 0,
    val activeDays: Int = 0,
    val goalsMetDays: Int = 0,
)

data class CalendarCell(
    val date: LocalDate?,
    val record: DailyRecord?,
)

fun resolveDailyTarget(day: String, targets: List<Pair<String, Int>>, fallback: Int): Int {
    var resolved = fallback.coerceAtLeast(1)
    for ((targetDay, value) in targets) {
        if (targetDay <= day && value > 0) resolved = value
    }
    return resolved
}

fun summarizeHistory(records: List<DailyRecord>): HistorySummary {
    val active = records.filter { it.hasActivity }
    if (active.isEmpty()) return HistorySummary()

    val totalLearned = active.sumOf { it.learned }
    val totalNew = active.sumOf { it.newWords }
    val totalReviews = active.sumOf { it.reviews }
    val totalMinutes = active.sumOf { it.minutes }
    val activeDays = active.size
    val goalsMetDays = active.count { it.goalMet }

    val sortedDates = active
        .mapNotNull { runCatching { LocalDate.parse(it.day) }.getOrNull() }
        .distinct()
        .sorted()

    var bestStreak = 0
    var runStreak = 0
    var previous: LocalDate? = null
    for (d in sortedDates) {
        runStreak = if (previous != null && previous.plusDays(1) == d) runStreak + 1 else 1
        if (runStreak > bestStreak) bestStreak = runStreak
        previous = d
    }

    val bestDay = active
        .filter { it.learned > 0 }
        .maxWithOrNull(compareBy<DailyRecord>({ it.learned }, { it.reviews }, { it.day }))

    return HistorySummary(
        totalLearned = totalLearned,
        totalNew = totalNew,
        totalReviews = totalReviews,
        totalMinutes = totalMinutes,
        activeDays = activeDays,
        goalsMetDays = goalsMetDays,
        bestStreak = bestStreak,
        averagePerActiveDay = totalLearned.toDouble() / activeDays.toDouble(),
        bestDay = bestDay,
    )
}

fun summarizeMonth(month: YearMonth, records: List<DailyRecord>): MonthSummary {
    val prefix = month.toString() + "-"
    val monthRecords = records.filter { it.day.startsWith(prefix) && it.hasActivity }
    return MonthSummary(
        month = month,
        learned = monthRecords.sumOf { it.learned },
        newWords = monthRecords.sumOf { it.newWords },
        reviews = monthRecords.sumOf { it.reviews },
        minutes = monthRecords.sumOf { it.minutes },
        activeDays = monthRecords.size,
        goalsMetDays = monthRecords.count { it.goalMet },
    )
}

fun buildMonthGrid(month: YearMonth, recordsByDay: Map<String, DailyRecord>): List<List<CalendarCell>> {
    val firstDay = month.atDay(1)
    val leadingBlanks = (firstDay.dayOfWeek.value - DayOfWeek.MONDAY.value + 7) % 7
    val totalDays = month.lengthOfMonth()
    val cells = ArrayList<CalendarCell>(42)

    repeat(leadingBlanks) { cells.add(CalendarCell(null, null)) }
    for (dayOfMonth in 1..totalDays) {
        val date = month.atDay(dayOfMonth)
        cells.add(CalendarCell(date, recordsByDay[date.toString()]))
    }
    while (cells.size % 7 != 0) {
        cells.add(CalendarCell(null, null))
    }
    return cells.chunked(7)
}

fun formatStudyTime(minutes: Int): String {
    val safe = max(0, minutes)
    val hours = safe / 60
    val mins = safe % 60
    return when {
        hours == 0 -> "${mins}m"
        mins == 0 -> "${hours}h"
        else -> "${hours}h ${mins}m"
    }
}

fun StudyRepository.dailyHistory(): List<DailyRecord> {
    val learnedByDay = db.rows("SELECT firstDay, count(*) FROM $successDays GROUP BY firstDay") {
        it.getString(0) to it.getInt(1)
    }.toMap()

    val ratingsByDay = db.rows(
        """SELECT day,
            sum(CASE WHEN kind='new' THEN 1 ELSE 0 END),
            sum(CASE WHEN kind='review' THEN 1 ELSE 0 END)
            FROM $ratings GROUP BY day"""
    ) {
        it.getString(0) to (it.getInt(1) to it.getInt(2))
    }.toMap()

    val minutesByDay = db.rows(
        "SELECT day, coalesce(sum(score), 0) FROM effective_event WHERE kind='time' GROUP BY day"
    ) {
        it.getString(0) to (it.getLong(1) / 60000L).toInt()
    }.toMap()

    val targets = db.rows(
        "SELECT substr(k, 13), v FROM pref WHERE k LIKE 'dailyTarget.%' ORDER BY k"
    ) {
        it.getString(0) to (it.getString(1).toIntOrNull() ?: 0)
    }.filter { it.first.length == 10 && it.second > 0 }

    val fallbackGoal = pref("goal").toIntOrNull()?.coerceAtLeast(1) ?: 10
    val allDays = (learnedByDay.keys + ratingsByDay.keys + minutesByDay.keys)
        .filter { it.length == 10 }
        .sortedDescending()

    return allDays.mapNotNull { d ->
        val learned = learnedByDay[d] ?: 0
        val pair = ratingsByDay[d]
        val newWords = pair?.first ?: 0
        val reviews = pair?.second ?: 0
        val minutes = minutesByDay[d] ?: 0
        val rec = DailyRecord(
            day = d,
            learned = learned,
            newWords = newWords,
            reviews = reviews,
            minutes = minutes,
            target = resolveDailyTarget(d, targets, fallbackGoal),
        )
        if (rec.hasActivity) rec else null
    }
}

fun StudyRepository.wordsLearnedOn(date: String): List<Word> {
    if (date.length != 10) return emptyList()
    return db.rows(
        """SELECT w.* FROM word w
           JOIN $successDays s ON s.word=w.id
           WHERE s.firstDay=?
           ORDER BY w.lemma COLLATE NOCASE, w.id""",
        listOf(date),
    ) { it.word() }
}
