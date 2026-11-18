# Exportar resumen de importación a Excel

Estado: implementado, verificado y desplegado.

## Comportamiento

El botón verde conserva Excel a la izquierda, texto centrado, descarga a la derecha, esquinas redondeadas y altura de 48 dp. Durante la preparación muestra indicador de carga y bloquea otra exportación. No se cambiaron las demás tarjetas ni la navegación, salvo cancelar la tarea de exportación cuando se cierra su flujo.

El Excel se genera localmente en Android en segundo plano, sin dependencias nuevas. Se reutilizan ZIP y APIs estándar de Java/Kotlin, siguiendo el enfoque de lectura/escritura local existente. El selector ACTION_CREATE_DOCUMENT utiliza el tipo application/vnd.openxmlformats-officedocument.spreadsheetml.sheet. El nombre CLISA_Importacion_YYYY-MM-DD_UUID.xlsx usa la fecha de exportación del dispositivo y un UUID nuevo por archivo.

Primero se prepara un archivo temporal válido. Después el usuario elige dónde guardarlo. El mensaje Resumen exportado correctamente aparece únicamente después de copiar y cerrar correctamente el archivo de destino. Cancelar el selector no altera la importación. Un error de red, generación, espacio o proveedor muestra un mensaje y permite reintentar. Se eliminan los temporales de la app al terminar o cancelar.

## Cuatro hojas

1. Resumen general: identificador, fecha/hora con huso, archivo, usuario, estado, productos procesados/nuevos/actualizados, series actualizadas/en cero, productos omitidos, errores y destino. Incluye notas de alcance para distinguir productos, incidencias y operaciones.
2. Productos actualizados: código, nombre, tienda, stock anterior/nuevo, diferencia, tipo y estado. El detalle incluye nuevos, actualizados, sin cambios, omitidos y fallidos, identificados por su estado. No presenta omitidos o fallidos como actualizaciones aplicadas.
3. Incidencias omitidas: hoja/fila originales, producto, serie, tipo, descripción y motivo. Se deduplican por hoja, fila, código, serie y tipo para no repetir el mismo diagnóstico conservado tanto en el producto como en la colección de incidencias.
4. Resultados de sincronización: colección, identificador de registro u operación, tipo, estado y error o exclusión. El recibo APPLIED confirma todas las operaciones de la transacción atómica del producto. Los planes fallidos mantienen estado Fallido, no Completado. Una exclusión sin operaciones de inventario referencia su recibo del expediente. Los productos sin cambios quedan identificados como SIN_CAMBIOS.

Stock anterior procede de la comparación archivada, no del inventario actual. Stock nuevo se muestra únicamente cuando existe recibo aplicado y un valor conocido. Para productos nuevos, el stock anterior figura como No existía y la diferencia como No aplica. No se sustituye un valor ausente por cero. La tienda figura como No disponible porque el flujo actual no conserva una tienda por producto. Los expedientes sin historial aprovechan su fecha de cierre y correo archivados si están disponibles; los datos restantes quedan como No disponible.

La tarjeta Incidencias omitidas ya representa productos apartados. El Excel mantiene ese valor y explica su unidad; no lo confunde con el número de diagnósticos o de operaciones. Los datos ausentes y los huecos de evidencia se señalan. Cuando una hoja de detalle no tiene registros disponibles conserva únicamente sus encabezados.

## Backend y seguridad

Nuevo endpoint GET /v1/admin/excel-imports/:id/export?section=products|issues&offset=N.

Reutiliza la validación vigente de token revocado, claims actuales, rol administrativo, perfil activo y propietario del expediente. Requiere importación terminada y confirmada. No admite POST de exportación ni crea pantallas de incidencias.

Lee únicamente el expediente, historial, planes, diagnósticos y recibos guardados. No ejecuta advance, confirm, clasificación ni consultas al inventario actual. No escribe en Firestore ni adquiere bloqueos de escritura. La prueba de emulador compara updateTime de inventario, historial y expediente antes y después de exportar.

Los datos se descargan por páginas, hasta 50 productos o 10 bloques de diagnósticos y con respuesta acotada a 3 MB de detalle. Android continúa por cursores crecientes y conserva una instantánea coherente del resultado. Los límites de solicitudes existentes permanecen activos; una respuesta 429 puede esperar y reanudar la descarga del resumen.

