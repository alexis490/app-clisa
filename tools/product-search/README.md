# Activar la búsqueda por fragmentos

Android consulta `productos.busquedaTrigramas` con `array-contains`. El campo es un array de trigramas únicos del nombre y código normalizados (minúsculas, sin tildes, espacios compactados). `busquedaVersion: 1` identifica los documentos migrados. No se crea otra colección.

La app comprueba la cobertura mediante dos agregaciones, una vez por visita/caducidad de caché. Hasta completar la migración muestra que el índice está pendiente; no presenta resultados parciales como catálogo completo. El listado y contadores siguen funcionando.

## Migración administrativa, pendiente de ejecutar

Requiere Node.js 18+ y credenciales administrativas mediante Application Default Credentials, fuera de Android. No compartas claves ni las agregues al proyecto. No requiere cambiar las reglas: Admin SDK utiliza IAM. Las reglas actuales impiden añadir estos campos mediante el cliente Android.

Desde esta carpeta, instala la dependencia con `npm install`. Ejemplo de simulación (lee hasta 1000 documentos, no escribe):

```powershell
node index-product-search.mjs --project empresa-clisa --limit 1000
```

Para aplicar, añade `--apply` explícitamente. Conserva el `nextAfter` del último lote APLICADO y úsalo en `--after ID` para el siguiente lote. Al pasar de simulación a aplicación utiliza el mismo cursor de entrada, no el cursor final de la simulación. Máximo 5000 documentos por ejecución. Solo actualiza `busquedaTrigramas` y `busquedaVersion`, en transacciones que releen nombre/código. No toca stock, existencias, registros ni fechas.

Una migración inicial de 19 600 productos requiere aproximadamente 19 600 escrituras; con este script, también una lectura paginada y otra lectura transaccional por documento (aproximadamente 39 200 lecturas, más reintentos). Divide el trabajo entre días y descuenta el consumo normal de la app; 1000 es el límite predeterminado por ejecución, no un control automático de cuota diaria. La simulación consume lecturas adicionales. La cuota Spark no se reinicia por ejecutar un lote nuevo.

El array añade almacenamiento e índices. La simulación informa el promedio de trigramas; verifica el espacio disponible de Spark antes de aplicar todos los lotes. No truncar fragmentos: se perderían coincidencias.

## Mantenimiento e índices

Con índices automáticos de Firestore habilitados, basta el índice de campo único `busquedaTrigramas` en modo array-contains. No hay orden ni filtro de stock en esta consulta, por lo que no se solicita un índice compuesto. Las páginas contienen hasta 25 candidatos; Android valida todas las palabras y agrupa por el stock del documento, sin consultar existencias. Una página puede contener menos coincidencias; Cargar más avanza el cursor sin escanear automáticamente todo el resultado.

Cada alta o edición administrativa de `nombre` o `codigo` debe generar estos dos campos en la misma operación, usando `trigrams()` exportada por el script. Un índice antiguo puede omitir búsquedas nuevas; `busquedaVersion` comprueba cobertura, no detecta cambios posteriores de nombre/código. La app actual no tiene editor/importador de productos: ese flujo externo deberá actualizarse antes de mantener el índice en producción. El script puede volver a ejecutarse para corregir índices obsoletos, con su coste de lecturas.
