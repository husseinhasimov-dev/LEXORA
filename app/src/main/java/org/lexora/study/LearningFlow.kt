package org.lexora.study

import android.database.sqlite.SQLiteDatabase
import org.json.JSONArray
import java.util.UUID

const val selectionReady="Discovery finished. Review your selected words before learning."
val knowledgeLabels=listOf("Don’t know","Know a little","Know moderately","Already know")

fun createLearningSchema(db:SQLiteDatabase) {
    db.execSQL("CREATE INDEX event_correction ON event(kind,detail,word,at,id)")
    db.execSQL("CREATE TABLE undo_step(seq INTEGER PRIMARY KEY AUTOINCREMENT, before_session TEXT NOT NULL, word TEXT NOT NULL, event_ids TEXT NOT NULL, old_level TEXT NOT NULL)")
    db.execSQL("""CREATE VIEW live_event AS SELECT e.* FROM event e WHERE kind!='void'
        AND NOT EXISTS(SELECT 1 FROM event v WHERE v.kind='void' AND v.detail=e.id AND v.word=e.word)""")
    db.execSQL("""CREATE VIEW effective_event AS SELECT e.id,e.word,e.kind,
        CASE WHEN e.kind IN ('new','review') THEN coalesce((SELECT a.score FROM live_event a
        WHERE a.kind='adjust' AND a.detail=e.id AND a.word=e.word ORDER BY a.at DESC,a.id DESC LIMIT 1),e.score)
        ELSE e.score END AS score,e.detail,e.at,e.day,e.retention FROM live_event e WHERE e.kind!='adjust'""")
    db.execSQL("""CREATE VIEW assessment AS SELECT e.word,e.at,CASE WHEN e.kind='known' THEN 4 ELSE e.score END grade
        FROM live_event e WHERE e.kind IN ('known','assess') AND e.id=(SELECT a.id FROM live_event a
        WHERE a.word=e.word AND a.kind IN ('known','assess') ORDER BY a.at DESC,a.id DESC LIMIT 1)""")
    db.execSQL("""CREATE VIEW active_card AS SELECT c.* FROM card c WHERE NOT EXISTS(
        SELECT 1 FROM assessment a WHERE a.word=c.word AND a.at>c.last)""")

}

data class LevelCoverage(val level:String,val total:Int,val known:Int,val selected:Int,val learning:Int,val reviewing:Int,val mastered:Int){
    val remaining get()=total-known-mastered
    val unseen get()=remaining-selected-learning-reviewing
}
data class Selection(val id:String,val german:String,val grade:Int,val level:String,val topic:String,val academic:String=""){ fun matches(l:String,t:String)= (l=="All" || l==level || l=="Academic" && academic.isNotEmpty()) && (t=="All" || t==if(l=="Academic")academic else topic) }
fun StudyRepository.grade(id:String)=db.long("SELECT grade FROM assessment WHERE word=?",id).toInt()
fun StudyRepository.selections(level:String="All",topic:String="All")=db.rows("""
    SELECT w.id,w.article,w.lemma,CASE WHEN v.k IS NULL THEN a.grade ELSE 3 END AS priority,w.level,w.topic,coalesce(x.category,'')
    FROM word w LEFT JOIN assessment a ON a.word=w.id LEFT JOIN content_v4 x ON x.word=w.id LEFT JOIN active_card c ON c.word=w.id
    LEFT JOIN pref v ON v.k='revisit.'||w.id
    WHERE ((a.grade<4 AND c.word IS NULL) OR v.k IS NOT NULL)
    AND (?='All' OR w.level=? OR ?='Academic' AND x.category!='') AND (?='All' OR CASE WHEN ?='Academic' THEN x.category ELSE w.topic END=?) ORDER BY priority,w.rank,w.id
""",listOf(level,level,level,topic,level,topic)){Selection(it.getString(0),(it.getString(1)+" "+it.getString(2)).trim(),it.getInt(3),it.getString(4),it.getString(5),it.getString(6))}

