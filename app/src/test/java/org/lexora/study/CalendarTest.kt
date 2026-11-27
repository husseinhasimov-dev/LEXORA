package org.lexora.study

import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate
import java.time.YearMonth

class CalendarTest {
    @Test
    fun resolveDailyTargetUsesLatestEffectiveDate() {
        val targets = listOf(
            "2026-11-01" to 10,
            "2026-11-10" to 20,
            "2026-11-20" to 15,
        )
        assertEquals(12, resolveDailyTarget("2026-10-31", targets, fallback = 12))
        assertEquals(10, resolveDailyTarget("2026-11-01", targets, fallback = 12))
        assertEquals(10, resolveDailyTarget("2026-11-09", targets, fallback = 12))
        assertEquals(20, resolveDailyTarget("2026-11-15", targets, fallback = 12))
        assertEquals(15, resolveDailyTarget("2026-11-27", targets, fallback = 12))
    }

    @Test
    fun summarizeHistoryComputesTotalsStreaksAverageAndBestDay() {
        val records = listOf(
            DailyRecord("2026-11-27", learned = 12, newWords = 12, reviews = 8, minutes = 18, target = 10),
            DailyRecord("2026-11-26", learned = 10, newWords = 11, reviews = 14, minutes = 22, target = 10),
            DailyRecord("2026-11-25", learned = 6, newWords = 6, reviews = 5, minutes = 9, target = 10),
            // gap on Nov 24
            DailyRecord("2026-11-23", learned = 15, newWords = 15, reviews = 10, minutes = 25, target = 10),
            DailyRecord("2026-11-22", learned = 0, newWords = 0, reviews = 9, minutes = 7, target = 10),
        )
        val summary = summarizeHistory(records)
        assertEquals(43, summary.totalLearned)
        assertEquals(44, summary.totalNew)
        assertEquals(46, summary.totalReviews)
        assertEquals(81, summary.totalMinutes)
        assertEquals(5, summary.activeDays)
        assertEquals(3, summary.goalsMetDays)
        assertEquals(3, summary.bestStreak)
        assertEquals(43.0 / 5.0, summary.averagePerActiveDay, 0.0001)
        assertEquals("2026-11-23", summary.bestDay?.day)
        assertEquals(15, summary.bestDay?.learned)
    }

    @Test
    fun summarizeHistoryHandlesEmptyInput() {
        val summary = summarizeHistory(emptyList())
        assertEquals(0, summary.totalLearned)
        assertEquals(0, summary.activeDays)
        assertEquals(0, summary.bestStreak)
        assertNull(summary.bestDay)
    }

    @Test
    fun summarizeMonthFiltersOnlyTargetMonth() {
        val records = listOf(
            DailyRecord("2026-11-27", learned = 10, newWords = 10, reviews = 5, minutes = 12, target = 10),
            DailyRecord("2026-11-02", learned = 4, newWords = 5, reviews = 8, minutes = 9, target = 10),
            DailyRecord("2026-10-30", learned = 20, newWords = 20, reviews = 15, minutes = 30, target = 10),
        )
        val nov = summarizeMonth(YearMonth.of(2026, 11), records)
        assertEquals(14, nov.learned)
        assertEquals(15, nov.newWords)
        assertEquals(13, nov.reviews)
        assertEquals(21, nov.minutes)
        assertEquals(2, nov.activeDays)
        assertEquals(1, nov.goalsMetDays)
    }

    @Test
    fun buildMonthGridAlignsMondayToSundayAndLeapYears() {
        // November 2026 starts on Sunday (6 leading blanks before day 1) and has 30 days -> 36 cells -> padded to 42 (6 weeks)
        val novRecord = DailyRecord("2026-11-15", learned = 8, target = 10)
        val novGrid = buildMonthGrid(YearMonth.of(2026, 11), mapOf(novRecord.day to novRecord))
        assertEquals(6, novGrid.size)
        assertTrue(novGrid.all { it.size == 7 })
        assertNull(novGrid[0][5].date)
        assertEquals(LocalDate.of(2026, 11, 1), novGrid[0][6].date)
        val day15 = novGrid.flatten().single { it.date == LocalDate.of(2026, 11, 15) }
        assertEquals(8, day15.record?.learned)

        // Leap year February 2024 has 29 days
        val feb2024 = buildMonthGrid(YearMonth.of(2024, 2), emptyMap())
        assertEquals(29, feb2024.flatten().count { it.date != null })

        // Non-leap February 2026 has 28 days
        val feb2026 = buildMonthGrid(YearMonth.of(2026, 2), emptyMap())
        assertEquals(28, feb2026.flatten().count { it.date != null })
    }

    @Test
    fun formatStudyTimeFormatsMinutesAndHours() {
        assertEquals("0m", formatStudyTime(0))
        assertEquals("0m", formatStudyTime(-5))
        assertEquals("45m", formatStudyTime(45))
        assertEquals("1h", formatStudyTime(60))
        assertEquals("2h 15m", formatStudyTime(135))
    }
}
