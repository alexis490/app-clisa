# Paso 4A.1 — Resolución de bloqueos y validación de seguridad

Fecha: 22 de octubre de 2026. Alcance: validación y planificación local; **sin ejecutor de sincronización**.

## 1. Resultado y clasificación

| Bloqueo | Estado | Evidencia y límite |
|---|---|---|
| Excel completo | **PARCIALMENTE RESUELTO** | Seguimiento exhaustivo de filas y validación técnica implementados. Los tres estados están probados. No existe un manifiesto confiable del exportador real; formato correcto permanece UNKNOWN. El Excel real es INCOMPLETE por sus incidencias. |
| Stock y cantidadRestante | **PARCIALMENTE RESUELTO** | Las lecturas y escrituras Android están identificadas; cantidadRestante no se propone como escritura. Su significado de negocio sigue sin demostrarse. |
| Identificadores | **RESUELTO en el generador local** | IDs reales conservados; IDs nuevos deterministas, detección de colisiones/ambigüedad, referencias y reintentos probados. La ausencia/unicidad deberá revalidarse atómicamente antes de cualquier creación futura. No existe todavía un ejecutor autorizado. |
| Reglas de seguridad | **PARCIALMENTE RESUELTO** | Propuesta separada y 33 pruebas reales en Emulator aprobadas. Reproduce los fallos del ruleset auditado y comprueba la propuesta. La sincronización masiva exige otro componente y contrato; ninguna regla se desplegó. |

La concurrencia distribuida y recuperación automática de escrituras siguen **PENDIENTES de implementación en 4B**. Se probaron detección local de cambios y un prototipo aislado de cerrojo en Emulator; no se presentan como una solución ya instalada en producción.

## 2. Archivos y reutilización

Se revisaron los ocho archivos solicitados, el informe 4A, su revisión de seguridad y la auditoría real. Se conserva el comparador existente, la lectura paginada, la vista previa y el coordinador de interfaz. No se reemplazó el lector XLSX.

Código modificado, bajo `app/src/main/java/com/example/app_clisa/data/excel/`:

- `ExcelInventory.kt`: añade `ExcelStructure` y contadores de clasificación de filas sin cambiar la interpretación ni las incidencias existentes.
- `XlsxInventoryReader.kt`: confirma que terminó la validación del contenedor XLSX antes de marcar ese dato estructural.
- **Nuevo `ExcelCompleteness.kt`**: validación técnica, tres estados y contrato opcional de manifiesto firmado, sin autoridad configurada por defecto.
- **Nuevo `InventoryIdPolicy.kt`**: resolución exacta de identidades sobre la lectura retenida; índices en memoria, sin consultas.
- `SyncPlan.kt`: evidencia técnica vinculada a la huella del archivo.
- `InventoryDryRunPlanner.kt`: usa identidades deterministas y evidencia, mantiene los bloqueos y versiona el plan a esquema 2.
- `SyncPlanIdentity.kt`: exige versión observada a documentos existentes, no a creaciones aún inexistentes.
- `SyncRecoveryReview.kt`: un comprobante posterior vacío tampoco acredita una operación aplicada.

Pruebas: nuevo `InventorySafety4a1Test.kt`; actualizados `InventoryDryRunPlannerTest.kt` y `XlsxInventoryReaderTest.kt`, bajo `app/src/test/java/com/example/app_clisa/`.

Seguridad local:

- `security/paso4a1/firestore.proposed.rules`: propuesta completa, **no copiar/desplegar sin revisar las diferencias de permisos de la sección 6**.
- `security/paso4a1/firebase.json`: configuración exclusivamente local, no configuración de despliegue en la raíz.
- `security/paso4a1/emulator/`: pruebas Node, lanzador PowerShell, dependencias fijadas y lockfile; `node_modules` y logs de depuración ignorados.

