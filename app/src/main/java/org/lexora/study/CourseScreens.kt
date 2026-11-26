package org.lexora.study

import androidx.activity.compose.BackHandler
import androidx.compose.animation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

@Composable fun UsageIndicator(w:Word){
    var open by rememberSaveable(w.id){mutableStateOf(false)}
    val score=usageScore(w.rank)
    TextButton({open=true},contentPadding=PaddingValues(horizontal=4.dp)){
        Text("Everyday Usage · "+(score?.let{"$it/100"}?:"Unranked"))
    }
    if(open)AlertDialog(onDismissRequest={open=false},title={Text("Everyday usage")},text={
        Text(if(score==null)"Personal entries have no measured corpus rank." else
            "This word ranks "+w.rank+" in the German subtitle frequency data. A higher score means more frequent use in that corpus. Scripted dialogue differs from conversation and formal writing. Your progress does not affect the score.")
    },confirmButton={TextButton({open=false}){Text("Close")}})
}

@Composable fun PreparationGoal(g:GoalCounts){
    val fraction=g.covered.toFloat()/preparationTarget
    Sheet {
        Text("TestDaF TDN 4 — Vocabulary Preparation Goal",style=MaterialTheme.typography.titleLarge)
        Text("${g.covered} / 6,000 covered · ${(fraction.coerceAtMost(1f)*100).roundToInt()}%")
        Progress(fraction)
        Hint("${g.known} previously known · ${g.learned} learned in LEXORA")
        Hint("${g.mastered} estimated mastered · ${g.remaining} remaining")
        Text("Each word counts once. Learned means a Good or Easy rating or a successful new-word retry. Mastery is a separate estimate from scheduled reviews.",style=MaterialTheme.typography.bodySmall)
        Hint("6,000 is your personal preparation target. TestDaF sets no such requirement and this goal cannot guarantee TDN 4.")
    }
}
@Composable fun EditDailyGoal(goal:Int,enabled:Boolean,onSave:(Int)->Unit){
    var open by rememberSaveable{mutableStateOf(false)}
    var value by rememberSaveable(goal){mutableStateOf(goal.toString())}
    TextButton({value=goal.toString();open=true},enabled=enabled){Text("Edit daily target")}
    if(open)AlertDialog(onDismissRequest={open=false},title={Text("Daily vocabulary target")},text={
        OutlinedTextField(value,{value=it},label={Text("Words per day (1–500)")},singleLine=true,keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Number))
    },confirmButton={TextButton({value.toIntOrNull()?.let(onSave);open=false},enabled=value.toIntOrNull() in 1..500){Text("Save target")}},
        dismissButton={TextButton({open=false}){Text("Cancel")}})
}

