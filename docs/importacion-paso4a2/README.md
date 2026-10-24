# Paso 4A.2 — Incidencias del Excel y corte temporal

Fecha: 22 de octubre de 2026. Solo lectura, análisis y Dry Run. **No hay sincronización ni paso 4B.**

## 1. Resultado y estado de los objetivos

| Objetivo | Estado | Resultado |
|---|---|---|
| 1. Diagnosticar las 567 incidencias | **RESUELTO** | Se clasificaron todas por hoja, fila, producto y causa; ver CSV antes/después. |
| 2. Interpretación del Excel | **RESUELTO para la lectura y trazabilidad verificadas** | No se confirmó un error del lector que explique esas 567 incidencias. Se conserva el formato agrupado y los casos ambiguos; se corrigió la validación de grupos cero sin series y se amplió la trazabilidad. La limpieza de los datos de origen sigue pendiente. |
| 3. Revalidar el archivo real | **RESUELTO** | Métricas anteriores/nuevas contrastadas. El archivo permanece INCOMPLETE. |
| 4. Fecha/hora de exportación | **RESUELTO** | Entrada manual con selectores, America/Lima, validaciones de futuro, desconocimiento, duda y antigüedad. |
| 5. Movimientos posteriores | **PARCIALMENTE RESUELTO** | Clasificación local y consulta opcional acotada/paginada probadas. No existe evidencia para reconciliar automáticamente el corte con el sistema principal. |
| 6. Integración con Dry Run/vista previa | **RESUELTO** | Se muestran estado, fecha declarada, movimientos disponibles y bloqueos. Revisar/navegar no repite consultas. |
| 7. Pruebas | **RESUELTO para el alcance local** | Compilación, 200 pruebas unitarias, 15 instrumentadas Android y 36 en Firestore Emulator aprobadas. No certifican producción. |

La confirmación del operador —exportación completa, sin filtros e incluyendo stock cero— queda documentada como información operativa. **No certifica técnicamente el alcance del archivo ni su hora de corte.**

## 2. Diagnóstico antes de cambiar el lector

Se revisaron el informe y la validación de 4A.1, `ExcelInventory.kt`, `XlsxInventoryReader.kt` y `ExcelCompleteness.kt`. Se inspeccionaron las celdas del Excel con una extracción independiente de solo lectura y luego se contrastaron los resultados con el lector Kotlin.

Archivo: `stock.production.lot123 - 2000.xlsx`, 1 005 411 bytes. SHA-256: `ce8c508680f8b547d71572def7fe127012d4e8191c8bc25bc7cbb69065c61a8c`.

Hoja única: **stock.production.lot**, 24 577 filas y 6 columnas. Las primeras cuatro son las requeridas. Las adicionales son **Creado en** y **Remaining Qty**. Las fechas de Creado en pertenecen a filas de existencias, con valores distintos; **no son la fecha de exportación**. Remaining Qty no se usa como sustituto de Cantidad ni se equipara al campo cantidadRestante de Firestore.

La extracción independiente reproduce exactamente las 567 incidencias del comportamiento anterior; la prueba Kotlin posterior confirma las mismas identidades de incidencia —hoja, fila, SKU y categoría—. No se necesitó leer Firestore para este diagnóstico.

