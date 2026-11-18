# CLISA — API segura para Render

## Actualización Importar Excel — 29/10/2026

Estado remoto actualizado: la versión automática está desplegada y habilitada en el servicio existente, con autorización explícita del usuario. Gemini fue verificado desde Render mediante una petición sintética. Ver [resultado del despliegue](../../docs/importacion-maestro/DESPLIEGUE_RENDER.md) para fuente, configuración y alcance de las pruebas remotas.

El flujo nuevo se documenta en [la entrega y configuración](../../docs/importacion-maestro/ENTREGA.md), con seguimiento en [TASKS.md](../../TASKS.md). `IMPORT_SYNC_ENABLED=true` habilita un canal separado de importación automática sobre los mismos servicios Render, Firebase Auth y Firestore. Su valor predeterminado es `false`; las rutas y garantías SAFE_PREVIEW descritas abajo corresponden al modo anterior, que continúa disponible con el canal automático desactivado. `INVENTORY_WRITES_ENABLED` continúa siendo una variable del modo anterior y debe permanecer `false`.

El canal nuevo valida los bytes en el servidor, aparta productos críticos, conserva identidades y metadatos válidos, prepara cambios por alcance y requiere una confirmación antes de escribir inventario. Guarda propuestas y recibos técnicos en `importaciones_excel`, con transacciones por producto y reanudación mediante solicitudes cortas. No reutiliza las puertas de auditoría del recorrido antiguo. El motor CLI anterior conserva su restricción a Emulator.

La configuración nueva **no se desplegó** durante las pruebas; los resultados de escritura se verifican únicamente en Emulator. Gemini requiere `GEMINI_API_KEY` y `GEMINI_MODEL` solo en Render. Si no están configurados o falla la IA, el stock válido continúa y la clasificación queda pendiente.

Cliente Android integrado el 25 de octubre de 2026: ver [conexión Android → Render](../../docs/importacion-paso4b-final/CONEXION_ANDROID_RENDER.md). No cambian los endpoints ni se habilita ejecución de inventario.

Backend: `tools/inventory-sync`. Esta carpeta contiene el servidor HTTP y el motor 4B existente. La API funciona en modo **SAFE_PREVIEW**, sin rutas de ejecución de inventario. No requiere una computadora encendida después de desplegarse; el despliegue real todavía no se realizó.

## Configuración de Render

| Campo | Configuración |
| --- | --- |
| Tipo de servicio | Web Service, Node |
| Root Directory | `tools/inventory-sync` |
| Build Command | `npm ci --omit=dev --ignore-scripts --no-audit --no-fund` |
| Start Command | `npm start` |
| Health Check Path | `/health` |
| Versión de Node | Rama 22, con límite superior en package.json |
| Instancias | Una; sin autoescalado ni disco persistente |

