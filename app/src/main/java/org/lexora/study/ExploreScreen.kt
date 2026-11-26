package org.lexora.study

import androidx.activity.compose.BackHandler
import androidx.compose.animation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.ClickableText
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.dp
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.*
import androidx.compose.ui.text.font.FontWeight
import org.json.JSONObject

private data class ExploreView(val mode:String,val playing:Boolean){
    val depth:Int get()=when{mode.isEmpty()->0;playing->2;else->1}
}
private data class PassageRow(val passage:Passage,val wordCount:Int,val knownCount:Int)

@Composable fun ExploreScreen(state:ScreenState,vm:StudyModel,voice:Voice,close:()->Unit){
    DisposableEffect(vm){vm.explore(true);onDispose{voice.stop();vm.explore(false)}}
    val shelf=state.shelf;val progress=state.explorer
    if(shelf==null || progress==null){
        Box(Modifier.fillMaxSize(),contentAlignment=Alignment.Center){
            CircularProgressIndicator(color=blue,trackColor=panel)
        }
        return
    }
    val reducedMotion=LocalReducedMotion.current
    var mode by rememberSaveable{mutableStateOf("")}
    var detail by rememberSaveable(mode){mutableStateOf("")}
    val session=remember(progress.session){progress.session.takeIf{it.isNotBlank()}?.let(::JSONObject)}
    val playing=session!=null && mode==session.optString("mode") && detail=="practice"
    fun back(){voice.stop();if(detail.isNotEmpty())detail="" else if(mode.isNotEmpty())mode="" else close()}
    BackHandler{back()}
    fun begin(item:String,qs:List<Prompt>){if(!state.busy){vm.run{beginExplore(mode,item,qs);null};detail="practice"}}
    AnimatedContent(
        targetState=ExploreView(mode,playing),
        modifier=Modifier.fillMaxSize(),
        transitionSpec={drillTransition(targetState.depth>=initialState.depth,reducedMotion)},
        label="exploreView"
    ){view->
        Page{
            TextButton({back()}){Text(if(view.playing)"Pause practice" else "Back")}
            Heading(view.mode.ifEmpty{"Practice & Explore"})
            if(view.mode.isEmpty()){
                exploreModes.forEach{Secondary(it){mode=it}}
                return@Page
            }
            if(view.playing){
                val activeSession=session?:return@Page
                ExploreQuiz(activeSession,shelf,vm,voice,state.busy){detail=""}
                return@Page
            }
            if(session!=null){
                Sheet{Text("Saved practice · "+session.getString("mode"));Primary("Resume practice"){mode=session.getString("mode");detail="practice"}
                    TextButton({vm.run{set("explore.session","");null}}){Text("End saved round")}}
            }
            val start:(String,List<Prompt>)->Unit={item,qs->if(session==null)begin(item,qs)}
            val enabled=session==null && !state.busy
            val done=remember(progress.attempts,view.mode){progress.attempts.filter{it.mode==view.mode}}
            if(done.isNotEmpty()){
                val answers=remember(done){done.filter{it.part!="Completed"}}
                Hint("${done.count{it.part=="Completed"}} rounds completed · ${answers.count{it.ok}} / ${answers.size} answers correct")
            }
            when(view.mode){
                "Context Mastery"->ContextBrowser(shelf,progress,state,enabled,start)
                "Confusion Lab"->ConfusionBrowser(shelf,progress,vm,enabled,start)
                "Word Families"->familyLinks.forEachIndexed{i,(from,to,note)->
                    val a=shelf.byLemma.getValue(from);val b=shelf.byLemma.getValue(to)
                    Sheet{Label(note);TextButton({vm.detail(a.id)}){Text(a.german)};TextButton({vm.detail(b.id)}){Text("↳ "+b.german)}
                        Hint(b.meaning);Secondary("Practise this family",enabled){val (clue,key)=familyExamples[i];start(a.id+"|"+b.id,familyQuestions(a,b,key,clue,shelf.words))}}
                }
                else->PassageBrowser(view.mode,shelf,progress,voice,vm,enabled,start)
            }
        }
    }
}

