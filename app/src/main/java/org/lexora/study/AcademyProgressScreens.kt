package org.lexora.study

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import java.time.LocalDate
import java.time.DayOfWeek
import java.time.format.TextStyle
import java.util.Locale
import java.util.UUID

@Composable internal fun QuestsScreen(d:AcademyData,a:AcademyActions){
    var editor by remember{mutableStateOf<Quest?>(null)}
    var settings by rememberSaveable{mutableStateOf(false)}
    val required=d.quests.filterNot{it.first.bonus}
    Page{
        SectionTitle("Daily quests",a)
        Hint(d.today+" · "+required.count{it.second>=it.first.target}+" / "+required.size+" planned tasks")
        if(required.isEmpty())Hint("No required tasks today. Extra learning is available.")
        else{
            val skills=required.groupBy{it.first.skill}
            Hint("Planned skills completed · "+skills.values.count{group->group.all{it.second>=it.first.target}}+" / "+skills.size)
        }
        val bonus=d.quests.filter{it.first.bonus}
        if(bonus.isNotEmpty())Hint("Bonus tasks · "+bonus.count{it.second>=it.first.target}+" / "+bonus.size)
        Hint("Graded answers · "+d.daily.correct+" / "+d.daily.attempted+" · Scheduled reviews completed · "+d.daily.reviews)
        Hint("Recorded question interaction time · "+d.daily.interactionMs/1000+" seconds")
        d.quests.forEach{(q,n)->Sheet{
            Text(q.title,style=MaterialTheme.typography.titleMedium);Hint(q.skill+" · "+q.metric+(if(q.bonus)" · Bonus" else "")+(if(q.recurring)" · Daily" else ""))
            Progress((n.toFloat()/q.target).coerceAtMost(1f));Text(n.toString()+" / "+q.target)
            if(q.metric=="Self-reported" && n<q.target)Secondary("Log one completion"){a.write{logQuest(q)}}
            else if(n<q.target)Secondary("Open practice"){when(q.skill){"Reading"->a.go("Reading");"Grammar"->a.go("Grammar");"Listening"->a.explore();else->if(q.metric=="Scheduled reviews")a.review() else a.go("Discover")}}
            TextButton({editor=q}){Text("Edit task")}
        }}
        Secondary("Create task"){editor=Quest(UUID.randomUUID().toString(),"","Speaking","Self-reported",1,d.today)}
        Secondary("Weekly study schedule"){settings=true}
        Label("Continue learning");Secondary("More reading"){a.go("Reading")};Secondary("Extra grammar"){a.go("Grammar")}
        Secondary("Discover vocabulary"){a.go("Discover")};Secondary("Due reviews · "+d.due,d.due>0,onClick=a.review)
    }
    editor?.let{q->
        var title by rememberSaveable(q.id){mutableStateOf(q.title)};var skill by rememberSaveable(q.id){mutableStateOf(q.skill)}
        var metric by rememberSaveable(q.id){mutableStateOf(q.metric)};var target by rememberSaveable(q.id){mutableStateOf(q.target.toString())}
        var date by rememberSaveable(q.id){mutableStateOf(q.date)};var recurring by rememberSaveable(q.id){mutableStateOf(q.recurring)};var bonus by rememberSaveable(q.id){mutableStateOf(q.bonus)}
        AlertDialog(onDismissRequest={editor=null},title={Text("Daily task")},text={Column(Modifier.verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(5.dp)){
            EntryField("Task name",title){title=it};Pick("Skill",skill,academySkills){skill=it;metric=(questMetrics.getValue(it)+ "Self-reported").first()}
            Pick("Measure",metric,questMetrics.getValue(skill)+"Self-reported"){metric=it};EntryField("Number of completions",target){target=it}
            EntryField("Start date · YYYY-MM-DD",date){date=it}
            FilterChip(recurring,{recurring=!recurring},label={Text("Repeat every day")})
            FilterChip(bonus,{bonus=!bonus},label={Text("Bonus task")})
            if(q.title.isNotEmpty())TextButton({a.write{set("academy.hide."+q.id,"true")};editor=null}){Text("Remove task")}
        }},confirmButton={TextButton({a.write{saveQuest(q.copy(title=title,skill=skill,metric=metric,target=target.toInt(),date=date,recurring=recurring,bonus=bonus))};editor=null},
            enabled=title.isNotBlank() && target.toIntOrNull() in 1..600 && runCatching{LocalDate.parse(date)}.isSuccess){Text("Save task")}},dismissButton={TextButton({editor=null}){Text("Cancel")}})
    }
    if(settings){
        var days by rememberSaveable{mutableStateOf(d.prefs["academy.days"]?:"1,2,3,4,5,6,7")};var weekly by rememberSaveable{mutableStateOf(d.prefs["academy.weekly"]?:"150")}
        var exam by rememberSaveable{mutableStateOf(d.prefs["academy.exam"].orEmpty())}
        var focus by rememberSaveable{mutableStateOf(d.prefs["academy.focus"].orEmpty())}
        AlertDialog(onDismissRequest={settings=false},title={Text("Weekly study schedule")},text={Column(Modifier.verticalScroll(rememberScrollState())){
            EntryField("Weekly study minutes",weekly){weekly=it};EntryField("Digital TestDaF date · YYYY-MM-DD",exam){exam=it}
            Hint("Target · TDN 4 in reading, listening, speaking and writing. Select your study days.")
            Hint("Additional skills for longer days or the final four weeks before the exam")
            listOf("Listening","Speaking","Writing").forEach{s->
                FilterChip(s in focus.split(","),{focus=(if(s in focus.split(","))focus.split(",")-s else focus.split(",")+s).filter(String::isNotEmpty).joinToString(",")},label={Text(s)})
            }
            DayOfWeek.values().forEach{day->val id=day.value.toString();val enabled=id in days.split(",")
                FilterChip(enabled,{days=(if(enabled)days.split(",")-id else days.split(",")+id).filter(String::isNotEmpty).distinct().sorted().joinToString(",")},label={Text(day.getDisplayName(TextStyle.FULL,Locale.ENGLISH))})}
        }},confirmButton={TextButton({a.write{set("academy.weekly",weekly);set("academy.days",days.ifEmpty{"rest"});set("academy.exam",exam);set("academy.focus",focus);set("academy.plan."+day(),"")};settings=false},
            enabled=weekly.toIntOrNull() in 1..3000 && (exam.isBlank() || runCatching{LocalDate.parse(exam)}.isSuccess)){Text("Save schedule")}},dismissButton={TextButton({settings=false}){Text("Cancel")}})
    }
}
@Composable internal fun JournalScreen(d:AcademyData,a:AcademyActions,from:String,to:String,range:(String,String)->Unit){
    var period by rememberSaveable{mutableStateOf("Week")};var note by rememberSaveable{mutableStateOf("")};var limit by rememberSaveable{mutableIntStateOf(40)}
    val today=LocalDate.parse(d.today)
    fun show(date:LocalDate,p:String){
        val start=when(p){"Day"->date;"Week"->date.minusDays(date.dayOfWeek.value-1L);else->date.withDayOfMonth(1)}
        val end=when(p){"Day"->start;"Week"->start.plusDays(6);else->start.plusMonths(1).minusDays(1)}
        range(start.toString(),end.toString());limit=40
    }
    LaunchedEffect(Unit){show(LocalDate.parse(to),period)}
    Page{
        SectionTitle("Learning journal",a);Text(d.xp.toString()+" total XP");Pick("Period",period,listOf("Day","Week","Month")){period=it;show(today,it)}
        Text(from+" — "+to)
        Row{TextButton({show(LocalDate.parse(from).minusDays(1),period)}){Text("Earlier")};TextButton({show(today,period)}){Text("Current")};TextButton({show(LocalDate.parse(to).plusDays(1),period)}){Text("Later")}}
        val active=d.journal.sumOf{it.activeMs?:0}
        Hint("Recorded question interaction time · "+active/1000+" seconds")
        if(d.badges.isNotEmpty())Sheet{Label("Achievements");d.badges.forEach{Text(it)}}
        EntryField("Personal note",note){note=it};Secondary("Save note",note.isNotBlank()){val msg=note;a.write{record(Evidence(UUID.randomUUID().toString(),"Journal","Personal note",msg,method="self-reported"))};note=""}
        if(d.journal.isEmpty())Hint("No recorded activity in this period.")
        d.journal.take(limit).forEach{e->Sheet{
            Label(e.day+" · "+e.skill);Text(e.activity+" · "+(d.lessons.find{it.id==e.content}?.title?:d.readings.find{it.id==e.content}?.title?:e.content))
            Hint(e.method+(e.attempted?.let{" · "+e.correct+"/"+it}.orEmpty()))
            if(e.activity=="Daily plan" && e.answer!=null){
                val plan=org.json.JSONObject(e.answer)
                Hint("Planned study · "+plan.optInt("minutes")+" minutes")
            }else if(e.answer!=null)Hint("Response · "+e.answer)
            if(e.xp>0)Text("+"+e.xp+" XP · "+e.reason,color=mint)
        }}
        if(limit<d.journal.size)Secondary("Show earlier entries"){limit+=40}
    }
}
@Composable internal fun RecoveryScreen(d:AcademyData,a:AcademyActions){
    val weak=d.progress.filterValues{it.errors>0}.entries.sortedByDescending{it.value.errors}
    val missed=d.journal.filter{it.correct==0 && it.attempted==1}
    Page{
        SectionTitle("Mistake recovery",a);Secondary("Vocabulary Mistake Lab",onClick=a.lab)
        val unstable=d.index.entries.count{it.weak}
        Hint(unstable.toString()+" unstable vocabulary cards · "+d.due+" due now")
        if(d.due>0)Secondary("Review due cards",onClick=a.review)
        weak.forEach{(id,p)->val l=d.lessons.first{it.id==id};Sheet{Text(l.title);Hint(p.errors.toString()+" missed answers · "+p.status);Secondary("Targeted grammar practice"){a.go("Lesson:"+id)}}}
        d.repairs.forEach{(id,outcome)->
            val title=d.lessons.find{it.id==id}?.title?:d.readings.find{it.id==id}?.title?:id
            Hint(title+" · "+outcome.second+" of "+outcome.first+" previously missed questions answered correctly on the latest attempt")
        }
        Label("Recent incorrect answers · journal period")
        if(missed.isEmpty())Hint("No incorrect answers recorded for this period.")
        missed.distinctBy{it.content to it.activity}.take(30).forEach{e->Sheet{Text(e.activity+" · "+e.content);Hint(e.day+" · "+e.answer.orEmpty())
            val p=d.readings.find{it.id==e.content};val l=d.lessons.find{it.id==e.content}
            if(p!=null)Secondary("Revisit passage"){a.go("Read:"+p.id)} else if(l!=null)Secondary("Revisit lesson"){a.go("Lesson:"+l.id)}
            else Secondary("Open practice",onClick=a.explore)
        }}
    }
}
