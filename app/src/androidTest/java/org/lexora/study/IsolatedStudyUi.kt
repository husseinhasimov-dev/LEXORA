package org.lexora.study

import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import java.util.UUID
import org.junit.After
import org.junit.Before
import org.junit.Rule

/** Keep UI fixtures independent of the installed learner's active sessions and progress. */
abstract class IsolatedStudyUi {
    @get:Rule val ui=createAndroidComposeRule<ComponentActivity>()
    protected val owner="qa-"+UUID.randomUUID()
    private lateinit var voice:Voice

    @Before fun isolatedProfile(){
        val activity=ui.activity
        ui.runOnUiThread {
            voice=Voice(activity)
            val factory=object:ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T:ViewModel> create(modelClass:Class<T>):T=StudyModel(activity.application){owner} as T
            }
            val model=ViewModelProvider(activity,factory)[StudyModel::class.java]
            activity.setContent {LexoraApp(model,voice)}
        }
    }
    @After fun closeProfile(){
        val activity=ui.activity
        ui.runOnUiThread{activity.viewModelStore.clear();voice.close()}
        activity.deleteDatabase("account-"+UUID.nameUUIDFromBytes(owner.toByteArray())+".db")
    }
}
