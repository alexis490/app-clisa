package com.example.app_clisa.data.excel

import org.json.JSONObject
import java.io.*
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Local, streaming OOXML export. Text is always literal; no formulas, links or credentials. */
internal class ImportSummaryWorkbook(private val cacheDir: File, private val job: JSONObject) : Closeable {
    private val folder=File(cacheDir,"clisa-export-${UUID.randomUUID()}").apply { check(mkdirs()) }
    private val sheets=listOf("Resumen general","Productos actualizados","Incidencias omitidas","Resultados de sincronización")
    private val columnWidths=listOf(listOf(37,62,65),listOf(26,58,23,22,22,23,30,22),listOf(22,14,26,58,29,29,75,75),listOf(25,75,30,23,72))
    private val files=sheets.indices.map { File(folder,"sheet${it+1}.xml") }
    private val writers=files.map { it.bufferedWriter(Charsets.UTF_8) }
    private val rows=IntArray(4)
    private val issueKeys=HashSet<List<Any?>>()
    private var coverageComplete=true
    private var finished=false
    private var closed=false
    init {
        require(job.optString("status") in setOf("COMPLETED","PARTIAL"))
        writers.forEachIndexed { i,w ->
            w.write("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?><worksheet xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\"><sheetViews><sheetView workbookViewId=\"0\"><pane ySplit=\"1\" topLeftCell=\"A2\" activePane=\"bottomLeft\" state=\"frozen\"/></sheetView></sheetViews><cols>")
            val widths=columnWidths[i]
            widths.forEachIndexed { n,width->w.write("<col min=\"${n+1}\" max=\"${n+1}\" width=\"$width\" customWidth=\"1\"/>") }
            w.write("</cols><sheetData>")
        }
        row(0,listOf("Concepto","Valor","Unidad o alcance"),true)
        row(1,listOf("Código","Nombre","Tienda","Stock anterior","Stock nuevo","Diferencia de stock","Tipo de operación","Estado de sincronización"),true)
        row(2,listOf("Hoja original","Fila original del Excel","Código","Nombre del producto","Número de serie","Tipo de incidencia","Descripción del problema","Motivo de exclusión"),true)
        row(3,listOf("Colección afectada","Identificador del registro u operación","Tipo de operación","Estado","Detalle del error o exclusión"),true)
    }
    fun append(section:String,page:JSONObject,complete:Boolean=true) {
        check(!finished);coverageComplete=coverageComplete&&complete
        if(section=="products"){
            val products=page.optJSONArray("products")
            repeat(products?.length()?:0){index->val p=products!!.getJSONObject(index)
                val exists=if(p.isNull("previousExists"))null else p.optBoolean("previousExists")
                row(1,listOf(text(p,"code"),text(p,"name"),text(p,"store"),if(exists==false)"No existía" else number(p,"beforeStock"),number(p,"newStock"),if(exists==false)"No aplica" else number(p,"difference"),text(p,"type"),state(text(p,"state"))))
            }
            val operations=page.optJSONArray("operations")
            repeat(operations?.length()?:0){index->val op=operations!!.getJSONObject(index)
                row(3,listOf(text(op,"collection"),text(op,"id"),text(op,"type"),state(text(op,"state")),if(text(op,"state")=="COMPLETADO") "" else text(op,"error")))
            }
        }
        val issues=page.optJSONArray("issues")
        repeat(issues?.length()?:0){index->val i=issues!!.getJSONObject(index)
            val key=listOf(text(i,"sheet"),number(i,"row"),text(i,"code"),text(i,"serial"),text(i,"type"))
            if(issueKeys.add(key)){
                require(issueKeys.size<=20000){"El detalle de incidencias supera el límite de exportación."}
                row(2,listOf(text(i,"sheet"),number(i,"row"),text(i,"code"),text(i,"name"),text(i,"serial"),text(i,"type"),text(i,"description"),text(i,"reason")))
            }
        }
    }
    fun finish():File {
        check(!finished)
        val history=job.optJSONObject("history")
        val c=job.optJSONObject("summary")?:JSONObject()
        fun info(label:String,value:Any?,scope:String="")=row(0,listOf(label,value,scope))
        info("ID de importación",(text(history,"idImportacion")?:text(job,"id"))?.take(8))
        val date=(text(history,"fecha")?:text(job,"completedAt"))?.let { runCatching { DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm:ss XXX").withZone(ZoneId.systemDefault()).format(Instant.parse(it)) }.getOrNull() }
        info("Fecha y hora",date,"Hora de finalización. Huso horario indicado en el valor.")
        info("Nombre del archivo original",text(history,"archivo")?:text(job,"filename"))
        info("Usuario responsable",text(history,"usuario")?:text(job,"responsibleUser"))
        info("Estado de la importación",text(history,"estado")?:text(job,"status"),if(history==null)"Estado del expediente. No se conserva registro histórico." else "Estado registrado por el backend.")
        val processed=listOf("productsCreated","productsUpdated","productsUnchanged").map { number(c,it) }
        info("Productos procesados",if(processed.all{it!=null})processed.sumOf{it!!} else null,"Productos aplicados, incluidos los que no requerían cambios.")
        info("Productos nuevos",number(c,"productsCreated"),"Productos. No es un recuento de existencias.")
        info("Productos actualizados",number(c,"productsUpdated"),"Productos con actualización confirmada.")
        info("Series actualizadas",number(c,"seriesUpdated"),"Registros de existencia actualizados.")
        info("Series en cero",number(c,"seriesZeroed"),"Registros de existencia puestos en cero.")
        info("Incidencias omitidas",number(c,"productsOmitted"),"Productos apartados, como en la tarjeta de la app. No equivale al número de incidencias.")
        info("Errores de sincronización",number(c,"syncErrors"),"Productos cuya transacción falló. No equivale al número de operaciones fallidas.")
        info("Base de datos de destino",text(history,"destino")?:"Firebase Firestore")
        info("Incidencias detalladas exportadas",issueKeys.size,"Incidencias únicas por hoja, fila, código, serie y tipo.")
        info("Cobertura del detalle",if(coverageComplete)"Datos conservados disponibles" else "Detalle incompleto","No disponible indica que el expediente no conserva ese dato. No se consultó inventario actual.")
        info("Stock anterior y diferencia",null,"Stock anterior: comparación guardada. Stock nuevo: solo con recibo aplicado. Diferencia solo si ambos valores son conocidos.")
        info("Tienda",null,"No se asigna una tienda cuando no quedó registrada en la importación.")
        info("Resultados de operaciones",null,"El recibo confirma la transacción completa del producto. Las propuestas fallidas no se presentan como cambios aplicados.")
        info("Productos del detalle",null,"Incluye nuevos, actualizados, sin cambios, apartados y fallidos, con su estado explícito.")
        writers.forEachIndexed { index,w->w.write("</sheetData>");if(index>0)w.write("<autoFilter ref=\"A1:${column(when(index){1->8;2->8;else->5})}${rows[index]}\"/>");w.write("</worksheet>");w.close() }
        finished=true
        val output=File.createTempFile("clisa-resumen-", ".xlsx",cacheDir)
        try {
            ZipOutputStream(output.outputStream().buffered()).use { zip ->
                fun entry(name:String,value:String){zip.putNextEntry(ZipEntry(name));zip.write(value.toByteArray(Charsets.UTF_8));zip.closeEntry()}
                entry("[Content_Types].xml","""<?xml version="1.0" encoding="UTF-8"?><Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types"><Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/><Default Extension="xml" ContentType="application/xml"/><Override PartName="/xl/workbook.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml"/><Override PartName="/xl/styles.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.styles+xml"/>${sheets.indices.joinToString(""){"<Override PartName=\"/xl/worksheets/sheet${it+1}.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml\"/>"}}</Types>""")
                entry("_rels/.rels","""<?xml version="1.0" encoding="UTF-8"?><Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="xl/workbook.xml"/></Relationships>""")
                entry("xl/workbook.xml","""<?xml version="1.0" encoding="UTF-8"?><workbook xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main" xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships"><sheets>${sheets.mapIndexed{i,name->"<sheet name=\"${xml(name)}\" sheetId=\"${i+1}\" r:id=\"rId${i+1}\"/>"}.joinToString("")}</sheets></workbook>""")
                entry("xl/_rels/workbook.xml.rels","""<?xml version="1.0" encoding="UTF-8"?><Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">${sheets.indices.joinToString(""){"<Relationship Id=\"rId${it+1}\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet\" Target=\"worksheets/sheet${it+1}.xml\"/>"}}<Relationship Id="rId5" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/styles" Target="styles.xml"/></Relationships>""")
                entry("xl/styles.xml","""<?xml version="1.0" encoding="UTF-8"?><styleSheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main"><fonts count="2"><font><sz val="11"/><name val="Arial"/></font><font><b/><color rgb="FFFFFFFF"/><sz val="11"/><name val="Arial"/></font></fonts><fills count="3"><fill><patternFill patternType="none"/></fill><fill><patternFill patternType="gray125"/></fill><fill><patternFill patternType="solid"><fgColor rgb="FF107C41"/><bgColor indexed="64"/></patternFill></fill></fills><borders count="1"><border/></borders><cellStyleXfs count="1"><xf numFmtId="0" fontId="0" fillId="0" borderId="0"/></cellStyleXfs><cellXfs count="2"><xf numFmtId="0" fontId="0" fillId="0" borderId="0" xfId="0" applyAlignment="1"><alignment vertical="top" wrapText="1"/></xf><xf numFmtId="0" fontId="1" fillId="2" borderId="0" xfId="0" applyFont="1" applyFill="1" applyAlignment="1"><alignment vertical="center" wrapText="1"/></xf></cellXfs><cellStyles count="1"><cellStyle name="Normal" xfId="0" builtinId="0"/></cellStyles></styleSheet>""")
                files.forEachIndexed { index,file->zip.putNextEntry(ZipEntry("xl/worksheets/sheet${index+1}.xml"));file.inputStream().use{it.copyTo(zip)};zip.closeEntry() }
            }
            return output
        } catch(e:Exception){output.delete();throw e} finally {close()}
    }
    private fun row(sheet:Int,values:List<Any?>,header:Boolean=false){
        require(++rows[sheet]<=1048576){"El resumen supera el límite de filas de Excel. No se guardó un resumen incompleto."}
        val lines=values.mapIndexed { index,value -> (value?.toString() ?: "No disponible").split('\n').sumOf { kotlin.math.ceil(it.length / (columnWidths[sheet][index]*0.85)).toInt().coerceAtLeast(1) } }.maxOrNull() ?: 1
        val height=if(header)30 else (lines*14+4).coerceIn(18,409)
        val w=writers[sheet];w.write("<row r=\"${rows[sheet]}\" ht=\"$height\" customHeight=\"1\">")
        values.forEachIndexed{index,value->
            val address="${column(index+1)}${rows[sheet]}";val style=if(header)1 else 0
            if(value is Number && value.toLong() in -999999999999999L..999999999999999L)w.write("<c r=\"$address\" s=\"$style\" t=\"n\"><v>${value.toLong()}</v></c>")
            else {val literal=value?.toString()?:"No disponible";require(literal.length<=32767){"Un texto del resumen supera el tamaño admitido por Excel."};w.write("<c r=\"$address\" s=\"$style\" t=\"inlineStr\"><is><t xml:space=\"preserve\">${xml(literal)}</t></is></c>")}
        };w.write("</row>")
    }
    override fun close(){if(closed)return;closed=true;writers.forEach{runCatching{it.close()}};files.forEach{it.delete()};folder.delete()}
    companion object {
        const val MIME="application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
        fun suggestedName()="CLISA_Importacion_${LocalDate.now()}_${UUID.randomUUID()}.xlsx"
        private fun column(value:Int):String {var n=value;var s="";while(n>0){n--;s=('A'+n%26)+s;n/=26};return s}
        private fun text(json:JSONObject?,key:String)=json?.opt(key)?.takeUnless{it===JSONObject.NULL}?.let{it as? String}
        private fun number(json:JSONObject?,key:String):Long? {val v=json?.opt(key) as? Number?:return null;return v.toDouble().takeIf{it.isFinite()&&it%1.0==0.0&&kotlin.math.abs(it)<=9007199254740991.0}?.toLong()}
        private fun state(value:String?)=when(value){"COMPLETADO"->"Completado";"OMITIDO"->"Omitido";"FALLIDO"->"Fallido";else->"No disponible"}
        private fun xml(value:String):String=buildString {value.codePoints().forEach {c->if(c==9||c==10||c==13||c in 32..0xD7FF||c in 0xE000..0xFFFD||c in 0x10000..0x10FFFF)when(c){38->append("&amp;");60->append("&lt;");62->append("&gt;");34->append("&quot;");39->append("&apos;");else->appendCodePoint(c)} else append('\uFFFD')} }
    }
}
