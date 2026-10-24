# PASO 4B.2B.2 — Conciliación segura de traspasos e inventario

Fecha: 23 de octubre de 2026, America/Lima.

**Análisis, revisión administrativa y validaciones implementados. No se modificó Firestore de producción ni se habilitó sincronización real.** No se avanzó a 4B.2B.3. SALIDA, pago, formalización, Gestión de Productos y Detalle de producto conservan su lógica existente.

## Estado de los componentes

| Componente | Estado | Alcance real |
|---|---|---|
| Evaluador por producto y serie | IMPLEMENTADO / PROBADO | Fórmula condicionada a evidencia, identidad exacta, versiones, alcance y cantidades. No escribe. |
| Lectura Android de movimientos | IMPLEMENTADO | Consulta explícita por producto, 4 páginas de 50, sin filtro temporal que oculte movimientos anteriores. No se ejecutó contra producción. |
| Interfaz de conciliación | IMPLEMENTADO / PROBADO | Stocks separados, movimientos, bloqueos, declaraciones confirmadas, paginación y sincronización deshabilitada. |
| Expediente persistente | IMPLEMENTADO / PROBADO | Reutiliza el journal versionado local; incluye datos originales, plan, fuente, versiones, decisiones y responsable. |
| Clasificación de operaciones del Dry Run | IMPLEMENTADO / PROBADO | Mapa por ID de operación. No convierte un plan bloqueado en autorizado ni modifica el contrato original. |
| Rechazo independiente en Node | IMPLEMENTADO / PROBADO en Emulator | Atestación firmada versión 2, fórmula y cantidades de cada operación, contexto y versiones nativas comprobados. |
| Idempotencia, fencing y auditoría | PROBADO | Regresiones anteriores conservadas; nuevos datos de conciliación se incluyen en el checkpoint protegido. |
| Verificador del sistema principal | BLOQUEADO / pendiente | No existe un adaptador real que certifique exportación completa, inclusiones y exclusiones. Android no simula esa autoridad. |
| Declaraciones administrativas | IMPLEMENTADO / PROBADO | Se guardan como EN_REVISION/BLOQUEADO, nunca se convierten automáticamente en evidencia verificada. |
| Conciliación verificable de producción | PARCIAL / BLOQUEADO | Modelo e interfaces preparados; sin fuente externa confiable no se calcula un stock conciliado autorizado. |
| Sincronización real / IA / reglas | BLOQUEADO | No se conectó ni desplegó nada. |

## 1. Inspección de la implementación anterior

Se revisaron los informes 4B.1, 4B.2A y 4B.2B.1, `FirestoreInventory.save`, `FirestoreTransferFormalization`, `TransferFormalization`, los lectores de inventario y movimientos, `ExportTemporalReviewer`, Dry Run, contrato Kotlin/Node, ejecutor, política de conciliación anterior y pruebas existentes.

Hallazgos que determinaron el diseño:

- SALIDA descuenta `productos.stock` y `existencias.cantidad` atómicamente, junto con `ultimaSalidaId`. El campo `cantidadRestante` no es el contador que esa transacción descuenta; se muestra separado, sin asumir equivalencia.
- `registros.estado = COMPLETADO` describe el movimiento guardado. **No equivale** a `estadoFormalizacion = COMPLETADO`. Las nuevas SALIDA tienen formalización PENDIENTE; los históricos pueden carecer del campo.
- Confirmar formalización solo actualiza `estadoFormalizacion`, `formalizadoEn` y `formalizadoPor`; no vuelve a descontar stock. No se modificó ese código.
- El lector temporal previo consulta desde el corte declarado hasta el momento de consulta, con máximo 200 documentos. No alcanza para conciliar salidas anteriores al Excel y no demuestra la ausencia de registros mal formados. Se conserva para su función anterior.
- El ejecutor exigía una atestación ficticia firmada con un booleano `reconciled`, hash del historial y hash del producto. Ahora exige además evidencia por movimiento y valida la aritmética; el booleano antiguo ya no basta.
- Continúan pendientes la certificación de fuente, el transporte administrativo y el protocolo de producción descritos en 4B.2A. Esta tarea no los declara terminados.