No se modificaron `InventoryComparison.kt`, `FirestoreInventoryReader.kt`, `InventoryPreview.kt`, `ExcelImportAction.kt`, Gestión de Productos, Detalle de producto ni el diseño de Importar Excel. El generador sigue siendo una API local de planificación; no se añadió una pantalla ni un nuevo recorrido de consultas. El botón de sincronización permanece deshabilitado.

## 3. Completitud del Excel

### Comprobaciones técnicas

Se verifican las cuatro columnas por hoja, contenedor XLSX validado, encabezados de grupos, códigos exactos no vacíos, series por grupo, hojas/filas de pertenencia, duplicados globales de SKU y series, cantidades enteras no negativas, suma sin desbordamiento, recuento declarado y cantidad de resumen frente al detalle. Se conserva cada ocurrencia; no se fusionan grupos ni se elige un duplicado.

Se contabilizan por separado filas no vacías, encabezados de columna, encabezados de producto, series, resúmenes independientes, filas no interpretadas y contenido anterior a la tabla. Una fila desconocida no se convierte en una existencia ni desaparece de la contabilidad. Los resúmenes se excluyen de la suma de series. El lector existente sigue validando las cantidades de resúmenes independientes; esos totales generales no se usan como prueba de alcance.

`INCOMPLETE`: incidencias del lector, columnas faltantes, filas no interpretadas, identidades/cantidades inválidas, incoherencias de grupos o contabilidad.

`UNKNOWN`: estructura válida sin evidencia externa suficiente; también ausencia de trazabilidad estructural o contenido previo sin clasificar. Una declaración del operador, muchas filas o una alta coincidencia con Firestore no cambian esto.

`COMPLETE_VERIFIED`: estructura completamente válida **más** evidencia de exportación confiable vinculada al archivo, alcance, ausencia de filtros, fecha y totales de control. El contrato implementado permite un manifiesto firmado SHA256withRSA por una clave pública de confianza. Se comprueban firma, clave autorizada, SHA-256, alcance, antigüedad configurada, grupos, series y cantidad total. Una firma alterada, otro archivo o una exportación filtrada no certifican completitud.

**No hay claves confiables ni integración de manifiestos configuradas en la app.** Las claves de las pruebas se generan temporalmente y son ficticias. El sistema de ventas tendría que producir el manifiesto del proceso completo de exportación y custodiar su clave privada fuera de Android. La firma prueba la afirmación del exportador confiable; no descubre por sí misma errores o filtros ocultos en ese sistema. Otra evidencia autoritativa podría incorporarse posteriormente tras definir su contrato.

La evaluación y la política suministradas a estas funciones puras no son una autorización de seguridad: un futuro componente de confianza deberá repetir su validación con sus propias claves, reloj y archivo original. No basta con recibir un booleano desde Android.

Para UNKNOWN/INCOMPLETE se mantiene `FULL_REPORT_UNPROVEN` en las bajas. Incluso con un manifiesto ficticio válido el plan sigue sin ser ejecutable: no se eliminan los bloqueos de reglas, referencias históricas ni autorización de 4B. `productionExecutable` y `destructiveEligible` siempre devuelven false.

### Archivo real

Se leyó nuevamente el archivo local `stock.production.lot123 - 2000.xlsx`, sin acceder a Firestore: **1 814 grupos, 22 762 filas de serie, 567 incidencias**. Resultado: **INCOMPLETE**. No se corrigieron ni descartaron silenciosamente sus grupos sin SKU, duplicados o cantidades inválidas.

La prueba del Dry Run usa ese Excel frente a un **inventario vacío ficticio**, no frente a producción. Sus 21 196 propuestas y 43 833 escrituras hipotéticas incluyendo auditoría son una prueba de estimación/bloqueo, **no el resultado de comparar la base real**. Véase [validacion-excel-real.txt](validacion-excel-real.txt).

## 4. Stock: qué demuestra el código

