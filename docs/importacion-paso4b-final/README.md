# Paso 4B — Política vigente: Excel como inventario de referencia

Actualización definitiva del 23 de octubre de 2026. **Esta sección sustituye la política de conciliación descrita en el informe histórico que se conserva más abajo. No aplicar sus fórmulas ni requisitos de inclusión de movimientos.**

## Resultado actual

Android y Node usan `EXCEL_REFERENCE_V1`, con planes versión 4 y checkpoints/jobs versión 3. La importación semanal fuera de jornada y la formalización previa son decisiones administrativas externas: la aplicación no las verifica ni exige comprobantes de formalización.

Para cada producto identificado inequívocamente por `codigo`:

```text
productos.stock propuesto = suma de cantidades válidas de sus series del Excel
existencia incluida en Excel: cantidad propuesta = cantidad literal validada del Excel
existencia ausente del Excel: conservar documento y cantidad, sin escritura
producto ausente del Excel: conservar documento, stock e historial
```

Se comprueba que la suma de series coincide con el resumen del producto, sin sumar dos veces los encabezados. No se resta ningún registro PENDIENTE, COMPLETADO o SIN_VERIFICAR. El ejecutor no consulta `registros` para calcular ni autorizar el stock y nunca modifica sus documentos. Las cantidades no se deducen por diferencia con el inventario anterior.

`cantidadRestante`, descripciones, referencias de categoría y marca y demás metadatos existentes se conservan. La clasificación por reglas y sus aprobaciones siguen en el expediente, con la misma revisión independiente de IDs; no conceden permisos de stock. Descripciones faltantes continúan pendientes, sin Gemini.

## Correcciones realizadas sobre los componentes existentes

- `app/src/main/java/com/example/app_clisa/data/excel/InventoryDryRunPlanner.kt`: retira los bloqueos de corte/conciliación, prepara altas y cantidades del Excel, conserva las ausencias como `REVIEW_MISSING_EXISTENCE` sin operación de eliminación. La diferencia de `cantidadRestante` es informativa, no bloquea actualizar `stock`.
- `SyncPlan.kt`, `SyncExecutionContract.kt`: nueva versión/política explícita y operación informativa de ausencia. Los planes viejos no se reinterpretan; se rechazan y deben generarse de nuevo. No se reanuda un checkpoint antiguo con la fórmula nueva.
- `InventoryComparison.kt`, `InventoryPreview.kt`: las ausencias se conservan y no excluyen por sí solas las escrituras de productos presentes; se mantienen comparaciones de series individuales y todas las validaciones.
- `ImportFinalization.kt`: resumen basado en candidatos técnicamente válidos, sin entradas ni verificación de conciliación. Un candidato no es una autorización de producción.
- `app/src/main/java/com/example/app_clisa/ui/products/ExcelImportAction.kt`: retira consultas y navegación de conciliación/corte. Mantiene selección, comparación, incidencias, clasificación, vista previa y expediente.
- `ExportTimeSection.kt`, `InventoryPreviewScreen.kt`, `ImportFinalScreen.kt`: mismas tarjetas/estilo, con mensajes de Excel de referencia y ausencias conservadas; no se solicita fecha/hora ni evidencia de formalización para importar. Los botones de producción siguen deshabilitados.
- Se retiró `ui/products/TransferReconciliationScreen.kt` y sus pruebas de la pantalla retirada. La formalización de Lista de Registros y sus pruebas no se modificaron.
- `tools/inventory-sync/inventory.mjs`: construye el grupo atómico con las cantidades exactas del Excel. No usa historiales ni objetivos descontados. Guarda las ausencias conservadas en el checkpoint.
- `contract.mjs`, `preparation.mjs`, `cli.mjs`: validan la política nueva. Preparación automática desde Excel con `--prepare true`; un objetivo manual inferior o distinto al Excel se rechaza. Se conserva la aprobación administrativa firmada, no se exige una prueba externa de formalización.
- `engine.mjs`: conserva transacciones por producto, versiones nativas, SKU/series únicos, fencing, exclusión, idempotencia por archivo, checkpoints, auditoría, presupuesto y recuperación supervisada. Comprueba también series creadas concurrentemente bajo otro producto. No consulta el historial de registros.
- Se retiró el evaluador Node obsoleto `transfer-reconciliation.mjs` y sus pruebas de la fórmula anterior. Los modelos/lectores auxiliares Kotlin históricos quedan desacoplados del flujo; los expedientes y decisiones antiguos no se borran ni se interpretan como autorización vigente.

