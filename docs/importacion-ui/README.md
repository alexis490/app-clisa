# Corrección visual de Importar Excel: pasos 1, 2 y 3

Referencia: `Flujo de importación de inventario Excel.png`. El alcance corresponde exclusivamente a las tres funciones ya implementadas. No hay pantallas funcionales de sincronización o IA.

## Diferencias encontradas

- Antes, Importar Excel abría directamente el selector Android; faltaba el panel inicial del mockup.
- La lectura y comparación se presentaban en AlertDialog, sin tarjeta de archivo ni filas de progreso por fase.
- El resumen de lectura y la vista previa se veían como interfaces distintas. Faltaba una navegación consistente entre selección, análisis y revisión.
- La pantalla de revisión ya tenía seis indicadores, pestañas, búsqueda y tarjetas expandibles después de la corrección anterior; se reutiliza completa.

## Diseño y navegación actuales

1. **Importar inventario:** cabecera azul marino sin logo CLISA, indicador de cinco etapas con Seleccionar activo, tarjeta Excel, botón para abrir el selector de archivos, tarjeta del archivo seleccionado con tamaño real y opción de quitarlo, las cuatro columnas reconocidas y botón verde Analizar archivo. Solo se ofrece `.xlsx`, el formato que soporta el lector existente.
2. **Analizando inventario:** misma cabecera y etapas con Analizar activo, tarjeta del archivo, estados de lectura, validación, conexión, productos, existencias y comparación/conflictos. Las operaciones en curso usan barras indeterminadas y recuentos observados. No se inventan porcentajes a partir de los recuentos antiguos de la auditoría. Solo una fase terminada se muestra al 100%; una validación con incidencias se marca para revisar.
3. **Revisar sincronización:** reutiliza la vista previa existente, sus seis tarjetas, filtros, búsqueda local, series expandibles, IDs originales, cantidades anteriores/nuevas, conflictos, bajas condicionales y botón de sincronización deshabilitado.

Seleccionar archivo usa el selector Android existente y el lector local existente. Una vez leído, vuelve al primer panel con las columnas y el resumen disponibles. Analizar archivo inicia la misma pasada de lectura Firestore del paso 2 y abre la revisión al terminar. Durante la selección/lectura no hay consultas de inventario. Cancelar un análisis vuelve a la selección e informa que las lecturas realizadas ya cuentan para la cuota.

El resumen original sigue accesible mediante **Ver resumen y errores de lectura**: grupos, códigos únicos, grupos sin SKU, filas y series únicas, resúmenes, incidencias con hoja/fila y los primeros 50 grupos. Reconocer las columnas no significa que el contenido esté libre de errores ni que el reporte sea completo.

Atrás desde la revisión vuelve al primer panel con el archivo y la comparación conservados. El botón cambia a **Revisar diferencias**, que abre el resultado en memoria sin consultar de nuevo Firestore. Elegir otro archivo o quitarlo invalida la comparación anterior. Atrás desde el primer panel vuelve a Gestión de Productos.

El presupuesto aproximado de la auditoría, **24 561 lecturas más validaciones**, sigue visible antes de iniciar la comparación; no representa un recuento actual del servidor ni la cuota restante. El repositorio no realiza consultas nuevas para calcular porcentajes o tamaños. El tamaño del Excel se obtiene del proveedor de archivos y del archivo temporal local.

## Archivos

- Modificado `app/src/main/java/com/example/app_clisa/ui/products/ExcelImportAction.kt`: conserva el lector y la secuencia de comparación; sustituye los diálogos por los paneles y conecta sus callbacks, cancelación y navegación. Añade únicamente los metadatos locales de nombre/tamaño para presentarlos.
- Creado `app/src/main/java/com/example/app_clisa/ui/products/ImportExcelScreens.kt`: marco visual, indicador de etapas, selección, archivo, columnas y análisis por fases. Los componentes reciben estados y callbacks: no tienen referencias a Firebase o a un repositorio.
- Creado `app/src/androidTest/java/com/example/app_clisa/ImportExcelScreensTest.kt`: pruebas de entrada al panel, selección vacía, botones, comparación en memoria, cancelación, progreso observado y lectura/proyección del Excel real.
- Este documento y cuatro capturas del emulador.

No se modificaron `InventoryComparison.kt`, `FirestoreInventoryReader.kt`, `InventoryPreview.kt`, `InventoryPreviewScreen.kt`, `XlsxInventoryReader.kt`, categorías, Gestión de Productos, Detalle de producto, reglas o índices. Los tests existentes del comparador y de la vista previa se conservan.

## Capturas y validación

- `01-seleccionar-excel.png`: selección vacía, sin asignar estados de columna verificada antes de leer.
- `01-excel-real-leido.png`: archivo real local leído, 1 814 grupos, 22 762 filas de serie y 567 incidencias. Los datos no se corrigieron ni se enviaron a Firestore.
- `02-analizar-inventario.png`: estado de UI simulado exclusivamente dentro de la prueba instrumentada, sin conexión a Firestore. Los 500 productos pertenecen a ese estado local de prueba, no a una consulta real.
- `03-revisar-diferencias.png`: vista existente con objetos locales de la prueba, sin datos consultados de producción.

Las pruebas instrumentadas usan una actividad Compose aislada y no abren MainActivity. Para la prueba del archivo real se copia temporalmente el Excel del usuario al emulador; se lee con `XlsxInventoryReader` y se compara con un inventario vacío **solo en memoria**, sin ejecutar el repositorio Firestore. Se verifica que los datos proyectados coincidan con el resultado del comparador y que se conserven códigos como `00780`.

Resultado final: **compilación Debug correcta, 125 pruebas unitarias y 10 pruebas de interfaz aprobadas**, cero fallos y cero omitidas. Las 10 pruebas de interfaz incluyen las 5 existentes de revisión y las 5 nuevas de selección/análisis/archivo real. Se inspeccionaron visualmente las capturas en Android 15/API 35.

Comandos de validación en PowerShell, desde la raíz del proyecto:

```powershell
& 'C:\Users\TUF\AppData\Local\Android\Sdk\platform-tools\adb.exe' push 'C:\Users\TUF\Documents\stock.production.lot123 - 2000.xlsx' '/sdcard/Download/clisa-import-validation.xlsx'
$env:JAVA_HOME = 'C:\Program Files\Android\Android Studio1\jbr'
$env:CLISA_EXCEL_SAMPLE = 'C:\Users\TUF\Documents\stock.production.lot123 - 2000.xlsx'
.\gradlew.bat :app:assembleDebug :app:testDebugUnitTest :app:connectedDebugAndroidTest '-Pandroid.testInstrumentationRunnerArguments.class=com.example.app_clisa.ImportExcelScreensTest,com.example.app_clisa.InventoryPreviewScreenTest' '-Pandroid.testInstrumentationRunnerArguments.excelSample=/sdcard/Download/clisa-import-validation.xlsx' --no-configuration-cache
```

Para probar desde Android Studio: ejecutar `app`, entrar con ADMIN/SUPER_ADMIN activo, abrir Gestión de Productos → Importar Excel → Seleccionar archivo. Tras la lectura local, revisar columnas e incidencias y pulsar Analizar archivo. La consulta real conservará el consumo del paso 2; verificar el presupuesto disponible antes de ejecutarla. La revisión y sus filtros no añaden lecturas.

No se consultó Firestore de producción durante la corrección visual ni se ejecutaron escrituras, cambios de reglas, índices, roles o registros históricos. Las etapas 4 y 5 permanecen sin implementar.
