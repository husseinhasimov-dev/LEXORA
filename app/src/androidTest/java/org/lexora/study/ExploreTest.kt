package org.lexora.study

import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import java.util.UUID

class ExploreTest{
    private val ctx=InstrumentationRegistry.getInstrumentation().targetContext
    private val owner=UUID.randomUUID().toString()
    private lateinit var repo:StudyRepository
    private lateinit var shelf:ExploreShelf
    @Before fun open(){repo=StudyRepository(ctx,owner);shelf=ExploreShelf(repo.db.rows("SELECT * FROM word ORDER BY rank,id"){it.word()},passages(ctx))}
    @After fun close(){val name=java.io.File(repo.db.path).name;repo.close();ctx.deleteDatabase(name)}
    @Test fun everyPassageAndFamilyLinksToOriginalWords(){
        assertEquals(6546,shelf.words.size);assertEquals(12,shelf.passages.distinctBy{it.id}.size)
        for(p in shelf.passages){
            assertEquals(2,p.targets.distinct().size);p.targets.forEach{assertNotNull(shelf.byLemma[it])}
            val qs=p.questions(shelf);assertEquals(if(p.kind=="L")4 else 2,qs.size)
            qs.forEach{q->assertEquals(1,q.choices.count{it==q.key});assertEquals(q.choices.distinct(),q.choices)
                if(p.kind=="L")assertTrue(p.text.contains(q.spoken) && q.spoken.isNotBlank())}
        }
        familyLinks.forEachIndexed{i,t->val a=shelf.byLemma.getValue(t.first);val b=shelf.byLemma.getValue(t.second)
            assertNotEquals(a.id,b.id);val (clue,key)=familyExamples[i];val qs=familyQuestions(a,b,key,clue,shelf.words)
            assertTrue(qs.first().choices.contains(b.lemma));assertTrue(qs.last().accepts(key))}
    }
    @Test fun contextScoringRestartAndNoScheduling(){
        val w=shelf.contexts.first{it.level=="B1"};val qs=contextPrompts(w,shelf.contexts,false)
        assertEquals(4,qs.distinctBy{it.part}.size);assertEquals(w.example,qs.last().key)
        assertEquals(qs.last().choices.sorted(),w.example.split(Regex("\\s+")).sorted())
        assertTrue(qs[2].accepts(" "+w.answer+" "));assertFalse(qs[2].accepts("unrelated"))
        repo.beginExplore("Context Mastery",w.id,qs)
        val id=JSONObject(repo.pref("explore.session")).getString("id")
        repo.answerExplore(id,0,"wrong");repo.answerExplore(id,0,qs.first().key);repo.close();repo=StudyRepository(ctx,owner)
        assertEquals("wrong",JSONObject(repo.pref("explore.session")).getString("feedback"))
        repo.nextExplore(id,0);repo.nextExplore(id,0)
        for(i in 1 until qs.size){repo.answerExplore(id,i,qs[i].key);repo.nextExplore(id,i)}
        assertEquals(5,repo.attempts().size);assertEquals(3,repo.attempts().count{it.ok && it.part!="Completed"})
        assertEquals(0,repo.db.long("SELECT count(*) FROM card"));assertEquals(0,repo.db.long("SELECT count(*) FROM event"))
        val backup=repo.backup();repo.restore(backup);assertEquals(5,repo.attempts().size)
    }
    @Test fun confusionNeedsEvidenceClearsAfterRepairAndCanBeDismissed(){
        val a=shelf.byLemma.getValue("kennen");val b=shelf.byLemma.getValue("wissen");val key=pairKey(a.id,b.id)
        assertEquals(key,pairKey(b.id,a.id))
        fun write(mode:String,part:String,target:Word,other:Word,ok:Boolean){repo.recordExplore(mode,key,part,target.id,other.id,other.lemma,ok)}
        write("confusion","Choice",a,b,false);assertFalse(pairActive(repo.attempts()))
        write("confusion","Choice",b,a,false);assertTrue(pairActive(repo.attempts()))
        write("Confusion Lab","Contrast",a,b,true);write("Confusion Lab","Contrast",b,a,true);assertFalse(pairActive(repo.attempts()))
        write("confusion","Marked",a,b,true);assertTrue(pairActive(repo.attempts()))
        write("confusion","Dismissed",a,b,true);assertFalse(pairActive(repo.attempts()))
    }
    @Test fun wrongChoiceFromOrdinaryPracticeRecordsOnlyObservedPairs(){
        val w=shelf.byLemma.getValue("kennen")
        val other=shelf.byLemma.getValue("wissen")
        val round=Session(Mode.Custom,listOf(w.id),Stage.Practice,v3=true,plan=listOf(Turn(w.id,Drill.Choice,true)))
        repeat(2){
            repo.save(round)
            repo.act(round,"choice",other.id)
            repo.act(repo.session()!!,"choice",other.id)
        }
        val observed=repo.attempts()
        assertEquals(2,observed.size)
        assertTrue(observed.all{it.target==w.id && it.other==other.id && !it.ok})
        assertTrue(pairActive(observed))
        val written=round.copy(stage=Stage.Write,v3=false)
        repo.save(written);repo.act(written,"answer","not-a-known-headword")
        val miss=repo.attempts().last()
        assertEquals("spelling",miss.mode);assertNull(miss.other)
        assertEquals(0,repo.db.long("SELECT count(*) FROM card"))
    }
    @Test fun v4MigrationPreservesActualRatingsAndSettings(){
        val w=shelf.byLemma.getValue("wohnen");repo.event(w.id,"new",3)
        val c=Memory.review(w.id,null,3,repo.now(),90);repo.db.saveCard(w.id,c);repo.setDailyGoal(32)
        removeAcademySchema(repo.db);repo.db.execSQL("DROP TABLE exploration");repo.db.version=4;repo.close();repo=StudyRepository(ctx,owner)
        assertEquals(6,repo.db.version);assertEquals(32,repo.pref("goal").toInt());assertEquals(c.due.toEpochMilli(),repo.db.long("SELECT due FROM card"))
        assertEquals(1,repo.today("new"));assertEquals(0,repo.db.long("SELECT count(*) FROM pragma_foreign_key_check"))
    }
}
