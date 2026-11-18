# Actualización de Importar Excel · CLISA

**Estado actualizado al 29/10/2026:** backend desplegado en Render, importación habilitada con autorización explícita y conexión real con Gemini verificada. Android → Render pasó su prueba HTTPS real. El resultado, la configuración activa, los deploys y los límites de la verificación están en [DESPLIEGUE_RENDER.md](DESPLIEGUE_RENDER.md). Las referencias siguientes a despliegue pendiente describen la entrega local anterior; consultar ese informe para el estado remoto vigente.

## Implementación

Se conserva la arquitectura Kotlin → HTTPS → Node en Render → Firebase. El acceso visible `ExcelImportAction` usa ahora el flujo automático. El coordinador anterior permanece en `ExcelImportLegacyAction`, con sus pruebas de regresión; no se eliminaron el lector, el motor CLI ni las revisiones existentes. Se preservaron todos los cambios locales previos a esta sesión.

El servidor interpreta los bytes originales XLSX/XLS/CSV, detecta columnas y permite mapeo de las hojas que no reconoce. Distingue tablas planas de exportaciones agrupadas y conserva códigos de texto/formato. Valida identidades, cantidades enteras, duplicados, nombres, resúmenes y relación de las filas. Las incidencias contienen producto, serie, hoja, fila y motivo. Se aparta íntegramente el producto afectado y continúa el resto.

La comparación usa consultas por SKU, producto y grupos de 30 series, sin descargar todas las colecciones ni añadir listeners. Se conservan los IDs actuales y se reutilizan los IDs deterministas existentes para nuevas entidades. Las series ausentes se proponen a cero exclusivamente para productos válidos incluidos en el archivo. `cantidadRestante` se conserva, por tener semántica heredada no demostrada; el contador de inventario actualizado es `stock`.

La clasificación conserva categoría, marca y descripción válidas, reutiliza reglas y catálogo activo, y consulta Gemini solo para campos aún pendientes. El modelo solo puede proponer IDs presentes en el catálogo. La descripción nueva es el nombre del producto, sin inventar especificaciones. Un fallo de IA, catálogo incompleto o referencia desactivada entre análisis y escritura deja clasificación pendiente y permite el inventario válido.

Las propuestas y los recibos se guardan en `importaciones_excel/{id}`, con subcolecciones `productos` e `incidencias`. Son estado técnico para reanudar, no una auditoría que el usuario deba aprobar. No se escribe inventario durante el análisis. Después de una confirmación, cada producto y todas sus existencias se aplican en una sola transacción, junto con su resultado. Se revalidan versiones, identidades, series, referencias activas y perfil administrativo. Los cambios concurrentes y errores de escritura no dejan medio producto aplicado. Los recibos impiden repetir operaciones tras una respuesta perdida.

Android muestra metadatos del archivo, cuatro etapas, resumen, grupos con detalles, clasificación pendiente, confirmación única, progreso por productos y resultados persistidos. Los estados parciales no se presentan como éxito total. Permite reanudar al volver al módulo y exportar un resumen JSON. Los conteos de las capturas de interfaz son datos de prueba, no resultados sobre producción.

## Endpoints nuevos

Todos requieren el middleware administrativo existente: Firebase ID Token, claims actuales, usuario no deshabilitado y perfil `ACTIVO` con rol `ADMIN` o `SUPER_ADMIN`. El job pertenece al UID que lo creó.

| Método | Ruta | Uso |
|---|---|---|
| POST | `/v1/admin/excel-imports` | Dos archivos multipart: `excel` original y `contract` JSON `{ "mappings": {} }`. Devuelve mapeo requerido o propuesta durable en preparación. |
| GET | `/v1/admin/excel-imports/{id}?offset=0` | Estado real y página de 10 productos, con `nextOffset`. |
| POST | `/v1/admin/excel-imports/{id}/advance` | Hasta 20 productos de comparación/clasificación o 5 de sincronización. La comparación corta el lote cuando alcanza su plazo operativo. No habilita escrituras sin confirmación. |
| POST | `/v1/admin/excel-imports/{id}/confirm` | Confirma una propuesta completa e inicia el estado de sincronización. Repetible sin volver a confirmar una operación ya aceptada. |

