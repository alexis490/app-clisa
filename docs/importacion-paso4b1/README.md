# PASO 4B.1 — Motor administrativo de sincronización, solo Emulator

Fecha: 23 de octubre de 2026, America/Lima. **No habilita 4B.2 ni sincronización de producción.**

## Resultado

| Apartado | Estado | Alcance y límite |
|---|---|---|
| Ejecutor Node.js + Firebase Admin SDK | IMPLEMENTADO / PROBADO | Runtime cerrado a `demo-clisa-sync-4b1` y endpoints locales; no existe modo producción. |
| Contrato Kotlin → Node | IMPLEMENTADO / PROBADO | Esquema 1 sobre Dry Run 3; canonicalización, planId, operationId e IDs contrastados entre JVM y Node. Exportación offline por API; sin botón ni transporte nuevo. |
| Mutaciones por producto y conservación de metadatos | IMPLEMENTADO / PROBADO | Crear producto/serie, actualizar cantidad/estado y stock en una unidad atómica con journal/checkpoint. |
| Bloqueo, fencing, versiones y exclusión de SALIDA | IMPLEMENTADO / PROBADO en Emulator | Nueva propuesta local de reglas. **BLOQUEADO en producción:** las reglas publicadas no incluyen este protocolo. |
| Idempotencia, auditoría y recuperación supervisada | IMPLEMENTADO / PROBADO | Fuente reservada, checkpoint atómico, reanudación sin repetir commits; cercado del trabajador anterior. Preparación incompleta y revisiones no resueltas retienen el bloqueo. |
| Presupuesto Spark | IMPLEMENTADO / PROBADO como política | Exige saldo explícito reciente y margen; no mide cuota real ni convierte al Emulator en simulador de facturación. |
| Autorización del plan | IMPLEMENTADO / PROBADO para fixtures | Firma Ed25519 de autoridad independiente de Android, perfil ACTIVO y claims en Auth Emulator. Sin claves reales configuradas. |
| Procedencia del Excel y conciliación externa reales | PARCIAL / BLOQUEADO | El motor exige atestaciones independientes, pero esta fase solo reconoce `EMULATOR_FIXTURE_ONLY`. No hay adaptador confiable del sistema de ventas ni evidencia real de inclusión de movimientos. |
| DELETE_EXISTENCE | BLOQUEADO deliberadamente | Tipo reconocido y rechazo incondicional, incluso con firma y una declaración COMPLETE_VERIFIED. |
| Excel real con 567 incidencias | BLOQUEADO | El contrato conserva las incidencias; no transforma sus operaciones en autorizadas. |
| Interfaz Android, producción e IA | Sin activación | Diseño, búsquedas, detalle, SALIDA, estados de pago/formalización y sincronización deshabilitada se conservan. |

## 1. Inspección previa y reglas reales

Se revisaron los informes 4A, 4A.1, 4A.2 y formalización, modelos, lector, comparador, planificación, política de IDs, recuperación y las propuestas locales.

Solo se hicieron **dos GET de metadatos/reglas**, sin leer documentos de producción. Copia: `security/paso4b1/production-inspection/`.

- Proyecto inspeccionado: `empresa-clisa`.
- Inspección UTC: `2026-10-23T17:24:28.498Z`.
- Ruleset: `27e24b85-2698-435c-9949-e5daa2a4abce`.
- SHA-256: `41f7aadf06df0e4d5b8e89af1fb4ae6f484c9cd57aec3fd6dc553b35ea33451a`.

Es la versión desplegada para formalización, no la propuesta más restrictiva de 4A.1. Permite la transición de formalización acotada y SALIDA atómica, pero no coordina un cerrojo de importación. Además conserva permisos antiguos que requieren revisión separada. **No se reutilizó una versión antigua suponiendo que estuviera publicada.**

`security/paso4b1/firestore.emulator.rules` parte de esa copia y añade únicamente el protocolo local: las creaciones/actualizaciones de productos, existencias y registros requieren inventario desbloqueado; las creaciones de importaciones desde clientes quedan denegadas. El cerrojo y sus subcolecciones son de escritura exclusiva del operador Admin. No se cambió ninguna regla publicada. El diff está en [rules-emulator.diff](rules-emulator.diff).

## 2. Arquitectura

