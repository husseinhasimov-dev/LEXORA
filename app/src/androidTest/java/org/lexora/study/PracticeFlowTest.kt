package org.lexora.study

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class PracticeFlowTest {
    private val ctx=InstrumentationRegistry.getInstrumentation().targetContext
    private val owner=UUID.randomUUID().toString()
    private var clock=1801000000000L
    private fun open()=StudyRepository(ctx,owner){clock}
    private lateinit var repo:StudyRepository
    @Before fun before(){repo=open()}
    @After fun after(){val file=java.io.File(repo.db.path);repo.close();ctx.deleteDatabase(file.name)}
    private fun restart(){repo.close();repo=open()}
    private fun current()=repo.session()!!
    private fun action(a:String,b:String="")=repo.act(current(),a,b)
    private fun select(n:Int){
        repo.startDiscovery("A1","All",n)
        repeat(n){action("assess","1")}
        assertEquals(Stage.DiscoveryEnd,current().stage);action("learnSelected")
        assertEquals(Stage.StudyAll,current().stage)
    }

    @Test fun thirtyVisibleInStudyOrderAndPracticeRequiresExplicitStart(){
        select(30)
        val study=current()
        assertEquals(study.ids,repo.studyWords(study.ids).map{it.id})
        assertEquals(30,study.ids.size)
        restart();assertEquals(study,current())
        assertEquals(0,repo.goalCounts(repo.stats()).covered)
        assertEquals(0,repo.db.long("SELECT count(*) FROM card"))
        action("startPractice")
        val practice=current()
        assertEquals(Drill.Bridge,practice.plan.first().kind)
        assertEquals(30,practice.plan.count{it.kind==Drill.Flash})
        assertEquals(setOf(false,true),practice.plan.filter{it.kind==Drill.Flash}.map{it.reverse}.toSet())
        assertEquals(study.ids,practice.ids)
        assertEquals(study.ids,repo.studyWords(practice.ids).map{it.id})
        restart();assertEquals(practice,current())
    }
    @Test fun shortDeckRetriesAreSpacedAndNeverScheduleTwice(){
        select(1);action("startPractice")
        val target=current().ids.single()
        val sequence=mutableListOf<Turn>()
        while(current().stage==Stage.Practice){
            val s=current();val turn=s.plan[s.step];sequence+=turn
            when(turn.kind){
                Drill.Choice,Drill.Bridge,Drill.Audio->{action("choice",if(turn.kind==Drill.Choice)"incorrect" else turn.word);action("next")}
                Drill.Flash->{action("reveal");val rated=current();action("rate","1");repo.act(rated,"rate","1")}
                Drill.Retry->{action("reveal");action("retryGrade","yes")}
            }
            assertTrue(sequence.size<20)
        }
        val positions=sequence.indices.filter{sequence[it].word==target}
        assertEquals(3,positions.size)
        assertTrue(positions.zipWithNext().all{(a,b)->b-a>=3})
        assertEquals(1,repo.today("new"));assertEquals(0,repo.today("review"));assertEquals(1,repo.learnedToday())
        assertEquals("ok",repo.db.str("PRAGMA integrity_check"))
        assertEquals(0,repo.db.long("SELECT count(*) FROM pragma_foreign_key_check"))
    }
    @Test fun goalsCountFirstSuccessAndKnownWithoutOverlaps(){
        val (a,b,c)=repo.words(Filters(),3)
        repo.reclassify(a.id,4);repo.reclassify(b.id,2)
        repo.event(c.id,"new",1)
        assertEquals(GoalCounts(1,0,0,0),repo.goalCounts(repo.stats()))
        val t=clock;repo.event(c.id,"review",3,at=t+1)
        repo.event(c.id,"review",4,at=t+2)
        var g=repo.goalCounts(repo.stats());assertEquals(2,g.covered);assertEquals(5998,g.remaining);assertEquals(1,g.today)
        repo.reclassify(c.id,4);g=repo.goalCounts(repo.stats());assertEquals(2,g.known);assertEquals(0,g.learned);assertEquals(2,g.covered)
        repo.reclassify(a.id,1);assertEquals(1,repo.goalCounts(repo.stats()).covered)
        assertEquals(0,GoalCounts(5000,1500,100,0).remaining)
        clock+=86400000L;assertEquals(0,repo.learnedToday())
    }
    @Test fun dailyGoalAndDirectionsPersistIncludingAnInterruptedDiscovery(){
        repo.startDiscovery("A2","All",30);repo.setDailyGoal(7)
        repo.set("direction","English → German");restart()
        assertEquals("7",repo.pref("goal"));assertEquals(7,current().limit)
        repeat(7){action("assess","2")};action("learnSelected");action("startPractice")
        assertTrue(current().plan.filter{it.kind!=Drill.Bridge}.all{it.reverse})
        repo.save(null);repo.set("direction","German → English");repo.startSelected()
        action("startPractice");assertTrue(current().plan.none{it.reverse})
    }
    @Test fun reopeningLegacyDiscoveryRecoversHistoryAndLeavesRatingsUntouched(){
        val (a,b)=repo.words(Filters(level="A1"),2)
        val old=Session(Mode.New,listOf(a.id),Stage.Assess,limit=4,batch="older",level="A1")
        repo.save(old);repo.act(old,"assess","4");val next=current().current
        repo.event(b.id,"review",2);val history=repo.db.long("SELECT count(*) FROM event")
        repo.upgradeCourse();val now=current()
        assertEquals(listOf(a.id,next),now.ids);assertEquals(next,now.current)
        action("previous");assertEquals(a.id,current().current);assertEquals(4,repo.grade(a.id))
        action("forward");assertEquals(next,current().current)
        assertEquals(history,repo.db.long("SELECT count(*) FROM event"))
    }
    @Test fun writingAnAlternativeKeepsTheAttemptUntilTheLearnerRatesRecall(){
        select(1);action("startPractice")
        while(current().plan[current().step].kind!=Drill.Flash){action("choice",current().current);action("next")}
        val id=current().current
        action("written","another accurate phrasing")
        restart();assertTrue(current().reveal);assertEquals("another accurate phrasing",current().feedback)
        assertNull(repo.lastRating(id));action("rate","3");assertEquals(1,repo.learnedToday())
    }
    @Test fun rankingAndListeningChoicesUseCatalogEvidence(){
        assertEquals(100,usageScore(1));assertEquals(0,usageScore(68134));assertNull(usageScore(0))
        val words=repo.words(Filters(),7000)
        assertEquals(6546,words.size)
        assertTrue(words.all{usageScore(it.rank) in 0..100})
        assertTrue(words.zipWithNext().all{(a,b)->usageScore(a.rank)!!>=usageScore(b.rank)!!})
        assertEquals(words.last().rank,repo.words(Filters(sort="Lowest usage"),1).first().rank)
        val w=words.first{it.lemma=="gerade"}
        val s=Session(Mode.Custom,listOf(w.id),Stage.Practice,v3=true,plan=listOf(Turn(w.id,Drill.Audio)))
        val q=repo.question(s,w)
        assertTrue(q.audioSafe);assertEquals(4,q.choices.size);assertTrue(q.choices.contains(w))
        assertEquals(4,q.choices.map{soundClass(it.ipa)}.distinct().size)
        assertEquals(soundClass("/zaɪn/"),soundClass("/zaɪ̯n/"))
        assertNull(soundClass("/a/, /e/"))
        assertFalse(repo.question(s,w.copy(ipa="")).audioSafe)
        assertTrue(w.cloze.isNotBlank());assertTrue(w.example.contains("gerade"))
    }
}
