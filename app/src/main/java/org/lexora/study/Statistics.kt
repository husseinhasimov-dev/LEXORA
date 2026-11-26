package org.lexora.study

import java.time.*
import kotlin.math.roundToInt

// Two offline devices can introduce the same word. Retain both ratings for FSRS
// and count only the earliest introduction towards new-word goals and XP.
const val ratings = """(SELECT e.day,e.score,e.word,CASE WHEN e.kind='new' AND e.id<>(
SELECT first.id FROM effective_event first WHERE first.word=e.word AND first.kind='new' ORDER BY first.at,first.id LIMIT 1)
THEN 'review' ELSE e.kind END AS kind FROM effective_event e WHERE e.kind IN ('new','review'))"""

fun StudyRepository.stats():Stats {
    val timestamp=now()
    val date=LocalDate.parse(day(timestamp))
    val zone=ZoneId.systemDefault()
    val schedule=Memory.scheduler(pref("retention").toInt())
    var n=0; var learned=0;var inLearning=0;var s=0.0;var d=0.0;var r=0.0
    db.rawQuery("SELECT * FROM active_card",null).use { cursor ->
        while(cursor.moveToNext()) {
            val card=cursor.card()
            n++;s+=card.stability;d+=card.difficulty
            r+=schedule.getCardRetrievability(card,Instant.ofEpochMilli(timestamp))
            if(card.state.name!="REVIEW") inLearning++
            else if(card.stability>=30 && card.due.toEpochMilli()>=timestamp) learned++
        }
    }
    val dates=db.rows("SELECT distinct day FROM effective_event WHERE kind IN ('new','review') ORDER BY day DESC"){LocalDate.parse(it.getString(0))}.toSet()
    var streak=0
    var cursor=if(date in dates) date else date.minusDays(1)
    while(cursor in dates){streak++;cursor=cursor.minusDays(1)}
    val coverage=db.rows("""SELECT w.level,count(*),
        sum(CASE WHEN c.word IS NULL AND a.grade=4 THEN 1 ELSE 0 END),
        sum(CASE WHEN c.word IS NULL AND a.grade<4 THEN 1 ELSE 0 END),
        sum(CASE WHEN c.state IN ('LEARNING','RELEARNING') THEN 1 ELSE 0 END),
        sum(CASE WHEN c.state='REVIEW' AND (c.stability<30 OR c.due<?) THEN 1 ELSE 0 END),
        sum(CASE WHEN c.state='REVIEW' AND c.stability>=30 AND c.due>=? THEN 1 ELSE 0 END)
        FROM word w LEFT JOIN active_card c ON c.word=w.id LEFT JOIN assessment a ON a.word=w.id GROUP BY w.level ORDER BY w.level""",
        listOf(timestamp.toString(),timestamp.toString())){LevelCoverage(it.getString(0),it.getInt(1),it.getInt(2),it.getInt(3),it.getInt(4),it.getInt(5),it.getInt(6))}
    val week=(6 downTo 0).map { back -> db.long("SELECT count(*) FROM effective_event WHERE day=? AND kind IN ('new','review')",date.minusDays(back.toLong()).toString()).toInt() }
    val newWeek=(6 downTo 0).map { back -> db.long("SELECT count(*) FROM $ratings WHERE day=? AND kind='new'",date.minusDays(back.toLong()).toString()).toInt() }
    val forecast=(0..6).map { ahead ->
        val start=date.plusDays(ahead.toLong()).atStartOfDay(zone).toInstant().toEpochMilli()
        val end=date.plusDays(ahead+1L).atStartOfDay(zone).toInstant().toEpochMilli()
        db.long("SELECT count(*) FROM active_card WHERE due>=? AND due<?",if(ahead==0) "0" else start.toString(),end.toString()).toInt()
    }
    val success=db.rows("SELECT avg(CASE WHEN score>=2 THEN 1.0 ELSE 0.0 END)*100 FROM $ratings WHERE kind='review'"){if(it.isNull(0)) null else it.getDouble(0).roundToInt()}.first()
    val weak=db.rows("SELECT topic,count(*),sum(score) FROM effective_event e JOIN word w ON w.id=e.word WHERE kind='answer' GROUP BY topic ORDER BY avg(score),count(*) DESC LIMIT 8"){
        it.getString(0)+" · "+it.getInt(2)+"/"+it.getInt(1)+" correct"
    }
    val missed=db.rows("SELECT lemma,detail,count(*) FROM effective_event e JOIN word w ON w.id=e.word WHERE kind='answer' AND score=0 GROUP BY word,detail ORDER BY count(*) DESC,max(at) DESC LIMIT 8"){
        it.getString(0)+" · "+it.getString(1)+" ×"+it.getInt(2)
    }
    return Stats(coverage.sumOf {it.total},db.long("SELECT count(*) FROM card").toInt(),learned,inLearning,db.long("SELECT count(*) FROM active_card WHERE due<=?",timestamp.toString()).toInt(),
        today("new"),today("review"),db.long("SELECT coalesce(sum(CASE WHEN kind='new' THEN 10 WHEN kind='review' AND score>=2 THEN 5 ELSE 0 END),0) FROM $ratings").toInt(),
        streak,(db.long("SELECT sum(score) FROM effective_event WHERE kind='time'")/60000).toInt(),if(n==0)null else (100*r/n).roundToInt(),success,
        if(n>0)s/n else 0.0,if(n>0)d/n else 0.0,week,forecast,coverage,weak,missed,
        db.long("SELECT count(*) FROM effective_event WHERE kind='answer' AND score=0").toInt(),
        db.long("SELECT count(DISTINCT a.word) FROM effective_event a WHERE a.kind='answer' AND a.score=0 AND EXISTS(SELECT 1 FROM effective_event b WHERE b.word=a.word AND b.kind='answer' AND b.score=1 AND b.at>a.at)").toInt(), newWeek)
}
