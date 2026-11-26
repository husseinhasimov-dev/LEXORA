package org.lexora.study

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.json.JSONObject
import java.util.UUID
import java.io.StringWriter
import java.time.Instant

@RunWith(AndroidJUnit4::class)
class RepositoryTest{
    val ctx=InstrumentationRegistry.getInstrumentation().targetContext
    val owner=UUID.randomUUID().toString()
    var tick=Instant.parse("2025-04-01T12:00:00Z").toEpochMilli()
    lateinit var r:StudyRepository
    @Before fun setup(){r=StudyRepository(ctx,owner){tick}}
    @After fun cleanup(){val path=r.db.path;r.close();ctx.deleteDatabase(java.io.File(path).name)}
    @Test fun catalogAndPlacement(){
        val st=r.stats()
        assertTrue(st.total>6000);assertEquals(st.total.toLong(),r.db.long("SELECT count(DISTINCT lemma) FROM word"))
        assertEquals(4,st.coverage.size);assertEquals(0,st.started)
        assertNull(r.start(Mode.Placement))
        repeat(8){val s=r.session()!!;val w=r.word(s.current);assertTrue(r.choices(s,w).size>=2)
            r.act(s,"answer",w.meaning);r.act(r.session()!!,"next")}
        assertNull(r.session());assertEquals("B2",r.pref("level"));assertEquals(0,r.stats().started)
    }
    @Test fun sixStagesSurviveRestartAndMistakesDoNotReschedule(){
        r.set("size","1");r.set("goal","1")
        val w=r.words(Filters(query="Bahnhof")).first();val start=Session(Mode.New,listOf(w.id),Stage.Discover,limit=1);r.save(start)
        r.act(start,"discover");r.act(r.session()!!,"answer","wrong");r.act(r.session()!!,"next")
        r.close();r=StudyRepository(ctx,owner){tick}
        assertEquals(Stage.Recognize,r.session()!!.stage);assertEquals(w.id,r.session()!!.current)
        r.act(r.session()!!,"answer",w.meaning);r.act(r.session()!!,"next")
        r.act(r.session()!!,"reveal");r.act(r.session()!!,"rate","3")
        r.act(r.session()!!,"answer",w.german);r.act(r.session()!!,"next")
        if(r.session()!=null){assertEquals(Stage.Context,r.session()!!.stage);r.act(r.session()!!,"answer",w.answer);r.act(r.session()!!,"next")}
        assertNull(r.session());assertEquals(1,r.stats().todayNew);assertNotNull(r.start(Mode.New))
        val due=r.db.long("SELECT due FROM card")
        r.start(Mode.Lab);assertEquals(Stage.Recognize,r.session()!!.stage);r.act(r.session()!!,"answer",w.meaning);r.act(r.session()!!,"next")
        assertEquals(due,r.db.long("SELECT due FROM card"));assertEquals(0,r.stats().todayReview)
        tick=due+1000;r.start(Mode.Review);r.act(r.session()!!,"reveal")
        val before=r.session()!!;r.act(before,"rate","3");r.act(before,"rate","3")
        assertEquals(1,r.stats().todayReview);assertEquals(100,r.stats().success);assertEquals(15,r.stats().xp)
    }
    @Test fun transactionalCsvAndBackup(){
        val csv="german,english,level,pos,article,topic\r\nPrüfbegriff,\"test, term\",B2,noun,der,Personal\r\n"
        r.importCsv(csv.reader());assertTrue(r.importCsv(csv.reader()).contains("1 duplicates"))
        assertEquals(1,r.db.long("SELECT count(*) FROM word WHERE source='Personal'"))
        val out=StringWriter();r.exportCsv(out);assertTrue(out.toString().contains("\"test, term\""))
        val anki=StringWriter();r.exportCsv(anki,true)
        assertTrue(r.importCsv(anki.toString().reader(),true).contains("1 duplicates"))
        val bad=csv.replace("Prüfbegriff","Probebegriff")+"ungültig,broken,X9,noun,der,Test"
        assertTrue(runCatching{r.importCsv(bad.reader())}.isFailure)
        assertEquals(0,r.db.long("SELECT count(*) FROM word WHERE lemma='Probebegriff'"))
        val w=r.words(Filters(query="Prüfbegriff")).first()
        r.addDeck("Study",w.id);r.toggle(w.id);r.event(w.id,"answer",0,"Article")
        val backup=r.backup()
        StudyRepository(ctx,owner+"other"){tick}.use{other->
            other.restore(backup);other.restore(backup)
            assertEquals(1,other.db.long("SELECT count(*) FROM event"));assertEquals(1,other.words(Filters(deck="Study")).size)
            assertTrue(other.favorite(w.id));val out2=StringWriter();other.exportCsv(out2);assertEquals(out.toString(),out2.toString())
        }
    }
    @Test fun concurrentOfflineDevicesReplayInTimeOrder(){
        val w=r.words(Filters()).first()
        fun event(day:Int,rating:Int)=JSONObject().put("id",UUID.randomUUID().toString()).put("word",w.id)
            .put("kind",if(day==0)"new" else "review").put("score",rating).put("detail","").put("at",tick+day*86400000L)
            .put("day","2025-04-0"+(day+1)).put("retention",90)
        val e=listOf(event(0,4),event(2,3),event(3,1))
        r.mergeEvents(e.reversed());assertEquals(0,r.mergeEvents(e))
        val c=r.db.rows("SELECT * FROM card"){it.card()}.single()
        val expected=e.fold(null as io.github.openspacedrepetition.Card?){v,j->Memory.review(w.id,v,j.getInt("score"),j.getLong("at"),90)}!!
        assertEquals(expected.due,c.due);assertEquals(expected.stability,c.stability,0.000001);assertEquals(3,r.db.long("SELECT count(*) FROM event"))
    }
    @Test fun migrationKeepsExistingData(){
        r.set("goal","15");val w=r.words(Filters()).first();r.event(w.id,"known",0)
        for(view in listOf("active_card","assessment","effective_event","live_event"))r.db.execSQL("DROP VIEW "+view)
        r.db.execSQL("DROP TABLE undo_step");r.db.execSQL("DROP INDEX event_correction")
        r.db.execSQL("DROP TABLE exploration");r.db.execSQL("DROP TABLE content_v4");r.db.execSQL("DROP TABLE synced");r.db.version=1;r.close()
        r=StudyRepository(ctx,owner){tick}
        assertEquals(5,r.db.version);assertEquals("15",r.pref("goal"));assertEquals(1,r.db.long("SELECT count(*) FROM event"))
    }
    @Test fun hundredsOfDueCardsRespectReviewLimitAfterSeveralOfflineDays(){
        val words=r.words(Filters(),250)
        r.db.tx {words.forEach {w->
            r.event(w.id,"new",4);r.db.saveCard(w.id,Memory.review(w.id,null,4,tick,90))
        }}
        tick+=20*86400000L
        assertEquals(250,r.stats().due)
        r.set("reviewLimit","3");r.set("size","15");r.start(Mode.Review)
        assertEquals(3,r.session()!!.limit)
        repeat(3){r.act(r.session()!!,"reveal");r.act(r.session()!!,"rate","3")}
        assertNotNull(r.start(Mode.Review));assertEquals(247,r.stats().due)
        r.set("reviewLimit","5");assertNull(r.start(Mode.Review));assertEquals(2,r.session()!!.limit)
        assertEquals(1,r.stats().streak)
    }
    @Test fun simultaneousIntroductionsCountOneNewWord(){
        val word=r.words(Filters()).first().id
        r.event(word,"new",4);tick+=1000;r.event(word,"new",3)
        assertEquals(1,r.stats().todayNew);assertEquals(1,r.stats().todayReview)
        assertEquals(15,r.stats().xp)
    }
}
