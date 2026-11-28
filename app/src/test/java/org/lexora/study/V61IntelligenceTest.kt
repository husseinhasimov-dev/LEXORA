package org.lexora.study

import org.junit.Assert.*
import org.junit.Test
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.ZoneOffset

class V61IntelligenceTest {
    private val today = LocalDate.of(2026, 11, 28)

    private fun millis(day: LocalDate) = day.atStartOfDay().toInstant(ZoneOffset.UTC).toEpochMilli()

    private fun rec(
        skill: String,
        tier: EvidenceReliability,
        score: Double,
        max: Double = 20.0,
        difficulty: String = "TestDaF",
        day: LocalDate = today,
        tdn: String? = null,
    ) = TestDaFAssessmentRecord(
        skill = skill,
        evidenceType = tier,
        score = score,
        maxScore = max,
        tdnLevel = tdn,
        difficulty = difficulty,
        format = "Digital TestDaF",
        assessedAt = millis(day),
        day = day.toString(),
    )

    private fun emptyAnalytics(
        weeklyMin: Int = 0,
        due: Int = 0,
        streak: Int = 0,
        weeklyXp: Long = 0,
        prevWeekXp: Long = 0,
        grammarAttempted: Int = 0,
        grammarCorrect: Int = 0,
        readingHistory: List<ReadingHistoryItem> = emptyList(),
        reviewSuccess: Int? = null,
        inFsrs: Int = 0,
        masteredGrammar: Int = 0,
    ): AnalyticsBundle {
        val range = resolveDateRange(AnalyticsWindow.LAST_30, today)
        return AnalyticsBundle(
            activity = StudyActivityAnalytics(
                range = range,
                totalActiveMinutesAllTime = weeklyMin,
                observedActiveMinutesAllTime = weeklyMin,
                reportedMinutesAllTime = 0,
                todayMinutes = weeklyMin / 7,
                weeklyMinutes = weeklyMin,
                monthlyMinutes = weeklyMin,
                rangeObservedMinutes = weeklyMin,
                rangeReportedMinutes = 0,
                averageMinutesPerActiveDay = if (weeklyMin > 0) weeklyMin / 3.0 else null,
                sessionsCompletedInRange = if (weeklyMin > 0) 3 else 0,
                activeDaysInRange = if (weeklyMin > 0) 3 else 0,
                currentStreak = streak,
                longestStreak = streak,
                configuredRestDays = setOf(DayOfWeek.SUNDAY),
                restDaysTakenInRange = 1,
                weeklyConsistencyPct = if (weeklyMin > 0) 75 else 0,
                plannedMinutesInRange = 300,
                actualMinutesInRange = weeklyMin,
                dailySeries = emptyList(),
            ),
            xp = XpAnalytics(
                totalXp = weeklyXp,
                todayXp = weeklyXp,
                weeklyXp = weeklyXp,
                previousWeekXp = prevWeekXp,
                monthlyXp = weeklyXp,
                previousMonthXp = prevWeekXp,
                rangeXp = weeklyXp,
                level = AcademyXp.level(weeklyXp),
                xpInCurrentLevel = weeklyXp % 100,
                xpToNextLevel = 100 - (weeklyXp % 100),
                xpByActivity = emptyMap(),
                xpBySkill = emptyMap(),
                xpPerActiveHour = null,
                highestXpDay = null,
                dailyGrowthPct = null,
                weeklyGrowthPct = xpGrowthPercent(weeklyXp, prevWeekXp),
                monthlyGrowthPct = xpGrowthPercent(weeklyXp, prevWeekXp),
                rollingSeries = emptyList(),
                achievements = emptyList(),
            ),
            vocabulary = VocabularyAnalytics(
                totalCatalog = 6546,
                collectedInbox = 0,
                inboxBacklog = 0,
                inboxUnresolved = 0,
                selectedForLearning = 0,
                newlyStudied = inFsrs,
                inFsrsReview = inFsrs,
                currentlyDue = due,
                stableFsrs = inFsrs / 2,
                knownCount = inFsrs,
                unknownEstimate = 6546 - inFsrs,
                averageReviewSuccessPct = reviewSuccess,
                predictedRetentionPct = reviewSuccess,
                retentionTrend = emptyList(),
                frequentlyMissed = emptyList(),
                byCefr = emptyList(),
                byTopic = emptyList(),
                academicTotal = 200,
                academicCovered = 0,
            ),
            reading = ReadingAnalytics(
                passagesCompleted = readingHistory.size,
                uniquePassagesCompleted = readingHistory.map { it.passageId }.distinct().size,
                totalWordsRead = readingHistory.sumOf { it.words },
                totalActiveReadingSeconds = 120,
                averageComprehensionPct = if (readingHistory.isEmpty()) null else
                    (readingHistory.sumOf { it.correct } * 100) / readingHistory.sumOf { it.attempted }.coerceAtLeast(1),
                byCefr = emptyMap(),
                byTopic = emptyMap(),
                vocabCollectedFromReading = 0,
                vocabPerPassage = null,
                readingSpeedWpm = null,
                repeatedImprovements = emptyList(),
                strongestTheme = null,
                weakestTheme = null,
                history = readingHistory,
            ),
            grammar = GrammarAnalytics(
                totalTopics = 50,
                topicsStarted = if (grammarAttempted > 0) 3 else 0,
                lessonsCompleted = masteredGrammar,
                exercisesAttempted = grammarAttempted,
                exercisesCorrect = grammarCorrect,
                overallAccuracyPct = if (grammarAttempted > 0) (grammarCorrect * 100) / grammarAttempted else null,
                testCount = 0,
                averageTestPct = null,
                masteryCounts = mapOf("Mastered" to masteredGrammar),
                frequentMistakes = emptyList(),
                weakAreas = if (grammarAttempted > 0 && grammarCorrect * 100 / grammarAttempted < 70) listOf("Dative") else emptyList(),
                recentImprovements = emptyList(),
                needsDelayedReview = emptyList(),
                accuracyByCategory = emptyMap(),
                progressByCefr = emptyMap(),
                activeGrammarSeconds = 60,
            ),
            listening = ListeningAnalytics(0, 0, 0, null, 0, emptyMap()),
            speaking = SpeakingAnalytics(0, 0, 0, 0, null, null),
            writing = WritingAnalytics(0, 0, 0, 0, null, null, emptyList()),
        )
    }

