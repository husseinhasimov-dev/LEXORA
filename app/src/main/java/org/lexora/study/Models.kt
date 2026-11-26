package org.lexora.study

import java.text.Normalizer
import java.util.Locale
import org.json.JSONObject
import org.json.JSONArray
import io.github.openspacedrepetition.Card
import io.github.openspacedrepetition.Scheduler
import io.github.openspacedrepetition.Rating
import java.time.Instant

val levels = listOf("A1", "A2", "B1", "B2")
val classes = mapOf("noun" to "Noun", "verb" to "Verb", "adj" to "Adjective", "adv" to "Adverb",
    "conj" to "Conjunction", "prep" to "Preposition", "pron" to "Pronoun", "det" to "Determiner",
    "num" to "Numeral", "particle" to "Particle", "intj" to "Interjection")
data class Word(val id:String, val lemma:String, val meaning:String, val level:String, val pos:String,
    val article:String, val plural:String, val topic:String, val rank:Int, val evidence:String,
    val example:String, val translation:String, val cloze:String, val answer:String, val ipa:String,
    val grammar:String, val synonyms:String, val antonyms:String, val source:String) {
    val german get() = if(pos=="verb" && "reflexive" in grammar.split(" · ")) "sich $lemma"
        else listOf(article, lemma).filter(String::isNotBlank).joinToString(" ")
}
enum class Mode(val label:String) {
    New("New words"), Review("Scheduled review"), Lab("Mistake Lab"), Quick("Quick study"),
    Custom("Custom study"), Placement("Placement assessment")
}
enum class Stage { Assess, Teach, Discover, Recognize, Recall, Write, Context, StudyAll, Practice, DiscoveryEnd, RoundResult }
data class Session(val mode:Mode, val ids:List<String>, val stage:Stage, val index:Int=0,
    val rating:Int=0, val feedback:String="", val passed:Boolean=false, val reveal:Boolean=false,
    val band:Int=0, val correct:Int=0, val limit:Int=10, val activeMs:Long=0, val duration:Int=0, val batch:String="", val level:String="All", val topic:String="All", val inspected:Int=0,
    val v3:Boolean=false, val trail:List<String> = emptyList(), val plan:List<Turn> = emptyList(), val step:Int=0, val missed:List<String> = emptyList(), val skipped:List<String> = emptyList(), val completed:List<String> = emptyList()) {
    val current get() = if(stage==Stage.Practice) plan[step].word else ids[index]
    fun encode() = JSONObject().put("mode",mode.name).put("ids",JSONArray(ids)).put("stage",stage.name)
        .put("index",index).put("rating",rating).put("feedback",feedback).put("passed",passed)
        .put("reveal",reveal).put("band",band).put("correct",correct).put("limit",limit)
        .put("v3",v3).put("trail",JSONArray(trail)).put("step",step)
        .put("missed",JSONArray(missed)).put("skipped",JSONArray(skipped)).put("completed",JSONArray(completed))
        .put("plan",JSONArray().apply { plan.forEach { put(it.json()) } }).put("batch",batch).put("level",level).put("topic",topic).put("inspected",inspected).put("activeMs",activeMs).put("duration",duration).toString()
    companion object {
        fun decode(raw:String):Session {
            val j=JSONObject(raw)
            val a=j.getJSONArray("ids")
            return Session(Mode.valueOf(j.getString("mode")),List(a.length(),a::getString),
                Stage.valueOf(j.getString("stage")),j.getInt("index"),j.getInt("rating"),j.getString("feedback"),
                j.getBoolean("passed"),j.getBoolean("reveal"),j.getInt("band"),j.getInt("correct"),
                j.getInt("limit"),j.optLong("activeMs"),j.optInt("duration"),j.optString("batch"),j.optString("level","All"),j.optString("topic","All"),j.optInt("inspected"),
                j.optBoolean("v3"), j.optJSONArray("trail")?.let{ t->List(t.length(),t::getString) }.orEmpty(),
                j.optJSONArray("plan")?.let{ p->List(p.length()){Turn.read(p.getJSONArray(it))} }.orEmpty(),j.optInt("step"),j.ids("missed"),j.ids("skipped"),j.ids("completed"))
        }
    }
}

