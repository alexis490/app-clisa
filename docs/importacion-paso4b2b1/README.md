# PASO 4B.2B.1 — Revisión controlada de incidencias

Implementación local de revisión. **No se modificó Firestore, el Excel original, reglas, índices, roles, registros ni formalizaciones. La sincronización real continúa deshabilitada.**

## Estado de los componentes

| Componente | Estado | Alcance |
|---|---|---|
| Catálogo, identificación y agrupación | IMPLEMENTADO / PROBADO | Se cargan las 567 incidencias del Excel real; los IDs coinciden con el expediente 4B.2A. |
| Pantalla de revisión y detalle | IMPLEMENTADO / PROBADO | Filtros locales, páginas de 25, originales, propuestas, historial y confirmación explícita. |
| Persistencia | IMPLEMENTADO / PROBADO | Expediente local versión 1 por cuenta y SHA-256; recuperación, historial, revisión optimista, checksum y reemplazo atómico. |
| Validaciones por tipo | IMPLEMENTADO / PROBADO | Colisiones Excel/Firestore cuando existe baseline completo; evidencia documental obligatoria para RESUELTO. |
| Verificación documental externa | PARCIAL | La referencia la proporciona el administrador; la herramienta no autentica tickets ni certifica el sistema principal. |
| Permisos | IMPLEMENTADO / PROBADO localmente | USER rechazado en el repositorio; ADMIN y SUPER_ADMIN permitidos con principal verificado por el adaptador. No se probaron identidades reales en producción. |
| Integración con Dry Run | IMPLEMENTADO / PROBADO | Resumen separado de estados; conserva el plan, hash, errores, verificaciones temporales y bloqueos originales. |
| Copia XLSX corregida | BLOQUEADO | API de preparación/resumen y rechazo explícito; no hay escritor seguro que preserve el libro. |
| Revalidación de nueva exportación | IMPLEMENTADO / PROBADO | Nueva selección vuelve a ejecutar el lector y calcula el hash de los mismos bytes. Una fixture corregida externamente prueba independencia. |
| Sincronización, IA, 4B.2B.4 | BLOQUEADO | Sin botones ni rutas de ejecución habilitadas. |

## Inspección y arquitectura

Se revisaron `docs/importacion-paso4b2a/README.md`, su `issue-review.json`, `docs/importacion-paso4b1/README.md`, el expediente generado por Node, lector ZIP/SAX, comparador, Dry Run, pantallas existentes y política de roles. No se reemplazaron los pasos anteriores.

Flujo: lector existente → catálogo de incidencias → expediente local → propuesta confirmada → adaptador de autorización → validación → persistencia local. La comparación existente aporta opcionalmente su snapshot ya retenido; abrir o filtrar la revisión no vuelve a leer inventario.

El ID utiliza la misma canonicalización Kotlin/Node y la fórmula `SHA256(['CLISA_ISSUE_REVIEW_V1', hashExcel, índiceIncidencia, incidencia])`. No se sustituyen IDs de documentos por SKU. Las ocurrencias Firestore muestran el ID real y, en series, el `productoId`.

## Archivos

Nuevos:

- `app/src/main/java/com/example/app_clisa/data/excel/IssueReview.kt`: estados, decisiones, catálogo, ocurrencias, validación, preparación bloqueada de copia y resumen de revisión del Dry Run.
- `app/src/main/java/com/example/app_clisa/data/excel/IssueReviewStore.kt`: expediente binario versionado y persistente.
- `app/src/main/java/com/example/app_clisa/data/IssueReviewAuthorization.kt`: revalidación de sesión, perfil propio y permisos existentes.
- `app/src/main/java/com/example/app_clisa/ui/products/IssueReviewScreen.kt`: acceso, coordinador, listado, filtros, detalle, propuestas, confirmación e historial.
- `app/src/test/java/com/example/app_clisa/IssueReviewTest.kt`: 24 pruebas unitarias nuevas.
- `app/src/androidTest/java/com/example/app_clisa/IssueReviewUiTest.kt`: cuatro pruebas Compose aisladas.

Modificado: `app/src/main/java/com/example/app_clisa/ui/products/ExcelImportAction.kt`, exclusivamente para incorporar la sección y reutilizar el resultado de lectura/comparación.

