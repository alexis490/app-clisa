# Conexión Android → Render — CLISA

25 de octubre de 2026, America/Lima.

Implementada contra `https://app-clisa.onrender.com/`, conservando el lector, comparación, clasificación, revisión, exportación y bloqueos de inventario existentes. No se crearon pantallas/etapas ni rutas nuevas en Node. La selección permite comprobar conexión antes de elegir Excel o descargar inventario; el resumen añade la preparación de una propuesta sin escrituras, con el mismo estilo.

## Archivos creados y modificados

| Archivo relativo a la raíz | Cambio |
| --- | --- |
| `app/build.gradle.kts` | URL HTTPS en BuildConfig; OkHttp 5.5.1; dependencias TLS/JSON solo de pruebas. |
| `app/src/main/AndroidManifest.xml` | INTERNET explícito y tráfico sin cifrar deshabilitado. |
| `app/src/main/java/com/example/app_clisa/data/backend/BackendSession.kt` | Adaptador FirebaseAuth para UID, ID token y filtro inicial de claims administrativos. |
| `app/src/main/java/com/example/app_clisa/data/backend/ClisaBackendClient.kt` | Cliente HTTPS, contratos de respuesta segura, multipart, cancelación, límites y errores. |
| `app/src/main/java/com/example/app_clisa/data/backend/RenderImportRepository.kt` | Reutiliza los modelos/contrato existentes y lee la URI original a caché privada temporal. |
| `app/src/main/java/com/example/app_clisa/ui/products/RenderImportActions.kt` | Acciones explícitas en el resumen, progreso, cancelación y mensajes seguros. |
| `app/src/main/java/com/example/app_clisa/ui/products/ImportFinalScreen.kt` | Inserta esas acciones en la tarjeta existente; exportación y ejecución bloqueada se conservan. |
| `app/src/main/java/com/example/app_clisa/ui/products/ExcelImportAction.kt`, `ImportExcelScreens.kt` | Slot de conexión en la selección, independiente del archivo y la comparación. |
| `app/src/test/java/com/example/app_clisa/ClisaBackendClientTest.kt` | 18 pruebas JVM con servidores TLS locales y certificado de prueba. |
| `app/src/androidTest/java/com/example/app_clisa/RenderConnectionUiTest.kt` | Cuatro pruebas de interacción, conexión sin archivo, restricciones y cancelación. |
| `app/src/androidTest/java/com/example/app_clisa/ImportExcelScreensTest.kt` | Comprueba que el coordinador real permite verificar conexión sin archivo ni comparación. |
| `app/src/androidTest/java/com/example/app_clisa/RenderLiveConnectionTest.kt` | Prueba optativa de TLS real contra salud y rechazo sin token de Render. |
| `tools/inventory-sync/test/http-emulator.test.mjs` | Añade aceptación del multipart exacto generado por Android y conservación de versiones nativas. |
| `docs/importacion-paso4b-final/android-multipart.json` | Fixture ficticio de interoperabilidad; no contiene ID token ni datos reales de inventario. |

No se modificó el código de autenticación/login existente, roles, Registrar y Escanear, Detalle de producto, reglas, motor de stock ni configuración del servicio desplegado.

## Endpoints utilizados

| Solicitud | Uso |
| --- | --- |
| `GET /health` | Activación/comprobación pública, sin token. |
| `GET /v1/admin/status` | Comprueba acceso administrativo y modo `SAFE_PREVIEW`, con escrituras deshabilitadas. |
| `POST /v1/admin/imports/prepare` | Envía dos archivos multipart: `contract`/`contract.json` y `excel`/`source.xlsx`. Solo prepara una propuesta. |

No hay llamadas a ejecución, sincronización, recuperación, cambios de roles o aplicación de clasificación. No se añadió Gemini.

## Sesión y seguridad

- Se obtiene el ID token mediante `FirebaseAuth.currentUser.getIdToken(...)` y se envía únicamente como `Authorization: Bearer`. No se usan tokens de acceso de Google como sustituto ni credenciales administrativas en Android.
- UID y sesión se comprueban antes/después de solicitudes; si cambia el usuario, no se reutiliza su respuesta ni se reenvía una petición con la identidad nueva. Los tokens no se copian a archivos o logs; FirebaseAuth conserva su gestión normal de sesión.
- El filtro cliente exige claims ADMIN/SUPER_ADMIN completos. Es solo una comprobación anticipada: Node sigue verificando token, revocación, Auth actual y perfil ACTIVO. USER/cuentas bloqueadas no obtienen autorización por enviar un rol en el cuerpo.
- Un 401 provoca como máximo una renovación forzada del token y un nuevo intento. Un segundo 401 pide verificar la sesión. No se reintentan automáticamente 403, 429, fallos de red ni preparación por errores del servicio.
- HTTPS obligatorio, certificados y hostname verificados mediante confianza normal del sistema. No hay trust-all, excepción de certificado, bypass de hostname ni downgrade a HTTP. Se impiden redirecciones para no reenviar Bearer a otro destino.
- No se consulta nuevamente el inventario ni se crean listeners. El cliente añade cero lecturas directas de Firestore; el backend relee el perfil autorizado al verificar status y al preparar.

## Preparación y recursos

La comprobación pública inicial permite despertar Render sin enviar credenciales. Después se valida el acceso antes de subir el Excel. Límites: conexión 15 s, lectura pública 90 s/call 100 s, lectura protegida 40 s/call 65 s, escritura HTTP 30 s y plazo total de la acción 180 s. El usuario puede cancelar. No se comprueba el servidor automáticamente al abrir o navegar por las pantallas.