Las rutas Kotlin abreviadas anteriores pertenecen a `app/src/main/java/com/example/app_clisa/data/excel/`. No se cambió el diseño de otras pantallas, Registrar y Escanear, Detalle de producto, autenticación ni roles. No se modificó ninguna regla de Firestore.

## Ausencias, concurrencia e historial

Las ausencias son informativas: no autorizan `delete`, puesta a cero ni desactivación. El ejecutor rechaza cualquier operación `DELETE_EXISTENCE`, incluso si recibe una aprobación firmada. La selección del archivo no acredita completitud; las incidencias reales permanecen visibles y las cantidades ambiguas no se aplican.

Un job con ausencias puede terminar `REQUIRES_REVIEW` y liberar su bloqueo si **todas las operaciones de inventario autorizadas** se aplicaron y no hay exclusiones o conflictos bloqueantes. No exige aprobar eliminaciones ni declarar importación parcial solo por conservar ausencias. Errores, ejecución parcial real o cambios de alcance siguen reteniendo el bloqueo para recuperación supervisada. Un bloqueo vencido nunca se libera automáticamente.

Las verificaciones de concurrencia se basan en producto, existencias, conjunto de series y unicidad de SKU/series. Una SALIDA que cambie inventario después de la comparación invalida el baseline/versiones; no se ignora como si fuera un registro histórico. Cambiar solo pago o formalización no modifica el objetivo del Excel ni impide importar. El ejecutor no vuelve a descontar movimientos ni reescribe sus documentos.

El ZIP conserva los mismos `source.xlsx`, `contract.json` y `review.json`, incluidos el historial de decisiones y las aprobaciones de clasificación. La fuente se verifica por SHA-256 antes de exportar. Los jobs anteriores no se migran ni se sobrescriben; cada nueva ejecución conserva auditoría y checkpoints.

## Validación de esta corrección

- Compilación de APK y APK de pruebas: correcta.
- **283 pruebas JVM aprobadas, 0 fallos, 0 omitidas**, con el Excel real local. Se actualizaron las expectativas que imponían conciliación o inferían bajas.
- **123 pruebas Node aprobadas en Firebase Emulator, 0 fallos**. Incluyen: producto/serie nuevos desde XLSX real generado y leído por Kotlin; producto existente; stock igual a la suma del Excel; los tres estados históricos sin descuentos ni prueba de inclusión; duplicados; archivo incompleto; reimportación; concurrencia de inventario y series; recuperación tras error/respuesta perdida; integridad de registros, pagos y formalización; clasificación conservada.
- **28 pruebas de interfaz Android aprobadas**, en AVD API 35 con red desactivada. Incluyen importación con el Excel real, vista previa, ausencia de controles de conciliación, clasificación, revisión de incidencias y formalización de Lista de Registros.
- Verificación adicional de la CLI real: `--prepare true` produce la propuesta sin solicitar cantidades conciliadas. Evidencia en `correccion-cli.log`.
- Durante la adaptación se detectaron y corrigieron expectativas antiguas y una autorización de prueba que incluía indebidamente operaciones informativas; no se relajó la prohibición de escribir esas operaciones.
- Evidencia final en esta carpeta: `correccion-android.log`, `correccion-emulator.log`, `correccion-ui.log`. El Excel original no se modificó; mantiene SHA-256 `ce8c508680f8b547d71572def7fe127012d4e8191c8bc25bc7cbb69065c61a8c`.

Se actualizaron los tests `InventoryDryRunPlannerTest`, `InventoryComparatorTest`, `InventoryPreviewTest`, `ExportTemporalReviewTest`, `SyncExecutionContractTest`, `ImportFinalizationTest` y la llamada de regresión de `ProductClassificationTest`; los UI `ExportTimeSectionTest`, `InventoryPreviewScreenTest`, `ImportFinalScreenTest`; y los Node `engine.test.mjs`, `final-pipeline.test.mjs`. Los fixtures Kotlin de contratos/XLSX se regeneraron con la política nueva.