Se proyectan solo campos necesarios. No se copian objetos completos del backend al archivo, campos de propuesta, contraseñas, tokens, claves, cabeceras HTTP ni stacks de errores. El texto del usuario se escribe siempre como texto literal, no como fórmula. Se escapan XML y controles inválidos. Los enteros que exceden la precisión de 15 dígitos de Excel se conservan como texto exacto. Los límites de celdas/filas de Excel generan un error claro, sin guardar un archivo truncado.

No se modificaron reglas de Firestore ni permisos de almacenamiento Android. No se copiaron bytes del Excel original al resumen ni se ejecutaron importaciones de producción durante las pruebas.

## Archivos

Android:

- app/src/main/java/com/example/app_clisa/data/excel/ImportSummaryWorkbook.kt: escritor OOXML local por filas, cuatro hojas, valores reales, estilos y límites.
- app/src/main/java/com/example/app_clisa/data/backend/ImportSummaryExporter.kt: consulta paginada de evidencia y generación temporal.
- app/src/main/java/com/example/app_clisa/data/backend/ClisaBackendClient.kt: endpoint GET protegido y validación del contrato.
- app/src/main/java/com/example/app_clisa/ui/products/AutomaticExcelImportAction.kt: selector de documentos, exclusión mutua, progreso, guardado y mensajes.
- app/src/main/java/com/example/app_clisa/ui/products/InventoryImportDesign.kt: habilitación y estado del botón actual.

Backend:

- tools/inventory-sync/import-export.mjs: proyección segura de resultados a partir de planes y recibos.
- tools/inventory-sync/automatic-import.mjs: páginas de solo lectura, metadatos guardados y detección de evidencia faltante.
- tools/inventory-sync/http-app.mjs: ruta GET con la autorización existente.

Pruebas:

- ImportSummaryWorkbookTest.kt y src/test/resources/export-fixture.json.
- ImportSummaryExportUiTest.kt y src/androidTest/assets/export-fixture.json.
- ImportHistoryPanelTest.kt y AutomaticImportScreensTest.kt actualizados para el botón habilitado.
- tools/inventory-sync/test/import-export.test.mjs y import-export-emulator.test.mjs.
- tools/inventory-sync/test/automatic-http.test.mjs: protección y método GET.

## Verificación

- 320 pruebas JVM aprobadas: export-release-build.log. Incluyen cuatro hojas, datos reales de emulador, deduplicación, unidades, valores desconocidos, texto literal, ausencia de secretos y límites.
- 45 pruebas Node/HTTP aprobadas: export-node.log.
- 21 pruebas de Firebase Emulator aprobadas: export-emulator.log. Incluyen exportación, regresión de inventario y reglas existentes.
- Las dos pruebas de exportación del emulador se repitieron tras añadir los metadatos archivados de expedientes sin historial: export-final-emulator.log.
- 24 pruebas Android aprobadas: export-android-ui.log, con navegación, historial, formato, sincronización y exportación.
- Las cuatro pruebas de exportación Android volvieron a pasar en el APK final: export-release-ui.log. Verifican el intent de creación de documento, tipo MIME, nombre único, bloqueo de múltiples exportaciones, cancelación, éxito real y errores de red/proveedor.
- Archivo guardado por Android: CLISA_export_verificado_android.xlsx. Se abrió con SheetJS y OpenPyXL: cuatro hojas, sin fórmulas, con 20/5/2/8 filas respectivamente. export-independent-verification.json conserva la comprobación. Son datos aislados del emulador, no una exportación de producción.

Backend final: commit ad29c481d329d2d7dff29540c1f5615b045f46cf, árbol 0ccd93a9d793073ce0de6fffb4c0f5c6c7ca9d2c. Render dep-dbk04geq1p3s73b8kfpg live el 01/11/2026 a las 22:24:33 (Lima). Health HTTP 200.

APK: app/build/outputs/apk/debug/app-clisa-exportar-resumen.apk
SHA256: D4F41D4D2E2CB39CD30A4E76A35563E89B8788DC13C91DFFBEF3A05E2CBB5ACC
