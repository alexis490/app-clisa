package com.example.app_clisa.data.backend

import com.example.app_clisa.data.excel.ImportSummaryWorkbook
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File

/** Only authenticated GETs of saved evidence. No advance, classification or confirm action. */
internal object ImportSummaryExporter {
    suspend fun prepare(client: ClisaBackendClient,id:String,cache:File,onProgress:(String)->Unit):File=withContext(Dispatchers.IO){
        var first:JSONObject?=null
        var book:ImportSummaryWorkbook?=null
        try {
            for(section in listOf("products","issues")){
                var offset=0
                do {
                    currentCoroutineContext().ensureActive()
                    onProgress(if(section=="products")"Preparando productos y operaciones guardadas…" else "Preparando incidencias guardadas…")
                    var page:JSONObject?=null
                    var retries=0
                    while(page==null){
                        try { page=client.importExportPage(id,section,offset) }
                        catch(e:BackendFailure){if(e.problem!=BackendProblem.RATE_LIMIT || retries++>=3)throw e;onProgress("Esperando para continuar la descarga del resumen…");delay(61000)}
                    }
                    val metadata=page.getJSONObject("job")
                    if(first==null){first=metadata;book=ImportSummaryWorkbook(cache,metadata)}
                    else require(sameResult(first,metadata)){"El resultado de la importación cambió. Vuelve a exportar el resumen."}
                    book!!.append(section,page.getJSONObject("data"),page.getBoolean("complete"))
                    offset=if(page.isNull("nextOffset"))-1 else page.getInt("nextOffset")
                }while(offset>=0)
            }
            currentCoroutineContext().ensureActive();onProgress("Generando archivo Excel…")
            book!!.finish()
        } finally { book?.close() }
    }
    private fun sameResult(a:JSONObject,b:JSONObject):Boolean {
        if(listOf("id","filename","status","total","completedAt","responsibleUser").any{a.opt(it)!=b.opt(it)})return false
        val keys=listOf("productsCreated","productsUpdated","productsUnchanged","productsOmitted","seriesCreated","seriesUpdated","seriesZeroed","classificationPending","syncErrors")
        if(keys.any{a.optJSONObject("summary")?.opt(it)!=b.optJSONObject("summary")?.opt(it)})return false
        return a.optJSONObject("history")?.toString()==b.optJSONObject("history")?.toString()
    }
}
