package org.lexora.study

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp

@Composable fun DailyLearning(state:ScreenState,vm:StudyModel,resume:()->Unit,begin:(Mode,Filters)->Unit,previewRequested:Boolean,consumePreview:()->Unit,discoverRequested:Boolean,consumeDiscovery:()->Unit){
    var setup by rememberSaveable {mutableStateOf(false)}
    LaunchedEffect(discoverRequested){if(discoverRequested){setup=true;consumeDiscovery()}}
    var preview by rememberSaveable {mutableStateOf(false)}
    LaunchedEffect(previewRequested){if(previewRequested){preview=true;consumePreview()}}
    var level by rememberSaveable(state.prefs["discoveryLevel"]){mutableStateOf(state.prefs["discoveryLevel"].orEmpty().ifEmpty{state.prefs.getValue("level")})}
    var topic by rememberSaveable {mutableStateOf(state.prefs["discoveryTopic"].orEmpty().ifEmpty{"All"})}
    var target by rememberSaveable(state.prefs["goal"]){mutableStateOf(state.prefs.getValue("goal"))}
    val goal=state.prefs.getValue("goal").toInt()
    val available=state.session==null && !state.busy
    Sheet {
        Label("Daily vocabulary")
        Text("${state.goalCounts.today} of $goal words learned today",style=MaterialTheme.typography.titleLarge)
        Progress(state.goalCounts.today.toFloat()/goal)
        Hint("${state.assessedToday} assessed today · ${state.selected.size} awaiting learning")
        Primary("Discover words",available){setup=true}
        Secondary("Learn selected words",available && state.selected.isNotEmpty()){preview=true}
        Pick("Practice direction",state.prefs.getValue("direction"),practiceDirections){vm.preference("direction",it)}
        Secondary("Audio recognition",available){resume();vm.run{startListening()}}
    }
    Secondary("Review due words · ${state.stats.due}",available && state.stats.due>0){begin(Mode.Review,Filters())}
    var focus by rememberSaveable{mutableStateOf("Frequent")}
    Sheet {
        Text("Mistake Lab",style=MaterialTheme.typography.titleLarge)
        Pick("Focus",focus,listOf("Frequent","Recent","Article","Spelling","Capitalization","Umlaut or ß","Translation confusion","Verb form","Difficult reviews")){focus=it}
        Secondary("Open Mistake Lab",available){begin(Mode.Lab,Filters(status=focus))}
    }
    if(setup)AlertDialog(onDismissRequest={setup=false},title={Text("Discover words")},text={
        Column(verticalArrangement=Arrangement.spacedBy(10.dp)){
            Pick("Discover by",level,levels+"Academic"){level=it;topic="All"}
            Pick(if(level=="Academic")"Academic category" else "Topic",topic,listOf("All")+(if(level=="Academic")state.categories.values.distinct().sorted() else state.topics)){topic=it}
            OutlinedTextField(target,{target=it},label={Text("Daily new-word goal (1–500)")},singleLine=true,keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Number))
            Hint("Already known words do not use a place in your goal.")
        }
    },confirmButton={TextButton({val n=target.toInt();setup=false;resume();vm.run{startDiscovery(level,topic,n)}},enabled=target.toIntOrNull() in 1..500){Text("Begin discovery")}},
        dismissButton={TextButton({setup=false}){Text("Cancel")}})
    if(preview){
        var all by rememberSaveable{mutableStateOf(false)}
        val lv=if(all)"All" else level
        val t=if(all)"All" else topic
        val queue=state.selected.filter{it.matches(lv,t)}

        AlertDialog(onDismissRequest={preview=false},title={Text("Your selected words")},text={
            Column {
                Hint("${queue.size} ready for today · "+if(all)"All levels" else "$level · $topic")
                TextButton({all=!all}){Text(if(all)"Use discovery filters" else "Show all levels and topics")}
                LazyColumn(Modifier.heightIn(max=340.dp)){items(queue,key={it.id}){item->
                    ListItem(headlineContent={Text(item.german)},supportingContent={Text(knowledgeLabels[item.grade-1])},
                        trailingContent={TextButton({preview=false;vm.detail(item.id)}){Text("Edit")}})
                }}
            }
        },confirmButton={TextButton({preview=false;resume();vm.run{startSelected(lv,t)}},enabled=queue.isNotEmpty()){Text("Learn selected words")}},
            dismissButton={TextButton({preview=false}){Text("Close")}})
    }
}
