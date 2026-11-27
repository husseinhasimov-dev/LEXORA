package org.lexora.study

import android.app.Application
import android.net.Uri
import android.os.SystemClock
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject

data class ScreenState(val ready:Boolean=false,val busy:Boolean=false,val error:String="",val notice:String="",
    val shelf:ExploreShelf?=null,val explorer:ExploreProgress?=null,val relatives:List<Pair<Word,String>> = emptyList(),val stats:Stats=Stats(),val prefs:Map<String,String> = emptyMap(),val session:Session?=null,
    val word:Word?=null,val choices:List<String> = emptyList(),val filters:Filters=Filters(),val words:List<Word> = emptyList(),
    val decks:List<String> = emptyList(),val topics:List<String> = emptyList(),val detail:Word?=null,val fav:Boolean=false,
    val selected:List<Selection> = emptyList(),val undoId:Long=0,val canBack:Boolean=false,
    val studyWords:List<Word> = emptyList(),val question:Question?=null,val currentGrade:Int=0,
    val academic:List<Word> = emptyList(),val categories:Map<String,String> = emptyMap(),val credit:String="",
    val schedules:List<ReminderSchedule> = emptyList(),val nextReminders:Map<String,Long> = emptyMap(),val weekly:WeeklyGoals=WeeklyGoals(),val goalCounts:GoalCounts=GoalCounts(0,0,0,0),val detailGrade:Int=0,val latestRating:Pair<String,Int>?=null,val assessedToday:Int=0,
    val dailyHistory:List<DailyRecord> = emptyList(),val selectedCalendarDay:String="",val selectedDayWords:List<Word> = emptyList())

