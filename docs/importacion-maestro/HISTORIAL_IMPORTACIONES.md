# Historial automático de importaciones

Implementado, verificado y publicado. No se ejecutaron importaciones reales ni se modificó inventario de producción durante las pruebas.

## Flujo y datos

La app genera un UUID al iniciar la carga para análisis. Si una respuesta se pierde, conserva el ID en memoria y en preferencias del usuario junto con el hash y nombre del archivo. Al volver a elegir el mismo archivo después de una carga sin respuesta, reutiliza ese ID. Un archivo distinto inicia otro expediente. Las cargas con columnas por mapear mantienen el mismo ID hasta crear el expediente.

El backend acepta la repetición solo para el mismo propietario, archivo y contenido de análisis. Un expediente que ya avanzó no se reinicia. Una preparación STAGING interrumpida se puede reconstruir; las transacciones comprueban el propietario del bloqueo para impedir escrituras de una preparación anterior.

Al confirmar la sincronización se captura el correo del registro actual de Firebase Authentication obtenido con getUser(uid). El UID procede del token verificado, y se validan también sus claims vigentes y perfil activo administrativo. El contrato del cliente no determina el UID ni el correo.

Al terminar los recibos de todos los productos, una única transacción cierra el expediente y crea historial_importaciones/{UUID}. Firestore crea implícitamente la colección al crear ese primer documento. Si guardar el historial falla, el expediente no se anuncia como terminado y puede reanudarse sin repetir las escrituras de inventario que ya tienen recibo.

Cada documento histórico contiene exactamente:

- idImportacion: mismo UUID del expediente, desde el inicio hasta los reintentos.
- archivo: nombre del Excel aceptado.
- fecha: Firestore Timestamp generado en el servidor al finalizar. Coincide con el cierre del expediente.
- usuarioUid: UID autenticado propietario de la operación.
- usuario: correo obtenido de Firebase Authentication y capturado al confirmar.
- destino: Firebase Firestore.
- estado: COMPLETADO, COMPLETADO_CON_OBSERVACIONES o FALLIDO.

COMPLETADO corresponde a un resultado sin errores, apartados ni pendientes. COMPLETADO_CON_OBSERVACIONES contempla incidencias, productos apartados, clasificación pendiente o fallos de algunos productos cuando otros sí se aplicaron. FALLIDO corresponde a errores de sincronización sin ningún producto aplicado. Los errores transitorios de transporte no inventan un estado terminal: el expediente continúa pendiente de reanudación.

No se guardan bytes del Excel, productos, existencias, propuestas ni tokens en esta colección. Los reintentos no actualizan un registro histórico existente ni su fecha. Los expedientes antiguos terminados sin historial no se rellenan con metadatos inventados.

## Android

El endpoint protegido del expediente devuelve history solo después del cierre. El cliente comprueba que ID, archivo y usuarioUid corresponden a la importación y sesión actuales, así como destino, fecha y estado. La fecha viaja como ISO UTC y se muestra con el huso horario del dispositivo. El recuadro muestra archivo, fecha, correo, destino e ID guardados. El resultado distingue éxito, observaciones y fallo total.

Exportar resumen permanece deshabilitado, tal como se solicitó anteriormente: este trabajo implementa únicamente el registro y visualización de información de la importación.

## Seguridad

La única nueva regla explícita deniega lectura y escritura directa, incluidas subcolecciones, en historial_importaciones para todos los clientes. Android consulta su registro a través del backend autorizado, que verifica propiedad del expediente. Firebase Console utiliza los permisos IAM del proyecto.

El SDK de servidor opera con su cuenta de servicio, por lo que el control de token/rol/propiedad del backend es obligatorio y se mantiene. Referencia oficial: https://firebase.google.com/docs/firestore/security/rules-conditions

La propuesta se generó desde las reglas de producción recién leídas y se comprobó que, retirando el único bloque nuevo, el archivo completo permaneciera idéntico. Antes de publicar se volvió a comprobar que la versión desplegada no hubiese cambiado. Después se descargaron las reglas publicadas y se verificó su igualdad con la propuesta.

Reglas previas: projects/empresa-clisa/rulesets/27e24b85-2698-435c-9949-e5daa2a4abce.
Reglas publicadas: projects/empresa-clisa/rulesets/dc7a7cc5-8551-4757-b03f-7a47170f3321.
SHA256 del archivo publicado: 9550878bf7641265740723a678387498f34cafdd2f3a4d7caa746e5df26ddbaf.

## Archivos implementados

Android:

- app/src/main/java/com/example/app_clisa/data/backend/ClisaBackendClient.kt: ID de carga y validación del historial recibido.
- app/src/main/java/com/example/app_clisa/ui/products/AutomaticExcelImportAction.kt: persistencia de IDs de carga y recuperación de reintentos.
- app/src/main/java/com/example/app_clisa/ui/products/InventoryImportDesign.kt: datos registrados y estados reales en el panel final.

