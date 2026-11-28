package org.lexora.study

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.*
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.*
import androidx.compose.ui.window.Dialog
import kotlinx.coroutines.delay

@Composable fun Exercise(state:ScreenState,vm:StudyModel,voice:Voice,pause:()->Unit){
    val s=state.session?:return
    val w=state.word?:return
    if(s.v3){CourseScreen(state,vm,voice,pause);return}
    val enabled=!state.busy
    var typed by rememberSaveable(w.id,s.stage,s.feedback){mutableStateOf("")}
    fun submit(key:String,value:String=""){vm.action(s,key,value)}
    fun say(text:String){voice.play(text,state.prefs.getValue("speed").toFloat())}
    key(w.id,s.stage){Page {
        Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.SpaceBetween){
            Label(s.mode.label);TextButton(pause){Text("Pause")}
        }
        Row(horizontalArrangement=Arrangement.spacedBy(8.dp)){
            OutlinedButton({val step=state.undoId;vm.run{undo(step,true)}},enabled=enabled && state.canBack){Text("Previous word")}
        }
        if(s.stage==Stage.Assess){
            val picked=state.selected.count {it.level==s.level && (s.topic=="All" || it.topic==s.topic)}
            Progress((picked+state.stats.todayNew).toFloat()/s.limit)
            Hint("$picked selected · ${s.inspected} assessed this round · daily goal ${s.limit}")
        }else{
        Progress(s.index.toFloat()/s.limit)
        Hint("Word ${s.index+1} of ${s.limit}  ·  ${s.stage.name}")
        }
        val isWriting=s.stage==Stage.Write || s.stage==Stage.Context
        Sheet {
            Label("${w.level}  /  "+(classes[w.pos]?:w.pos))
            Text(when(s.stage){Stage.Write->w.meaning;Stage.Context->w.cloze;else->w.german},
                fontFamily=FontFamily.Serif,fontSize=32.sp,lineHeight=40.sp)
            if(s.stage==Stage.Teach || s.stage==Stage.Discover || s.reveal){Text(w.meaning,fontSize=21.sp);WordInfo(w,{say(it)},false)}
            if(s.stage==Stage.Assess){
                Text(w.meaning,fontSize=21.sp)
                var more by rememberSaveable(w.id){mutableStateOf(false)}
                TextButton({more=!more}){Text(if(more)"Hide details" else "Grammar and example")}
                if(more)WordInfo(w,{say(it)},false)
            }
            if(s.stage==Stage.Context)Hint(w.translation)
            if(s.stage==Stage.Write){
                Hint("Write the studied "+(classes[w.pos]?:w.pos).lowercase()+" beginning with "+w.lemma.take(1)+"…")
                if(w.cloze.isNotBlank())Text(w.cloze)
            }
            if(!isWriting){TextButton({say(w.german)}){Text("Listen to word")};if(s.stage==Stage.Discover)Hint(voice.status)}
        }
        when(s.stage){
            Stage.StudyAll,Stage.Practice,Stage.DiscoveryEnd,Stage.RoundResult->Unit
            Stage.Assess->{
                Label("How familiar is this word?")
                knowledgeLabels.forEachIndexed {i,label->
                    if(i==0)Primary(label,enabled){submit("assess","1")}
                    else Secondary(label,enabled){submit("assess",(i+1).toString())}
                }
                if(state.selected.isNotEmpty())TextButton({submit("finishDiscovery")},enabled=enabled){Text("Finish discovery early")}
            }
            Stage.Teach->Primary(if(s.index+1==s.limit)"Start flashcards" else "Next word",enabled){submit("teach")}
            Stage.Discover->{Primary("Begin practice",enabled){submit("discover")};Secondary("I already know this",enabled){submit("known")};TextButton({submit("later")},enabled=enabled){Text("Save for another day")}}
            Stage.Recognize->{
                if(s.feedback.isEmpty()){Hint("Choose the English meaning.");state.choices.forEach{Secondary(it,enabled){submit("answer",it)}}}
            }
            Stage.Recall->if(!s.reveal){Hint("Recall the English meaning, then check your answer.");Primary("Reveal meaning",enabled){submit("reveal")}}
                else {Label("How well did you recall it?");listOf("Again","Hard","Good","Easy").forEachIndexed {i,r->Secondary(r,enabled){submit("rate",(i+1).toString())}}}
            Stage.Write,Stage.Context->if(s.feedback.isEmpty()){
                OutlinedTextField(typed,{typed=it},Modifier.fillMaxWidth(),label={Text(if(s.stage==Stage.Context)"Missing German form" else if(w.article.isNotEmpty())"German word with article" else "German word")},singleLine=true)
                Primary("Check answer",enabled && typed.isNotBlank()){submit("answer",typed)}
                TextButton({submit("answer","")},enabled=enabled){Text("Show answer")}
            }
        }
        if(s.feedback.isNotEmpty()){
            Sheet {Text(if(s.passed)"Correct" else s.feedback,color=if(s.passed)mint else amber,fontWeight=FontWeight.Bold)
                Text(when(s.stage){Stage.Recognize->w.meaning;Stage.Context->w.example;else->w.german})
                if(s.stage==Stage.Context)Hint(w.translation)
            if(s.stage==Stage.Write){
                Hint("Write the studied "+(classes[w.pos]?:w.pos).lowercase()+" beginning with "+w.lemma.take(1)+"…")
                if(w.cloze.isNotBlank())Text(w.cloze)
            }
            }
            Primary(if(!s.passed && s.mode==Mode.New)"Try again" else "Continue",enabled){submit("next")}
        }
    }}
}