| Campo | Uso observado | Escritura existente | Política propuesta |
|---|---|---|---|
| `productos.stock` | Disponibilidad agregada utilizada por catálogo, filtros, contadores, detalle y validación de salidas | SALIDA descuenta la cantidad en la transacción | Proponer la suma de cantidades de series válidas del mismo producto, solo con origen aceptado, sin conflictos y coherencia atómica |
| `existencias.cantidad` | Disponibilidad de esa serie; una serie puede tener cantidad distinta de 1 | SALIDA descuenta cantidad y cambia estado a DISPONIBLE/SIN_STOCK | Comparación y propuesta individual por serie, sin reemplazar identidades |
| `productos.cantidadRestante` | Dato observado en comparación/vista previa y contraste diagnóstico | No se encontró escritor Android de este campo | Conservarlo; discrepancia requiere revisión, no es un alias demostrado de stock |

Inventario de usos relevantes:

- `data/ProductCatalog.kt:46-47,91-94,135,140`: consultas de stock, agregaciones, disponibilidad de códigos y modelo de catálogo.
- `ui/products/CategoryManagement.kt:190-191` y `ui/products/ProductsScreen.kt:203,247-250`: presentación de filtros/cantidades/estado. Detalle no modificado.
- `ui/registration/RegisterScreen.kt:129`, `data/PendingExit.kt:19,35` y `data/StockDeduction.kt:6-7`: reservas y validación de cantidad disponible.
- `data/FirestoreInventory.kt:78-86,116,130-131`: lee disponibilidad, calcula y escribe stock/existencia. `save` crea registro y actualiza ambos documentos atómicamente; **no actualiza cantidadRestante**.
- `data/excel/FirestoreInventoryReader.kt:38-41`: lee los tres campos diferenciados.
- `data/excel/InventoryComparison.kt:145-156,172`: suma series, compara stock y cantidadRestante, detecta diferencias, incluso con stock total igual y series distintas.
- `data/excel/InventoryPreview.kt` y `ui/products/InventoryPreviewScreen.kt`: muestran los tres valores por separado.
- `data/excel/InventoryDryRunPlanner.kt`: observa cantidadRestante pero nunca lo incluye en campos esperados de una mutación.
- `ui/records/RecordsScreen.kt:91` y `data/ReportRepository.kt:27`: leen la cantidad histórica del movimiento; **no son contadores de inventario**.

No se encontró un segundo escritor Android de stock ni un ejecutor de AJUSTE. AJUSTE existe como contrato de reglas, no como nueva función implementada aquí. Las sumas del comparador y la validación transaccional son cálculos con fines diferentes; no deben tratarse como escrituras duplicadas.

La auditoría documentó un producto con stock=0, cantidadRestante=1 y existencia=0 tras SALIDA. Esto confirma la divergencia; no demuestra si cantidadRestante significa saldo inicial, dato heredado u otra regla de negocio. Cambiarlo para igualar stock ocultaría información cuyo significado no está definido. Cambiar stock sin las series desalinearía filtros/salidas; cambiar solo las series dejaría disponibilidad agregada incorrecta.

**Decisiones pendientes:** significado de los contadores heredados y fecha de corte del Excel. Un archivo anterior a un traspaso no debe reponerlo automáticamente. Se necesita un corte autoritativo y reconciliación con movimientos posteriores; si no puede demostrarse, bloquear ese plan, no borrar ni reescribir el historial. Tampoco se asignan automáticamente categorías, marcas ni metadatos a productos nuevos.

## 5. Identificadores y reintentos

