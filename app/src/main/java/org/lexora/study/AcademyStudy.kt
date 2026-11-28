package org.lexora.study

import org.json.JSONObject
import java.util.UUID
import java.util.Locale
import java.time.LocalDate

internal fun StudyRepository.collect(surface:String,id:String?,source:String,sentence:String,topic:String):String {
    val name=surface.trim()
    require(name.isNotEmpty() && name.length<=200)
    val key=id?:"surface:"+java.text.Normalizer.normalize(name,java.text.Normalizer.Form.NFC)
    db.execSQL("""INSERT OR IGNORE INTO academy_inbox(id,word,surface,passage,sentence,topic,at) VALUES(?,?,?,?,?,?,?)""",
        arrayOf(key,id,name,source,sentence,topic,now()))
    db.execSQL("UPDATE academy_inbox SET archived=0 WHERE id=?",arrayOf(key))
    return key
}
internal fun StudyRepository.resolveInbox(entry:InboxWord,wordId:String)=db.tx{
    require(entry.id==null && db.long("SELECT count(*) FROM word WHERE id=?",wordId)==1L)
    val key=collect(entry.name,wordId,entry.source,entry.sentence,entry.topic)
    db.execSQL("UPDATE academy_inbox SET priority=max(priority,?),at=min(at,?),label=CASE WHEN label='' THEN ? ELSE label END WHERE id=?",arrayOf(entry.priority,entry.at,entry.label,key))
    db.delete("academy_inbox","id=? AND word IS NULL",arrayOf(entry.key))
}
internal data class InboxWord(val key:String,val id:String?,val name:String,val source:String,val sentence:String,val topic:String,val at:Long,
    val priority:Int,val label:String,val archived:Boolean,val status:String,val level:String)
