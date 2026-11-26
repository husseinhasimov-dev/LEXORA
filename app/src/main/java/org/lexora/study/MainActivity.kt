package org.lexora.study

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.BackHandler
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.*
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

val blue=Color(0xffa6caff)
val mint=Color(0xff9dd8b9)
val ink=Color(0xff0d1521)
val panel=Color(0xff182535)
val faint=Color(0xffbcc9d6)
val amber=Color(0xfff4c68c)
private val navBars=listOf("Home" to Icons.Default.Home,"Learn" to Icons.Default.PlayArrow,"Library" to Icons.Default.List,
    "Statistics" to Icons.Default.DateRange,"Profile" to Icons.Default.Person)
private val drillRoutes=setOf("Practice","Academic","Explore")
private val homeDateFormatter=DateTimeFormatter.ofPattern("EEE, MMM d",Locale.ENGLISH)
class MainActivity:ComponentActivity(){
    private val model:StudyModel by viewModels()
    private lateinit var voice:Voice
    override fun onCreate(savedInstanceState:Bundle?){
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(statusBarStyle=androidx.activity.SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle=androidx.activity.SystemBarStyle.dark(android.graphics.Color.TRANSPARENT))
        voice=Voice(this)
        setContent { MaterialTheme(colorScheme=darkColorScheme(primary=blue,onPrimary=Color(0xff102c4d),
            background=ink,surface=ink,surfaceContainer=panel,onBackground=Color(0xffedf4ef),
            onSurface=Color(0xffedf4ef),onSurfaceVariant=faint,secondary=mint,outline=Color(0xff53677b))){
            App(model,voice)
        } }
    }
    override fun onDestroy(){voice.close();super.onDestroy()}
}

@Composable fun Heading(text:String){Text(text,fontSize=32.sp,lineHeight=38.sp,fontWeight=FontWeight.SemiBold,letterSpacing=(-1).sp)}
@Composable fun Label(text:String){Text(text.uppercase(Locale.ENGLISH),fontSize=11.sp,fontWeight=FontWeight.Bold,color=mint,letterSpacing=1.5.sp)}
@Composable fun Hint(text:String){Text(text,color=faint,style=MaterialTheme.typography.bodyMedium)}
@Composable fun Sheet(content:@Composable ColumnScope.()->Unit){
    Column(Modifier.fillMaxWidth().background(panel,RoundedCornerShape(18.dp)).border(1.dp,Color(0xff304153),RoundedCornerShape(18.dp)).padding(18.dp),
        verticalArrangement=Arrangement.spacedBy(10.dp),content=content)
}
@Composable fun Primary(text:String,enabled:Boolean=true,onClick:()->Unit){
    val interaction=remember{MutableInteractionSource()}
    val scale=rememberPressScale(interaction,enabled)
    Button(onClick,Modifier.fillMaxWidth().heightIn(min=50.dp).applyScale(scale),enabled,
        shape=RoundedCornerShape(15.dp),
        elevation=ButtonDefaults.buttonElevation(defaultElevation=2.dp,pressedElevation=0.dp,focusedElevation=2.dp,hoveredElevation=3.dp,disabledElevation=0.dp),
        interactionSource=interaction){Text(text,fontWeight=FontWeight.Bold)}
}
@Composable fun Secondary(text:String,enabled:Boolean=true,onClick:()->Unit){
    val interaction=remember{MutableInteractionSource()}
    val pressed by interaction.collectIsPressedAsState()
    val reducedMotion=LocalReducedMotion.current
    val scale=rememberPressScale(interaction,enabled)
    val borderColor by animateColorAsState(
        targetValue=when{!enabled->Color(0xff53677b).copy(alpha=0.4f);pressed->mint.copy(alpha=0.7f);else->Color(0xff53677b)},
        animationSpec=if(reducedMotion)snap() else tween(MOTION_PRESS,easing=FastOutSlowInEasing),
        label="secondaryBorder"
    )
    val containerColor by animateColorAsState(
        targetValue=if(enabled && pressed)mint.copy(alpha=0.10f) else Color.Transparent,
        animationSpec=if(reducedMotion)snap() else tween(MOTION_PRESS,easing=FastOutSlowInEasing),
        label="secondaryContainer"
    )
    OutlinedButton(onClick,Modifier.fillMaxWidth().heightIn(min=48.dp).applyScale(scale),enabled,
        shape=RoundedCornerShape(15.dp),
        border=BorderStroke(1.dp,borderColor),
        colors=ButtonDefaults.outlinedButtonColors(containerColor=containerColor,contentColor=mint),
        interactionSource=interaction){Text(text)}
}
@Composable fun Progress(value:Float){
    val target=value.coerceIn(0f,1f)
    val reducedMotion=LocalReducedMotion.current
    val animated=animateFloatAsState(
        targetValue=target,
        animationSpec=if(reducedMotion)snap() else tween(MOTION_PROGRESS,easing=FastOutSlowInEasing),
        label="progress"
    )
    LinearProgressIndicator(progress={animated.value},modifier=Modifier.fillMaxWidth().height(5.dp),color=mint,trackColor=Color(0xff35423e))
}
@Composable fun Metric(value:String,title:String,modifier:Modifier=Modifier){
    Column(modifier.background(panel,RoundedCornerShape(16.dp)).padding(13.dp)){
        Text(value,fontSize=23.sp,fontWeight=FontWeight.SemiBold,color=Color.White);Hint(title)
    }
}
@Composable fun Page(content:@Composable ColumnScope.()->Unit){
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal=20.dp,vertical=16.dp),
        verticalArrangement=Arrangement.spacedBy(14.dp),content=content)
}
@Composable fun Pick(label:String,value:String,options:List<String>,onChange:(String)->Unit){
    var open by remember {mutableStateOf(false)}
    val interaction=remember{MutableInteractionSource()}
    val pressed by interaction.collectIsPressedAsState()
    val reducedMotion=LocalReducedMotion.current
    val scale=rememberPressScale(interaction,true)
    val borderColor by animateColorAsState(
        targetValue=if(pressed || open)blue.copy(alpha=0.75f) else Color(0xff53677b),
        animationSpec=if(reducedMotion)snap() else tween(MOTION_PRESS,easing=FastOutSlowInEasing),
        label="pickBorder"
    )
    val containerColor by animateColorAsState(
        targetValue=if(pressed || open)blue.copy(alpha=0.10f) else Color.Transparent,
        animationSpec=if(reducedMotion)snap() else tween(MOTION_PRESS,easing=FastOutSlowInEasing),
        label="pickContainer"
    )
    Box {
        OutlinedButton({open=true},Modifier.applyScale(scale),shape=RoundedCornerShape(12.dp),
            border=BorderStroke(1.dp,borderColor),
            colors=ButtonDefaults.outlinedButtonColors(containerColor=containerColor),
            interactionSource=interaction) {Text("$label · $value  ▾")}
        DropdownMenu(open,{open=false},modifier=Modifier.heightIn(max=360.dp)){
            options.forEach { DropdownMenuItem(text={Text(it)},onClick={open=false;onChange(it)}) }
        }
    }
}