@Composable private fun ContextBrowser(shelf:ExploreShelf,p:ExploreProgress,state:ScreenState,enabled:Boolean,start:(String,List<Prompt>)->Unit){
    var level by rememberSaveable{mutableStateOf("All")}
    var pool by rememberSaveable{mutableStateOf("All contexts")}
    var search by rememberSaveable{mutableStateOf("")}
    val filters=remember(p.decks){listOf("All contexts","Selected today","Recent study","Difficult words","Academic")+p.decks.keys.map{"Deck · "+it}}
    val levelOptions=remember{listOf("All")+levels}
    Pick("Words",pool,filters){pool=it};Pick("Level",level,levelOptions){level=it}
    OutlinedTextField(search,{search=it},label={Text("Find a German word")},singleLine=true)
    val scores=remember(p.attempts){p.attempts.filter{it.mode=="Context Mastery" && it.target!=null}.groupBy{it.target}}
    val ids=remember(pool,p,state.categories){
        when(pool){"Selected today"->p.selected;"Recent study"->p.recent;"Difficult words"->p.difficult;"Academic"->state.categories.keys;"All contexts"->null;else->p.decks[pool.removePrefix("Deck · ")].orEmpty()}
    }
    val filtered=remember(pool,level,search,p,ids){shelf.contexts.filter{(ids==null || it.id in ids) && (level=="All" || it.level==level) && it.lemma.contains(search,true)}
        .sortedBy{w->val history=scores[w.id].orEmpty();if(history.lastOrNull()?.ok==false)-1 else history.size}}
    var page by rememberSaveable(pool,level,search){mutableIntStateOf(0)}
    Hint("${filtered.size} words with a bilingual sentence and a marked answer")
    filtered.drop(page*10).take(10).forEach{w->Secondary(w.german+" · "+w.level,enabled){
        val hard=scores[w.id].orEmpty().takeLast(4).count{it.ok}>=3
        start(w.id,contextPrompts(w,shelf.contexts.filter{it.level==w.level && it.pos==w.pos}+shelf.contexts,hard))}}
    if(page>0)TextButton({page--}){Text("Previous page")}
    if((page+1)*10<filtered.size)TextButton({page++}){Text("Next page")}
}

@Composable private fun ConfusionBrowser(shelf:ExploreShelf,p:ExploreProgress,vm:StudyModel,enabled:Boolean,start:(String,List<Prompt>)->Unit){
    var first by rememberSaveable{mutableStateOf("")};var second by rememberSaveable{mutableStateOf("")}
    OutlinedTextField(first,{first=it},label={Text("First German headword")},singleLine=true)
    OutlinedTextField(second,{second=it},label={Text("Second German headword")},singleLine=true)
    val a=shelf.byLemma[first.trim()];val b=shelf.byLemma[second.trim()]
    Secondary("Mark pair",a!=null && b!=null && a!=b){if(a!=null && b!=null)vm.run{recordExplore("confusion",pairKey(a.id,b.id),"Marked",a.id,b.id,"",true);null}}
    Hint("Pairs appear after two observed mix-ups or a manual mark. Try wissen and kennen.")
    val repeatedSpelling=remember(p.attempts){p.attempts.filter{it.mode=="spelling"}.groupBy{it.target}.filterValues{it.size>=2}}
    repeatedSpelling.forEach{(id,errors)->Hint("Repeated spelling · "+shelf.byId[id]?.german+" · "+errors.last().answer)}
    val sortedGroups=remember(p.attempts){
        val groups=p.attempts.filter{it.mode=="confusion" || it.mode=="Confusion Lab"}.groupBy{it.item}.filterValues(::pairActive)
        groups.entries.sortedWith(compareByDescending<Map.Entry<String,List<Attempt>>>{it.value.count{e->!e.ok}}.thenByDescending{it.value.last().at})
    }
    sortedGroups.forEach{(key,rows)->
        val pair=key.split("|").mapNotNull{shelf.byId[it]}
        if(pair.size==2)Sheet{
            val(x,y)=pair;Heading(x.lemma+" / "+y.lemma)
            Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(12.dp)){
                pair.forEach{w->Column(Modifier.weight(1f)){Text(w.german);Label(classes[w.pos]?:w.pos);Text(w.meaning)
                    if(w.grammar.isNotBlank())Hint(w.grammar)}}
            }
            pair.forEach{w->if(w.example.isNotBlank()){Text(w.example);Hint(w.translation)}else Hint("No bilingual example for "+w.german)}
            contrastNotes[setOf(x.lemma,y.lemma)]?.let{Hint(it)}
            val errors=rows.filter{!it.ok};Hint("${errors.size} mix-ups · ${rows.count{it.mode=="Confusion Lab" && it.ok && it.part!="Completed"}} correct repairs")
            errors.lastOrNull()?.let{Hint(it.part+" · "+java.time.Instant.ofEpochMilli(it.at).atZone(java.time.ZoneId.systemDefault()).toLocalDate())}
            Secondary("Compare in practice",enabled){start(key,pair.map{w->
                val guided=if(setOf(x.lemma,y.lemma) !in contrastNotes.keys)null else when(w.lemma){"wissen"->"Ich möchte ____, wann der Bus fährt.";"kennen"->"Wir ____ unsere neuen Nachbarn schon persönlich.";"lernen"->"Die Kinder ____ gerade das Einmaleins.";"studieren"->"Nach dem Abitur will sie an der Universität Medizin ____.";else->null}
                val prompt=guided ?: if(w.cloze.contains("____"))w.cloze+"\n"+w.translation else w.meaning
                Prompt("Contrast","Choose the headword for this sense.\n"+prompt,w.lemma,options(w.lemma,pair.map{it.lemma},w.id),w.id,ids=pair.associate{it.lemma to it.id})})}
            Row{listOf("Resolved","Dismissed").forEach{action->TextButton({vm.run{recordExplore("confusion",key,action,x.id,y.id,"",true);null}}){Text(if(action=="Resolved")"Resolve" else "Dismiss")}}}
        }
    }
}

