# Despliegue de CLISA en Render — resultado verificado

Fecha: 29/10/2026, zona America/Lima.

## Estado final

- Servicio: https://app-clisa.onrender.com
- Dashboard: https://dashboard.render.com/web/srv-dbeopl0m7kps73eiuhtg
- Workspace confirmado por el usuario: `Alexis's workspace` (`tea-dbe2pcvb9u1s73bvt350`).
- Servicio existente: `app-clisa.` (`srv-dbeopl0m7kps73eiuhtg`), una instancia Free, Node 22, Oregon.
- Repositorio privado: https://github.com/alexis490/app-clisa, rama `master`, despliegue automático por commit.
- Commit remoto publicado: `f96e653031c1358ee13f124c76e773c734a7ca65`.
- Árbol de fuente: `a083cd1f18a04d9f219aa5b5e354240cc8b653b8`, comprobado idéntico al árbol local preparado. Se publicaron 17 archivos de backend/configuración, sin Excel ni secretos.
- Deploy final: `dep-dbhqp44s728c73dcjjq0`, estado **live**, finalizado el 29/10/2026 a las **15:29:50 de Lima**.

## Configuración activa

| Variable | Estado |
|---|---|
| `IMPORT_SYNC_ENABLED` | `true`, activación de producción autorizada explícitamente por el usuario |
| `GEMINI_MODEL` | `gemini-3.5-flash-lite` |
| `GEMINI_API_KEY` | Se conservó el secreto existente confirmado por el usuario, sin extraerlo ni copiarlo |
| `VERIFY_GEMINI_ON_STARTUP` | `false` después de completar la prueba real, para evitar llamadas de comprobación en cada reinicio |
| Credenciales y demás variables Firebase | Conservadas por fusión; el runtime confirmó `firebaseConfigured=true` |

El servicio existente se administra mediante sus variables remotas, no mediante una sincronización de Blueprint. `render.yaml` conserva la plantilla segura con importación deshabilitada para nuevos despliegues: no sincronizar esa plantilla sobre el servicio existente sin revisar esta diferencia de configuración.

## Evidencia

1. Gemini REAL desde Render: el log del deploy de activación `dep-dbhqo959el5s73c2a860`, a las 15:27:57 de Lima, confirmó `gemini_probe.status=ok`, modelo `gemini-3.5-flash-lite`, `automaticImportEnabled=true` y `firebaseConfigured=true`. La petición fue sintética, sin datos del Excel, lectura de existencias ni escrituras de inventario.
2. Se desactivó la prueba temporal y el deploy posterior de la misma fuente llegó a `live`. Se conservó la activación de importación y los demás valores por fusión.
3. HTTPS final: `/health` devuelve 200; `/v1/admin/status` sin token devuelve 401. Evidencia: `render-final-https.json`.
4. Android real contra Render: `RenderLiveConnectionTest` ejecutado en Android Emulator con `liveRender=true`, 1 prueba aprobada, sin omisiones. Verifica el TLS del sistema y rechazo sin token; no obtiene credenciales ni sube archivos. Evidencia: `android-render-live-new.log`.
5. Prueba segura de Gemini y regresión HTTP: 32/32 pruebas aprobadas en `gemini-probe-tests.log`. Se conservan las verificaciones previas del módulo en Emulator, JVM y Compose.

## Incidencias resueltas

- Se conectaron Render y GitHub y se confirmó el workspace antes de actuar.
- Git de consola no tenía credenciales. La publicación se hizo con el conector GitHub, mediante un árbol idéntico al snapshot local y actualización de `master` con comprobación de la versión previa, sin force push.
- La revisión automática rechazó inicialmente activar endpoints de inventario de producción. Se solicitó autorización específica para `IMPORT_SYNC_ENABLED=true`; el usuario la otorgó explícitamente y la activación posterior fue aceptada. No se eludió el rechazo.
- El primer ensayo Android falló porque el emulador tenía el Wi-Fi deshabilitado y carecía de ruta y DNS. Se restableció el Wi-Fi del emulador y la prueba posterior pasó.

## Alcance y límites de la verificación

No se ejecutaron importaciones de prueba, escrituras de existencias ni cambios IAM sobre producción. No se inició sesión como un administrador real para ejecutar una importación. La autorización administrativa y las transacciones se verificaron localmente y en Emulator; las pruebas remotas acreditan despliegue activo, configuración, TLS, disponibilidad, rechazo sin token y conexión real con Gemini.

El APK actualizado está en `app/build/outputs/apk/debug/app-debug.apk`; SHA-256 `378d1b5502567c91dca0ce19b88901ef2b5884c599727371d31165df6590fa53`. No se distribuyó mediante Play Store. Para una importación de negocio, instalar esa versión, entrar con ADMIN/SUPER_ADMIN activo, seleccionar el archivo, revisar la propuesta y confirmar una sola vez.

Los commits remotos difieren de los locales porque el conector crea sus propios metadatos de commit. Los árboles de fuente coinciden. No se reseteó la rama local ni se descartaron los cambios del usuario; al volver a publicar por Git CLI habrá que autenticarlo y reconciliar su historial con el remoto.

Referencia del modelo consultada: https://ai.google.dev/gemini-api/docs/models/gemini-3.5-flash-lite
