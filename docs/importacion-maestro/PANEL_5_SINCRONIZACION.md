# Paso 5 y confirmación de actualización de stock

Referencias: Sincronización de productos en Firebase.png y Confirmación de actualización de stock.png.

- Panel Sincronizar productos y subtítulo Actualizar inventario en Firebase; pasos con los nombres corregidos: Seleccionar, Analizar, Revisar, Clasificar y Sincronizar.
- Tarjetas Firebase/Render, resumen de productos válidos, actualizaciones, nuevos y omitidos; aviso de exclusión de productos con incidencias críticas y progreso de sincronización.
- Accesos conservados al detalle de cambios, clasificación e incidencias; resumen de clasificación completa y pendiente disponible al desplazar el panel.
- Botones Actualizar stock y Cancelar; confirmación con borde azul, título, descripción, cierre, cancelar y acción de actualización.

La conexión no está simulada: al entrar en el paso 5 se consulta el expediente mediante GET autenticado a Render, que lee Firestore y valida al propietario. Los dos indicadores aparecen conectados solo después de esa respuesta. Ante un fallo se bloquea Actualizar stock y se permite reintentar la consulta. Esto verifica acceso remoto, no constituye una escritura de prueba.

La vista previa comienza en 0%, con datos preparados y las escrituras pendientes. Abrir o cancelar el diálogo no envía confirm. Solo la acción afirmativa envía POST confirm; el botón permanece bloqueado mientras avanza el proceso. El servidor mantiene todas las validaciones y confirmaciones existentes. Las series ausentes de productos válidos pasan a cero; los productos fuera del Excel y los productos apartados se conservan, tal como advierte el diálogo.

El porcentaje procede del cursor persistido del servidor. Las operaciones de producto, existencias y recibos se realizan por producto, por eso sus indicadores permanecen en progreso simultáneamente: no se inventan fases secuenciales terminadas. Al finalizar se conserva la pantalla de resultados existente, que distingue finalización completa y parcial. Cerrar seguimiento no revierte escrituras ya confirmadas; un expediente inconcluso se puede reanudar.

No se modificó el backend ni fue necesario otro despliegue en Render. No se usó Linear ni se ejecutaron escrituras de prueba sobre inventario de producción. Las capturas corresponden a datos de prueba aislados.

Verificación y evidencias:

- Compilación y 314 pruebas JVM aprobadas: sync-panel-final-build.log.
- Pruebas Android de conexión fallida, bloqueo y reintento, cancelar/cerrar sin confirmar, un único POST confirm, progreso 68% real del fixture y llegada a resultados.
- Regresión de revisión, clasificación y pantallas anteriores: sync-panel-final-ui.log.
- Capturas revisadas: sync-ready.png, sync-confirmation.png y sync-progress.png.

APK: app/build/outputs/apk/debug/app-clisa-sincronizacion.apk
SHA256: 49551F03FD588BF851635435F8A1F33C14733718BB1994439E3375A07BBD9924

Estado final: terminada. Las 17 pruebas Android de la versión final aprobaron (66,559 s); 314 pruebas JVM, cero fallos. APK y capturas corresponden a esta versión verificada.