La URI original se vuelve a leer únicamente para enviar los mismos bytes; no se reimplementa ni vuelve a ejecutar el lector/comparador. La copia está limitada a 25 MiB y se elimina de caché en `finally`. Su SHA-256 debe coincidir con el del análisis. Contrato/respuesta se limitan a 8 MiB y se controla también el tamaño durante la lectura de respuestas sin longitud declarada.

Solo se ofrece preparar cuando hay fuente y no hay errores de lectura conocidos. La preparación no resuelve las 567 incidencias de la muestra real. La exportación local continúa disponible aunque Render falle. Las aprobaciones de clasificación y los expedientes existentes no se alteran.

La respuesta remota debe mantener `inventoryWritesEnabled=false`, `authorization=false`, plan no ejecutable, política `EXCEL_REFERENCE_V1`, SHA, baseline, UID y plan de origen correspondientes. Se rechazan tipos de operación desconocidos o eliminaciones. Solo se presenta un resumen en memoria; no se aplican cantidades ni se sustituye el plan local por uno remoto. La propuesta no se persiste ni se convierte en una ejecución autorizada.

## Verificación realizada

- APK y APK de instrumentación: compilación correcta.
- **301 pruebas JVM aprobadas, 0 fallos, 0 omitidas**: 283 existentes y 18 del cliente HTTPS. Incluyen Bearer, renovación única, USER/claims incompletos, 403 sin reintento, cambio de sesión, redirecciones, SHA cambiado, respuestas inseguras, límites, timeout y cancelación durante lectura.
- **164 pruebas Node aprobadas en Firebase Emulator, 0 fallos**. La prueba nueva usa los bytes multipart producidos por OkHttp Android con un XLSX ficticio realmente leído por el flujo Kotlin. Node verifica un ID token de Auth Emulator, acepta el formato y prepara sin crear jobs ni cambiar versiones de productos, existencias o registros. Se mantiene toda la regresión 4B y de autorización.
- **32 pruebas de interfaz distintas aprobadas** con red del AVD desactivada: suite completa de 31 y repetición final de las 10 afectadas, incluyendo la prueba adicional de conexión sin archivo. Incluyen el flujo existente con el Excel real, acciones nuevas, clasificación y formalización conservadas.
- **Una prueba real aprobada desde AVD API 35 hacia Render**, usando el cliente Android y TLS del sistema: `/health` correcto y `/v1/admin/status` sin token con 401/UNAUTHENTICATED. No se subió ningún Excel ni se usó una cuenta real en esta prueba.
- Un primer intento real tras reactivar la red del AVD falló por conexión. Se verificó conectividad y se repitió solo la prueba pública, que pasó sin cambiar TLS. También se corrigieron una firma de prueba JUnit y una aserción de texto; no hay fallos funcionales pendientes en la suite final.
- Evidencia: `render-android-build.log`, `render-android-final-unit.log`, `render-android-final-apk.log`, `render-android-node.log`, `render-android-ui.log`, `render-android-final-ui.log` y `render-android-live.log` en esta carpeta.

No se obtuvo ni extrajo el ID token de una cuenta real del usuario para las pruebas. **La aceptación autenticada de tu cuenta en el Render desplegado requiere comprobarla desde la app iniciando sesión normalmente**; no se afirma que IAM/permisos reales estén verificados por los tests de Emulator. No envíes tokens ni el JSON de la cuenta de servicio por chat.

## Probar desde Android Studio

1. Ejecutar/instalar el APK actualizado e iniciar sesión normalmente con ADMIN o SUPER_ADMIN.
2. Entrar a Gestión de Productos → Importar Excel. Pulsar **Comprobar conexión con servidor**, sin necesidad de elegir archivo, comparar o leer productos/existencias.
3. Debe confirmar acceso administrativo con escrituras deshabilitadas. USER debe ser rechazado; si el backend rechaza la cuenta o detecta un bloqueo, se muestra el mensaje correspondiente, sin cambiar permisos.
4. Para preparar, leer/comparar con el flujo actual, abrir **Resumen final y expediente** y, solo con un archivo válido, pulsar **Preparar propuesta en servidor**. Se muestra el recuento para revisión y la indicación de que no se ejecutaron cambios. El botón de ejecución real permanece deshabilitado.
5. Probar cancelar y volver a entrar: no se generan llamadas por navegación ni se pierde el expediente local. Evitar pulsaciones repetidas si se recibe 429; esperar antes de reintentar.

Para repetir las pruebas JVM: `gradlew.bat :app:testDebugUnitTest`. La variable opcional `CLISA_EXCEL_SAMPLE` permite incluir el Excel real y `CLISA_ANDROID_MULTIPART_FIXTURE` regenera el fixture ficticio de interoperabilidad en su ruta absoluta.

La clase `RenderLiveConnectionTest` solo consulta Render si se suministra explícitamente el argumento de instrumentación `liveRender=true`. No usarla para subir datos ni para probar escrituras. Las pruebas locales de cliente usan certificados de prueba solo dentro de los tests, nunca en la configuración de la app.

**No se realizaron sincronizaciones reales, modificaciones de Firestore de producción, cambios de reglas, roles o datos históricos. No se modificó `INVENTORY_WRITES_ENABLED=false` ni se desplegó un nuevo backend.** El Excel original mantiene SHA-256 `ce8c508680f8b547d71572def7fe127012d4e8191c8bc25bc7cbb69065c61a8c`.