## Uso y bloqueos reales para producción

Flujo: **seleccionar Excel → leer/validar → comparar → revisar diferencias/incidencias y clasificación → resumen y expediente**. No existe el paso de conciliación de traspasos. Navegar por los resultados no vuelve a consultar inventario ni movimientos.

La preparación administrativa local admite `--contract <contract.json> --excel <source.xlsx> --prepare true --output <directorio>`. La ejecución sigue usando la aprobación firmada y clave pública ya existentes. **Todos estos comandos siguen limitados al proyecto demo y Emulator; no cambiar los hosts o el proyecto para usarlos con producción.**

Quedan estos requisitos para un despliegue posterior, no realizados en esta tarea:

1. Aprobar y configurar un ejecutor administrativo de producción autenticado, identidad/IAM mínima, firmante administrativo y canal seguro del expediente. No se alojó Node ni se incluyeron credenciales privilegiadas en Android.
2. Revisar y probar, con aprobación independiente, las reglas y el mecanismo de exclusión de escritores durante la importación. El protocolo y las reglas de Emulator ya se prueban, pero eso no acredita que estén desplegados en producción. No se desplegaron reglas ni índices.
3. Corregir o resolver de forma verificable las incidencias del Excel real. Sigue teniendo 567 incidencias, incluidas identidades sin SKU. Una decisión local no corrige los bytes ni convierte el archivo en completo. Se mantiene el aislamiento técnico firmado de grupos independientes cuando puede demostrarse; los alcances desconocidos siguen bloqueados.
4. Confirmar cuotas disponibles y tamaño de los cambios. La comparación y revalidación consumen lecturas; actualizar miles de series puede superar Spark. El ejecutor conserva presupuestos/reintentos acotados y rechaza ejecuciones que exceden el saldo acreditado. No se promete importar todo el archivo en un día ni se implementó fraccionamiento automático para eludir cuotas.
5. Conservar los límites de transporte y transacción: Excel 25 MB, contrato/revisión 8 MB, grupos por producto con máximo de escrituras/tamaño. Un grupo demasiado grande o un expediente incompleto se bloquea, no se trunca.

**No queda como requisito demostrar la inclusión de traspasos, certificar su formalización ni obtener un verificador externo de conciliación.** La política semanal es externa. Los requisitos anteriores son de integridad, autorización y despliegue.

---

# Informe histórico anterior — política de conciliación sustituida

El texto siguiente se conserva como historial técnico. Describe el comportamiento anterior; sus fórmulas, controles temporales, pantallas de conciliación y comandos con objetivos descontados **ya no corresponden a la implementación vigente**.

# Finalización del paso 4B — Importación Excel (histórico)

Fecha: 23 de octubre de 2026, America/Lima.

## Resultado: FUNCIONAL EN EMULATOR

El flujo de lectura, comparación, revisión, conciliación, exportación, preparación administrativa, validación, ejecución por producto y auditoría está integrado y probado con XLSX ficticios reales. **No está listo para solicitar la primera escritura en producción.** El ejecutor sigue cerrado a Emulator; faltan evidencia externa confiable y los requisitos operativos de producción indicados abajo.

No se hicieron escrituras ni consultas a inventario de producción, despliegues, cambios de stock real, pagos, formalizaciones, roles o reglas. El Excel original permanece intacto. No se añadieron etapas, IA ni una conexión administrativa dentro del APK.

## 1. Componentes integrados

Se reutilizaron el lector ZIP/SAX, `InventoryComparator`, vista previa, `InventoryDryRunPlanner`, revisión persistente de incidencias, `TransferReconciler`, contrato Kotlin/Node, ejecutor, bloqueo global/fencing, presupuesto, idempotencia, checkpoints y auditoría.

La revisión incluyó los informes 4A, 4B.1, 4B.2A, 4B.2B.1 y 4B.2B.2 y sus implementaciones. Se corrigieron dos desconexiones:

1. Android tenía un contrato exportable por API, pero no una transferencia utilizable desde la interfaz. Ahora exporta un expediente ZIP con fuente, contrato y decisiones.
2. Node comprobaba conciliación, pero construía las operaciones esperadas usando cantidades oficiales sin aplicar el objetivo conciliado. Ahora primero verifica la conciliación y construye las operaciones esperadas a partir de sus objetivos por serie.