Mapeo por hoja: `headerRow` desde 1 y columnas `code`, `name`, `serial`, `quantity` desde 0, todas distintas. Solo se solicita cuando la detección no fue suficiente. El servidor vuelve a interpretar los bytes tras aplicar el mapeo.

## Validación y archivo autorizado

El archivo original conserva SHA-256 `ce8c508680f8b547d71572def7fe127012d4e8191c8bc25bc7cbb69065c61a8c`. Se trabajó con lecturas y variantes en memoria; también se copió al almacenamiento del Android Emulator para la prueba del lector anterior. No se importó en producción.

La lectura nueva encuentra 1814 grupos, 22762 filas de serie, 629 incidencias y 419 productos con incidencias críticas. Hay 1395 productos válidos de origen. El número de incidencias difiere de las 567 del lector anterior porque la validación nueva registra también conflictos en ambos productos afectados y validaciones adicionales; no significa que se hayan corregido datos. Un producto válido contiene 629 series y tiene una prueba transaccional específica.

`source-analysis.json` contiene una simulación contra inventario vacío, tiempos y límites. Sus 1395 creaciones y 17727 series hipotéticas **no son escrituras realizadas ni una comparación con la base real**. La lectura local tarda aproximadamente 0,8 segundos; el tiempo completo depende de Firestore, Render y Gemini. Para esa simulación se estiman 3074 consultas de comparación, no 3074 lecturas facturables: una consulta puede devolver varios documentos y hay lecturas adicionales de planes, catálogos, permisos y transacciones.

Evidencias: `baseline-http.log`, `http-regression.log`, `backend-full.log`, `automatic-final.log`, `android-final.log` y las capturas `automatic-*.png`. El estado y la evidencia por tarea se encuentran en `TASKS.md` y Linear ALE-5.

## Configuración y despliegue pendientes

1. Desplegar esta versión del repositorio en el servicio Render existente. Conservar `rootDir=tools/inventory-sync`, `npm ci --omit=dev --ignore-scripts --no-audit --no-fund`, `npm start`, Node 22 y HTTPS. El lockfile incluye SheetJS 0.20.3 desde su distribución oficial. El archivo local `classification-rules.tsv` debe acompañar al backend.
2. Configurar `FIREBASE_PROJECT_ID` y `FIREBASE_SERVICE_ACCOUNT_JSON` en el almacén de secretos de Render. La cuenta de servicio debe poder verificar Auth/perfiles y leer/escribir inventario y estado de importaciones. La identidad de solo lectura del modo anterior es insuficiente. No introducir credenciales en Android, Git o el chat.
3. Habilitar `IMPORT_SYNC_ENABLED=true` solo en el servicio que haya recibido esta versión. Conservar `INVENTORY_WRITES_ENABLED=false`; pertenece al modo anterior. El Blueprint mantiene el nuevo canal desactivado por defecto. No se cambió configuración remota durante esta implementación.
4. Para IA, configurar `GEMINI_API_KEY` y `GEMINI_MODEL` con un modelo disponible en la cuenta. Si faltan, se usan las reglas y queda pendiente lo que no puedan completar. No se hizo una llamada real a Gemini; se verificaron respuestas válidas, inválidas y fallidas con simulaciones.
5. Mantener las colecciones administrativas inaccesibles para clientes directos. Las reglas existentes tienen denegación por defecto; Admin SDK usa IAM. Excluir de indexación los campos grandes `source`, `input`, `plan`, `issues`, `receipt` y `items` de esas colecciones administrativas para reducir tamaño y consumo; es una configuración recomendada que no se desplegó automáticamente. Definir retención y limpieza recursiva de jobs terminados conforme a la operación del negocio; no activar TTL solo en el padre porque deja subcolecciones.
6. Conservar la URL HTTPS de Render en `BuildConfig.BACKEND_BASE_URL` y publicar el APK generado después de verificar la configuración. Confirmar `/health` y que `/v1/admin/status` devuelva `AUTOMATIC_IMPORT` para un administrador activo. La aceptación de roles reales y la comunicación de esta versión Android → Render requieren el despliegue y configuración del servicio; no están acreditadas por pruebas locales.
7. Verificar primero en un Firebase de pruebas y con un administrador autorizado el recorrido completo. La primera importación de negocio debe revisar la propuesta y confirmar explícitamente; las pruebas de este trabajo no modificaron datos reales.