| Categoría | Antes | Después | Evidencia y decisión |
|---|---:|---:|---|
| Encabezados de producto no reconocidos | 0 | 0 | Los 1 814 grupos se reconocen. Un grupo reconocido sin SKU es otra categoría, no una fila ignorada. |
| SKU ausentes | 346 | 346 | No hay corchetes con SKU en esos grupos ni Referencia Interna disponible en sus detalles. No deducirlos por nombre o por otra fila. |
| Series con espacios exteriores | 156 | 156 | 6 con espacio inicial y 150 final. Los espacios existen en la celda; no los introduce el lector. Se conserva el literal y se bloquea su uso automático. |
| Números de serie repetidos | 48 | 48 | Son ocurrencias posteriores a la primera, sobre 32 claves repetidas al contrastar espacios exteriores. Se conservan todas; ninguna se elige o fusiona automáticamente. |
| SKU/productos repetidos | 15 | 15 | Son grupos adicionales sobre 14 SKU repetidos. Los grupos pueden tener descripciones diferentes; no se fusionan. |
| Cantidades negativas | 2 | 2 | Filas **7137 y 7146**, producto **DTX/64GB**, cantidad **-1** en cada una. Se conserva el valor para diagnóstico, pero es inválido para importar. |
| Cantidades no numéricas, fraccionarias o faltantes | 0 | 0 | No explican las incidencias de este archivo. Continúan cubiertas por pruebas sintéticas. |
| Diferencias de cantidad/recuento entre resumen y detalle | 0 | 0 | Los resúmenes concilian con los detalles leídos, incluyendo valores inválidos que se retienen para revisión. Conciliar no convierte un -1 en válido. |
| Filas no interpretadas o sin asociación reconocida | 0 | 0 | Hay grupos ambiguos por identidad, pero no filas de este archivo perdidas por el parser. |
| Problemas del contenedor/formatos de celda/otras causas | 0 | 0 | No se encontró una incompatibilidad de XLSX que justifique corregir esas 567 incidencias. |
| **Total** | **567** | **567** | Incidencias sobre **565 filas**; algunas filas tienen más de una causa. |

Ejemplos trazables:

- Fila 21676: `AIO HP 200 G4 ... (1)` es un encabezado válido del formato agrupado, pero no contiene SKU. La fila 21677 conserva su serie `8CC202420K`, cantidad 0 y origen. No se asigna el SKU del grupo anterior.
- Fila 1498: serie `S_34E97308000153 ` con espacio final real; se conserva el texto, no se recorta para que pase la validación.
- Fila 523: otro grupo de SKU `15-BS`, ya presente desde la fila 511. No se resuelve por similitud de nombres.
- `SFYRS/1000G` también aparece repetido en el Excel; el hecho de que un SKU pueda tener ID Firestore diferente no resuelve un duplicado del origen.

**No se redujo artificialmente el número de incidencias.** No hay evidencia para rellenar los 346 SKU, recortar las 156 series, fusionar los duplicados o convertir las dos cantidades negativas a cero. Deben corregirse o explicarse en el sistema principal y exportarse nuevamente, o resolverse mediante un procedimiento de revisión autorizado.

Detalle completo: [incidencias-antes.csv](incidencias-antes.csv) y [incidencias-despues.csv](incidencias-despues.csv). El segundo incluye tipo estable, hoja, fila, SKU, nombre, serie y mensaje. [comparacion-incidencias.json](comparacion-incidencias.json) confirma correspondencia de las 567 identidades. Los CSV son diagnósticos locales, no archivos de importación ni propuestas de cambios a Firestore.

## 3. Cambios localizados de interpretación y validación

- El lector ZIP/SAX existente permanece. No se sustituyó por otra biblioteca ni se ejecutan fórmulas/macros.
- `ExcelIssueKind` clasifica las causas; `ExcelIssue` conserva contexto de producto/serie. Se retienen hasta **10 000** detalles y se agregan **todos** los contadores aunque un archivo hostil supere ese límite. La interfaz indica si los detalles están truncados; una incidencia no desaparece del total ni deja de bloquear.
- En el Excel real ahora se conservan **las 567 incidencias completas**, frente al límite anterior de 200 detalles. Eso mejora el diagnóstico, no cambia los datos interpretados.
- Se preserva el SKU literal entre corchetes. Si contiene espacios exteriores se señala como incidencia en vez de recortarlo silenciosamente. Guiones, barras, acentos, corchetes anidados y ceros iniciales permanecen. El archivo real no tiene ese nuevo caso de espacios exteriores en SKU; sus cuentas no cambian.
- Un grupo reconocido con resumen **0** y sin series ya no se declara técnicamente inválido solo por estar vacío. Se conserva como producto sin stock, sin inventar una existencia. Si el resumen es positivo sin detalle, o el recuento declarado no concilia, sigue siendo crítico. El comparador tenía la misma restricción; se ajustaron únicamente sus dos condiciones para productos nuevos y existentes. La prueba verifica stock cero conocido, conservación del producto y ausencia de existencias inventadas. Un recuento de series positivo sin detalle sigue siendo conflicto. Estas correcciones no explican ni eliminan las incidencias reales.
- Los productos/series de cantidad cero siguen representados. Las filas vacías no generan existencias. Los resúmenes no se suman al detalle. Las filas no asociables se conservan como incidencias con origen, sin tomar el último producto por conveniencia.

