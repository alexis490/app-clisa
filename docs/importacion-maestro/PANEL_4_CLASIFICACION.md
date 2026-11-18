# Paso 4: clasificación de productos

Implementado siguiendo `Clasificación de productos con Firebase.png`.

Estado: terminada tras verificación.

- Título y subtítulo del mockup; pasos Seleccionar, Analizar, Revisar, Clasificar y Sincronizar, conservando los nombres corregidos por el usuario.
- Tarjeta de progreso con porcentaje y productos procesados, estados de reglas/IA/preparación y tarjetas Por reglas, Por IA y Pendientes.
- Iconos de destellos, producto, documento pendiente y llama dibujados en la app; botón inferior con borde amarillo/naranja, bloqueado hasta PREVIEW.
- El botón Sincronizar con Firebase abre el resumen del paso 5. La escritura sigue requiriendo la confirmación explícita posterior. Clasificar no modifica productos ni existencias.

Los contadores se guardan en el expediente del servidor y sobreviven a la reanudación. Por reglas y Por IA cuentan productos completos; Pendientes cuenta los procesados que aún requieren campos. Los productos apartados no se clasifican. El porcentaje usa productos válidos procesados; los productos pendientes también han sido procesados, por lo que 100% no implica que todos estén completos. Se muestra una advertencia cuando faltan datos.

Gemini solo se atribuye cuando aporta al menos un ID activo aceptado. Se conservan los metadatos válidos, las reglas, la descripción segura, los límites, los permisos y la confirmación existentes. La clasificación opera por lotes: reglas e IA pueden aparecer en progreso simultáneamente; no se inventa una fase global completada antes de terminar todos los lotes. Los expedientes antiguos sin datos de origen muestran — en el desglose, sin cifras inventadas.

Verificación:

- Compilación de la aplicación y pruebas: 314 pruebas JVM aprobadas.
- 32 pruebas Node de clasificación, informes y HTTP aprobadas.
- 14 pruebas Firebase Emulator aprobadas: contadores iniciales/finales, repetición sin duplicados, clasificación sin escribir inventario y confirmación posterior.
- 9 pruebas Android aprobadas: clasificación en progreso bloquea el botón, clasificación con pendientes permite abrir resumen, expedientes antiguos no inventan cifras; navegación integrada desde revisión y paneles 1/2.
- Capturas del emulador con datos ficticios de prueba: classification-progress.png y classification-ready.png.
- En la primera ejecución Android la prueba de navegación comprobaba el botón antes de finalizar el estado ocupado. Se corrigió la espera del test para verificar el final real de la operación.

Logs: classification-build.log, classification-node.log, classification-emulator.log y classification-final-ui.log.

Backend publicado en commit 5e28043bc9bcd8c3b8301eeddba6ab482d1f6e1a, árbol cc8d2b727862f00afbf0601b90dc5585bb72ef1d. Render dep-dbj7vjavcj2c73enkqbg live el 31/10/2026 a las 18:55:33 (Lima); health HTTP 200. Solo se publicaron automatic-import.mjs y automatic-classification.mjs. No se ejecutaron importaciones de prueba ni escrituras de inventario en producción.

APK: app/build/outputs/apk/debug/app-clisa-clasificacion.apk
SHA256: 40AE8ADBD172D365F7CEDB7A5ED6E2C8C6CDB3130472D49AC31D1F3F2832BD04