@Composable private fun PassageBrowser(mode:String,shelf:ExploreShelf,p:ExploreProgress,voice:Voice,vm:StudyModel,enabled:Boolean,start:(String,List<Prompt>)->Unit){
    val listening=mode=="Listening in Context"
    val reducedMotion=LocalReducedMotion.current
    var level by rememberSaveable(mode){mutableStateOf("All")};var topic by rememberSaveable(mode){mutableStateOf("All")}
    var length by rememberSaveable(mode){mutableStateOf("All")};var group by rememberSaveable(mode){mutableStateOf("All")}
    var familiar by rememberSaveable(mode){mutableStateOf("All")};var open by rememberSaveable(mode){mutableStateOf("")}
    val entries=remember(shelf.passages,listening){shelf.passages.filter{(it.kind=="L")==listening}}
    val passage=remember(entries,open){entries.find{it.id==open}}
    AnimatedContent(
        targetState=passage!=null,
        transitionSpec={drillTransition(targetState,reducedMotion)},
        label="passageDetail"
    ){showingPassage->
        Column(Modifier.fillMaxWidth(),verticalArrangement=Arrangement.spacedBy(14.dp)){
            if(showingPassage){
                val current=passage?:return@Column
                TextButton({open=""}){Text("Back to collection")};Heading(current.title)
                if(!listening)ReadPassage(current,shelf,vm) else {Hint("German synthetic speech · "+voice.status);Secondary("Play passage"){voice.playPassage(current.sentences,.92f)};Transport(voice)}
                Primary(if(listening)"Start listening" else "Check comprehension",enabled && (!listening || voice.ready)){start(current.id,current.questions(shelf))}
                if(listening && !voice.ready)Hint("Install an offline German voice in Android’s Text-to-speech settings.")
                return@Column
            }
            val levelOptions=remember{listOf("All")+levels}
            val topicOptions=remember(entries){listOf("All")+entries.map{it.topic}.distinct()}
            Pick("Level",level,levelOptions){level=it};Pick("Topic",topic,topicOptions){topic=it}
            Pick("Category",group,listOf("All","General","Academic")){group=it}
            Pick("Length",length,listOf("All","Under 45 words","45 words or more")){length=it}
            Pick("Familiar vocabulary",familiar,listOf("All","At least one familiar target","New targets only")){familiar=it}
            val attemptsByItem=remember(p.attempts,mode){
                p.attempts.filter{it.mode==mode && it.part!="Completed"}.groupBy{it.item}
            }
            val sortedRows=remember(entries,level,topic,group,length,familiar,p.familiar,shelf){
                entries.map{x->
                    PassageRow(x,x.text.split(" ").size,x.targets.count{shelf.byLemma[it]?.id in p.familiar})
                }.filter{row->
                    val x=row.passage
                    (level=="All" || x.level==level) && (topic=="All" || x.topic==topic) && (group=="All" || (group=="Academic")==x.academic) &&
                        (length=="All" || (length=="Under 45 words")==(row.wordCount<45)) && (familiar=="All" || (familiar=="At least one familiar target")==(row.knownCount>0))
                }.sortedWith(compareByDescending<PassageRow>{it.knownCount==1}.thenByDescending{it.knownCount})
            }
            sortedRows.forEach{row->
                val x=row.passage
                val answers=attemptsByItem[x.id].orEmpty()
                Secondary(x.title){open=x.id};Hint(x.level+" · "+x.topic+" · "+row.wordCount+" words · "+row.knownCount+"/"+x.targets.size+" familiar targets")
                if(answers.isNotEmpty())Hint("${answers.count{it.ok}} / ${answers.size} correct so far")
            }
            if(sortedRows.isEmpty())Hint("No passages match these filters.")
            if(listening)levels.forEach{lv->
                val ids=remember(entries,lv){entries.filter{it.level==lv}.mapTo(HashSet()){it.id}}
                val answers=remember(p.attempts,mode,ids){p.attempts.filter{it.mode==mode && it.item in ids && it.part!="Completed"}}
                if(answers.isNotEmpty()){
                    Hint(lv+" · "+answers.count{it.ok}+" / "+answers.size+" correct")
                    val latestWords=answers.filter{it.target!=null}.associateBy{it.target}
                    listOf(true to "Recognized",false to "Practise again").forEach{(correct,label)->
                        val words=latestWords.values.filter{it.ok==correct}.mapNotNull{shelf.byId[it.target]?.german}
                        if(words.isNotEmpty())Hint(label+" · "+words.joinToString(", "))
                    }
                    answers.takeLast(4).reversed().forEach{a->Hint(java.time.Instant.ofEpochMilli(a.at).atZone(java.time.ZoneId.systemDefault()).toLocalDate().toString()+" · "+(shelf.byId[a.target]?.german?:a.part)+" · "+if(a.ok)"Correct" else "Practise again")}
                }
            }
        }
    }
}