@Composable fun WordInfo(w:Word,say:(String)->Unit,extended:Boolean){
    if(w.ipa.isNotEmpty())Hint(w.ipa)
    if(w.plural.isNotEmpty())Hint("Plural · ${w.plural}")
    if(w.grammar.isNotEmpty())Hint(w.grammar)
    if(w.example.isNotEmpty()){
        HorizontalDivider();Text(w.example,style=MaterialTheme.typography.bodyLarge);Hint(w.translation)
        TextButton({say(w.example)}){Text("Listen to example")}
    }
    if(extended){
        if(w.synonyms.isNotBlank())Hint("Synonyms · ${w.synonyms}")
        if(w.antonyms.isNotBlank())Hint("Antonyms · ${w.antonyms}")
        Hint("Topic · ${w.topic}")
        if(w.rank>0){Hint("Subtitle corpus rank · ${w.rank}");Hint(if("cefr:anchor" in w.evidence)"Level source · Wordhoard anchor" else "Level source · Wordhoard frequency estimate")}
        Hint(w.source)
    }
}
@Composable fun Library(state:ScreenState,vm:StudyModel,voice:Voice,academic:()->Unit){
    val f=state.filters
    var search by rememberSaveable{mutableStateOf(f.query)}
    var expanded by rememberSaveable{mutableStateOf(false)}
    var add by rememberSaveable{mutableStateOf(false)}
    LaunchedEffect(search){delay(220);if(search!=f.query)vm.filters(f.copy(query=search,page=0))}
    LazyColumn(Modifier.fillMaxSize(),contentPadding=PaddingValues(20.dp),verticalArrangement=Arrangement.spacedBy(8.dp)){
        item { Label("Vocabulary");Heading("Your library");Hint("${state.stats.total} entries on this device") }
        item {Secondary("TestDaF Academic Dictionary",onClick=academic)}
        item {OutlinedTextField(search,{search=it},Modifier.fillMaxWidth(),singleLine=true,label={Text("Search German or English")})}
        item { Row {TextButton({expanded=!expanded}){Text(if(expanded)"Hide filters" else "Filters")};TextButton({add=true}){Text("Add word")} } }
        item {Pick("Sort",f.sort,listOf("Highest usage","Lowest usage","Alphabetical")){vm.filters(f.copy(sort=it,page=0))}}
        if(expanded)item {
            Sheet {
                Pick("Level",f.level,listOf("All")+levels){vm.filters(f.copy(level=it,page=0))}
                Pick("Class",f.pos,listOf("All")+classes.keys){vm.filters(f.copy(pos=it,page=0))}
                Pick("Topic",f.topic,listOf("All")+state.topics){vm.filters(f.copy(topic=it,page=0))}
                Pick("Frequency",f.frequency,listOf("All","Top 1,000","Top 5,000")){vm.filters(f.copy(frequency=it,page=0))}
                Pick("Group",f.status,listOf("All","Unseen","Selected","Learning","Reviewing","Mastered","Known","Missed","Favorites")){vm.filters(f.copy(status=it,page=0))}
                Pick("Deck",f.deck,listOf("All")+state.decks){vm.filters(f.copy(deck=it,page=0))}
                TextButton({search="";vm.filters(Filters())}){Text("Clear filters")}
            }
        }
        items(state.words.take(40),key={it.id}){w->
            Column(Modifier.fillMaxWidth().background(panel,RoundedCornerShape(14.dp)).padding(14.dp)){
                Column(Modifier.fillMaxWidth().clickable {vm.detail(w.id)}.padding(vertical=6.dp)){
                    Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween){Text(w.german,Modifier.weight(1f),fontWeight=FontWeight.Medium,fontSize=18.sp);Label(w.level)}
                    Hint(w.meaning)
                }
                UsageIndicator(w)
            }
        }
        if(state.words.isEmpty())item {Hint("No words match these filters.")}
        item {Row {TextButton({vm.filters(f.copy(page=f.page-1))},enabled=f.page>0){Text("Previous")};TextButton({vm.filters(f.copy(page=f.page+1))},enabled=state.words.size>40){Text("Next page")}}}
    }
    if(add) PersonalEditor(null,{add=false}){fields->vm.run {if(personal(fields)) "Word added." else "This headword already exists."}}
}
@Composable fun WordDetail(state:ScreenState,vm:StudyModel,voice:Voice){
    val w=state.detail?:return
    var deck by rememberSaveable(w.id){mutableStateOf("")};var edit by rememberSaveable(w.id){mutableStateOf(false)}
    val say:(String)->Unit={voice.play(it,state.prefs.getValue("speed").toFloat())}
    Dialog({vm.detail(null)}){
        Surface(color=panel,shape=RoundedCornerShape(20.dp)){Column(Modifier.verticalScroll(rememberScrollState()).padding(20.dp),verticalArrangement=Arrangement.spacedBy(10.dp)){
            Label(w.level+"  /  "+(classes[w.pos]?:w.pos));Heading(w.german);Text(w.meaning);UsageIndicator(w);WordInfo(w,say,true)
            if(state.credit.isNotEmpty())Hint("Example · "+state.credit)
            if(state.relatives.isNotEmpty()){Label("Word Family");state.relatives.forEach{(related,relation)->Secondary(related.german+" · "+related.level){vm.detail(related.id)};Hint(relation+" · "+(classes[related.pos]?:related.pos)+" · "+related.meaning)}}
            val last=state.latestRating
            run {
                Pick("Knowledge",knowledgeLabels.getOrNull(state.detailGrade-1)?:"Unassessed",knowledgeLabels){
                    vm.run{reclassify(w.id,knowledgeLabels.indexOf(it)+1)}
                }
            }
            if(last!=null){
                val ratings=listOf("Again","Hard","Good","Easy")
                Pick("Latest recall",ratings[last.second-1],ratings){vm.run{correctRating(w.id,last.first,ratings.indexOf(it)+1)}}
            }
            
            Secondary("Listen to word"){say(w.german)}
            Secondary(if(state.fav)"Remove favorite" else "Add to favorites",!state.busy){vm.run{toggle(w.id);null}}
            OutlinedTextField(deck,{deck=it},Modifier.fillMaxWidth(),label={Text("Deck name")},singleLine=true)
            TextButton({vm.run{addDeck(deck,w.id);"Added to deck."}},enabled=deck.isNotBlank() && !state.busy){Text("Add to deck")}
            if(w.source=="Personal")TextButton({edit=true}){Text("Edit personal entry")}
            Primary("Done"){vm.detail(null)}
        }}
    }
    if(edit)PersonalEditor(w,{edit=false}){fields->vm.run{if(personal(fields,w.id))"Entry updated." else "This headword already exists."}}
}