No se modificaron `InventoryComparison.kt`, el parser, el motor Node, las reglas, Gestión de Productos ni Detalle de producto.

## Interfaz y navegación

Gestión de Productos → Importar Excel → seleccionar archivo → **Revisar incidencias**. También se accede desde la vista previa existente después de comparar.

Se reutiliza `ImportFlowFrame`, encabezado geométrico azul marino e indicador en Revisar. Hay tarjetas compactas con bordes azules, cifras reales, SHA, agrupación por tipo, selector de estado y búsqueda de producto/SKU/serie/fila. La búsqueda tiene debounce de 350 ms y se ejecuta en memoria. Lista, ocurrencias e historial se muestran en páginas de 25, sin construir miles de componentes Compose.

Cada incidencia abre un detalle con mensaje, fila, ID, literal original entre comillas, propuesta de recorte informativa cuando corresponde y todas las ocurrencias relacionadas. Las propuestas se escriben expresamente: no se rellenan ni aceptan solas. Se muestran cantidades originales y documentos reales disponibles. Volver retorna al listado; volver de nuevo recupera el paso de importación anterior.

Los campos de revisión y el guardado están deshabilitados para USER o cuando no se pudo verificar el acceso administrativo. Toda decisión pide confirmación; guardar requiere revalidar la cuenta. No hay acción de escritura de inventario.

## Modelo y estados

`PENDIENTE`, `EN_REVISION`, `RESUELTO`, `BLOQUEADO`. Revisadas = estados distintos de PENDIENTE; EN_REVISION se informa además por separado. RESUELTO **no significa aplicado**: correcciones aplicadas a copia = 0 en esta etapa.

Cada evento conserva ID de incidencia, tipo, original, propuesto, justificación, referencia documental, UID de responsable, hora local en milisegundos, estado y SHA del Excel. Se conserva todo el historial; cambiar un estado no elimina eventos previos. Los campos de propuesta/justificación/evidencia admiten hasta 4000 caracteres.

La evidencia textual y la hora del dispositivo son declaraciones de revisión, no firmas de aprobación para un worker privilegiado. El motor no consume este expediente como autorización.

## Validaciones

| Tipo | Validación al intentar RESUELTO |
|---|---|
| MISSING_SKU | Propuesta explícita no vacía, contexto de producto recuperable, sin espacios exteriores ni colisiones con grupos Excel, códigos comerciales de baseline Firestore completo o propuestas resueltas de otros grupos. No genera SKU ni adopta un documento existente automáticamente. Si colisiona con un producto existente requiere revisión futura de identidad, no se fusiona. |
| SERIAL_WHITESPACE | Conserva el original; solo admite proponer su recorte exacto. Exige una sola ocurrencia normalizada en Excel y baseline completo; rechaza otra identidad normalizada Firestore. La única identidad existente admisible conserva exactamente literal y producto originales. |
| DUPLICATE_SERIAL | Incluye la primera y todas las demás apariciones, productos, filas, cantidades y documentos disponibles. Exige referencia y decisión explícita `REPETICION_LEGITIMA` o `CORREGIR_EN_ORIGEN`. No elige un ganador ni suma, elimina o fusiona. El conflicto del parser permanece. |
| DUPLICATE_SKU | Incluye todos los encabezados del SKU y documentos disponibles. Mismos criterios documentales; ningún nombre determina automáticamente identidad común. RESUELTO documenta la conclusión, no permite fusionar grupos. |
| NEGATIVE_QUANTITY | Original negativo visible; propuesta explícita entera en rango Long y no negativa, con evidencia del saldo del sistema principal. Nunca convierte automáticamente a cero. |
| Otros diagnósticos | Requieren revisión técnica; se pueden documentar EN_REVISION/BLOQUEADO, pero no resolver mediante un editor genérico. |

EN_REVISION y BLOQUEADO requieren justificación. RESUELTO requiere además evidencia no vacía. Faltar baseline completo impide resolver los casos que exigen comprobar colisiones; sí permite documentar la propuesta pendiente sin nuevas lecturas automáticas.

Una incidencia no equivale a una fila única ni a un grupo único de duplicados: distintas incidencias relacionadas pueden mostrar el mismo conjunto de ocurrencias. No se redujeron artificialmente las 567 incidencias.

