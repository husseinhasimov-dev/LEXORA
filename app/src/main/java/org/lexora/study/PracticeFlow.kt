package org.lexora.study

import org.json.JSONArray
import kotlin.math.ln1p
import kotlin.math.roundToInt
import kotlin.random.Random

val practiceDirections = listOf("Balanced", "German → English", "English → German")
const val preparationTarget = 6000
fun usageScore(rank: Int): Int? = if (rank <= 0) null else
    (100 * (1 - ln1p((rank - 1) / 100.0) / ln1p(68133 / 100.0))).roundToInt().coerceIn(0, 100)

data class GoalCounts(val known: Int, val learned: Int, val mastered: Int, val today: Int, val masteredWithoutSuccessfulRecall: Int=0) {
    val covered get() = known + learned + masteredWithoutSuccessfulRecall
    val remaining get() = (preparationTarget - covered).coerceAtLeast(0)
}
internal const val successDays = """(SELECT word,min(day) AS firstDay FROM effective_event
    WHERE (kind IN ('new','review') AND score>=3) OR (kind='answer' AND detail='New-word retry recalled' AND score=1) GROUP BY word)"""
fun StudyRepository.learnedToday() = db.long("SELECT count(*) FROM $successDays WHERE firstDay=?", day()).toInt()
fun StudyRepository.goalCounts(stats: Stats): GoalCounts {
    val learned = db.long("""SELECT count(*) FROM $successDays s WHERE NOT EXISTS(
        SELECT 1 FROM assessment a WHERE a.word=s.word AND a.grade=4 AND NOT EXISTS(
        SELECT 1 FROM active_card c WHERE c.word=s.word))""").toInt()
    val masteryOnly=db.long("""SELECT count(*) FROM active_card c WHERE c.state='REVIEW' AND c.stability>=30 AND c.due>=?
        AND NOT EXISTS(SELECT 1 FROM $successDays s WHERE s.word=c.word)""",now().toString()).toInt()
    return GoalCounts(stats.coverage.sumOf {it.known}, learned, stats.mastered, learnedToday(),masteryOnly)
}
fun StudyRepository.setDailyGoal(value: Int) = db.tx {
    require(value in 1..500)
    set("goal",value.toString());set("dailyTarget."+day(),value.toString())
    session()?.takeIf { it.v3 && it.stage==Stage.Assess }?.let { save(it.copy(limit=value)) }
}
fun StudyRepository.studySelection(level: String, topic: String) = selections(level,topic)
    .map { it.id }

fun StudyRepository.studyWords(ids: List<String>): List<Word> {
    if(ids.isEmpty())return emptyList()
    val byId=ids.chunked(400).flatMap { batch->db.rows("SELECT * FROM word WHERE id IN ("+batch.joinToString(","){"?"}+")",batch){it.word()} }.associateBy{it.id}
    return ids.mapNotNull(byId::get)
}

// Recover the visited order of a discovery interrupted on version 0.2.
fun StudyRepository.upgradeCourse() {
    val s=session() ?: return
    if(s.v3 || s.batch.isEmpty() || s.mode!=Mode.New) return
    db.tx {
        if(s.stage==Stage.Assess){
            val previous=db.rows("SELECT before_session FROM undo_step ORDER BY seq"){it.getString(0)}
                .filter(String::isNotEmpty).map(Session::decode).filter {it.batch==s.batch && it.stage==Stage.Assess}.map{it.current}
            val trail=(previous.filter{it!=s.current}+s.current).distinct()
            save(s.copy(v3=true,ids=trail,index=trail.lastIndex))
        }else{
            val pending=selections().map{it.id}.toSet()
            val remaining=s.ids.filter{it in pending}
            save(if(remaining.isEmpty())null else s.copy(v3=true,ids=remaining,index=0,limit=remaining.size,
                stage=Stage.StudyAll,feedback="",reveal=false,rating=0,passed=false))
        }
    }
}

enum class Drill { Choice, Audio, Flash, Retry, Bridge }
data class Turn(val word: String, val kind: Drill, val reverse: Boolean = false) {
    fun json() = JSONArray().put(word).put(kind.name).put(reverse)
    companion object { fun read(a: JSONArray) = Turn(a.getString(0), Drill.valueOf(a.getString(1)), a.getBoolean(2)) }
}