1. Se construyen índices en memoria sobre la instantánea completa retenida. Se busca por SKU exacto, no por nombre ni por ID supuesto.
2. Si hay una sola identidad coherente, se conserva **su ID real**, tanto para productos como existencias. `SFYRS/1000G` puede continuar apuntando a `productos/SFYRS_1000G`.
3. Solo para una identidad no existente se propone `p_v1_<sha256>`; el hash incluye dominio/versionado y SKU exacto codificado con tipos y longitudes. No reemplaza `/` por `_`, no cambia mayúsculas, normaliza acentos ni elimina ceros iniciales.
4. La existencia nueva propone `e_v1_<sha256>` sobre otro dominio más **ID real/propuesto del padre y serie exacta**. `productoId` usa ese ID; `codigoProducto` conserva el SKU comercial.
5. Se comprueba que el ID propuesto no esté ocupado por otro documento en la instantánea. SKU repetido, serie global repetida, ID duplicado/inválido, serie de otro producto o datos sin identificar bloquean la operación. Se conservan referencias de los conflictos; nunca se toma la primera coincidencia.
6. Repetir la generación conserva IDs. Cuando el documento equivalente aparece en una lectura posterior se reconoce como existente, no se crea otro. Una comparación inconsistente que marque una identidad existente como nueva queda bloqueada.

Se probaron `/` frente a `_`, separadores, Unicode/caso, límites entre componentes, padres distintos, IDs ocupados artificialmente, reintentos, duplicados y referencias a IDs heredados. La colisión criptográfica no se declara matemáticamente imposible: se valida ocupación de ruta e identidad. Los IDs nuevos usan un espacio versionado y tamaño acotado.

Esta resolución es respecto de los datos comparados. **No elimina la carrera posterior**: el futuro worker deberá comprobar ausencia/identidad bajo el cerrojo común y usar precondiciones de creación/existencia. Un cliente que escriba con IDs arbitrarios no queda coordinado solo por usar hashes.

## 6. Reglas propuestas y diferencias deliberadas

Archivo separado: [firestore.proposed.rules](../../security/paso4a1/firestore.proposed.rules). La copia auditada de octubre 21 permanece intacta.

- SALIDA: `estadoPago` figura tanto en obligatorios como permitidos. AJUSTE manual usa una lista consistente; no exige recibidoPor/estadoPago para luego prohibirlos.
- Perfil ACTIVO, rol coincidente con el token, claims administrativos coherentes; perfiles bloqueados o claims contradictorios no habilitan inventario. El perfil propio puede leerse para resolver el acceso; una cuenta nueva solo puede crearse como USER activo, sin sobrescribir perfiles.
- USER puede registrar una **SALIDA válida atómica**; no puede editar arbitrariamente stock, cantidades, metadatos, crear ni borrar inventario. Esta distinción conserva Registrar y Escanear. Interpretar literalmente «USER no modifica inventario» como prohibir también SALIDA rompería el flujo autorizado; se probaron ambas rutas por separado.
- ADMIN/SUPER_ADMIN: solo edición descriptiva limitada a nombre/estado/timestamp y AJUSTE **MANUAL** atómico sobre documentos existentes, además de SALIDA. No es permiso general de escritura. CantidadRestante y referencias no pueden cambiar por esas rutas.
- Cada movimiento exige códigos/IDs coherentes, cantidad entera positiva, saldo no negativo, identidad/nombre/correo del actor, timestamp de servidor y cambios de producto/existencia vinculados al mismo registro. SALIDA conserva CLISA → TOQEN. Un registro viejo no autoriza un nuevo descuento: debe ser una creación nueva en el mismo commit.
- Historial inmutable. Las operaciones incompletas —solo registro o solo una parte del inventario— fallan.
- Solo SUPER_ADMIN puede listar usuarios y bloquear/activar **otras** cuentas USER/ADMIN. Ningún cliente cambia roles, su propia cuenta ni una cuenta SUPER_ADMIN. Asignar roles requiere servicio administrativo con sincronización Auth/perfil, fuera de esta etapa.

**Restricciones más fuertes que el ruleset auditado, para aprobación previa:**