La lógica definitiva sigue siendo **Excel − salidas demostradas no incluidas**. No se toma PENDIENTE/COMPLETADO como prueba de inclusión, ni se convierte SIN_VERIFICAR en un estado conocido.

## 2. Archivos modificados

Nuevos:

- `app/src/main/java/com/example/app_clisa/data/excel/ImportFinalization.kt`: resumen final y empaquetado de solo lectura con comprobación del SHA.
- `app/src/main/java/com/example/app_clisa/ui/products/ImportFinalScreen.kt`: resumen, exportación SAF e informe informativo de Emulator.
- `tools/inventory-sync/preparation.mjs`: aislamiento técnico de incidencias locales y compilación de propuesta administrativa por SKU/serie.
- `app/src/test/java/com/example/app_clisa/ImportFinalizationTest.kt`: lector real → comparación → contrato → paquete y fixtures del recorrido completo.
- `app/src/androidTest/java/com/example/app_clisa/ImportFinalScreenTest.kt`: resumen sin autorización ficticia ni botón de escritura habilitado.
- `tools/inventory-sync/test/final-pipeline.test.mjs`: pruebas completas, incluyendo la CLI real.

Modificados:

- `ExcelImportAction.kt`: conecta resumen y URI del archivo seleccionado; retiene resultados de conciliación del plan actual.
- `TransferReconciliationScreen.kt`: comunica el contexto analizado al resumen, sin consultas adicionales.
- `SyncExecutionContract.kt`: incluye la estructura realmente reconocida del archivo para evaluar aislamiento técnico.
- `tools/inventory-sync/contract.mjs`: valida alcance parcial firmado y comprobado, conservando bloqueos globales desconocidos.
- `tools/inventory-sync/inventory.mjs`: genera expectativas con stock/series conciliados y vuelve a verificar antes de ejecutar.
- `tools/inventory-sync/cli.mjs`: prepara propuestas sin escribir e informa resultados de simulación ligados a fuente/plan.
- `tools/inventory-sync/test/engine.test.mjs`: actualiza dos expectativas a los rechazos más específicos; no elimina controles.
- `security/paso4b1/Test-Emulator.ps1`: admite seleccionar pruebas afectadas mediante `-TestCommand`, conservando el comando anterior como predeterminado.

Las rutas Kotlin anteriores corresponden a `app/src/main/java/com/example/app_clisa/`. No se modificaron Gestión de Productos, Detalle de producto, Lista de Registros, SALIDA ni formalización.

## 3. Flujo completo desde Android

1. Seleccionar el XLSX, leer y validar con el lector existente.
2. Comparar explícitamente contra Firestore mediante las consultas existentes.
3. Revisar diferencias e incidencias; las decisiones permanecen vinculadas al SHA y no alteran el archivo.
4. Revisar conciliación por producto; las consultas de movimientos siguen siendo explícitas y acotadas.
5. Abrir **Resumen final y expediente**.
6. Consultar productos analizados, operaciones verificadas/bloqueadas/sin cambios, motivos, estado de conciliación y estimaciones de lectura/escritura.
7. Con ADMIN/SUPER_ADMIN activo, exportar el expediente usando el selector Android.
8. Un operador extrae y revisa el expediente, prepara una propuesta, obtiene una aprobación independiente y ejecuta exclusivamente en Emulator.
9. Cargar `simulation-summary.json` desde el resumen para consultar el resultado simulado.

La interfaz conserva el marco azul marino y las tarjetas existentes, sin logo interno. No consulta de nuevo inventario al navegar por el resumen. Las estimaciones son escenarios potenciales, no saldo Spark ni operaciones autorizadas. Sin verificador externo conectado, las operaciones seguras acreditadas en Android permanecen en cero.

El informe cargado es **informativo y no autenticado**. Comprueba esquema, entorno Emulator, SHA y plan de origen, pero no se usa como prueba de conciliación ni habilita escrituras. Modificar un JSON no concede permisos.

`COMPLETED` se refiere exclusivamente al alcance aprobado de ese job, no a la completitud del Excel ni a todos sus productos. En una importación parcial pueden quedar productos excluidos e incidencias en el archivo; se conservan en el expediente original y no se marcan resueltos por finalizar el job.

