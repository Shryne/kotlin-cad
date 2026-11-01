package cad

import java.util.concurrent.TimeUnit

internal object MotionPreferences {
    val reduced: Boolean by lazy {
        System.getenv("KOTLIN_CAD_REDUCE_MOTION")?.let { return@lazy it=="1" }
        if(!System.getProperty("os.name").contains("Mac")) return@lazy false
        runCatching {
            val process=ProcessBuilder("/usr/bin/defaults","read","com.apple.universalaccess","reduceMotion").redirectError(ProcessBuilder.Redirect.DISCARD).start()
            if(!process.waitFor(1,TimeUnit.SECONDS)) { process.destroyForcibly();false }
            else process.exitValue()==0 && process.inputStream.bufferedReader().readText().trim() in setOf("1","true")
        }.getOrDefault(false)
    }
    val panelMillis get()=if(reduced) 0 else 190
    val controlMillis get()=if(reduced) 0 else 100
}