## 2. Arquitectura y archivos

Flujo de análisis:

`Excel existente → comparación y SyncPlan existentes → selección de producto → lectura explícita de sus movimientos → evaluador puro → detalle y expediente local`.

La declaración local no se envía al ejecutor como autorización. Node acepta únicamente la aprobación firmada de la autoridad de fixtures y vuelve a comprobar los documentos reales de Emulator.

Archivos nuevos:

- `app/src/main/java/com/example/app_clisa/data/excel/TransferReconciliation.kt`: modelos, huella del contexto, política de evidencia, evaluador, aritmética por serie y clasificación del Dry Run.
- `app/src/main/java/com/example/app_clisa/data/excel/FirestoreReconciliationReader.kt`: lector acotado por `productoId`.
- `app/src/main/java/com/example/app_clisa/data/excel/ReconciliationReviewStore.kt`: adaptador del expediente versionado existente.
- `app/src/main/java/com/example/app_clisa/ui/products/TransferReconciliationScreen.kt`: entrada, listado, detalle y revisión administrativa.
- `tools/inventory-sync/transfer-reconciliation.mjs`: evaluación independiente de evidencia firmada y rechazo de operaciones incompatibles.
- `app/src/test/java/com/example/app_clisa/TransferReconciliationTest.kt`: pruebas JVM del motor y persistencia.
- `app/src/androidTest/java/com/example/app_clisa/TransferReconciliationUiTest.kt`: pruebas Compose aisladas.
- `tools/inventory-sync/test/transfer-reconciliation.test.mjs`: 25 escenarios con Firebase Emulator y documentos ficticios.

Archivos modificados:

- `app/src/main/java/com/example/app_clisa/ui/products/ExcelImportAction.kt`: incorpora el acceso a la conciliación desde el flujo existente.
- `tools/inventory-sync/inventory.mjs`: sustituye la aceptación del booleano por la evaluación y validación de la propuesta.
- `tools/inventory-sync/engine.mjs`: comprueba el digest de conciliación y versiones nativas del historial antes del commit y al recuperar unidades aplicadas.
- `tools/inventory-sync/test/engine.test.mjs`: migra las atestaciones de fixtures a versión 2 y añade seis pruebas de integración con el ejecutor.

No se reescribieron el lector Excel, el comparador, el planificador, la transacción SALIDA ni la formalización. No se modificaron otras pantallas, reglas, índices, dependencias o credenciales.

## 3. Modelo de conciliación

Cada entrada enlaza SHA-256 del Excel, `planId`, huella del baseline, producto real, SKU, fuente Excel, existencias y movimientos relacionados, fecha declarada e incidencias pendientes.

Se distinguen:

- `stockOficial`: cantidad del encabezado del producto en Excel, validada contra la suma de sus filas individuales. No se suman resumen y detalle dos veces.
- `stockApp`: campo `stock` del documento actual retenido por la comparación.
- `cantidadRestante`: valor independiente, informado sin utilizarlo como sustituto de stock.
- `stockConciliado`: propuesta calculada únicamente con evidencia confiable y validaciones satisfechas; en casos inciertos es `null`, nunca cero por defecto.
- `difference`: stock oficial menos stockApp. La diferencia por sí sola no justifica ajuste alguno.
- `serialTargets`: cantidades conciliadas por serie exacta, conservando el ID de la existencia original.

Los movimientos conservan ID real, producto/SKU, existencia/serie, cantidad, tipo, estado del movimiento, pago, formalización, fechas, responsable de formalización, tiendas y versión. No se fusionan nombres o series.

## 4. Evidencia y fórmula

Tipos explícitos: `DECLARACION_ADMINISTRATIVA`, `REFERENCIA_VERIFICADA`, `INFERENCIA_SISTEMA`. Los formularios solo generan declaraciones administrativas. Ni una fecha, ni el estado de formalización, ni una coincidencia de totales se convierten en referencia verificada.