internal fun StudyRepository.candidate(level:String,topic:String)=db.str("""SELECT w.id FROM word w
    LEFT JOIN assessment a ON a.word=w.id LEFT JOIN card c ON c.word=w.id
    LEFT JOIN content_v4 x ON x.word=w.id WHERE (w.level=? OR ?='Academic' AND x.category!='') AND (?='All' OR CASE WHEN ?='Academic' THEN x.category ELSE w.topic END=?) AND a.word IS NULL AND c.word IS NULL
    AND NOT EXISTS(SELECT 1 FROM live_event e WHERE e.word=w.id AND e.kind='later' AND e.day=?)
    ORDER BY CASE WHEN rank<=0 THEN 1000000 ELSE (rank-1)/250 END,RANDOM() LIMIT 1""",level,level,topic,level,topic,day())

fun StudyRepository.startDiscovery(level:String,topic:String,target:Int):String?=db.tx {
    require((level in levels || level=="Academic") && target in 1..500)
    if(session()!=null)return@tx "Resume or finish your current session first."
    setDailyGoal(target);set("discoveryLevel",level);if(level in levels)set("level",level);set("discoveryTopic",topic)
    if(learnedToday()+selections(level,topic).size>=target)return@tx "Your daily target is filled. Choose Learn selected words to study your list."
    val first=candidate(level,topic) ?: return@tx "You’ve assessed all available words in this selection."
    db.delete("undo_step",null,null)
    save(Session(Mode.New,listOf(first),Stage.Assess,limit=target,batch=UUID.randomUUID().toString(),level=level,topic=topic,v3=true))
    null
}

fun StudyRepository.startSelected(level:String="All",topic:String="All"):String?=db.tx {
    if(session()!=null)return@tx "Resume or finish your current session first."
    val ids=studySelection(level,topic)
    if(ids.isEmpty())return@tx "No words are selected in this group."
    db.delete("undo_step",null,null)
    save(Session(Mode.New,ids,Stage.StudyAll,v3=true,limit=ids.size,batch=UUID.randomUUID().toString(),level=level,topic=topic,duration=pref("duration").toInt()))
    null
}

fun StudyRepository.recordAssessment(id:String,value:Int){
    require(value in 1..4)
    val at=maxOf(now(),db.long("SELECT last FROM card WHERE word=?",id)+1,db.long("SELECT max(at) FROM event WHERE word=? AND kind IN ('known','assess')",id)+1)
    event(id,"assess",value,at=at)
}
fun StudyRepository.assessNext(s:Session,value:Int):String?{
    // A second device may have classified this candidate while discovery was paused.
    if(grade(s.current)==0 && db.long("SELECT count(*) FROM card WHERE word=?",s.current)==0L)
        recordAssessment(s.current,value)
    val next=if(today("new")+selections(s.level,s.topic).size<s.limit)candidate(s.level,s.topic) else null
    save(next?.let{s.copy(ids=listOf(it),inspected=s.inspected+1)})
    return if(next==null)selectionReady else null
}

fun StudyRepository.replayCard(w:String){
    var card:io.github.openspacedrepetition.Card?=null
    db.rawQuery("SELECT e.score,e.at,e.retention,CASE WHEN e.score=o.score THEN e.detail ELSE '' END FROM effective_event e JOIN event o ON o.id=e.id WHERE e.word=? AND e.kind IN ('new','review') ORDER BY e.at,e.id",arrayOf(w)).use{c->
        while(c.moveToNext())card=Memory.review(w,card,c.getInt(0),c.getLong(1),c.getInt(2),c.getString(3))
    }
    card?.let{db.saveCard(w,it)} ?: db.delete("card","word=?",arrayOf(w))
}

