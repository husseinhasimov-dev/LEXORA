package org.lexora.study

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.util.UUID

internal data class ReaderMark(val paragraph:Int,val bookmark:Int?)
internal data class AcademyData(val readings:List<Reading>,val lessons:List<Lesson>,val index:ReadingIndex,val inbox:List<InboxWord>,
    val marks:Map<String,ReaderMark>,val prefs:Map<String,String>,val progress:Map<String,TopicProgress>,val rounds:Map<String,Round>,
    val xp:Long,val badges:List<String>,val journal:List<JournalRow>,val history:Map<String,Pair<Int,Double>>,
    val quests:List<Pair<Quest,Int>>,val today:String,val now:Long,val due:Int,val lastRead:String?)
internal fun StudyRepository.academyData(readings:List<Reading>,lessons:List<Lesson>,index:ReadingIndex,from:String,to:String):AcademyData {
    val progress=lessons.associate{it.id to topicProgress(it.id)}
    val quests=quests(LocalDate.parse(day()),progress)
    questMilestones(quests)
    val prefs=db.rows("SELECT k,v FROM pref WHERE k LIKE 'academy.%'"){it.getString(0) to it.getString(1)}.toMap()
    val rounds=prefs.keys.filter{it.startsWith("academy.round.")}.map{it.removePrefix("academy.round.")}.associateWith{round(it)!!}
    return AcademyData(readings,lessons,index,inbox(),db.rows("SELECT passage,paragraph,bookmark FROM academy_reader"){it.getString(0) to ReaderMark(it.getInt(1),if(it.isNull(2))null else it.getInt(2))}.toMap(),
        prefs,progress,rounds,academyXp(),achievements()+progress.filterValues{it.status=="Mastered"}.keys.map{"Grammar mastery · "+lessons.first{l->l.id==it}.title},
        journal(from,to),db.rows("""SELECT content,count(*),sum(correct)*1.0/sum(attempted) FROM academy_event WHERE activity='Comprehension' GROUP BY content"""){
            it.getString(0) to (it.getInt(1) to it.getDouble(2))}.toMap(),quests.map{it to questCount(it,day())},day(),now(),
        db.long("SELECT count(*) FROM active_card WHERE due<=?",now().toString()).toInt(),
        db.str("SELECT passage FROM academy_reader ORDER BY touched DESC LIMIT 1"))
}
internal class AcademyActions(val go:(String)->Unit,val back:()->Unit,val edit:(StudyRepository.()->Unit,()->Unit)->Unit,
    val study:()->Unit,val detail:(String)->Unit,val explore:()->Unit,val lab:()->Unit,val review:()->Unit){
    fun write(op:StudyRepository.()->Unit)=edit(op,{})
    fun quiz(a:AcademyActivity)=edit({beginRound(a.key)},{go("Quiz:"+a.key)})
    fun learn(ids:Collection<String>)=edit({enqueue(ids,true)?.let{error(it)}},study)
}
@Composable internal fun AcademyScreen(vm:StudyModel,voice:Voice,study:()->Unit,explore:()->Unit,lab:()->Unit,review:()->Unit){
    var path by rememberSaveable{mutableStateOf("Hub")}
    val route=path.substringAfterLast('>')
    var revision by remember{mutableIntStateOf(0)}
    var vocabRevision by remember{mutableIntStateOf(0)}
    var from by rememberSaveable{mutableStateOf(LocalDate.now().minusDays(6).toString())}
    var to by rememberSaveable{mutableStateOf(LocalDate.now().toString())}
    var data by remember{mutableStateOf<AcademyData?>(null)}
    var message by rememberSaveable{mutableStateOf("")}
    var working by remember{mutableStateOf(false)}
    val scope=rememberCoroutineScope()
    val owner=Cloud.uid()
    var cache by remember(owner){mutableStateOf<Triple<List<Reading>,List<Lesson>,ReadingIndex>?>(null)}
    LaunchedEffect(owner,revision,from,to,vocabRevision){
        try {
            val old=cache
            val resources=old?:vm.academyQuery{Triple(OfflineReadings.passages(vm.app),grammarLessons(vm.app),readingIndex())}.also{cache=it}
            data=vm.academyQuery{academyData(resources.first,resources.second,resources.third,from,to)}
        }catch(e:CancellationException){throw e}catch(e:Exception){message=e.localizedMessage?:"Could not open learning records"}
    }
    val back={path=path.substringBeforeLast('>',"Hub")}
    BackHandler(route!="Hub",back)
    DisposableEffect(route){onDispose{voice.stop()}}
    val a=AcademyActions({next->if(route!=next)path+=">"+next},back,{op,done->
        if(!working){
            working=true
            scope.launch {
                try {
                    vm.academyQuery { db.tx{op()} }
                    if(route.startsWith("Read:") || route in listOf("Inbox","Discover")){cache=null;vocabRevision++}
                    revision++
                    vm.run{null}
                    done()
                }catch(e:CancellationException){throw e}catch(e:Exception){message=e.localizedMessage?:"Could not save"}finally{working=false}
            }
        }
    },study,vm::detail,explore,lab,review)
    val holder=rememberSaveableStateHolder()
    val d=data
    if(d==null)Page{Heading("Learning Academy");if(message.isEmpty())CircularProgressIndicator() else Secondary("Retry"){revision++}}
    else holder.SaveableStateProvider(route){
        key(route){
            when {
                route=="Reading"->ReadingBuilder(d,a)
                route.startsWith("Read:")->Reader(d,a,voice,d.readings.first{it.id==route.substringAfter(':')})
                route=="Inbox"->InboxScreen(d,a)
                route=="Discover"->AcademyDiscover(d,a)
                route=="Grammar" || route=="Roadmap"->Curriculum(d,a,route)
                route.startsWith("Lesson:")->LessonScreen(d,a,d.lessons.first{it.id==route.substringAfter(':')})
                route.startsWith("Quiz:")->{
                    val (skill,id,mode)=route.substringAfter(':').split('/')
                    val activity=if(skill=="Reading")d.readings.first{it.id==id}.activity() else d.lessons.first{it.id==id}.activity(mode)
                    AcademyQuiz(d,a,activity)
                }
                route=="Quests"->QuestsScreen(d,a)
                route=="Journal"->JournalScreen(d,a,from,to){f,t->from=f;to=t}
                route=="Recovery"->RecoveryScreen(d,a)
                else->Page{
                    Label("Learn");Heading("Learning Academy")
                    Sheet{Text(d.xp.toString()+" XP · Level "+AcademyXp.level(d.xp));Progress((d.xp%100)/100f);Hint((100-d.xp%100).toString()+" XP to the next level")}
                    Primary("Reading Academy"){a.go("Reading")}
                    Secondary("Grammar Academy"){a.go("Grammar")}
                    Secondary("Unknown Words Inbox"){a.go("Inbox")}
                    Secondary("Discover vocabulary"){a.go("Discover")}
                    Secondary("Learning roadmap"){a.go("Roadmap")}
                    Secondary("Daily quests"){a.go("Quests")}
                    Secondary("Mistake recovery"){a.go("Recovery")}
                    Secondary("Learning journal"){a.go("Journal")}
                }
            }
        }
    }
    if(message.isNotEmpty())AlertDialog(onDismissRequest={message=""},title={Text("Learning Academy")},text={Text(message)},confirmButton={TextButton({message=""}){Text("Close")}})
}
@Composable internal fun SectionTitle(title:String,a:AcademyActions){
    TextButton(a.back){Text("‹ Back")};Heading(title)
}
@Composable internal fun EntryField(label:String,value:String,onChange:(String)->Unit){
    OutlinedTextField(value,onChange,label={Text(label)},modifier=Modifier.fillMaxWidth(),singleLine=true)
}
@Composable internal fun Curriculum(d:AcademyData,a:AcademyActions,route:String){
    var level by rememberSaveable{mutableStateOf("A1")}
    Page{
        SectionTitle(if(route=="Roadmap")"Learning roadmap" else "Grammar Academy",a)
        Pick("Level",level,levels){level=it}
        val group=d.lessons.filter{it.level==level}
        val next=group.firstOrNull{d.progress.getValue(it.id).status in listOf("Needs Review","Practice Needed")}?:group.firstOrNull{d.progress.getValue(it.id).status!="Mastered"}
        if(next!=null)Primary("Recommended · "+next.title){a.go("Lesson:"+next.id)}
        Hint("Estimated level · "+group.count{d.progress.getValue(it.id).status=="Mastered"}+" / "+group.size+" topics mastered")
        group.forEach{l->Sheet{
            Text(l.title);Hint(d.progress.getValue(l.id).status+" · Suggested background: "+(d.lessons.find{it.id==l.prerequisite}?.title?:"Start here"))
            Secondary("Open "+l.title){a.go("Lesson:"+l.id)}
        }}
        if(route=="Roadmap"){
            Secondary("Vocabulary · discover and recall"){a.go("Discover")}
            Secondary("Reading · comprehension"){a.go("Reading")}
            Secondary("Listening · practice in context",onClick=a.explore)
            Secondary("Plan speaking and writing practice"){a.go("Quests")}
        }
    }
}
@Composable internal fun LessonScreen(d:AcademyData,a:AcademyActions,l:Lesson){
    val p=d.progress.getValue(l.id)
    Page{
        SectionTitle(l.title,a);Label(l.level+" · "+p.status)
        Sheet{Text(l.explanation);Text(l.example,color=mint);Hint(l.mistake)}
        if(!p.lesson)Primary("Finish studying lesson"){a.write{completeLesson(l)}}
        Hint("Practice, assessment and a successful review at least one day later build mastery.")
        listOf("Guided","Practice","Assessment","Delayed review").forEach{mode->
            Secondary(mode,mode!="Delayed review" || p.lastAssessment>0 && d.now-p.lastAssessment>=MasteryRules().delayMs){a.quiz(l.activity(mode))}
        }
        fun percent(v:Double?)=v?.let{(it*100).toInt().toString()+"%"}?:"—"
        Hint("Recent practice "+percent(p.practice)+" · Assessment "+percent(p.test)+" · Delayed review "+percent(p.delayed))
        d.readings.filter{it.grammar==l.id}.forEach{r->Secondary("Read · "+r.title){a.go("Read:"+r.id)}}
    }
}
@Composable internal fun AcademyQuiz(d:AcademyData,a:AcademyActions,activity:AcademyActivity){
    val r=d.rounds[activity.key]
    Page {
        SectionTitle(activity.mode,a)
        if(r==null){Hint("Opening practice…");return@Page}
        if(r.finished){
            Text("Round complete");Hint("Your results and eligible XP are in the learning journal.")
            Primary("Return",onClick=a.back)
            Secondary("Practice again"){a.quiz(activity)}
            return@Page
        }
        val q=activity.questions[r.step]
        var input by rememberSaveable(r.id,r.step){mutableStateOf("")}
        var last by remember(r.id,r.step){mutableLongStateOf(android.os.SystemClock.elapsedRealtime())}
        val life=androidx.lifecycle.compose.LocalLifecycleOwner.current.lifecycle
        DisposableEffect(life,r.id,r.step){
            val observer=androidx.lifecycle.LifecycleEventObserver{_,event->if(event==androidx.lifecycle.Lifecycle.Event.ON_RESUME)last=android.os.SystemClock.elapsedRealtime()}
            life.addObserver(observer);onDispose{life.removeObserver(observer)}
        }
        fun submit(value:String){val ms=(android.os.SystemClock.elapsedRealtime()-last).takeIf{it in 1..30_000};a.write{answerAcademy(activity,r,value,ms)}}
        Label(q.type+" · "+(r.step+1)+"/"+activity.questions.size);Text(q.text,style=MaterialTheme.typography.titleLarge)
        if(activity.mode=="Guided")Hint(q.why)
        if(r.result==null){
            if(q.distractors.isEmpty()){EntryField("Your answer",input){input=it};Primary("Check answer",input.isNotBlank()){submit(input)}}
            else (q.distractors+q.key).shuffled(kotlin.random.Random((activity.key+r.step).hashCode())).forEach{option->Secondary(option){submit(option)}}
        }else{
            Sheet{Text(if(q.accepts(r.result))"Correct" else "Answer · "+q.key);Hint(q.why)}
            Primary(if(r.step+1==activity.questions.size)"Finish round" else "Next question"){a.write{advanceAcademy(activity,r)}}
        }
    }
}