Un futuro verificador independiente debe comprobar la autenticidad y el alcance del archivo/exportación, la correspondencia de cada comprobante con cada movimiento y las versiones utilizadas. La implementación predeterminada Android rechaza pruebas no verificadas; los tests utilizan verificadores ficticios aislados. No hay una opción visual para activar el verificador.

Cuando existe evidencia suficiente:

`stockConciliado = stockOficial − suma de SALIDA físicas demostradas NO_INCLUIDAS en ese Excel`.

La resta se hace primero por serie, con control de desbordamiento y de cantidades negativas; el total es la suma de los objetivos individuales. Las incluidas no se descuentan otra vez. COMPLETADO no suma nada; PENDIENTE no implica exclusión.

Ejemplos comprobados con datos ficticios:

| Formalización | Inclusión demostrada | Excel | App | Propuesta |
|---|---|---:|---:|---:|
| PENDIENTE | NO_INCLUIDO, salida de 2 | 10 | 8 | 8 |
| COMPLETADO | INCLUIDO | 8 | 8 | 8 |
| COMPLETADO | NO_INCLUIDO, salida de 2 | 10 | 8 | 8 |
| PENDIENTE | INCLUIDO | 8 | 8 | 8 |
| Cualquier estado | Inclusión desconocida | 8 o 10 | 8 | Sin calcular |

Las entradas/ajustes y tipos distintos de SALIDA CLISA → toqen quedan bloqueados para revisión; no se omiten ni se intenta reinterpretarlos. SIN_VERIFICAR también permanece bloqueado. Un contador de producto discrepante con sus series requiere una evidencia adicional específica de reparación, vinculada al contexto; una referencia genérica de exportación no lo resuelve. Esta reparación solo se ejerce en las fixtures verificadas, no desde el formulario Android.

## 5. Estados y bloqueos

- `CONCILIADO`: propuesta calculada con evidencia verificada; todavía no es autorización de producción.
- `SIN_CAMBIOS`: cantidades y conjunto exacto de series coinciden con la propuesta verificada.
- `REQUIERE_REVISION`: faltan las evidencias confiables, sin que las demás validaciones detecten una contradicción concreta.
- `BLOQUEADO`: identidad, esquema, alcance, cantidades, versiones, fecha, incidencias o plan impiden continuar.

Entre los bloqueos: SKU ambiguo, series duplicadas/ausentes, cantidades inválidas o negativas, historial incompleto, SIN_VERIFICAR, movimientos de otros tipos, campos de formalización incompletos, falta de fecha, futuro, plan de más de 15 minutos al consultar movimientos, versión/cantidad distinta al baseline del plan, evidencias alteradas y archivo diferente.

La desaparición de una serie no se transforma en eliminación ni en cantidad cero. Las eliminaciones siguen prohibidas. Las incidencias RESUELTO del paso anterior no eliminan los errores del Excel original.

Una decisión sobre un producto no modifica datos o decisiones de otro. Los contextos se identifican por producto. Un nuevo plan global invalida revisiones ligadas al plan anterior, como exige su vinculación. Si el alcance es desconocido por un producto sin identidad o un historial sin `productoId`, no se inventa una separación segura: permanece el bloqueo de alcance.

## 6. Interfaz y lecturas

Después de leer y comparar el Excel, abre **Revisar conciliación** desde la importación o la vista previa. Se reutilizan el marco azul marino, encabezado, indicador de etapas y tarjetas con bordes azules; no se añade logo.

Listado: productos de la comparación y creaciones propuestas por el plan, búsqueda local con debounce de 350 ms y páginas de 20. Un producto nuevo sin identidad/versión de inventario comprobable se muestra pendiente/bloqueado; no se inventa stock actual.

Detalle: stocks oficial/App/conciliado separados, `cantidadRestante`, diferencia, fecha declarada, movimientos relacionados, estado, razones y clasificación de operaciones. Las cantidades inciertas se muestran como desconocidas, sin fabricar ceros. Movimientos paginados de 25, con serie, cantidad, fechas, versión y declaración guardada. Cada propuesta pide justificación, referencia y confirmación explícita. USER no puede guardar; ADMIN/SUPER_ADMIN se revalidan antes de guardar.

