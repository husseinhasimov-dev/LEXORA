package org.lexora.study

import android.content.Context
import org.json.JSONArray
import java.util.Locale
import kotlin.math.abs

internal val readingTopics=listOf("Everyday life","University and education","Science and technology","Computer science and AI","Economics and business","Politics and society","Geography and history","Culture and travel","Environment and climate","Health and wellbeing","Academic German","TestDaF-oriented academic themes")
internal val compositions=listOf("Mostly known","A few unfamiliar","Balanced","Mostly unfamiliar","Previously missed","Weak FSRS","Due for review","Unseen","Academic","Personal targets","Custom mix")
internal val lengthBands=linkedMapOf("Any" to 0..10000,"Short" to 150..250,"Medium" to 350..550,"Long" to 700..1000,"Extra long" to 1200..1800)
internal val germanToken=Regex("[\\p{L}]+(?:[-’'][\\p{L}]+)*")
internal data class AcademyQuestion(val type:String,val text:String,val key:String,val distractors:List<String>,val why:String) {
    fun accepts(value:String):Boolean {
        fun norm(s:String)=java.text.Normalizer.normalize(s.trim().trimEnd('.','?','!').replace(Regex("\\s+")," "),java.text.Normalizer.Form.NFC)
        return norm(value).equals(norm(key),ignoreCase=true)
    }
}
internal data class Reading(val id:String,val title:String,val level:String,val topic:String,val text:String,val questions:List<AcademyQuestion>,
    val forms:Map<String,String>,val grammar:String="",val quote:String="",val version:Int=1) {
    val paragraphs=text.split(Regex("\n\\s*\n"))
    val tokens=germanToken.findAll(text).map{it.value}.toList()
    val academic get()=topic in setOf(readingTopics[1],readingTopics[2],readingTopics[3],readingTopics[10],readingTopics[11])
}
internal interface ReadingProvider { fun passages(context:Context):List<Reading> }
internal object OfflineReadings:ReadingProvider {
    override fun passages(context:Context):List<Reading> {
        val entries=JSONArray(context.assets.open("academy-readings.json").bufferedReader().use{it.readText()})
        val newTexts=List(entries.length()){i->
            val r=entries.getJSONObject(i);val f=r.getJSONObject("forms");val qs=r.getJSONArray("questions")
            Reading(r.getString("id"),r.getString("title"),r.getString("level"),r.getString("topic"),r.getString("text"),
                List(qs.length()){n->val q=qs.getJSONObject(n);AcademyQuestion(q.getString("type"),q.getString("prompt"),q.getString("key"),q.getJSONArray("choices").strings(),q.getString("why"))},
                f.keys().asSequence().associateWith(f::getString),r.getString("grammar"),r.getString("quote"))
        }
        val reasons=listOf(
            "Mila's key is inside the flat." to "She brings Frau Klein flowers that evening.",
            "Ali gives the seat to the woman." to "She points out a large lake.",
            "The address is familiar, but the name on the note is wrong." to "The father is waiting for his daughter's birthday gift.",
            "Ben's phone has no power." to "His sister brings two bicycles.",
            "The manager opens a room for conversation." to "She follows up with both groups about their experience.",
            "Jana notices the student looking in the wrong place." to "He explains a difficult task to Jana.",
            "The team mounted the sensor differently that day." to "Mira reports both calculations and explains the measurement conditions.",
            "The bus-stop interviews reach shift workers." to "The design changes while the park area stays the same.")
        val legacy=org.lexora.study.passages(context).filter{it.kind=="R"}.map{p->
            val why=reasons[p.id.substringAfter('-').toInt()-1]
            val topic=when(p.topic){"Travel"->7;"University"->1;"Community"->5;"Research"->2;else->0}
            Reading(p.id,p.title,p.level,readingTopics[topic],p.text,p.checks.mapIndexed{i,q->AcademyQuestion("Detail",q[0],q[1],q.drop(2),if(i==0)why.first else why.second)},p.targets.associateWith{it})
        }
        return newTexts+legacy
    }
}
internal data class Lesson(val id:String,val level:String,val title:String,val prerequisite:String,val explanation:String,val example:String,val mistake:String,val tasks:List<AcademyQuestion>)
internal fun grammarLessons(context:Context)=context.assets.open("academy-grammar.tsv").bufferedReader().useLines{lines->
    lines.filter{it.isNotBlank() && !it.startsWith("#")}.map{line->
        val f=line.split('|');require(f.size==15){"Invalid grammar lesson: "+f.first()}
        Lesson(f[0],f[1],f[2],f[3],f[4],f[5],f[6],(7..13 step 2).map{i->
            val prompt=f[i]
            val choices=Regex("\\(([^() ]+)/([^() ]+)\\)").find(prompt)?.groupValues?.drop(1).orEmpty().filterNot{it.equals(f[i+1],true)}
            AcademyQuestion(when{prompt.startsWith("Order")->"Word ordering";prompt.startsWith("Correct")->"Error correction";choices.isNotEmpty()->"Choice";else->"Written response"},
                prompt,f[i+1],choices,f[4])
        })
    }.toList()
}
internal data class LexicalState(val word:Word,val known:Boolean,val weak:Boolean,val due:Boolean,val missed:Boolean,val unseen:Boolean,val academic:Boolean)
internal class ReadingIndex(val entries:List<LexicalState>){
    private val exact=entries.groupBy{it.word.lemma}
    fun lookup(surface:String,passage:Reading):LexicalState? {
        val annotated=passage.forms[surface]
        if(annotated!=null)return exact[annotated]?.singleOrNull()
        val direct=exact[surface]?.singleOrNull()
        val lower=surface.lowercase(Locale.GERMAN)
        val alternative=if(lower!=surface) exact[lower]?.singleOrNull() else null
        if(direct!=null && alternative!=null && direct!=alternative)return null
        return direct?:alternative
    }
}
internal fun StudyRepository.readingIndex():ReadingIndex {
    val missed=db.rows("SELECT DISTINCT word FROM effective_event WHERE kind='answer' AND score=0 OR kind IN ('review','new') AND score=1"){it.getString(0)}.toSet()
    return ReadingIndex(db.rows("""SELECT w.*,a.grade,c.state,c.stability,c.due,x.category FROM word w
        LEFT JOIN assessment a ON a.word=w.id LEFT JOIN active_card c ON c.word=w.id LEFT JOIN content_v4 x ON x.word=w.id"""){c->
        val w=c.word();val state=c.getString(20);val weak=state!=null && (state!="REVIEW" || c.getDouble(21)<7)
        LexicalState(w,c.getInt(19)==4 || state=="REVIEW",weak,state!=null && c.getLong(22)<=now(),w.id in missed,c.isNull(19)&&state==null,!c.getString(23).isNullOrBlank())
    })
}
internal data class ReadingPrefs(val level:String="A1",val topic:String="Random topic",val length:String="Any",val mix:String="Balanced",
    val known:Int=60,val weak:Int=10,val unknownCount:Int=0,val targets:Set<String> = emptySet())
