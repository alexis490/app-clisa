package com.example.app_clisa

import com.example.app_clisa.data.excel.ImportSummaryWorkbook
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Test
import org.junit.Assert.*
import java.io.File
import java.nio.file.Files
import java.util.zip.ZipFile
import javax.xml.parsers.DocumentBuilderFactory

class ImportSummaryWorkbookTest {
    private fun pages()=JSONArray(javaClass.getResource("/export-fixture.json")!!.readText())
    private fun document(file:File,part:String)=ZipFile(file).use{zip->zip.getInputStream(zip.getEntry(part)).use{input->DocumentBuilderFactory.newInstance().apply{isNamespaceAware=true;setFeature("http://apache.org/xml/features/disallow-doctype-decl",true)}.newDocumentBuilder().parse(input)}}
    private fun cell(file:File,sheet:Int,address:String):String {
        val nodes=document(file,"xl/worksheets/sheet$sheet.xml").getElementsByTagName("c")
        return (0 until nodes.length).map{nodes.item(it)}.first{it.attributes.getNamedItem("r").nodeValue==address}.textContent
    }
    @Test fun realEmulatorResultsProduceFourReadableSheetsWithCorrectUnits(){
        val data=pages();val dir=Files.createTempDirectory("clisa-export-test").toFile()
        try {
            val file=ImportSummaryWorkbook(dir,data.getJSONObject(0).getJSONObject("job")).use{book->repeat(data.length()){val p=data.getJSONObject(it);book.append(p.getString("section"),p.getJSONObject("data"),p.getBoolean("complete"))};book.finish()}
            val names=document(file,"xl/workbook.xml").getElementsByTagName("sheet")
            assertEquals(listOf("Resumen general","Productos actualizados","Incidencias omitidas","Resultados de sincronización"),(0 until names.length).map{names.item(it).attributes.getNamedItem("name").nodeValue})
            assertEquals(5,document(file,"xl/worksheets/sheet2.xml").getElementsByTagName("row").length)
            assertEquals(2,document(file,"xl/worksheets/sheet3.xml").getElementsByTagName("row").length)
            assertEquals("2",cell(file,1,"B7"));assertEquals("1",cell(file,1,"B12"));assertEquals("1",cell(file,1,"B13"))
            assertEquals("No disponible",cell(file,2,"C2"));assertEquals("-2",cell(file,2,"F2"));assertEquals("No existía",cell(file,2,"D3"))
            assertEquals("No disponible",cell(file,2,"E4"));assertEquals("No disponible",cell(file,2,"E5"));assertEquals("Fallido",cell(file,2,"H5"))
            val out=File("build/exports/CLISA_export_verificado.xlsx").apply{parentFile!!.mkdirs()};file.copyTo(out,overwrite=true)
            assertEquals(8,document(file,"xl/worksheets/sheet4.xml").getElementsByTagName("row").length)
        }finally{dir.deleteRecursively()}
    }
    @Test fun stringsRemainLiteralAndOnlyWhitelistedFieldsAreWritten(){
        val dir=Files.createTempDirectory("clisa-export-test").toFile()
        try{
            val all=pages();val page=all.getJSONObject(0);val job=page.getJSONObject("job").put("apiKey","DO_NOT_EXPORT_KEY").put("token","DO_NOT_EXPORT_TOKEN")
            val product=page.getJSONObject("data").getJSONArray("products").getJSONObject(0)
            product.put("code","=HYPERLINK(\"https://example.test\",\"literal\")").put("name","A & B <xml>\u0001").put("secret","DO_NOT_EXPORT_SECRET")
            val file=ImportSummaryWorkbook(dir,job).use{it.append("products",page.getJSONObject("data"));it.finish()}
            assertEquals("=HYPERLINK(\"https://example.test\",\"literal\")",cell(file,2,"A2"));assertEquals("A & B <xml>\uFFFD",cell(file,2,"B2"))
            ZipFile(file).use{zip->zip.entries().asSequence().filter{it.name.endsWith(".xml")}.forEach{entry->val text=zip.getInputStream(entry).bufferedReader().use{it.readText()};assertFalse(text.contains("DO_NOT_EXPORT"));assertFalse(text.contains("<f>"))}}
        }finally{dir.deleteRecursively()}
    }
    @Test fun unknownCountsStayUnknownAndIncompleteEvidenceIsLabeled(){
        val dir=Files.createTempDirectory("clisa-export-test").toFile()
        try{
            val job=JSONObject().put("id","known-id").put("status","COMPLETED").put("summary",JSONObject())
            val file=ImportSummaryWorkbook(dir,job).use{it.append("products",JSONObject(),false);it.finish()}
            assertEquals("No disponible",cell(file,1,"B7"));assertEquals("No disponible",cell(file,1,"B3"));assertEquals("Detalle incompleto",cell(file,1,"B16"))
            assertNotEquals(ImportSummaryWorkbook.suggestedName(),ImportSummaryWorkbook.suggestedName())
        }finally{dir.deleteRecursively()}
    }
    @Test fun oversizedCellFailsWithoutLeavingAnIncompleteWorkbook(){
        val dir=Files.createTempDirectory("clisa-export-test").toFile()
        try{
            try{ImportSummaryWorkbook(dir,pages().getJSONObject(0).getJSONObject("job")).use{it.append("products",JSONObject().put("products",JSONArray().put(JSONObject().put("name","x".repeat(32768)))))};fail("Debe informar el límite de Excel")}
            catch(expected:IllegalArgumentException){assertTrue(expected.message!!.contains("tamaño"))}
            assertTrue(dir.listFiles()!!.isEmpty())
        }finally{dir.deleteRecursively()}
    }
}