## 4. Comparación antes/después del archivo real

| Métrica | Antes | Después |
|---|---:|---:|
| Grupos reconocidos | 1 814 | 1 814 |
| SKU distintos no nulos | 1 453 | 1 453 |
| Grupos sin SKU | 346 | 346 |
| Grupos con SKU único y todas sus series sin error local | 1 395 | 1 395 |
| Filas de serie leídas | 22 762 | 22 762 |
| Series válidas según validación local de la fila | 22 528 | 22 528 |
| Series pertenecientes a los 1 395 grupos anteriores | 17 727 | 17 727 |
| Grupos con resumen de cantidad cero | 1 016 | 1 016 |
| Incidencias | 567 | 567 |
| Detalles de incidencia retenidos por Android | 200 | 567 |
| Filas no interpretadas | 0 | 0 |
| Diferencias de resumen/detalle | 0 | 0 |
| Cantidades negativas | 2 | 2 |
| Completitud | INCOMPLETE | INCOMPLETE |

«Fila de serie válida» no significa «existencia autorizada para importar»: puede estar dentro de un grupo sin SKU o ambiguo. Los 1 395 grupos son una métrica diagnóstica —SKU único en este archivo y series sin error local—, no una certificación de sincronización. Los 1 016 son **grupos con cantidad cero**, sin inventar un número de productos únicos para los que carecen de identidad.

Evidencia: [baseline-independent.json](baseline-independent.json) y [validacion-despues.txt](validacion-despues.txt). El script local `diagnose_baseline.py` extrae y reproduce la clasificación anterior; no modifica el XLSX. La prueba `ExcelInterpretation4a2Test` valida la clasificación actual directamente en Kotlin.

Con un archivo técnicamente correcto, sin prueba autoritativa de alcance, el estado seguirá **UNKNOWN**. La declaración manual de fecha, incluso válida, tampoco produce COMPLETE_VERIFIED. Las posibles bajas siguen bloqueadas por alcance, identidad, referencias históricas, reglas y temporalidad, según corresponda.

## 5. Fecha y hora de exportación

Se añadió una sección compacta en Seleccionar Excel con:

- Selector de fecha y selector de hora en estilo azul oscuro.
- Texto: «Indica cuándo generaste este archivo desde el sistema principal».
- Zona fija **America/Lima**; fecha y hora comienzan sin seleccionar.
- Acción «No conozco la fecha u hora» y marca de horario aproximado/dudoso.
- Validación de fecha futura, hora futura del mismo día y ausencia de cualquiera de los dos valores.
- Revisión conservadora de antigüedad: **más de 24 horas** se marca STALE y exige nueva exportación o revisión. Es un umbral configurable de esta etapa, no un requisito certificado del negocio ni una ventana que autorice escrituras.

`ManualExportTime` convierte fecha/hora a instante mediante America/Lima. La selección de fecha del calendario Material usa medianoche UTC: se conserva su día de calendario antes de combinarlo con la hora de Lima, evitando desplazar la fecha al día anterior.

No se usa Creado en, metadata del XLSX, fecha de modificación del archivo ni hora de importación como exportación. Abrir/cancelar un selector no rellena el dato. Elegir otro archivo o limpiar el actual elimina la declaración anterior y su consulta temporal, evitando aplicar el corte de un Excel a otro. La declaración se mantiene en memoria durante ese flujo; al reiniciarlo se debe indicar nuevamente.

Las capturas verificadas del emulador están en [selector de fecha](clisa-export-date.png), [selector de hora](clisa-export-time.png) y [vista previa con revisión temporal](clisa-export-preview.png). Corresponden a componentes aislados de prueba. Los textos nativos de mes/días/hora siguen el idioma del dispositivo; los títulos e instrucciones del módulo están en español.

## 6. Movimientos existentes y política temporal