## 4. Expediente y comunicación administrativa

El ZIP contiene:

| Archivo | Contenido |
|---|---|
| `source.xlsx` | Copia idéntica en bytes del Excel seleccionado. |
| `contract.json` | Fuente, errores, estructura, comparación, IDs reales, Dry Run y bloqueos originales. |
| `review.json` | Decisiones persistidas de incidencias y declaraciones de movimientos; `authorization=false`. |

Antes de exportar se revalida el administrador y el hash de los bytes leídos. Se rechaza usar el documento original como destino. El empaquetado no corrige el XLSX ni modifica el original. Si cambia el archivo, se exige volver a leer/comparar.

Se mantienen límites explícitos: XLSX de 25 MB; contrato y revisión de hasta 8 MB cada uno. No se truncan documentos para caber. Un expediente que exceda estos límites queda bloqueado y requiere una solución de transporte acotada antes de utilizarlo en producción; no se levantó el límite de memoria existente sin probarlo.

El transporte es manual. No hay servidor HTTP administrativo, claves privadas ni Admin SDK en Android. Tampoco hay un firmante automático que convierta declaraciones locales en autorización.

## 5. Preparación de operaciones y evidencia

`prepareAdministrativeContract` verifica esquema, IDs del plan Android y fingerprint del baseline, preserva toda la fuente original y genera una **propuesta**, con `AWAITING_SIGNED_APPROVAL` y `productionExecutable=false`.

Recibe objetivos por producto/serie ya revisados. No acepta nuevas series fuera de la fuente, objetivos superiores al stock oficial, sumas incoherentes ni desapariciones de series existentes. Conserva los IDs reales cuando SKU y documentId difieren. Crear productos y existencias utiliza la política de IDs existente, comprobando colisiones.

La propuesta no es autoridad: el ejecutor recalcula los objetivos desde evidencia independiente firmada y rechaza cualquier diferencia. No se debe elaborar `targets.json` basándose únicamente en diferencias numéricas o estados de formalización.

Operaciones que se pueden proponer y ejecutar con evidencia válida en Emulator:

- Crear productos válidos y existencias válidas.
- Actualizar cantidades/estados de existencias verificadas.
- Actualizar `stock` con el total conciliado.
- Omitir documentos sin cambios, con cero escrituras.

No se modifica `cantidadRestante`. Registros históricos, pago y formalización no son destinos de ninguna de estas operaciones.

Permanecen bloqueados: eliminaciones, identidades ambiguas, series duplicadas relacionadas, cantidades negativas relacionadas, historial sin conciliar, desconocimiento del alcance, evidencia alterada, versiones cambiadas, plan obsoleto, cuotas insuficientes y usuarios no autorizados. Los cerrojos y fencing anteriores no se sustituyeron.

## 6. Incidencias independientes

No se exige resolver todos los errores conocidos de otros productos cuando puede probarse su independencia. Se implementó un alcance parcial **sin inferencias de ausencia**:

- Los productos seleccionados deben tener SKU único, cantidades válidas y series únicas.
- Se comprueba que ninguna serie de otros grupos, incluso tras recortar espacios, colisione con las seleccionadas.
- Solo se aíslan incidencias locales identificables de SKU/series duplicados, espacios o cantidades negativas, pertenecientes a otros productos.
- Se exige estructura XLSX reconocida, encabezados completos y cero filas/preambulos no interpretados.
- La autorización debe incluir un certificado independiente firmado, ligado al hash de toda la fuente, todas las incidencias y los SKU seleccionados. Su ausencia o alteración se rechaza.

No se declara completo el Excel: conserva su estado INCOMPLETE y todos sus errores. MISSING_SKU, diagnósticos truncados y filas de alcance desconocido no se convierten en locales automáticamente. El archivo real conserva sus 567 incidencias; las decisiones registradas no corrigen sus bytes ni certifican la fuente.

La prueba completa demuestra que un producto A válido se actualiza mientras B conserva sus cantidades negativas sin importar B. También demuestra que un archivo con un grupo sin SKU queda bloqueado. La independencia no se presume para las 346 identidades ausentes del archivo real.