```mermaid
flowchart LR
  X[Excel y lector Kotlin existentes] --> C[Comparador y Dry Run existentes]
  C --> W[SyncExecutionContract: JSON sin autorización]
  W --> A[Revisión administrativa separada]
  A --> S[Firma de alcance y evidencia ficticia]
  S --> E[Ejecutor Node cerrado a Emulator]
  E --> L[Cerrojo global y fencing]
  L --> V[Una relectura paginada y validación]
  V --> T[Transacción por producto]
  T --> J[Inventario + journal + checkpoint]
```

Firebase Admin SDK está instalado **solo** en `tools/inventory-sync`, nunca en Gradle ni dentro del APK. No se usan Cloud Functions, Blaze, claves privadas del proyecto, cuentas de servicio ni IAM para escribir en producción. El runtime exige simultáneamente:

```text
GCLOUD_PROJECT=demo-clisa-sync-4b1
FIRESTORE_EMULATOR_HOST=127.0.0.1:8086
FIREBASE_AUTH_EMULATOR_HOST=127.0.0.1:9098
```

Otro proyecto/endpoint se rechaza antes del acceso del SDK. No hay parámetros para cambiar el destino a producción ni un servidor HTTP que acepte peticiones del teléfono.

**Integración móvil pendiente:** un equipo administrativo tendrá que recibir el archivo/contrato, volver a producir o verificar su evidencia mediante una herramienta confiable y aprobar el alcance. Para iniciarlo realmente desde el teléfono haría falta un servicio autenticado adicional, identidad del solicitante verificable y protocolo de autorización aprobado. Exportar JSON no sustituye ese servicio. Esta etapa no conecta un transporte ni habilita el botón.

## 3. Archivos

Nuevos Kotlin:

- `app/src/main/java/com/example/app_clisa/data/excel/SyncExecutionContract.kt`.
- `app/src/test/java/com/example/app_clisa/SyncExecutionContractTest.kt`.

Nuevo ejecutor, en `tools/inventory-sync/`:

- `package.json`, `package-lock.json`, `.gitignore`: Admin SDK 13.5.0, SDK cliente y herramientas de reglas fijados; Node >=22.
- `canonical.mjs`: codificación compatible con SyncCanonical y política determinista de IDs.
- `runtime.mjs`: guardia de destino y clientes Admin de Firestore/Auth Emulator.
- `contract.mjs`: integridad, firma, selección autorizada, bloqueos, vigencia y presupuesto.
- `inventory.mjs`: páginas de 500, identidades, huellas y reconstrucción independiente de las mutaciones por producto.
- `engine.mjs`: cerrojo, fencing, unidades atómicas, journal, checkpoints, idempotencia y recuperación.
- `cli.mjs`: entrada administrativa local con archivos, sin modo producción; guarda solicitud y resultado/error local.
- `test/engine.test.mjs`: fixtures ficticios, firma efímera de pruebas y pruebas reales contra ambos emuladores.

Seguridad local nueva: `security/paso4b1/firebase.json`, `firestore.emulator.rules`, `prepare-emulator-rules.cjs`, `Test-Emulator.ps1` y copia inspeccionada de reglas/metadatos.

Modificación de pruebas existentes: `security/paso4a1/emulator/formalization.test.mjs` acepta opcionalmente `CLISA_FORMALIZATION_RULES` para ejecutar las mismas regresiones contra la propuesta local nueva; su destino local y comportamiento por defecto se conservan.

**Sin cambios** en InventoryComparison, InventoryDryRunPlanner, FirestoreInventoryReader, XlsxInventoryReader, ExcelImportAction, pantallas, navegación, autenticación, roles, lectores de stock ni transacción SALIDA. Las propuestas previas de reglas no se sobrescriben.

## 4. Contrato Android ↔ ejecutor

`SyncExecutionContract.value/json(plan, excel, comparison, requesterUid)` reutiliza los objetos ya retenidos, sin consultar Firestore. No firma, envía ni ejecuta nada.

El esquema 1 contiene:

| Campo | Contenido |
|---|---|
| schemaVersion | 1, separado de plan.schemaVersion = 3 |
| requester.uid | Identificador declarado del solicitante; no se considera prueba de autenticación por sí solo |
| plan | planId, SHA-256, nombre, baselineFingerprint, observedAtMillis, declaración temporal con zona, identidad temporal, checks y todas las operaciones |
| operations | operationId, tipo, ruta/ID real, SKU/serie, padre, before/expected, estado de validación, riesgo, bloqueos, referencias, grupo y versión observada |
| source | Productos/series originales, cantidades, pertenencia de filas, incidencias y evaluación de completitud |
| comparison | Instantánea retenida con IDs reales, stock y cantidadRestante separados, existencias, versiones y conflictos |
| productionExecutable | Siempre false en el artefacto Android |

