# Paso 3: Vista previa de sincronización

La pantalla corregida sigue la tercera referencia del mockup: fondo azul marino, cabecera geométrica, seis indicadores compactos en tres filas, pestañas Todos/Nuevos/Diferentes, búsqueda con filtro y tarjetas de producto expandibles. La primera tarjeta se abre inicialmente para mostrar stock y series individuales. Los demás grupos se abren desde el botón de filtro. No hay navegación ni acciones para sincronizar o clasificar.

La revisión visual eliminó las diez tarjetas y los avisos extensos que desplazaban la lista de productos. Los avisos permanecen disponibles mediante “Ver avisos”, con la condición de bajas sin validar siempre visible. Cada serie presenta su estado propuesto y cantidades, conservando su ID en los detalles. No se cambió el lector ni el comparador, ni los pasos 1 y 2, ni Gestión de Productos o Detalle de producto.

Archivos cambiados en esta corrección: `InventoryPreviewScreen.kt`, `InventoryPreviewScreenTest.kt`, este documento y las dos capturas del emulador.

## Archivos

- Creado `app/src/main/java/com/example/app_clisa/data/excel/InventoryPreview.kt`: proyección local de los resultados en grupos, detalles, referencias y tarjetas. No importa Firebase ni repositorios.
- Creado `app/src/main/java/com/example/app_clisa/ui/products/InventoryPreviewScreen.kt`: pantalla completa dentro de un diálogo, para conservar intacta la navegación de Gestión de Productos y Detalle de producto.
- Modificado `InventoryComparison.kt`: conserva en memoria la lectura ya realizada, incluidos documentos ambiguos que no forman parte de las coincidencias. No cambia las clasificaciones, estimación ni consultas del comparador.
- Modificado `ExcelImportAction.kt`: prepara la proyección fuera del hilo principal y abre la pantalla al terminar la comparación. Reabrir Importar Excel recupera la vista guardada durante esa sesión.
- Creados `app/src/test/java/com/example/app_clisa/InventoryPreviewTest.kt` y `app/src/androidTest/java/com/example/app_clisa/InventoryPreviewScreenTest.kt`.
- Este documento y las capturas locales de referencia.

`XlsxInventoryReader`, `ExcelInventory`, `FirestoreInventoryReader`, Gestión de Productos y Detalle de producto no se modificaron.

## Navegación y datos

1. Importar Excel → lector y resumen existentes → Comparar con Firestore.
2. Al finalizar la comparación, se abre Revisar sincronización automáticamente.
3. Las seis tarjetas abren productos nuevos, existencias nuevas, cantidades diferentes, posibles bajas, existencias sin cambios y conflictos. El filtro permite consultar también productos existentes/ausentes, conflictos de series e inconsistencias de stock. Las pestañas filtran los productos en memoria sin abrir una consulta.
4. Cada grupo tiene búsqueda local y una lista virtualizada. Sus detalles conservan SKU, IDs reales y series con ceros/espacios originales. Las referencias y series de productos/conflictos se despliegan en bloques locales de 20, sin consultas. El número del grupo de conflictos cuenta incidencias, no series únicas: una serie puede originar varios conflictos.
5. En la lista principal se pueden expandir productos y ver sus contadores y una muestra de series, o abrir los grupos completos y buscar por SKU, serie o ID. Los productos ambiguos se inspeccionan en Conflictos sin asignarles arbitrariamente un documento.
6. Atrás desde un grupo vuelve al resumen. Atrás desde el resumen cierra la vista y vuelve a Gestión de Productos. Volver al resumen del Excel permite revisar los errores del lector o elegir Otro Excel.

La proyección se construye una vez a partir de `InventoryComparison` y del Excel conservados en memoria. La búsqueda, los grupos, el despliegue de referencias y el regreso entre pantallas **no ejecutan consultas de Firestore**. Elegir otro archivo o abandonar el módulo termina esa sesión, como en el paso 2; no se promete persistencia tras destruir el proceso.

## Ausencias, conflictos y contadores

