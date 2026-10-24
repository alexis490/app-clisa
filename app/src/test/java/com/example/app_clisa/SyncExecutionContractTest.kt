package com.example.app_clisa

import com.example.app_clisa.data.excel.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.Assume.assumeTrue
import java.io.File

class SyncExecutionContractTest {
    private fun fixture(): Triple<SyncPlan, ExcelInventory, InventoryComparison> {
        val excel=ExcelInventory(listOf(ExcelProduct("SFYRS/1000G","Producto \"local\" 😀",0,emptyList(),"Hoja",2,0)),emptyList(),0,1,
            ExcelStructure(true,1,1,2,1,1,0,0,0,0))
        val snapshot=InventoryReadSnapshot(listOf(ComparedProduct("SFYRS_1000G","SFYRS/1000G","Local",0,7,"Timestamp(seconds=1, nanoseconds=0)")),emptyList(),1,2)
        val result=InventoryComparator.compare(excel,snapshot)
        val plan=InventoryDryRunPlanner.generate(result,ReportReadEvidence.fromReadResult(excel,"local.xlsx","a".repeat(64)))
        return Triple(plan,excel,result)
    }
    @Test fun preservesRealIdsBlocksAndNoProductionAuthorization() {
        val (plan,excel,result)=fixture();val wire=SyncExecutionContract.value(plan,excel,result,"ADMIN")
        assertEquals(1,wire["schemaVersion"])
        val p=wire["plan"] as Map<*,*>;assertEquals(false,p["productionExecutable"])
        assertEquals(plan.operations.size,(p["operations"] as List<*>).size)
        assertTrue(SyncExecutionContract.json(plan,excel,result,"ADMIN").contains("SFYRS_1000G"))
        assertEquals(4, p["schemaVersion"]); assertEquals("EXCEL_REFERENCE_V1", p["inventoryPolicy"])
    }
    @Test fun emitsDeterministicCrossLanguageFixture() {
        val (plan,excel,result)=fixture();val value=SyncExecutionContract.value(plan,excel,result,"ADMIN")
        val json=SyncExecutionContract.json(plan,excel,result,"ADMIN")
        assertEquals(json,SyncExecutionContract.json(plan,excel,result,"ADMIN"))
        assertTrue(json.contains("\\\"local\\\""))
        System.getenv("CLISA_SYNC_CONTRACT_FIXTURE")?.let { path ->
            File(path).writeText("{\"contract\":$json,\"canonicalHash\":\"${SyncCanonical.hash(value)}\",\"productId\":\"${InventoryIdPolicy.newProductId("SFYRS/1000G")}\",\"existenceId\":\"${InventoryIdPolicy.newExistenceId("SFYRS_1000G","0001😀")}\"}")
        }
    }
    @Test(expected=IllegalArgumentException::class) fun rejectsChangedBaseline() {
        val (p,e,c)=fixture();SyncExecutionContract.value(p.copy(baselineFingerprint="wrong"),e,c,"ADMIN")
    }
    @Test fun realExcelStillExports567IssuesWithoutAuthorizingAnyMutation() {
        val path=System.getenv("CLISA_EXCEL_SAMPLE")
        assumeTrue(!path.isNullOrBlank() && File(path!!).isFile)
        val file=File(requireNotNull(path));val excel=XlsxInventoryReader().read(file)
        val comparison=InventoryComparator.compare(excel,InventoryReadSnapshot(emptyList(),emptyList(),1,2))
        val sha=file.inputStream().use { SyncPlanIdentity.fileSha256(it) }
        val plan=InventoryDryRunPlanner.generate(comparison,ReportReadEvidence.fromReadResult(excel,file.name,sha))
        val wire=SyncExecutionContract.value(plan,excel,comparison,"ADMIN")
        assertEquals(567,(wire["source"] as Map<*,*>)["errorCount"])
        assertEquals(567,((wire["source"] as Map<*,*>)["issues"] as List<*>).size)
        assertTrue(plan.operations.filter { it.changesInventory }.all { it.validation==SyncValidation.BLOCKED })
        assertFalse(plan.productionExecutable)
        System.getenv("CLISA_ISSUE_REVIEW_SOURCE")?.let { path ->
            File(path).writeText(SyncExecutionContract.jsonValue(mapOf("fileSha256" to sha,
                "issues" to (wire["source"] as Map<*,*>)["issues"])))
        }
    }
}