fun StudyRepository.finishV3Discovery(s: Session, visited: List<String>) {
    val selected = studySelection(s.level,s.topic)
    if (selected.isEmpty()) {
        if(visited.isEmpty()) save(null)
        else save(s.copy(ids=visited,index=visited.lastIndex,stage=Stage.DiscoveryEnd,trail=visited))
    } else save(s.copy(ids=selected,index=0,stage=Stage.StudyAll,trail=visited,
        limit=selected.size,duration=pref("duration").toInt()))
}

private fun StudyRepository.forward(s: Session) {
    if(s.index < s.ids.lastIndex) { save(s.copy(index=s.index+1)); return }
    val room = selections(s.level,s.topic).size + learnedToday() < s.limit
    val next = if(room) candidate(s.level,s.topic) else null
    if(next==null) save(s.copy(stage=Stage.DiscoveryEnd,trail=s.ids))
    else save(s.copy(ids=s.ids+next,index=s.index+1))
}

private fun reverseDirection(setting: String, ordinal: Int) = when(setting) {
    "English → German" -> true
    "German → English" -> false
    else -> ordinal % 2 == 1
}

// Two intervening activities separate retrievals even when the selected group contains one word.
private fun StudyRepository.appendSpaced(plan: MutableList<Turn>, turn: Turn, selected: List<String>) {
    while(plan.takeLast(2).any { it.word == turn.word }) {
        val avoid = (selected + plan.takeLast(2).map { it.word } + turn.word).distinct()
        val filler = fillerWord(avoid.toSet(),(plan.takeLast(2).map{it.word}+turn.word).toSet())
        plan += Turn(filler,Drill.Bridge,reverseDirection(pref("direction"),0))
    }
    plan += turn
}
private fun StudyRepository.fillerWord(avoid:Set<String>,recent:Set<String>):String {
    db.rawQuery("SELECT id FROM word WHERE rank>0 ORDER BY rank",null).use{c->
        var fallback:String?=null
        while(c.moveToNext()){
            val id=c.getString(0)
            if(id !in avoid)return id
            if(fallback==null && id !in recent)fallback=id
        }
        return requireNotNull(fallback)
    }
}
private fun StudyRepository.beginPractice(s: Session) {
    val setting=pref("direction")
    val plan=mutableListOf<Turn>()
    // The learner can finish studying on any entry. A neutral warm-up separates that view from testing.
    val filler=fillerWord(s.ids.toSet(),setOf(s.ids.first()))
    plan += Turn(filler,Drill.Bridge,reverseDirection(pref("direction"),0))
    s.ids.forEachIndexed { i,id ->
        if(grade(id)!=3) appendSpaced(plan,Turn(id,if(i%4==1 && setting=="Balanced")Drill.Audio else Drill.Choice,reverseDirection(setting,i)),s.ids)
    }
    s.ids.forEachIndexed { i,id -> appendSpaced(plan,Turn(id,Drill.Flash,reverseDirection(setting,i+1)),s.ids) }
    save(s.copy(stage=Stage.Practice,index=0,step=0,plan=plan,feedback="",reveal=false,activeMs=0))
}
fun StudyRepository.startListening(): String? = db.tx {
    if(session()!=null) return@tx "Resume or finish your current session first."
    val ids = db.rows("SELECT id FROM word WHERE level=? AND ipa!='' ORDER BY RANDOM() LIMIT ?",listOf(pref("level"),pref("size"))){it.getString(0)}
    if(ids.isEmpty()) return@tx "No pronunciation entries at this level."
    db.delete("undo_step",null,null)
    save(Session(Mode.Custom,ids,Stage.Practice,limit=ids.size,v3=true,plan=ids.map{Turn(it,Drill.Audio)}))
    null
}

private fun StudyRepository.advancePractice(s: Session): String? {
    if(s.step+1==s.plan.size || (s.duration>0 && s.activeMs>=s.duration*60000L)){
        if(s.mode==Mode.New || s.batch=="date-review"){
            save(s.copy(stage=Stage.RoundResult,index=0,feedback="",reveal=false))
            return null
        }
        save(null)
        return "Practice complete"
    }
    save(s.copy(step=s.step+1,feedback="",reveal=false,passed=false))
    return null
}