JSON solo admite tipos explícitos e enteros representables exactamente en JavaScript. Conserva Unicode, comillas, SKU con `/`, ceros y espacios literales; no normaliza identidades. Se rechazan artefactos de más de 8 MiB en esta implementación; un contrato real grande requiere otro empaquetado autenticado, no truncarlo.

Node repite los hashes de operaciones y plan con la canonicalización Kotlin (incluidas longitudes UTF-16 y orden de mapas), verifica SHA-256 contra los **bytes del archivo recibido**, firma y hash del contrato completo, hash de las filas atestadas y selección explícita de operaciones. No basta con cambiar un booleano o borrar un bloqueo del JSON.

La aprobación **separada** contiene keyId, payload y firma Ed25519 en base64. Su payload vincula contractHash, fileSha256, sourceHash, requesterUid, issuedAt/expiresAt, allowedOperationIds, presupuesto/margen reciente, límite de historial y evidencia de conciliación por producto. Las claves efímeras de pruebas se generan en memoria; ninguna clave privada se escribe en Android o en la documentación.

Solo el registro de claves públicas del operador es confiable; no se toma una clave del contrato Android. La CLI es para un entorno local administrado: su archivo de clave pública y permisos del sistema operativo forman parte de esa frontera de confianza.

### Limitación deliberada de la evidencia

El motor no incorpora otro lector XLSX que compita con el existente. El SHA acredita bytes, **no acredita por sí solo que las filas JSON representen esos bytes**. En estas pruebas esa correspondencia y la conciliación se atestan desde el generador de fixtures independiente de Android, y se aceptan exclusivamente con `evidenceKind = EMULATOR_FIXTURE_ONLY`.

La canonicalización de un contrato genuino generado por Kotlin sí se contrasta en Node con un fixture emitido por JUnit. Ese Dry Run genuino conserva sus bloqueos; no se lo convierte en un fixture ejecutable. Para archivos reales falta un adaptador administrativo confiable que ejecute/verifique el lector sobre el archivo recibido y una autoridad del exportador/reconciliador. Firmar datos arbitrarios enviados por el teléfono no resolvería ese requisito. **No se declara implementada la autorización de un Excel real.**

## 5. Operaciones y validación independiente

| Tipo | Ejecución local |
|---|---|
| CREATE_PRODUCT | ID `p_v1_<sha256>` exacto de 4A.1; ausencia/unicidad comprobadas. Campos mínimos del fixture: codigo, nombre, stock, estado y actualizadoEn. El esquema real nuevo sigue sujeto a aprobación antes de 4B.2. |
| CREATE_EXISTENCE | ID `e_v1_<sha256>` sobre padre real + serie exacta; productoId y codigoProducto coherentes; cantidad y estado validados. |
| UPDATE_EXISTENCE | Solo cantidad, estado y actualizadoEn. |
| UPDATE_PRODUCT_STOCK | Solo stock y actualizadoEn. Exige que el conjunto completo de series del producto concilie con el Excel atestado. |
| SKIP_UNCHANGED | Ninguna escritura de inventario ni journal de mutación; un plan completamente sin cambios no crea job/cerrojo. |
| BLOCK_CONFLICT | Nunca ejecutable; no se elige un duplicado. |
| REVIEW_MISSING_PRODUCT | Documento conservado sin poner stock cero ni eliminarlo. |
| DELETE_EXISTENCE | Rechazo incondicional; no hay borrado ni sustitución silenciosa por cantidad cero. |

Un producto existente conserva su ID real, aunque SKU sea `SFYRS/1000G` e ID `SFYRS_1000G`. Unicidad por código exacto, no similitud de nombres. Se bloquean duplicados en Excel/Firestore, series asociadas a otro padre, referencias inválidas, cantidades negativas, producto inactivo y rutas ocupadas. No se inventa tiendaId: el esquema de existencias actual no tiene ese campo y el alcance verificado del negocio es CLISA.

El ejecutor reconstruye las mutaciones esperadas desde las filas atestadas y el inventario bajo cerrojo; exige igualdad exacta con las propuestas autorizadas. No acepta una cantidad esperada manipulada aunque el JSON tenga firma. También impide seleccionar solo el stock o solo una parte de un grupo.

