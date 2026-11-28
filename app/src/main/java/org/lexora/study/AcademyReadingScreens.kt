package org.lexora.study

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.ClickableText
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.time.Instant
import java.time.ZoneId

@Composable internal fun ReadingBuilder(d:AcademyData,a:AcademyActions){
    var level by rememberSaveable{mutableStateOf("A1")}
    var topic by rememberSaveable{mutableStateOf("Random topic")}
    var length by rememberSaveable{mutableStateOf("Any")}
    var mix by rememberSaveable{mutableStateOf("Balanced")}
    var known by rememberSaveable{mutableStateOf("60")}
    var weak by rememberSaveable{mutableStateOf("10")}
    var count by rememberSaveable{mutableStateOf("")}
    var query by rememberSaveable{mutableStateOf("")}
    var targets by rememberSaveable{mutableStateOf(arrayListOf<String>())}
    var advanced by rememberSaveable{mutableStateOf(false)}
    var seed by rememberSaveable{mutableIntStateOf(kotlin.random.Random.nextInt())}
    val prefs=ReadingPrefs(level,topic,length,mix,known.toInt(),weak.toInt(),count.toIntOrNull()?:0,targets.toSet())
    val ranked=remember(d.index,prefs,seed,d.history){recommend(d.readings,d.index,prefs,d.history,seed)}
    Page {
        SectionTitle("Reading Academy",a)
        d.lastRead?.let{id->d.readings.find{it.id==id}?.let{p->Secondary("Resume · "+p.title){a.go("Read:"+id)}}}
        Pick("Estimated CEFR",level,levels){level=it}
        Pick("Topic",topic,listOf("Random topic")+readingTopics){topic=it}
        Pick("Length",length,lengthBands.keys.toList()){length=it}
        Pick("Vocabulary",mix,compositions){mix=it}
        TextButton({advanced=!advanced}){Text(if(advanced)"Hide preferences" else "Target words and proportions")}
        if(advanced || mix=="Custom mix" || mix=="Personal targets"){
            if(mix=="Custom mix"){
                Pick("Known %",known,(0..100 step 5).map(Int::toString)){known=it;if(weak.toInt()+known.toInt()>100)weak=(100-known.toInt()).toString()}
                Pick("Weak or missed %",weak,(0..100-known.toInt() step 5).map(Int::toString)){weak=it}
                Hint("Unfamiliar preference · "+(100-known.toInt()-weak.toInt())+"%")
            }
            EntryField("Preferred unfamiliar word count",count){count=it.filter(Char::isDigit).take(3)}
            EntryField("Find a dictionary target",query){query=it}
            val choices=if(query.isBlank())d.index.entries.filter{v->d.inbox.any{it.id==v.word.id}}.take(12)
                else d.index.entries.filter{it.word.lemma.contains(query,true)}.take(12)
            @OptIn(ExperimentalLayoutApi::class)
            FlowRow(horizontalArrangement=Arrangement.spacedBy(6.dp),verticalArrangement=Arrangement.spacedBy(4.dp)){
                for(v in choices){FilterChip(v.word.id in targets,{targets=ArrayList(if(v.word.id in targets)targets-v.word.id else targets+v.word.id)},label={Text(v.word.german)})}
            }
            Hint(targets.size.toString()+" selected targets")
        }
        if(topic=="Random topic"){
            Secondary("Shuffle recommendations"){seed++}
            Secondary("Read a random match",ranked.isNotEmpty()){val pick=ranked.random().p;a.edit({readerPosition(pick.id,d.marks[pick.id]?.paragraph?:0)},{a.go("Read:"+pick.id)})}
        }
        Label("Matching passages · "+ranked.size+" of "+d.readings.size)
        for(hit in ranked){
            val p=hit.p
            Sheet{
                Text(p.title,style=MaterialTheme.typography.titleLarge);Hint(p.level+" · "+p.topic+" · "+p.tokens.size+" words")
                if(hit.misses.isNotEmpty())Hint("Closest available match · "+hit.misses.joinToString(" · "))
                Hint("Dictionary matches · "+hit.known+" familiar / "+hit.identifiable+" distinct entries")
                if(hit.identifiable>0 && hit.matched*2>hit.identifiable)Hint("Many matched words are unfamiliar. You can inspect them as you read.")
                if(targets.isNotEmpty())Hint("Targets present · "+d.index.entries.filter{it.word.id in hit.targets}.joinToString{it.word.lemma}.ifEmpty{"none"})
                Primary("Read "+p.title){a.edit({readerPosition(p.id,d.marks[p.id]?.paragraph?:0)},{a.go("Read:"+p.id)})}
            }
        }
        Hint("LEXORA original texts · content v1 · editorial level estimates, pending independent language review. Offline personalization selects from this library. Online generation is unavailable.")
    }
}
@Suppress("DEPRECATION")
@Composable internal fun Reader(d:AcademyData,a:AcademyActions,voice:Voice,p:Reading){
    val mark=d.marks[p.id];val n=(mark?.paragraph?:0).coerceIn(p.paragraphs.indices)
    val paragraph=p.paragraphs[n]
    var focus by rememberSaveable{mutableStateOf(false)}
    var surface by rememberSaveable{mutableStateOf("")}
    var wordList by rememberSaveable{mutableStateOf(false)}
    var sentence by rememberSaveable{mutableStateOf("")}
    val font=d.prefs["academy.font"]?.toIntOrNull()?.coerceIn(16,28)?:20
    val spacing=d.prefs["academy.spacing"]?.toIntOrNull()?.coerceIn(4,18)?:10
    fun inspect(offset:Int){
        val match=germanToken.findAll(paragraph).firstOrNull{offset in it.range}?:return
        surface=match.value
        val start=paragraph.lastIndexOfAny(charArrayOf('.','?','!'),(match.range.first-1).coerceAtLeast(0))+1
        val end=paragraph.indexOfAny(charArrayOf('.','?','!'),match.range.last+1).let{if(it<0)paragraph.length else it+1}
        sentence=paragraph.substring(start,end).trim()
    }
    Page{
        SectionTitle(p.title,a)
        TextButton({focus=!focus}){Text(if(focus)"Show reading tools" else "Focus mode")}
        if(!focus){
            Hint(p.level+" · "+p.tokens.size+" words · "+p.topic)
            Pick("Text size",font.toString(),listOf("16","18","20","24","28")){a.write{set("academy.font",it)}}
            Pick("Line spacing",spacing.toString(),listOf("4","8","10","14","18")){a.write{set("academy.spacing",it)}}
            Secondary(if(voice.ready)"Listen to paragraph" else "German voice unavailable",voice.ready){voice.playPassage(paragraph.split(Regex("(?<=[.!?])\\s+")),1f)}
        }
        Label("Paragraph "+(n+1)+" of "+p.paragraphs.size);Progress((n+1f)/p.paragraphs.size)
        val display=remember(paragraph,d.inbox,focus){buildAnnotatedString{
            append(paragraph)
            if(!focus)germanToken.findAll(paragraph).forEach{m->if(d.inbox.any{it.name==m.value && !it.archived})addStyle(SpanStyle(color=mint),m.range.first,m.range.last+1)}
        }}
        ClickableText(display,style=MaterialTheme.typography.bodyLarge.copy(color=MaterialTheme.colorScheme.onBackground,fontSize=font.sp,lineHeight=(font+spacing).sp),onClick=::inspect)
        if(!focus){
            TextButton({wordList=!wordList}){Text(if(wordList)"Hide paragraph words" else "Words in this paragraph")}
            if(wordList) @OptIn(ExperimentalLayoutApi::class) FlowRow(horizontalArrangement=Arrangement.spacedBy(6.dp),verticalArrangement=Arrangement.spacedBy(4.dp)){
                germanToken.findAll(paragraph).distinctBy{it.value}.forEach{m->OutlinedButton({inspect(m.range.first)},contentPadding=PaddingValues(horizontal=10.dp,vertical=4.dp)){Text(m.value)}}
            }
            else Hint("Tap any word to inspect or save it.")
        }
        if(n>0)Secondary("Previous paragraph"){a.write{readerPosition(p.id,n-1)}}
        if(n<p.paragraphs.lastIndex)Primary("Next paragraph"){a.write{readerPosition(p.id,n+1)}}
        if(!focus){
            Secondary("Bookmark this paragraph"){a.write{readerPosition(p.id,n,true)}}
            mark?.bookmark?.let{b->TextButton({a.write{readerPosition(p.id,b)}}){Text("Go to bookmark · paragraph "+(b+1))}}
            if(p.grammar.isNotEmpty() && paragraph.contains(p.quote)){
                val l=d.lessons.first{it.id==p.grammar}
                Sheet{Label("Grammar in context");Text(p.quote);Hint(l.explanation);Secondary(l.title+" · "+d.progress.getValue(l.id).status){a.go("Lesson:"+l.id)}}
            }
        }
        Primary("Start comprehension"){a.quiz(p.activity())}
    }
    if(surface.isNotEmpty()){
        val v=d.index.lookup(surface,p);val w=v?.word
        AlertDialog(onDismissRequest={surface=""},title={Text(w?.german?:surface)},text={Column(Modifier.verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(7.dp)){
            if(w!=null){
                Text(w.meaning);Hint(listOf(w.pos,w.level,w.plural.takeIf{it.isNotBlank()}?.let{"Plural "+it},usageScore(w.rank)?.let{"Usage "+it+"/100"}).filterNotNull().joinToString(" · "))
                if(w.grammar.isNotBlank())Hint(w.grammar)
                if(v.fsrs!=null)Hint("FSRS · "+v.fsrs.lowercase())
                if(w.example.isNotBlank())Text(w.example)
                Hint(when{v.due->"FSRS review due";v.weak->"Learning or weak FSRS card";v.known->"Familiar";v.unseen->"Unseen";else->"Selected or learning"})
                Secondary("Pronounce",voice.ready){voice.play(w.german,1f)}
            }else Hint("No unambiguous dictionary match. Save the original form and its sentence.")
            Text(sentence)
            fun collectOnly(label:String){val term=surface;val context=sentence;a.write{val key=collect(term,w?.id,p.id,context,p.topic);if(label.isNotBlank())db.execSQL("UPDATE academy_inbox SET label=? WHERE id=?",arrayOf(label,key))};surface=""}
            Secondary("Save for later"){collectOnly("")};Secondary("Mark unknown"){collectOnly("Unknown")}
            if(w!=null){
                Secondary("I know this"){a.write{reclassify(w.id,4)};surface=""}
                Secondary("Add to today’s learning"){val term=surface;a.write{collect(term,w.id,p.id,sentence,p.topic);enqueue(listOf(w.id))?.let{error(it)}};surface=""}
                Secondary("Full dictionary entry"){surface="";a.detail(w.id)}
                Secondary("Practice word"){surface="";a.edit({
                    check(session()==null){"Resume or finish the current vocabulary session first."}
                    db.delete("undo_step",null,null)
                    save(Session(Mode.Custom,listOf(w.id),Stage.Recognize,limit=1))
                },a.study)}
            }
        }},confirmButton={TextButton({surface=""}){Text("Close")}})
    }
}
@Composable internal fun InboxScreen(d:AcademyData,a:AcademyActions){
    var query by rememberSaveable{mutableStateOf("")};var topic by rememberSaveable{mutableStateOf("All")}
    var archived by rememberSaveable{mutableStateOf(false)};var sort by rememberSaveable{mutableStateOf("Priority")}
    var ids by rememberSaveable{mutableStateOf(arrayListOf<String>())};var limit by rememberSaveable{mutableIntStateOf(35)}
    var editing by rememberSaveable{mutableStateOf("")};var label by rememberSaveable{mutableStateOf("")}
    val visible=d.inbox.filter{it.archived==archived && (topic=="All" || it.topic==topic) && (it.name.contains(query,true) || it.label.contains(query,true))}
        .let{list->when(sort){"Newest"->list.sortedByDescending{it.at};"Alphabetical"->list.sortedBy{it.name};else->list}}
    Page {
        SectionTitle("Unknown Words Inbox",a);Hint(visible.size.toString()+" entries · "+ids.size+" checked")
        EntryField("Search words or labels",query){query=it}
        Pick("Topic",topic,listOf("All")+d.inbox.map{it.topic}.distinct()){topic=it}
        Pick("Sort",sort,listOf("Priority","Newest","Alphabetical")){sort=it}
        TextButton({archived=!archived;ids=arrayListOf()}){Text(if(archived)"Show current inbox" else "Show archived")}
        Secondary("Check visible words"){ids=ArrayList((ids+visible.take(limit).filter{it.id!=null && it.status !in listOf("Learning","FSRS review","Known")}.mapNotNull{it.id}).distinct())}
        if(ids.isNotEmpty()){
            Primary("Study checked words"){a.learn(ids)}
            Secondary("Add checked to learning queue"){val chosen=ids.toList();a.write{enqueue(chosen)?.let{error(it)}};ids=arrayListOf()}
            TextButton({ids=arrayListOf()}){Text("Clear selection")}
        }
        visible.take(limit).forEach{w->Sheet{
            if(w.id!=null)FilterChip(w.id in ids,{ids=ArrayList(if(w.id in ids)ids-w.id else ids+w.id)},label={Text(w.name)}) else Text(w.name)
            Text(w.status+" · "+w.level.ifEmpty{"Unmatched"});Hint(listOf(w.topic,w.label,"Priority "+w.priority).filter(String::isNotEmpty).joinToString(" · "))
            Hint(Instant.ofEpochMilli(w.at).atZone(ZoneId.systemDefault()).toLocalDate().toString()+" · "+(d.readings.find{it.id==w.source}?.title?:w.source))
            if(w.sentence.isNotBlank())Text(w.sentence)
            TextButton({editing=w.key;label=w.label}){Text("Edit entry")}
            if(w.id!=null)TextButton({a.detail(w.id)}){Text("Dictionary")}
        }}
        if(visible.size>limit)Secondary("Show more"){limit+=35}
    }
    d.inbox.find{it.key==editing}?.let{w->
        var lemma by rememberSaveable(w.key){mutableStateOf("")}
        var personal by rememberSaveable(w.key){mutableStateOf(false)}
        var meaning by rememberSaveable(w.key){mutableStateOf("")}
        var pos by rememberSaveable(w.key){mutableStateOf("noun")}
        var level by rememberSaveable(w.key){mutableStateOf("A1")}
        var article by rememberSaveable(w.key){mutableStateOf("der")}
        AlertDialog(onDismissRequest={editing=""},title={Text(w.name)},text={Column(Modifier.verticalScroll(rememberScrollState())){
            EntryField("Personal label",label){label=it}
            Secondary("Save label"){a.write{db.execSQL("UPDATE academy_inbox SET label=? WHERE id=?",arrayOf(label,w.key))};editing=""}
            Secondary(if(w.priority==0)"Raise priority" else "Normal priority"){a.write{db.execSQL("UPDATE academy_inbox SET priority=? WHERE id=?",arrayOf(1-w.priority,w.key))};editing=""}
            Secondary(if(w.archived)"Restore to inbox" else "Archive"){a.write{db.execSQL("UPDATE academy_inbox SET archived=? WHERE id=?",arrayOf(if(w.archived)0 else 1,w.key))};editing="";ids=ArrayList(ids-w.key)}
            if(w.id!=null)Secondary("Mark known"){a.write{reclassify(w.id,4)};editing=""}
            else {
                EntryField("Find the dictionary lemma",lemma){lemma=it}
                if(lemma.isNotBlank())d.index.entries.filter{it.word.lemma.contains(lemma,true)}.take(8).forEach{v->
                    Secondary(v.word.german+" · "+v.word.meaning.take(45)){
                        a.write{resolveInbox(w,v.word.id)};editing=""
                    }
                }
                TextButton({personal=!personal;if(lemma.isBlank())lemma=w.name}){Text("Create a personal dictionary entry")}
                if(personal){
                    Hint("Enter the dictionary form and meaning you have checked.")
                    EntryField("English meaning",meaning){meaning=it}
                    Pick("Part of speech",pos,classes.keys.toList()){pos=it};Pick("Estimated level",level,levels){level=it}
                    if(pos=="noun")Pick("Article",article,listOf("der","die","das")){article=it}
                    Secondary("Save personal word",lemma.isNotBlank() && meaning.isNotBlank()){
                        val fields=listOf(lemma.trim(),meaning,level,pos,if(pos=="noun")article else "","Personal")
                        a.write{
                            require(this.personal(fields)){"This lemma already exists. Choose its dictionary entry above."}
                            val id=requireNotNull(db.str("SELECT id FROM word WHERE lemma=? AND source='Personal'",java.text.Normalizer.normalize(fields[0],java.text.Normalizer.Form.NFC)))
                            resolveInbox(w,id)
                        };editing=""
                    }
                }
            }
        }},confirmButton={TextButton({editing=""}){Text("Close")}})
    }
}
@Composable internal fun AcademyDiscover(d:AcademyData,a:AcademyActions){
    var level by rememberSaveable{mutableStateOf("A1")};var topic by rememberSaveable{mutableStateOf("All")};var group by rememberSaveable{mutableStateOf("Unseen")}
    var count by rememberSaveable{mutableStateOf("10")};var band by rememberSaveable{mutableStateOf("All")};var academic by rememberSaveable{mutableStateOf(false)}
    val results=remember(d.index,level,topic,group,count,band,academic){d.index.entries.filter{it.word.level==level && (!academic || it.academic) && (topic=="All" || it.word.topic==topic) &&
        (band=="All" || it.word.rank in 1..band.toInt()) && when(group){"Weak"->it.weak;"Unknown"->!it.known;else->it.unseen}}.sortedBy{if(it.word.rank>0)it.word.rank else Int.MAX_VALUE}.take(count.toIntOrNull()?.coerceIn(1,500)?:10)}
    Page{
        SectionTitle("Discover vocabulary",a);Pick("Level",level,levels){level=it};Pick("Group",group,listOf("Unseen","Unknown","Weak")){group=it}
        Pick("Topic",topic,listOf("All")+d.index.entries.map{it.word.topic}.distinct().sorted()){topic=it}
        Pick("Frequency rank",band,listOf("All","1000","5000","10000")){band=it}
        FilterChip(academic,{academic=!academic},label={Text("Academic vocabulary")});EntryField("Number of words",count){count=it.filter(Char::isDigit).take(3)}
        Hint(results.size.toString()+" catalog matches")
        if(results.isNotEmpty()){Primary("Save list to inbox"){a.write{results.forEach{v->collect(v.word.lemma,v.word.id,"Discovery",v.word.example,v.word.topic)}}}
            val fresh=results.filter{it.fsrs==null}
            if(fresh.isNotEmpty())Secondary("Study new words in selection"){a.learn(fresh.map{it.word.id})}
            val practice=results.filter{it.fsrs!=null}
            if(practice.isNotEmpty())Secondary("Practice existing cards"){a.edit({
                check(session()==null){"Resume or finish the current vocabulary session first."}
                val ids=practice.map{it.word.id}
                db.delete("undo_step",null,null)
                save(Session(Mode.Custom,ids,Stage.Recognize,limit=ids.size))
            },a.study)}}
        results.forEach{v->Sheet{Text(v.word.german);Hint(v.word.meaning);TextButton({a.detail(v.word.id)}){Text("Dictionary entry")}}}
    }
}
