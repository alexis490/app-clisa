# Corrección del diseño aprobado

Referencia única: `C:/Users/TUF/Downloads/Flujo de importación de inventario.png`.

Se corrigieron la selección, procesamiento, análisis, vista previa, resultados, incidencias y clasificación. Se retiraron el encabezado con barras, el subtítulo de navegación y los cinco círculos horizontales añadidos anteriormente. El selector contiene su propio botón, y el formato esperado muestra cuatro filas con marcas verdes.

Verificación 2026-10-29: compilación del APK aprobada, 305 pruebas JVM y 7 pruebas Android aprobadas. Las pruebas de interfaz usan datos sintéticos y no realizan sincronizaciones de producción. Se inspeccionaron las capturas en el emulador API 35. Log: `mockup-tests.log`. APK: `app/build/outputs/apk/debug/app-debug.apk`.

Estado: **en curso** en Linear ALE-56/T51 y ALE-40/T35. La revisión previa se reabrió porque no acreditaba fidelidad al original. No se afirma equivalencia completa ni resolución del error de carga.

Pendientes que requieren ampliar el contrato actual:

- Guardar ediciones individuales de categoría, marca y descripción en 4B: la propuesta actual es de consulta; Aplicar permanece deshabilitado y la pantalla lo explica.
- Totales globales de existencias válidas y cantidades, y duración del proceso: se muestra `—` cuando el servidor no entrega el dato.
- La operación actual solo propone cambios para CLISA. No se inventan movimientos en TOQEN ni fotografías de productos.
- El detalle de duplicados muestra la ubicación comprobable; la tabla de todas las filas duplicadas requiere datos completos de cada fila.

El diagnóstico del error de carga quedó en pausa al priorizar esta corrección. No se modificó el backend, Render, Gemini ni el inventario de producción en esta revisión. El Excel original se conservó.

Capturas finales: `mockup-selection.png`, `mockup-processing.png`, `mockup-analysis.png`, `mockup-preview.png`, `mockup-results.png`, `mockup-results-partial.png`, `mockup-incidents.png`, `mockup-incident-detail.png`, `mockup-classification.png` y `mockup-classification-detail.png`.
