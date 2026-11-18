# Plan técnico y decisiones verificadas

## Inspección T01–T05

Se reutilizan el estilo `ImportFlowFrame`, la sesión Firebase y OkHttp de Android, los identificadores deterministas de `canonical.mjs`, las reglas de `classification.mjs`, el middleware administrativo y el servidor Render. El lector Android existente interpreta XLSX agrupado; el nuevo lector del servidor debe repetir la interpretación de los bytes originales y admitir XLS/CSV. Se conserva el flujo anterior como código compatible, sin borrar sus pruebas ni los cambios locales previos.

Esquema comprobado en `FirestoreInventory.kt`, `FirestoreInventoryReader.kt`, `inventory.mjs` y la evidencia segura existente en `docs/importacion-paso4a1/README.md`: `productos/{id}` usa `codigo`, `nombre`, `stock`, `estado`; `existencias/{id}` usa `productoId`, `codigoProducto`, `codigo`, `cantidad`, `estado`. La cantidad representa unidades de una serie/lote, no necesariamente una unidad por documento. `categorias` y `marcas` usan `nombre` y `activo`. Se conservan IDs reales; IDs de nuevas entidades reutilizan el algoritmo existente. `cantidadRestante` se conserva porque no es un alias demostrado de stock. No se consultó producción.

## Diferencias

El HTTP actual es SAFE_PREVIEW, sin escritor de inventario ni Gemini. Android requiere iniciar comparación manualmente, descarga ambas colecciones completas y utiliza revisiones obligatorias. Las ausencias se conservan en el motor antiguo. El nuevo requisito pide cuatro etapas automáticas, consultas por alcance, exclusión por producto, clasificación tolerante a fallos y cantidad cero solo dentro del alcance válido.

## Dependencias y arquitectura

1. Lectura y validación autoritativas en backend, con ubicación de incidencias y asignación opcional de columnas. Detectar tanto exportación agrupada como tabla plana. Cero inicial conservado como texto formateado. Lector en Worker acotado.
2. Propuesta durable por producto, vinculada a UID y hash de archivo; consultas por SKU, producto y serie, con topes explícitos. Clasificación reutiliza reglas, catálogo real y Gemini opcional solo para campos faltantes.
3. Avance por solicitudes pequeñas. Ninguna escritura de inventario durante el análisis. Una única confirmación permite ejecutar; transacción atómica por producto y recibo durable para idempotencia y recuperación. Verificar versiones antes de aplicar; excluir cambios concurrentes.
4. Android consume el nuevo flujo mediante HTTPS/Firebase ID Token; progreso real, detalle de grupos, incidencias y resultados persistidos. Conservar azul marino, tarjetas compactas, acento verde, sin logo.
5. Pruebas unitarias, HTTP, Emulator y compilación Android; Excel real solo como fuente local. No desplegar reglas, importar sobre producción ni modificar datos reales al verificar.

## Configuración y límites

La activación del nuevo servicio debe ser explícita (`IMPORT_SYNC_ENABLED=true`) y requiere permisos IAM de escritura y nuevas colecciones administrativas privadas. El modo existente sigue siendo el predeterminado. Los clientes no escriben propuestas ni recibos directamente. No se modifica la política del motor CLI anterior. El backend revalida rol/perfil independientemente de Android. Gemini solo usa variables de entorno. Catálogo/IA fallidos dejan clasificación pendiente y no invalidan stock.

## Evidencia inicial

`baseline-http.log`: primera ejecución impedida por `spawn EPERM` del sandbox, sin ejecutar pruebas. Se debe repetir con permiso de procesos. La consulta de notas compartidas de Linear respondió `Tool get_agent_context not found`; el resto de Linear funciona. No se presupone contexto de esas notas.
