package org.lexora.study

import java.time.LocalDate
import java.time.DayOfWeek
import java.time.temporal.TemporalAdjusters

val reviewRanges=listOf("Today","Last 7 days","Last 30 days","Older than 30 days","All previously studied words","Custom date range")
val reviewOrders=listOf("Oldest first","Newest first","Alphabetical","Random")
data class WeeklyGoals(val learningDays:Int=0,val scheduled:Int=0,val selectedToday:Int=0)
fun StudyRepository.weeklyGoals():WeeklyGoals {
    val today=LocalDate.parse(day())
    val monday=today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
    var hits=0
    for(i in 0..today.dayOfWeek.value-1){
        val date=monday.plusDays(i.toLong()).toString()
        val goal=db.str("SELECT v FROM pref WHERE k LIKE 'dailyTarget.%' AND k<=? ORDER BY k DESC LIMIT 1","dailyTarget."+date)?.toIntOrNull() ?: continue
        val successes=db.long("SELECT count(*) FROM $successDays WHERE firstDay=?",date)
        if(successes>=goal)hits++
    }
    return WeeklyGoals(hits,db.long("SELECT count(*) FROM effective_event WHERE kind='review' AND detail='scheduled' AND day BETWEEN ? AND ?",monday.toString(),day()).toInt(),
        db.long("""SELECT count(*) FROM (SELECT a.word FROM assessment a WHERE grade<4 AND EXISTS(
            SELECT 1 FROM effective_event e WHERE e.word=a.word AND e.kind='assess' AND e.at=a.at AND e.day=?)
            UNION SELECT substr(k,9) FROM pref WHERE k LIKE 'revisit.%' AND v=?)""",day(),day()).toInt())
}
fun StudyRepository.academicWords(query:String="",category:String="All")=db.rows("""
    SELECT w.* FROM word w JOIN content_v4 x ON x.word=w.id WHERE x.category!='' AND (?='All' OR x.category=?)
        AND (instr(lower(w.lemma),lower(?))>0 OR instr(lower(w.meaning),lower(?))>0) ORDER BY w.lemma COLLATE NOCASE
""",listOf(category,category,query.trim(),query.trim())){it.word()}
fun StudyRepository.addAcademic(id:String):String?=db.tx {
    require(db.long("SELECT count(*) FROM content_v4 WHERE word=? AND category!=''",id)==1L)
    if(db.long("SELECT count(*) FROM active_card WHERE word=?",id)>0){
        set("revisit."+id,day());return@tx null
    }
    if(grade(id) !in 1..3)reclassify(id,1)
    null
}
fun StudyRepository.dateReviewIds(range:String,order:String,size:Int,from:String="",to:String=""):List<String>{
    require(range in reviewRanges && order in reviewOrders && size in 1..500)
    val today=LocalDate.parse(day())
    val (start,end)=when(range){
        "Today"->today to today
        "Last 7 days"->today.minusDays(6) to today
        "Last 30 days"->today.minusDays(29) to today
        "Older than 30 days"->LocalDate.of(1,1,1) to today.minusDays(31)
        "Custom date range"->LocalDate.parse(from) to LocalDate.parse(to)
        else->LocalDate.of(1,1,1) to today
    }
    require(!end.isBefore(start)){"Choose an end date on or after the start date."}
    val sort=when(order){"Newest first"->"firstDay DESC,w.id";"Alphabetical"->"w.lemma COLLATE NOCASE,w.id";"Random"->"random()";else->"firstDay,w.id"}
    return db.rows("SELECT w.id FROM word w JOIN (SELECT word,min(day) firstDay FROM effective_event WHERE kind IN ('new','review') GROUP BY word) f ON f.word=w.id WHERE firstDay BETWEEN ? AND ? ORDER BY "+sort+" LIMIT ?",listOf(start.toString(),end.toString(),size.toString())){it.getString(0)}
}
fun StudyRepository.startDateReview(range:String,order:String,size:Int,from:String,to:String):String?=db.tx {
    if(session()!=null)return@tx "Pause and finish your current session before starting another."
    val ids=dateReviewIds(range,order,size,from,to)
    if(ids.isEmpty())return@tx "No words were first studied in this date range."
    val setting=pref("direction")
    save(Session(Mode.Custom,ids,Stage.Practice,limit=ids.size,v3=true,batch="date-review",
        plan=ids.mapIndexed{i,id->Turn(id,Drill.Flash,setting=="English → German" || setting=="Balanced" && i%2==1)}))
    null
}
