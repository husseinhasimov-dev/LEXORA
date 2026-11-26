package org.lexora.study

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.*
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class NavigationTest{
    @get:Rule val app=createAndroidComposeRule<MainActivity>()
    @Test fun homeLibrarySearchAndOpenWord(){
        app.waitUntil(20000){app.onAllNodesWithText("Start at A1").fetchSemanticsNodes().isNotEmpty() || app.onAllNodesWithText("Your study day").fetchSemanticsNodes().isNotEmpty()}
        if(app.onAllNodesWithText("Start at A1").fetchSemanticsNodes().isNotEmpty())app.onNodeWithText("Start at A1").performClick()
        app.onNodeWithText("Library").performClick()
        app.onNodeWithText("Search German or English").performTextInput("Bahnhof")
        app.waitUntil(15000){app.onAllNodesWithText("der Bahnhof").fetchSemanticsNodes().isNotEmpty()}
        app.onNodeWithText("der Bahnhof").performClick()
        app.waitUntil(10000){app.onAllNodesWithText("Listen to word").fetchSemanticsNodes().isNotEmpty()}
        app.onNodeWithText("Done").performClick()
        app.onNodeWithText("Statistics").performClick()
        app.onNodeWithText("VOCABULARY COVERAGE").assertExists()
    }
}