## 7. Procedimiento administrativo probado

**Los comandos siguientes operan únicamente sobre Emulator. No cambies el proyecto a empresa-clisa.** Ejecutar fuera de los endpoints demo/locales se rechaza.

Desde la raíz del proyecto, con los emuladores ya iniciados con la configuración de `security/paso4b1/firebase.json`:

```powershell
$env:GCLOUD_PROJECT = 'demo-clisa-sync-4b1'
$env:FIRESTORE_EMULATOR_HOST = '127.0.0.1:8086'
$env:FIREBASE_AUTH_EMULATOR_HOST = '127.0.0.1:9098'

# Paquete extraído y objetivos revisados con evidencia ficticia independiente.
node tools/inventory-sync/cli.mjs `
  --contract expediente/contract.json `
  --excel expediente/source.xlsx `
  --targets objetivos-revisados.json `
  --output propuesta
```

`objetivos-revisados.json` es un mapa por SKU con `stockConciliado` y `serialTargets`. Sus valores no se inventan: la prueba de Emulator los obtiene del caso ficticio y de sus comprobantes. El comando comprueba el hash del archivo y genera `propuesta/proposal.json`; **no escribe inventario ni concede aprobación**.

El operador/autoridad debe revisar la propuesta completa y firmar una aprobación para ese contrato exacto, su solicitante, presupuesto, alcance, versiones y evidencia de inclusión/exclusión. El protocolo de firma Ed25519 y las atestaciones de conciliación v2 existentes se reutilizan. Actualmente solo hay una autoridad ficticia en las pruebas; no se entregó un firmante de producción ni se exportaron sus claves.

Después de obtener esa aprobación ficticia y su clave pública:

```powershell
node tools/inventory-sync/cli.mjs `
  --contract propuesta/proposal.json `
  --excel expediente/source.xlsx `
  --approval aprobacion-firmada.json `
  --public-key autoridad-publica.pem `
  --output resultado `
  --run-id run_importacion_revisada
```

El operador debe corresponder a un ADMIN/SUPER_ADMIN activo en Auth/Profile Emulator. El ejecutor valida firma, SHA, plan, evidencia, cuotas, cerrojo, fencing y versiones; aplica cada producto atómicamente, escribe auditoría y preserva registros. No hay atomicidad prometida del Excel entero.

Archivos de resultado: `request.json`, `result.json` o `error.json` y `simulation-summary.json`. Este último incluye SHA, plan administrativo, plan Android de origen, runId, estado y grupos aplicados. Se puede cargar en Android como información.

Ante fallo, no se libera a ciegas el cerrojo. La recuperación requiere supervisión explícita, la misma fuente/contrato/alcance/aprobación válidos y los controles existentes:

```powershell
node tools/inventory-sync/cli.mjs `
  --contract propuesta/proposal.json --excel expediente/source.xlsx `
  --approval aprobacion-firmada.json --public-key autoridad-publica.pem `
  --output recuperacion --recover run_importacion_revisada --supervised true
