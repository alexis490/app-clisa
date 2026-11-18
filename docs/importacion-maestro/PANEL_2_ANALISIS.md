# Panel 2: Analizando Excel

Implementado el 2026-10-29 según el mockup recibido. No se utilizó Linear.

La cabecera dice **Analizando Excel**. Incluye el indicador del segundo paso, tarjeta del archivo, seis comprobaciones con barras, aviso de inventario sin modificaciones y botón Cancelar. Se ajustaron las alturas para mostrar las seis filas completas en el emulador utilizado.

Corrección posterior solicitada por el usuario: el indicador compartido de los paneles Seleccionar, Analizar y Revisar usa exactamente **Seleccionar → Analizar → Revisar → Clasificar → Sincronizar**. La indicación textual prevalece sobre los rótulos anteriores de las imágenes. APK con esta corrección: `app/build/outputs/apk/debug/app-clisa-pasos.apk`. Compilación aprobada y dos pruebas de análisis/navegación aprobadas; logs `step-labels-build.log` y `step-labels-ui.log`. El mockup de Revisar análisis recién recibido queda como referencia para la implementación de su contenido.

Comprobaciones mostradas:

1. Lectura del archivo: filas realmente leídas.
2. Validación de origen: productos sin incidencias críticas de origen.
3. Consulta del inventario: productos comprobados frente a los que requieren consulta; no se presenta este número como el total de productos de Firestore.
4. Comparación: productos procesados frente al total del archivo.
5. Series: series revisadas o apartadas de forma segura frente al total de series.
6. Duplicados y conflictos: productos comprobados y conteos confirmados de incidencias y apartados.

Los datos se guardan junto al avance del expediente y vuelven del servidor. Las tareas realizadas dentro de cada producto avanzan por lotes y pueden compartir porcentaje. No se simulan avances independientes. Antes de recibir el resultado inicial, la lectura usa indicador indeterminado y las tareas posteriores permanecen pendientes.

Las nuevas cargas solicitan detenerse antes de clasificar. Al completar la comparación, el servidor guarda `REVIEW_READY`; Android pasa automáticamente a **Revisar análisis** y deja de enviar avances. No se ejecuta Gemini ni se consultan catálogos de clasificación en esta etapa. Tampoco se habilita la confirmación de sincronización: el servidor la rechaza desde ese estado. Se mantienen las reglas de validación, comparación, apartados y protección de inventario anteriores.

Los procesos de clientes anteriores conservan su comportamiento para evitar interrumpir expedientes existentes. El nuevo APK envía explícitamente la opción de revisión previa. La reorganización se aplica a las nuevas cargas que lo utilizan.

Cancelar detiene la solicitud del cliente y los avances siguientes y cierra el panel. Un lote ya recibido por el servidor puede terminar de preparar su borrador; no escribe inventario. Un expediente conocido se conserva para reanudarlo.

El panel 3 tiene solo un contenedor provisional y resultados básicos de lectura. Su diseño definitivo queda pendiente del mockup que el usuario proporcionará. No se han añadido acciones de clasificación ni sincronización a ese contenedor.

Verificación:

- 314 pruebas JVM aprobadas, incluidas la opción enviada para detenerse antes de clasificar y el rechazo de estados de revisión incoherentes.
- 46 pruebas HTTP/formato aprobadas.
- 13 pruebas con Firebase Emulator aprobadas, incluidas pausa recuperable, métricas, ausencia de Gemini/catálogos y de cambios de inventario en la etapa de análisis, rechazo de confirmación y regresiones del flujo anterior.
- 11 pruebas Android aprobadas en el emulador, incluida selección de archivo y navegación automática; dos pruebas del panel volvieron a pasar tras el ajuste visual final.
- Capturas de pruebas: `excel-analysis-progress.png` y `excel-analysis-review-ready.png`. Sus cifras corresponden a un expediente de prueba, no a un resultado de producción.
- Logs: `analysis-panel-android-build.log`, `analysis-panel-http.log`, `analysis-panel-emulator.log`, `analysis-panel-ui.log` y `analysis-panel-final-ui.log`.

Backend: commit `435bccf1f61173e5efec95176582139aadc01ca1`, despliegue `dep-dbhtfaavcj2c73f7ec1g` live el 2026-10-29 a las 18:33:33 (Lima). Salud posterior: 200, `status: ok`, 725 ms. Se conservaron credenciales y configuración existentes. No se realizaron importaciones ni sincronizaciones de prueba sobre inventario de producción.

APK final: `app/build/outputs/apk/debug/app-clisa-analisis.apk`.