La búsqueda de escritores de inventario confirma **SALIDA** en `FirestoreInventory.save`: registro, stock de producto y cantidad/estado de existencia cambian en la misma transacción. `AJUSTE_ENTRADA` y `AJUSTE_SALIDA` existen como contratos de reglas/propuestas, pero no se encontró un ejecutor Android actual de AJUSTE. No se encontró un flujo implementado de ENTRADA independiente. Los tipos desconocidos se señalan; no se presupone su signo ni su efecto.

`ExportTemporalReviewer` clasifica datos suministrados como anteriores, posteriores, exactamente en el corte o fecha desconocida. También distingue falta de prueba de timestamp de servidor, esquema inválido y relación no demostrada con el inventario externo. No elige el primer registro duplicado.

Un timestamp Firestore bien formado no demuestra por sí solo cómo se generó ni que el movimiento esté ausente del sistema principal. El lector no marca `serverTimeProven` ni `reflectedInExportProven` a partir del formato del campo. Los registros anteriores a las reglas propuestas pueden no tener el mismo contrato de timestamps.

**Ejemplo probado:** Excel 5 unidades a las 10:00; SALIDA registrada a las 10:10; Firestore observado 4. El Dry Run conserva la diferencia 4 → 5 como propuesta **BLOCKED**, con `POST_EXPORT_MOVEMENTS_REQUIRE_REVIEW` y `TEMPORAL_CUTOFF_RECONCILED`. No repone 5 ni descuenta otra vez 1 automáticamente.

La precisión manual es de minutos, no un corte certificado al segundo. Tampoco un movimiento posterior a la hora declarada demuestra que no esté incluido en el sistema principal. Los movimientos en el minuto del corte o con fechas dudosas requieren revisión. En ausencia de una relación demostrable se bloquea conservadoramente el plan completo, no solo un producto que parezca coincidir.

### Consulta opcional de registros

Solo se ejecuta al pulsar **«Revisar movimientos (máx. 200)»**, con fecha completa válida y no antigua. No se lanza al abrir la vista previa, navegar, expandir productos ni cambiar filtros.

```kotlin
db.collection("registros")
    .whereGreaterThanOrEqualTo("fechaHora", Timestamp(Date(corteDeclarado)))
    .whereLessThanOrEqualTo("fechaHora", Timestamp(Date(finDeConsulta)))
    .orderBy("fechaHora")
    .limit(50)
// Siguientes páginas: startAfter(ultimoDocumentSnapshot)
```

Máximo cuatro páginas. Cursor documental evita perder registros que comparten timestamp. Exactamente 200 resultados se considera «pueden faltar más», sin hacer una quinta lectura para contar. El rango máximo es 24 horas. No se usa un listener, agregación global ni búsqueda alternativa por IAM. Se comprueban módulo/estado de acceso y continuidad de sesión, se respetan cancelaciones y se invalida el resultado si cambia el corte/archivo.

La consulta devuelve **solo el rango desde el corte**. No pretende descargar ni revisar todo el historial anterior. La clasificación BEFORE sirve también para datos suministrados/cachés futuros y pruebas; el contador de la interfaz está rotulado como datos consultados, no como total histórico.

Los documentos sin fechaHora o con otro tipo no aparecen en ese índice/rango. La prueba de Emulator demuestra esa limitación. Por eso terminar las páginas o devolver cero resultados **no certifica ausencia de movimientos**, ni prueba que todas las escrituras de inventario tengan historial. Se mantiene `HISTORY_SCOPE_UNPROVEN`. También se avisa del instante observado: pueden existir movimientos posteriores a la consulta.

El coste es hasta **200 documentos devueltos**, más validaciones de perfil, evaluación de reglas y mínimos de consultas vacías según facturación. Reintentar tras error puede volver a consumir lecturas. Los resultados terminados se conservan en memoria; el botón no vuelve a consultar el mismo corte al navegar. No se consumieron estas lecturas en producción durante las pruebas.

La consulta usa filtros/orden sobre fechaHora; no se desplegó un índice compuesto ni una regla. Las pruebas locales no certifican los índices o permisos actuales del proyecto desplegado. Si producción rechaza la lectura, se muestra el error y no se intenta saltar permisos.

