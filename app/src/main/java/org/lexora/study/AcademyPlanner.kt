package org.lexora.study

import java.time.LocalDate
import java.time.DayOfWeek
import java.util.UUID
import org.json.JSONArray

internal val academySkills=listOf("Vocabulary","Reading","Grammar","Listening","Speaking","Writing")
internal data class JournalRow(val id:String,val day:String,val at:Long,val skill:String,val activity:String,val content:String,val method:String,
    val attempted:Int?,val correct:Int?,val xp:Int,val reason:String,val activeMs:Long?,val answer:String?)
internal fun StudyRepository.journal(from:String,to:String)=db.rows("""SELECT e.id,e.day,e.at,e.skill,e.activity,e.content,e.method,e.attempted,e.correct,
    coalesce(c.amount,0),c.reason,e.active_ms,e.answer FROM academy_event e LEFT JOIN academy_credit c ON c.event_id=e.id WHERE e.day BETWEEN ? AND ?
    UNION ALL SELECT id,day,at,'Vocabulary',kind,lemma,CASE WHEN kind='First successful retry' THEN 'self-reported recall' ELSE 'FSRS rating' END,
        NULL,NULL,xp,CASE WHEN kind='First successful retry' THEN 'First successful recall after a missed new word' ELSE 'Effective FSRS rating '||score END,NULL,NULL FROM academy_vocab WHERE day BETWEEN ? AND ?
    UNION ALL SELECT x.id,date(x.at/1000,'unixepoch','localtime'),x.at,CASE x.mode WHEN 'Listening in Context' THEN 'Listening' WHEN 'Mini Stories' THEN 'Reading' ELSE 'Vocabulary' END,
        x.mode,x.item,'answer key',1,x.correct,0,NULL,NULL,x.answer FROM exploration x WHERE x.mode IN ('Listening in Context','Mini Stories','Context Mastery','Word Families','Confusion Lab')
        AND x.part!='Completed' AND date(x.at/1000,'unixepoch','localtime') BETWEEN ? AND ? ORDER BY 3 DESC,1 DESC""",listOf(from,to,from,to,from,to)){c->
    JournalRow(c.getString(0),c.getString(1),c.getLong(2),c.getString(3),c.getString(4),c.getString(5),c.getString(6),
        if(c.isNull(7))null else c.getInt(7),if(c.isNull(8))null else c.getInt(8),c.getInt(9),c.getString(10).orEmpty(),
        if(c.isNull(11))null else c.getLong(11),c.getString(12))
}
internal data class Quest(val id:String,val title:String,val skill:String,val metric:String,val target:Int,val date:String,val recurring:Boolean=false,val bonus:Boolean=false)
internal val questMetrics=mapOf("Vocabulary" to listOf("Learn words","Scheduled reviews"),"Reading" to listOf("Comprehension"),"Grammar" to listOf("Practice","Assessment"),
    "Listening" to listOf("Listening in Context"),"Speaking" to emptyList(),"Writing" to emptyList())
