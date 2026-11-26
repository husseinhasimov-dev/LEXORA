package org.lexora.study

import android.app.DatePickerDialog
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import java.time.LocalDate

@Composable fun AcademicScreen(state:ScreenState,vm:StudyModel,voice:Voice,back:()->Unit){
    var q by rememberSaveable{mutableStateOf("")}
    var cat by rememberSaveable{mutableStateOf("All")}
    val result=state.academic.filter{(cat=="All" || state.categories[it.id]==cat) &&
        (it.german.contains(q.trim(),true) || it.meaning.contains(q.trim(),true))}
    val list=rememberLazyListState()
    LaunchedEffect(q,cat){list.scrollToItem(0)}
    LazyColumn(state=list,modifier=Modifier.fillMaxSize(),contentPadding=PaddingValues(18.dp),verticalArrangement=Arrangement.spacedBy(12.dp)){
        item{TextButton(back){Text("Back to library")};Heading("TestDaF Academic Dictionary")
            Hint("${state.academic.size} selected entries for university study and exam preparation.")}
        item{OutlinedTextField(q,{q=it},Modifier.fillMaxWidth(),label={Text("Search academic German or English")},singleLine=true)}
        item{Pick("Category",cat,listOf("All")+state.categories.values.distinct().sorted()){cat=it};Hint("${result.size} entries")}
        items(result,key={it.id}){w->Sheet{
            Label(state.categories[w.id].orEmpty());Text(w.german,style=MaterialTheme.typography.headlineSmall);Text(w.meaning)
            Text((classes[w.pos]?:w.pos)+" · "+w.level)
            WordInfo(w,{voice.play(it,state.prefs.getValue("speed").toFloat())},false)
            UsageIndicator(w)
            TextButton({voice.play(w.german,state.prefs.getValue("speed").toFloat())}){Text("Listen to word")}
            val queued=state.selected.any{it.id==w.id}
            Secondary(if(queued)"In my learning list" else "Add to my learning list",!state.busy && !queued){vm.run{addAcademic(w.id)}}
            TextButton({vm.detail(w.id)}){Text("Word details and progress")}
        }}
    }
}
@Composable fun ReviewChooser(close:()->Unit,start:(String,String,Int,String,String)->Unit){
    val ctx=LocalContext.current
    var range by rememberSaveable{mutableStateOf(reviewRanges.first())}
    var order by rememberSaveable{mutableStateOf(reviewOrders.first())}
    var size by rememberSaveable{mutableStateOf("10")}
    var first by rememberSaveable{mutableStateOf(LocalDate.now().minusDays(6).toString())}
    var last by rememberSaveable{mutableStateOf(LocalDate.now().toString())}
    fun date(value:String,change:(String)->Unit){
        val d=LocalDate.parse(value)
        DatePickerDialog(ctx,{_,y,m,dd->change(LocalDate.of(y,m+1,dd).toString())},d.year,d.monthValue-1,d.dayOfMonth).show()
    }
    AlertDialog(onDismissRequest=close,title={Text("Review words")},text={Column(Modifier.verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(8.dp)){
        Hint("Dates refer to a word’s first saved FSRS rating. These extra practice rounds leave its due date unchanged.")
        Pick("First studied",range,reviewRanges){range=it}
        if(range=="Custom date range"){
            Secondary("From · "+first){date(first){first=it}};Secondary("Through · "+last){date(last){last=it}}
            if(first>last)Hint("Choose an end date on or after the start.")
        }
        Pick("Order",order,reviewOrders){order=it};Pick("Session size",size,listOf("1","5","10","15","20","30","50","100")){size=it}
    }},confirmButton={TextButton({start(range,order,size.toInt(),first,last);close()},enabled=range!="Custom date range" || first<=last){Text("Start review")}},dismissButton={TextButton(close){Text("Cancel")}})
}