    // ==================== ANALYTICS TESTS ====================

    @Test
    fun xpGrowthHandlesZeroDenominatorAndNormalRatios() {
        assertNull(xpGrowthPercent(120, 0))
        assertNull(xpGrowthPercent(0, 0))
        assertEquals("No comparable previous period", formatGrowth(xpGrowthPercent(120, 0)))
        assertEquals(50.0, xpGrowthPercent(150, 100)!!, 0.0001)
        assertEquals("+50.0%", formatGrowth(xpGrowthPercent(150, 100)))
        assertEquals(-25.0, xpGrowthPercent(75, 100)!!, 0.0001)
        assertEquals("-25.0%", formatGrowth(xpGrowthPercent(75, 100)))
    }

    @Test
    fun dateRangeResolutionNormalizesCustomBounds() {
        val r7 = resolveDateRange(AnalyticsWindow.LAST_7, today)
        assertEquals(today.minusDays(6), r7.from)
        assertEquals(today, r7.to)

        val reversed = resolveDateRange(AnalyticsWindow.CUSTOM, today, customFrom = today, customTo = today.minusDays(10))
        assertEquals(today.minusDays(10), reversed.from)
        assertEquals(today, reversed.to)
    }

    @Test
    fun streaksRespectConfiguredRestDaysWithoutBreaking() {
        // Nov 28, 2026 is Saturday; Nov 22 is Sunday (rest day)
        val active = setOf(
            LocalDate.of(2026, 11, 20), // Fri
            LocalDate.of(2026, 11, 21), // Sat
            // Nov 22 Sunday = rest day
            LocalDate.of(2026, 11, 23), // Mon
            LocalDate.of(2026, 11, 24), // Tue
        )
        val (curWithRest, bestWithRest) = computeStreaks(active, LocalDate.of(2026, 11, 24), setOf(DayOfWeek.SUNDAY))
        assertEquals(4, curWithRest)
        assertEquals(4, bestWithRest)

        // Without Sunday as rest day, streak resets after Sunday gap
        val (curNoRest, bestNoRest) = computeStreaks(active, LocalDate.of(2026, 11, 24), emptySet())
        assertEquals(2, curNoRest)
        assertEquals(2, bestNoRest)
    }

    // ==================== TESTDAF & RISK ENGINE TESTS ====================

