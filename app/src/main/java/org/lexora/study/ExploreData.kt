package org.lexora.study

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

val exploreModes=listOf("Context Mastery","Confusion Lab","Listening in Context","Mini Stories","Word Families")
val familyLinks=listOf(
    Triple("entscheiden","Entscheidung","Noun derived with -ung"), Triple("entscheiden","entscheidend","Participial adjective"),
    Triple("arbeiten","Arbeit","Verb and related noun"),Triple("Arbeit","Arbeitsplatz","Compound of Arbeit and Platz, with linking -s-"),
    Triple("beschreiben","Beschreibung","Noun derived with -ung"),Triple("schreiben","beschreiben","Verb formed with be-"),
    Triple("zahlen","Zahlung","Noun derived with -ung"),Triple("zahlen","bezahlen","Verb formed with be-"),
    Triple("wohnen","Wohnung","Noun derived with -ung"),Triple("studieren","Studium","Verb and related Latin-derived noun")
)
val contrastNotes=mapOf(setOf("wissen","kennen") to "Use wissen for a fact or an answer. Use kennen for familiarity with a person, place or work.",
    setOf("lernen","studieren") to "Lernen describes acquiring knowledge or a skill. Studieren usually refers to university study.")
val markedWord=Regex("\\[([^\\]]+)]")
data class Passage(val id:String,val kind:String,val level:String,val topic:String,val title:String,val marked:String,val english:String,val checks:List<List<String>>){
    val text get()=markedWord.replace(marked) { it.groupValues[1] }
    val targets get()=markedWord.findAll(marked).map{it.groupValues[1]}.toList()
    val sentences get()=text.split(Regex("(?<=[.!?])\\s+"))
    val academic get()=topic in listOf("University","Research")
}
fun JSONArray.strings()=List(length()){getString(it)}
fun passages(ctx:Context):List<Passage>{
    val entries=JSONArray(ctx.assets.open("explore-passages.json").bufferedReader().use{it.readText()})
    return List(entries.length()){n->val r=entries.getJSONObject(n);val checks=r.getJSONArray("questions")
        Passage(r.getString("id"),r.getString("kind"),r.getString("level"),r.getString("topic"),r.getString("title"),r.getString("de"),r.getString("en"),List(checks.length()){checks.getJSONArray(it).strings()})}
}
fun pairKey(a:String,b:String)=listOf(a,b).sorted().joinToString("|")
fun StudyRepository.recordExplore(mode:String,item:String,part:String,target:String?,other:String?,answer:String,correct:Boolean,id:String=UUID.randomUUID().toString()){
    db.execSQL("INSERT OR IGNORE INTO exploration VALUES(?,?,?,?,?,?,?,?,?)",arrayOf(id,mode,item,part,target,other,answer,if(correct)1 else 0,now()))
}
data class Attempt(val mode:String,val item:String,val part:String,val target:String?,val other:String?,val answer:String,val ok:Boolean,val at:Long)
fun StudyRepository.attempts()=db.rows("SELECT mode,item,part,target,other,answer,correct,at FROM exploration ORDER BY at,rowid"){
    Attempt(it.getString(0),it.getString(1),it.getString(2),it.getString(3),it.getString(4),it.getString(5),it.getInt(6)==1,it.getLong(7))}