// Caller owns the transaction. Only events written by this action are reversible.
fun StudyRepository.tracked(word:String,op:()->String?):String?{
    val before=pref("session");val prevLevel=pref("level")
    val watermark=db.long("SELECT max(rowid) FROM event")
    val result=op()
    val ids=db.rows("SELECT id FROM event WHERE rowid>?",listOf(watermark.toString())){it.getString(0)}
    if(before!=pref("session") || ids.isNotEmpty())
        db.execSQL("INSERT INTO undo_step(before_session,word,event_ids,old_level) VALUES(?,?,?,?)",
            arrayOf(before,word,JSONArray(ids).toString(),if(prevLevel!=pref("level"))prevLevel else ""))
    return result
}
fun StudyRepository.latestStep()=db.long("SELECT max(seq) FROM undo_step")
fun StudyRepository.undo(expected:Long,previousCard:Boolean=false):String?=db.tx {
    if(expected==0L || latestStep()!=expected)return@tx null
    val current=session()?.current
    val target=if(previousCard && current!=null)db.long("SELECT max(seq) FROM undo_step WHERE word<>? AND before_session<>''",current) else expected
    if(target==0L)return@tx "This is the first card."
    val affected=mutableSetOf<String>()
    db.rawQuery("SELECT seq,before_session,word,event_ids,old_level FROM undo_step WHERE seq>=? ORDER BY seq DESC",arrayOf(target.toString())).use{c->
        while(c.moveToNext()){
            val ids=JSONArray(c.getString(3))
            for(i in 0 until ids.length()){
                val id=ids.getString(i);val w=c.getString(2)
                event(w,"void",0,id)
                if(db.str("SELECT kind FROM event WHERE id=?",id) in listOf("new","review","adjust"))affected.add(w)
            }
            set("session",c.getString(1))
            if(c.getString(4).isNotEmpty())set("level",c.getString(4))
        }
    }
    db.delete("undo_step","seq>=?",arrayOf(target.toString()))
    affected.forEach(::replayCard)
    null
}
fun StudyRepository.reclassify(id:String,value:Int):String?=db.tx {
    if(value==4)db.delete("pref","k=?",arrayOf("revisit."+id))
    if(grade(id)==value && db.long("SELECT count(*) FROM active_card WHERE word=?",id)==0L)return@tx null
    tracked(id){
        val s=session()
        if(s?.v3==true){
            if(grade(id)!=value || db.long("SELECT count(*) FROM active_card WHERE word=?",id)>0) recordAssessment(id,value)
            if(s.stage==Stage.StudyAll) finishV3Discovery(s, s.trail)
            if(s.stage==Stage.Practice && s.mode==Mode.New && value==4 && id in s.ids){
                val remaining=s.plan.drop(s.step).filter { it.word!=id }
                val ids=s.ids-id
                if(remaining.isEmpty() || ids.isEmpty())save(null)
                else {
                    val same=remaining.first()==s.plan[s.step]
                    save(s.copy(ids=ids,index=0,limit=ids.size,plan=s.plan.take(s.step)+remaining,
                        reveal=same && s.reveal,feedback=if(same)s.feedback else "",passed=same && s.passed))
                }
            }
            return@tracked null
        }
        if(s?.stage==Stage.Assess && s.current==id)return@tracked assessNext(s,value)
        recordAssessment(id,value)
        if(s?.stage==Stage.Assess && selections(s.level,s.topic).size+today("new")>=s.limit){
            save(null)
            return@tracked selectionReady
        }
        if(s!=null && (s.mode==Mode.Review || s.mode==Mode.New && s.stage!=Stage.Assess && value==4) && id in s.ids){
            val ids=s.ids.filter {it!=id}
            val index=s.index-if(s.ids.indexOf(id)<s.index)1 else 0
            save(if(index>=ids.size)null else if(id!=s.current)s.copy(ids=ids,index=index,limit=ids.size)
                else s.copy(ids=ids,index=index,limit=ids.size,stage=if(s.mode==Mode.Review)Stage.Recall else Stage.Teach,
                    feedback="",reveal=false,passed=false,rating=0))
        }
        null
    }
}
fun StudyRepository.lastRating(id:String)=db.rows("SELECT id,score FROM effective_event WHERE word=? AND kind IN ('new','review') ORDER BY at DESC,id DESC LIMIT 1",listOf(id)){it.getString(0) to it.getInt(1)}.firstOrNull()
fun StudyRepository.correctRating(word:String,target:String,score:Int):String?=db.tx {
    require(score in 1..4)
    val last=lastRating(word)?:return@tx null
    if(last.first!=target || last.second==score)return@tx null
    tracked(word){
        val at=maxOf(now(),db.long("SELECT max(at) FROM event WHERE kind='adjust' AND detail=?",target)+1)
        event(word,"adjust",score,target,at)
        replayCard(word)
        null
    }
}
