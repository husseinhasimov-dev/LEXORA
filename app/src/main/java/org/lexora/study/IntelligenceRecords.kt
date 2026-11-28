package org.lexora.study

import android.database.sqlite.SQLiteDatabase
import java.util.UUID

internal val intelligenceTables = listOf(
    "testdaf_assessment",
    "intelligence_alert_state",
    "study_plan_proposal",
)

internal fun createIntelligenceSchema(db: SQLiteDatabase) {
    db.execSQL(
        """CREATE TABLE IF NOT EXISTS testdaf_assessment(
            id TEXT PRIMARY KEY,
            skill TEXT NOT NULL,
            evidence_type TEXT NOT NULL,
            score REAL NOT NULL,
            max_score REAL NOT NULL,
            tdn_level TEXT,
            difficulty TEXT NOT NULL,
            format TEXT NOT NULL,
            rubric_version TEXT NOT NULL DEFAULT 'v1',
            content_version INTEGER NOT NULL DEFAULT 1,
            assessed_at INTEGER NOT NULL,
            day TEXT NOT NULL,
            notes TEXT NOT NULL DEFAULT '',
            source_event_id TEXT
        )"""
    )
    db.execSQL("CREATE INDEX IF NOT EXISTS testdaf_assessment_skill ON testdaf_assessment(skill, assessed_at)")
    db.execSQL(
        """CREATE TABLE IF NOT EXISTS intelligence_alert_state(
            fingerprint TEXT PRIMARY KEY,
            status TEXT NOT NULL,
            updated_at INTEGER NOT NULL
        )"""
    )
    db.execSQL(
        """CREATE TABLE IF NOT EXISTS study_plan_proposal(
            id TEXT PRIMARY KEY,
            fingerprint TEXT NOT NULL,
            title TEXT NOT NULL,
            skill TEXT NOT NULL,
            metric TEXT NOT NULL,
            target INTEGER NOT NULL,
            rationale TEXT NOT NULL,
            status TEXT NOT NULL,
            created_at INTEGER NOT NULL,
            decided_at INTEGER
        )"""
    )
    db.execSQL("CREATE INDEX IF NOT EXISTS study_plan_proposal_fp ON study_plan_proposal(fingerprint, status)")
}

enum class EvidenceReliability(val code: String, val label: String, val rank: Int) {
    SELF_REPORTED("self_reported", "Self-reported", 1),
    AUTO_PRACTICE("auto_practice", "Automatically graded practice", 2),
    AI_EVALUATED("ai_evaluated", "AI-evaluated practice", 3),
    EXAM_DIAGNOSTIC("exam_diagnostic", "Exam-like diagnostic assessment", 4),
    OFFICIAL_EXAM("official_exam", "Official examination result", 5);

    companion object {
        fun fromCode(code: String): EvidenceReliability =
            entries.firstOrNull { it.code == code || it.name.equals(code, ignoreCase = true) } ?: SELF_REPORTED
    }
}

data class TestDaFAssessmentRecord(
    val id: String = UUID.randomUUID().toString(),
    val skill: String, // Lesen, Hören, Schreiben, Sprechen
    val evidenceType: EvidenceReliability,
    val score: Double,
    val maxScore: Double,
    val tdnLevel: String? = null, // "Below TDN3", "TDN3", "TDN4", "TDN5", or null
    val difficulty: String = "TestDaF", // A1, A2, B1, B2, C1, TestDaF
    val format: String = "Digital TestDaF",
    val rubricVersion: String = "v1",
    val contentVersion: Int = 1,
    val assessedAt: Long,
    val day: String,
    val notes: String = "",
    val sourceEventId: String? = null,
) {
    val ratio: Double get() = if (maxScore > 0.0) (score / maxScore).coerceIn(0.0, 1.0) else 0.0
    val isExamRelevant: Boolean
        get() = difficulty in setOf("B2", "C1", "TestDaF") && evidenceType.rank >= EvidenceReliability.AUTO_PRACTICE.rank
    val supportsTargetIndication: Boolean
        get() = difficulty in setOf("B2", "C1", "TestDaF") && evidenceType.rank >= EvidenceReliability.EXAM_DIAGNOSTIC.rank
}