- Crear productos/existencias desde el cliente queda denegado para todos los roles. Las reglas no pueden consultar libremente toda la colección y demostrar unicidad global sobre IDs heredados arbitrarios.
- No se permite que un cliente fabrique un resumen COMPLETADO ni ajustes que declaren origen IMPORTACION. Importaciones y su auditoría serán responsabilidad del componente de confianza aprobado.
- Tiendas quedan de solo lectura en esta propuesta; se retira la escritura genérica SUPER_ADMIN sin esquema acotado. No se modificó esa autorización en producción.
- Se propone reservar `importaciones/_control_clisa` como cerrojo global de inventario, sin crear una colección nueva. Solo el futuro operador de confianza podría gestionarlo. Mientras exista, incluso caducado, se deniegan movimientos y edición de inventario; **no se borra automáticamente por el reloj del cliente**. Se necesita una recuperación supervisada del cerrojo.

No son reglas listas para una importación masiva desde Android. Rules no puede garantizar el conjunto completo exportado, la unicidad histórica global ni un commit atómico de miles de documentos. Los fixtures del Emulator con reglas deshabilitadas simulan **solo** el operador futuro; ese privilegio no existe en Android.

Límite adicional: el esquema SALIDA conserva `recibidoPor` como nombre, sin receptorId. Estas reglas validan que sea texto no vacío; no pueden demostrar con ese nombre la identidad/estado del documento receptor sin una referencia inequívoca. Android conserva su comprobación actual de receptor TOQEN. Reforzar ese vínculo exige un contrato aprobado; no se inventó un campo ni se modificó el registro actual.

## 7. Pruebas reales y reproducción

Resultados de la ejecución final:

- `:app:assembleDebug`: **BUILD SUCCESSFUL**.
- `:app:testDebugUnitTest`: **177 pruebas, 0 fallos, 0 errores, 0 omitidas**; incluye 20 pruebas nuevas de seguridad 4A.1 y regresiones existentes de comparación, vista previa, recuperación y cuotas.
- Firestore Emulator **1.22.0**, Firebase CLI **15.34.0**, Node **22.20.0**, Java **21**; `@firebase/rules-unit-testing` **5.0.0**, SDK Firebase JS **12.4.0** fijados por lockfile: **33 pruebas, 33 aprobadas, 0 fallos/omitidas**.
- Proyecto ficticio **demo-clisa-4a1**, endpoint **127.0.0.1:8087**. El test aborta si endpoint/proyecto no coinciden. No usa datos exportados de usuarios reales ni credenciales administrativas.
- Prueba de regresión carga la copia congelada auditada solo en Emulator: SALIDA y AJUSTE válidos son rechazados con sus esquemas contradictorios. La propuesta permite los dos esquemas válidos en las pruebas positivas.
- Prototipo de dos solicitudes simultáneas al cerrojo: una sola obtiene propiedad; una escritura con fencing anterior se rechaza por el prototipo transaccional. No es un worker de producción.

Evidencia: [test-results.json](test-results.json), [emulator-results.txt](emulator-results.txt) y [validacion-excel-real.txt](validacion-excel-real.txt). Los PERMISSION_DENIED del log son esperados en pruebas negativas y se comprueban con assertFails; no representan fallos de las 33 pruebas. Las pruebas no cubren todas las combinaciones posibles ni certifican el proyecto desplegado.

Desde PowerShell en la raíz del proyecto:

```powershell
$env:JAVA_HOME = 'C:\Program Files\Android\Android Studio1\jbr'
$env:CLISA_EXCEL_SAMPLE = 'C:\Users\TUF\Documents\stock.production.lot123 - 2000.xlsx'
.\gradlew.bat :app:assembleDebug :app:testDebugUnitTest --no-configuration-cache
```

Para reglas, con Firebase CLI instalado y Node >=20/Java21 disponibles:

```powershell
Set-Location .\security\paso4a1\emulator
npm.cmd ci --ignore-scripts --no-audit --no-fund
.\Test-LocalRules.ps1 -FirebaseCli 'firebase.cmd' -JavaHome 'C:\Program Files\Android\Android Studio1\jbr'
```

También admite `-FirebaseCli '<ruta absoluta al firebase.js instalado>'`. El lanzador fija demo-clisa-4a1 y la configuración local, restaura las variables de entorno y detiene la ejecución si fallan las pruebas. No contiene comandos de despliegue. Una primera ejecución puede descargar el JAR del emulador. No hay nuevas pruebas instrumentadas ni cambios visuales; se ejecutó la suite local existente completa.