El `render.yaml` situado en la raíz del proyecto configura estos campos, un plan gratuito de pruebas y despliegues automáticos desactivados. El proceso escucha en `0.0.0.0` y en el puerto suministrado por Render. Render termina HTTPS delante del proceso HTTP. Se siguen los campos actuales de la [configuración Blueprint](https://render.com/docs/blueprint-spec) y la [configuración de versiones de Node](https://render.com/docs/node-version).

### Pasos de despliegue

1. Subir a un repositorio privado conectado con Render el código del backend, `package.json`, `package-lock.json` y `render.yaml`, conservando la estructura de carpetas. No incluir credenciales, `.env`, expedientes ni Excel reales. No usar un `git add .` indiscriminado del proyecto Android. Este trabajo no subió archivos ni creó un repositorio.
2. En Render, elegir **New → Web Service**, conectar el repositorio y seleccionar la rama deseada. Completar los campos de la tabla y desactivar Auto Deploy. Alternativamente, elegir **New → Blueprint** y utilizar el `render.yaml` raíz. Son dos alternativas de creación: no crear dos servicios.
3. En **Environment**, configurar el proyecto Firebase y el JSON completo de una cuenta de servicio autorizada, solo en el almacén de secretos de Render. No pegar esas credenciales en GitHub, en este chat ni dentro de Android. El `project_id` del JSON debe coincidir con el proyecto configurado. No se utilizan automáticamente credenciales de Firebase CLI, Android Studio o Application Default Credentials. Instrucciones de la plataforma: [variables y secretos](https://render.com/docs/configure-environment-variables).
4. Usar para este modo una identidad de servidor con permisos mínimos de **lectura**, suficientes para consultar el usuario de Auth y el perfil correspondiente. No necesita permisos de escritura de inventario, creación de usuarios, cambios de claims ni reglas. La asignación de IAM debe realizarla un administrador; este cambio no crea ni otorga permisos a ninguna cuenta. Admin SDK se autoriza mediante IAM y no queda limitado por las reglas cliente de Firestore.
5. Mantener las escrituras deshabilitadas y la exigencia de HTTPS detrás del proxy de Render. No configurar variables de Emulator en Render. No añadir scripts CLI de ejecución al Start Command, tareas cron ni endpoints de sincronización.
6. Ejecutar el despliegue manual. Abrir `/health` en la URL HTTPS asignada: debe devolver únicamente `{"status":"ok"}`. Después comprobar `/v1/admin/status` con un **Firebase ID Token** de ADMIN o SUPER_ADMIN activo. Un token de acceso de Google no sustituye al ID token de Firebase.

Es posible desplegar primero sin credenciales: `/health` funciona y todas las operaciones administrativas permanecen cerradas. Si se configura solo uno de los dos parámetros de Firebase, un JSON inválido o una combinación insegura, el arranque falla sin imprimir secretos. `/health` acredita disponibilidad del proceso, **no** que Firebase, IAM o un usuario administrativo estén correctamente configurados.

El plan Free puede suspender el servicio por inactividad y producir un arranque en frío al volver a consultarlo. Por tanto, permite pruebas sin PC encendida, pero no garantiza disponibilidad continua o baja latencia. Consultar las [limitaciones oficiales del plan gratuito](https://render.com/docs/free) antes de elegir el plan definitivo. No se contrató ningún servicio de pago.

## Variables de entorno — solo nombres

Para verificar usuarios y perfiles:

```text
FIREBASE_PROJECT_ID
FIREBASE_SERVICE_ACCOUNT_JSON
```

Plataforma, seguridad y límites; el Blueprint/defaults ya cubren los ajustes de pruebas:

```text
NODE_VERSION
NODE_ENV
PORT
INVENTORY_WRITES_ENABLED
REQUIRE_HTTPS
TRUST_PROXY_HOPS
REQUEST_TIMEOUT_MS
AUTH_TIMEOUT_MS
PREPARATION_TIMEOUT_MS
GLOBAL_REQUESTS_PER_MINUTE
IP_REQUESTS_PER_MINUTE
USER_REQUESTS_PER_MINUTE
MAX_EXCEL_BYTES
MAX_CONTRACT_BYTES
```

Reservada y opcional:

```text
GEMINI_API_KEY
```

No hay integración de Gemini. La variable reservada no se utiliza ni se registra. `.env.example` es una lista de referencia, no contiene claves y no se carga automáticamente. Los nombres de credenciales habituales y los archivos `.env` están excluidos mediante `.gitignore`; comprobar siempre lo que se va a subir.

## Endpoints disponibles

| Método y ruta | Autorización | Resultado |
| --- | --- | --- |
| `GET /health` | Público | Disponibilidad mínima del proceso; no consulta Firebase. |
| `GET /v1/admin/status` | ADMIN/SUPER_ADMIN activo | Modo seguro y capacidades; escrituras deshabilitadas. |
| `POST /v1/admin/imports/prepare` | ADMIN/SUPER_ADMIN activo | Propuesta administrativa no ejecutable, derivada del expediente recibido. |

Las llamadas administrativas envían `Authorization: Bearer <Firebase ID Token>`. Se verifica firma/audiencia/expiración mediante Admin SDK, revocación, cuenta no deshabilitada, claims administrativos actuales y perfil `usuarios/{uid}` con `role` coincidente y `estado = ACTIVO`. SUPER_ADMIN requiere también su claim específico. Cada petición admitida revalida el perfil; no se confía en un rol enviado por Android ni se cachea un bloqueo. La verificación utiliza [Firebase Admin SDK](https://firebase.google.com/docs/auth/admin/verify-id-tokens) con [comprobación de revocación](https://firebase.google.com/docs/auth/admin/manage-sessions).

El endpoint de preparación recibe `multipart/form-data` con exactamente dos **partes de archivo**:

- `excel`: `source.xlsx`, los bytes originales del archivo.
- `contract`: `contract.json`, generado por el flujo Android existente y perteneciente al UID autenticado.

Se verifica SHA-256 de los mismos bytes y se reutiliza `prepareAdministrativeContract`, sin reescribir el importador. El JSON de respuesta contiene `mode`, `inventoryWritesEnabled`, `authorization` y `proposal`. La propuesta mantiene la política `EXCEL_REFERENCE_V1`: cantidades válidas del Excel, sin descuentos históricos; ausencias conservadas. Es una preparación técnica **sin firma ni autorización de ejecución**. No certifica por sí misma la interpretación del Excel, su completitud ni un baseline actualizado de producción. Los errores e identidades ambiguas siguen bloqueados según el motor existente.

No existen rutas de `execute`, `start`, `recover`, `sync`, cambios de stock, cambios de usuarios o aplicación de clasificación. Un administrador autenticado recibe 404 si intenta llamarlas. Un usuario no autenticado no supera el middleware administrativo. Cambiar una variable de entorno para activar escrituras provoca un rechazo de configuración, no abre un camino de ejecución.

## Recursos, almacenamiento y errores

- Excel: máximo 25 MiB. Contrato y respuesta: máximo 8 MiB. Dos archivos, cero campos de texto y límite total de transferencia más pequeño que 34 MiB. No se aceptan cuerpos comprimidos; se rechazan partes extra, duplicadas o incompletas. Los topes configurables solo pueden reducir los máximos.
- Un solo proceso de preparación simultáneo. Se procesa en Worker Thread con memoria limitada y un máximo predeterminado de 15 segundos; un worker que supera el plazo se termina. No se ejecuta inventario dentro del worker.
- HTTP: plazo de aplicación y recepción de 30 segundos, encabezados/actividad de socket acotados y keep-alive de 5 segundos. Auth y perfil tienen plazos separados de 5 segundos; como máximo cuatro verificaciones pendientes. Una operación de SDK que siga pendiente después del timeout conserva su cupo hasta terminar, evitando acumulación ilimitada.
- Límites predeterminados por minuto: global 120, por IP 20 y por UID verificado 20. El límite por UID se aplica **antes** de leer Firestore, para evitar lecturas de perfil en repeticiones rechazadas. Son defensas por instancia, en memoria, no un registro persistente ni una cuota diaria garantizada. Por eso el Blueprint usa una instancia; antes de escalar debe revisarse un límite distribuido.
- HTTPS obligatorio para la API administrativa en Render; confianza limitada a un proxy, nunca `trust proxy = true`. Las cabeceras de un proxy no configurado no habilitan HTTPS ni cambian el límite de IP. No hay cookies de sesión ni CORS abierto.
- Se aplican cabeceras de seguridad, `no-store`, encabezados HTTP acotados, identificadores aleatorios de petición y errores públicos controlados. No se registran tokens, cuerpo del expediente, nombres de archivo, consultas URL ni mensajes internos del SDK. La configuración de Express sigue sus [recomendaciones oficiales de seguridad](https://expressjs.com/advanced/best-practice-security/).
- Excel y contrato viven solo en memoria durante la petición; se descartan al terminar. No se crean archivos, directorios de upload, jobs ni expedientes en el disco de Render. Si el cliente pierde la conexión, debe reenviar la propuesta; no se promete reanudación de este procesamiento temporal. Una respuesta de preparación no es un job aceptado para sincronización.
- El motor 4B conserva su persistencia de auditoría/checkpoints en Firestore, transacciones, fencing, presupuesto y recuperación, pero sigue siendo **solo Emulator** y no está conectado a rutas de escritura del servidor. La clasificación por reglas se conserva en el motor y el expediente; no se ofrece su aplicación desde HTTP.

Códigos habituales: 401 token ausente/inválido/revocado, 403 cuenta o permisos rechazados, 404 ruta inexistente, 413 tamaño excedido, 415 formato no admitido, 422 propuesta no válida, 429 límite alcanzado y 503/504 servicio saturado/no configurado o timeout. No se devuelven trazas del SDK ni credenciales.

## Pruebas y archivos

Se añadieron `server.mjs`, `http-config.mjs`, `http-firebase.mjs`, `http-app.mjs`, `http-upload.mjs`, `http-proposal.mjs`, `http-proposal-worker.mjs`, `.env.example`, este README y las pruebas HTTP. Se modificaron `package.json`, `package-lock.json`, los `.gitignore` y se añadió el `render.yaml` raíz. No se modificaron Android, el motor/contrato/preparador 4B, reglas ni documentos de producción.

Se actualizaron las dependencias HTTP y Firebase Admin a una versión corregida compatible con Node 22; el motor se probó con el SDK actualizado. `npm audit --omit=dev` terminó sin vulnerabilidades reportadas en las dependencias de producción. Eso no equivale a una garantía permanente: revisar de nuevo antes de desplegar o actualizar el lockfile.

Ejecución local de pruebas HTTP sin Firebase real: `npm ci --ignore-scripts` y `npm run test:http`. Para la suite completa con Auth/Firestore Emulator, usar el lanzador existente `security/paso4b1/Test-Emulator.ps1` con su ruta local de Firebase CLI y `-TestCommand 'npm.cmd test'`. El lanzador fija el proyecto demo y los hosts locales; no cambiarlo al proyecto real.

La evidencia final y los recuentos están en `validation/`. Se probó también el Build con `--omit=dev` en una copia aislada que solo contenía archivos del backend y su lockfile, y se inició mediante `npm start`: salud 200, administración sin token 401 y operación sin Firebase configurado rechazada. No se usaron credenciales reales para estas comprobaciones.

## Antes de permitir operaciones reales

1. Desplegar y validar HTTPS, IAM mínimo y perfiles reales autorizados. Ningún despliegue se hizo en esta tarea; no existe una URL de Render verificada todavía.
2. Diseñar y aprobar un canal de ejecución y aprobación administrativa firmada. El motor rechaza destinos de producción; este trabajo no retira ese bloqueo ni añade un interruptor que lo evite.
3. Resolver el almacenamiento **durable externo** de fuentes/expedientes y la ejecución recuperable ante suspensión o reinicio de Render. No utilizar el disco local ni mantener una petición HTTP abierta como sustituto de un job persistente.
4. Verificar con aprobación el protocolo de bloqueo/exclusión de escritores y las reglas necesarias, sin desplegarlas automáticamente. Conservar versiones nativas, idempotencia, auditoría y recuperación del motor.
5. Resolver las incidencias y el alcance incompleto del Excel real, y acreditar cuotas disponibles de Firestore. Preparar una propuesta no corrige sus datos ni permite eludir esos controles.
6. Configurar el cliente HTTP de Android con la URL HTTPS que Render asigne y el ID token actual de Firebase. No se rediseñó ni se modificó Android en esta tarea.

La formalización de traspasos y la política semanal permanecen fuera de estos requisitos técnicos: no se reinstauró una conciliación de movimientos ni descuentos históricos.