@Composable fun App(vm:StudyModel,voice:Voice){
    val state by vm.state.collectAsStateWithLifecycle()
    var tab by rememberSaveable{mutableStateOf("Home")}
    var practice by rememberSaveable{mutableStateOf(false)}
    var discoverRequested by rememberSaveable{mutableStateOf(false)}
    var previewRequested by rememberSaveable {mutableStateOf(false)}
    var openedWelcome by rememberSaveable{mutableStateOf(true)}
    val reducedMotion=rememberReducedMotion()
    val showBusy=rememberDelayedBusy(state.ready && state.busy)
    val session=state.session
    val lifecycle=LocalLifecycleOwner.current.lifecycle
    val active=if(practice && tab=="Learn")session?.current else null
    DisposableEffect(active,lifecycle){
        val observer=LifecycleEventObserver { _,event ->
            when(event){Lifecycle.Event.ON_RESUME->vm.timing(active);Lifecycle.Event.ON_PAUSE->vm.timing(null);else->Unit}
        }
        if(lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED))vm.timing(active)
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer);vm.timing(null) }
    }
    LaunchedEffect(state.ready,session==null){if(state.ready && session==null && !state.busy)practice=false}
    fun begin(mode:Mode,f:Filters=Filters()){
        if(state.busy)return
        tab="Learn";practice=true;vm.start(mode,f)
    }
    BackHandler(enabled=state.detail!=null || practice || tab!="Home"){
        when {state.detail!=null -> vm.detail(null);practice -> practice=false;tab=="Academic"->tab="Library";else -> tab="Home"}
    }
    val inPractice=tab=="Learn" && practice && session!=null && state.word!=null
    val route=when{!state.ready->"Loading";inPractice->"Practice";else->tab}
    CompositionLocalProvider(LocalReducedMotion provides reducedMotion){
        Scaffold(containerColor=ink,bottomBar={
            AnimatedVisibility(visible=!practice || session==null,enter=panelEnter(reducedMotion),exit=panelExit(reducedMotion)){
                NavigationBar(containerColor=Color(0xff131f2e)){
                    navBars.forEach { (name,icon)->NavigationBarItem(selected=name==tab || tab=="Academic" && name=="Library",onClick={tab=name;practice=false},
                        icon={Icon(icon,null)},label={Text(name,fontSize=11.sp,maxLines=1)},colors=NavigationBarItemDefaults.colors(indicatorColor=Color(0xff29415f),selectedIconColor=blue)) }
                }
            }
        }){inner->
            Box(Modifier.fillMaxSize().padding(inner)){
                AnimatedContent(
                    targetState=route,
                    modifier=Modifier.fillMaxSize(),
                    transitionSpec={
                        when{
                            targetState in drillRoutes && initialState !in drillRoutes -> drillTransition(true,reducedMotion)
                            initialState in drillRoutes && targetState !in drillRoutes -> drillTransition(false,reducedMotion)
                            else -> peerScreenTransition(reducedMotion)
                        }
                    },
                    label="appRoute"
                ){currentRoute->
                    when(currentRoute){
                        "Loading"->Box(Modifier.fillMaxSize(),contentAlignment=Alignment.Center){
                            if(state.error.isEmpty())CircularProgressIndicator(color=blue,trackColor=panel)
                            else Box(Modifier.padding(horizontal=20.dp)){Primary("Retry opening library"){vm.clear();vm.run{null}}}
                        }
                        "Home"->Home(state,vm,{tab="Learn";practice=false;discoverRequested=true},{begin(Mode.Review)},{tab="Learn";practice=true}, {tab="Academic"},{tab="Explore"})
                        "Explore"->ExploreScreen(state,vm,voice){tab="Home"}
                        "Practice"->Exercise(state,vm,voice){practice=false}
                        "Learn"->Learn(state,vm,{m,f->begin(m,f)},{practice=true},previewRequested,{previewRequested=false},discoverRequested,{tab="Explore"}){discoverRequested=false}
                        "Academic"->AcademicScreen(state,vm,voice){tab="Library"}
                        "Library"->Library(state,vm,voice){tab="Academic"}
                        "Statistics"->Statistics(state)
                        "Profile"->Profile(state,vm){begin(Mode.Placement)}
                    }
                }
                AnimatedVisibility(
                    visible=showBusy,
                    modifier=Modifier.align(Alignment.TopCenter).fillMaxWidth(),
                    enter=if(reducedMotion)EnterTransition.None else fadeIn(tween(MOTION_FAST)),
                    exit=if(reducedMotion)ExitTransition.None else fadeOut(tween(MOTION_FAST))
                ){
                    LinearProgressIndicator(modifier=Modifier.fillMaxWidth().height(3.dp),color=mint,trackColor=Color.Transparent)
                }
            }
        }
        if(state.ready && state.prefs["welcome"]=="true" && openedWelcome)AlertDialog(onDismissRequest={openedWelcome=false;vm.preference("welcome","false")},
            title={Text("Where would you like to start?")},text={Text("Try eight vocabulary questions to choose a starting level, or begin at A1. You can change your level in Profile.")},
            confirmButton={TextButton({openedWelcome=false;vm.preference("welcome","false");begin(Mode.Placement)}){Text("Try assessment")}},
            dismissButton={TextButton({openedWelcome=false;vm.preference("welcome","false")}){Text("Start at A1")}})
        if(state.detail!=null)WordDetail(state,vm,voice)
        val message=state.error.ifEmpty{state.notice}
        if(message.isNotEmpty())AlertDialog(onDismissRequest=vm::clear,title={Text(if(state.error.isNotEmpty())"Couldn’t finish" else "Study update")},
            text={Text(message)},confirmButton={TextButton({
                vm.clear()
                if(message==selectionReady && state.selected.isNotEmpty()){tab="Learn";practice=false;previewRequested=true}
            }){Text(if(message==selectionReady && state.selected.isNotEmpty())"Review selection" else "Done")}})
    }
}

