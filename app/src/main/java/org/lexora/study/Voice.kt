package org.lexora.study

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED
import android.widget.Toast
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

class Voice(val ctx:Context) : AutoCloseable {
    private var sequence=0L
    private var lines:List<String> = emptyList()
    private var currentLine=0
    private var rate=1f
    var passagePlaying by mutableStateOf(false)
        private set
    var passagePaused by mutableStateOf(false)
        private set
    private fun playbackEvent(id:String?,done:Boolean){
        val parts=id?.split("/")?:return
        if(parts.size!=3 || parts[0]!="passage")return
        android.os.Handler(android.os.Looper.getMainLooper()).post {
            if(parts[1].toLongOrNull()==sequence && passagePlaying){
                val index=parts[2].toIntOrNull()?:return@post
                currentLine=index
                if(done && index==lines.lastIndex){passagePlaying=false;passagePaused=false}
            }
        }
    }
    private var engine:TextToSpeech?=null
    var ready by mutableStateOf(false)
        private set
    var status by mutableStateOf("Checking German voice…")
        private set
    init{
        engine=TextToSpeech(ctx.applicationContext){initResult->
            val t=engine
            if(initResult==TextToSpeech.SUCCESS && t!=null){
                val voice=t.voices.orEmpty().filter {it.locale.language=="de" && !it.isNetworkConnectionRequired && KEY_FEATURE_NOT_INSTALLED !in it.features.orEmpty()}
                    .sortedWith(compareByDescending<android.speech.tts.Voice>{it.locale.country=="DE"}.thenByDescending {it.quality}).firstOrNull()
                ready=voice!=null && t.setVoice(voice)==TextToSpeech.SUCCESS
                t.setOnUtteranceProgressListener(object:android.speech.tts.UtteranceProgressListener(){
                    override fun onStart(id:String?) {playbackEvent(id,false)}
                    override fun onDone(id:String?) {playbackEvent(id,true)}
                    @Deprecated("Required by Android") override fun onError(id:String?) {
                        android.os.Handler(android.os.Looper.getMainLooper()).post {
                            if(engine===t){passagePlaying=false;passagePaused=false;ready=false;status="Offline German speech unavailable"}
                        }
                    }
                })
            }
            status=if(ready)"Offline German voice ready" else "German offline voice unavailable · tap Listen for setup"
        }
    }
    fun play(text:String,speed:Float):Boolean{
        stop()
        if(ready){
            engine?.setSpeechRate(speed)
            if(engine?.speak(text,TextToSpeech.QUEUE_FLUSH,null,"lexora")==TextToSpeech.SUCCESS)return true
            ready=false
            status="Offline German speech unavailable"
        }
        Toast.makeText(ctx,"Open Android Settings, search for Text-to-speech, then install German voice data in your engine’s settings.",Toast.LENGTH_LONG).show()
        return false
    }
    fun playPassage(sentences:List<String>,speed:Float,resume:Boolean=false):Boolean{
        if(sentences.isEmpty())return false
        if(!ready)return play(sentences.first(),speed)
        val start=if(resume && passagePaused && sentences==lines)currentLine else 0
        stop();lines=sentences;rate=speed;currentLine=start
        val t=engine?:return false
        t.setSpeechRate(speed);passagePlaying=true
        for(i in start..sentences.lastIndex){
            if(t.speak(sentences[i],if(i==start)TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD,null,"passage/"+sequence+"/"+i)!=TextToSpeech.SUCCESS){
                stop();return false
            }
        }
        return true
    }
    fun pausePassage(){if(passagePlaying){sequence++;passagePlaying=false;passagePaused=true;engine?.stop()}}
    fun resumePassage()=playPassage(lines,rate,true)
    fun stop(){sequence++;passagePlaying=false;passagePaused=false;engine?.stop()}
    override fun close(){engine?.stop();engine?.shutdown();engine=null;ready=false}
}
