# Rechazo del Excel de 175 KB

Archivo recibido: `C:/Users/TUF/Documents/stock.production.lot123.xlsx`, 179937 bytes. Se preservó el original; se usó una copia como asset del APK de pruebas, sin incluirla en el APK principal.

El lector real reconoce 4043 filas, 366 productos, 348 productos válidos y 18 productos con incidencias críticas. Hay 35 incidencias: 20 de serie inválida, 8 de SKU duplicado, 6 de serie duplicada y 1 de recuento. No solicita mapeo. Estas incidencias no justifican rechazar todo el archivo; se deben apartar los productos afectados.

Se confirmó y corrigió una incompatibilidad del cliente: `checkConnection()` exigía siempre SAFE_PREVIEW y escrituras desactivadas, aunque Render ya usa AUTOMATIC_IMPORT. Ahora admite el modo automático habilitado con su capacidad declarada. El endpoint antiguo de preparación mantiene su restricción original y no permite subir archivos en modo de escritura. No se debilitan autenticación, comprobación de expediente ni SHA-256.

Se reemplazó el mensaje genérico de “solo revisión” por mensajes coherentes con el flujo automático. La carga distingue respuesta inválida, análisis inválido y huella distinta. No se registran tokens ni contenidos de las respuestas.

Verificación:

- 307 pruebas JVM aprobadas, incluidas compatibilidad de conexión y rechazo del flujo antiguo antes de subir bytes.
- 6 pruebas HTTP aprobadas, incluida carga del archivo exacto a través de multipart y Worker reales.
- Prueba de carga con Android JSON, TLS y multipart aprobada en emulador API 35 y teléfono Infinix X6850 Android 16. Usa servidor HTTPS aislado con respuesta de expediente controlada y los bytes exactos recibidos. No es prueba autenticada contra Render.
- APK compilado: `app/build/outputs/apk/debug/app-debug.apk`.
- Logs: `upload-runtime.log` y `upload-http.log`.

La incompatibilidad de conexión está corregida. No está demostrado que fuera la causa única del rechazo dentro de `uploadAutomatic`, que tiene su propia comprobación. Los registros disponibles de Render no muestran un POST de carga para los intentos revisados. Falta repetir desde la sesión real del usuario con la versión actual antes de cerrar T52/ALE-57. No se efectuaron sincronizaciones ni escrituras de inventario de producción.
