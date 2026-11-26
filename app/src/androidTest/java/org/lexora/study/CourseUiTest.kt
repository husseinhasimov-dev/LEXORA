package org.lexora.study

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class CourseUiTest {
    @get:Rule val ui=createComposeRule()
    private val ctx=InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var repo:StudyRepository
    @Before fun open(){repo=StudyRepository(ctx,UUID.randomUUID().toString())}
    @After fun close(){val name=java.io.File(repo.db.path).name;repo.close();ctx.deleteDatabase(name)}
    @Test fun studyListScrollsToThirtiethWordAndStartsOnlyOnTap(){
        val words=repo.words(Filters(),30)
        var starts=0
        ui.setContent{MaterialTheme{StudyList(words,true,false,false,"Balanced",{},{},{},{},{}){starts++}}}
        ui.onNodeWithText("Study all words").assertIsDisplayed()
        ui.onNodeWithText("Start practice").assertExists()
        ui.onNode(hasScrollAction()).performScrollToKey(words.last().id)
        ui.onNodeWithText(words.last().german).assertIsDisplayed()
        ui.onNodeWithText(words.last().meaning).assertExists()
        ui.onNode(hasScrollAction()).performScrollToIndex(31)
        assertEquals(0,starts);ui.onNodeWithText("Start practice").performClick();assertEquals(1,starts)
    }
    @Test fun audioHidesAnswerPlaysReplaysAndFallsBackToMeaning(){
        val word=repo.words(Filters(query="gerade")).first{it.lemma=="gerade"}
        val turn=Turn(word.id,Drill.Audio)
        val s=Session(Mode.Custom,listOf(word.id),Stage.Practice,v3=true,plan=listOf(turn))
        val q=repo.question(s,word)
        val ready=mutableStateOf(true)
        var plays=0;var picked="";var action="";var speed="1.0"
        ui.setContent{MaterialTheme{Column{PracticeCard(turn,word,q,ready.value,speed,true,false,"",
            {speed=it},{assertEquals(word.german,it);plays++},{a,id->action=a;picked=id})}}}
        ui.waitForIdle()
        ui.onNodeWithText(word.meaning).assertDoesNotExist()
        ui.onNodeWithText(word.german).assertHasClickAction()
        assertEquals(1,ui.onAllNodesWithText(word.german).fetchSemanticsNodes().size)
        ui.onNodeWithText("Replay audio").performClick();assertEquals(2,plays)
        ui.onNodeWithText("Playback speed",substring=true).performClick()
        ui.onNodeWithText("0.7").performClick();assertEquals("0.7",speed)
        ui.onNodeWithText(word.german).performClick();assertEquals(word.id,picked);assertEquals("choice",action)
        ui.runOnIdle{ready.value=false}
        ui.onNodeWithText("German offline voice unavailable",substring=true).assertExists()
        ui.onNodeWithText("Replay audio").assertDoesNotExist()
        ui.onNodeWithText(word.meaning).performClick();assertEquals("meaningChoice",action)
    }
    @Test fun editDailyGoalFromDashboardControl(){
        val goal=mutableIntStateOf(10)
        ui.setContent{MaterialTheme{EditDailyGoal(goal.intValue,true){repo.setDailyGoal(it);goal.intValue=it}}}
        ui.onNodeWithText("Edit daily target").performClick()
        ui.onNode(hasSetTextAction()).performTextReplacement("30")
        ui.onNodeWithText("Save target").performClick()
        assertEquals("30",repo.pref("goal"));assertEquals(30,goal.intValue)
    }
}
