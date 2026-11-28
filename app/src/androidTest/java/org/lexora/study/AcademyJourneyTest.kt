package org.lexora.study

import androidx.compose.ui.test.*
import androidx.activity.compose.setContent
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File

class AcademyJourneyTest {
    @get:Rule val ui=createAndroidComposeRule<androidx.activity.ComponentActivity>()
    private val owner="qa-"+java.util.UUID.randomUUID()
    private lateinit var voice:Voice
    @org.junit.Before fun isolatedProfile(){
        val activity=ui.activity
        ui.runOnUiThread{
            voice=Voice(activity)
            val factory=object:androidx.lifecycle.ViewModelProvider.Factory{
                @Suppress("UNCHECKED_CAST")
                override fun <T:androidx.lifecycle.ViewModel> create(modelClass:Class<T>):T=
                    StudyModel(activity.application){owner} as T
            }
            val vm=androidx.lifecycle.ViewModelProvider(activity,factory)[StudyModel::class.java]
            activity.setContent {LexoraApp(vm,voice)}
        }
    }
    @org.junit.After fun closeProfile(){
        val activity=ui.activity
        ui.runOnUiThread{activity.viewModelStore.clear();voice.close()}
        activity.deleteDatabase("account-"+java.util.UUID.nameUUIDFromBytes(owner.toByteArray())+".db")
    }
    private fun find(label:String)=hasText(label) and hasClickAction() and isEnabled()
    private fun waitText(label:String){ui.waitUntil(25000){ui.onAllNodesWithText(label).fetchSemanticsNodes().isNotEmpty()}}
    private fun tap(label:String){
        try{ui.waitUntil(25000){ui.onAllNodes(find(label)).fetchSemanticsNodes().isNotEmpty()}}
        catch(e:Throwable){throw AssertionError("Missing "+label+"\n"+ui.onRoot().printToString(7),e)}
        val node=ui.onAllNodes(find(label)).onLast()
        if(!node.isDisplayed())node.performScrollTo()
        node.performClick()
    }
    private fun shot(name:String){
        val ctx=InstrumentationRegistry.getInstrumentation().targetContext
        File(ctx.filesDir,"v6-"+name+".png").outputStream().use{ui.onRoot().captureToImage().asAndroidBitmap().compress(android.graphics.Bitmap.CompressFormat.PNG,100,it)}
    }
    @Test fun readCollectAndStartVocabularyPractice(){
        waitText("Your study day")
        if(ui.onAllNodesWithText("Start at A1").fetchSemanticsNodes().isNotEmpty())tap("Start at A1")
        tap("Learning Academy");tap("Reading Academy");tap("Read Der letzte Einkauf");waitText("Start comprehension")
        shot("reader");tap("Words in this paragraph");tap("Korb");tap("Save for later")
        tap("Hide paragraph words");tap("Bookmark this paragraph");tap("Start comprehension")
        tap("Bread");tap("Next question");tap("True");tap("Next question")
        waitText("Your answer");ui.onNodeWithText("Your answer").performTextInput("vier");tap("Check answer");tap("Finish round");waitText("Round complete")
        tap("Return");tap("‹ Back");tap("‹ Back");tap("Unknown Words Inbox");waitText("Korb")
        tap("Check visible words");tap("Study checked words");waitText("Start practice")
        tap("Start practice")
    }
    @Test fun grammarRecoveryAndQuestBeyondDailyGoal(){
        waitText("Your study day")
        if(ui.onAllNodesWithText("Start at A1").fetchSemanticsNodes().isNotEmpty())tap("Start at A1")
        tap("Learning Academy");tap("Grammar Academy");tap("Open Personal pronouns")
        if(ui.onAllNodesWithText("Finish studying lesson").fetchSemanticsNodes().isNotEmpty())tap("Finish studying lesson")
        tap("Practice");waitText("Your answer");ui.onNodeWithText("Your answer").performTextInput("Er");tap("Check answer");tap("Finish round");waitText("Round complete")
        tap("Return");waitText("A1 · ASSESSMENT READY")
        shot("grammar");tap("‹ Back");tap("‹ Back");tap("Daily quests");tap("Create task")
        ui.onNodeWithText("Task name").performTextInput("Describe my day");tap("Save task");tap("Log one completion");waitText("1 / 1")
        tap("More reading");waitText("Reading Academy")
    }
}