`categoriaId`, `marcaId`, `descripcion`, `cantidadRestante`, cantidades heredadas, pago y formalización existentes no entran en ninguna actualización del ejecutor. Un campo antes desconocido tampoco se borra mediante reemplazo completo.

No se permite ninguna incidencia de origen en un plan ejecutable en esta fase. La ejecución parcial exige una selección explícita de **grupos íntegros e independientes**, sin conflictos de origen/globales, y `allowPartial` firmado. Las exclusiones se contabilizan; terminar las autorizadas deja `REQUIRES_REVIEW` y conserva el cerrojo. No se presenta una fracción como una importación completa.

## 6. Traspasos y corte temporal

Antes de preparar unidades se lee el historial **una sola vez**, paginado por ID, con un límite explícito firmado (1 a 5 000 documentos). No se usa un índice de fecha para inferir que no existen movimientos mal fechados. Si falta identidad de producto/tipo en ese historial, o se alcanza un resultado que excede el límite, se bloquea en lugar de omitirlo.

Cada producto exige una evidencia firmada independiente que vincule el hash de sus movimientos completos, el hash de sus filas de origen y una referencia externa de conciliación. En esta fase esa referencia es obligatoriamente ficticia (`fixture:`).

Se prueban `PENDIENTE`, `COMPLETADO` y ausencia histórica de estado como `SIN_VERIFICAR`. **Ninguno de los tres, ni una fecha manual posterior, acredita inclusión en un Excel.** Sin prueba independiente se bloquea; no se resta otra vez ni se restaura automáticamente stock. El ejecutor no formaliza registros ni cambia estadoPago.

Para conciliar realmente haría falta identidad estable de la exportación, alcance/no filtros acreditados, corte autoritativo, identificadores externos de los traspasos y evidencia verificable de inclusión/exclusión de cada movimiento, además de un protocolo común para todos los escritores de inventario. Una marca de tiempo manual o una confirmación en CLISA no aporta por sí sola esos datos.

## 7. Cerrojo y concurrencia

En Emulator se usan exclusivamente:

```text
importaciones/_control_clisa
importaciones/source_<sourceReplayKey>
importaciones/run_<id>
importaciones/run_<id>/units/<productoId>
importaciones/run_<id>/journal/<operationId>
importaciones/run_<id>/recoveries/<fence>
```

Este esquema local nuevo no crea colecciones/documentos de producción; necesita aprobación antes de activarse allí.

Adquirir el cerrojo, reservar la fuente y crear PREPARED es una transacción. El control mantiene locked, runId, fence y timestamps del servidor. El fencing es monotónico y el documento de control no se borra al finalizar. Una antigüedad/heartbeat no libera automáticamente el cerrojo.

Cada commit por producto vuelve a leer control/fence, job, checkpoint y perfil ACTIVO; verifica rol con Auth Emulator, conjunto de series, unicidad de SKU, historial del producto y versiones nativas de todos los documentos implicados. Un trabajador con token anterior no puede confirmar ni cerrar el job del nuevo propietario.

El lock se impone en las reglas a **todos los clientes**, incluidas SALIDA, cambios descriptivos, creación de productos/existencias y formalización, manteniendo consultas de lectura. No depende del Dashboard. Se demuestra que una SALIDA real de SDK cliente pasa con inventario libre y se rechaza atómicamente durante la importación; una SALIDA completada entre comparación y adquisición invalida el plan.