@Composable private fun Transport(voice:Voice){
    if(voice.passagePlaying)TextButton(voice::pausePassage){Text("Pause audio")}
    if(voice.passagePaused)TextButton({voice.resumePassage()}){Text("Resume from this sentence")}
}
@Composable private fun ReadPassage(x:Passage,shelf:ExploreShelf,vm:StudyModel){
    val annotated=remember(x.id,x.marked,shelf){
        buildAnnotatedString{var cursor=0
            markedWord.findAll(x.marked).forEach{m->append(x.marked.substring(cursor,m.range.first));val w=shelf.byLemma.getValue(m.groupValues[1])
                pushStringAnnotation("word",w.id);withStyle(SpanStyle(color=mint,fontWeight=FontWeight.Bold)){append(w.lemma)};pop();cursor=m.range.last+1}
            append(x.marked.substring(cursor))}
    }
    ClickableText(annotated,style=MaterialTheme.typography.bodyLarge.copy(color=MaterialTheme.colorScheme.onSurface)){i->annotated.getStringAnnotations("word",i,i).firstOrNull()?.let{vm.detail(it.item)}}
    Hint(x.english)
    x.targets.forEach{lemma->val w=shelf.byLemma.getValue(lemma)
        TextButton({vm.detail(w.id)}){Text(w.german+" · "+w.meaning.substringBefore(";"))}
        Secondary("Save "+lemma+" for study"){vm.run{
            if(db.long("SELECT count(*) FROM active_card WHERE word=?",w.id)>0)set("revisit."+w.id,day()) else if(grade(w.id) !in 1..3)reclassify(w.id,1)
            "Saved to your learning list."}}
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable private fun ExploreQuiz(j:JSONObject,shelf:ExploreShelf,vm:StudyModel,voice:Voice,busy:Boolean,finish:()->Unit){
    val reducedMotion=LocalReducedMotion.current
    val step=j.getInt("step");val qs=j.getJSONArray("qs");val id=j.getString("id");val item=j.getString("item")
    val passage=remember(shelf.passages,item){shelf.passages.find{it.id==item}}
    AnimatedContent(
        targetState=step,
        transitionSpec={stepTransition(reducedMotion)},
        label="exploreStep"
    ){currentStep->
        Column(Modifier.fillMaxWidth(),verticalArrangement=Arrangement.spacedBy(14.dp)){
            if(currentStep>=qs.length()){
                Heading("Round complete")
                val answers=j.optJSONArray("responses")
                val missed=remember(id,currentStep,j){
                    (0 until qs.length()).mapNotNull{i->val q=Prompt.read(qs.getJSONObject(i));val response=answers?.optString(i).orEmpty()
                        if(q.accepts(response))null else q to response}
                }
                Hint("${qs.length()-missed.size} / ${qs.length()} correct")
                missed.forEach{(q,response)->Sheet{Text(q.prompt);Hint("Your answer · "+response);Text("Reference · "+q.key)}}
                if(missed.isNotEmpty())Secondary("Retry missed questions",!busy){vm.run{set("explore.session","");beginExplore(j.getString("mode"),item,missed.map{it.first});null}}
                Primary("Finish"){vm.run{set("explore.session","");null};finish()};return@Column
            }
            val q=remember(id,currentStep,qs){Prompt.read(qs.getJSONObject(currentStep))};val answered=j.has("feedback")
            var typed by rememberSaveable(id,currentStep){mutableStateOf("")};var indexes by rememberSaveable(id,currentStep){mutableStateOf("")}
            var transcript by rememberSaveable(id,currentStep){mutableStateOf(false)}
            val used=remember(indexes){indexes.split(",").mapNotNull{it.toIntOrNull()}}
            fun submit(value:String){vm.run{answerExplore(id,currentStep,value);null}}
            Label(q.part+" · "+(currentStep+1)+" / "+qs.length());Text(q.prompt)
            if(q.spoken.isNotEmpty()){
                val lines=if(passage?.text==q.spoken)passage.sentences else listOf(q.spoken)
                Secondary("Play or replay"){voice.playPassage(lines,1f)};Secondary("Play slowly"){voice.playPassage(lines,.75f)}
                Transport(voice)
                if(lines.size>1)lines.forEachIndexed{i,line->TextButton({voice.play(line,.85f)}){Text("Replay sentence "+(i+1))}}
                if(!voice.ready)Hint("Install an offline German voice in Android Text-to-speech settings.")
            }
            if(answered){
                val reply=j.getString("feedback");Text(if(q.accepts(reply))"Correct" else "Your answer: "+reply+"\nReference: "+q.key)
                Primary("Continue",!busy){voice.stop();vm.run{nextExplore(id,currentStep);null}}
            }else if(q.part=="Construction"){
                Text(used.joinToString(" "){q.choices[it]})
                FlowRow(horizontalArrangement=Arrangement.spacedBy(6.dp)){q.choices.forEachIndexed{i,token->
                    if(i !in used)OutlinedButton({indexes=(used+i).joinToString(",")}){Text(token)}
                }}
                TextButton({indexes=used.dropLast(1).joinToString(",")}){Text("Remove last word")}
                Primary("Check sequence",used.size==q.choices.size && !busy){submit(used.joinToString(" "){q.choices[it]})}
            }else if(q.choices.isEmpty()){
                OutlinedTextField(typed,{typed=it},label={Text("German answer")});Primary("Check answer",typed.isNotBlank() && !busy){submit(typed)}
            }else q.choices.forEach{Secondary(it,!busy && (q.spoken.isEmpty() || voice.ready)){submit(it)}}
            if(passage!=null && (passage.kind=="R" || answered)){
                TextButton({transcript=!transcript}){Text(if(passage.kind=="L")"Study transcript" else "Reread story")}
                AnimatedVisibility(visible=transcript,enter=panelEnter(reducedMotion),exit=panelExit(reducedMotion)){
                    Column(verticalArrangement=Arrangement.spacedBy(10.dp)){
                        ReadPassage(passage,shelf,vm)
                        if(passage.kind=="L")passage.sentences.forEach{line->TextButton({voice.play(line,.85f)}){Text("▶ "+line)}}
                    }
                }
            }
        }
    }
}