## 7. Dry Run y vista previa

- Se reutilizan `InventoryComparison`, `InventoryPreviewBuilder`, el generador, identidades y revisión de recuperación existentes. Se conserva cómo se suman y emparejan productos y series; solo se exceptúa el resumen cero sin series de la condición que lo marcaba como no conciliable.
- Se calcula SHA-256 sobre los mismos bytes temporales que lee el parser. La evidencia incluye evaluación técnica y revisión temporal.
- El plan de esquema **3** incluye declaración, incertidumbre, movimientos/rango observados y bloqueos en su identidad canónica. Cambiar el corte o los movimientos cambia el planId; una declaración anterior no reutiliza un plan accidentalmente.
- Se añade `TEMPORAL_CUTOFF_RECONCILED` y razones detalladas a todas las mutaciones cuando falta un corte reconciliado. Una fecha manual válida produce REQUIRES_REVIEW; desconocida/futura/dudosa/antigua produce BLOCKED. Ninguna permite escribir.
- Un journal de otro plan queda sujeto a la validación existente de identidad; no se alteró ni se ejecutó recuperación. Las operaciones SKIP_UNCHANGED siguen sin contar como escrituras.
- La vista previa muestra interpretación/completitud, incidencias, exportación declarada, advertencia de corte, movimientos consultados y detalles locales, operaciones temporalmente bloqueadas y posibles bajas todavía bloqueadas.
- El plan se genera fuera del hilo principal y sin nuevas lecturas de inventario. No se vuelve a comparar Firestore para modificar la fecha o revisar tarjetas. Solo la acción explícita de registros puede hacer la consulta pequeña anterior.
- `productionExecutable`, `destructiveEligible` y la reconciliación autoritativa siguen false. **El botón de sincronización sigue deshabilitado.**

## 8. Archivos creados y modificados

Directorio base Kotlin: `app/src/main/java/com/example/app_clisa/`.

Nuevos:

- `data/excel/ExportTemporalReview.kt`: declaración, clasificación, bloqueos y modelo de datos temporales.
- `data/excel/FirestoreMovementReader.kt`: consulta de registros explícita, paginada y acotada.
- `ui/products/ExportTimeSection.kt`: selectores y sección de seguridad de solo revisión.

Modificados:

- `data/excel/ExcelInventory.kt`: tipos/counters de incidencias y conservación literal del SKU.
- `data/excel/ExcelCompleteness.kt`: permite resumen cero sin series, manteniendo controles de cantidad/recuento.
- `data/excel/InventoryComparison.kt`: corrección puntual de ese mismo caso para productos nuevos y existentes, sin reescribir el comparador.
- `data/excel/SyncPlan.kt`: evidencia temporal y resultado del plan.
- `data/excel/InventoryDryRunPlanner.kt`: bloqueos temporales e identidad de plan versionada.
- `ui/products/ExcelImportAction.kt`: hash del archivo, coordinación de declaración, plan local y consulta opcional; invalida resultados anteriores al cambiar archivo/corte.
- `ui/products/ImportExcelScreens.kt` y `InventoryPreviewScreen.kt`: punto de integración visual de esas secciones, sin rediseñar el resto.

Pruebas nuevas: `ExportTemporalReviewTest.kt`, `ExcelInterpretation4a2Test.kt`, `ExportTimeSectionTest.kt`.

Pruebas de Firestore reutilizan el entorno local de 4A.1: nuevo `security/paso4a1/emulator/temporal.test.mjs`; se añade script `test:4a2` al package.json y opción acotada al lanzador PowerShell. El ruleset propuesto, copia auditada y configuración de producción **no cambian**.

`XlsxInventoryReader.kt`, `InventoryPreview.kt`, `SyncPlanIdentity.kt`, `SyncRecoveryReview.kt`, Gestión de Productos y Detalle de producto no requirieron cambios en este paso.

## 9. Pruebas y reproducción

