# Formalización de traspasos — CLISA

> **Actualización: DESPLEGADO Y VERIFICADO** el 22 de octubre de 2026, tras autorización explícita del usuario. Se repitieron las 33 pruebas locales (todas aprobadas) y se publicaron únicamente reglas de Firestore en `empresa-clisa`. El ruleset `27e24b85-2698-435c-9949-e5daa2a4abce` coincide exactamente con el SHA-256 de la propuesta aprobada. No se leyeron ni escribieron documentos de producción ni se desplegaron índices o Functions. Evidencias: [deployment.json](deployment.json), [deploy.log](deploy.log), [pre-deploy-tests.log](pre-deploy-tests.log). Las referencias a autorización o despliegue pendientes del informe original inferior describen el estado previo y quedan superadas por esta actualización. La distribución del APK sigue siendo un paso separado.

Fecha: 22 de octubre de 2026, America/Lima. Implementación local; **producción no modificada**.

## 1. Resultado y clasificación

| Componente | Implementación/pruebas | Activación |
|---|---|---|
| Modelo de estados e interpretación histórica | **IMPLEMENTADO / PROBADO** con pruebas unitarias | Incluido en el próximo APK |
| Indicadores naranja, verde y neutro | **IMPLEMENTADO / PROBADO** en Compose, incluidos nombres largos y ancho pequeño | Próximo APK |
| Confirmación, cancelación, procesamiento y error | **IMPLEMENTADO / PROBADO** como componentes Android aislados | Próximo APK + reglas aprobadas |
| Repositorio Android transaccional | **IMPLEMENTADO**, compilado; contrato de transacción y concurrencia **PROBADO** con SDK en Emulator | **PENDIENTE DE DESPLIEGUE** de reglas y distribución del APK |
| Campos iniciales de registros nuevos | **IMPLEMENTADO / PROBADO**: SALIDA atómica para los tres roles en Emulator | **PENDIENTE DE DESPLIEGUE** |
| Reglas propuestas | **PROBADO** en Emulator, con diff contra la versión inspeccionada | **PENDIENTE DE DESPLIEGUE**, requiere autorización explícita |
| Confirmar contra producción | **BLOQUEADO deliberadamente** | Las reglas actuales prohíben actualizar registros |
| Migración de históricos / sincronización Excel | Fuera de alcance, no implementadas | No se ejecutarán como parte de esta activación |

**No distribuir el nuevo APK a producción antes de aprobar y desplegar las reglas compatibles.** La versión desplegada inspeccionada rechaza los tres campos adicionales de creación y las actualizaciones de formalización. Hasta ese despliegue, guardar una SALIDA con el nuevo APK o confirmar formalización puede recibir PERMISSION_DENIED. Las pruebas instalaron el APK únicamente en el emulador Android aislado.

## 2. Revisión real de reglas y estructura

Se consultaron exclusivamente los dos endpoints GET de publicación/ruleset mediante la sesión OAuth existente de Firebase CLI. **No se consultaron documentos de producción** ni se usó Admin SDK. Resultado guardado en `security/formalizacion-traspasos/inspection.json`:

- Proyecto: `empresa-clisa`.
- Inspección: `2026-10-23T00:34:03Z`, equivalente al 22 de octubre en Lima.
- Ruleset desplegado: `fa1be2b3-bfde-47d1-8758-a4ef1c43cf91`.
- SHA-256: `45f9f43640e970f740abfa7588ddbc8dd9b9720720f7da7d620d8481cacd063c`.
- Copia congelada: `security/formalizacion-traspasos/deployed-0-firestore.rules`.

Coincide con la copia de la auditoría anterior. Se revisó también la propuesta separada de 4A.1. **No se sustituyó el ruleset por aquella propuesta más amplia:** la nueva propuesta parte de las reglas efectivamente inspeccionadas, con cambios acotados a esta función y su compatibilidad/protección de SALIDA.

La estructura se revisó a partir del escritor Kotlin actual, del lector de Lista de registros y de la muestra documentada en la auditoría previa. No se inventó una ruta de inventario ni se hizo una migración para comprobar nuevos campos.

