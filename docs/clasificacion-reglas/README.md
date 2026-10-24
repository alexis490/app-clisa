# Clasificación determinista integrada en Importar Excel

Implementada la etapa de reglas, sin Gemini, sin servicios de pago y sin escrituras en producción. Las aprobaciones de Android son propuestas locales, no una autorización para cambiar stock. El proceso Node de aplicación de metadatos funciona exclusivamente en Firebase Emulator.

## Estructura utilizada

Se revisaron `security/audit-2026-10-21/AUDITORIA.md`, `schema-samples.json`, `relations.json`, las reglas descargadas y los repositorios actuales. Los campos reales son:

- `productos`: `codigo`, `nombre`, `categoriaId`, `marcaId`, `descripcion`. El SKU y el ID del documento se conservan por separado.
- `categorias` y `marcas`: `nombre` y `activo`. Los IDs se obtienen de los documentos; no se calculan a partir del nombre.
- No existe un campo de alias acreditado en los catálogos. Los alias son configuración local compartida, no nuevos campos de Firestore.

Esta revisión usa los archivos disponibles de la auditoría. No acredita el estado actual de producción: al cargar la pantalla, el SDK de Android consulta los catálogos reales y valida su esquema y los permisos publicados. Si la lectura falla o resulta incompleta, no se generan sugerencias parciales ni se intenta otro acceso.

## Archivos

Nuevos:

| Archivo relativo a la raíz del proyecto | Función |
| --- | --- |
| `app/src/main/assets/classification-rules.tsv` | Versión, alias explícitos y exclusiones compartidos por Kotlin y Node. |
| `app/src/main/java/com/example/app_clisa/data/excel/ProductClassification.kt` | Normalización, identificación única, reglas, estados, decisiones y manifiesto. |
| `app/src/main/java/com/example/app_clisa/data/excel/FirestoreClassificationCatalog.kt` | Carga controlada de ambos catálogos con SDK cliente. |
| `app/src/main/java/com/example/app_clisa/data/excel/ClassificationReviewStore.kt` | Aprobaciones persistentes por usuario y SHA-256 del Excel. |
| `app/src/main/java/com/example/app_clisa/ui/products/ProductClassificationScreen.kt` | Revisión, edición individual, aprobación masiva y confirmación. |
| `tools/inventory-sync/classification.mjs` | Mismas reglas y revalidación/aplicación atómica de metadatos en Emulator. |
| `app/src/test/java/com/example/app_clisa/ProductClassificationTest.kt` | 15 pruebas JVM. |
| `app/src/androidTest/java/com/example/app_clisa/ProductClassificationUiTest.kt` | 5 pruebas Compose sin consultas remotas. |
| `tools/inventory-sync/test/classification.test.mjs` | 21 pruebas con Auth y Firestore Emulator. |
| `docs/clasificacion-reglas/kotlin-fixture.json` | Contrato ficticio generado por Kotlin para probar paridad con Node. |

Modificados:

| Archivo relativo a la raíz del proyecto | Cambio |
| --- | --- |
| `app/src/main/java/com/example/app_clisa/data/excel/InventoryComparison.kt` | Retiene categoría, marca y descripción originales en el modelo de lectura; no cambia la comparación de stock. |
| `app/src/main/java/com/example/app_clisa/data/excel/FirestoreInventoryReader.kt` | Extrae esos campos de las lecturas ya existentes, sin consultas adicionales de productos. |
| `app/src/main/java/com/example/app_clisa/data/excel/IssueReviewStore.kt` | Permite guardar varias decisiones con una sola escritura local y revisión de versión. |
| `app/src/main/java/com/example/app_clisa/ui/products/ExcelImportAction.kt` | Entrada a clasificación desde el flujo y la vista previa existentes. |
| `app/src/main/java/com/example/app_clisa/ui/products/ImportFinalScreen.kt` | Adjunta el manifiesto de clasificación a `review.json` del expediente exportado. |
| `tools/inventory-sync/cli.mjs` | Opción de vista previa/aplicación de clasificación restringida al proyecto demo. |

No se modificaron reglas, Detalle de producto, categorías, búsquedas, escaneo ni otros módulos.

## Reglas y decisiones