La consulta nueva es:

```kotlin
db.collection("registros")
    .whereEqualTo("productoId", productId)
    .orderBy(FieldPath.documentId())
    .limit(50)
// siguientes páginas: startAfter(ultimoDocumento), máximo 4 páginas
```

Incluye movimientos anteriores/posteriores a la exportación y documentos del producto sin fecha. No encuentra registros huérfanos sin producto; por eso esta lectura cliente no certifica completitud global. Alcanzar exactamente 200 documentos se considera incompleto, sin una consulta adicional de conteo.

Abrir/paginar/buscar no descarga productos o existencias nuevamente. Leer movimientos requiere pulsar el botón. Revalidar descarta el snapshot anterior; un fallo no conserva una revisión supuestamente fresca. Se usa SERVER, sin listeners ni fallback de permisos.

Por lectura explícita se recuperan como máximo 200 documentos y se realizan dos verificaciones del perfil propio. Hay una verificación administrativa al abrir la herramienta y una por guardado; cambiar filtros no las repite. No se desplegaron índices. Si Firestore deniega la consulta o exige configuración adicional, se informa el fallo sin cambiar reglas ni buscar otra identidad.

## 7. Persistencia y trazabilidad

Directorio privado `noBackupFilesDir/transfer-reconciliation`, separado de las incidencias Excel. Reutiliza `IssueReviewStore`: versión 1, cuenta + SHA, historial de eventos, checksum, revisión optimista y escritura local atómica. No hay colecciones nuevas.

Cada decisión conserva inclusión propuesta, justificación, evidencia/referencia, UID, fecha de revisión y SHA original. Su valor original contiene metadata JSON versionada: plan, producto, SKU, stock oficial/App, cantidadRestante, fecha declarada, movimiento original completo y versiones de producto, existencias y movimientos. `movementOriginal` utiliza el orden documentado en `TransferMovement.identity()`; incluye cantidad, formalización y ambas fechas.

El ID de la revisión incluye una huella del contexto. Si cambian archivo, plan, cantidades, movimientos, formalización, pago, versiones o declaración de exportación, los eventos anteriores no se reutilizan como vigentes. Se conservan para auditoría y se informa el número invalidado del producto. Las decisiones de otros productos no se confunden por prefijos de ID.

Las declaraciones INCLUIDO/NO_INCLUIDO se guardan EN_REVISION; DESCONOCIDO se guarda BLOQUEADO. No existe una acción local que las marque como conciliación verificada. Referencias vacías se rechazan.

Límites heredados: 10 000 eventos/32 MB por expediente. Un contexto original de más de 55 KB, valores no representables o corrupción bloquean la edición en lugar de truncar información. El expediente pertenece a ese dispositivo/cuenta; desinstalar o borrar datos lo elimina. El checksum no es una firma administrativa ni prueba contra manipulación de un dispositivo comprometido.

Android conserva versiones de contenido y el marcador de actualización disponible del inventario; **no inventa un `updateTime` nativo que su SDK no expone**. El ejecutor usa además versiones nativas de Firestore; una reescritura con contenido final idéntico y versión distinta invalida la revisión.

## 8. Integración con Dry Run y Node.js

`planOperationStates` relaciona cada operación del producto con CONCILIADO, REQUIERE_REVISION, BLOQUEADO o SIN_CAMBIOS. Se muestra en el detalle. Operaciones de eliminación/conflicto permanecen bloqueadas. La conciliación no borra bloqueos del planificador ni recompila el plan automáticamente.

Ejemplo: Excel 10, App 8 y salida de 2 demostrada no incluida dan propuesta 8. Si el plan original intenta escribir 10, esa operación se bloquea; no se ejecuta el plan antiguo ni se sustituye silenciosamente su cantidad. Para ejecutar en un futuro haría falta un nuevo plan explícito sobre la propuesta y sus verificaciones.