    @Test
    fun a2PracticeNeverProducesTargetRangeIndicated() {
        val a2Quiz = rec("Lesen", EvidenceReliability.AUTO_PRACTICE, 20.0, 20.0, difficulty = "A2", day = today)
        val panel = evaluateSkillPanel("Lesen", listOf(a2Quiz), today)
        assertEquals(SkillPrepStatus.FOUNDATION_BUILDING, panel.status)
    }

    @Test
    fun outdatedAssessmentTransitionsToNeedsReassessment() {
        val oldExam = rec("Lesen", EvidenceReliability.OFFICIAL_EXAM, 18.0, 20.0, difficulty = "TestDaF", day = today.minusDays(60), tdn = "TDN4")
        val panel = evaluateSkillPanel("Lesen", listOf(oldExam), today)
        assertEquals(SkillPrepStatus.NEEDS_REASSESSMENT, panel.status)
    }

    @Test
    fun weakestSkillGovernsFourSkillExamRisk() {
        val config = TestDaFConfig(targetDate = today.plusDays(60), weeklyHours = 6.0)
        val records = listOf(
            rec("Lesen", EvidenceReliability.OFFICIAL_EXAM, 18.0, 20.0, tdn = "TDN5"),
            rec("Hören", EvidenceReliability.EXAM_DIAGNOSTIC, 17.0, 20.0, tdn = "TDN4"),
            rec("Schreiben", EvidenceReliability.EXAM_DIAGNOSTIC, 16.0, 20.0, tdn = "TDN4"),
            rec("Sprechen", EvidenceReliability.EXAM_DIAGNOSTIC, 9.0, 20.0, tdn = "Below TDN3"),
        )
        val panels = testDaFSkills.map { evaluateSkillPanel(it, records, today) }
        val risk = evaluateRisks(config, panels, emptyAnalytics(weeklyMin = 360), today)

        assertEquals("Sprechen", risk.weakestSkill.skill)
        assertEquals(SkillPrepStatus.BELOW_TARGET, risk.weakestSkill.status)
        assertEquals(RiskLevel.HIGH, risk.examReadinessRisk.level)
        assertEquals(RiskLevel.LOW, risk.scheduleRisk.level)
    }

    @Test
    fun expiredExamDateRaisesHighScheduleRisk() {
        val config = TestDaFConfig(targetDate = today.minusDays(5), weeklyHours = 5.0)
        val panels = testDaFSkills.map { evaluateSkillPanel(it, emptyList(), today) }
        val risk = evaluateRisks(config, panels, emptyAnalytics(weeklyMin = 300), today)
        assertTrue(config.isExpired(today))
        assertEquals(RiskLevel.HIGH, risk.scheduleRisk.level)
        assertEquals(RiskLevel.UNKNOWN, risk.examReadinessRisk.level)
    }

    // ==================== FORECASTING TESTS ====================

    @Test
    fun forecastRefusesToFabricateWithoutComparableEvidence() {
        val config = TestDaFConfig(targetDate = today.plusDays(120), weeklyHours = 10.0)
        val panels = testDaFSkills.map { evaluateSkillPanel(it, emptyList(), today) }
        val fc = computeReadinessForecast(config, panels, today)
        assertFalse(fc.sufficient)
        assertEquals("Insufficient evidence for a reliable readiness forecast.", fc.headline)
        assertTrue(fc.scenarios.isEmpty())
        assertTrue(fc.missingRequirements.isNotEmpty())
    }

    @Test
    fun forecastProvidesThreeConservativeScenariosWithComparableLongitudinalData() {
        val config = TestDaFConfig(targetDate = today.plusDays(180), weeklyHours = 6.0)
        val records = listOf(
            rec("Lesen", EvidenceReliability.EXAM_DIAGNOSTIC, 11.0, 20.0, day = today.minusDays(30)),
            rec("Lesen", EvidenceReliability.EXAM_DIAGNOSTIC, 13.0, 20.0, day = today),
            rec("Hören", EvidenceReliability.EXAM_DIAGNOSTIC, 12.0, 20.0, day = today),
            rec("Schreiben", EvidenceReliability.EXAM_DIAGNOSTIC, 13.0, 20.0, day = today),
            rec("Sprechen", EvidenceReliability.EXAM_DIAGNOSTIC, 11.0, 20.0, day = today),
        )
        val panels = testDaFSkills.map { evaluateSkillPanel(it, records, today) }
        val fc = computeReadinessForecast(config, panels, today)
        assertTrue(fc.sufficient)
        assertEquals(3, fc.scenarios.size)
        assertTrue(fc.disclaimer.contains("NOT a guarantee"))
    }

