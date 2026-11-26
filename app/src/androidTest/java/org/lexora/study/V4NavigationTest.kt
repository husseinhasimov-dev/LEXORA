package org.lexora.study

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import android.app.Notification
import android.app.NotificationManager
import android.os.Build
import org.junit.*
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class V4NavigationTest{
    @get:Rule val ui=createAndroidComposeRule<MainActivity>()
    private fun home(){
        ui.waitUntil(20000){ui.onAllNodesWithText("Your study day").fetchSemanticsNodes().isNotEmpty() || ui.onAllNodesWithText("Start at A1").fetchSemanticsNodes().isNotEmpty()}
        if(ui.onAllNodesWithText("Start at A1").fetchSemanticsNodes().isNotEmpty())ui.onNodeWithText("Start at A1").performClick()
    }
    @Test fun dashboardOpensDiscoveryDateReviewsAndAcademicSearch(){
        home()
        ui.onNodeWithText("Today’s words").assertExists()
        ui.onNodeWithText("Review by date").performScrollTo().performClick()
        ui.onNodeWithText("First studied · Today",substring=true).performClick()
        ui.onNodeWithText("Custom date range").performClick()
        ui.onNodeWithText("Through · ",substring=true).assertExists()
        ui.onNodeWithText("Cancel").performClick()
        ui.onNodeWithText("TestDaF Academic Dictionary").performScrollTo().performClick()
        ui.onNodeWithText("Search academic German or English").performTextInput("Vorlesung")
        ui.onNodeWithText("die Vorlesung").assertExists()
        ui.onNodeWithText("Back to library").performClick()
        ui.onNodeWithText("Home").performClick()
        ui.onNodeWithText("Discover words").performScrollTo().performClick()
        ui.onNodeWithText("Begin discovery").assertExists()
        ui.onNodeWithText("Cancel").performClick()
    }
    @Test fun notificationEditorSavesPreviewsAndDeletes(){
        val device = InstrumentationRegistry.getInstrumentation()
        val context = device.targetContext
        if (Build.VERSION.SDK_INT >= 33) {
            device.uiAutomation.grantRuntimePermission(context.packageName, android.Manifest.permission.POST_NOTIFICATIONS)
        }
        val notifications = context.getSystemService(NotificationManager::class.java)
        home();ui.onNodeWithText("Profile").performClick()
        ui.onNodeWithText("Manage notifications").performScrollTo().performClick()
        ui.onNodeWithText("Add reminder").performScrollTo().performClick()
        ui.onNodeWithText("Reminder name").performScrollTo().performTextReplacement("Test seminar")
        ui.onNodeWithText("Notification title").performScrollTo().performTextReplacement("Test seminar")
        ui.onNodeWithText("Custom message").performTextInput("Read the examples.")
        ui.onNodeWithText("Enabled").performClick()
        ui.onNodeWithText("Save reminder").performScrollTo().performClick()
        ui.waitUntil(10000){ui.onAllNodesWithText("Test seminar").fetchSemanticsNodes().size == 1}
        ui.onNodeWithText("Test seminar").performScrollTo().assertIsDisplayed()
        ui.onAllNodesWithText("Preview").onLast().performScrollTo().performClick()
        ui.onNodeWithText("Send preview").performClick()
        try {
            ui.waitUntil(10000) {
                notifications.activeNotifications.any {
                    val extras = it.notification.extras
                    extras.getString(Notification.EXTRA_TITLE) == "Preview · Test seminar" &&
                        extras.getString(Notification.EXTRA_TEXT) == "Read the examples."
                }
            }
        } finally {
            notifications.cancel("preview",0)
        }
        ui.onAllNodesWithText("Edit").onLast().performScrollTo().performClick()
        ui.onNodeWithText("Read the examples.").assertExists()
        ui.onNodeWithText("Cancel").performClick()
        ui.onAllNodesWithText("Delete").onLast().performScrollTo().performClick()
        ui.onNodeWithText("Confirm").performClick()
        ui.waitUntil(5000){ui.onAllNodesWithText("Test seminar").fetchSemanticsNodes().isEmpty()}
    }
}
