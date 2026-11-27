package org.lexora.study

import android.database.sqlite.SQLiteDatabase
import java.time.ZoneId
import kotlin.math.roundToInt

internal val academyTables = listOf("academy_event","academy_credit","academy_inbox","academy_reader","academy_quest")
internal fun createAcademy(db: SQLiteDatabase) {
    db.execSQL("""CREATE TABLE academy_event(
        id TEXT PRIMARY KEY, schema_version INTEGER NOT NULL DEFAULT 1, at INTEGER NOT NULL, day TEXT NOT NULL,
        zone TEXT NOT NULL, skill TEXT NOT NULL, activity TEXT NOT NULL, content TEXT NOT NULL, level TEXT,
        method TEXT NOT NULL, confidence TEXT NOT NULL, source TEXT NOT NULL, version INTEGER NOT NULL,
        attempted INTEGER, correct INTEGER, answer TEXT, started INTEGER, active_ms INTEGER,
        rubric TEXT, quest TEXT, word TEXT REFERENCES word(id), grammar TEXT, assessment_type TEXT NOT NULL)""")
    db.execSQL("CREATE INDEX academy_event_topic ON academy_event(skill,content,activity,at)")
    db.execSQL("CREATE INDEX academy_event_day ON academy_event(day,activity)")
    db.execSQL("""CREATE TABLE academy_credit(reward_key TEXT PRIMARY KEY,event_id TEXT UNIQUE NOT NULL
        REFERENCES academy_event(id) ON DELETE CASCADE,amount INTEGER NOT NULL CHECK(amount>0),reason TEXT NOT NULL)""")
    db.execSQL("""CREATE TABLE academy_inbox(id TEXT PRIMARY KEY,word TEXT UNIQUE REFERENCES word(id),surface TEXT NOT NULL,
        passage TEXT NOT NULL,sentence TEXT NOT NULL,topic TEXT NOT NULL,at INTEGER NOT NULL,priority INTEGER NOT NULL DEFAULT 0,
        label TEXT NOT NULL DEFAULT '',archived INTEGER NOT NULL DEFAULT 0)""")
    db.execSQL("""CREATE TABLE academy_reader(passage TEXT PRIMARY KEY,paragraph INTEGER NOT NULL DEFAULT 0,
        bookmark INTEGER, touched INTEGER NOT NULL)""")
    db.execSQL("""CREATE TABLE academy_quest(id TEXT PRIMARY KEY,title TEXT NOT NULL,skill TEXT NOT NULL,metric TEXT NOT NULL,
        target INTEGER NOT NULL,day TEXT NOT NULL,recurring INTEGER NOT NULL,bonus INTEGER NOT NULL)""")
    // Project historical FSRS ratings through the existing correction-aware view. Undo and edits
    // immediately affect credit, and restoring the same event never creates a second transaction.
    db.execSQL("""CREATE VIEW academy_vocab AS SELECT e.id,e.word,e.kind,e.at,e.day,e.score,w.level,w.lemma,
        CASE WHEN e.score>=3 AND (e.kind='new' OR e.kind='review' AND e.detail='scheduled')
        THEN CASE WHEN e.kind='new' THEN 3 ELSE 2 END ELSE 0 END xp
        FROM effective_event e JOIN word w ON e.word=w.id WHERE e.kind IN ('new','review')""")
}

internal fun removeAcademySchema(db:SQLiteDatabase) {
    db.execSQL("DROP VIEW academy_vocab")
    academyTables.reversed().forEach { db.execSQL("DROP TABLE "+it) }
}

internal data class Evidence(val id:String,val skill:String,val activity:String,val content:String,
    val level:String?=null,val attempted:Int?=null,val correct:Int?=null,val answer:String?=null,
    val method:String="answer key",val kind:String="practice",val version:Int=1,val start:Long?=null,
    val activeMs:Long?=null,val quest:String?=null,val grammar:String?=null,val word:String?=null)

internal object AcademyXp {
    fun points(base:Int,level:String?,ratio:Double,novelty:Double=1.0):Int {
        if(base<=0 || ratio<=0.0)return 0
        val difficulty=when(level){"A1"->0.85;"B1"->1.15;"B2"->1.35;else->1.0}
        return (base*difficulty*ratio.coerceIn(0.0,1.0)*novelty).roundToInt().coerceAtLeast(1)
    }
    fun level(xp:Long)=1+xp/100
}

internal fun StudyRepository.record(e:Evidence, rewardKey:String?=null,amount:Int=0,reason:String=""):Boolean=db.tx {
    require(e.attempted==null || e.attempted>=0 && (e.correct==null || e.correct in 0..e.attempted))
    if(db.long("SELECT count(*) FROM academy_event WHERE id=?",e.id)>0)return@tx false
    val at=now()
    db.execSQL("""INSERT INTO academy_event(id,at,day,zone,skill,activity,content,level,method,confidence,source,version,
        attempted,correct,answer,started,active_ms,rubric,quest,word,grammar,assessment_type)
        VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)""",arrayOf(e.id,at,day(at),ZoneId.systemDefault().id,e.skill,
        e.activity,e.content,e.level,e.method,if(e.method=="answer key")"deterministic" else "reported",
        "lexora-local",e.version,e.attempted,e.correct,e.answer,e.start,e.activeMs,if(e.method=="answer key")"key-v1" else null,
        e.quest,e.word,e.grammar,e.kind))
    if(rewardKey!=null && amount>0)db.execSQL("INSERT OR IGNORE INTO academy_credit VALUES(?,?,?,?)",arrayOf(rewardKey,e.id,amount,reason))
    true
}
internal fun StudyRepository.academyXp()=db.long("SELECT COALESCE((SELECT sum(amount) FROM academy_credit),0)+COALESCE((SELECT sum(xp) FROM academy_vocab),0)")
