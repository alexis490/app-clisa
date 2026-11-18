# Navegación de regreso entre pasos

Estado: terminada y verificada.

La flecha superior retrocede un panel por pulsación en el flujo de cinco pasos: Sincronizar → Clasificar → Revisar → Analizar → Seleccionar. En un detalle, primero vuelve al panel que lo abrió; desde Seleccionar cierra el flujo.

La pantalla consultada se mantiene separada del estado real del expediente. Navegar no cancela ni reinicia análisis o clasificación y no revierte escrituras confirmadas. Al volver a avanzar se reutilizan los resultados guardados; una clasificación ya ejecutada no vuelve a invocar classify. Volver al paso 5 repite únicamente la consulta autenticada de conexión. Se conservan el diálogo y la acción explícita de confirmación antes de sincronizar.

El paso 1 conserva el archivo y la validación de formato. Tras subirlo puede seguir mostrándose el nombre guardado aunque la copia temporal de carga haya sido eliminada. Seleccionar otro archivo continúa sujeto a los bloqueos existentes mientras hay operaciones en curso o el expediente ya fue confirmado. Los paneles anteriores muestran su resultado guardado, incluso durante o después de la sincronización; esto es navegación, no una anulación del inventario.

La entrada normal al análisis mantiene su avance automático hacia revisión; cuando el usuario retrocede deliberadamente, se conserva el panel elegido y se ofrece continuar. Cada cambio de panel reinicia su posición de desplazamiento.

Verificación:

- Compilación y 314 pruebas JVM aprobadas: back-navigation-final-build.log.
- 14 pruebas Android aprobadas en emulator-5554: back-navigation-ui.log.
- Prueba nueva: 5→4→3→2→1 y 1→2→3→4→5 sin repetir POST; solo consultas GET al entrar en sincronización.
- Prueba nueva: retroceder durante clasificación conserva el proceso, permite llegar al paso 1 y volver a los resultados sin repetir clasificación.
- Regresión de los cinco paneles, confirmación, conexión fallida y reintento.

No se modificó ni desplegó el servidor, no se usó Linear y no se realizaron escrituras de prueba en producción.

APK: app/build/outputs/apk/debug/app-clisa-navegacion.apk
SHA256: 2DC8EDBBBBF9E3827D58E8711387DF9CB2717D742D0AFE15E7756F0144483D47