## Persistencia y permisos

Directorio privado `context.noBackupFilesDir/excel-issue-reviews`. Nombre derivado del hash del UID y SHA del archivo; no se usa el SKU como ruta. Binario con cabecera `CLISA_ISSUE_REVIEW`, versión 1, UID, SHA, eventos y checksum SHA-256. Archivo temporal, fsync y reemplazo atómico cuando el sistema lo soporta; fallback de reemplazo de archivo. Máximo 10 000 eventos y 32 MB. Se rechazan revisiones concurrentes obsoletas.

Al reabrir la herramienta, selecciona el mismo Excel: se recupera el expediente de esa cuenta. Cambiar bytes/hash crea un expediente independiente; no importa resoluciones anteriores. Un expediente corrupto o de versión desconocida falla cerrado, se conserva y no se sustituye por uno vacío. La interfaz no publica cifras recuperadas hasta abrir el expediente.

Cada cuenta tiene su propio expediente local; no existe colaboración entre dispositivos ni fusión automática entre administradores. Borrar datos o desinstalar elimina esta información. No se incluye en copias de seguridad Android. El checksum detecta corrupción accidental; no protege frente a manipulación de un dispositivo comprometido y no constituye una firma administrativa.

La autorización usa Firebase Authentication y la lectura SERVER del documento propio `usuarios/{uid}`; comprueba `role`, `estado` y claims con `AccessPolicy` existente. Fuerza renovación del token y verifica que el UID siga siendo el mismo. Un fallo de permisos/red mantiene el editor sin acceso; no se usa Admin SDK ni se eluden reglas.

Lecturas adicionales: **cero de productos/existencias** al abrir, filtrar, paginar, consultar detalles o preparar el resumen de copia. Una lectura del perfil propio al verificar acceso al abrir, y otra por cada guardado explícito. El baseline es el ya retenido por el paso 2. No se dispara una comparación automáticamente; el usuario conserva el control del análisis existente y de su coste.

## Copia corregida: límite deliberado

El lector ZIP/SAX interpreta celdas pero no conserva un modelo de edición de estilos, fórmulas, relaciones y demás partes del libro. Reescribir el libro a partir de sus modelos de inventario perdería información.

`prepareCopy()` revalida decisiones resueltas y prepara su resumen. `canGenerate` es siempre false y `generate()` rechaza sin abrir ni modificar el original o un destino. La UI muestra el resumen y explica el bloqueo; no promete que se haya generado una copia.

Alternativa segura actual: revisar las decisiones, corregirlas en el sistema principal con respaldo documental y **exportar un nuevo XLSX**. Volver a seleccionarlo ejecuta el parser y validaciones desde cero sobre sus propios bytes/hash, sin reutilizar decisiones ni la conciliación temporal previa. Las filas de resumen siguen separadas de las existencias por el lector existente.

Antes de habilitar un generador se requiere un escritor OOXML probado que preserve las partes no modificadas, relación celda/incidencia fiable, manifest de cambios y hash origen/destino, exportación a un destino nuevo, comparación de filas intactas y relectura integral automática. **Ese escritor no está implementado ni se simuló como aprobado.**

## Dry Run

`IssueReviewedDryRun` enlaza el plan del mismo SHA con los contadores del expediente y distingue sin revisar, en revisión, bloqueadas, resueltas con evidencia y aplicadas a copia (0). Devuelve el mismo plan, sin alterar operaciones, errores del parser, completitud, cantidades, permisos ni evidencia temporal. Un plan de otro hash se rechaza.

Aunque todas las decisiones estén RESUELTO, el Excel real sigue teniendo incidencias; no adquiere completitud verificada. La preparación de copia revalida incluso las propuestas persistidas. Un baseline previo solo sirve para revisión, nunca prueba actualidad para una sincronización posterior.

## Pruebas y evidencias