Node exige la atestación de conciliación **versión 2 dentro de la aprobación Ed25519 existente**: SHA, planId, producto, baseline, fuente, historial, versiones nativas, fecha declarada, alcance, responsable, fecha, justificación y evidencia por movimiento. Solo acepta autoridad `EMULATOR_FIXTURE_ONLY`. Las referencias `fixture:` tienen significado exclusivamente dentro de pruebas firmadas, no en producción.

El evaluador independiente comprueba la fórmula, series y cantidades propuestas. `requireReconciledOperations` rechaza tanto restaurar unidades como descontarlas dos veces. La firma se valida antes de confiar en el expediente; alterar referencias después de firmar se rechaza.

El checkpoint contiene `reconciliationDigest` y `movementVersions`, incluidos en su digest. Antes del commit se comparan digest de la aprobación, contenido del historial, versiones nativas y versiones del inventario; al recuperar unidades ya aplicadas se vuelven a comprobar. Se mantienen las transacciones, fencing, control global de escritores, presupuesto, idempotencia, auditoría y límites existentes.

Registros con producto identificable se evalúan dentro de su producto: un ajuste de otro producto no autoriza ni cambia este. Un historial huérfano impide acreditar el alcance global y sigue bloqueando. La ejecución parcial continúa requiriendo aprobación explícita de alcance y conserva el estado REQUIRES_REVIEW.

El runtime sigue cerrado al proyecto demo y endpoints locales. No hay transporte ni credenciales administrativas en Android. Checkpoints antiguos de Emulator que no tengan la nueva evidencia no se migran ni se consideran válidos automáticamente.

## 9. Pruebas ejecutadas

Evidencias locales:

- `android-build.log`: `assembleDebug`, `testDebugUnitTest`, `assembleDebugAndroidTest`.
- `node-emulator-tests.log`: batería completa del motor y políticas en Firebase Auth/Firestore Emulator.
- `ui-tests.log`: pruebas Compose aisladas del flujo, revisión de incidencias, conciliación, vista previa, fecha y formalización.

Resultado final JVM: **265 pruebas, cero fallos y cero omitidas**, incluidas 29 nuevas de conciliación/persistencia y las lecturas del Excel real mediante `CLISA_EXCEL_SAMPLE`.

Resultado final Node/Emulator: **109 pruebas, cero fallos y cero omitidas**. Se agregaron 25 escenarios de conciliación y seis integraciones con el ejecutor. Las 78 anteriores permanecen cubiertas; las fixtures ahora proporcionan evidencia explícita en lugar del booleano antiguo.

Resultado de interfaz: **27 pruebas aprobadas**, incluidas tres nuevas: stocks separados/sin sincronización, confirmación sin omitir el motor y USER sin guardado/lectura por acción explícita. Se verificó la batería nuevamente sobre el APK final después de la interrupción.

Cobertura de los 25 requisitos del paso:

| Requisitos | Pruebas |
|---|---|
| 1–4 pendiente/completado incluido/no incluido | Escenarios Emulator 01–04 y pruebas JVM equivalentes. |
| 5 histórico sin verificar | Escenario 05; bloqueado sin reinterpretación. |
| 6 varios movimientos | Escenario 06; resta por identidad de serie. |
| 7 anteriores/posteriores | Escenario 07 y lector sin filtro temporal. |
| 8–9 fecha incierta/sin evidencia temporal | Escenarios 08–09 y verificador predeterminado cerrado. |
| 10–11 diferencia/igualdad ambigua | Escenarios 10–11; ninguna prueba automática de inclusión. |
| 12–14 duplicados/incidencias/evidencia insuficiente | Escenarios 12–14 y regresiones del contrato. |
| 15–18 evidencia, movimiento, Excel o plan alterados | Escenarios 15–18, firma alterada, versiones nativas y baseline del plan. |
| 19 repetición | Escenario 19 y recuperación/idempotencia previas del ejecutor. |
| 20 usuario no autorizado | Escenario 20, repositorio USER rechazado, Auth/profile/claims/disabled existentes del ejecutor y UI. |
| 21–24 conservación de registros, stock, pago, formalización | Escenarios 21–24, análisis sin escrituras y prueba de preservación del ejecutor. |
| 25 bloqueo inseguro | Escenario 25, plan que restaura stock rechazado y booleano legado rechazado incluso firmado. |