La SALIDA actual usa `estado = COMPLETADO` para indicar que el movimiento de inventario se guardó. Ese campo **no significa que el gerente lo haya formalizado en el sistema externo**. `estadoPago` también es independiente. Se mantiene esa distinción en modelo, UI, repositorio y reglas.

## 3. Archivos Kotlin

Nuevos, bajo `app/src/main/java/com/example/app_clisa/`:

- `data/TransferFormalization.kt`: estados/etiquetas, valores iniciales, roles autorizados y condiciones de transición.
- `data/FirestoreTransferFormalization.kt`: transacción de confirmación y resolución de carreras mediante lecturas puntuales autorizadas.
- `ui/records/FormalizationUi.kt`: iconos, indicador compacto y diálogo del mockup, con estado de procesamiento y error.

Modificados:

- `data/FirestoreInventory.kt`: añade únicamente los tres valores iniciales al mapa del registro nuevo. Conserva la transacción y los descuentos/validaciones existentes.
- `ui/records/RecordsScreen.kt`: lee formalización, incorpora indicador, coordina confirmación y conserva tarjetas, búsqueda, filtros, navegación y detalle de registro.
- `MainActivity.kt`: pasa al módulo el rol del perfil ya validado por el flujo de acceso existente. No cambia autenticación ni roles.

Pruebas nuevas:

- `app/src/test/java/com/example/app_clisa/TransferFormalizationTest.kt`.
- `app/src/androidTest/java/com/example/app_clisa/TransferFormalizationUiTest.kt`.
- `security/paso4a1/emulator/formalization.test.mjs`, reutilizando dependencias locales fijadas y añadiendo el script `test:formalizacion` al package.json. No se añadieron dependencias Android.

Preparación de seguridad:

- `security/formalizacion-traspasos/inspect-rules.cjs`: GET de reglas/metadatos, sin endpoints de documentos o despliegue.
- `firestore.proposed.rules`: propuesta completa separada.
- `proposed.diff`: diferencias concretas para aprobación.
- `firebase.json` y `Test-LocalRules.ps1`: pruebas locales con proyecto `demo-clisa-formalizacion`, host 127.0.0.1:8088; nunca producción.

No se modificaron Detalle de producto, Gestión de Productos, Reportes, receptores, autenticación, roles, reglas publicadas ni algoritmos de importación Excel.

## 4. Interfaz y navegación

- **Pendiente:** borde y reloj naranja; solo ADMIN/SUPER_ADMIN tienen la acción habilitada.
- **Completado:** borde y check verde, solo lectura.
- **Sin verificar:** indicador neutro; ausencia histórica del campo no se interpreta como pendiente. ADMIN/SUPER_ADMIN pueden confirmar un histórico coherente.
- Valores desconocidos, campo explícitamente nulo o auditoría previa inconsistente se muestran de forma conservadora como Sin verificar, **sin habilitar una corrección automática**.
- La tarjeta conserva nombre completo, código, cantidad, fecha, autor, receptor y tienda. El indicador se ubica arriba a la derecha. En ancho pequeño o fuente ampliada se reorganiza solo el interior de esa tarjeta para evitar comprimir el nombre.
- Tocar el indicador autorizado abre el diálogo, sin abrir el detalle simultáneamente. Tocar la tarjeta conserva la navegación anterior.
- El diálogo contiene el título/descripción solicitados, reloj, producto/código, Cancelar y botón verde. Mientras procesa se impiden cierre, cancelación y pulsaciones duplicadas.
- Cancelar solo cierra el diálogo: no llama al repositorio.
- Se muestra Completado únicamente tras la respuesta del servidor o una lectura confirmada de un estado que otro administrador ya completó. Un error mantiene el estado anterior y permite revisar/reintentar. Si el listener informa que otro administrador completó el registro mientras se revisaba, el diálogo pendiente se cierra.

Capturas de componentes con **fixtures ficticios locales**, no registros de producción:

- [Pendiente](pending.png).
- [Confirmación](confirmation.png).
- [Completado en ancho pequeño](completed-small.png).