## Límites explícitos

Archivos hasta 25 MiB, 20 hojas, 250000 filas, 5000 grupos y 10000 incidencias. Máximo 1024 caracteres por celda. Lectura en Worker sin variables de entorno, memoria acotada y plazo de 15 segundos. Catálogos hasta 1000 documentos; máximo 1000 series por producto y límites adicionales de tamaño de fuente/propuesta. Los productos que exceden el tamaño seguro quedan omitidos con motivo y no se escriben parcialmente.

No se usa el límite antiguo de 500 escrituras como límite de Firestore: Google lo retiró en marzo de 2023. Se mantienen topes operativos y de tamaño; siguen aplicando los límites actuales de petición y transacción. Referencias: [notas oficiales de Firestore](https://docs.cloud.google.com/firestore/docs/release-notes), [cuotas actuales](https://firebase.google.com/docs/firestore/quotas).

Los avances se ejecutan mientras Android realiza solicitudes; cerrar el módulo pausa el envío de avances y al volver se reanuda desde el estado guardado. Una llamada ya recibida puede terminar y su recibo se recupera después. Si se interrumpe la carga inicial antes de recibir el identificador, debe seleccionarse el archivo de nuevo; aún no hay escrituras de inventario. No se promete procesamiento autónomo durante suspensión o reinicio del servicio. El plan gratuito de Render puede tener arranque en frío.

El canal nuevo tiene límites propios por minuto: 180 globales, 100 por IP y 90 por UID, con verificación de Auth y perfil en cada solicitud. El cliente deja 750 ms entre avances y espera automáticamente si recibe 429 o si una solicitud anterior mantiene el proceso ocupado. Así no hereda el tope de 20 solicitudes del canal anterior ni requiere confirmaciones intermedias. Los límites son por instancia, en memoria; mantener una instancia como en el Blueprint.

No se desplegaron backend, IAM, reglas ni índices, ni se publicó el APK. Las tareas que requieran esa conexión remota deben conservar su limitación documentada.

## Archivos principales

Backend nuevo: `source-reader.mjs`, `source-worker.mjs`, `automatic-plan.mjs`, `automatic-classification.mjs`, `automatic-import.mjs`, `classification-rules.tsv`; adaptación de `http-app.mjs`, `http-config.mjs`, `http-firebase.mjs`, `http-upload.mjs`, dependencias y variables de entorno.

Android: `AutomaticExcelImportAction.kt`, métodos nuevos de `ClisaBackendClient.kt`, conservación del coordinador anterior en `ExcelImportAction.kt` y etiqueta de resultados en `ImportExcelScreens.kt`. Pruebas nuevas `AutomaticBackendClientTest.kt`, `AutomaticImportScreensTest.kt`, `automatic.test.mjs`, `automatic-http.test.mjs`, `automatic-emulator.test.mjs`. Documentación y seguimiento en `docs/importacion-maestro`, `TASKS.md`, README y `render.yaml`.

Documentación de integraciones consultada: [SheetJS Node](https://docs.sheetjs.com/docs/getting-started/installation/nodejs/) y [salidas estructuradas de Gemini](https://ai.google.dev/gemini-api/docs/generate-content/structured-output).