1. Se clasifica una vez por SKU único del Excel. Las series no originan clasificaciones adicionales. SKU duplicado, identidad incompleta, documento ambiguo o discrepancia entre nombres originales impiden la aprobación automática. Nunca se selecciona la primera coincidencia ni se fusiona por parecido.
2. Se normalizan mayúsculas, tildes, puntuación y espacios únicamente para comparar. El nombre, el SKU, la descripción y los IDs originales no se normalizan ni se escriben.
3. Las coincidencias exigen palabras o frases completas. `CreativePro` no coincide con `Creative`, ni `hpbook` con `HP`.
4. Se consideran los nombres de todos los documentos activos. Los alias adicionales solo se activan cuando existe una entrada cuyo **nombre** normalizado coincide con el nombre configurado en el TSV; el resultado siempre es su ID real.
5. Alias de categorías: accesorios para computadora/PC; accesorios, mochilas y soportes para laptop; audífonos, auriculares, micrófonos y headset; case/gabinete/chasis; parlante/speaker. Se excluyen cables, adaptadores y soportes de las categorías de audio; SSD, laptop y celular de cases. No se interpreta una marca como categoría.
6. Alias de marcas: HP/Hewlett Packard, Western Digital/WD, 1ST Player/1stplayer. Si dos documentos del catálogo coinciden con estos alias, ambos se muestran como conflicto; no se elige uno.
7. Cada campo tiene estado independiente: `ASIGNADO`, `REVISAR` o `SIN_IDENTIFICAR`. `ASIGNADO` significa sugerencia inequívoca o referencia válida conservada; **no significa escritura realizada**. Referencias válidas existentes quedan bloqueadas para edición. Una referencia inexistente o inactiva requiere revisión individual.
8. La aprobación masiva solo incluye sugerencias inequívocas no aprobadas. La individual exige elegir un ID activo y justificarlo. Ninguna aprobación puede resolver una identidad de producto ambigua. La descripción existente se conserva; si falta, queda pendiente, sin generar texto.

## Integración y trazabilidad

En Importar Excel, después de comparar, abrir **Clasificar categorías y marcas** y pulsar **Cargar catálogos y clasificar**. La pantalla mantiene el marco azul marino de importación y pagina localmente los productos de 20 en 20. Revisar un campo, seleccionar una referencia existente, justificar y confirmar; o confirmar **Aprobar inequívocas**. Navegar, editar y paginar no consulta Firestore.

Las decisiones se guardan en almacenamiento privado `noBackupFilesDir/product-classification`, por usuario y hash del archivo. Se conservan el producto e ID reales, campo, valor original, candidatos y reglas, versión/hash de reglas, hash del catálogo, justificación, revisor y fecha. La suma de comprobación detecta corrupción; no sustituye una firma administrativa. Se comprueba además la revisión del diario antes de exportar para rechazar un manifiesto desactualizado. Repetir la misma aprobación no añade eventos ni escrituras remotas. Actualizar un catálogo o las reglas invalida las aprobaciones anteriores para el contexto nuevo, sin borrar su historial.

**Resumen final y expediente → Exportar expediente administrativo** incorpora el manifiesto vigente en `review.json.classification`. Se reutilizan el Excel original y su verificación de hash. No se modifica el contrato ni se habilita ninguna operación del motor 4B.

El comando existente admite `--classification <review.json o manifiesto.json> --output <directorio>` para revisar sin escribir. Solo en Emulator admite además `--apply-classification true --id-token-file <archivo-local-con-token-del-emulador>`. No acepta proyectos ni hosts arbitrarios. El token no se incluye en los informes ni se coloca en el APK o el expediente.

Antes de aplicar, Node verifica el ID token, el usuario no deshabilitado, claims actuales, rol ADMIN/SUPER_ADMIN, perfil ACTIVO y coincidencia del revisor. Una transacción vuelve a comprobar catálogo completo, IDs activos, reglas, valores originales, SKU único y trazabilidad de cada decisión. Solo puede actualizar `categoriaId` y `marcaId`, máximo 100 productos por invocación. Repetir un manifiesto ya aplicado no vuelve a escribir. Si el producto aún no existe, se informa `pendingCreation`; no se crea y no se llama al motor de stock.

## Lecturas, permisos y límites reales

