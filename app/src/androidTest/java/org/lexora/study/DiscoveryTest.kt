package org.lexora.study

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class DiscoveryTest {
    private val ctx=InstrumentationRegistry.getInstrumentation().targetContext
    private val profile=UUID.randomUUID().toString()
    private var clock=1800000000000L
    private lateinit var repo:StudyRepository
    private fun open()=StudyRepository(ctx,profile){clock}
    @Before fun prepare(){repo=open()}
    @After fun done(){val file=java.io.File(repo.db.path);repo.close();ctx.deleteDatabase(file.name)}
    private fun restart(){repo.close();repo=open()}
    private fun assess(value:Int)=repo.act(repo.session()!!,"assess",value.toString())
    private fun coverage()=repo.stats().coverage.single{it.level=="A1"}

    @Test fun knownDoesNotFillGoalAndThirtyUniqueSelectionsSurviveRestart(){
        repo.set("size","3")
        repo.startDiscovery("A1","All",30)
        val seen=mutableSetOf<String>()
        repeat(37){ i->
            val current=repo.session()!!
            assertEquals("A1",repo.word(current.current).level)
            assertTrue(seen.add(current.current))
            if(i==12){restart();assertEquals(current,repo.session())}
            assess(if(i<7)4 else 1+i%3)
            repo.act(current,"assess","2")
        }
        assertEquals(Stage.DiscoveryEnd,repo.session()!!.stage);assertEquals(30,repo.selections().size)
        assertEquals(7,coverage().known);assertEquals(30,coverage().selected)
        assertEquals(0,repo.today("new"));assertEquals(0,repo.stats().started)
        assertEquals(37,repo.db.long("SELECT count(*) FROM effective_event WHERE kind='assess'"))
        val order=repo.selections().map{it.grade};assertEquals(order.sorted(),order)
    }

    @Test fun previousAndNextPreserveCardsAndOnlyChangeTheChosenAssessment(){
        repo.startDiscovery("A1","All",3)
        val first=repo.session()!!.current
        assess(4)
        val second=repo.session()!!.current
        assess(1)
        val frontier=repo.session()!!.current
        repo.act(repo.session()!!,"previous");repo.act(repo.session()!!,"previous")
        assertEquals(first,repo.session()!!.current);assertEquals(4,repo.grade(first))
        assess(3);assertEquals(first,repo.session()!!.current)
        assertEquals(0,coverage().known);assertEquals(2,coverage().selected)
        restart();assertEquals(first,repo.session()!!.current)
        repo.act(repo.session()!!,"forward");assertEquals(second,repo.session()!!.current)
        repo.act(repo.session()!!,"forward");assertEquals(frontier,repo.session()!!.current)
        assertEquals(0,repo.db.long("SELECT count(*) FROM card"))
        assertEquals(3,repo.db.long("SELECT count(*) FROM event WHERE kind='assess'"))
    }

    @Test fun libraryReclassificationMovesLegacyKnownIntoQueue(){
        val w=repo.words(Filters(level="A1")).first()
        repo.event(w.id,"known",0)
        val before=coverage()
        assertEquals(1,before.known);assertEquals(before.total-1,before.remaining)
        repo.reclassify(w.id,1);restart()
        assertEquals(0,coverage().known);assertEquals(coverage().total,coverage().remaining)
        assertEquals(listOf(w.id),repo.selections().map{it.id});assertEquals(0,repo.today("new"))
        repo.undo(repo.latestStep());assertEquals(4,repo.grade(w.id));assertTrue(repo.selections().isEmpty())
    }

    @Test fun strictTopicExhaustionAndAlreadyClassifiedWords(){
        val topic="Small test collection"
        for(i in 1..3)repo.personal(listOf("Testwort"+i,"test word "+i,"A1","noun","das",topic))
        repo.startDiscovery("A1",topic,30)
        assess(4);assess(3);assess(1)
        repo.act(repo.session()!!,"learnSelected")
        val study=repo.session()!!
        assertEquals(Stage.StudyAll,study.stage);assertEquals(2,study.ids.size)
        repo.act(study,"startPractice");assertEquals(Stage.Practice,repo.session()!!.stage)
        repo.save(null)
        assertNotNull(repo.startDiscovery("A1",topic,30));assertNull(repo.session())
        assertNull(repo.startSelected("A1",topic));assertEquals(Stage.StudyAll,repo.session()!!.stage)
    }

    @Test fun firstRecallUndoCorrectionAndReplayKeepOneEffectiveRating(){
        repo.startDiscovery("A1","All",1);val w=repo.session()!!.current;assess(3)
        // Legacy navigation journal and corrections must still replay reviews recorded on 0.2.
        repo.save(Session(Mode.New,listOf(w),Stage.Teach,batch="legacy",limit=1))
        repo.act(repo.session()!!,"teach")
        assertEquals(Stage.Recall,repo.session()!!.stage)
        repo.act(repo.session()!!,"reveal");val rated=repo.session()!!
        repo.act(rated,"rate","4");repo.act(rated,"rate","4")
        assertEquals(1,repo.stats().todayNew);assertEquals(10,repo.stats().xp)
        restart();repo.undo(repo.latestStep());assertEquals(rated,repo.session())
        assertEquals(0,repo.db.long("SELECT count(*) FROM card"));assertEquals(0,repo.stats().xp)
        repo.act(rated,"rate","3")
        val original=repo.db.long("SELECT due FROM card")
        val id=repo.lastRating(w)!!.first
        repo.correctRating(w,id,1);assertTrue(repo.db.long("SELECT due FROM card")<original)
        assertEquals(1,repo.stats().todayNew);assertEquals(1,repo.db.long("SELECT count(*) FROM effective_event WHERE kind='new'"))
        val backup=repo.backup()
        StudyRepository(ctx,profile+"copy"){clock}.use { copy->
            copy.restore(backup);copy.restore(backup)
            assertEquals(repo.db.long("SELECT due FROM card"),copy.db.long("SELECT due FROM card"))
            assertEquals(repo.lastRating(w),copy.lastRating(w))
        }
        repo.undo(repo.latestStep());assertEquals(original,repo.db.long("SELECT due FROM card"))
        assertEquals(3,repo.lastRating(w)!!.second)
        assertEquals(2,repo.db.long("SELECT count(*) FROM event WHERE kind='new'"))
    }

    @Test fun undoScheduledReviewRestoresPreviousDueStateAndDoesNotAlterOlderHistory(){
        val w=repo.words(Filters()).first().id
        repo.event(w,"new",4)
        val first=Memory.review(w,null,4,clock,90)
        repo.db.saveCard(w,first);clock=first.due.toEpochMilli()+1000
        repo.start(Mode.Review);repo.act(repo.session()!!,"reveal");val s=repo.session()!!
        repo.act(s,"rate","1");repo.undo(repo.latestStep())
        assertEquals(0,repo.today("review"));assertEquals(1,repo.stats().due)
        assertEquals(first.due.toEpochMilli(),repo.db.long("SELECT due FROM card"))
        assertEquals(first.stability,repo.db.rows("SELECT stability FROM card"){it.getDouble(0)}.single(),0.00001)
        assertEquals(s,repo.session())
    }
    @Test fun reclassifyingScheduledWordPausesAndRestoresItsReviewQueue(){
        val w=repo.words(Filters(level="A1")).first()
        repo.event(w.id,"new",4);val c=Memory.review(w.id,null,4,clock,90);repo.db.saveCard(w.id,c)
        clock=c.due.toEpochMilli()+1
        assertEquals(1,repo.stats().due)
        repo.reclassify(w.id,4);assertEquals(0,repo.stats().due);assertEquals(1,coverage().known)
        assertEquals(c.due.toEpochMilli(),repo.db.long("SELECT due FROM card"))
        repo.reclassify(w.id,2);assertEquals(w.id,repo.selections().single().id)
        repo.undo(repo.latestStep());repo.undo(repo.latestStep())
        assertEquals(1,repo.stats().due);assertEquals(1,repo.db.long("SELECT count(*) FROM event WHERE kind='new'"))
    }

    @Test fun typedRecallStartsOnlyAfterTwoSuccessfulScheduledRecalls(){
        val w=repo.words(Filters()).first().id
        repo.event(w,"new",3);var card=Memory.review(w,null,3,clock,90);repo.db.saveCard(w,card)
        repeat(2){
            clock=card.due.toEpochMilli()+1
            repo.start(Mode.Review);assertEquals(Stage.Recall,repo.session()!!.stage)
            repo.act(repo.session()!!,"reveal");repo.act(repo.session()!!,"rate","3")
            card=repo.db.rows("SELECT * FROM card"){it.card()}.single()
        }
        clock=card.due.toEpochMilli()+1;repo.start(Mode.Review);assertEquals(Stage.Write,repo.session()!!.stage)
    }

    @Test fun previouslyKnownAndMasteredHaveDisjointCoverage(){
        val (known,master)=repo.words(Filters(level="A1"),2)
        repo.event(known.id,"known",0);repo.event(master.id,"new",4)
        val card=Memory.review(master.id,null,4,clock,90)
        card.stability=60.0;repo.db.saveCard(master.id,card)
        val c=coverage();assertEquals(1,c.known);assertEquals(1,c.mastered)
        assertEquals(c.total-2,c.remaining);assertEquals(c.remaining,c.unseen)
        assertEquals(1,repo.today("new"));assertEquals(0,c.reviewing)
    }
}