@Composable fun CourseScreen(state:ScreenState,vm:StudyModel,voice:Voice,pause:()->Unit){
    val s=state.session?:return
    val w=state.word?:return
    val enabled=!state.busy
    val reducedMotion=LocalReducedMotion.current
    var listOpen by rememberSaveable(s.batch,s.stage){mutableStateOf(false)}
    val speechSpeed=remember(state.prefs){state.prefs.getValue("speed").toFloat()}
    val say:(String)->Unit=remember(voice,speechSpeed){{voice.play(it,speechSpeed)}}
    fun act(action:String,value:String=""){vm.action(s,action,value)}
    val picked=remember(state.selected,s.level,s.topic){state.selected.count{it.matches(s.level,s.topic)}}
    val viewStage=if(s.stage==Stage.StudyAll || listOpen)"StudyAll" else s.stage.name
    BackHandler(enabled=listOpen){listOpen=false}
    BackHandler(enabled=!listOpen && s.stage==Stage.Assess && s.index>0){act("previous")}
    AnimatedContent(
        targetState=viewStage,
        modifier=Modifier.fillMaxSize(),
        transitionSpec={
            when{
                targetState=="StudyAll" && initialState!="StudyAll" -> drillTransition(true,reducedMotion)
                initialState=="StudyAll" && targetState!="StudyAll" -> drillTransition(false,reducedMotion)
                else -> stepTransition(reducedMotion)
            }
        },
        label="courseStage"
    ){currentStage->
        when(currentStage){
            "StudyAll"->StudyList(state.studyWords,enabled,listOpen,s.trail.isNotEmpty(),state.prefs.getValue("direction"),
                {vm.preference("direction",it)},say,{vm.detail(it)},{act("previous")},pause){
                if(listOpen)listOpen=false else act("startPractice")
            }
            Stage.DiscoveryEnd.name->Page{
                Heading("Discovery complete")
                Hint("$picked selected for learning")
                if(picked>0)Primary("Learn selected words",enabled){act("learnSelected")}
                Secondary("Previous word",enabled){act("previous")};TextButton({act("finish")}){Text("Save and leave")}
            }
            Stage.RoundResult.name->Page{
                val pending=remember(s.ids,s.completed,s.skipped){s.ids-s.completed.toSet()-s.skipped.toSet()}
                Heading(if(pending.isEmpty())"Session complete" else "Round finished")
                Sheet{Text("Recalled this session · "+s.completed.size);Hint("Known for this session · "+s.skipped.size)
                    Hint("Still to practise · "+pending.size);Hint("Long-term mastery is estimated separately from scheduled reviews.")}
                if(pending.isNotEmpty())Primary("Retry missed or unfinished words",enabled){act("retry")}
                Secondary("Finish session",enabled){act("finish")}
            }
            Stage.Assess.name->AnimatedContent(
                targetState=w.id,
                modifier=Modifier.fillMaxSize(),
                transitionSpec={stepTransition(reducedMotion)},
                label="assessWord"
            ){_->
                Page{
                    Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween){
                        OutlinedButton({act("previous")},enabled=enabled && s.index>0){Text("Previous word")}
                        TextButton(pause){Text("Pause")}
                    }
                    Label("Discovery · word ${s.index+1}")
                    Hint("$picked selected · daily target ${s.limit}")
                    Sheet{
                        Label(w.level+" · "+(classes[w.pos]?:w.pos));Heading(w.german);Text(w.meaning)
                        UsageIndicator(w)
                        TextButton({say(w.german)}){Text("Listen to word")}
                        var details by rememberSaveable{mutableStateOf(false)}
                        TextButton({details=!details}){Text(if(details)"Hide details" else "Grammar and example")}
                        AnimatedVisibility(visible=details,enter=panelEnter(reducedMotion),exit=panelExit(reducedMotion)){
                            Column(verticalArrangement=Arrangement.spacedBy(10.dp)){WordInfo(w,say,false)}
                        }
                    }
                    Label("How familiar is this word?")
                    knowledgeLabels.forEachIndexed{i,label->
                        if(state.currentGrade==i+1)Primary("✓ $label",enabled){act("assess",(i+1).toString())}
                        else Secondary(label,enabled){act("assess",(i+1).toString())}
                    }
                    if(state.currentGrade>0)Primary("Next word",enabled){act("forward")}
                    if(picked>0)Primary("Learn selected words",enabled){act("learnSelected")}
                }
            }
            Stage.Practice.name->AnimatedContent(
                targetState=s.step,
                modifier=Modifier.fillMaxSize(),
                transitionSpec={stepTransition(reducedMotion)},
                label="practiceStep"
            ){step->
                val turn=s.plan.getOrNull(step)?:return@AnimatedContent
                Page{
                    Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween){
                        TextButton({listOpen=true}){Text("Study all words")};TextButton(pause){Text("Pause")}
                    }
                    Progress(step.toFloat()/s.plan.size)
                    Hint("Activity ${step+1} of ${s.plan.size}")
                    PracticeCard(turn,w,state.question,voice.ready,state.prefs.getValue("speed"),enabled,
                        s.reveal,s.feedback,{vm.preference("speed",it)},say,::act)
                    if(turn.kind!=Drill.Bridge && s.mode==Mode.New)TextButton({act("sessionKnown")},enabled=enabled){Text("Known for this session")}
                }
            }
        }
    }
}

@Composable fun StudyList(words:List<Word>,enabled:Boolean,returning:Boolean,canBack:Boolean,direction:String,
    setDirection:(String)->Unit,say:(String)->Unit,edit:(String)->Unit,back:()->Unit,pause:()->Unit,start:()->Unit){
    val button=if(returning)"Return to practice" else "Start practice"
    LazyColumn(Modifier.fillMaxSize(),contentPadding=PaddingValues(18.dp),verticalArrangement=Arrangement.spacedBy(12.dp)){
        item(contentType="header") {
            Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween,verticalAlignment=Alignment.CenterVertically){
                Label("Study · ${words.size} words");TextButton(pause){Text("Pause")}
            }
            Heading("Study all words")
            if(!returning)Pick("Practice direction",direction,practiceDirections,setDirection)
            if(returning || words.size>4)TextButton(start,enabled=enabled){Text(button)}
            if(!returning && canBack)TextButton(back,enabled=enabled){Text("Previous word")}
        }
        items(words,key={it.id},contentType={"word"}){word->Sheet{
            Label(word.level+" · "+(classes[word.pos]?:word.pos))
            Text(word.german,style=MaterialTheme.typography.headlineSmall)
            Text(word.meaning,style=MaterialTheme.typography.titleMedium)
            UsageIndicator(word);TextButton({say(word.german)}){Text("Listen to word")}
            WordInfo(word,say,false)
            if(!returning)TextButton({edit(word.id)},enabled=enabled){Text("Edit assessment")}
        }}
        item(contentType="footer") {Primary(button,enabled,start)}
    }
}

