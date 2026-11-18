# Corrección del corte de la conexión de carga

2026-10-29. No se utilizó ni actualizó Linear.

Se reprodujo un defecto del servidor real de la aplicación en una prueba HTTP local: la configuración de socket cerraba conexiones a los 10 segundos aunque el presupuesto de la solicitud era de 30 segundos. Una carga del Excel recibido con una preparación controlada de 11 segundos falló a los 10.080 segundos (`fetch failed`). La prueba usa multipart, autenticación simulada y el lector Worker real, sin escribir inventario.

Se cambió el timeout de socket para respetar el presupuesto configurado de la solicitud. La misma carga termina con 200 después de corregirlo. Se conservan los límites de tamaño, concurrencia, autenticación y tiempo total de solicitud.

Además se registran inicio, final o interrupción de cada solicitud y una ruta categorizada. La carga distingue autorización, recepción de Excel, lectura y guardado de análisis. No se registran tokens, UID, nombres del archivo ni contenido de inventario. Esto permite localizar cortes que antes no emitían ningún evento de finalización.

Pruebas: 37 HTTP aprobadas. Regresión antes del cambio: `upload-socket-before.log`; después: `upload-socket-after.log`. Los nuevos registros se verificaron para no incluir token, nombre de archivo ni parámetros de consulta. Se verificó también el registro de una conexión interrumpida durante autorización.

Commit remoto: `2729016c65ff28b65bcb4797f7a5944dcb342980`. Solo se publicaron `tools/inventory-sync/server.mjs` y `tools/inventory-sync/http-app.mjs`, conservando los demás archivos remotos y los cambios locales del usuario. Configuración, credenciales, Gemini y permiso de sincronización no cambiaron.

Despliegue Render `dep-dbhrst942hec73bu83d0`: live, finalizado el 2026-10-29 a las 16:46:33 (Lima). Salud remota posterior: 200, `status: ok`, 427 ms.

Verificación remota posterior al reintento solicitado: el servidor recibió un POST autenticado de `excel-upload` a las 16:47:56 y finalizó a las 16:48:11 con estado 200, fase `saving-analysis` y duración 15675 ms. Luego registró múltiples POST de `excel-job` con estado 200, varios entre 10 y 11.5 segundos. Estas duraciones exceden el corte de socket anterior y confirman que el proceso real ya supera ese punto. Se verifica carga aceptada y avances posteriores; no se certifica todavía el resultado completo del análisis o una sincronización de inventario. Los registros omiten el nombre y la huella del archivo, de modo que no identifican sus bytes por sí solos.

No se ejecutaron importaciones de prueba sobre inventario de producción ni se confirmó una sincronización desde las herramientas del agente.