internal fun StudyRepository.inbox()=db.rows("""SELECT i.*,a.grade,c.state,w.level FROM academy_inbox i LEFT JOIN assessment a ON a.word=i.word
    LEFT JOIN active_card c ON c.word=i.word LEFT JOIN word w ON w.id=i.word ORDER BY priority DESC,i.at DESC,i.id"""){c->
    InboxWord(c.getString(0),c.getString(1),c.getString(2),c.getString(3),c.getString(4),c.getString(5),c.getLong(6),c.getInt(7),c.getString(8),c.getInt(9)!=0,
        when{c.getInt(9)!=0->"Archived";c.getString(11)=="REVIEW"->"FSRS review";!c.isNull(11)->"Learning";c.getInt(10)==4->"Known";c.getInt(10)>0->"Selected";else->"Collected"},c.getString(12).orEmpty())
}
internal fun StudyRepository.enqueue(ids:Collection<String>,start:Boolean=false):String?=db.tx {
    if(start && session()!=null)return@tx "Resume or finish your current vocabulary session first."
    val eligible=ids.distinct().filter{db.long("SELECT count(*) FROM word WHERE id=?",it)==1L &&
        db.long("SELECT count(*) FROM active_card WHERE word=?",it)==0L}
    if(eligible.isEmpty())return@tx "These entries need a dictionary match or already have an active review card."
    for(id in eligible)if(grade(id) !in 1..3)recordAssessment(id,1)
    if(start){
        db.delete("undo_step",null,null)
        save(Session(Mode.New,eligible,Stage.StudyAll,v3=true,limit=eligible.size,batch=UUID.randomUUID().toString(),duration=pref("duration").toInt()))
    }
    null
}
internal fun StudyRepository.readerPosition(id:String,paragraph:Int,bookmark:Boolean=false) = db.tx {
    require(paragraph>=0)
    db.execSQL("INSERT OR IGNORE INTO academy_reader(passage,touched) VALUES(?,?)",arrayOf(id,now()))
    db.execSQL("UPDATE academy_reader SET paragraph=?,touched=? WHERE passage=?",arrayOf(paragraph,now(),id))
    if(bookmark)db.execSQL("UPDATE academy_reader SET bookmark=? WHERE passage=?",arrayOf(paragraph,id))
}
internal data class Round(val id:String,val step:Int,val started:Long,val result:String?,val finished:Boolean)
internal fun StudyRepository.round(key:String):Round?=pref("academy.round."+key).takeIf{it.isNotBlank()}?.let{v->
    val j=JSONObject(v);Round(j.getString("id"),j.getInt("step"),j.getLong("started"),if(j.has("result"))j.getString("result") else null,j.optBoolean("finished"))
}
internal fun StudyRepository.beginRound(key:String) {
    if(round(key)?.finished==false)return
    if(key.startsWith("Grammar/") && key.endsWith("/Delayed review")){
        val assessed=topicProgress(key.substringAfter('/').substringBefore('/')).lastAssessment
        require(assessed>0 && now()-assessed>=MasteryRules().delayMs){"Delayed review opens one day after the assessment."}
    }
    set("academy.round."+key,JSONObject().put("id",UUID.randomUUID().toString()).put("step",0).put("started",now()).toString())
}
internal data class AcademyActivity(val skill:String,val mode:String,val item:String,val level:String,val questions:List<AcademyQuestion>,val base:Int,val version:Int=1){
    val key get()=skill+"/"+item+"/"+mode
}
internal fun Reading.activity()=AcademyActivity("Reading","Comprehension",id,level,questions,when{tokens.size>=700->70;tokens.size>=350->45;else->25},version)
internal fun Lesson.activity(mode:String):AcademyActivity{
    val questions=when(mode){"Guided"->listOf(tasks[0]);"Practice"->listOf(tasks[1]);"Assessment"->listOf(tasks[2],tasks[1]);"Delayed review"->listOf(tasks[3]);else->error("Unknown exercise")}
    return AcademyActivity("Grammar",mode,id,level,questions,when(mode){"Assessment"->30;"Delayed review"->50;"Practice"->12;else->5})
}
internal fun StudyRepository.answerAcademy(a:AcademyActivity,expected:Round,value:String,activeMs:Long?)=db.tx{
    val r=round(a.key)?:return@tx
    if(r!=expected || r.finished || r.result!=null || value.isBlank())return@tx
    val q=a.questions[r.step];val correct=q.accepts(value)
    val e=Evidence(r.id+"/"+r.step,a.skill,a.mode+" answer",a.item,a.level,1,if(correct)1 else 0,value,
        grammar=a.item.takeIf{a.skill=="Grammar"},kind=if(a.mode=="Assessment")"topic assessment" else "practice",version=a.version,activeMs=activeMs?.coerceIn(0,30_000),
        questionId=a.item+"/v"+a.version+"/"+UUID.nameUUIDFromBytes(q.text.toByteArray(Charsets.UTF_8)))
    record(e)
    val j=JSONObject(pref("academy.round."+a.key)).put("result",value)
    set("academy.round."+a.key,j.toString())
}
internal fun StudyRepository.advanceAcademy(a:AcademyActivity,expected:Round)=db.tx {
    val r=round(a.key)?:return@tx
    if(r!=expected || r.finished || r.result==null)return@tx
    val j=JSONObject(pref("academy.round."+a.key))
    j.remove("result")
    if(r.step+1<a.questions.size)j.put("step",r.step+1)
    else {
        val correct=db.long("SELECT sum(correct) FROM academy_event WHERE id LIKE ?",r.id+"/%").toInt()
        val ratio=correct.toDouble()/a.questions.size
        val baseKey=a.key+"/v"+a.version
        val awardKey=if(a.mode=="Delayed review")baseKey+"/"+day() else baseKey
        val previous=db.long("SELECT count(*) FROM academy_credit WHERE reward_key=?",awardKey)>0
        val last=db.long("SELECT max(at) FROM academy_event WHERE content=? AND activity=?",a.item,a.mode)
        // Distinct delayed retrieval days can earn smaller credit for a familiar task.
        val delayed=previous && last>0 && now()-last>=86_400_000L
        val amount=AcademyXp.points(a.base,a.level,ratio,if(delayed)0.5 else 1.0)
        record(Evidence(r.id,a.skill,a.mode,a.item,a.level,a.questions.size,correct,start=r.started,
            grammar=a.item.takeIf{a.skill=="Grammar"},kind=if(a.mode=="Assessment")"topic assessment" else "practice",version=a.version),
            if(delayed)awardKey+"/retrieval/"+day() else awardKey,amount,"Completed "+a.mode.lowercase(Locale.ENGLISH)+" · "+correct+"/"+a.questions.size+" · "+a.level)
        j.put("finished",true)
    }
    set("academy.round."+a.key,j.toString())
}
internal data class TopicProgress(val status:String,val lesson:Boolean,val practice:Double?,val test:Double?,val delayed:Double?,val lastAssessment:Long,val errors:Int)
internal data class MasteryRules(val practice:Double=0.80,val test:Double=0.85,val delayMs:Long=86_400_000L,val reviewMs:Long=30L*86_400_000L)
internal fun StudyRepository.topicProgress(id:String,rules:MasteryRules=MasteryRules()):TopicProgress {
    val records=db.rows("SELECT activity,at,attempted,correct FROM academy_event WHERE grammar=? ORDER BY at,rowid",listOf(id)){
        listOf(it.getString(0),it.getLong(1).toString(),it.getInt(2).toString(),it.getInt(3).toString())}
    fun accuracy(row:List<String>?)=row?.takeIf{it[2].toInt()>0}?.let{it[3].toDouble()/it[2].toInt()}
    val lesson=records.any{it[0]=="Lesson"}
    val practiceRecords=records.filter{it[0]=="Practice"}.takeLast(5)
    val practice=if(practiceRecords.isEmpty())null else practiceRecords.sumOf{it[3].toInt()}.toDouble()/practiceRecords.sumOf{it[2].toInt()}
    val assessment=records.lastOrNull{it[0]=="Assessment"}
    val at=assessment?.get(1)?.toLong()?:0L
    val delayed=records.lastOrNull{it[0]=="Delayed review" && at>0 && it[1].toLong()-at>=rules.delayMs}
    val test=accuracy(assessment);val recall=accuracy(delayed)
    val status=when{
        records.isEmpty()->"Not Started"
        !lesson->"In Progress"
        practice==null->"In Progress"
        practice<rules.practice || test!=null && test<rules.test->"Practice Needed"
        test==null->"Assessment Ready"
        recall!=null && recall>=rules.test->if(now()-delayed!![1].toLong()>=rules.reviewMs)"Needs Review" else "Mastered"
        recall!=null || now()-at>=rules.delayMs->"Needs Review"
        else->"In Progress"
    }
    return TopicProgress(status,lesson,practice,test,recall,at,records.count{it[0].endsWith(" answer") && it[3]=="0"})
}
internal fun StudyRepository.completeLesson(l:Lesson)=record(Evidence("lesson/"+l.id,"Grammar","Lesson",l.id,l.level,method="self-reported",grammar=l.id))
