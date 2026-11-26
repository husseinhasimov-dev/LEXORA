package org.lexora.study

import androidx.test.platform.app.InstrumentationRegistry
import org.junit.*
import org.junit.Assert.*
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.UUID

class V4Test{
    val ctx=InstrumentationRegistry.getInstrumentation().targetContext
    val profile=UUID.randomUUID().toString()
    private fun at(date:String)=LocalDate.parse(date).atTime(12,0).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
    var clock=at("2026-11-24")
    lateinit var r:StudyRepository
    @Before fun open(){r=StudyRepository(ctx,profile){clock}}
    @After fun close(){val name=java.io.File(r.db.path).name;r.close();ctx.deleteDatabase(name)}
    fun resume(){r.close();open()}
    fun action(a:String,b:String="")=r.act(r.session()!!,a,b)
    @Test fun academicDictionarySharesCanonicalIdsAndDiscoveryMembership(){
        val all=r.academicWords()
        assertEquals(73,all.size);assertEquals(73,all.distinctBy{it.id}.size)
        assertTrue(all.all{it.example.isNotEmpty() && it.translation.isNotEmpty() && (it.pos!="noun" || it.article.isNotEmpty())})
        val cat="University and Education"
        val lecture=r.academicWords("lecture",cat).single()
        assertEquals(r.words(Filters(query="Vorlesung")).first{it.lemma=="Vorlesung"}.id,lecture.id)
        r.addAcademic(lecture.id);r.addAcademic(lecture.id)
        assertEquals(1,r.selections("Academic",cat).size);assertEquals(1,r.weeklyGoals().selectedToday)
        r.startDiscovery("Academic",cat,3)
        repeat(2){assertTrue(r.session()!!.current in r.academicWords(category=cat).map{it.id});action("assess","2")}
        assertEquals(Stage.DiscoveryEnd,r.session()!!.stage);resume()
        action("learnSelected");assertEquals(3,r.session()!!.ids.size);assertEquals(Stage.StudyAll,r.session()!!.stage)
        assertEquals("ok",r.db.str("PRAGMA integrity_check"));assertEquals(0,r.db.long("SELECT count(*) FROM pragma_foreign_key_check"))
    }
    @Test fun academicListCanRevisitAnAlreadyScheduledWordWithoutRescheduling(){
        val w=r.academicWords().first()
        r.event(w.id,"new",4);val card=Memory.review(w.id,null,4,clock,90);r.db.saveCard(w.id,card)
        r.addAcademic(w.id);r.addAcademic(w.id);assertEquals(listOf(w.id),r.selections().map{it.id})
        r.startSelected();action("startPractice")
        while(r.session()!!.plan[r.session()!!.step].kind!=Drill.Flash){
            action("choice",r.session()!!.current);action("next")
        }
        action("reveal");action("rate","3")
        assertTrue(r.selections().isEmpty());assertEquals(card.due.toEpochMilli(),r.db.long("SELECT due FROM card"))
        assertEquals(1,r.today("new"));assertEquals(0,r.today("review"))
    }
    @Test fun manualReviewFiltersUseFirstStudyDateAndLeaveScheduleUnchanged(){
        val words=r.words(Filters(),5)
        listOf("2026-10-10","2026-10-28","2026-11-18","2026-11-23","2026-11-24").forEachIndexed{i,day->
            val id=words[i].id;r.event(id,"new",3,at=at(day));r.db.saveCard(id,Memory.review(id,null,3,at(day),90))
        }
        r.event(words[0].id,"review",4,"scheduled",clock)
        assertEquals(listOf(words[4].id),r.dateReviewIds("Today","Oldest first",10))
        assertEquals(3,r.dateReviewIds("Last 7 days","Oldest first",10).size)
        assertEquals(4,r.dateReviewIds("Last 30 days","Oldest first",10).size)
        assertEquals(listOf(words[0].id),r.dateReviewIds("Older than 30 days","Oldest first",10))
        assertEquals(5,r.dateReviewIds("All previously studied words","Random",10).distinct().size)
        assertEquals(listOf(words[2].id),r.dateReviewIds("Custom date range","Newest first",1,"2026-10-28","2026-11-18"))
        val snapshot=r.db.rows("SELECT word,due,last,stability,difficulty FROM card ORDER BY word"){c->(0..4).map{c.getString(it)}}
        r.startDateReview("Today","Newest first",1,"","");action("reveal");action("rate","1")
        while(r.session()!!.plan[r.session()!!.step].kind==Drill.Bridge){action("choice",r.session()!!.current);action("next")}
        action("reveal");action("retryGrade","yes")
        assertEquals(Stage.RoundResult,r.session()!!.stage);assertEquals(1,r.session()!!.completed.size)
        assertEquals(snapshot,r.db.rows("SELECT word,due,last,stability,difficulty FROM card ORDER BY word"){c->(0..4).map{c.getString(it)}})
        assertEquals(6,r.db.long("SELECT count(*) FROM event WHERE kind IN ('new','review')"))
    }
    @Test fun weeklyTargetsUseHistoricalDailyGoalsAndOnlyScheduledRatings(){
        val ids=r.words(Filters(),3).map{it.id}
        clock=at("2026-11-23");r.setDailyGoal(1);r.event(ids[0],"new",3)
        clock=at("2026-11-24");r.setDailyGoal(2);r.event(ids[1],"new",3)
        r.event(ids[0],"review",4,"scheduled");r.event(ids[0],"answer",1,"Choice")
        assertEquals(WeeklyGoals(1,1,0),r.weeklyGoals())
        r.event(ids[2],"new",3);assertEquals(2,r.weeklyGoals().learningDays)
        resume();assertEquals(2,r.weeklyGoals().learningDays)
        clock=at("2026-11-30");assertEquals(0,r.weeklyGoals().learningDays);assertEquals(0,r.weeklyGoals().scheduled)
    }
    @Test fun retryUntilRecalledAndMarkKnownOnlyForThisSession(){
        r.startDiscovery("A1","All",2);repeat(2){action("assess","3")}
        action("learnSelected");action("startPractice")
        val ids=r.session()!!.ids
        action("choice",r.session()!!.current);action("next")
        action("sessionKnown");assertNull(r.lastRating(ids[0]));assertEquals(3,r.grade(ids[0]))
        action("reveal");action("rate","1")
        fun toRetry(){while(r.session()!!.plan[r.session()!!.step].kind==Drill.Bridge){action("choice",r.session()!!.current);action("next")}}
        toRetry();action("reveal");action("retryGrade","no")
        assertEquals(Stage.RoundResult,r.session()!!.stage);assertEquals(listOf(ids[1]),r.session()!!.missed)
        resume();action("retry");toRetry();action("reveal");action("retryGrade","yes")
        val s=r.session()!!;assertEquals(listOf(ids[0]),s.skipped);assertEquals(listOf(ids[1]),s.completed);assertTrue(s.missed.isEmpty())
        assertEquals(1,r.today("new"));assertEquals(0,r.today("review"));assertEquals(0,r.stats().mastered)
    }
    @Test fun customizableRemindersKeepLocalDaysTimesTextAndDelete(){
        val s=ReminderSchedule(UUID.randomUUID().toString(),"due",false,7,45,"2,4","Vocabulary review","Bring your notebook.")
        r.saveReminder(ctx,s);resume();assertEquals(s,r.reminderSchedule(s.id))
        val start=ZonedDateTime.parse("2026-11-24T07:46:00+04:00")
        assertEquals("2026-11-26T07:45+04:00",Reminder.next(start,s.hour,s.minute,s.days).toString())
        val newer=s.copy(title="Study review",days="7");r.saveReminder(ctx,newer)
        assertEquals("Study review",r.reminderSchedule(s.id)!!.title)
        val backup=r.backup();StudyRepository(ctx,profile+"copy").use{other->other.restore(backup);assertEquals(newer,other.reminderSchedule(s.id))}
        r.deleteReminder(ctx,s.id);resume();assertNull(r.reminderSchedule(s.id))
    }
}