- `android-build.log`: compilación debug, APK de pruebas y pruebas JVM. **236 pruebas JVM, 0 fallos, 0 omitidas**, incluidas 24 nuevas y lectura del Excel real mediante `CLISA_EXCEL_SAMPLE`.
- `ui-tests.log`: primera batería local de 14 pruebas Compose, incluidas cuatro nuevas; sin cuentas de producción y emulador Android sin red.
- `ui-final-tests.log` y `ui-regression-retry.log`: herramienta y regresiones adicionales de vista previa/fecha. **24 pruebas de interfaz distintas verificadas en total** entre ambas baterías. En la batería final, una prueba existente de búsqueda agotó su espera de 1000 ms; pasó al repetirla aisladamente, sin modificar su código ni la pantalla. Se conserva el fallo inicial y el resultado de la repetición para no ocultar esta inestabilidad temporal.
- `node-emulator-tests.log`: **78 pruebas Node/Firestore/Auth Emulator, 0 fallos, 0 omitidas**, proyecto `demo-clisa-sync-4b1`, sin producción. No se repitieron suites ajenas al motor que no cambiaron en este paso.

Las pruebas nuevas cubren carga real/grupos/IDs, filtros/estados, SKU explícito y colisiones Excel/Firestore/otras propuestas, ausencia de baseline, recorte y colisiones normalizadas, duplicados con primeras apariciones e IDs reales, negativos, evidencia/justificación vacías, persistencia/reapertura/historial/revisión obsoleta, corrupción, cambio de hash/cuenta, permisos USER/ADMIN/SUPER_ADMIN, bloqueo del generador y conservación del original, relectura completa de fixture corregida externamente y conservación exacta del Dry Run.

Captura verificada de las tarjetas y filtros con datos ficticios: [issue-review.png](issue-review.png). La captura corresponde al contenido desplazable; el encabezado y el indicador se reutilizan del marco existente.

Los casos de copia se prueban conforme al bloqueo permitido por el requisito: **no certifican un escritor XLSX inexistente**. La fixture de una nueva exportación prueba el parser y el aislamiento por hash, no una generación desde Android. Los guardados de pruebas son solo archivos locales con decisiones ficticias.

## Prueba desde Android Studio

1. Ejecuta debug con una cuenta ADMIN/SUPER_ADMIN existente y activa. Abre Importar Excel y selecciona el archivo real; no se modifica.
2. Abre Revisar incidencias: verifica total 567 y distribución 346/156/48/15/2. Usa filtros, páginas y detalles. Para propuestas de identidad que necesiten comprobar colisiones, primero realiza la comparación existente de forma explícita; no la repitas innecesariamente.
3. Para verificar persistencia sin inventar correcciones, guarda EN_REVISION/BLOQUEADO con una justificación documental real y confirma. Cierra y vuelve a seleccionar el mismo archivo: el estado e historial deben recuperarse para tu cuenta.
4. No marques RESUELTO sin evidencia real. Prueba con fixtures aisladas para cantidades/SKU ficticios. Las decisiones no cambian el archivo ni Firestore.
5. Abre Revisar propuesta de copia: aparece el resumen y el bloqueo del escritor. La sincronización permanece deshabilitada.
6. Selecciona una exportación nueva: se vuelve a leer y no se trasladan resoluciones del hash anterior. El corte temporal debe revisarse de nuevo.

Comando local JVM: configura `JAVA_HOME` al JBR instalado, `CLISA_EXCEL_SAMPLE` al XLSX real y ejecuta `gradlew.bat :app:assembleDebug :app:testDebugUnitTest :app:assembleDebugAndroidTest --no-configuration-cache`. Las pruebas de interfaz deben ejecutarse en un emulador aislado, sin iniciar operaciones contra producción.

## Pendientes reales y requisitos de 4B.2B.2

Las **567 incidencias reales continúan pendientes**, sin decisiones inventadas por esta implementación: 346 MISSING_SKU, 156 SERIAL_WHITESPACE, 48 DUPLICATE_SERIAL, 15 DUPLICATE_SKU y 2 NEGATIVE_QUANTITY. No se editó el expediente histórico 4B.2A ni el archivo original.

Para continuar: revisión humana documentada, resolver identidades en origen, nueva exportación trazable, completar la verificación de alcance y corte temporal, decidir transporte/respaldo del expediente y autenticación de evidencia externa. Si se solicita copia desde Android, implementar y probar primero el escritor seguro descrito. Los requisitos de cerrojo, revalidación fresca y autorización del worker de 4B.2A siguen pendientes e independientes; este paso no los sustituye ni activa producción.