internal fun StudyRepository.saveQuest(q:Quest) {
    require(q.title.isNotBlank() && q.target in 1..600 && q.skill in academySkills && (q.metric=="Self-reported" || q.metric in questMetrics.getValue(q.skill)))
    LocalDate.parse(q.date)
    db.execSQL("INSERT OR REPLACE INTO academy_quest VALUES(?,?,?,?,?,?,?,?)",arrayOf(q.id,q.title.trim(),q.skill,q.metric,q.target,q.date,if(q.recurring)1 else 0,if(q.bonus)1 else 0))
}
internal fun StudyRepository.quests(date:LocalDate,progress:Map<String,TopicProgress>):List<Quest>{
    val day=date.toString()
    val custom=db.rows("SELECT * FROM academy_quest WHERE day=? OR recurring=1 AND day<=?",listOf(day,day)){
        Quest(it.getString(0),it.getString(1),it.getString(2),it.getString(3),it.getInt(4),it.getString(5),it.getInt(6)==1,it.getInt(7)==1)}
    val days=(pref("academy.days").ifEmpty{"1,2,3,4,5,6,7"}).split(",")
    val mins=(pref("academy.weekly").toIntOrNull()?:150)/days.size.coerceAtLeast(1)
    fun withEdits(auto:List<Quest>):List<Quest>{
        val overridden=db.rows("SELECT id FROM academy_quest WHERE id LIKE 'auto/%'"){it.getString(0)}.toSet()
        return (auto.filterNot{it.id in overridden}+custom).filterNot{pref("academy.hide."+it.id)=="true"}
    }
    val planKey="academy.plan."+day
    val saved=pref(planKey)
    val auto=mutableListOf<Quest>()
    if(saved.isNotEmpty()){
        val plan=JSONArray(saved)
        for(i in 0 until plan.length()){val q=plan.getJSONArray(i);auto+=Quest(q.getString(0),q.getString(1),q.getString(2),q.getString(3),q.getInt(4),day)}
        return withEdits(auto)
    }
    if(date.dayOfWeek.value.toString() in days) {
        fun add(title:String,skill:String,metric:String,target:Int){
            auto+=Quest("auto/"+day+"/"+metric,title,skill,metric,target,day)
        }
        val due=db.long("SELECT count(*) FROM active_card WHERE due<=?",now().toString()).toInt()
        val reviewed=db.long("SELECT count(*) FROM effective_event WHERE kind='review' AND detail='scheduled' AND day=?",day).toInt()
        // Keep the goal stable as reviews are done.
        if(due+reviewed>0)add("Review due vocabulary","Vocabulary","Scheduled reviews",(due+reviewed).coerceAtMost((mins/2).coerceAtLeast(1)))
        val learned=pref("goal").toInt().coerceAtMost(mins.coerceAtLeast(1))
        add("Recall new vocabulary","Vocabulary","Learn words",learned)
        val weak=progress.entries.firstOrNull{it.value.status in listOf("Practice Needed","Needs Review")}
        val recent=journal(date.minusDays(6).toString(),day).filter{it.activity=="Comprehension" || it.activity=="Practice"}
        val readingNeedsWork=recent.firstOrNull{it.activity=="Comprehension"}?.let{(it.correct?:0)*5<(it.attempted?:0)*4}?:false
        if(weak!=null || !readingNeedsWork && date.dayOfWeek.value%2==0)add("Complete grammar practice","Grammar","Practice",1)
        else add("Complete a reading","Reading","Comprehension",1)
        val preferred=pref("academy.focus").split(",").filter{it in listOf("Listening","Writing","Speaking")}
        val exam=pref("academy.exam").takeIf{it.isNotEmpty()}?.let{runCatching{LocalDate.parse(it)}.getOrNull()}
        val near=exam!=null && exam.toEpochDay()-date.toEpochDay() in 0..28
        val slot=days.indexOf(date.dayOfWeek.value.toString())
        if(preferred.isNotEmpty() && (mins>=30 || near) && slot>=0){
            val skill=preferred[(slot+date.get(java.time.temporal.IsoFields.WEEK_OF_WEEK_BASED_YEAR))%preferred.size]
            if(skill=="Listening")add("Complete a listening round",skill,"Listening in Context",1)
            else add("Complete a "+skill.lowercase()+" task",skill,"Self-reported",1)
        }
    }
    val plan=JSONArray()
    auto.forEach{q->plan.put(JSONArray(listOf(q.id,q.title,q.skill,q.metric,q.target)))}
    set(planKey,plan.toString())
    if(day==day()){
        val evidence=org.json.JSONObject().put("minutes",if(date.dayOfWeek.value.toString() in days)mins else 0)
            .put("weeklyMinutes",pref("academy.weekly").toIntOrNull()?:150).put("days",JSONArray(days))
            .put("examDate",pref("academy.exam")).put("focus",pref("academy.focus")).put("tasks",plan).toString()
        record(Evidence("plan/"+day+"/"+UUID.nameUUIDFromBytes(evidence.toByteArray()),"Planning","Daily plan",day,
            answer=evidence,method="activity record",kind="study plan"))
    }
    return withEdits(auto)
}
internal fun StudyRepository.questCount(q:Quest,day:String):Int=when(q.metric){
    "Self-reported"->db.long("SELECT count(*) FROM academy_event WHERE quest=? AND day=? AND activity='Reported task'",q.id,day)
    "Learn words"->db.long("SELECT count(*) FROM "+successDays+" WHERE firstDay=?",day)
    "Scheduled reviews"->db.long("SELECT count(*) FROM effective_event WHERE kind='review' AND detail='scheduled' AND day=?",day)
    "Listening in Context"->db.long("SELECT count(*) FROM exploration WHERE mode='Listening in Context' AND part='Completed' AND date(at/1000,'unixepoch','localtime')=?",day)
    else->db.long("SELECT count(*) FROM academy_event WHERE skill=? AND activity=? AND day=?",q.skill,q.metric,day)
}.toInt()
internal fun StudyRepository.logQuest(q:Quest) {
    require(q.metric=="Self-reported")
    val count=questCount(q,day())
    if(count<q.target)record(Evidence("reported/"+q.id+"/"+day()+"/"+count,q.skill,"Reported task",q.title,method="self-reported",quest=q.id))
}
internal fun StudyRepository.questMilestones(quests:List<Quest>){
    for(q in quests)if(questCount(q,day())>=q.target)
        record(Evidence("quest/"+q.id+"/"+day(),q.skill,"Quest completed",q.title,method=if(q.metric=="Self-reported")"self-reported" else "activity record",quest=q.id))
}
internal fun StudyRepository.achievements():List<String> = buildList {
    if(db.long("SELECT count(*) FROM academy_vocab WHERE xp>0")>0)add("First successful vocabulary rating")
    if(db.long("SELECT count(*) FROM academy_event WHERE activity='Comprehension' AND correct=attempted")>0)add("Perfect reading comprehension round")
    if(db.long("SELECT count(*) FROM academy_event WHERE activity='Assessment' AND correct=attempted")>0)add("Perfect grammar assessment")
    if(db.long("SELECT count(DISTINCT day) FROM (SELECT day FROM academy_vocab UNION SELECT day FROM academy_event WHERE correct>0)")>=3)add("Practice on three different days")
    if(db.long("SELECT count(*) FROM exploration WHERE mode='Listening in Context' AND correct=1")>0)add("First listening answer")
}
