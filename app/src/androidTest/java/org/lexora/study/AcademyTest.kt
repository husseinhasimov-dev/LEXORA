package org.lexora.study

import androidx.test.platform.app.InstrumentationRegistry
import org.junit.*
import org.junit.Assert.*
import java.time.LocalDate
import java.util.UUID

class AcademyTest {
    private val ctx=InstrumentationRegistry.getInstrumentation().targetContext
    private val owner=UUID.randomUUID().toString()
    private var clock=1_800_000_000_000L
    private lateinit var r:StudyRepository
    @Before fun open(){r=StudyRepository(ctx,owner){clock}}
    @After fun close(){val name=java.io.File(r.db.path).name;r.close();ctx.deleteDatabase(name)}
    private fun restart(){r.close();open()}
    private fun complete(a:AcademyActivity,correct:Boolean=true){
        r.beginRound(a.key)
        a.questions.forEachIndexed{i,q->
            val before=r.round(a.key)!!
            assertEquals(i,before.step)
            r.answerAcademy(a,before,if(correct)q.key else "incorrect",2000)
            r.answerAcademy(a,before,"incorrect",2000)
            val answered=r.round(a.key)!!
            r.advanceAcademy(a,answered);r.advanceAcademy(a,answered)
        }
        assertTrue(r.round(a.key)!!.finished)
    }
    @Test fun readingFiltersMatchesAndLabelsFallback(){
        val all=OfflineReadings.passages(ctx);val index=r.readingIndex()
        val matching=recommend(all,index,ReadingPrefs(level="B2",topic="Computer science and AI",length="Medium"), emptyMap(),2)
        assertEquals(listOf("speech"),matching.map{it.p.id})
        assertTrue(matching.single().misses.isEmpty())
        val fallback=recommend(all,index,ReadingPrefs(level="A1",topic="Computer science and AI",length="Extra long"),emptyMap(),2)
        assertTrue(fallback.isNotEmpty());assertTrue(fallback.all{it.misses.isNotEmpty()})
        val market=all.first{it.id=="market"}
        val basket=index.lookup("Korb",market)!!.word
        assertEquals("nehmen",index.lookup("nehme",market)!!.word.lemma)
        assertNull(index.lookup("Nichtimkatalogxyz",market))
        val prefs=ReadingPrefs(targets=setOf(basket.id),mix="Personal targets")
        val old=recommend(all,index,prefs,emptyMap(),1).first{it.p==market}
        r.reclassify(basket.id,4)
        val updated=recommend(all,r.readingIndex(),prefs,emptyMap(),1).first{it.p==market}
        assertEquals(setOf(basket.id),updated.targets);assertEquals(old.known+1,updated.known)
    }
    @Test fun inboxDoesNotScheduleUntilStudyAndRecall(){
        val words=r.words(Filters(),100)
        words.forEach{w->repeat(2){r.collect(w.lemma,w.id,"market","Context","Everyday")}}
        assertEquals(100,r.inbox().size);assertEquals(0L,r.db.long("SELECT count(*) FROM card"))
        assertNull(r.enqueue(words.take(35).map{it.id},true));assertEquals(35,r.session()!!.ids.size)
        assertEquals(65,r.inbox().count{it.status=="Collected"})
        assertEquals(Stage.StudyAll,r.session()!!.stage);restart();assertEquals(35,r.session()!!.ids.size)
        r.act(r.session()!!,"startPractice")
        repeat(150){
            val s=r.session()!!
            if(s.stage==Stage.Practice && r.db.long("SELECT count(*) FROM card")==0L){
                val turn=s.plan[s.step]
                when(turn.kind){
                    Drill.Flash->{r.act(s,"reveal");val revealed=r.session()!!;r.act(revealed,"rate","3");r.act(revealed,"rate","3")}
                    Drill.Retry->error("Unexpected retry")
                    else->{r.act(s,"choice",turn.word);r.act(r.session()!!,"next")}
                }
            }
        }
        assertEquals(1L,r.db.long("SELECT count(*) FROM card"));assertEquals(1,r.today("new"))
        assertEquals(3L,r.academyXp());assertEquals(0,r.today("review"))
    }
    @Test fun provisionalEntriesAndReaderStateSurviveRestart(){
        val key=r.collect("Fachwerkhaus",null,"map","Ein Fachwerkhaus steht am Fluss.","History")
        assertEquals(key,r.collect("Fachwerkhaus",null,"map","Ein Fachwerkhaus steht am Fluss.","History"))
        r.readerPosition("map",1,true);r.readerPosition("map",2);restart()
        assertNull(r.inbox().single().id);assertEquals(1L,r.db.long("SELECT bookmark FROM academy_reader"))
        assertEquals(2L,r.db.long("SELECT paragraph FROM academy_reader"));assertTrue(r.selections().isEmpty())
    }
    @Test fun provisionalWordCanJoinThePersonalDictionaryAndBackup(){
        r.collect("Fachwerkbogen",null,"map","Der Fachwerkbogen steht noch.","History")
        val original=r.inbox().single()
        assertTrue(r.personal(listOf("Fachwerkbogen","timber-framed arch","B2","noun","der","Personal")))
        val word=r.words(Filters(query="Fachwerkbogen")).single()
        r.resolveInbox(original,word.id);assertEquals(word.id,r.inbox().single().id)
        val copyOwner=UUID.randomUUID().toString()
        val copy=StudyRepository(ctx,copyOwner)
        try{copy.restore(r.backup());assertEquals(word,copy.word(word.id));assertEquals(original.sentence,copy.inbox().single().sentence);assertEquals(0L,copy.academyXp())}
        finally{val name=java.io.File(copy.db.path).name;copy.close();ctx.deleteDatabase(name)}
    }
    @Test fun retryAfterFailedInitialRatingEarnsOneCorrectionAwareCredit(){
        val w=r.words(Filters()).first()
        r.event(w.id,"new",1)
        clock+=100;r.event(w.id,"answer",1,"New-word retry recalled")
        clock+=100;r.event(w.id,"answer",1,"New-word retry recalled")
        assertEquals(3L,r.academyXp());assertEquals(1,r.learnedToday())
    }
    @Test fun roundResumesWithFeedbackAndNoRepeatCompletionBonus(){
        val a=OfflineReadings.passages(ctx).first{it.id=="market"}.activity()
        r.beginRound(a.key);val first=r.round(a.key)!!
        r.answerAcademy(a,first,a.questions.first().key,1000);restart()
        assertEquals(a.questions.first().key,r.round(a.key)!!.result)
        assertEquals(0L,r.academyXp())
        r.advanceAcademy(a,r.round(a.key)!!)
        for(q in a.questions.drop(1)){r.answerAcademy(a,r.round(a.key)!!,q.key,1000);r.advanceAcademy(a,r.round(a.key)!!)}
        val xp=r.academyXp();assertTrue(xp>0)
        complete(a);assertEquals(xp,r.academyXp());assertEquals(1L,r.db.long("SELECT count(*) FROM academy_credit"))
        clock+=2*86_400_000L;complete(a);assertTrue(r.academyXp()>xp)
    }
    @Test fun masteryRequiresLearningAssessmentAndDelayedRecall(){
        val l=grammarLessons(ctx).first()
        assertEquals("Not Started",r.topicProgress(l.id).status)
        r.completeLesson(l);complete(l.activity("Practice"),false)
        assertEquals("Practice Needed",r.topicProgress(l.id).status)
        repeat(5){complete(l.activity("Practice"))}
        assertEquals("Assessment Ready",r.topicProgress(l.id).status)
        complete(l.activity("Assessment"));assertNotEquals("Mastered",r.topicProgress(l.id).status)
        clock+=86_400_001L;assertEquals("Needs Review",r.topicProgress(l.id).status)
        complete(l.activity("Delayed review"));restart();assertEquals("Mastered",r.topicProgress(l.id).status)
        clock+=31L*86_400_000;assertEquals("Needs Review",r.topicProgress(l.id).status)
        assertTrue(r.topicProgress(l.id).errors>0)
    }
    @Test fun unlimitedXpAtomicCreditsAndBackup(){
        val e=Evidence("real","Grammar","Practice","item","B2",1,1)
        assertTrue(runCatching{r.db.tx{r.record(e,"first",10,"First");error("abort")}}.isFailure)
        assertEquals(0L,r.academyXp());assertFalse(r.db.str("SELECT id FROM academy_event")!=null)
        repeat(150){i->val id=i.toString();r.record(e.copy(id=id,content=id),id,10,"Unique practice");r.record(e.copy(id=id,content=id),id,10,"Unique practice")}
        assertEquals(1500L,r.academyXp());assertEquals(16L,AcademyXp.level(r.academyXp()))
        val backup=r.backup();r.restore(backup);restart();assertEquals(1500L,r.academyXp())
        assertEquals(150,r.journal(r.day(),r.day()).size)
    }
    @Test fun questsTrackTheirOwnSkillAndRespectRest(){
        val date=LocalDate.parse(r.day());r.set("academy.days","rest")
        assertTrue(r.quests(date,emptyMap()).isEmpty())
        val q=Quest("speaking","Describe your route","Speaking","Self-reported",1,r.day(),true,true)
        r.saveQuest(q);complete(OfflineReadings.passages(ctx).first().activity());assertEquals(0,r.questCount(q,r.day()))
        r.logQuest(q);r.logQuest(q);r.questMilestones(listOf(q));r.questMilestones(listOf(q));assertEquals(1,r.questCount(q,r.day()))
        assertEquals(1L,r.db.long("SELECT count(*) FROM academy_event WHERE activity='Quest completed'"))
        val xp=r.academyXp();complete(OfflineReadings.passages(ctx)[1].activity());assertTrue(r.academyXp()>xp)
        clock+=86_400_000L
        assertEquals(q,r.quests(date.plusDays(1),emptyMap()).single());assertEquals(0,r.questCount(q,r.day()))
        r.set("academy.days","1,2,3,4,5,6,7");r.set("academy.plan."+r.day(),"")
        assertTrue(r.quests(date.plusDays(1),emptyMap()).any{it.metric=="Learn words"})
    }
    @Test fun migratePopulatedV5WithoutChangingExistingEvidence(){
        val word=r.words(Filters()).first()
        r.event(word.id,"new",3);val old=Memory.review(word.id,null,3,clock,90);r.db.saveCard(word.id,old)
        r.set("goal","35");r.set("notify.custom","existing schedule");r.toggle(word.id);r.addDeck("My words",word.id)
        r.recordExplore("Mini Stories","story-1","Detail",word.id,null,"Inside the flat",true)
        val baseline=r.backup();removeAcademySchema(r.db);r.db.version=5;restart()
        val updated=r.backup()
        for(key in listOf("events","preferences","decks","favorites","exploration","personal"))assertEquals(key,baseline.getJSONArray(key).toString(),updated.getJSONArray(key).toString())
        assertEquals(6,r.db.version);assertEquals(6546L,r.db.long("SELECT count(*) FROM word"))
        assertEquals(old.due.toEpochMilli(),r.db.long("SELECT due FROM card"))
        assertEquals("ok",r.db.str("PRAGMA integrity_check"));assertEquals(0L,r.db.long("SELECT count(*) FROM pragma_foreign_key_check"))
        val originalId=r.lastRating(word.id)!!.first;r.correctRating(word.id,originalId,1);assertEquals(0L,r.academyXp())
    }
}
