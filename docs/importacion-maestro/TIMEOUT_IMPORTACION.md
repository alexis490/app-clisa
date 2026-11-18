# Tiempo de espera al importar

2026-10-29. Se recibió captura de las 16:20 (Lima) con timeout y el archivo de 175 KB seleccionado. No se usó ni actualizó Linear, según instrucción del usuario.

Evidencia remota: el servicio Render responde `200 {"status":"ok"}`; comprobación desde este equipo en 420 ms. Los registros consultados entre 16:15 y 16:24 no muestran solicitudes de carga ni errores de aplicación. La salud no se registra por diseño. Esto no permite atribuir el incidente al procesamiento del Excel ni confirmar qué conexión falló en ese teléfono.

Cambios del cliente Android:

- La conexión inicial tolera más tiempo de inicio del servicio y un único reintento para timeout, indisponibilidad o fallo de red.
- Las consultas GET protegidas permiten un único reintento ante esos fallos transitorios y vuelven a verificar la identidad de sesión.
- Los POST de carga, avance y confirmación no se repiten por timeout ni error de servicio. Se conserva únicamente el mecanismo previo de renovar token tras un 401.
- Durante la carga se muestra si está conectando, verificando permisos o enviando el archivo.
- Los errores de timeout distinguen conexión (no se envió Excel), autorización (no se envió Excel) y carga (resultado desconocido; sincronización no confirmada).
- Se elimina el aviso de progreso al terminar o fallar, para evitar mostrar una operación activa cuando ya se detuvo.

Verificación: 311 pruebas JVM sin fallos; APK principal y de prueba compilados; 2 pruebas Android en el emulador aprobadas. Una prueba usa los bytes exactos del archivo de 175 KB contra HTTPS aislado y recupera un 503 de salud. La otra usa TLS real del sistema contra Render, comprueba salud y rechazo sin token. No se inició sesión de administrador ni se subió inventario de producción. El teléfono no estaba conectado durante estas verificaciones y su instalación no se modificó.

Logs: `timeout-client-tests.log`, `timeout-apk-build.log` y `timeout-android-runtime.log`. APK: `app/build/outputs/apk/debug/app-clisa-timeout.apk` (copia del APK verificado).

Pendiente: repetir el intento desde la sesión y red reales del usuario. No se declara resuelto el timeout observado hasta esa comprobación.
