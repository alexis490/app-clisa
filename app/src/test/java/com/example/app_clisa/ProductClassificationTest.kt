package com.example.app_clisa

import com.example.app_clisa.data.excel.*
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class ProductClassificationTest {
    @get:Rule val temporary = TemporaryFolder()
    private val sourceHash = "ab".repeat(32)
    private val rulesText = listOf(File("src/main/assets/classification-rules.tsv"), File("app/src/main/assets/classification-rules.tsv")).first { it.exists() }.readText()
    private val rules = ClassificationRules.read(rulesText)
    private val catalog = ClassificationCatalog(listOf(ClassificationReference("audio-real", "Parlantes y audio", true),
        ClassificationReference("headset-real", "Audífonos y micrófonos", true)), listOf(ClassificationReference("creative-real", "Creative", true),
        ClassificationReference("creativo-real", "Creativo", true), ClassificationReference("hp-real", "HP", true)))
    private fun product(name: String = "Parlante Creative Pebble", category: String? = null, brand: String? = null) =
        ClassificationProduct("SFYRS/1000G", "SFYRS_1000G", "SFYRS/1000G", name, category, brand, null, true)
    private fun classify(p: ClassificationProduct) = ProductClassifier.classify(p, catalog, rules)
    private fun review(p: ClassificationProduct = product(), c: ClassificationCatalog = catalog) = ClassificationReview(sourceHash, listOf(p), c, rules)
    private fun rejects(block: () -> Unit) { try { block(); fail("Debía rechazar") } catch (_: IllegalArgumentException) {} catch (_: IllegalStateException) {} }

    @Test fun recognizedWordsNormalizeWithoutChangingOriginal() {
        val p = product("  PARLÁNTE   CREATIVE Pebble"); val c = classify(p)
        assertEquals("audio-real", c.category.proposed); assertEquals("creative-real", c.brand.proposed)
        assertEquals("  PARLÁNTE   CREATIVE Pebble", c.product.name); assertTrue(c.descriptionPending)
    }
    @Test fun boundariesDoNotConfuseSimilarBrandsOrPartialWords() {
        assertEquals("creativo-real", classify(product("Parlante Creativo")).brand.proposed)
        assertEquals(ClassificationState.SIN_IDENTIFICAR, classify(product("CreativePro parlantecito")).brand.state)
        assertEquals(ClassificationState.SIN_IDENTIFICAR, classify(product("CreativePro parlantecito")).category.state)
    }
    @Test fun ambiguityIsIndependentAndBulkCannotResolveIt() {
        val p = product("Parlante auriculares Creative HP"); val c = classify(p)
        assertEquals(ClassificationState.REVISAR, c.category.state); assertEquals(ClassificationState.REVISAR, c.brand.state)
        rejects { review(p).decision(p.key, "marcaId", "creative-real", "Revisado", true, "ADMIN", 1) }
        assertEquals("creative-real", review(p).decision(p.key, "marcaId", "creative-real", "Foto contrastada", false, "ADMIN", 1).proposed)
    }
    @Test fun noMatchesOrAbsentCategoryNeverInventAnId() {
        assertEquals(ClassificationState.SIN_IDENTIFICAR, classify(product("Objeto genérico")).category.state)
        val c = catalog.copy(categories = emptyList()); assertNull(ProductClassifier.classify(product(), c, rules).category.proposed)
        rejects { review(c = c).decision(product().key, "categoriaId", "inventado", "Revisado", false, "ADMIN", 1) }
    }
    @Test fun completeFieldsAndDescriptionAreLockedEvenWithContradictoryName() {
        val p = product(category = "headset-real", brand = "hp-real").copy(description = "Descripción real")
        val c = classify(p); assertTrue(c.category.preserved); assertTrue(c.brand.preserved); assertFalse(c.descriptionPending)
        assertEquals(ClassificationState.REVISAR, c.brand.state); assertEquals("hp-real", c.brand.proposed)
        rejects { review(p).decision(p.key, "marcaId", "creative-real", "Cambiar", false, "ADMIN", 1) }
    }
    @Test fun invalidExistingReferenceRequiresIndividualApproval() {
        val p = product(brand = "inexistente"); assertEquals(ClassificationState.REVISAR, classify(p).brand.state)
        rejects { review(p).decision(p.key, "marcaId", "creative-real", "Revisado", true, "ADMIN", 1) }
        assertEquals("creative-real", review(p).decision(p.key, "marcaId", "creative-real", "ID original ausente", false, "ADMIN", 1).proposed)
    }
    private fun excel() = ExcelInventory(listOf(ExcelProduct("SFYRS/1000G", "Parlante Creative Pebble", 2,
        listOf(ExcelSerial("0001", 1, "Excel", 3), ExcelSerial("0002", 1, "Excel", 4)), "Excel", 2, 2)), emptyList(), 0, 1)
    private fun db() = InventoryReadSnapshot(listOf(ComparedProduct("SFYRS_1000G", "SFYRS/1000G", "Parlante Creative Pebble", 2, 99)), emptyList(), 1, 2)
    @Test fun manySeriesClassifyOnceAndPreserveRealDocumentId() {
        val products = ProductClassifier.products(excel(), db()); assertEquals(1, products.size)
        assertEquals("SFYRS_1000G", products.single().documentId); assertTrue(products.single().identityValid)
    }
    @Test fun duplicateSkuNeverSelectsFirstAndIncompleteBaselineBlocksIdentity() {
        val snapshot = db(); assertFalse(ProductClassifier.products(excel(), snapshot.copy(products = snapshot.products + snapshot.products.single().copy(id = "other"))).single().identityValid)
        assertFalse(ProductClassifier.products(excel().copy(products = excel().products + excel().products), snapshot).single().identityValid)
        assertFalse(ProductClassifier.products(excel(), snapshot.copy(complete = false)).single().identityValid)
    }
    @Test fun exclusionsPreventAccessoryFromBecomingMainProduct() {
        assertNull(classify(product("Cable para parlante Creative")).category.proposed)
    }
    @Test fun realExcelRetainsAllRowsButClassifiesOnlyUniqueProducts() {
        val path = System.getenv("CLISA_EXCEL_SAMPLE"); assumeTrue(!path.isNullOrBlank())
        val file = File(path!!); val hashBefore = file.inputStream().use(SyncPlanIdentity::fileSha256)
        val excel = XlsxInventoryReader().read(file)
        val products = ProductClassifier.products(excel, InventoryReadSnapshot(emptyList(), emptyList(), 1, 2))
        assertEquals(22762, excel.serialRowCount); assertEquals(567, excel.errorCount)
        assertEquals(excel.uniqueProductCodeCount + excel.uncodedProductCount, products.size)
        assertTrue(products.size < excel.serialRowCount)
        assertEquals(products.size, ClassificationReview(hashBefore, products, catalog, rules).classified.size)
        assertEquals(hashBefore, file.inputStream().use(SyncPlanIdentity::fileSha256))
    }
    @Test fun approvalsPersistAndSameExcelIsIdempotent() {
        val r = review(); val store = ClassificationReviewStore(temporary.root, "ADMIN")
        val choices = listOf(ClassificationChoice(product().key, "marcaId", "creative-real", "Revisado", true))
        val saved = store.approve(r, choices, "ADMIN", 0, 1)
        assertEquals(1, saved.revision); assertEquals(1, store.approve(r, choices, "ADMIN", 1, 2).revision)
        assertEquals(1, r.current(store.load(sourceHash)).size); assertNotNull(store.readManifest(sourceHash))
        rejects { store.approve(r, choices, "USER", 1, 3) }; rejects { store.approve(r, choices, "ADMIN", 0, 3) }
    }
    @Test fun catalogChangesInvalidateEarlierApproval() {
        val r = review(); val d = r.decision(product().key, "marcaId", "creative-real", "Revisado", true, "ADMIN", 1)
        val changed = catalog.copy(brands = catalog.brands.map { if (it.id == "creative-real") it.copy(active = false) else it })
        assertTrue(review(c = changed).current(IssueReviewJournal(sourceHash, listOf(d))).isEmpty())
    }
    @Test fun staleOrCorruptManifestCannotBeExportedAsCurrentReview() {
        val r = review(); val store = ClassificationReviewStore(temporary.root, "ADMIN")
        store.approve(r, listOf(ClassificationChoice(product().key, "marcaId", "creative-real", "Revisado", true)), "ADMIN", 0, 1)
        store.saveManifest(r, IssueReviewJournal(sourceHash))
        rejects { store.readManifest(sourceHash) }
        store.saveManifest(r, store.load(sourceHash)); assertNotNull(store.readManifest(sourceHash))
        temporary.root.listFiles()!!.single { it.name.endsWith(".classification-v1") }.appendText("alterado")
        rejects { store.readManifest(sourceHash) }
    }
    @Test fun metadataApprovalsCannotUnlockTheStockPlan() {
        val e = excel(); val snapshot = db(); val comparison = InventoryComparator.compare(e, snapshot)
        fun plan() = InventoryDryRunPlanner.generate(comparison, ReportReadEvidence.fromReadResult(e, "test.xlsx", sourceHash))
        val before = plan(); val r = ClassificationReview(sourceHash, ProductClassifier.products(e, snapshot), catalog, rules)
        ClassificationReviewStore(temporary.root, "ADMIN").approve(r, listOf(ClassificationChoice(product().key, "marcaId", "creative-real", "Revisado", true)), "ADMIN", 0, 1)
        assertEquals(before, plan())
        assertFalse(ImportFinalization.summarize(before, e).productionExecutable)
    }
    @Test fun crossRuntimeFixtureAndExistingStockFingerprintAreStable() {
        val p = product(); val r = review(p); val journal = IssueReviewJournal(sourceHash, listOf(
            r.decision(p.key, "categoriaId", "audio-real", "Coincidencia revisada", true, "ADMIN", 1),
            r.decision(p.key, "marcaId", "creative-real", "Coincidencia revisada", true, "ADMIN", 1)))
        val output = System.getenv("CLISA_CLASSIFICATION_FIXTURE")
        if (output != null) File(output).also { it.parentFile?.mkdirs() }.writeText(SyncExecutionContract.jsonValue(mapOf("rulesText" to rulesText,
            "catalog" to mapOf("categories" to catalog.categories.map { mapOf("id" to it.id, "name" to it.name, "active" to it.active) },
                "brands" to catalog.brands.map { mapOf("id" to it.id, "name" to it.name, "active" to it.active) }),
            "manifest" to r.manifest(journal), "category" to ClassificationReview.suggestionValue(r.classified.single().category),
            "brand" to ClassificationReview.suggestionValue(r.classified.single().brand))))
        val baseline = db(); assertEquals(SyncPlanIdentity.inventoryFingerprint(baseline), SyncPlanIdentity.inventoryFingerprint(baseline.copy(products = baseline.products.map { it.copy(categoryId = "audio-real", brandId = "creative-real") })))
    }
}
