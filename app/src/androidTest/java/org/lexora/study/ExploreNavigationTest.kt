package org.lexora.study

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Rule
import org.junit.Test

class ExploreNavigationTest{
    @get:Rule val ui=createAndroidComposeRule<MainActivity>()
    private fun waitFor(text:String){ui.waitUntil(20000){ui.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()}}
    private fun tap(text:String){
        val enabled=hasText(text) and hasClickAction() and isEnabled()
        try {
            ui.waitUntil(20000){ui.onAllNodes(enabled).fetchSemanticsNodes().isNotEmpty()}
        } catch(e:Throwable) {
            throw AssertionError("Expected enabled button: "+text+"\n"+ui.onRoot().printToString(),e)
        }
        val button=ui.onAllNodes(enabled).onLast()
        if(!button.isDisplayed())button.performScrollTo()
        button.performClick()
    }
    private fun endRound(){
        tap("End saved round")
        ui.waitUntil(10000){ui.onAllNodesWithText("End saved round").fetchSemanticsNodes().isEmpty()}
    }
    @Test fun visitEveryModeAndAnswerRealExercises(){
        ui.waitUntil(20000){ui.onAllNodesWithText("Start at A1").fetchSemanticsNodes().isNotEmpty() || ui.onAllNodesWithText("Your study day").fetchSemanticsNodes().isNotEmpty()}
        if(ui.onAllNodesWithText("Start at A1").fetchSemanticsNodes().isNotEmpty())tap("Start at A1")
        tap("Practice & Explore")
        tap("Context Mastery")
        ui.onNodeWithText("Find a German word").performTextInput("Beweis")
        tap("der Beweis · B1");waitFor("Pause practice")
        tap("Pause practice");endRound();tap("Back")
        tap("Confusion Lab");ui.onNodeWithText("First German headword").performTextInput("wissen");ui.onNodeWithText("Second German headword").performTextInput("kennen")
        tap("Mark pair")
        tap("Compare in practice")
        waitFor("CONTRAST · 1 / 2")
        tap("kennen")
        tap("Continue")
        waitFor("CONTRAST · 2 / 2")
        tap("wissen")
        tap("Continue")
        tap("Finish")
        ui.waitUntil(10000){ui.onAllNodesWithText("Round complete").fetchSemanticsNodes().isEmpty() && ui.onAllNodesWithText("Pause practice").fetchSemanticsNodes().isEmpty()}
        tap("Back")
        tap("Word Families");tap("Practise this family");waitFor("Pause practice");tap("Pause practice");endRound();tap("Back")
        tap("Listening in Context");tap("Beim Bäcker")
        if(InstrumentationRegistry.getArguments().getString("germanSpeech")=="true"){
            tap("Play passage");tap("Start listening");tap("Play or replay");tap("Brot");waitFor("Correct");tap("Study transcript")
            tap("Pause practice");endRound()
        }
        tap("Back");tap("Mini Stories");tap("Der zweite Schlüssel");tap("Check comprehension");tap("Inside the flat");tap("Continue")
        tap("Flowers");tap("Continue");waitFor("2 / 2 correct");tap("Finish")
    }
}