private fun JSONObject.ids(key:String)=optJSONArray(key)?.let{a->List(a.length(),a::getString)}.orEmpty()

object Answers {
    private fun clean(s:String) = Normalizer.normalize(s.trim(), Normalizer.Form.NFC).replace(Regex("\\s+"), " ")
    private fun fold(s:String) = s.lowercase(Locale.GERMAN).replace("ä","a").replace("ö","o").replace("ü","u").replace("ß","ss")
    fun check(input:String, expected:String, word:Word, hints:Boolean):String {
        val a=clean(input)
        val b=clean(expected)
        if(a==b) return "Correct"
        if(a.equals(b,ignoreCase=true)) return "Capitalization"
        val bare=a.removePrefix("der ").removePrefix("die ").removePrefix("das ")
        if(word.article.isNotEmpty() && b==word.german && bare.equals(word.lemma,true)) return "Article"
        val expanded=b.lowercase(Locale.GERMAN).replace("ä","ae").replace("ö","oe").replace("ü","ue").replace("ß","ss")
        if(fold(a)==fold(b) || expanded==a.lowercase(Locale.GERMAN)) return "Umlaut or ß"
        if(word.pos=="verb" && (word.grammar.split(" · ").any { it.substringAfter(": ", "")==a } ||
                    a==word.lemma && b!=word.lemma)) return "Verb form"
        if(hints && a.length>2 && kotlin.math.abs(a.length-b.length)<=1) {
            val shorter=if(a.length<=b.length) a else b
            val longer=if(a.length<=b.length) b else a
            if(a.length==b.length && a.zip(b).count { it.first!=it.second }==1 ||
                a.length!=b.length && longer.indices.any { longer.removeRange(it,it+1)==shorter }) return "Minor spelling error"
        }
        return "Spelling"
    }
}

object Memory {
    fun scheduler(retention:Int) = Scheduler.builder().desiredRetention(retention / 100.0).enableFuzzing(false).build()
    fun review(id:String, previous:Card?, rating:Int, time:Long, retention:Int, calibration:String=""):Card {
        require(rating in 1..4 && retention in 70..97)
        val at=Instant.ofEpochMilli(time)
        val card=previous ?: Card.builder().cardId(id.hashCode()).due(at).build()
        val base=scheduler(retention)
        val model=if(previous==null && calibration.startsWith("initial=")){
            val stability=calibration.removePrefix("initial=").toDouble()
            require(stability.isFinite() && stability in 0.001..100.0)
            val parameters=base.parameters.copyOf()
            parameters[rating-1]=stability
            Scheduler.builder().desiredRetention(retention/100.0).enableFuzzing(false).parameters(parameters).build()
        }else base
        return model.reviewCard(card, Rating.values()[rating-1], at).card()
    }
}
data class Filters(val query:String="",val level:String="All",val pos:String="All",val topic:String="All",
    val status:String="All",val frequency:String="All",val deck:String="All",val page:Int=0,val sort:String="Highest usage")

data class Stats(val total:Int=0,val started:Int=0,val mastered:Int=0,val learning:Int=0,val due:Int=0,
    val todayNew:Int=0,val todayReview:Int=0,val xp:Int=0,val streak:Int=0,val minutes:Int=0,
    val retention:Int?=null,val success:Int?=null,val stability:Double=0.0,val difficulty:Double=0.0,
    val week:List<Int> = emptyList(),val forecast:List<Int> = emptyList(),
    val coverage:List<LevelCoverage> = emptyList(), val weak:List<String> = emptyList(),
    val missed:List<String> = emptyList(), val mistakes:Int=0,val improved:Int=0,val newWeek:List<Int> = emptyList())