    // ==================== ALERTS & RECOMMENDATIONS TESTS ====================

    @Test
    fun alertsPreventNoiseFromSingleBadReadingAndRespectDismissal() {
        val config = TestDaFConfig(targetDate = today.plusDays(40), weeklyHours = 5.0)
        val panels = testDaFSkills.map { evaluateSkillPanel(it, emptyList(), today) }
        val singleBadRead = listOf(ReadingHistoryItem(today.toString(), "market", "Der letzte Einkauf", "A1", "Everyday", 1, 3, 152))
        val an = emptyAnalytics(weeklyMin = 60, due = 35, streak = 5, weeklyXp = 200, prevWeekXp = 100, readingHistory = singleBadRead, masteredGrammar = 2)
        val risk = evaluateRisks(config, panels, an, today)
        val fc = computeReadinessForecast(config, panels, today)
        val vel = buildVelocityReport(30, emptyList(), emptyList(), singleBadRead, today)
        val mc = MissionControlBundle(config, panels, risk, fc, vel, vel, vel)

        val alerts = generateLearningAlerts(an, mc, emptyMap(), today, pendingQuestCount = 2)
        // Must NOT trigger reading decline from 1 isolated round
        assertTrue(alerts.none { it.fingerprint.startsWith("perf:reading-decline:") })
        // MUST trigger FSRS backlog, missing exam diagnostic evidence, and positive insights (streak, mastery, xp growth)
        assertTrue(alerts.any { it.fingerprint.startsWith("plan:fsrs-backlog:") })
        assertTrue(alerts.any { it.fingerprint.startsWith("exam:missing-evidence:") })
        assertTrue(alerts.any { it.category == AlertCategory.POSITIVE })

        // Dismissal suppresses exact fingerprint
        val toDismiss = alerts.first().fingerprint
        val afterDismiss = generateLearningAlerts(an, mc, mapOf(toDismiss to ("dismissed" to 1L)), today, pendingQuestCount = 2)
        assertTrue(afterDismiss.none { it.fingerprint == toDismiss })
    }

    @Test
    fun smartProposalsRespectRejectedDecisionsAndPrioritizeWeakestSkill() {
        val config = TestDaFConfig(targetDate = today.plusDays(90), weeklyHours = 5.0)
        val panels = testDaFSkills.map { evaluateSkillPanel(it, emptyList(), today) }
        val an = emptyAnalytics(weeklyMin = 120, due = 30)
        val risk = evaluateRisks(config, panels, an, today)
        val fc = computeReadinessForecast(config, panels, today)
        val vel = buildVelocityReport(30, emptyList(), emptyList(), emptyList(), today)
        val mc = MissionControlBundle(config, panels, risk, fc, vel, vel, vel)

        val props = generateStudyPlanProposals(an, mc, emptyMap(), today)
        assertTrue(props.isNotEmpty())
        assertTrue(props.any { it.fingerprint.startsWith("prop:fsrs-backlog:") })

        val rejected = mapOf(props.first().fingerprint to ("rejected" to 1L))
        val afterReject = generateStudyPlanProposals(an, mc, rejected, today)
        assertTrue(afterReject.none { it.fingerprint == props.first().fingerprint })
    }

    // ==================== GRAMMAR EXPANSION TESTS ====================

    @Test
    fun personalizedGrammarRevisionPrioritizesDelayedReviewAndErrors() {
        val l1 = Lesson("t1", "A1", "Topic 1", "", "ex", "eg", "err", emptyList())
        val l2 = Lesson("t2", "A1", "Topic 2", "t1", "ex", "eg", "err", emptyList())
        val l3 = Lesson("t3", "A2", "Topic 3", "t2", "ex", "eg", "err", emptyList())
        val progress = mapOf(
            "t1" to TopicProgress("Practice Needed", true, 0.5, null, null, 0L, 3),
            "t2" to TopicProgress("Needs Review", true, 0.9, 0.9, null, 1L, 0),
            "t3" to TopicProgress("Not Started", false, null, null, null, 0L, 0),
        )
        val ranked = computePersonalizedGrammarRevisions(listOf(l1, l2, l3), progress, emptyList(), emptyList())
        assertEquals("t2", ranked.first().lesson.id)
        assertEquals("Delayed review", ranked.first().suggestedMode)
        assertEquals("t1", ranked[1].lesson.id)
        assertTrue(GrammarReferenceTables.all.size >= 6)
    }
}