No se incorporó el logo CLISA ni se rediseñó el encabezado, los filtros o el detalle de registro.

## 5. Datos y transacción

Registro nuevo de SALIDA:

```text
estadoFormalizacion: "PENDIENTE"
formalizadoEn: null
formalizadoPor: null
```

Confirmación:

```text
estadoFormalizacion: "COMPLETADO"
formalizadoEn: FieldValue.serverTimestamp()
formalizadoPor: UID autenticado
```

`confirm(recordId)` obtiene las claims del usuario autenticado y, dentro de la transacción, relee su perfil y el registro. Exige perfil ACTIVO, coherencia de rol/claims y rol ADMIN/SUPER_ADMIN. Solo acepta SALIDA COMPLETADO de CLISA hacia TOQEN, pendiente o sin verificar, sin auditoría previa incompatible.

Se utiliza `transaction.update` sobre **el documento existente** y solo esas tres claves. No hay `set`, creación de registro, referencia de escritura a productos/existencias, modificación de pago, ni sustitución del documento completo en este repositorio.

Si ya estaba completado, la operación es idempotente y no vuelve a escribir fecha/UID. La transacción vuelve a verificar las condiciones al reintentarse. El perfil también se lee dentro de ella, para detectar un bloqueo/cambio concurrente de permisos.

### Dos administradores a la vez

Las reglas pueden rechazar al perdedor con PERMISSION_DENIED antes de que el SDK rehaga la transacción, porque el estado anterior ya cambió. Se reprodujo ese comportamiento en Emulator.

Ante ese código o ABORTED, Android realiza una **relectura puntual de perfil y registro con el mismo SDK/sesión y Source.SERVER**. Solo si el perfil sigue autorizado y el traspaso ya tiene estado completado, UID no vacío y timestamp almacenado, devuelve ALREADY_COMPLETED. Si no, conserva el error. No repite una escritura a ciegas, no cambia de identidad ni utiliza IAM para eludir las reglas. El primer UID/timestamp permanecen intactos.

Un fallo de conexión no se convierte en éxito local ni en una escritura offline encolada por esta función. Si el servidor confirmó pero se perdió una respuesta, un resultado posterior del servidor puede resolver el estado; el cliente no afirma quién completó ni una hora inventada.

## 6. Reglas propuestas para revisión

Ver [diff contra lo desplegado](../../security/formalizacion-traspasos/proposed.diff) y [ruleset propuesto](../../security/formalizacion-traspasos/firestore.proposed.rules).

La única nueva autorización sobre registros existentes exige:

1. Sesión válida, cuenta ACTIVA y rol ADMIN/SUPER_ADMIN según las helpers ya existentes.
2. Documento existente, SALIDA COMPLETADO, CLISA → TOQEN.
3. Estado previo PENDIENTE o SIN_VERIFICAR; la ausencia del campo se interpreta como SIN_VERIFICAR. Nulo explícito o cualquier otro valor no habilitan la transición.
4. Auditoría previa vacía/ausente; no reemplazar confirmaciones existentes.
5. Estado posterior COMPLETADO, `formalizadoPor == request.auth.uid`, timestamp tipado y `formalizadoEn == request.time`.
6. `diff().affectedKeys().hasOnly(...)` con las tres claves de formalización y presencia posterior de las tres. Esto protege **también campos originales adicionales**, además de producto, cantidad, receptor, tienda, fecha y estadoPago.

Actualizar un completado, revertirlo, cambiar su auditoría o borrar el documento sigue denegado. No se habilita edición genérica de históricos ni cambio de roles.

Compatibilidad de creación:

- Los tres campos nuevos entran en la lista permitida de SALIDA.
- Si se envía cualquiera, se exigen los tres, con PENDIENTE y ambos valores de auditoría nulos. Un cliente no puede crear su traspaso ya formalizado.
- Se permite temporalmente la creación sin ninguno de esos campos para no romper clientes anteriores durante el despliegue. Esos registros se ven como Sin verificar, no se migran automáticamente. Exigirlos a todos los clientes podría evaluarse después de retirar las versiones antiguas.
- Se corrige la contradicción existente: `estadoPago` se añade tanto a obligatorios como a permitidos. Su validación Pagado/Pendiente se conserva. No se eliminan las validaciones de cantidades, tiendas ni descuentos atómicos de SALIDA.