@Composable fun PracticeCard(turn:Turn,w:Word,question:Question?,ready:Boolean,speed:String,enabled:Boolean,
    revealed:Boolean,feedback:String,setSpeed:(String)->Unit,say:(String)->Unit,action:(String,String)->Unit){
    val audio=turn.kind==Drill.Audio && ready && question?.audioSafe==true
    val reverse=turn.reverse && !audio
    val flash=turn.kind==Drill.Flash || turn.kind==Drill.Retry
    val direction=if(reverse)"English → German" else "German → English"
    val reducedMotion=LocalReducedMotion.current
    LaunchedEffect(turn,audio){if(audio && feedback.isEmpty())say(w.german)}
    Label(when {audio->"Audio recognition";turn.kind==Drill.Bridge->"Warm-up · $direction";turn.kind==Drill.Retry->"Later recall · $direction";else->direction})
    if(turn.kind==Drill.Audio && !audio)Hint(if(!ready)
        "German offline voice unavailable. Practise this meaning instead, or install German voice data in Android’s text-to-speech settings."
        else "Using a meaning question because four distinct pronunciations could not be verified.")
    Sheet{
        if(audio){
            Text("Which German word did you hear?",style=MaterialTheme.typography.titleLarge)
            Primary("Replay audio"){say(w.german)}
            Pick("Playback speed",speed,listOf("0.7","0.85","1.0","1.15","1.3"),setSpeed)
        }else{
            Hint(if(reverse)"Recall the German entry you studied for this sense." else "Recall the meaning of the studied sense.")
            Heading(if(reverse)w.meaning else w.german)
            if(reverse && w.cloze.isNotBlank())Text(w.cloze)
            if(!reverse && w.example.isNotBlank())Text(w.example)
        }
    }
    if(flash){
        AnimatedContent(targetState=revealed,transitionSpec={peerScreenTransition(reducedMotion)},label="flashReveal"){isRevealed->
            Column(Modifier.fillMaxWidth(),verticalArrangement=Arrangement.spacedBy(14.dp)){
                if(!isRevealed){
                    var writing by rememberSaveable(turn.word,turn.kind){mutableStateOf(false)}
                    var draft by rememberSaveable(turn.word,turn.kind){mutableStateOf("")}
                    if(writing){
                        OutlinedTextField(draft,{draft=it.take(300)},Modifier.fillMaxWidth(),
                            label={Text(if(reverse)"Your German answer" else "Your English answer")})
                        Primary("Compare answer",enabled && draft.isNotBlank()){action("written",draft)}
                    }else TextButton({writing=true}){Text("Write my answer")}
                    Primary("Reveal answer",enabled){action("reveal","")}
                }else {
                    Sheet{if(feedback.isNotEmpty())Hint("Your answer · "+feedback)
                        Text(if(reverse)w.german else w.meaning,style=MaterialTheme.typography.titleLarge)
                        if(w.example.isNotBlank()){Text(w.example);Hint(w.translation)}
                        if(turn.kind==Drill.Flash)Hint("Rate your own recall. Equivalent wording for this sense counts.")}
                    if(turn.kind==Drill.Retry){
                        Primary("Recalled it",enabled){action("retryGrade","yes")}
                        Secondary("Still difficult",enabled){action("retryGrade","no")}
                    }
                    else listOf("Again","Hard","Good","Easy").forEachIndexed{i,label->Secondary(label,enabled){action("rate",(i+1).toString())}}
                }
            }
        }
    }else{
        AnimatedContent(targetState=feedback.isEmpty(),transitionSpec={peerScreenTransition(reducedMotion)},label="choiceFeedback"){awaitingChoice->
            Column(Modifier.fillMaxWidth(),verticalArrangement=Arrangement.spacedBy(14.dp)){
                if(awaitingChoice){
                    question?.choices?.forEach{option->Secondary(if(audio || reverse)option.german else option.meaning,enabled){action(if(turn.kind==Drill.Audio && !audio)"meaningChoice" else "choice",option.id)}}
                }else{
                    Sheet{Label(feedback);Text(w.german+" · "+w.meaning);if(w.example.isNotBlank()){Text(w.example);Hint(w.translation)}}
                    Primary("Continue",enabled){action("next","")}
                }
            }
        }
    }
}