Backend:

- tools/inventory-sync/import-history.mjs: esquema mínimo, estados y representación de transporte del Timestamp.
- tools/inventory-sync/automatic-import.mjs: carga idempotente, identidad capturada al confirmar y cierre/historial atómicos.
- tools/inventory-sync/http-app.mjs: correo autorizado de Auth e importId validado en el contrato.

Reglas y pruebas:

- security/historial-importaciones/firestore.rules y history-denial.fragment: nueva denegación aislada, conservando todas las reglas existentes.
- security/historial-importaciones/firebase.json: configuración de pruebas y despliegue de reglas.
- tools/inventory-sync/test/import-history-emulator.test.mjs: registro, Timestamp, campos exactos, tres estados, propietario, reintentos, concurrencia, interrupción STAGING y fallo de guardado.
- tools/inventory-sync/test/import-history-rules.test.mjs: denegación de CRUD y subcolecciones para USER, ADMIN, SUPER_ADMIN y usuarios sin sesión; regresión de inventario.
- tools/inventory-sync/test/automatic-emulator.test.mjs: identidad de prueba y limpieza del nuevo historial, manteniendo los escenarios existentes.
- tools/inventory-sync/test/automatic-http.test.mjs: intento de suplantación por contrato y email obsoleto del token, sin ruta de creación arbitraria de historial.
- app/src/test/java/com/example/app_clisa/AutomaticBackendClientTest.kt: validación de dueño, ID, fecha y reintentos de carga.
- app/src/androidTest/java/com/example/app_clisa/ImportHistoryPanelTest.kt y assets/history-result.json: visualización de una respuesta realmente registrada en Firestore Emulator y sus estados.
- app/src/androidTest/java/com/example/app_clisa/ExcelFormatGateTest.kt: el servidor simulado devuelve el UUID elegido por Android.

## Verificación

- 316 pruebas JVM aprobadas y APK generado: history-release-build.log.
- 58 pruebas Node/HTTP aprobadas: history-node.log.
- 28 pruebas con Firebase Auth/Firestore Emulator aprobadas: history-final-all-emulator.log. Incluyen los 14 escenarios anteriores de inventario y las nuevas pruebas de historial y seguridad.
- 20 pruebas Android aprobadas en emulator-5554: history-release-ui.log. Incluyen visualización del registro real del emulador, ID estable, formato, confirmación, navegación y pantallas existentes.
- Captura revisada: history-completed.png. Sus datos son de prueba, no información de producción.
- Documento y respuesta comprobados en el emulador: history-emulator-result.json, conservado únicamente como evidencia local/de tests.
- Regla desplegada y leída nuevamente: security/historial-importaciones/post-deploy/inspection.json.

Al preparar las pruebas se corrigieron una configuración incompleta del emulador y la cola del servidor simulado de Android. También se ajustó la prueba de diagnóstico para comprobar el contador de análisis en la etapa correcta. Los resultados anteriores fallidos no se cuentan como verificaciones aprobadas; los logs finales anteriores corresponden a las suites completas aprobadas.

Backend final: commit 5c8ad48d6c0e92fb13ea0567982562cc94ad733a, árbol e57ebf9db15279e218b1747bb057533f5ec2b31a. Render dep-dbjv8lrbc2fs73dumke0, live el 01/11/2026 a las 21:25:03 (Lima). Health verificado HTTP 200. Se publicaron solo los archivos de backend y reglas de este cambio, sin datos de los Excel ni activos de pruebas.

## Verificar en Firebase Console

1. Abrir https://console.firebase.google.com/project/empresa-clisa/firestore y entrar en la pestaña Datos.
2. Después de completar una importación autorizada, abrir historial_importaciones y el documento con el mismo UUID mostrado por la app.
3. Comprobar los siete campos anteriores. fecha debe tener tipo Timestamp, no texto. El correo y UID deben corresponder al usuario que confirmó la operación.
4. Reanudar o reintentar esa misma operación no debe añadir otro documento ni cambiar fecha. Una nueva importación deliberada sí tendrá un UUID distinto.

La colección puede no aparecer aún en producción porque se crea con la primera importación finalizada bajo esta versión. No se insertó ningún registro ficticio ni se confirmó una importación de producción para probarla.

APK: app/build/outputs/apk/debug/app-clisa-historial.apk
SHA256: DBD648C9F80F406F71DA10933E9E528455730D8867259973C289F65CA30A7588

Actualización: Exportar resumen ya está implementado como XLSX local de cuatro hojas. Ver EXPORTAR_RESUMEN.md para la versión, pruebas y archivos correspondientes.
