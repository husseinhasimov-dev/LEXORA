package org.lexora.study

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import java.time.*
import java.util.UUID
import kotlin.random.Random

class StudyRepository(context:Context,val owner:String?=Cloud.uid(),val now:()->Long = System::currentTimeMillis) : AutoCloseable {
    private val helper=StudyDatabase.open(context,owner)
    val db:SQLiteDatabase get()=helper.writableDatabase
    val defaults=mapOf("goal" to "10","reviewLimit" to "100","size" to "8","level" to "A1",
        "retention" to "90","speed" to "1.0","hints" to "true","writing" to "true","context" to "true",
        "weeklyReviewGoal" to "30","notificationsMaster" to "true","direction" to "Balanced", "duration" to "0","welcome" to "true","hour" to "19","minute" to "0","days" to "Every day")
    fun pref(key:String)=db.str("SELECT v FROM pref WHERE k=?",key) ?: defaults[key].orEmpty()
    fun set(key:String,value:String)=db.execSQL("INSERT OR REPLACE INTO pref VALUES(?,?)",arrayOf(key,value))
    fun day(t:Long=now())=Instant.ofEpochMilli(t).atZone(ZoneId.systemDefault()).toLocalDate().toString()
    fun session()=pref("session").takeIf(String::isNotEmpty)?.let(Session::decode)
    fun save(s:Session?)=set("session",s?.encode().orEmpty())
    fun word(id:String)=db.rows("SELECT * FROM word WHERE id=?",listOf(id)) { it.word() }.single()
    fun favorite(id:String)=db.long("SELECT COUNT(*) FROM favorite WHERE word=?",id)>0
    fun toggle(id:String) { if(favorite(id)) db.execSQL("DELETE FROM favorite WHERE word=?",arrayOf(id))
        else db.execSQL("INSERT INTO favorite VALUES(?)",arrayOf(id)) }
    fun decks()=db.rows("SELECT DISTINCT name FROM deck ORDER BY name"){it.getString(0)}
    fun topics()=db.rows("SELECT DISTINCT topic FROM word ORDER BY topic"){it.getString(0)}
    fun addDeck(name:String,id:String) {
        require(name.isNotBlank() && name.trim().length<=64)
        db.execSQL("INSERT OR IGNORE INTO deck VALUES(?,?)",arrayOf(name.trim(),id))
    }
    fun words(f:Filters,limit:Int=40):List<Word> {
        val filters=mutableListOf<String>()
        val args=mutableListOf<String>()
        fun add(sql:String,vararg arg:String){ filters.add(sql);args.addAll(arg) }
        if(f.query.isNotBlank()) {
            val q="%"+f.query.trim().replace("\\","\\\\").replace("%","\\%").replace("_","\\_")+"%"
            add("(w.lemma LIKE ? ESCAPE '\\' OR w.meaning LIKE ? ESCAPE '\\')",q,q)
        }
        if(f.level!="All") add("w.level=?",f.level)
        if(f.pos!="All") add("w.pos=?",f.pos)
        if(f.topic!="All") add("w.topic=?",f.topic)
        if(f.deck!="All") add("EXISTS(SELECT 1 FROM deck WHERE word=w.id AND name=?)",f.deck)
        when(f.frequency) {
            "Top 1,000" -> add("w.rank BETWEEN 1 AND 1000")
            "Top 5,000" -> add("w.rank BETWEEN 1 AND 5000")
        }
        when(f.status) {
            "Favorites" -> add("EXISTS(SELECT 1 FROM favorite WHERE word=w.id)")
            "Unseen" -> add("c.word IS NULL AND NOT EXISTS(SELECT 1 FROM assessment WHERE word=w.id)")
            "Learning" -> add("c.state IN ('LEARNING','RELEARNING')")
            "Reviewing" -> add("c.state='REVIEW' AND (c.stability<30 OR c.due<?)",now().toString())
            "Mastered" -> add("c.state='REVIEW' AND c.stability>=30 AND c.due>=?",now().toString())
            "Missed" -> add("EXISTS(SELECT 1 FROM effective_event WHERE word=w.id AND (kind='answer' AND score=0 OR kind IN ('new','review') AND score=1))")
            "Known" -> add("c.word IS NULL AND EXISTS(SELECT 1 FROM assessment WHERE word=w.id AND grade=4)")
            "Selected" -> add("c.word IS NULL AND EXISTS(SELECT 1 FROM assessment WHERE word=w.id AND grade<4)")
        }
        val where=if(filters.isEmpty()) "" else " WHERE "+filters.joinToString(" AND ")
        val order=when(f.sort){
            "Alphabetical" -> "w.lemma COLLATE NOCASE,w.id"
            "Lowest usage" -> "(w.rank<=0),w.rank DESC,w.id"
            else -> "(w.rank<=0),w.rank,w.id"
        }
        return db.rows("SELECT w.* FROM word w LEFT JOIN active_card c ON c.word=w.id"+where+" ORDER BY "+order+" LIMIT ? OFFSET ?",args+listOf(limit.toString(),(f.page*40).toString())){it.word()}
    }
    fun event(id:String,kind:String,score:Int,detail:String="",at:Long=now()) {
        db.execSQL("INSERT INTO event VALUES(?,?,?,?,?,?,?,?)",arrayOf(UUID.randomUUID().toString(),id,kind,score,detail,at,day(at),pref("retention").toInt()))
    }
    fun today(kind:String)=db.long("SELECT count(*) FROM $ratings WHERE kind=? AND day=?",kind,day()).toInt()
    private fun placementWord(band:Int,used:List<String>):String? {
        val marks=used.joinToString(","){"?"}
        return db.str("SELECT id FROM word WHERE level=? AND rank>0 AND pos IN ('noun','verb','adj') AND id NOT IN ($marks) ORDER BY RANDOM() LIMIT 1",levels[band],*used.toTypedArray())
    }
    fun start(mode:Mode,custom:Filters=Filters()):String?=db.tx {
        if(session()!=null) return@tx "Resume or finish the saved session first."
        val size=pref("size").toInt()
        val level=pref("level")
        fun ids(sql:String,vararg args:String)=db.rows(sql,args.toList()){it.getString(0)}
        val band=levels.indexOf(level).coerceAtLeast(0)
        val queue=when(mode) {
            Mode.New -> return@tx startDiscovery(if(custom.level=="All")level else custom.level,custom.topic,pref("goal").toInt())
            Mode.Review -> ids("SELECT word FROM active_card WHERE due<=? ORDER BY due,word LIMIT ?",now().toString(),minOf(size,(pref("reviewLimit").toInt()-today("review")).coerceAtLeast(0)).toString())
            Mode.Lab -> {
                val repair=custom.status
                val clause=when(repair) {
                    "Article","Verb form","Translation confusion","Capitalization","Umlaut or ß" -> " AND detail=?"
                    "Spelling" -> " AND detail IN ('Spelling','Minor spelling error')"
                    "Difficult reviews" -> " AND kind IN ('new','review')"
                    else -> ""
                }
                val order=if(repair=="Recent") "max(at) DESC,count(*) DESC" else "count(*) DESC,max(at) DESC"
                val params=if(clause.endsWith("=?"))listOf(repair,size.toString()) else listOf(size.toString())
                ids("SELECT word FROM effective_event WHERE (kind='answer' AND score=0 OR kind IN ('new','review') AND score<=2)"+clause+" GROUP BY word ORDER BY "+order+" LIMIT ?",*params.toTypedArray())
            }
            Mode.Quick -> ids("SELECT id FROM word WHERE level=? ORDER BY RANDOM() LIMIT ?",level,minOf(5,size).toString())
            Mode.Custom -> words(custom,size).map { it.id }
            Mode.Placement -> listOfNotNull(placementWord(band,emptyList()))
        }
        if(queue.isEmpty()) return@tx when(mode) {
            Mode.New -> "No new words available within today’s goal."
            Mode.Review -> "You’re caught up or have reached today’s review limit."
            Mode.Lab -> "Mistake Lab will open when you have a missed word."
            else -> "No words match this session."
        }
        db.delete("undo_step",null,null)
        save(Session(mode,queue,firstStage(mode,queue.first()),band=band,limit=if(mode==Mode.Placement) 8 else queue.size,
            duration=if(mode==Mode.Placement) 0 else pref("duration").toInt()))
        null
    }
    private fun recallStage(id:String)=if(grade(id)==3)Stage.Recall else Stage.Recognize
    private fun firstStage(mode:Mode,wordId:String)=when(mode){
        Mode.New -> Stage.Discover
        Mode.Review -> {
            val successes=db.long("SELECT count(*) FROM effective_event WHERE word=? AND kind='review' AND score>=3",wordId)
            when {
                successes>=2 && pref("context")=="true" && successes%2==1L && word(wordId).cloze.isNotEmpty()->Stage.Context
                successes>=2 && pref("writing")=="true"->Stage.Write
                else->Stage.Recall
            }
        }
        Mode.Lab -> when(db.str("SELECT detail FROM effective_event WHERE word=? AND kind='answer' AND score=0 ORDER BY at DESC,id DESC LIMIT 1",wordId)){
            "Translation confusion" -> Stage.Recognize
            "Verb form" -> if(word(wordId).cloze.isNotBlank())Stage.Context else Stage.Write
            else -> Stage.Write
        }
        else -> Stage.Recognize
    }
    fun choices(s:Session,w:Word):List<String> {
        val rng=Random(w.id.hashCode()+s.index)
        val nearby=db.rows("SELECT meaning FROM word WHERE pos=? AND id<>? AND length(meaning)<90 ORDER BY abs(rank-?),id LIMIT 120",
            listOf(w.pos,w.id,w.rank.toString())){it.getString(0)}.distinct().shuffled(rng)
        val stop=setOf("to","the","of","a","an","and","or","in","with")
        fun tokens(text:String)=text.lowercase().split(Regex("[^a-z]+")).filter { it.isNotBlank() && it !in stop }.toSet()
        val used=tokens(w.meaning).toMutableSet()
        val options=mutableListOf(w.meaning)
        for(candidate in nearby) {
            val t=tokens(candidate)
            if(candidate!=w.meaning && t.isNotEmpty() && t.intersect(used).isEmpty()) {
                options.add(candidate);used.addAll(t)
                if(options.size==4) break
            }
        }
        return options.shuffled(rng)
    }
    // A stale screen cannot apply the same answer to a second stage or card.
    fun act(expected:Session,action:String,input:String=""):String?=db.tx {
        val current=session() ?: return@tx null
        if(current.copy(activeMs=expected.activeMs)!=expected) return@tx null
        if(current.v3)return@tx v3Action(current,action,input)
        return@tx tracked(current.current){applyAction(current,action,input)}
    }
    private fun applyAction(s:Session,action:String,input:String):String? {
        val w=word(s.current)
        when(action) {
            "finishDiscovery" -> if(s.stage==Stage.Assess){save(null);return selectionReady}
            "assess" -> if(s.stage==Stage.Assess)return assessNext(s,input.toInt())
            "teach" -> if(s.stage==Stage.Teach){
                if(s.index+1<s.ids.size)save(s.copy(index=s.index+1))
                else save(s.copy(index=0,stage=recallStage(s.ids.first())))
            }
            "reveal" -> if(s.stage==Stage.Recall) save(s.copy(reveal=true))
            "known","later" -> if(s.stage==Stage.Discover) {event(w.id,action,0);return advance(s)}
            "discover" -> if(s.stage==Stage.Discover) save(s.copy(stage=Stage.Recognize))
            "answer" -> if(s.feedback.isEmpty() && s.stage in listOf(Stage.Recognize,Stage.Write,Stage.Context)) {
                val result=if(s.stage==Stage.Recognize) {
                    if(input==w.meaning) "Correct" else "Translation confusion"
                } else Answers.check(input,if(s.stage==Stage.Write) w.german else w.answer,w,pref("hints")=="true")
                val ok=result=="Correct"
                if(!ok){
                    val field=if(s.stage==Stage.Recognize)"meaning" else "lemma"
                    val entered=if(s.stage==Stage.Recognize)input else input.trim().removePrefix("der ").removePrefix("die ").removePrefix("das ")
                    val match=db.rows("SELECT id FROM word WHERE "+field+"=? AND id<>?",listOf(entered,w.id)){it.getString(0)}.singleOrNull()
                    if(match!=null)recordExplore("confusion",pairKey(w.id,match),s.stage.name,w.id,match,input,false)
                    else if(s.stage==Stage.Write)recordExplore("spelling",w.id,"Written recall",w.id,null,input,false)
                }
                event(w.id,"answer",if(ok) 1 else 0,if(ok) s.stage.name else result)
                save(s.copy(feedback=result,passed=ok))
            }
            "rate" -> if(s.stage==Stage.Recall && s.reveal) {
                if(s.mode==Mode.New && s.batch.isNotEmpty() &&
                    (grade(w.id)==4 || db.long("SELECT count(*) FROM active_card WHERE word=?",w.id)>0))
                    return advance(s)
                if(s.mode==Mode.Review && db.long("SELECT count(*) FROM active_card WHERE word=? AND due<=?",w.id,now().toString())==0L)
                    return advance(s)
                val rating=input.toInt()
                require(rating in 1..4)
                val rated=s.copy(rating=rating,reveal=false)
                if(s.mode==Mode.Review || s.mode==Mode.New && s.batch.isNotEmpty()) {schedule(rated);return advance(rated)}
                if(pref("writing")=="true") save(rated.copy(stage=Stage.Write))
                else if(w.cloze.isNotBlank() && pref("context")=="true") save(rated.copy(stage=Stage.Context))
                else {schedule(rated);return advance(rated)}
            }
            "next" -> if(s.feedback.isNotEmpty()) {
                val clean=s.copy(feedback="",passed=false)
                when {
                    s.mode==Mode.Placement -> return advance(s.copy(correct=s.correct+if(s.passed) 1 else 0,band=(s.band+if(s.passed) 1 else -1).coerceIn(0,3)))
                    s.mode==Mode.Review -> save(clean.copy(stage=Stage.Recall,reveal=true))
                    s.mode!=Mode.New -> return advance(s)
                    !s.passed -> save(clean)
                    s.stage==Stage.Recognize -> save(clean.copy(stage=Stage.Recall))
                    s.stage==Stage.Write && w.cloze.isNotBlank() && pref("context")=="true" -> save(clean.copy(stage=Stage.Context))
                    else -> {schedule(s);return advance(s)}
                }
            }
        }
        return null
    }
    internal fun schedule(s:Session) {
        val old=db.rows("SELECT * FROM card WHERE word=?",listOf(s.current)){it.card()}.firstOrNull()
        val time=maxOf(now(),db.long("SELECT at FROM assessment WHERE word=?",s.current)+1,(old?.lastReview?.toEpochMilli()?:0L)+1)
        val kind=if(old==null) "new" else "review"
        val fitted=pref("initialStability")
        val calibration=if(old==null && fitted.isNotEmpty()) "initial="+fitted.split(",")[s.rating-1] else ""
        event(s.current,kind,s.rating,if(kind=="review" && s.mode==Mode.Review)"scheduled" else calibration,time)
        db.saveCard(s.current,Memory.review(s.current,old,s.rating,time,pref("retention").toInt(),calibration))
    }
    private fun advance(s:Session):String? {
        val next=s.index+1
        val timed=s.duration>0 && s.activeMs>=s.duration*60000L
        if(next>=s.limit || timed) {
            save(null)
            if(s.mode==Mode.Placement) {
                val results=db.rows("SELECT w.level,e.score FROM effective_event e JOIN word w ON w.id=e.word WHERE e.kind='answer' AND e.word IN ("+s.ids.joinToString(","){"?"}+") ORDER BY e.at DESC LIMIT ?",s.ids+next.toString()){it.getString(0) to it.getInt(1)}
                val suggested=levels.lastOrNull { band -> results.count { it.first==band && it.second==1 }>=2 } ?: "A1"
                set("level",suggested)
                return "Assessment complete · ${s.correct} of $next correct. Suggested starting vocabulary: $suggested. Change it in Profile."
            }
            return "Session complete · $next " + if(next==1) "word" else "words"
        }
        val queue=if(s.mode==Mode.Placement) s.ids+listOfNotNull(placementWord(s.band,s.ids)) else s.ids
        if(next>=queue.size) {save(null);return "Session complete"}
        save(s.copy(ids=queue,index=next,stage=if(s.batch.isNotEmpty() && s.mode==Mode.New)recallStage(queue[next]) else firstStage(s.mode,queue[next]),feedback="",rating=0,passed=false,reveal=false))
        return null
    }
    fun time(id:String,ms:Long) = db.tx {
        if(ms>0) {event(id,"time",ms.coerceAtMost(Int.MAX_VALUE.toLong()).toInt())
            session()?.let { save(it.copy(activeMs=it.activeMs+ms)) }}
    }
    fun reset()=db.tx {
        db.execSQL("DELETE FROM pref WHERE k LIKE 'revisit.%' OR k='explore.session'")
        listOf("exploration","undo_step","card","event").forEach { db.delete(it,null,null) }
        save(null)
    }
    override fun close()=helper.close()
}