internal data class RankedText(val p:Reading,val matched:Int,val known:Int,val identifiable:Int,val targets:Set<String>,val score:Double,val misses:List<String>)
internal fun recommend(all:List<Reading>,index:ReadingIndex,prefs:ReadingPrefs,history:Map<String,Pair<Int,Double>>,seed:Int):List<RankedText>{
    val random=kotlin.random.Random(seed)
    return all.shuffled(random).map {p->
        val words=p.tokens.mapNotNull{index.lookup(it,p)}.distinctBy{it.word.id}
        val ids=words.map{it.word.id}.toSet();val count=words.size.coerceAtLeast(1)
        val known=words.count{it.known};val weak=words.count{it.weak || it.missed}
        val expected=when(prefs.mix){"Mostly known"->0.95;"A few unfamiliar"->0.8;"Mostly unfamiliar"->0.2;"Custom mix"->prefs.known/100.0;else->0.5}
        val mixScore=when(prefs.mix){"Previously missed"->words.count{it.missed}.toDouble()/count;"Due for review"->words.count{it.due}.toDouble()/count
            "Weak FSRS"->weak.toDouble()/count;"Unseen"->words.count{it.unseen}.toDouble()/count
            "Academic"->(if(p.academic)0.5 else 0.0)+0.5*words.count{it.academic}/count
            else->1-abs(known.toDouble()/count-expected)-(if(prefs.mix=="Custom mix")abs(weak.toDouble()/count-prefs.weak/100.0) else 0.0)}
        val distance=abs(levels.indexOf(p.level)-levels.indexOf(prefs.level))
        val mismatches=mutableListOf<String>()
        if(distance>0)mismatches+="Estimated level "+p.level
        val topicMatch=prefs.topic=="Random topic" || p.topic==prefs.topic
        if(!topicMatch)mismatches+="Topic: "+p.topic
        val range=lengthBands.getValue(prefs.length);val lengthMiss=abs(p.tokens.size-p.tokens.size.coerceIn(range))
        if(lengthMiss>0)mismatches+="Length: "+p.tokens.size+" words"
        val hit=ids.intersect(prefs.targets)
        if(hit.size<prefs.targets.size)mismatches+="Contains "+hit.size+" of "+prefs.targets.size+" targets"
        val prior=history[p.id]
        val score= (if(topicMatch)32 else 0)-distance*24-lengthMiss.coerceAtMost(1000)*0.025+
            mixScore*12+(if(prefs.targets.isEmpty())0.0 else 18.0*hit.size/prefs.targets.size)-
            (if(prefs.unknownCount>0)abs(count-known-prefs.unknownCount).coerceAtMost(50)*0.15 else 0.0)-
            (prior?.let{if(it.second>=0.8)5.0 else 1.0}?:0.0)
        RankedText(p,words.size-known,known,count.takeIf{words.isNotEmpty()}?:0,hit,score,mismatches)
    }.sortedByDescending{it.score}
}