### Protección necesaria contra reutilización de historial

Las helpers desplegadas de SALIDA/AJUSTE podían consultar un registro ya existente con getAfter y usarlo para justificar otro cambio de inventario. Abrir la actualización de formalización sin considerar ese camino permitiría intentar asociarle un nuevo descuento.

Se añade `!exists(registros/{registroId})` a las cuatro helpers de mutación de inventario: exige un registro **nuevo** en esa operación atómica. La prueba de formalización combinada con otro descuento sobre el registro histórico se rechaza completa, manteniendo stock/existencia y estado anterior. La SALIDA legítima con creación nueva sigue pasando para USER, ADMIN y SUPER_ADMIN.

Esto no adopta todas las restricciones de 4A.1 ni pretende resolver la seguridad de importaciones masivas. La contradicción previa del esquema AJUSTE, permisos administrativos amplios de creación y otras decisiones ya documentadas siguen pendientes fuera del alcance. Las permisos generales de tiendas/productos/catálogos no se ampliaron para formalizar.

## 7. Lecturas, listeners y recursos

No se llama a `collection("registros").get()` después de confirmar ni se reinicia el listener para refrescar la tarjeta. Se actualiza localmente el registro confirmado, y el listener existente recibe sus cambios. Los documentos con escrituras locales pendientes conservan la representación previamente confirmada, evitando un verde especulativo o que la tarjeta desaparezca mientras se escribe.

La transacción lee puntualmente perfil y registro; puede repetir lecturas por concurrencia. La resolución excepcional añade como máximo esas dos relecturas puntuales, además de los costes de autorización/reglas y notificación del listener. La escritura es de un solo documento. No se añadieron listeners ni consultas globales.

**Hallazgo de la implementación actual:** Lista de registros ya tenía un listener global, liberado mediante DisposableEffect, y no tiene paginación de servidor. Se conservó ese comportamiento para no cambiar búsqueda/filtros en esta tarea. No se afirma que el historial esté paginado u optimizado globalmente; ese consumo previo requiere otro trabajo si crece el volumen.

## 8. Pruebas realizadas y límites

- **208 pruebas unitarias**, cero fallos/errores/omitidas: incluyen las 200 anteriores y 8 de estados, inicialización, históricos, coherencia de roles/perfiles y condiciones de confirmación.
- **20 pruebas Android instrumentadas**, cero fallos/omitidas: 15 regresiones de importación/vista previa más 5 nuevas de indicadores, diálogo/cancelación, estado ocupado/error, navegación y ancho pequeño. Las 5 afectadas se repitieron tras el ajuste final de distribución.
- **33 pruebas de Firestore Emulator**, todas aprobadas, con proyecto `demo-clisa-formalizacion`, puerto local 8088 y fixtures ficticios. Incluyen creación SALIDA por los tres roles, compatibilidad antigua, transición de históricos, permisos/usuarios bloqueados, UID/timestamp, conservación de todos los campos e inventario, concurrencia, reversión, replay y transporte inaccesible.
- `assembleDebug`, suite unitaria y compilación de AndroidTest correctas. Las capturas se inspeccionaron visualmente.

Evidencias: [build-results.log](build-results.log), [android-tests.log](android-tests.log), [final-build.log](final-build.log), [emulator-results.txt](emulator-results.txt) y [test-results.json](test-results.json).

Las pruebas de reglas usan el SDK JS oficial sobre Emulator para ejercitar el contrato transaccional. Los tests Android usan hosts Compose aislados y callbacks de prueba, sin MainActivity, sesión Auth real ni documentos de producción. No se declara una verificación end-to-end con cuentas reales del proyecto.