fun StudyRepository.v3Action(s: Session, action: String, input: String): String? {
    when(s.stage) {
        Stage.Assess -> when(action) {
            "previous" -> if(s.index>0) save(s.copy(index=s.index-1))
            "forward" -> if(grade(s.current)>0) forward(s)
            "assess" -> {
                val value=input.toInt()
                val prior=grade(s.current)
                if(prior!=value) recordAssessment(s.current,value)
                if(prior==0 && s.index==s.ids.lastIndex) forward(s.copy(inspected=s.inspected+1))
            }
            "finishDiscovery" -> save(s.copy(stage=Stage.DiscoveryEnd,trail=s.ids))
            "learnSelected" -> finishV3Discovery(s,s.ids)
        }
        Stage.StudyAll,Stage.DiscoveryEnd -> when(action) {
            "previous" -> if(s.trail.isNotEmpty())save(s.copy(stage=Stage.Assess,ids=s.trail,index=s.trail.lastIndex,limit=pref("goal").toInt()))
            "learnSelected" -> if(s.stage==Stage.DiscoveryEnd)finishV3Discovery(s,s.trail)
            "startPractice" -> if(s.stage==Stage.StudyAll) beginPractice(s)
            "finish" -> save(null)
        }
        Stage.RoundResult -> when(action){
            "finish" -> save(null)
            "retry" -> {
                val remaining=s.ids-s.completed.toSet()-s.skipped.toSet()
                if(remaining.isNotEmpty()){
                    val plan=s.plan.take(s.step+1).toMutableList()
                    remaining.forEachIndexed { i,id->appendSpaced(plan,Turn(id,Drill.Retry,reverseDirection(pref("direction"),i)),s.ids) }
                    save(s.copy(stage=Stage.Practice,plan=plan,step=s.step+1,feedback="",reveal=false,activeMs=0))
                }
            }
        }
        Stage.Practice -> {
            val turn=s.plan[s.step]
            when(action){
                "reveal" -> if(turn.kind in listOf(Drill.Flash,Drill.Retry))save(s.copy(reveal=true))
                "written" -> if(turn.kind in listOf(Drill.Flash,Drill.Retry) && !s.reveal && input.isNotBlank())
                    save(s.copy(reveal=true,feedback=input.trim().take(300)))
                "sessionKnown" -> if(turn.kind!=Drill.Bridge && turn.word in s.ids){
                    db.delete("pref","k=?",arrayOf("revisit."+turn.word))
                    val next=s.plan.take(s.step+1)+s.plan.drop(s.step+1).filter{it.word!=turn.word}
                    return advancePractice(s.copy(plan=next,skipped=(s.skipped+turn.word).distinct(),completed=s.completed-turn.word,missed=s.missed-turn.word))
                }
                "retryGrade" -> if(turn.kind==Drill.Retry && s.reveal){
                    require(input in listOf("yes","no"))
                    val ok=input=="yes"
                    event(turn.word,"answer",if(ok)1 else 0,if(s.mode==Mode.New && ok)"New-word retry recalled" else "Session retry")
                    return advancePractice(s.copy(completed=if(ok)(s.completed+turn.word).distinct() else s.completed-turn.word,
                        missed=if(ok)s.missed-turn.word else (s.missed+turn.word).distinct()))
                }
                "rate" -> if(turn.kind==Drill.Flash && s.reveal){
                    val score=input.toInt()
                    require(score in 1..4)
                    if(s.mode==Mode.New)db.delete("pref","k=?",arrayOf("revisit."+turn.word))
                    if(s.mode==Mode.New && grade(turn.word)!=4 && db.long("SELECT count(*) FROM active_card WHERE word=?",turn.word)==0L) schedule(s.copy(rating=score))
                    val plan=s.plan.toMutableList()
                    if(score<=2 && plan.drop(s.step+1).none{it.word==turn.word && it.kind==Drill.Retry})
                        appendSpaced(plan,Turn(turn.word,Drill.Retry,reverseDirection(pref("direction"),if(turn.reverse)0 else 1)),s.ids)
                    return advancePractice(s.copy(plan=plan,completed=if(score>=3)(s.completed+turn.word).distinct() else s.completed-turn.word,
                        missed=if(score>=3)s.missed-turn.word else (s.missed+turn.word).distinct()))
                }
                "choice","meaningChoice" -> if(turn.kind in listOf(Drill.Choice,Drill.Bridge,Drill.Audio) && s.feedback.isEmpty()){
                    val ok=input==turn.word
                    val plan=s.plan.toMutableList()
                    if(turn.kind!=Drill.Bridge){
                        if(!ok && db.long("SELECT count(*) FROM word WHERE id=?",input)==1L) recordExplore("confusion",pairKey(turn.word,input),if(turn.reverse)"German choice" else "Meaning choice",turn.word,input,input,false)
                        event(turn.word,"answer",if(ok)1 else 0,if(ok)turn.kind.name else if(turn.kind==Drill.Audio && action=="choice")"Listening" else "Translation confusion")
                        if(!ok && plan.drop(s.step+1).none{it.word==turn.word && it.kind==Drill.Retry})
                            appendSpaced(plan,Turn(turn.word,Drill.Retry,turn.reverse),s.ids)
                    }
                    save(s.copy(feedback=if(ok)"Correct" else "Review the answer",passed=ok,plan=plan))
                }
                "next" -> if(s.feedback.isNotEmpty() && turn.kind !in listOf(Drill.Flash,Drill.Retry) || turn.kind==Drill.Retry && s.reveal && s.mode==Mode.Custom && s.batch!="date-review") return advancePractice(s)
            }
        }
        else -> Unit
    }
    return null
}