@Composable fun Home(state:ScreenState,vm:StudyModel,discover:()->Unit,scheduled:()->Unit,resume:()->Unit,academic:()->Unit,explore:()->Unit={}){
    val stats=state.stats
    val goal=state.prefs.getValue("goal").toInt()
    val weeklyTarget=state.prefs.getValue("weeklyReviewGoal").toInt()
    val available=state.session==null && !state.busy
    val reviewing=state.session?.let{it.mode==Mode.Review || it.batch=="date-review"}==true
    var dateReview by rememberSaveable{mutableStateOf(false)}
    val today=LocalDate.now()
    val formattedDate=remember(today){today.format(homeDateFormatter)}
    Page {
        Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween){Label("Lexora");Hint(formattedDate)}
        Heading("Your study day")
        Sheet {
            Text("Today’s words",style=MaterialTheme.typography.headlineSmall)
            Text(state.goalCounts.today.toString()+" / "+goal+" successfully recalled",style=MaterialTheme.typography.titleMedium)
            Progress(state.goalCounts.today.toFloat()/goal)
            Hint(state.weekly.selectedToday.toString()+" selected today · "+stats.todayNew+" first studied today")
            Hint(state.selected.size.toString()+" waiting in my learning list")
            if(state.session!=null && !reviewing)Primary("Continue "+state.session.mode.label.lowercase(),!state.busy,resume)
            else if(state.selected.isNotEmpty())Primary("Learn selected words",available){resume();vm.run{startSelected()}}
            else Primary("Discover words",available,discover)
            if(state.session==null && state.selected.isNotEmpty())TextButton(discover){Text("Discover more words")}
            EditDailyGoal(goal,!state.busy){vm.preference("goal",it.toString())}
        }
        Sheet{
            Text("Review words",style=MaterialTheme.typography.headlineSmall)
            Hint("Choose words by the date you first studied them.")
            if(reviewing)Primary("Continue review",!state.busy,resume) else Primary("Review by date",available){dateReview=true}
            Secondary("Scheduled reviews · "+stats.due+" due",available && stats.due>0,scheduled)
        }
        Secondary("TestDaF Academic Dictionary",onClick=academic)
        Sheet{
            Label("Weekly goals · Monday to Sunday")
            Text("Daily learning targets met · "+state.weekly.learningDays+" / 7 days")
            Progress(state.weekly.learningDays/7f)
            Text("Scheduled reviews completed · "+state.weekly.scheduled+" / "+weeklyTarget)
            Progress(state.weekly.scheduled.toFloat()/weeklyTarget)
            Pick("Weekly review goal",weeklyTarget.toString(),listOf("5","10","20","30","50","100")){vm.preference("weeklyReviewGoal",it)}
        }
        Secondary("Practice & Explore",onClick=explore)
        PreparationGoal(state.goalCounts)
    }
    if(dateReview)ReviewChooser({dateReview=false}){range,order,size,from,to->
        resume();vm.run{startDateReview(range,order,size,from,to)}
    }
}

