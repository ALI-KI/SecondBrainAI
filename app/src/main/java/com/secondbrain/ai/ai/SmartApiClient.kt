package com.secondbrain.ai.ai

import android.content.Context
import android.media.MediaMetadataRetriever
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID

/** Explicit opt-in API calls; no database, ZIP, or attachments are uploaded automatically. */
class SmartApiClient(private val context:Context) {
    private val vault=ApiKeyVault(context)
    private val budget=BudgetGuard(context)
    private fun requireKey():String = vault.get() ?: error("أضف مفتاح API من الإعدادات أولًا")
    private fun ensureSmart(){
        if(!context.getSharedPreferences("secondbrain_settings",0).getBoolean("smart_mode",false))
            error("فعّل الوضع الذكي قبل الاتصال بالـ API")
    }
    private fun connect(path:String,key:String,contentType:String):HttpURLConnection {
        val connection=(URL("https://api.openai.com/v1/$path").openConnection() as HttpURLConnection)
        connection.requestMethod="POST"
        connection.connectTimeout=20000;connection.readTimeout=100000
        connection.doOutput=true
        connection.setRequestProperty("Authorization","Bearer $key")
        connection.setRequestProperty("Content-Type",contentType)
        return connection
    }
    private fun readResponse(c:HttpURLConnection):JSONObject {
        val status=c.responseCode
        val body=(if(status in 200..299)c.inputStream else c.errorStream)?.bufferedReader(Charsets.UTF_8)?.use{it.readText()}.orEmpty()
        if(status !in 200..299){
            val json=runCatching{JSONObject(body)}.getOrNull()
            val msg=json?.optJSONObject("error")?.optString("message")?.take(180) ?: "HTTP $status"
            error("فشل الاتصال بالخدمة: $msg")
        }
        return JSONObject(body)
    }
    suspend fun ask(userText:String,contextSnippet:String="",instructions:String=""):String=withContext(Dispatchers.IO) {
        ensureSmart()
        require(userText.isNotBlank()){"الرسالة فارغة"}
        val localContext=contextSnippet.take(8500)
        val prompt=userText.take(4500)
        val total=prompt.length+localContext.length+instructions.length
        val estimate=budget.estimateTextSar(total)
        if(!budget.maySpend(estimate))error("وصلت للحد المحلي الآمن (15 ريال تقريبًا)؛ الوضع العادي متاح")
        val json=JSONObject().apply {
            put("model","gpt-6-luna")
            put("store",false) // do not create remote stored conversation state
            put("max_output_tokens",900)
            put("instructions", if(instructions.isBlank())
                "أنت مساعد إداري شخصي. أجب بالعربية بإيجاز. استند فقط إلى السياق المرفق؛ إذا لم توجد معلومة قل إنها غير مسجلة. لا تدّع تنفيذ إجراءات." else instructions)
            put("input", buildString {
                if(localContext.isNotBlank())appendLine("سياق محلي اختاره المستخدم:\n$localContext\n")
                append("طلب المستخدم:\n$prompt")
            })
        }
        val c=connect("responses",requireKey(),"application/json; charset=utf-8")
        try {
            c.outputStream.use{it.write(json.toString().toByteArray(Charsets.UTF_8))}
            val response=readResponse(c)
            val usage=response.optJSONObject("usage")
            val inTok=usage?.optInt("input_tokens",0)?:0
            val outTok=usage?.optInt("output_tokens",0)?:0
            val actual=if(inTok>0 || outTok>0)
                (inTok*0.10+outTok*0.50)/1_000_000.0*budget.sarPerUsd*1.1 else estimate
            budget.record(actual.coerceAtLeast(estimate*0.5))
            val output=response.optJSONArray("output")
            val result=StringBuilder()
            if(output!=null)for(i in 0 until output.length()){
                val content=output.optJSONObject(i)?.optJSONArray("content") ?: continue
                for(j in 0 until content.length()) {
                    val item=content.optJSONObject(j) ?: continue
                    if(item.optString("type")=="output_text")result.append(item.optString("text"))
                }
            }
            result.toString().ifBlank { error("الخدمة لم ترجع نصًا قابلًا للعرض") }
        } finally { c.disconnect() }
    }
    suspend fun transcribe(audio:File):String=withContext(Dispatchers.IO){
        ensureSmart()
        require(audio.exists()&&audio.length()>0){"لا يوجد تسجيل صالح"}
        require(audio.length()<=25L*1024*1024){"الملف يتجاوز 25MB؛ لا يمكن رفعه"}
        val retriever=MediaMetadataRetriever()
        val seconds=try{
            retriever.setDataSource(audio.absolutePath)
            (retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()?:60000L)/1000L
        }catch(_:Exception){300L}finally{retriever.release()}
        val estimate=budget.estimateAudioSar(seconds)
        if(!budget.maySpend(estimate))error("ميزانية API لا تكفي لتفريغ هذا التسجيل")
        val boundary="secondbrain-${UUID.randomUUID()}"
        val c=connect("audio/transcriptions",requireKey(),"multipart/form-data; boundary=$boundary")
        try{
            c.outputStream.use{out->
                fun part(field:String,value:String){out.write(("--$boundary\r\nContent-Disposition: form-data; name=\"$field\"\r\n\r\n$value\r\n").toByteArray())}
                part("model","gpt-transcribe")
                part("prompt","Arabic Egyptian engineering tasks. Names: View Tower, RWDI, QuantEx, برج المربع, فيو المتحدة")
                out.write(("--$boundary\r\nContent-Disposition: form-data; name=\"file\"; filename=\"recording.m4a\"\r\nContent-Type: audio/mp4\r\n\r\n").toByteArray())
                audio.inputStream().use{it.copyTo(out)}
                out.write("\r\n--$boundary--\r\n".toByteArray())
            }
            val response=readResponse(c)
            val transcript=response.optString("text").trim()
            if(transcript.isBlank())error("لم يتم التعرف على نص التسجيل")
            budget.record(estimate)
            transcript
        } finally {c.disconnect()}
    }
}
