# Panel inicial: comprobar formato antes de analizar

Implementado según la referencia recibida el 2026-10-29. No se utilizó Linear.

Las cuatro marcas empiezan grises: Lote / N° de serie, Referencia interna, Producto y Cantidad / existencias. Tras elegir un archivo se comprueba la presencia de la tabla, los grupos `[código] producto`, sus series y cantidades. La cantidad representa las existencias; no se exige otra columna ni se suma el resumen con las series.

Cada marca se muestra verde solo a partir de una detección verificada. La respuesta se vincula a los bytes seleccionados mediante SHA-256. Las marcas se revelan en orden, sin porcentajes inventados. Si falta algún dato, el campo permanece gris y el botón no se habilita. Cambiar de archivo reinicia la comprobación y elimina la copia temporal anterior.

El botón Analizar archivo empieza gris y deshabilitado. Se vuelve verde cuando se completan las comprobaciones y permanece en este panel esperando la pulsación del usuario. La selección por sí sola no crea un expediente ni compara inventario ni ejecuta Gemini. Solo al pulsar Analizar se utiliza el canal de análisis existente. Se conservan las validaciones completas, incidencias, apartados y confirmación de sincronización anteriores.

El servicio de presencia funciona con XLSX, XLS y CSV en un Worker aislado y conserva los límites y la autorización administrativa. Solo consulta identidad/permisos; no consulta catálogos ni inventario durante esta comprobación. Duplicados, cantidades negativas, diferencias de stock y clasificación pertenecen al análisis posterior. El indicador Válido se refiere al formato, no a que todas las filas estén libres de incidencias.

Verificación:

- Ambos archivos suministrados: `stock.production.lot123.xlsx` y `stock.production.lot123 - 2000.xlsx` reconocen los cuatro campos. Originales intactos.
- 313 pruebas JVM aprobadas.
- 45 pruebas de formato y HTTP aprobadas, incluidas variantes XLS/CSV, columnas ausentes, grupos sin series, falta de corchetes y ausencia de creación/avance/confirmación durante la comprobación.
- 9 pruebas Android aprobadas en el emulador. Prueba de recorrido adicional aprobada tras los ajustes finales: selección → marcas verdes → permanencia en panel → cambio a formato incompleto → bloqueo → nueva selección válida → pulsación explícita de Analizar.
- Capturas inspeccionadas: `format-gate-initial.png`, `format-gate-ready.png` y `format-gate-incomplete.png`.
- Logs: `format-gate-tests.log`, `format-gate-http.log`, `format-gate-android.log`, `format-gate-final-android.log` y `format-gate-apk.log`.

Backend publicado en commit `e46cc217365275dbb63ed193d865ba1dc06e5461`; despliegue Render `dep-dbht2kh5efls73fb8k8g`, estado live a las 18:06:34 (Lima). Salud posterior 200, 400 ms. No se modificaron credenciales ni se realizaron sincronizaciones de inventario de prueba en producción.

APK final: `app/build/outputs/apk/debug/app-clisa-formato.apk`. La reorganización de las etapas posteriores planteada durante voz no forma parte de esta corrección del primer panel.