internal fun StudyRepository.saveTestDaFAssessment(record: TestDaFAssessmentRecord) {
    require(record.skill in testDaFSkills) { "Unsupported skill: ${record.skill}" }
    require(record.maxScore > 0.0 && record.score >= 0.0 && record.score <= record.maxScore) { "Score must be within 0..maxScore" }
    db.execSQL(
        """INSERT OR REPLACE INTO testdaf_assessment(
            id, skill, evidence_type, score, max_score, tdn_level, difficulty, format,
            rubric_version, content_version, assessed_at, day, notes, source_event_id
        ) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?)""",
        arrayOf(
            record.id,
            record.skill,
            record.evidenceType.code,
            record.score,
            record.maxScore,
            record.tdnLevel,
            record.difficulty,
            record.format,
            record.rubricVersion,
            record.contentVersion,
            record.assessedAt,
            record.day,
            record.notes.trim(),
            record.sourceEventId,
        ),
    )
}

internal fun StudyRepository.deleteTestDaFAssessment(id: String) {
    db.delete("testdaf_assessment", "id=?", arrayOf(id))
}

internal fun StudyRepository.testDaFAssessments(): List<TestDaFAssessmentRecord> =
    db.rows("SELECT * FROM testdaf_assessment ORDER BY assessed_at DESC, id DESC") { c ->
        TestDaFAssessmentRecord(
            id = c.getString(0),
            skill = c.getString(1),
            evidenceType = EvidenceReliability.fromCode(c.getString(2)),
            score = c.getDouble(3),
            maxScore = c.getDouble(4),
            tdnLevel = c.getString(5),
            difficulty = c.getString(6),
            format = c.getString(7),
            rubricVersion = c.getString(8),
            contentVersion = c.getInt(9),
            assessedAt = c.getLong(10),
            day = c.getString(11),
            notes = c.getString(12).orEmpty(),
            sourceEventId = c.getString(13),
        )
    }

internal fun StudyRepository.alertStates(): Map<String, Pair<String, Long>> =
    db.rows("SELECT fingerprint, status, updated_at FROM intelligence_alert_state") { c ->
        c.getString(0) to (c.getString(1) to c.getLong(2))
    }.toMap()

internal fun StudyRepository.setAlertState(fingerprint: String, status: String) {
    db.execSQL(
        "INSERT OR REPLACE INTO intelligence_alert_state(fingerprint, status, updated_at) VALUES(?,?,?)",
        arrayOf(fingerprint, status, now()),
    )
}

internal fun StudyRepository.clearAlertStates() {
    db.delete("intelligence_alert_state", null, null)
}

internal fun StudyRepository.proposalDecisions(): Map<String, Pair<String, Long>> =
    db.rows("SELECT fingerprint, status, coalesce(decided_at, created_at) FROM study_plan_proposal ORDER BY created_at DESC") { c ->
        c.getString(0) to (c.getString(1) to c.getLong(2))
    }.associate { it.first to it.second }

internal fun StudyRepository.recordProposalDecision(
    fingerprint: String,
    title: String,
    skill: String,
    metric: String,
    target: Int,
    rationale: String,
    status: String,
) {
    val t = now()
    db.execSQL(
        """INSERT OR REPLACE INTO study_plan_proposal(
            id, fingerprint, title, skill, metric, target, rationale, status, created_at, decided_at
        ) VALUES(?,?,?,?,?,?,?,?,?,?)""",
        arrayOf(UUID.randomUUID().toString(), fingerprint, title, skill, metric, target, rationale, status, t, t),
    )
}

val testDaFSkills = listOf("Lesen", "Hören", "Schreiben", "Sprechen")
