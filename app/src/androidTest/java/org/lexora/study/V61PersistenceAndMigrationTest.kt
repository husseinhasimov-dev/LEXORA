package org.lexora.study

import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.io.File
import java.time.DayOfWeek
import java.time.LocalDate
import java.util.UUID

class V61PersistenceAndMigrationTest {
    private val ctx = InstrumentationRegistry.getInstrumentation().targetContext
    private val owner = UUID.randomUUID().toString()
    private var clock = 1_800_000_000_000L
    private lateinit var repo: StudyRepository

    @Before
    fun setUp() {
        repo = StudyRepository(ctx, owner) { clock }
    }

    @After
    fun tearDown() {
        val name = File(repo.db.path).name
        repo.close()
        ctx.deleteDatabase(name)
    }

    private fun restart() {
        repo.close()
        repo = StudyRepository(ctx, owner) { clock }
    }

    @Test
    fun upgradeFromSchema6ToSchema7PreservesFsrsAndAcademyData() {
        val w = repo.words(Filters()).first()
        repo.event(w.id, "new", 3)
        val card = Memory.review(w.id, null, 3, clock, 90)
        repo.db.saveCard(w.id, card)
        repo.record(Evidence("v6-ev", "Grammar", "Practice", "pronouns", "A1", 1, 1, grammar = "pronouns"), "v6-rk", 12, "V6 credit")

        // Simulate a V6 database right before V6.1 upgrade
        intelligenceTables.reversed().forEach { repo.db.execSQL("DROP TABLE IF EXISTS $it") }
        repo.db.version = 6
        restart()

        assertEquals(7, repo.db.version)
        assertEquals("ok", repo.db.str("PRAGMA integrity_check"))
        assertEquals(0L, repo.db.long("SELECT count(*) FROM pragma_foreign_key_check"))
        assertEquals(card.due.toEpochMilli(), repo.db.long("SELECT due FROM card WHERE word=?", w.id))
        assertEquals(15L, repo.academyXp()) // 12 grammar + 3 new word
    }

    @Test
    fun testDaFConfigAssessmentsAlertsAndProposalsPersistAndBackup() {
        val today = LocalDate.parse(repo.day())
        val cfg = TestDaFConfig(
            targetDate = today.plusDays(90),
            weeklyHours = 8.5,
            restDays = setOf(DayOfWeek.SUNDAY),
            milestones = listOf(ExamMilestone("m1", "Full B2 Mock", today.plusDays(30).toString(), false)),
        )
        repo.saveTestDaFConfig(cfg)
        repo.saveTestDaFAssessment(
            TestDaFAssessmentRecord(
                id = "a1",
                skill = "Lesen",
                evidenceType = EvidenceReliability.EXAM_DIAGNOSTIC,
                score = 16.0,
                maxScore = 20.0,
                tdnLevel = "TDN4",
                difficulty = "TestDaF",
                format = "Digital TestDaF",
                assessedAt = clock,
                day = today.toString(),
                notes = "Timed mock 1",
            ),
        )
        repo.setAlertState("exam:missing-evidence:Hören, Schreiben, Sprechen", "acknowledged")
        val catalog = loadGrammarCatalog(ctx)
        assertEquals(50, catalog.totalTopics)
        assertEquals(12, catalog.newTopicCount)
        assertEquals(224, catalog.newExerciseCount)

        restart()

        val loadedCfg = repo.loadTestDaFConfig()
        assertEquals(cfg.targetDate, loadedCfg.targetDate)
        assertEquals(8.5, loadedCfg.weeklyHours, 0.01)
        assertEquals(setOf(DayOfWeek.SUNDAY), loadedCfg.restDays)
        assertEquals(1, loadedCfg.milestones.size)

        val readings = OfflineReadings.passages(ctx)
        val analytics = repo.computeAnalytics(AnalyticsWindow.LAST_30, readings, catalog.lessons, catalog.guides)
        val mission = repo.computeMissionControl(analytics, readings)
        val lesenPanel = mission.panels.first { it.skill == "Lesen" }
        assertEquals(SkillPrepStatus.TARGET_RANGE_INDICATED, lesenPanel.status)
        assertEquals(SkillPrepStatus.INSUFFICIENT_EVIDENCE, mission.panels.first { it.skill == "Sprechen" }.status)
        // Strong Lesen must NOT make overall 4-skill exam risk LOW!
        assertNotEquals(RiskLevel.LOW, mission.risk.examReadinessRisk.level)

        // Backup & restore into a brand new profile preserves all V6.1 intelligence tables
        val backupJson = repo.backup()
        val copyOwner = UUID.randomUUID().toString()
        val copy = StudyRepository(ctx, copyOwner) { clock }
        try {
            copy.restore(backupJson)
            assertEquals(1, copy.testDaFAssessments().size)
            assertEquals("TDN4", copy.testDaFAssessments().first().tdnLevel)
            assertEquals("acknowledged", copy.alertStates()["exam:missing-evidence:Hören, Schreiben, Sprechen"]?.first)
        } finally {
            val copyName = File(copy.db.path).name
            copy.close()
            ctx.deleteDatabase(copyName)
        }
    }
}