data class Question(val choices: List<Word>, val audioSafe: Boolean)
private fun contentTokens(text: String) = text.lowercase().split(Regex("[^a-zäöüß]+"))
    .filter { it.isNotBlank() && it !in setOf("a","an","and","or","to","the","of","in","with") }.toSet()

// Require distinct first vowel classes in single-transcription IPA, a conservative screen for homophones.
fun soundClass(ipa: String): String? {
    if(!ipa.matches(Regex("/[^/,;| ]+/"))) return null
    val vowel = ipa.firstOrNull { it in "aeiouyɑɐɒæɛəɜɞɪɔœøɵʊʏ" } ?: return null
    return when(vowel){in "aɑɐɒæ"->"a";in "eɛəɜɞ"->"e";in "iɪ"->"i";in "oɔ"->"o";in "uʊ"->"u";in "yʏ"->"ü";else->"ö"}
}
fun StudyRepository.question(s: Session, w: Word): Question {
    val turn=s.plan[s.step]
    val rng=Random(w.id.hashCode()+s.step)
    val pool=db.rows("""SELECT * FROM word WHERE id<>? AND rank>0
        ORDER BY (pos<>?), (level<>?),abs(rank-?),id LIMIT 240""",listOf(w.id,w.pos,w.level,w.rank.toString())){it.word()}.shuffled(rng)
    fun options(audio:Boolean):List<Word>{
        val result=mutableListOf(w)
        val sounds=mutableSetOf(soundClass(w.ipa))
        val meanings=contentTokens(w.meaning).toMutableSet()
        val relatives=contentTokens(w.synonyms+" "+w.lemma).toMutableSet()
        for(c in pool){
            if(turn.kind==Drill.Bridge && c.id in s.ids)continue
            val tokens=contentTokens(c.meaning)
            if(tokens.isEmpty() || tokens.any{it in meanings} || contentTokens(c.lemma+" "+c.synonyms).any {it in relatives})continue
            if(audio){
                val sound=soundClass(c.ipa)?:continue
                if(!sounds.add(sound))continue
            }
            result+=c
            meanings+=tokens
            relatives+=contentTokens(c.lemma+" "+c.synonyms)
            if(result.size==4)break
        }
        return result.shuffled(rng)
    }
    if(turn.kind==Drill.Audio && soundClass(w.ipa)!=null){
        val choices=options(true)
        if(choices.size==4)return Question(choices,true)
    }
    return Question(options(false),false)
}
