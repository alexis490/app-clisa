# Panel 3: Revisar análisis

Implementación del mockup `C:/Users/TUF/Downloads/Revisar análisis de inventario.png`. No se utilizó Linear.

El panel incluye la cabecera y descripción de la referencia, pasos anteriores completados y el tercer paso activo. Los nombres siguen la corrección textual del usuario: Seleccionar, Analizar, Revisar, Clasificar y Sincronizar.

Contenido implementado:

- Resumen del análisis con nombre del archivo, estado Analizado y cuatro tarjetas: filas procesadas, productos únicos, productos nuevos e incidencias detectadas.
- Calidad de datos con códigos duplicados, series duplicadas, series vacías, cantidades negativas y otros errores. Las incidencias de expedientes antiguos que no distinguen el signo de una cantidad se presentan honestamente como cantidades inválidas.
- Lista de incidencias agrupadas por código de producto; registros sin código permanecen separados por ubicación. El detalle conserva hoja, fila, serie, descripción y motivo. La búsqueda y la carga incremental de grupos permiten consultar el listado.
- Comparación con Firebase mediante barras y conteos: stock sin cambios, aumentado, disminuido, productos nuevos y ausentes del Excel. Las barras se escalan al mayor conteo mostrado. Stock sin cambios se refiere al total del producto; los cambios de series se revisan por separado.
- Resultado de validación con filas de inventario válidas y separadas. Se contabilizan registros únicos de producto/serie, excluyendo encabezados de columnas y totales generales. Un producto apartado conserva íntegramente su inventario; por eso las filas separadas no se calculan restando el número de incidencias a las filas procesadas.
- Botón fijo Continuar a clasificación, habilitado al existir un informe y productos válidos.

Los datos se calculan a partir de la comparación guardada en el servidor. No se usan las cifras ilustrativas del mockup como datos de producción. El informe se prepara automáticamente al llegar a revisión y también se puede generar para expedientes existentes. Queda guardado y su preparación repetida no duplica incidencias.

La consulta de ausentes solo lee códigos y está acotada a 5001 documentos. Cuando no se puede obtener un total completo, se muestra un valor no disponible, nunca un cero inventado. Los productos ausentes solo se informan; no se añaden al plan de cambios. Si un expediente antiguo truncó un grupo grande y no conserva el recuento de filas, sus totales de validación permanecen no disponibles.

Continuar envía una acción explícita de clasificación. El servidor carga los catálogos en ese momento, reinicia los contadores de propuestas para no duplicarlos y ejecuta las reglas/Gemini del flujo existente. La clasificación no modifica inventario. Al terminar se muestra su resultado, incluyendo los pendientes reales; otra pulsación permite ver el resumen final. La sincronización conserva su confirmación separada y no se puede iniciar directamente desde revisión.

Verificación:

- 314 pruebas JVM aprobadas.
- 68 pruebas de lógica, lector y HTTP aprobadas; 44 pruebas específicas de las rutas y el informe volvieron a pasar tras el ajuste final.
- 14 pruebas con Firebase Emulator aprobadas. Incluyen informe idempotente, conteos, continuación explícita, reinicio de contadores, confirmación posterior y conservación de productos ausentes/apartados.
- 14 pruebas Android aprobadas en el emulador, incluidos los dos paneles anteriores. Las tres del nuevo panel volvieron a pasar tras los ajustes finales.
- Capturas revisadas: `review-analysis-top.png`, `review-analysis-bottom.png` y `review-analysis-product-issues.png`. Son datos de prueba, no resultados de una importación de producción.
- Logs: `review-panel-node.log`, `review-panel-http-final.log`, `review-panel-emulator.log`, `review-panel-android-build.log`, `review-panel-ui.log` y `review-panel-final-ui.log`.

Backend publicado en commit `7e4a90176eeb49753c74ec3820bb61b70de408ba`; despliegue `dep-dbi0soi9lscs73fd89rg`, estado live el 2026-10-29 a las 22:27:13 (Lima). Se mantuvieron las credenciales y la configuración existentes. No se ejecutaron importaciones de prueba ni sincronizaciones de inventario en producción.

APK: `app/build/outputs/apk/debug/app-clisa-revision.apk`.