- **200 pruebas unitarias**, cero fallos/errores/omitidas: incluyen las 177 anteriores y 23 nuevas de interpretación, fecha, corte y Dry Run. Se leyó el Excel real y se generó el CSV Kotlin con las 567 incidencias.
- **15 pruebas instrumentadas**, cero fallos/omitidas: incluyen las pantallas anteriores con el Excel real y 5 nuevas pruebas de selectores, incertidumbre, fecha futura y navegación de datos temporales en memoria. Emulador Android API 35 sin red; hosts Compose aislados y callbacks ficticios, no sesiones de Auth ni consultas a producción.
- **36 pruebas de Firestore Emulator**, todas aprobadas: las 33 anteriores más 3 de paginación temporal, límite de lecturas y permisos. Proyecto fijo `demo-clisa-4a1`, host 127.0.0.1:8087, datos ficticios.
- Las capturas de los selectores se revisaron para comprobar disposición y colores; se ajustaron explícitamente al azul marino/azul de CLISA.
- `assembleDebug` y compilación de pruebas Android correctas. Evidencias: [test-results.json](test-results.json), [android-tests.log](android-tests.log), [emulator-results.txt](emulator-results.txt).

Desde la raíz del proyecto, con rutas ajustadas si cambia la instalación:

```powershell
$env:JAVA_HOME = 'C:\Program Files\Android\Android Studio1\jbr'
$env:CLISA_EXCEL_SAMPLE = 'C:\Users\TUF\Documents\stock.production.lot123 - 2000.xlsx'
$env:CLISA_EXCEL_AUDIT_DIR = Join-Path $PWD 'docs\importacion-paso4a2'
.\gradlew.bat :app:assembleDebug :app:testDebugUnitTest :app:assembleDebugAndroidTest --no-configuration-cache
```

Con un emulador Android aislado y el archivo copiado a `/sdcard/Download/clisa-import-validation.xlsx`, ejecutar las clases `ImportExcelScreensTest`, `InventoryPreviewScreenTest` y `ExportTimeSectionTest`, con argumento `excelSample` apuntando a ese archivo. No ejecutar una salida de prueba ni usar usuarios reales.

Reglas/consultas locales:

```powershell
.\security\paso4a1\emulator\Test-LocalRules.ps1 -FirebaseCli 'firebase.cmd' `
    -JavaHome 'C:\Program Files\Android\Android Studio1\jbr' -TestCommand 'npm.cmd run test:4a2'
```

Para comprobar la app manualmente: Gestión de Productos → Importar Excel → seleccionar archivo → indicar fecha/hora o desconocimiento → Analizar/Revisar. La comparación existente consume lecturas si se prueba con una sesión real; la consulta opcional de movimientos también. Las validaciones automatizadas aquí descritas usan datos locales o Emulator. Volver entre las secciones de resultados no repite lecturas. Cambiar de Excel borra la declaración temporal anterior.

## 10. Riesgos pendientes para 4B y producción

1. Resolver las incidencias reales en el sistema principal o mediante un procedimiento autorizado con trazabilidad. No inventar SKU, fusionar productos o recortar series silenciosamente.
2. Demostrar alcance y corte de exportación con evidencia confiable. Una hora manual, aun exacta según el operador, no prueba qué movimientos externos incluye el archivo.
3. Definir si las salidas/ajustes de CLISA se reflejan en el sistema principal, con identificadores y reglas de conciliación. No descontar todo movimiento posterior automáticamente ni restaurar cantidades anteriores.
4. Detectar cambios sin historial o fechas válidas requiere garantías del protocolo de escritura; una consulta por fechaHora no basta. Tampoco sustituye el cerrojo/fencing, revalidación de versiones y prevención de concurrencia diseñados en 4A.1.
5. La política de cantidadRestante, duplicados y bajas sigue pendiente. Las reglas y el componente privilegiado de sincronización continúan sujetos a aprobación.
6. Las pruebas locales no inspeccionaron permisos o datos actuales de producción. Si una lectura autorizada falla allí, debe reportarse y revisarse sin atajos.

**Confirmación:** no se creó, actualizó ni eliminó ningún documento real de Firestore. No se consultó producción, desplegaron reglas/índices, cambiaron usuarios/roles o alteraron registros históricos. El Excel original no se modificó. Solo hubo fixtures ficticios en Emulator y archivos de prueba locales. No se habilitó la sincronización ni se implementó 4B o IA.