class StudyModel(val app:Application):AndroidViewModel(app) {
    private var repository:StudyRepository?=null
    private var exploreOpen=false
    private var shelf:ExploreShelf?=null
    fun explore(open:Boolean){exploreOpen=open;run{null}}
    private val lock=Mutex()
    private val mutable=MutableStateFlow(ScreenState())
    val state=mutable.asStateFlow()
    private var filter=Filters()
    private var detailId:String?=null
    private var calendarDay:String?=null
    private var startedAt=0L
    private var timerWord:String?=null
    init { run { null } }
    fun run(op:StudyRepository.()->String?) {
        viewModelScope.launch {
            lock.withLock {
                mutable.update { it.copy(busy=true) }
                try {
                    val next=withContext(Dispatchers.IO){
                        if(repository?.owner!=Cloud.uid()){repository?.close();repository=null;shelf=null;detailId=null;calendarDay=null;filter=Filters()}
                        val repo=repository ?: StudyRepository(app).also { repository=it;Reminder.plan(app,it,forceRegister=true) }
                        repo.upgradeCourse()
                        if(repo.db.long("SELECT count(*) FROM pref WHERE k LIKE 'dailyTarget.%'")==0L)repo.set("dailyTarget."+repo.day(),repo.pref("goal"))
                        val msg=repo.op()
                        val s=repo.session();val w=s?.let {repo.word(it.current)}
                        val detail=detailId?.let(repo::word)
                        val keys=repo.defaults.keys+listOf("notificationsMaster","weeklyReviewGoal")+listOf("discoveryTopic","discoveryLevel")+Reminder.keys+Reminder.names.keys.flatMap {k->listOf("$k.hour","$k.minute","$k.days")}
                        val stats=repo.stats()
                        val activeCalendarDay=calendarDay ?: repo.day()
                        val dailyHistory=repo.dailyHistory()
                        val selectedDayWords=repo.wordsLearnedOn(activeCalendarDay)
                        if(exploreOpen && shelf==null)shelf=ExploreShelf(repo.db.rows("SELECT * FROM word ORDER BY rank,id"){it.word()}, passages(app))
                        val relatives=if(detail==null)emptyList() else familyLinks.filter{it.first==detail.lemma || it.second==detail.lemma}.map{edge->
                            val lemma=if(edge.first==detail.lemma)edge.second else edge.first
                            val w=repo.db.rows("SELECT * FROM word WHERE lemma=?",listOf(lemma)){it.word()}.single()
                            w to (edge.third+" · "+if(repo.lastRating(w.id)!=null)"Studied" else knowledgeLabels.getOrNull(repo.grade(w.id)-1)?:"Unassessed")
                        }
                        ScreenState(true,shelf=if(exploreOpen)shelf else null,explorer=if(exploreOpen)repo.exploreProgress() else null,relatives=relatives,academic=repo.academicWords(),categories=repo.db.rows("SELECT word,category FROM content_v4 WHERE category!=''"){it.getString(0) to it.getString(1)}.toMap(),
                            schedules=repo.reminderSchedules(),nextReminders=repo.nextReminders(),weekly=repo.weeklyGoals(),credit=detail?.let {repo.db.str("SELECT credit FROM content_v4 WHERE word=?",it.id)}.orEmpty(),notice=msg?:mutable.value.notice,stats=stats,goalCounts=repo.goalCounts(stats),prefs=keys.associateWith(repo::pref),
                            studyWords=if(s?.stage in listOf(Stage.StudyAll,Stage.Practice) && s!=null)repo.studyWords(s.ids) else emptyList(),
                            currentGrade=w?.let{repo.grade(it.id)}?:0,
                            question=if(s?.stage==Stage.Practice && w!=null && s.plan[s.step].kind in listOf(Drill.Bridge,Drill.Audio,Drill.Choice))repo.question(s,w) else null,
                            session=s,word=w,choices=if(s?.stage==Stage.Recognize && w!=null)repo.choices(s,w) else emptyList(),
                            filters=filter,words=repo.words(filter,41),decks=repo.decks(),topics=repo.topics(),detail=detail,
                            fav=detail?.let {repo.favorite(it.id)}?:false,selected=repo.selections(),undoId=repo.latestStep(),
                            canBack=s!=null && repo.db.long("SELECT EXISTS(SELECT 1 FROM undo_step WHERE word<>? AND before_session<>'')",s.current)>0,
                            detailGrade=detail?.let {repo.grade(it.id)}?:0,latestRating=detail?.let {repo.lastRating(it.id)},
                            assessedToday=repo.db.long("SELECT count(DISTINCT word) FROM effective_event WHERE kind IN ('known','assess') AND day=?",repo.day()).toInt(),
                            dailyHistory=dailyHistory,selectedCalendarDay=activeCalendarDay,selectedDayWords=selectedDayWords)
                    }
                    mutable.value=next
                }catch(e:CancellationException){throw e}
                catch(e:Exception){mutable.update {it.copy(busy=false,error=e.localizedMessage?:e.javaClass.simpleName)}}
            }
        }
    }
    internal suspend fun <T> academyQuery(op:StudyRepository.()->T):T=lock.withLock {
        withContext(Dispatchers.IO) {
            val repo=requireNotNull(repository)
            check(repo.owner==Cloud.uid()){"Profile changed. Reopen the academy."}
            repo.op()
        }
    }
    fun clear(){mutable.update {it.copy(notice="",error="")}}
    fun deleteAccount(){
        viewModelScope.launch {
            lock.withLock {
                mutable.update{it.copy(busy=true)}
                try {
                    val uid=requireNotNull(Cloud.uid())
                    Cloud.deleteAccount(app)
                    withContext(Dispatchers.IO){
                        repository?.close();repository=null
                        val name="account-"+java.util.UUID.nameUUIDFromBytes(uid.toByteArray())+".db"
                        check(app.deleteDatabase(name) || !app.getDatabasePath(name).exists())
                        detailId=null;filter=Filters()
                    }
                    run{"Account and learning history deleted."}
                }catch(e:Exception){mutable.update{it.copy(error=e.localizedMessage?:"Deletion could not finish. Sign in again and retry.",busy=false)}}
            }
        }
    }
    fun filters(f:Filters)=run {filter=f;null}
    fun detail(id:String?)=run {detailId=id;null}
    fun selectCalendarDay(day:String)=run {calendarDay=day;null}
    fun preference(k:String,v:String)=run {if(k=="goal")setDailyGoal(v.toInt()) else set(k,v);if(k.substringBefore('.') in Reminder.keys)Reminder.plan(app,this);null}
    fun start(mode:Mode,f:Filters=Filters())=run {start(mode,f)}
    fun action(s:Session,key:String,value:String=""){
        val time=SystemClock.elapsedRealtime()
        val elapsed=if(timerWord==s.current)time-startedAt else 0L
        if(timerWord==s.current)startedAt=time
        run {if(elapsed>0)time(s.current,elapsed);act(s,key,value)}
    }
    fun timing(id:String?) {
        val now=SystemClock.elapsedRealtime()
        val old=timerWord;val elapsed=now-startedAt
        if(id==old)return
        timerWord=id;startedAt=now
        if(old!=null && elapsed>0)run {time(old,elapsed);null}
    }
    fun transfer(uri:Uri,operation:String)=run {
        val resolver=app.contentResolver
        when(operation) {
            "CSV export","Anki export","Backup" -> {
                requireNotNull(resolver.openOutputStream(uri,"wt")).bufferedWriter(Charsets.UTF_8).use {
                    if(operation=="Backup")it.write(backup().toString()) else exportCsv(it,operation=="Anki export")
                }; "File saved."
            }
            "CSV import","Anki import" -> requireNotNull(resolver.openInputStream(uri)).bufferedReader().use { importCsv(it,operation=="Anki import") }
            else -> requireNotNull(resolver.openInputStream(uri)).bufferedReader().use {
                val buf=CharArray(8192);val text=StringBuilder()
                while(true){val n=it.read(buf);if(n<0)break;text.append(buf,0,n);require(text.length<=32_000_000){"Backup exceeds 32 MB."}}
                val msg=restore(JSONObject(text.toString()));Reminder.plan(app,this);msg
            }
        }
    }
    override fun onCleared(){repository?.close()}
}