@Composable fun PersonalEditor(word:Word?,dismiss:()->Unit,save:(List<String>)->Unit){
    var de by rememberSaveable{mutableStateOf(word?.lemma?:"")};var en by rememberSaveable{mutableStateOf(word?.meaning?:"")}
    var lv by rememberSaveable{mutableStateOf(word?.level?:"A1")};var pos by rememberSaveable{mutableStateOf(word?.pos?:"noun")}
    var art by rememberSaveable{mutableStateOf(word?.article?.ifEmpty{"der"}?:"der")};var topic by rememberSaveable{mutableStateOf(word?.topic?:"Personal")}
    AlertDialog(onDismissRequest=dismiss,title={Text("Personal vocabulary")},text={Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(8.dp)){
        OutlinedTextField(de,{de=it},Modifier.fillMaxWidth(),label={Text("German headword")},singleLine=true);OutlinedTextField(en,{en=it},Modifier.fillMaxWidth(),label={Text("English meaning")})
        Pick("Level",lv,levels){lv=it};Pick("Class",pos,classes.keys.toList()){pos=it};if(pos=="noun")Pick("Article",art,listOf("der","die","das")){art=it}
        OutlinedTextField(topic,{topic=it},Modifier.fillMaxWidth(),label={Text("Topic")},singleLine=true)
    }},confirmButton={TextButton({save(listOf(de,en,lv,pos,if(pos=="noun")art else "",topic));dismiss()},enabled=de.isNotBlank() && en.isNotBlank()){Text("Save")}},
        dismissButton={TextButton(dismiss){Text("Cancel")}})
}
