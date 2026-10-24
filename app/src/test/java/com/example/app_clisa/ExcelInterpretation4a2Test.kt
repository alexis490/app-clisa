package com.example.app_clisa

import com.example.app_clisa.data.excel.*
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class ExcelInterpretation4a2Test {
    private fun read(vararg rows: List<String>): ExcelInventory {
        val c=ExcelInventoryCollector();c.startSheet("Sheet")
        c.row(ExcelRow(1,listOf("Lote/Nº de serie","Referencia Interna","Producto","Cantidad").mapIndexed { i,t -> i to ExcelCell(t) }.toMap()))
        rows.forEachIndexed { i,row -> c.row(ExcelRow(i+2,row.mapIndexed { j,t -> j to ExcelCell(t,if(j==3) t else null) }.toMap())) }
        c.endSheet();val r=c.result();return r.copy(structure=r.structure!!.copy(xlsxValidated=true))
    }
    @Test fun zeroStockProductWithNoSeriesIsAValidZeroSummaryNotAnExistence() {
        val r=read(listOf("[ZERO] Producto sin stock (0)","","","0"))
        assertEquals(1,r.products.size);assertEquals(0,r.serialRowCount);assertEquals(0,r.errorCount)
        val a=ExcelCompletenessValidator.assess(r,"ab".repeat(32))
        assertTrue(a.technicalValid);assertEquals(ReportCompleteness.UNKNOWN,a.state)
        val empty=InventoryReadSnapshot(emptyList(),emptyList(),1,2)
        val newZero=InventoryComparator.compare(r,empty)
        assertEquals(1,newZero.newProducts.size);assertTrue(newZero.conflicts.isEmpty());assertTrue(newZero.newExistences.isEmpty())
        val existingZero=empty.copy(products=listOf(ComparedProduct("real-zero","ZERO","Producto sin stock",0,0,"v1")))
        val matched=InventoryComparator.compare(r,existingZero)
        assertEquals(1,matched.existingProducts.size);assertTrue(matched.conflicts.isEmpty());assertEquals(0,matched.estimatedInventoryWrites)
        val bad=r.copy(products=r.products.map { it.copy(reportedSerialCount=1) })
        assertTrue(InventoryComparator.compare(bad,existingZero).conflicts.isNotEmpty())
    }
    @Test fun zeroQuantitySerialAndSpecialSkuRemainLiteral() {
        val r=read(listOf("[0001/A_á#(B)[AA]] Producto (1)","","","0"), listOf("000001/AB-#","","","0"))
        assertEquals("0001/A_á#(B)[AA]",r.products.single().code)
        assertEquals("000001/AB-#",r.products.single().serials.single().code)
        assertEquals(0L,r.products.single().serials.single().quantity);assertEquals(0,r.errorCount)
        val spaced=read(listOf("[ 0001/A ] Producto (0)","","","0"))
        assertEquals(" 0001/A ",spaced.products.single().code)
        assertEquals(1,spaced.issueCounts[ExcelIssueKind.SKU_WHITESPACE])
    }
    @Test fun missingSkuKeepsGroupRowsAndDoesNotBorrowPreviousSku() {
        val r=read(listOf("[OK] Bien (0)","","","0"),listOf("Sin código (1)","","","0"),listOf("00001","","","0"))
        assertNull(r.products.last().code);assertEquals(4,r.products.last().serials.single().row)
        assertEquals(1,r.issueCounts[ExcelIssueKind.MISSING_SKU]);assertEquals(ReportCompleteness.INCOMPLETE,ExcelCompletenessValidator.assess(r,null).state)
    }
    @Test fun duplicatesHaveRowAndIdentityAndNeverSelectOneWinner() {
        val r=read(listOf("[S] Nombre (2)","","","2"),listOf("0001","","","1"),listOf("0001","","","1"))
        assertEquals(0,r.serialCount);assertEquals(1,r.issueCounts[ExcelIssueKind.DUPLICATE_SERIAL])
        val i=r.issues.single();assertEquals("S",i.productCode);assertEquals("0001",i.serialCode);assertEquals(4,i.row)
    }
    @Test fun negativeAndMalformedQuantitiesRemainIncidents() {
        val r=read(listOf("[S] Producto (2)","","","0"),listOf("S1","","","-1"),listOf("S2","","","x"))
        assertEquals(1,r.issueCounts[ExcelIssueKind.NEGATIVE_QUANTITY]);assertEquals(1,r.issueCounts[ExcelIssueKind.INVALID_QUANTITY])
        assertEquals(-1L,r.products.single().serials.first().quantity);assertNull(r.products.single().serials.last().quantity)
    }
    @Test fun summariesAreNotDoubleCountedAndUnknownRowsRemainVisible() {
        val r=read(listOf("[S] Producto (1)","","","1"),listOf("S1","","","1"),listOf("Total","","","1"),listOf("","","Extra desconocido",""))
        assertEquals(1,r.serialRowCount);assertEquals(2,r.summaryRows);assertEquals(1,r.structure!!.uninterpretedRows)
        assertEquals(1,r.issueCounts[ExcelIssueKind.UNINTERPRETED_ROW])
    }
    @Test fun summaryDisagreementRemainsCritical() {
        val r=read(listOf("[S] Producto (2)","","","9"),listOf("S1","","","1"))
        assertEquals(1,r.issueCounts[ExcelIssueKind.SUMMARY_COUNT_MISMATCH]);assertEquals(1,r.issueCounts[ExcelIssueKind.SUMMARY_QUANTITY_MISMATCH])
    }
    @Test fun realFileAll567IssuesAreTypedAndRetainedWithSourceTrace() {
        val source=System.getenv("CLISA_EXCEL_SAMPLE")
        org.junit.Assume.assumeTrue(source!=null)
        val r=XlsxInventoryReader().read(File(requireNotNull(source)))
        assertEquals(1814,r.products.size);assertEquals(22762,r.serialRowCount);assertEquals(567,r.errorCount)
        assertEquals(567,r.issues.size)
        assertEquals(mapOf(ExcelIssueKind.MISSING_SKU to 346,ExcelIssueKind.SERIAL_WHITESPACE to 156,
            ExcelIssueKind.DUPLICATE_SERIAL to 48,ExcelIssueKind.DUPLICATE_SKU to 15,ExcelIssueKind.NEGATIVE_QUANTITY to 2),r.issueCounts)
        assertTrue(r.issues.all { it.row!=null && it.sheet=="stock.production.lot" && !it.productName.isNullOrBlank() })
        assertEquals(0,r.structure!!.uninterpretedRows)
        assertEquals(1016,r.products.count { it.reportedQuantity==0L })
        System.getenv("CLISA_EXCEL_AUDIT_DIR")?.let { path ->
            val out=File(path);out.mkdirs()
            fun csv(v:Any?)="\"${v?.toString().orEmpty().replace("\"","\"\"")}\""
            File(out,"incidencias-despues.csv").writeText("type,sheet,row,sku,product,serial,cause\n"+r.issues.joinToString("\n") {
                listOf(it.kind,it.sheet,it.row,it.productCode,it.productName,it.serialCode,it.message).joinToString(",",transform=::csv)
            }+"\n")
            val repeated=r.products.groupBy { it.code }.filterValues { it.size>1 }.keys
            val valid=r.products.filter { !it.code.isNullOrBlank() && it.code !in repeated && it.serials.all { row -> row.valid } }
            File(out,"validacion-despues.txt").writeText(buildString {
                appendLine("Solo archivo local; Firestore no consultado.")
                appendLine("Grupos=${r.products.size}; SKU únicos=${r.uniqueProductCodeCount}; sin SKU=${r.uncodedProductCount}")
                appendLine("Grupos con SKU único y series sin error local=${valid.size}")
                appendLine("Filas de serie=${r.serialRowCount}; series válidas por fila=${r.serialCount}; series de grupos válidos=${valid.sumOf { it.serials.size }}")
                appendLine("Grupos con cantidad cero=${r.products.count { it.reportedQuantity==0L }}")
                appendLine("Incidencias=${r.errorCount}; retenidas=${r.issues.size}; filas no interpretadas=${r.structure.uninterpretedRows}")
                r.issueCounts.forEach { (kind,n) -> appendLine("$kind=$n") }
                appendLine("Completitud=${ExcelCompletenessValidator.assess(r,null).state}")
            })
        }
    }
}