```

No se debe reemplazar una aprobación caducada o cambiar cantidades/alcance sin la revisión exigida por el motor. Los límites de recuperación previos se conservan.

## 8. Pruebas y resultados

No se repitieron suites ajenas a los cambios. Evidencias:

- `android-build.log`: compilación debug/APK de tests correcta y **87 pruebas JVM afectadas aprobadas, cero omitidas**. Incluye lector del Excel real, planificador, contrato, incidencias, conciliación y empaquetado.
- `ui-tests.log`: **9 pruebas Compose aprobadas** del resumen final, importación y conciliación, en emulador sin red y sin cuentas de producción.
- `node-emulator-tests.log`: **64 regresiones del ejecutor aprobadas**, más la primera pasada del recorrido final. Incluye SALIDA cliente concurrente realmente denegada por reglas, fencing, recuperación, usuarios, presupuesto y auditoría.
- `final-pipeline-tests.log`: **15 pruebas finales completas aprobadas**, incluyendo la CLI como proceso real, fuente XLSX y contrato emitidos por Kotlin, y certificado de aislamiento ausente/alterado.

Son **79 casos Node/Emulator distintos verificados**: 64 regresiones y 15 del flujo final. El log de la primera pasada conserva un fallo de fixture: al alterar un certificado se alteraba accidentalmente el contrato por compartir una referencia de objeto, disparando antes PLAN_TAMPERED. Se separaron los objetos y se repitieron únicamente los casos finales; no se debilitó ninguna validación.

Las fixtures `fixtures/*.xlsx` son libros ficticios generados por tests; se procesan con `XlsxInventoryReader`, comparador y Dry Run reales. Node no reconstruye ni sustituye sus filas por otra fuente para aprobarlas. Las firmas y comprobantes son exclusivamente ficticios y no certifican el exportador real.

| Escenario solicitado | Comprobación |
|---|---|
| Producto sin cambios | Sin job ni escrituras; versión intacta. |
| Actualización válida | Stock/serie actualizados juntos y dos entradas de auditoría. |
| Traspaso pendiente no reflejado | Excel 10, salida 2, objetivo 8; no restaura 10. |
| Traspaso completado reflejado | Objetivo 8; no descuenta otra vez y conserva el registro. |
| Movimiento ambiguo | Rechazo antes de escrituras de inventario. |
| Serie duplicada | Rechazo durante preparación. |
| Archivo incompleto | Alcance desconocido bloqueado; no se declara completo por decisiones. |
| Fallo y reintento | Transacción fallida sin cambios, recuperación supervisada, replay sin duplicar auditoría. |
| SALIDA concurrente | Reglas mantienen el cerrojo; cambios inesperados de historial invalidan la unidad. |
| Cuota insuficiente | Rechazo previo a ejecutar. |
| Usuario sin permisos | Rechazo en Auth/profile y editor sin autorización automática. |
| Incidencia local independiente | A se ejecuta, B negativo no se importa y la fuente sigue INCOMPLETE. |
| CLI y retorno a Android | Propuesta sin escrituras, ejecución con aprobación firmada ficticia y reporte enlazado al plan de origen. |

Para regenerar fixtures locales: configurar `CLISA_FINAL_FIXTURE_DIR` a la carpeta `docs/importacion-paso4b-final/fixtures`, `CLISA_EXCEL_SAMPLE` al XLSX real y ejecutar las clases JVM afectadas. Para Emulator se usó:

```powershell
security/paso4b1/Test-Emulator.ps1 -FirebaseCli '<ruta instalada de firebase-tools/lib/bin/firebase.js>' `
  -TestCommand 'node --test --test-concurrency=1 test/engine.test.mjs test/final-pipeline.test.mjs'
```

La prueba de CLI usa una clave efímera en memoria y archivos temporales ficticios; los elimina al terminar. Las escrituras de pruebas ocurrieron solo en el proyecto demo.

## 9. Bloqueos reales y primera autorización de producción

No se clasifica el resultado como LISTO PARA SOLICITAR AUTORIZACIÓN DE PRODUCCIÓN. Antes de ello faltan:

1. Una fuente de evidencia externa que certifique el alcance del Excel y la inclusión/exclusión de los movimientos. El archivo no trae historial ni fecha verificable; no se inventaron.
2. Acreditar el aislamiento de los productos que se pretendan importar, especialmente ante grupos sin SKU. Las 567 incidencias no desaparecen por tener anotaciones.
3. Operador, identidad privilegiada y autoridad de firma aprobados para producción; transporte y custodia de expedientes definidos. El runtime actual solo permite Emulator y no tiene worker de escritura para empresa-clisa.
4. Protocolo de concurrencia y reglas de cerrojo/fencing de producción revisados, mostrados y autorizados por separado antes de desplegar. No se desplegaron reglas antiguas ni nuevas.
5. Baseline y conciliación frescos, presupuesto real de operaciones y plan concreto revisados. Las estimaciones no miden el saldo Spark.
6. Verificar tamaño y recursos del expediente real dentro de los límites, recuperación operativa y una ejecución inicial de alcance reducido expresamente autorizada.

El escritor de XLSX corregido sigue bloqueado como se documentó anteriormente; esta integración transporta los bytes originales. No es necesario fingir un Excel completo para probar el flujo seguro con casos independientes.

**Confirmación: Excel original y datos reales intactos; cero escrituras de producción; eliminaciones, IA y ejecución real deshabilitadas.**