- La sección roja se denomina **Posibles bajas**. Identifica cada serie que no aparece en el Excel interpretado y su cantidad actual. La cantidad nueva queda **sin definir**: no se transforma una ausencia en cero ni se anuncia un borrado aprobado.
- Se advierte siempre que el reporte completo está sin certificar. La ausencia solo podrá considerarse una baja después de validar el alcance del Excel; sus errores y grupos sin SKU pueden producir ausencias aparentes. Las reglas auditadas prohíben el borrado físico actual.
- Un SKU no se utiliza como ID: las correspondencias existentes conservan el ID leído. Las series de documentos ambiguos conservan todas sus referencias para revisión.
- Los conflictos de SKU, series, referencias o cantidades se muestran sin resolverlos. Cuando una referencia de conflicto es un producto, también pueden revisarse sus series relacionadas ya leídas.
- Stock actual, stock esperado del Excel y `cantidadRestante` se muestran por separado. Un esperado desconocido/conflictivo dice “no fiable o sin correspondencia”; no se sustituye por cero. En productos nuevos con conflictos no se muestra un esperado fiable.
- No se cuentan las filas de resumen como existencias. La vista reutiliza las clasificaciones del paso 2.
- La estimación se identifica como preliminar y parcial; excluye documentos sin cambios, códigos con conflictos/ausencias pendientes, bajas, metadatos, `cantidadRestante` e historial futuro. No representa todavía el coste total de una sincronización.
- El único botón relativo a sincronizar está deshabilitado: **Disponible en el paso 4**. No tiene operación de escritura.

## Pruebas y capturas

Pruebas unitarias nuevas: 13 escenarios de proyección, IDs diferentes del SKU, series/ceros iniciales, cantidades anterior/nueva, ausencias condicionales, stock separado, duplicados y referencias de conflictos, búsquedas locales, grupos y resúmenes. Las pruebas instrumentadas usan `createComposeRule` en una actividad aislada, sin abrir MainActivity ni autenticarse: validan botón deshabilitado, navegación de vuelta, cantidades/IDs y búsqueda de ausencias.

Las capturas `vista-previa-local.png` y `detalle-cantidades-local.png` se generan en el emulador con datos **solo de memoria de las pruebas**, identificados como “Prueba local · sin Firestore.xlsx”. No representan datos consultados de producción ni añaden datos de demostración a la aplicación.

Validación final de la corrección visual: compilación Debug correcta, **125 pruebas unitarias aprobadas** y **5 pruebas de interfaz aprobadas** en el emulador Android 15/API 35; cero fallos y cero omitidas. La prueba nueva verifica las seis tarjetas, el filtrado de las pestañas y las series desplegadas. Incluye la prueba existente de lectura del Excel real local. Se revisaron visualmente las capturas del resumen y del detalle de cantidades.

Comando PowerShell desde la raíz:

```powershell
$env:JAVA_HOME = 'C:\Program Files\Android\Android Studio1\jbr'
$env:CLISA_EXCEL_SAMPLE = 'C:\Users\TUF\Documents\stock.production.lot123 - 2000.xlsx'
.\gradlew.bat :app:assembleDebug :app:testDebugUnitTest :app:connectedDebugAndroidTest '-Pandroid.testInstrumentationRunnerArguments.class=com.example.app_clisa.InventoryPreviewScreenTest' --no-configuration-cache
```

Para probar el flujo real desde Android Studio: ejecutar la app con una cuenta ADMIN/SUPER_ADMIN activa, abrir Gestión de Productos → Importar Excel, seleccionar `.xlsx`, revisar el resumen y pulsar Comparar con Firestore. Esa comparación conserva el coste de lectura del paso 2 (aproximadamente 24 561 documentos según la auditoría, más validaciones): revisar la cuota antes de iniciarla. Abrir grupos o buscar en la vista no añade lecturas. No existe acción para aplicar cambios.

## Seguridad y alcance

No se añadieron escrituras, llamadas administrativas, consultas para la vista previa, despliegues, índices, IA ni modificaciones de roles/reglas. No se cambiaron documentos de productos, existencias o historial. No se ejecutó la comparación contra producción durante el desarrollo; las pruebas emplean únicamente objetos locales y el Excel local del lector existente.
