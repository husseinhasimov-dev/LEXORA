package org.lexora.study



data class ExploreShelf(val words:List<Word>,val passages:List<Passage>){
    val byLemma=words.associateBy{it.lemma}
    val byId=words.associateBy{it.id}
    val contexts=words.filter{it.answer.isNotBlank() && it.example.split(Regex("\\s+")).size in 3..(mapOf("A1" to 12,"A2" to 18,"B1" to 26,"B2" to 36)[it.level]?:24) && it.cloze.contains("____") && it.translation.isNotBlank() && it.cloze.replace("____",it.answer)==it.example}
}
data class ExploreProgress(val attempts:List<Attempt>,val familiar:Set<String>,val recent:Set<String>,val selected:Set<String>,val difficult:Set<String>,val decks:Map<String,Set<String>>,val session:String)
fun StudyRepository.exploreProgress():ExploreProgress {
    fun ids(sql:String,vararg args:String)=db.rows(sql,args.toList()){it.getString(0)}.toSet()
    return ExploreProgress(attempts(),ids("SELECT word FROM active_card UNION SELECT word FROM assessment WHERE grade=4"),
        ids("SELECT word FROM effective_event WHERE kind IN ('new','review') GROUP BY word HAVING max(day)>=?",java.time.LocalDate.parse(day()).minusDays(29).toString()),
        ids("SELECT a.word FROM assessment a WHERE a.grade<4 AND EXISTS(SELECT 1 FROM effective_event e WHERE e.kind='assess' AND e.word=a.word AND e.at=a.at AND e.day=?) UNION SELECT substr(k,9) FROM pref WHERE k LIKE 'revisit.%' AND v=?",day(),day()),
        ids("SELECT word FROM effective_event WHERE (kind='answer' AND score=0) OR (kind IN ('new','review') AND score=1) UNION SELECT target FROM exploration WHERE correct=0 AND target IS NOT NULL"),
        db.rows("SELECT name,word FROM deck"){it.getString(0) to it.getString(1)}.groupBy({it.first},{it.second}).mapValues{it.value.toSet()},pref("explore.session"))
}
fun Passage.questions(shelf:ExploreShelf):List<Prompt>{
    val questions=checks.mapIndexed{i,c->Prompt("Comprehension "+(i+1),c[0],c[1],options(c[1],c.drop(2),id+i),spoken=if(kind=="L")text else "")}
    if(kind!="L")return questions
    val highlighted=targets.map{shelf.byLemma.getValue(it)}
    val (first,last)=listOf(highlighted.first(),highlighted.last())
    fun audio(w:Word,gap:Boolean):Prompt{
        val sentence=sentences.first{it.contains(w.lemma)}
        val foils=shelf.words.filter{it.pos==w.pos && it.level==w.level && !text.contains(it.lemma,true) && it.lemma.length>2 && it.ipa.isNotEmpty() && it.ipa!=w.ipa}.distinctBy{it.ipa}.take(3)
        return Prompt(if(gap)"Listening gap" else "Heard word",if(gap)sentence.replace(w.lemma,"____") else "Which word did you hear?",w.lemma,options(w.lemma,foils.map{it.lemma},id+w.id),w.id,sentence,foils.associate{it.lemma to it.id})
    }
    return listOf(audio(first,false))+questions+audio(last,true)
}
fun familyQuestions(a:Word,b:Word,form:String,clue:String,words:List<Word>):List<Prompt>{
    val foils=words.filter{it.pos==b.pos && it.id!=a.id && it.id!=b.id && familyLinks.none{r->it.lemma==r.first || it.lemma==r.second}}.take(3)
    val choices=options(b.lemma,foils.map{it.lemma},a.id)
    return listOf(Prompt("Related word","Which "+(classes[b.pos]?:b.pos).lowercase()+" belongs to the family of "+a.german+"?",b.lemma,choices,b.id),
        Prompt("Word form",clue,form,emptyList(),b.id))
}
val familyExamples=listOf("Die ____ fiel erst am Abend." to "Entscheidung","Die letzte Stimme war ____." to "entscheidend","Nach der ____ gehe ich nach Hause." to "Arbeit","Mein neuer ____ ist nah am Bahnhof." to "Arbeitsplatz","Die ____ erklärt jeden Schritt." to "Beschreibung","Können Sie den Weg genauer ____?" to "beschreiben","Die ____ ist bereits eingegangen." to "Zahlung","Kann ich mit Karte ____?" to "bezahlen","Die ____ hat zwei Zimmer." to "Wohnung","Nach dem ____ beginnt sie im Labor." to "Studium")
fun pairActive(rows:List<Attempt>):Boolean{
    val lastAction=rows.indexOfLast{it.part in listOf("Dismissed","Resolved","Marked")}
    if(lastAction>=0 && rows[lastAction].part!="Marked")return false
    val evidence=rows.drop(lastAction.coerceAtLeast(0))
    val lastError=evidence.indexOfLast{it.mode=="confusion" || !it.ok}
    val repaired=evidence.drop(lastError+1).filter{it.mode=="Confusion Lab" && it.part!="Completed" && it.ok}.map{it.target}.distinct().size>=2
    return !repaired && (rows.any{it.part=="Marked"} || rows.count{it.mode=="confusion" && !it.ok}>=2)
}
