package org.lexora.study

import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class GermanSpeechTest {
    @Test fun installedGermanOfflineVoiceSynthesizesAudioAndAcceptsPlayback(){
        assumeTrue(InstrumentationRegistry.getArguments().getString("germanSpeech")=="true")
        val instrument=InstrumentationRegistry.getInstrumentation()
        val context=instrument.targetContext
        val init=CountDownLatch(1)
        val synth=TextToSpeech(context){if(it==TextToSpeech.SUCCESS)init.countDown()}
        val output=File.createTempFile("german-voice",".wav",context.cacheDir)
        lateinit var voice:Voice
        instrument.runOnMainSync{voice=Voice(context)}
        try{
            assertTrue("Speech engine initialization",init.await(15,TimeUnit.SECONDS))
            val de=synth.voices.first{it.locale.language=="de" && !it.isNetworkConnectionRequired}
            assertEquals(TextToSpeech.SUCCESS,synth.setVoice(de))
            val complete=CountDownLatch(1)
            synth.setOnUtteranceProgressListener(object:UtteranceProgressListener(){
                override fun onStart(id:String?){}
                override fun onDone(id:String?){complete.countDown()}
                @Deprecated("Android callback") override fun onError(id:String?){}
            })
            assertEquals(TextToSpeech.SUCCESS,synth.synthesizeToFile("Ich bin gerade in der Küche.",Bundle(),output,"german"))
            assertTrue("German synthesis completed",complete.await(15,TimeUnit.SECONDS))
            val bytes=output.readBytes()
            assertEquals("RIFF",String(bytes,0,4,Charsets.US_ASCII))
            assertTrue("Non-silent waveform",bytes.size>1024 && bytes.drop(100).distinct().size>20)
            for(passage in passages(context).filter{it.kind=="L"}){
                val rendered=CountDownLatch(1)
                synth.setOnUtteranceProgressListener(object:UtteranceProgressListener(){
                    override fun onStart(id:String?){}
                    override fun onDone(id:String?){rendered.countDown()}
                    @Deprecated("Android callback") override fun onError(id:String?){}
                })
                synth.setSpeechRate(.92f)
                assertEquals(TextToSpeech.SUCCESS,synth.synthesizeToFile(passage.text,Bundle(),output,passage.id))
                assertTrue(rendered.await(30,TimeUnit.SECONDS))
                val reader=android.media.MediaMetadataRetriever()
                val millis=try{reader.setDataSource(output.absolutePath);reader.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_DURATION)!!.toLong()}finally{reader.release()}
                android.util.Log.i("LexoraQA","Passage duration "+passage.id+" "+millis+" ms")
                assertTrue("Nontrivial passage at offline voice rate: "+millis,millis in 19500L..60000L)
            }
            instrument.runOnMainSync{
                val lines=passages(context).first{it.kind=="L"}.sentences
                assertTrue(voice.playPassage(lines,.92f));voice.pausePassage();assertTrue(voice.passagePaused)
                assertTrue(voice.resumePassage());voice.stop();assertFalse(voice.passagePlaying)
                assertTrue(voice.status,voice.ready)
                assertTrue(voice.play("gerade",0.85f));assertTrue(voice.play("gerade",1.15f))
            }
        }finally{instrument.runOnMainSync{voice.close()};synth.shutdown();output.delete()}
    }
}