La simulación de desconexión usa otro endpoint **local inaccesible**, con mock token de Emulator. Se comprobó que la transacción falla y el registro del emulador sigue pendiente. `disableNetwork` por sí solo no resultó una simulación fiable de fallo de transporte de esas transacciones JS; no se reportó como prueba de desconexión válida.

Reproducción:

```powershell
$env:JAVA_HOME = 'C:\Program Files\Android\Android Studio1\jbr'
$env:CLISA_EXCEL_SAMPLE = 'C:\Users\TUF\Documents\stock.production.lot123 - 2000.xlsx'
.\gradlew.bat :app:assembleDebug :app:testDebugUnitTest :app:assembleDebugAndroidTest --no-configuration-cache

.\security\formalizacion-traspasos\Test-LocalRules.ps1 -FirebaseCli 'C:\Users\TUF\AppData\Local\npm-cache\_npx\7750544ccf494d8b\node_modules\firebase-tools\lib\bin\firebase.js' `
    -JavaHome 'C:\Program Files\Android\Android Studio1\jbr'
```

Para UI, ejecutar `TransferFormalizationUiTest` en emulador aislado sin red. Las regresiones con el Excel usan el argumento `excelSample=/sdcard/Download/clisa-import-validation.xlsx`, con una copia local en esa ruta. No se crean usuarios de prueba ni registros reales.

## 9. Compatibilidad con Excel y stock

Formalizar solo registra la declaración del gerente sobre el sistema externo. **No prueba que un Excel exportado anteriormente ya contenga ese traspaso**. No se deduce ni repone stock, no se modifica fechaHora original y no se cambia estadoPago.

No se modificaron InventoryComparison, ExportTemporalReviewer, consultas de movimientos de Excel, completitud, planes Dry Run ni botón de sincronización. Las pruebas anteriores de importación siguen aprobadas. La integración contable futura con un corte de exportación confiable requiere un diseño separado; no se activa 4B.

## 10. Activación en producción — pendiente de autorización

No se ejecutó ninguno de estos pasos de publicación:

1. Revisar y aprobar el diff exacto, incluida la corrección de estadoPago, los campos iniciales opcionales para clientes antiguos y la protección contra replay.
2. Volver a inspeccionar el release inmediatamente antes del despliegue. Si su ruleset cambió respecto de la copia congelada, rebasar/revisar la propuesta y repetir pruebas; no sobrescribir cambios ajenos.
3. Ejecutar nuevamente las pruebas locales de reglas con el archivo aprobado. Mantener el ruleset anterior y su identificación para recuperación.
4. **Solo tras autorización explícita**, desde la raíz:

```powershell
node 'C:\Users\TUF\AppData\Local\npm-cache\_npx\7750544ccf494d8b\node_modules\firebase-tools\lib\bin\firebase.js' deploy --only firestore:rules --project empresa-clisa --config security/formalizacion-traspasos/firebase.json
```

Ese destino publica únicamente reglas de Firestore. No publica índices, Functions, datos, roles ni sincronización. El archivo de configuración también contiene el entorno Emulator; el flag de despliegue selecciona solo reglas.

5. Verificar que el release publicado corresponde al contenido aprobado, sin crear traspasos reales de prueba. Después distribuir el nuevo APK por el canal habitual.
6. Comprobar los roles existentes mediante el procedimiento de acceso autorizado; no asignarlos ni crear usuarios automáticamente. La primera confirmación real debe ser una acción deliberada de un ADMIN/SUPER_ADMIN sobre un traspaso que efectivamente haya formalizado.

No se requieren nuevas colecciones, migraciones, índices ni servicio administrativo privilegiado para esta transición limitada. Las reglas cliente sí deben estar desplegadas antes de usarla. USER no recibe esa autorización y no hay reversión en la app.

## 11. Confirmación final

No se modificó ningún documento, usuario, rol, stock, existencia o registro histórico de producción. Solo se descargaron metadatos/reglas autorizadas y se escribieron fixtures en los emuladores. No se desplegaron reglas/índices ni se ejecutaron traspasos reales. El siguiente paso de producción queda detenido hasta recibir autorización explícita.