Admin SDK elude reglas de seguridad: por eso el worker comprueba el cerrojo dentro de sus propias transacciones. Otra herramienta IAM que ignore el protocolo podría interferir; **ninguna regla cliente controla a un operador IAM arbitrario**. Este riesgo requiere permisos operativos y escritor único coordinado antes de producción. [Documentación de acceso de servidor](https://firebase.google.com/docs/firestore/security/rules-conditions).

La verificación de Auth y Firestore no es una transacción distribuida entre servicios. Se reconsulta Auth antes de cada unidad y el perfil dentro de la transacción; un servicio real de bloqueo debe coordinar primero la revocación del perfil. No se promete eliminar la ventana entre una modificación exclusiva de Auth y ese commit.

### Versiones y atomicidad

El campo `actualizadoEn` conservado por el lector Android **no es** la versión nativa `DocumentSnapshot.updateTime` del SDK de servidor. Se valida la huella semántica comparada bajo cerrojo y se guardan versiones nativas en checkpoints. Cada unidad vuelve a compararlas justo antes de escribir; esto detecta incluso una edición que no actualice aquel campo.

Una transacción confirma conjuntamente todos los cambios de un producto, sus entradas de journal, checkpoint y progreso del job. Máximo operativo: **400 escrituras por unidad incluyendo auditoría** y **600 KB del payload/checkpoint**. Un grupo mayor se bloquea completo, nunca se divide dejando stock/series desalineados. El SDK también aplica sus límites de tamaño/tiempo; no se afirma que esos umbrales reproduzcan todos los límites e índices reales.

No hay atomicidad global del Excel: si el primer producto se confirmó y el segundo falla, el resultado es PARTIAL y el bloqueo sigue activo. [Transacciones oficiales](https://firebase.google.com/docs/firestore/manage-data/transactions).

## 8. Auditoría, idempotencia y recuperación

El job guarda runId, planId, hash del contrato/Excel, solicitante, timestamps de servidor, estimación, cantidades autorizadas/omitidas/bloqueadas, grupos, errores y estado. Cada journal registra operationId, ruta, before/expected, tipo, actor, runId, fence, archivo y APPLIED en el mismo commit de inventario. Los checkpoints incluyen versiones anteriores y comprobantes del resultado.

Estados implementados: PREPARED, RUNNING, PARTIAL, FAILED, COMPLETED y REQUIRES_REVIEW. Una lista de errores no se borra al reanudar. Los documentos de job/journal/checkpoint no son editables por clientes Android.

- Mismo runId y contrato finalizado: devuelve el resultado registrado sin repetir mutaciones.
- Otra ejecución del mismo origen: la reserva sourceReplayKey la rechaza.
- Respuesta perdida después de commit: el checkpoint APPLIED permite reconocer la unidad; no vuelve a aplicar stock.
- Recuperación: exige `supervised=true`, aprobación válida, administrador vigente, mismo contrato/fuente/solicitante y checkpoints completos. Adquiere un fencing nuevo y audita la recuperación.
- Los checkpoints aplicados se contrastan con los documentos actuales y su timestamp de aplicación; si inventario/historial cambió, no se omiten a ciegas.
- Máximo **2 recuperaciones supervisadas**, y **3 intentos SDK por transacción**. Al agotarse, revisión manual y cerrojo retenido.
- Un proceso interrumpido en RUNNING/PREPARED con preparación completa puede cercarse y reanudarse de forma supervisada. Si no terminó de persistir todos los checkpoints, se bloquea con `PREPARATION_INCOMPLETE_REVIEW_REQUIRED`; no se inventa un estado intermedio.
- No hay rollback automático, compensaciones ni restauración de before. Un fallo o REQUIRES_REVIEW no libera el cerrojo. La resolución de exclusiones/preparaciones incompletas y su liberación supervisada requieren un procedimiento adicional; no existe botón para saltarlo.

Retener un bloqueo detiene las salidas del entorno que adopte el protocolo. Es deliberado para impedir actividad sobre inventario sin conciliar; no se debe borrar el control como «solución» a un fallo.

El sourceReplayKey vincula bytes y filas interpretadas; no sustituye la identidad de exportación del sistema externo. Dos XLSX distintos que expresen la misma exportación necesitan una política semántica/manifiesto confiable en la etapa real. La CLI guarda el contrato/aprobación y resultado/error local para revisión; esos archivos deben preservarse junto al journal.

## 9. Cuotas y consumo

No hay listeners. Una ejecución nueva reserva presupuesto antes de adquirir el lock; exige saldo observado hace como máximo 5 minutos y margen explícito. No asume que quedan 50 000/20 000/20 000 por estar en Spark. El Emulator no publica ni valida cuota de producción.

El estimador operativo separa inventario, auditoría, eliminaciones (0), consultas y límites de reintentos/recuperación. Para `B` documentos de baseline, `H` límite de historial, `N` grupos y `T = suma(series del grupo + 10)`:

```text
lecturas reservadas = B + 10*H + 18*T + 100
escrituras reservadas = 2*mutaciones + 4*N + 30
auditoría base estimada = 4*N + 8
eliminaciones = 0
```

Es una **reserva conservadora**, no lecturas/escrituras ya consumidas ni una medición de facturación. Incluye una lectura global, revalidaciones puntuales, historial, bookkeeping y margen para intentos/recuperaciones acotados. Los documentos efectivamente sin cambios no se actualizan ni generan journal de mutación. Los checkpoints/errores sí consumen escrituras administrativas cuando existe un job.

La revalidación usa una pasada de productos/existencias por páginas de 500, con límites vinculados al tamaño de la comparación. Encontrar un documento extra aborta, en lugar de seguir descargando una colección mayor sin presupuesto. Las unidades posteriores releen solo documentos/consultas del producto correspondiente. Reanudar reutiliza checkpoints, no vuelve a descargar todo el catálogo.

Con 1 799 productos + 22 762 existencias, `B ≈ 24 561`. La comparación Android previa ya consume aproximadamente otros 24 561 documentos si se hace ese día. Solo ambas pasadas sumarían **49 122**, sin historial, commits, app ni margen. El presupuesto normalmente bloqueará una sincronización de ese tamaño si no queda saldo suficiente. No se promete que Spark permita esa importación completa en un solo día ni se divide automáticamente entre días con un Excel viejo.

Referencia: Firestore ofrece habitualmente 50 000 lecturas, 20 000 escrituras y 20 000 eliminaciones diarias gratuitas, además de límites de almacenamiento/transferencia. [Cuotas oficiales](https://firebase.google.com/docs/firestore/quotas). Admin SDK **no elimina esas cuotas**; permitir más volumen no forma parte de esta fase.

## 10. Pruebas y resultados

Evidencias: [test-results.json](test-results.json), [android-build.log](android-build.log), [emulator-results.log](emulator-results.log), [formalization-regression.log](formalization-regression.log), [safety-regression.log](safety-regression.log).

- **212 pruebas Kotlin**, 0 fallos, errores u omitidas: 208 anteriores + 4 de contrato. Incluyen lectura del Excel real y conservación de sus 567 incidencias, INCOMPLETE y mutaciones bloqueadas; compatibilidad canónica del fixture entre JVM y Node.
- **56 casos del motor**, con Firestore y Auth Emulator, todos aprobados. Cubren los 27 requisitos de prueba del pedido y controles adicionales de fuente, cuotas, límite de grupo/historial, firma, scopes parciales, fencing, idempotencia y recuperación.
- **33 regresiones de formalización**, aprobadas **contra la propuesta local 4B.1**, con lock ausente: no se rompe SALIDA ni formalización fuera de importación.
- **36 regresiones de seguridad/temporalidad 4A.1/4A.2**, aprobadas contra su propuesta de referencia separada. No se confunde esa propuesta con las reglas desplegadas o con la nueva.
- assembleDebug y assembleDebugAndroidTest correctos. No se repitieron pruebas instrumentadas visuales: no hay cambios de interfaz; no se declara una prueba end-to-end desde Android contra este worker.

| Escenarios obligatorios | Cobertura |
|---|---|
| Producto/serie nuevos, cantidad, stock y sin cambios | Casos 01–03, 43–44; actualización conjunta y conservación de serie no mutada |
| SKU/serie duplicados e IDs deterministas | 04–05, 20–21, 29; sin primera coincidencia |
| Versión, antigüedad y plan alterado | 06–08, 22 y comparación de identidad Kotlin/Node |
| Repetición, simultaneidad y SALIDA concurrente | 09–11, 35–36 |
| PENDIENTE, COMPLETADO, SIN_VERIFICAR y corte incierto | 12–13; todos bloquean sin evidencia independiente |
| Fallo, respuesta perdida y recuperación parcial | 14–15, 26, 31, 33, 42, 45 |
| Cuota insuficiente y eliminación bloqueada | 16–17, 25, 48 |
| Usuario no autorizado | 18; USER, bloqueado, claims insuficientes y Auth deshabilitado |
| Historial/pago/formalización/cantidadRestante conservados | 02, 12 y 19; sin escrituras de esos campos |

Los 56 casos incluyen variantes parametrizadas; los números de nombre identifican familias de pruebas. Los usuarios, claves y documentos creados son **ficticios y exclusivos del Emulator**. Los mensajes PERMISSION_DENIED en pruebas negativas son rechazos esperados, no fallos de suite.

## 11. Cómo reproducir

Desde la raíz, con Node 22, Java 21 y Firebase CLI local:

```powershell
$env:JAVA_HOME = 'C:\Program Files\Android\Android Studio1\jbr'
$env:CLISA_EXCEL_SAMPLE = 'C:\Users\TUF\Documents\stock.production.lot123 - 2000.xlsx'
$env:CLISA_SYNC_CONTRACT_FIXTURE = Join-Path $PWD 'docs/importacion-paso4b1/kotlin-contract-fixture.json'
.\gradlew.bat :app:assembleDebug :app:testDebugUnitTest :app:assembleDebugAndroidTest --no-configuration-cache

Push-Location tools/inventory-sync
npm.cmd ci --ignore-scripts --no-audit --no-fund
Pop-Location
.\security\paso4b1\Test-Emulator.ps1 -FirebaseCli '<ruta a firebase-tools/lib/bin/firebase.js>'
```

El fixture Kotlin es requisito de la prueba de compatibilidad; no se omite silenciosamente si falta. El lanzador fija proyecto/puertos locales, restaura el entorno y detiene la ejecución si las pruebas fallan. Los tests limpian solo el namespace `demo-clisa-sync-4b1` del Emulator.

Uso de la CLI **solo con emuladores ya iniciados y archivos ficticios aprobados**:

```powershell
$env:GCLOUD_PROJECT = 'demo-clisa-sync-4b1'
$env:FIRESTORE_EMULATOR_HOST = '127.0.0.1:8086'
$env:FIREBASE_AUTH_EMULATOR_HOST = '127.0.0.1:9098'
node tools/inventory-sync/cli.mjs --contract fixture-contract.json --excel fixture.xlsx --approval fixture-approval.json --public-key fixture-operator-public.pem --output local-audit --run-id run_manual
# Recuperación supervisada, mismo contrato/fuente y aprobación vigente:
node tools/inventory-sync/cli.mjs --contract fixture-contract.json --excel fixture.xlsx --approval fixture-approval.json --public-key fixture-operator-public.pem --output local-audit-recovery --recover run_manual --supervised true
```

Los archivos del ejemplo no se generan con claves reales ni constituyen un procedimiento para importar producción. Para probar sin preparar manualmente identidades/aprobaciones, usar la suite: genera todos los fixtures y claves temporales. No copiar `empresa-clisa` en esos comandos: el runtime lo rechaza.

## 12. Riesgos y requisitos antes de 4B.2

1. Resolver las **567 incidencias** en origen o mediante procedimiento autorizado; el motor no rellena los 346 SKU, recorta las 156 series, fusiona las 48 repeticiones de serie/15 SKU ni convierte los 2 negativos a cero.
2. Aprobar e implementar fuente administrativa confiable que verifique XLSX/filas, manifiesto, alcance, ausencia de filtros, identidad de exportación y corte. El modo fixture no se puede reutilizar como certificado real.
3. Obtener evidencia externa por movimiento; confirmar en CLISA o introducir una fecha no acredita inclusión en el Excel.
4. Definir esquema mínimo de productos nuevos y semántica de contadores heredados, sin sobrescribirlos con una suposición.
5. Aprobar esquema de importaciones/journal/checkpoints y un procedimiento de recuperación/liberación para preparaciones incompletas y REQUIRES_REVIEW, con responsables operativos.
6. Revisar reglas y todos los escritores IAM/clientes. Aprobar y probar el protocolo de mantenimiento antes de cualquier despliegue. Las reglas actuales no bastan para proteger SALIDA frente a un worker real.
7. Configurar identidad real del solicitante, custodia/rotación de claves administrativas y transporte/servicio autenticado si se desea operar desde el teléfono. No introducir esas credenciales en el APK.
8. Medir cuota restante, almacenamiento/transferencia, tamaños e índices reales. Reducir relecturas/comparación o definir otra arquitectura si Spark no tiene margen; no asumir el límite diario como saldo.
9. Aprobar empaquetado autenticado para contratos mayores de 8 MiB y estrategia para productos que excedan la unidad atómica. No dividirlos sin rediseñar consistencia.
10. DELETE_EXISTENCE seguirá deshabilitado hasta evidencia de completitud, identidad y corte, política de referencias históricas y recuperación/bajas expresamente aprobadas.

**Confirmación final:** en este paso no se creó, actualizó ni eliminó ningún documento de Firestore de producción; no se modificaron usuarios/roles, stock, existencias, historial, estadoPago ni estadoFormalizacion reales. No se desplegaron reglas ni índices. No se habilitó el botón, 4B.2 o IA. Solo hubo lectura autorizada de metadatos de reglas y escrituras ficticias en Emulator.