@Composable fun Learn(state:ScreenState,vm:StudyModel,begin:(Mode,Filters)->Unit,resume:()->Unit,previewRequested:Boolean,consumePreview:()->Unit,discoverRequested:Boolean,explore:()->Unit,consumeDiscovery:()->Unit){
    var level by rememberSaveable{mutableStateOf("All")};var deck by rememberSaveable{mutableStateOf("All")}
    var status by rememberSaveable{mutableStateOf("All")}
    var topic by rememberSaveable{mutableStateOf("All")}
    Page {
        Label("Practice");Heading("Learn")
        if(state.session!=null)Sheet {Text(state.session.mode.label);Hint(if(state.session.stage==Stage.Assess)"Discovery in progress" else "Word ${state.session.index+1} of ${state.session.limit}")
            Primary("Resume session",!state.busy,resume)
            var confirm by remember{mutableStateOf(false)}
            TextButton({confirm=true}){Text("End this session")}
            if(confirm)Confirm("End this session?","Completed ratings and answers are already saved.",{confirm=false}){vm.run{save(null);null}}
        }
        DailyLearning(state,vm,resume,begin,previewRequested,consumePreview,discoverRequested,consumeDiscovery)
        Secondary("Practice & Explore",onClick=explore)
        Label("More practice")
        Pick("Time",state.prefs.getValue("duration"),listOf("0","5","10","15")){vm.preference("duration",it)}
        Hint("0 follows your word target. Timed sessions finish after the current word.")
        for(mode in listOf(Mode.Quick,Mode.Custom,Mode.Placement)){
            Sheet {
                Text(mode.label,style=MaterialTheme.typography.titleLarge)
                Hint(when(mode){Mode.New->"Discover, recognize and write new vocabulary.";Mode.Review->"${state.stats.due} due now · FSRS";Mode.Lab->"Practice words you missed, outside your review schedule.";Mode.Quick->"Up to five recognition questions at your starting level.";Mode.Custom->"Practice a selection from your library.";else->"Eight adaptive questions to suggest a starting level."})
                if(mode==Mode.Custom){Pick("Level",level,listOf("All")+levels){level=it};Pick("Deck",deck,listOf("All")+state.decks){deck=it};Pick("Group",status,listOf("All","Favorites","Missed","Learning","Known")){status=it}}
                if(mode==Mode.Custom)Pick("Topic",topic,listOf("All")+state.topics){topic=it}
                Secondary("Start "+mode.label.lowercase(),!state.busy && state.session==null){begin(mode,Filters(level=level,deck=deck,topic=topic,status=status))}
            }
        }
    }
}

@Composable fun Confirm(title:String,body:String,dismiss:()->Unit,accept:()->Unit){
    AlertDialog(onDismissRequest=dismiss,title={Text(title)},text={Text(body)},confirmButton={TextButton({dismiss();accept()}){Text("Confirm")}},dismissButton={TextButton(dismiss){Text("Cancel")}})
}