## 8. Concurrencia: garantías y trabajo de 4B

`SyncPlanFreshness` compara la huella del archivo y el conjunto completo retenido. Las pruebas detectan cambios de stock/cantidad/versión, existencia eliminada, existencia nueva, archivo distinto, lectura incompleta, plan caducado y retroceso del reloj. Los IDs nuevos no tienen versión previa; requieren futura precondición de ausencia. Una antigüedad aceptable **no sustituye** la comparación de datos.

Limitaciones reales: la lectura paginada del paso 2 no es una instantánea global atómica; el fingerprint abarca los campos de los modelos actuales. `actualizadoEn` es una versión de aplicación, no el updateTime nativo del servidor. Un snapshot suministrado al validador no demuestra que provenga de una lectura reciente y confiable. Releer y luego escribir sin coordinación todavía deja una carrera.

Arquitectura propuesta para 4B, sin implementarla:

1. Herramienta de operador en equipo de confianza o servicio privado con identidad IAM mínima, jamás claves en Android. Autorizar al operador, verificar estado/rol, archivo original, firma/alcance y política en ese componente; el Admin SDK omite Rules y **debe aplicar sus propias validaciones**.
2. Reservar atómicamente clave de replay y cerrojo de alcance con propietario, job, revisión/fencing y estado. El mismo archivo/alcance/política no obtiene dos jobs. Todos los trabajadores privilegiados deben respetar el protocolo. Las reglas propuestas coordinan los escritores cliente al comprobar existencia del cerrojo; hoy no están desplegadas.
3. Después de adquirir el cerrojo, volver a leer/confrontar el inventario completo; rechazar un plan distinto, caduco o un corte incompatible. Esto detecta también inserciones/eliminaciones ajenas a los objetivos conocidos. No basta releer solo las rutas que se van a cambiar.
4. En cada unidad transaccional: verificar propietario/fencing/job, versiones nativas/valores anteriores, ausencia de creaciones, referencias y unicidad; actualizar conjuntamente producto/series del grupo, journal y checkpoint. Reservar claves de identidad aprobadas o validar bajo exclusión de **todos** los escritores; no añadir otra colección sin autorización.
5. Si el producto supera los límites de una unidad atómica, bloquear/replantear el grupo. No dividirlo a ciegas dejando stock y series inconsistentes. El archivo entero no será atómico por encadenar lotes.
6. Reconcilación final de resultados antes de COMPLETED y liberar el cerrojo solo con estado/auditoría coherentes. Ante fallo, dejar PARTIAL y revisar. Expirar una hora no permite que un worker antiguo siga escribiendo; el fencing debe verificarse en cada commit, junto al resto de cambios.

