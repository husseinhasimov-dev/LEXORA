package org.lexora.study

import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.pow

data class FirstRecall(val initialRating:Int,val elapsedDays:Int,val recalled:Boolean)
object Calibration{
    // Only the four initial stability weights are fitted. All subsequent transitions
    // use the unchanged upstream FSRS-6 equations with the published default weights.
    fun fit(samples:List<FirstRecall>):DoubleArray{
        val model=Memory.scheduler(90)
        val original=model.parameters
        val result=original.copyOfRange(0,4)
        fun loss(rows:List<FirstRecall>,stability:Double)=rows.sumOf {
            val p=(1+model.getFACTOR()*it.elapsedDays/stability).pow(model.getDECAY()).coerceIn(1e-8,1-1e-8)
            -ln(if(it.recalled)p else 1-p)
        }
        for(index in result.indices){
            val bucket=samples.filter {it.initialRating==index+1 && it.elapsedDays>=1}
            if(bucket.size<50 || bucket.count{it.recalled}<5 || bucket.count{!it.recalled}<5)continue
            val split=bucket.size*4/5
            val train=bucket.take(split);val validation=bucket.drop(split)
            val base=original[index]
            val candidates=(-16..16).map { base*exp(it*ln(4.0)/16.0) }
            val fitted=candidates.minBy { loss(train,it)+2.0*ln(it/base).pow(2) }
            val lower=if(index==0)0.001 else result[index-1]
            val upper=if(index==3)100.0 else original[index+1]
            val bounded=fitted.coerceIn(lower,upper)
            if(loss(validation,bounded)<loss(validation,base))result[index]=bounded
        }
        return result
    }
}
fun StudyRepository.fitInitialMemory():String {
    val samples=mutableListOf<Pair<Long,FirstRecall>>()
    var word="";var firstTime=0L;var rating=0;var taken=false
    db.rawQuery("SELECT word,kind,score,at FROM effective_event WHERE kind IN ('new','review') ORDER BY word,at,id",null).use{c->
        while(c.moveToNext()){
            val id=c.getString(0);val t=c.getLong(3)
            if(word!=id){word=id;firstTime=t;rating=c.getInt(2);taken=c.getString(1)!="new"}
            else if(!taken){
                val days=((t-firstTime)/86400000L).toInt()
                if(days>0 && c.getString(1)=="review")samples.add(t to FirstRecall(rating,days,c.getInt(2)>1))
                taken=true
            }
        }
    }
    val eligible=samples.map{it.second}.groupBy{it.initialRating}.values.any{it.size>=50 && it.count{r->r.recalled}>=5 && it.count{r->!r.recalled}>=5}
    if(!eligible)return "Recorded ${samples.size} delayed first recalls. Fitting needs 50 in one initial rating group, including five successes and five lapses."
    val fitted=Calibration.fit(samples.sortedBy{it.first}.map{it.second})
    set("initialStability",fitted.joinToString(","))
    return "Evaluated ${samples.size} first recalls. Future new cards use fitted starting stability where it improved held-out predictions."
}