Las escrituras de preparación, simulación de cambios concurrentes y ejecución del motor ocurrieron **solo en documentos ficticios de Emulator**. Una prueba de versión hace cambiar y restaurar un campo ficticio para comprobar que se detecta una versión distinta aun con contenido final idéntico. Eso no es una escritura de la aplicación a registros reales.

Durante el desarrollo se corrigieron las fixtures de aprobación fechadas antes del movimiento, las expectativas de rechazo y el desplazamiento de nodos virtualizados en los tests Compose. No se desactivaron controles para hacer pasar las pruebas.

## 10. Cómo probar desde Android Studio

1. Ejecuta debug y abre Gestión de Productos → Importar Excel. Lee y compara un archivo, conservando el análisis actual.
2. Abre Revisar conciliación; busca y selecciona un producto. Comprueba que stock oficial, stock CLISA y cantidadRestante se muestran separados.
3. Pulsa Leer movimientos únicamente cuando quieras consultar ese producto. Revisa las series, fechas, estados y bloqueos. No hay consulta automática de todos los historiales.
4. Con ADMIN/SUPER_ADMIN activo, abre Revisar movimiento, indica inclusión declarada, justificación y referencia reales, y confirma. No inventes evidencia para resolver los pendientes reales.
5. Cierra y vuelve a abrir con el mismo archivo y plan: se recupera la declaración. Revalida movimientos o cambia el plan/archivo; una declaración de otro contexto no debe aparecer vigente.
6. Observa que guardar no cambia la propuesta incierta en stock autorizado, ni habilita sincronización, ni modifica pago/formalización.
7. Para escenarios positivos de conciliación verificable utiliza las fixtures y Emulator, no inventes una autoridad del sistema principal dentro de Android.

Compilación local: configurar JAVA_HOME y opcionalmente CLISA_EXCEL_SAMPLE, luego `gradlew.bat :app:assembleDebug :app:testDebugUnitTest :app:assembleDebugAndroidTest --no-configuration-cache`. Motor: `security/paso4b1/Test-Emulator.ps1 -FirebaseCli <ruta instalada de firebase-tools/lib/bin/firebase.js>`.

## 11. Riesgos pendientes y requisitos de 4B.2B.3

Falta implementar y aprobar un adaptador confiable del sistema principal que certifique el archivo/hash, alcance sin filtros, instante de exportación y comprobantes individuales de inclusión/exclusión. La declaración del administrador no cubre este requisito. Los casos positivos están demostrados solo con evidencia ficticia de pruebas.

El lector cliente acotado no certifica inexistencia de registros huérfanos ni ofrece una lectura atómica global. La comparación puede envejecer; el ejecutor vuelve a verificar bajo el protocolo de bloqueo y rechaza planes obsoletos. No se intentó saltar permisos ni hacer consultas de producción para probar la implementación.

Entradas, ajustes, históricos SIN_VERIFICAR, desapariciones de series y productos nuevos sin baseline suficiente requieren revisión ampliada. Los errores del Excel real y el escritor XLSX seguro pendiente del paso anterior conservan sus bloqueos.

Antes de 4B.2B.3 deben definirse: verificador externo, autoridad de aprobación, transporte autenticado de expedientes, conservación administrativa fuera del dispositivo, generación explícita de un nuevo plan conciliado, política de alcance/cerrojo publicada con autorización y revalidación fresca. No se crearon claves, cuentas ni colecciones de producción.

**Confirmación final: cero escrituras a Firestore de producción, cero despliegues de reglas/índices, Excel original intacto, stock y registros reales intactos, sincronización e IA deshabilitadas.**