Una herramienta local de operador puede utilizar Firestore Spark sin desplegar Functions, con su propio costo operativo y las cuotas del proyecto. Un servicio externo requiere infraestructura segura propia. Desplegar Cloud Functions for Firebase requiere Blaze ([documentación oficial](https://firebase.google.com/docs/functions/get-started)); no se habilitó facturación ni se instaló ese backend.

## 9. Recuperación, presupuesto y límites

El código existente distingue planId, operationId y sourceReplayKey. Se mantienen reintentos deterministas, detección de journal ajeno/duplicado/desconocido y ejecución parcial. APPLIED sin comprobante posterior no se considera confirmado, incluyendo ahora un texto vacío. Un journal suministrado localmente no es prueba de commit del servidor; el futuro worker deberá leer/verificar su journal confiable y precondiciones.

Una operación ya aplicada no se vuelve a ejecutar a ciegas. Si el servidor confirmó pero se perdió la respuesta, el journal/checkpoint en el mismo commit debe resolver el resultado incierto. Reanudar requiere reconciliar; nunca restaurar un before sobre traspasos posteriores. Compensaciones futuras deben ser nuevos movimientos auditados.

Las propuestas de bajas siguen bloqueadas. Su before parcial del Dry Run no es un respaldo completo restaurable: harían falta imagen completa, referencias históricas, versiones y política de recuperación aprobada antes de habilitarlas.

El Dry Run añade **0 lecturas/0 escrituras/0 eliminaciones**: reutiliza la instantánea. Step2 mantiene páginas de 500 por ID, sin listeners. Con las cantidades **de la auditoría anterior**, la comparación costaría aproximadamente **24 561 documentos leídos**, más validaciones de perfil/reglas. Una revalidación completa consumiría otros 24 561: **49 122**, antes de lecturas de objetivos, actividad normal y reintentos. Una tercera lectura completa el mismo día ya superaría 50 000. No se midió el saldo diario actual ni se consultó de nuevo producción.

El estimador cuenta solo mutaciones propuestas, no SKIP_UNCHANGED. Separa escrituras, bajas y auditoría: inicio/final de job, comprobante por mutación y checkpoint por grupo. Incluye propuestas bloqueadas en un escenario hipotético superior; no es un presupuesto ya autorizado ni conoce el saldo diario. La prueba con inventario vacío ficticio supera el presupuesto de escrituras y queda bloqueada.

Spark ofrece 50 000 lecturas, 20 000 escrituras y 20 000 eliminaciones diarias. Los límites relevantes incluyen solicitudes de 10 MiB, transacciones de 270 s (60 s de inactividad) y acceso de reglas de 10 documentos por operación/20 por transacción o lote. La cifra de 500 de la tabla oficial se refiere a transformaciones de campos **por documento**, no garantiza que 500 operaciones arbitrarias o un archivo entero quepan en un lote. Fuente: [cuotas oficiales](https://firebase.google.com/docs/firestore/quotas).

Para 4B se propone un techo operativo conservador de **400 escrituras por unidad**, incluyendo auditoría/checkpoints, menor si el SDK, tamaño serializado o reglas lo exigen. Es una política propuesta, no un límite certificado del servidor. No existe todavía empaquetador/ejecutor: debe probarse con el SDK elegido, grupos completos y tamaños reales; jamás despachar un grupo sobredimensionado. Debe reservarse presupuesto para trabajo normal, rechazar si se desconoce el saldo disponible y suspender ante RESOURCE_EXHAUSTED, sin bucles ciegos. Dividir en días obliga a revalidar corte, concurrencia y estado parcial; no vuelve seguro un Excel antiguo.

Los límites del emulador no certifican facturación, cuotas reales, índices de producción ni carga masiva. No se añadió ningún índice ni listener.

## 10. Decisiones que requieren autorización y riesgos pendientes

- Corregir incidencias del Excel en su sistema de origen y definir evidencia autoritativa de exportación completa, alcance y fecha de corte.
- Definir cantidadRestante/cantidadTotal/cantidadLotes y tratamiento de movimientos posteriores al corte; siguen conservados.
- Aprobar esquema mínimo y metadatos de productos nuevos, tratamiento de bajas y referencias históricas; no crear documentos incompletos.
- Resolver duplicados reales sin elegir ganadores automáticamente. Este paso no los altera.
- Revisar y autorizar cualquier despliegue futuro de reglas, especialmente la denegación de creación/importaciones/tiendas desde cliente y el cerrojo propuesto.
- Elegir componente de confianza, protocolo común para todos los escritores, journal, fencing, recuperación y presupuesto. Una identidad IAM que ignore el protocolo puede eludir las reglas: controlar también esos accesos.
- Autorizar expresamente el paso 4B y comprobar de nuevo todas las condiciones antes de habilitar escrituras.

**Confirmación:** no se hicieron consultas de producción, escrituras reales, cambios de roles, despliegues de reglas/índices, modificaciones del historial ni sincronización. Solo hubo escrituras sobre fixtures ficticios del emulador local. No se activó el botón de sincronización ni se implementó 4B o IA.