data class Prompt(val part:String,val prompt:String,val key:String,val choices:List<String>,val target:String?=null,val spoken:String="",val ids:Map<String,String> = emptyMap()){
    fun accepts(value:String)=value.trim().replace(Regex("\\s+")," ")==key.trim().replace(Regex("\\s+")," ")
    fun json()=JSONObject().put("part",part).put("prompt",prompt).put("key",key).put("choices",JSONArray(choices)).put("target",target).put("spoken",spoken).put("ids",JSONObject(ids))
    companion object{fun read(j:JSONObject):Prompt {val options=j.getJSONObject("ids");return Prompt(j.getString("part"),j.getString("prompt"),j.getString("key"),j.getJSONArray("choices").strings(),j.optString("target").takeIf{it.isNotEmpty()},j.getString("spoken"),options.keys().asSequence().associateWith{options.getString(it)})}}
}
fun StudyRepository.beginExplore(mode:String,item:String,qs:List<Prompt>){
    if(pref("explore.session").isNotBlank())return
    require(qs.isNotEmpty());set("explore.session",JSONObject().put("id",UUID.randomUUID().toString()).put("mode",mode).put("item",item).put("step",0).put("qs",JSONArray(qs.map{it.json()})).toString())
}
fun StudyRepository.answerExplore(id:String,step:Int,value:String)=db.tx{
    val j=runCatching{JSONObject(pref("explore.session"))}.getOrNull()?:return@tx
    if(j.getString("id")!=id || j.getInt("step")!=step || j.has("feedback"))return@tx
    val q=Prompt.read(j.getJSONArray("qs").getJSONObject(step));val mode=j.getString("mode");val item=j.getString("item")
    val ok=q.accepts(value);val other=q.ids[value]?.takeIf{!ok && it!=q.target}
    recordExplore(mode,item,q.part,q.target,other,value,ok)
    if(other!=null && q.target!=null && mode!="Confusion Lab")recordExplore("confusion",pairKey(q.target,other),q.part,q.target,other,value,false)
    set("explore.session",j.put("feedback",value).toString())
}
fun StudyRepository.nextExplore(id:String,step:Int)=db.tx{
    val j=runCatching{JSONObject(pref("explore.session"))}.getOrNull()?:return@tx
    if(j.getString("id")!=id || j.getInt("step")!=step || !j.has("feedback"))return@tx
    val history=j.optJSONArray("responses")?:JSONArray()
    history.put(j.getString("feedback"));j.put("responses",history)
    j.remove("feedback");j.put("step",step+1)
    if(step+1==j.getJSONArray("qs").length()){
        val mode=j.getString("mode");val item=j.getString("item");val questions=j.getJSONArray("qs")
        recordExplore(mode,item,"Completed",null,null,"",true)
        val skill=when(mode){"Listening in Context"->"Listening";"Mini Stories"->"Reading";else->"Vocabulary"}
        val correct=(0 until questions.length()).count{Prompt.read(questions.getJSONObject(it)).accepts(history.getString(it))}
        // Include keyed prompts so changing the custom exercise set can earn new credit.
        val signature=(0 until questions.length()).map{Prompt.read(questions.getJSONObject(it))}.map{it.prompt+"|"+it.key}.sorted().joinToString("\n")
        val key=UUID.nameUUIDFromBytes(signature.toByteArray(Charsets.UTF_8))
        record(Evidence("explore/"+id,skill,mode,item,attempted=questions.length(),correct=correct),
            "explore/"+mode+"/"+item+"/"+key,AcademyXp.points(if(skill=="Listening")25 else 12,null,correct.toDouble()/questions.length()),"Completed "+mode+" · "+correct+"/"+questions.length())
    }
    set("explore.session",j.toString())
}
fun options(key:String,wrong:List<String>,seed:String)=(listOf(key)+wrong.filter{it!=key}.distinct().take(3)).shuffled(kotlin.random.Random(seed.hashCode()))
fun contextPrompts(w:Word,others:List<Word>,hard:Boolean):List<Prompt>{
    val glosses=w.meaning.lowercase().split(Regex("[;,]")).map{it.trim()}.toSet()
    val decoys=others.asSequence().filter{it.id!=w.id && it.answer.isNotBlank() && !it.answer.equals(w.answer,true) && it.translation!=w.translation && it.example!=w.example && it.meaning.lowercase().split(Regex("[;,]")).none{gloss->gloss.trim() in glosses}}.distinctBy{it.answer}.take(3).toList()
    val forms=options(w.answer,decoys.map{it.answer},w.id)
    val tokens=w.example.split(Regex("\\s+"))
    val gap=Prompt("Gap","Type the missing form of "+w.german+".\n"+w.cloze+"\n"+w.translation,w.answer,emptyList(),w.id)
    val pick=Prompt("Word choice","Find the form that matches this sense: "+w.meaning+"\n"+w.translation+"\n"+w.cloze,w.answer,forms,w.id,ids=decoys.associate{it.answer to it.id})
    val meaning=Prompt("Sentence meaning",w.example,w.translation,options(w.translation,decoys.map{it.translation},w.example),w.id)
    val order=Prompt("Construction","Rebuild the source sentence from memory.\n"+w.translation,w.example,tokens.shuffled(kotlin.random.Random(w.id.hashCode())),w.id)
    return if(hard)listOf(gap,order,pick,meaning) else listOf(meaning,pick,gap,order)
}