- Android carga `categorias` y `marcas` una vez por acción explícita, con `orderBy(documentId)`, `limit(100)` y `startAfter`. Máximo inferior a 1 000 documentos por catálogo; si no se acredita fin de colección, se detiene. Incluye inactivos para reconocer referencias inválidas.
- Coste aproximado: C + M documentos de catálogo, posibles consultas de página final vacía y tres lecturas del perfil para autorizar la carga. Una aprobación individual o masiva verifica el perfil una vez adicional. No hay listeners, búsquedas por pulsación ni lecturas de existencias para clasificar.
- Reutiliza la comparación que ya se realizó; no repite su recorrido de productos/existencias. Esa comparación previa conserva su coste existente, no pasa a ser gratuita.
- En la aplicación administrativa de Emulator, cada transacción relee ambos catálogos y el perfil; valida cada destino por ID y por SKU (`where codigo == SKU`, `limit(2)`). Aproximadamente C + M + 1 + 2N lecturas para N productos, además de mínimos por consultas vacías y posibles reintentos. Máximo una escritura por producto realmente cambiado. No se necesitan índices compuestos nuevos.
- Las reglas auditadas permiten leer los catálogos a cuentas activas, pero `edicionProductoValida()` limita la edición a `nombre`, `estado`, `actualizadoEn`: no autoriza estos cambios de categoría/marca desde Android. Por eso no se añadió una escritura cliente ni se cambiaron reglas. La aplicación de metadatos queda disponible y probada solo en Emulator, con proceso administrativo autenticado.
- Catálogos nuevos pueden clasificarse por su nombre completo, pero requerirán alias explícitos adicionales para sinónimos; no hay inferencia semántica. Conflictos, referencias inactivas o identidades inciertas quedan pendientes. Un catálogo cambiado requiere recargar y revisar de nuevo.
- El expediente está limitado a 8 MB y el diario a 10 000 eventos. Si se alcanza un límite, se informa el error, sin habilitar stock ni efectuar escrituras remotas. No hay backend de producción habilitado por este cambio ni integración de Gemini.

## Validación final

- Compilación `assembleDebug` y `assembleDebugAndroidTest`: correctas.
- **46 pruebas JVM aprobadas**: clasificación 15, expediente previo 24, contrato 4 y finalización 3. Incluyen el Excel real, una clasificación por producto, conservación del ID distinto al SKU, referencias inexistentes, duplicados, permisos, reimportación, catálogo cambiado, expediente corrupto/desactualizado y plan de stock intacto.
- **36 pruebas Node aprobadas en Firebase Emulator**: clasificación 21 y regresión completa del flujo final 15. Incluyen los diez escenarios solicitados, paridad exacta Kotlin/Node, autorización real con Auth Emulator, rechazo de campos de stock, SKU duplicado posteriormente y aplicación idempotente de metadatos.
- **15 pruebas Android aprobadas** en AVD API 35: clasificación 5, revisión de incidencias 4, importación 5 y resumen final 1. Red del AVD desactivada; no se instaló en un teléfono físico.
- Fallos pendientes: **0**. Un primer comando de regresión usó un nombre de clase de prueba inexistente; se corrigió y la ejecución conjunta de 15 pruebas pasó.
- El Excel real sigue con 22 762 filas de series y 567 incidencias; SHA-256 `ce8c508680f8b547d71572def7fe127012d4e8191c8bc25bc7cbb69065c61a8c` intacto. Los catálogos de prueba son ficticios y no son un resultado de clasificación del inventario real de producción.
- Evidencia: `android-build.log`, `firebase-emulator.log`, `android-ui.log` en esta carpeta. No se ejecutaron consultas ni escrituras de prueba contra producción, no se desplegaron reglas ni índices y no se cambiaron datos históricos, roles o credenciales.

## Repetir las pruebas

Desde Android Studio abrir el proyecto y ejecutar las clases `ProductClassificationTest` y `ProductClassificationUiTest`. Para incluir el archivo real, establecer `CLISA_EXCEL_SAMPLE` a su ruta local en la configuración de pruebas JVM; para regenerar la paridad, establecer `CLISA_CLASSIFICATION_FIXTURE` a la ruta absoluta `docs/clasificacion-reglas/kotlin-fixture.json`.

Para las pruebas de Node usar el lanzador existente `security/paso4b1/Test-Emulator.ps1` con la ruta local de Firebase CLI y `-TestCommand 'node --test --test-concurrency=1 test/classification.test.mjs test/final-pipeline.test.mjs'`. El lanzador fija el proyecto `demo-clisa-sync-4b1`, Firestore `127.0.0.1:8086` y Auth `127.0.0.1:9098`. Nunca sustituirlo por el proyecto de producción para estas pruebas.
