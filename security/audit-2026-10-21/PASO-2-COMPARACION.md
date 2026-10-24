# Paso 2 — Comparación de inventario de solo lectura

## Alcance

Implementado en Android a partir de `AUDITORIA.md` y de la copia de reglas desplegadas de esta carpeta. Reutiliza `ExcelInventory` y `XlsxInventoryReader` sin cambiarlos. No contiene operaciones de escritura, sincronización, borrado, IA ni credenciales administrativas. No modifica Detalle de producto, categorías, búsquedas, reglas o roles.

Archivos:

- `app/src/main/java/com/example/app_clisa/data/excel/InventoryComparison.kt`: modelos independientes de Firebase y comparador puro.
- `app/src/main/java/com/example/app_clisa/data/excel/FirestoreInventoryReader.kt`: lectura paginada con SDK cliente autenticado.
- `app/src/main/java/com/example/app_clisa/ui/products/ExcelImportAction.kt`: progreso y resumen, reutilizando el botón y lector actuales.
- `app/src/test/java/com/example/app_clisa/InventoryComparatorTest.kt`: pruebas locales sin conexión a Firebase.

## Identidades y resultados

Los mapas agrupan **listas** por código; una coincidencia solo se acepta si hay exactamente un grupo Excel y como máximo un producto Firestore con ese SKU. Nunca se elige el primer documento de una lista ambigua. No se comparan nombres por similitud ni se cambian mayúsculas, barras o ceros iniciales.

`productos.codigo` identifica el SKU; `DocumentSnapshot.id` es el ID real. Por ejemplo, `SFYRS/1000G` conserva el ID `SFYRS_1000G` leído del documento. Una existencia nueva asociada a un producto existente conserva ese ID; si el producto es nuevo, su ID permanece nulo, para no inventar rutas de una futura importación.

Las series se agrupan globalmente en ambos lados por su texto sin espacios externos **solo para detectar ambigüedad**. El texto original se conserva: tener espacios externos es una inconsistencia, no una corrección automática. Cada duplicado conserva todas sus referencias de fila/documento. Además se identifican series asociadas a distintos productos. Una coincidencia requiere que `productoId` apunte al producto real y `codigoProducto` coincida con su SKU; referencias inexistentes, cantidades ausentes/negativas o estados incoherentes son conflictos.

Los resultados distinguen productos nuevos/existentes/ausentes; existencias nuevas/sin cambios/con otra cantidad/ausentes; conflictos y diferencias de stock. Cambiar una serie por otra se detecta aunque la suma de cantidades no varíe. Los documentos ambiguos no se consideran coincidencias ni se resuelven por nombre.

Las ausencias son diferencias de claves, **no una lista aprobada para eliminar**. Los grupos sin SKU o las filas no interpretadas pueden aparentar ausencias. No se propone borrado físico; las reglas descargadas lo prohíben. La integridad y alcance del archivo tendrán que revisarse en otra etapa.

## Stock y estimación

El esperado se calcula sumando cantidades de las filas individuales, sin sumar resúmenes, y se contrasta con el total declarado por el grupo. Cantidades inválidas, desbordamiento o identidad conflictiva impiden atribuir un esperado fiable. Cero conserva la identidad de una serie. Un producto ausente no recibe artificialmente un esperado cero.

`ProductCatalog.kt` filtra y muestra `productos.stock`. `FirestoreInventory.kt`, en el guardado de salida, descuenta `stock` y `existencias.cantidad`; no descuenta `cantidadRestante`. Por eso el resultado conserva y muestra por separado: esperado Excel, `stock`, `cantidadRestante` y suma de existencias. También expone diferencias entre los contadores de productos ausentes/ambiguos, sin adjudicarles un total Excel.

La estimación es **parcial y no ejecutable**: una escritura por producto nuevo inequívoco, una por existencia nueva, una por existencia con cantidad distinta y una por producto existente cuyo `stock` difiere. Cada documento se cuenta una sola vez. Se excluyen documentos sin cambios y todos los cambios de productos con conflictos o series ausentes pendientes de decisión. No incluye bajas, metadatos, actualización de `cantidadRestante`, nuevos registros históricos ni un resumen de importación. Por ello no representa el coste completo de una sincronización futura. Las reglas y el contrato de esa sincronización aún necesitan revisión; no hay botón para aplicar resultados.

## Consultas y cuota

Una pulsación explícita en **Comparar con Firestore** inicia una única pasada:

```kotlin
db.collection("productos")
    .orderBy(FieldPath.documentId()).limit(500)
    // .startAfter(ultimoDocumento) para las páginas siguientes
    .get(Source.SERVER)

db.collection("existencias")
    .orderBy(FieldPath.documentId()).limit(500)
    // .startAfter(ultimoDocumento) para las páginas siguientes
    .get(Source.SERVER)
```

No necesita índices compuestos nuevos. No se filtra por un `tiendaId` inexistente en las muestras: se utiliza el alcance CLISA confirmado para el inventario actual. Se incluyen estados sin stock, necesarios para detectar identidades y duplicados.

Con los recuentos auditados: 4 páginas de productos + 46 de existencias y aproximadamente **24 561 lecturas de documentos**, más dos lecturas explícitas del perfil, las lecturas dependientes que evalúen las reglas y posibles variaciones durante la pasada. Una colección vacía o una página final vacía también puede tener coste mínimo. No se consulta cada fila del Excel. No hay listeners, agregaciones nuevas ni consultas al escribir texto. El resultado se conserva en memoria durante esa sesión del módulo: cerrar/reabrir el resumen no vuelve a leer Firestore.

Otro Excel, volver a entrar al módulo o reintentar tras un fallo inicia otra pasada; se informa antes de empezar. Cancelar no recupera las lecturas ya facturadas. Se limita la lectura a 30 000 documentos por intento; llegar al límite sin poder certificar el final aborta sin resultados parciales. No se conoce la cuota restante del proyecto. Dos pasadas de este tamaño casi agotan las 50 000 lecturas diarias, sin contar el resto de la app.

Las páginas usan servidor, sin comparar contra una caché offline incompleta. Se conserva la fecha de inicio/fin y el campo `actualizadoEn` de cada documento si existe. **La lectura paginada no es una instantánea transaccional**: inventario concurrente puede cambiar durante la pasada. Estas versiones informativas no bastan para autorizar una aplicación futura; habrá que revalidar los documentos y el alcance antes de escribir.

## Permisos y fallos

El repositorio aplica `FirestoreAccess.requireModule(PRODUCTS)` al empezar y terminar: exige ADMIN/SUPER_ADMIN activos con perfil/claims coherentes. Comprueba el UID entre páginas y deja que las reglas autoricen cada consulta. Según las reglas descargadas, una cuenta activa puede leer productos y existencias; los bloqueos de SALIDA/AJUSTE encontrados no impiden esta lectura.

Si aparece `PERMISSION_DENIED`, se informa y se detiene; no hay acceso alternativo por IAM/Admin SDK ni resultados parciales. Se distingue cuota agotada y se ofrece reintento explícito en errores. La cancelación de la corrutina se propaga y no se transforma en un resultado satisfactorio. Esta implementación no certifica los permisos actuales en producción: no ejecutó consultas contra ella ni pruebas de roles con escrituras.

## Cómo probar

1. Compilar e instalar el APK con una cuenta ADMIN/SUPER_ADMIN activa.
2. Gestión de Productos → Importar Excel → seleccionar `.xlsx`. Esta fase sigue siendo local.
3. Revisar errores y presupuesto de lecturas. Pulsar **Comparar con Firestore** una sola vez.
4. Observar el progreso y el resumen; revisar conflictos y las muestras de diferencias entre contadores. Entendido y reabrir Importar Excel muestra el mismo resultado sin nueva consulta.
5. Cancelar o provocar una falta de conexión debe impedir mostrar un resultado parcial. Una cuenta sin permisos debe mostrar el bloqueo, sin intentar el acceso administrativo de consola.

Pruebas locales: `InventoryComparatorTest` cubre identidad SKU/ID, duplicados en ambos sistemas, series vinculadas a otro producto, igual total con distinta serie, referencias rotas, cantidades cero/negativas/nulas/desbordamiento, espacios, ausencias, stock frente a cantidadRestante, estimaciones sin cambios, cancelación y rechazo de instantáneas incompletas. Los datos de prueba existen solo en memoria de JUnit.

Validación ejecutada: `:app:assembleDebug :app:testDebugUnitTest --no-configuration-cache`, con `CLISA_EXCEL_SAMPLE` apuntando al Excel real local. Compilación correcta, **112 pruebas aprobadas, 0 fallos, 0 omitidas**, incluidas **21 pruebas del comparador**. No se ejecutaron la comparación de producción ni pruebas instrumentadas de la interfaz/permisos con una identidad Android.

No se modificó ningún documento de Firestore ni se desplegaron reglas durante esta implementación.
