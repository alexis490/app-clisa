# DOCUMENTO MAESTRO DE IMPLEMENTACIÓN PARA CODEX
## Actualización del módulo Importar Excel — CLISA Technology

**Versión:** 1.0  
**Proyecto:** Aplicación Android CLISA Technology  
**Objetivo:** Adaptar el módulo Importar Excel existente a los nuevos requisitos funcionales, técnicos y visuales.

---

## 1. INSTRUCCIÓN PRINCIPAL

Actúa como desarrollador senior especializado en Android (Kotlin), Node.js, Firebase Firestore e integración de inteligencia artificial.

El proyecto ya cuenta con un módulo Importar Excel funcional. **No debes reconstruirlo desde cero.**

Tu trabajo consiste en:

1. Inspeccionar la implementación actual.
2. Identificar las funcionalidades existentes y reutilizables.
3. Comparar el código con los requisitos de este documento.
4. Crear un archivo `TASKS.md` con todas las tareas necesarias.
5. Implementar los cambios en orden de dependencias.
6. Ejecutar pruebas después de cada modificación.
7. Marcar automáticamente las tareas completadas.
8. Entregar un resumen técnico de los resultados.

Trabaja de forma autónoma. No solicites aprobación entre tareas, salvo cuando una decisión implique riesgo de pérdida de datos, cambios destructivos o una ambigüedad que no pueda resolverse mediante inspección.

No elimines funcionalidades que ya trabajan correctamente.

---

## 2. ARQUITECTURA EXISTENTE

| Componente | Tecnología | Función |
|---|---|---|
| Aplicación móvil | Android Studio + Kotlin | Interfaz de importación |
| Backend | Node.js alojado en Render | Procesamiento y sincronización |
| Comunicación | HTTPS | Conexión Android–backend |
| Base de datos | Firebase Firestore | Inventario y catálogo |
| Inteligencia artificial | Gemini API | Clasificación de productos |

### Condiciones

- Conservar Render como proveedor del backend.
- Reutilizar los endpoints y servicios existentes cuando sea posible.
- Mantener las integraciones actuales con Firebase.
- No exponer la clave de Gemini en la aplicación Android.
- Utilizar Gemini desde el backend mediante variables de entorno.
- Evitar consultas y listeners masivos innecesarios sobre Firestore.
- No modificar módulos ajenos a Importar Excel, excepto dependencias estrictamente necesarias.

---

## 3. FLUJO FUNCIONAL DEFINITIVO

El usuario deberá seguir este recorrido:

**Seleccionar archivo → Procesamiento automático → Vista previa → Confirmación → Sincronización → Resultados**

### 3.1. Seleccionar archivo

Permitir seleccionar archivos:

- `.xlsx`
- `.xls`
- `.csv`

El sistema deberá:

- Mostrar nombre, tamaño y tipo de archivo.
- Detectar automáticamente las columnas necesarias.
- Reconocer código, nombre, número de serie/lote y cantidad.
- Permitir asignación manual de columnas únicamente cuando la detección automática no sea suficiente.
- Conservar los códigos como texto para evitar perder ceros iniciales.

### 3.2. Procesamiento automático

Después de seleccionar el archivo, ejecutar automáticamente cuatro etapas:

**Etapa 1. Lectura**
- Leer hojas y filas del Excel.
- Identificar columnas.
- Reconocer productos, registros de resumen y detalles de series/lotes.
- Interpretar correctamente los identificadores que aparecen entre corchetes en los nombres, cuando corresponda.

**Etapa 2. Validación**
- Validar códigos, nombres, cantidades y series.
- Detectar duplicados y registros inconsistentes.
- Verificar la relación entre resúmenes y detalles.
- Identificar los productos que no pueden procesarse correctamente.

**Etapa 3. Comparación**
- Comparar los productos válidos con Firestore.
- Detectar productos nuevos y existentes.
- Identificar series nuevas, existentes y ausentes.
- Calcular las cantidades finales.
- Identificar diferencias de inventario.

**Etapa 4. Clasificación**
- Consultar las categorías y marcas existentes.
- Aplicar reglas de clasificación.
- Utilizar Gemini cuando las reglas no sean suficientes.
- Completar categoría, marca y descripción cuando sea posible.
- Conservar los valores existentes que ya sean válidos.

Estas cuatro etapas deberán ejecutarse sin solicitar confirmaciones intermedias.

### 3.3. Vista previa

Mostrar un resumen de los cambios propuestos antes de sincronizar:

- Productos nuevos.
- Productos existentes por actualizar.
- Series nuevas.
- Series con cantidades modificadas.
- Series que pasarán a cantidad cero.
- Productos clasificados.
- Productos con información de clasificación pendiente.
- Productos omitidos por incidencias críticas.

Permitir consultar los detalles de cada grupo.

### 3.4. Confirmación

Solicitar una única confirmación antes de modificar Firestore.

La confirmación deberá indicar claramente que se actualizarán los productos válidos y se excluirán los productos con incidencias críticas.

### 3.5. Sincronización

Enviar al backend la operación de sincronización aprobada.

El backend deberá aplicar los cambios válidos, controlar los errores de escritura y devolver los resultados reales de la operación.

### 3.6. Resultados

Mostrar:

- Productos creados.
- Productos actualizados.
- Productos clasificados.
- Series creadas o actualizadas.
- Series con cantidad establecida en cero.
- Productos omitidos.
- Errores de sincronización.

No mostrar una importación como exitosa si alguna operación todavía está pendiente o falló.

---

## 4. REGLAS DEL INVENTARIO

### 4.1. Fuente de verdad

El Excel representa el inventario final que debe quedar registrado para los productos válidos incluidos en la importación.

El stock debe calcularse utilizando las cantidades validadas de las series o lotes.

**No sumar dos veces las filas de resumen y las filas de detalle.**

### 4.2. Productos existentes

Cuando el producto ya existe en Firestore:

- Conservar su identidad.
- Actualizar sus existencias según el Excel validado.
- Conservar categoría, marca y descripción existentes cuando sean válidas.
- Completar los campos faltantes mediante reglas o Gemini.
- No reemplazar información existente por valores vacíos.

### 4.3. Productos nuevos

Cuando el producto no existe:

- Crear el documento correspondiente.
- Registrar su código y nombre.
- Crear las existencias válidas.
- Asignar categoría, marca y descripción cuando sea posible.

Si la clasificación no se completa, el producto podrá sincronizarse con información pendiente, siempre que su identidad y stock sean válidos.

### 4.4. Series existentes

Si una serie aparece en Excel y Firestore:

- Actualizar su cantidad al valor validado del Excel.
- No duplicar el documento.
- Conservar los datos necesarios para su identificación.

### 4.5. Series nuevas

Si una serie válida aparece en Excel y no existe en Firestore:

- Crear su registro.
- Asociarla con el producto correcto.
- Registrar su cantidad.

### 4.6. Series ausentes

Si una serie existente en Firestore no aparece en el Excel:

- Conservar su documento.
- Actualizar su cantidad a `0`.
- No eliminarla.

**Esta regla se aplica exclusivamente a productos válidos incluidos en el alcance de la importación.**

No poner a cero existencias de productos ausentes completamente del archivo sin una regla de alcance previamente verificada en el código y en los datos.

### 4.7. Productos con incidencias críticas

Si un producto presenta errores que impiden determinar correctamente sus existencias:

- Excluirlo de la sincronización.
- No modificar sus cantidades.
- No crear ni eliminar sus series.
- No poner a cero ninguna de sus existencias.
- Mostrarlo en el apartado de incidencias.

Los demás productos válidos deberán continuar normalmente.

---

## 5. GESTIÓN DE INCIDENCIAS

Detectar, como mínimo:

1. Código de producto vacío.
2. Nombre de producto obligatorio ausente.
3. Número de serie duplicado de manera conflictiva.
4. Cantidad negativa o inválida.
5. Diferencias entre el resumen declarado y la suma de series.
6. Series o lotes ambiguos.
7. Registros que no pueden asociarse inequívocamente a un producto.

### Reglas

- No bloquear toda la importación por errores individuales.
- No realizar correcciones automáticas de datos críticos.
- No implementar auditorías obligatorias.
- No exigir que el usuario resuelva las incidencias una por una.
- Apartar los productos afectados y continuar con los válidos.
- Permitir revisar las incidencias después del procesamiento.

Cada incidencia deberá indicar:

- Código y nombre del producto, cuando estén disponibles.
- Número de serie o lote involucrado.
- Hoja y fila del Excel.
- Tipo de error.
- Descripción breve del problema.
- Motivo de exclusión.

**Importante:** La ausencia de categoría, marca o descripción no es una incidencia crítica de inventario y no debe impedir la actualización de existencias válidas.

---

## 6. CLASIFICACIÓN AUTOMÁTICA CON GEMINI

El Excel no contiene categoría, marca ni descripción. Estos campos deben obtenerse mediante la información existente, reglas y asistencia de IA.

### Orden de prioridad

**Primero: información existente en Firestore**

Conservar los campos válidos que ya tiene cada producto.

**Segundo: reglas de clasificación**

Utilizar coincidencias del catálogo, palabras clave, marcas conocidas y reglas existentes.

**Tercero: Gemini API**

Solicitar propuestas únicamente para los campos que continúan sin información suficiente.

### Restricciones

- Utilizar los identificadores reales de las categorías y marcas existentes.
- No inventar `categoriaId` ni `marcaId`.
- No inventar especificaciones técnicas del producto.
- No reemplazar campos válidos por respuestas vacías o inciertas.
- Validar las respuestas de Gemini antes de guardarlas.
- Si Gemini falla, continuar con la actualización de inventario válida.
- Registrar los campos pendientes para futuras mejoras.

Evitar enviar repetidamente a Gemini productos cuya información ya está completa.

---

## 7. INSPECCIÓN OBLIGATORIA DE FIRESTORE

Antes de implementar cambios, Codex deberá identificar cómo se almacenan actualmente:

- Productos.
- Existencias.
- Números de serie.
- Lotes.
- Categorías.
- Marcas.

Revisar especialmente las colecciones `productos` y `existencias`, sus identificadores de documentos y las relaciones existentes.

**No asumir que cada documento de `existencias` representa una serie.**

La estructura real deberá determinarse mediante la inspección del código y de muestras seguras del esquema actual.

Si la arquitectura existente no permite almacenar correctamente las series, proponer y aplicar una adaptación compatible que conserve los datos actuales.

No realizar migraciones destructivas ni modificar datos de producción durante la inspección.

---

## 8. DISEÑO UI/UX

Adaptar el módulo al mockup aprobado.

### Estilo visual

- Encabezados geométricos azul marino.
- Tarjetas compactas en tonos azul oscuro.
- Bordes azules finos.
- Acentos azules brillantes.
- Color verde asociado a la importación Excel.
- Tipografía legible y distribución ordenada.
- No incluir el logo CLISA en estas pantallas internas.

### Pantallas y estados

**Pantalla 1. Seleccionar archivo**

Selector de archivo y datos básicos del documento.

**Pantalla 2. Procesamiento automático**

Barra de progreso con cuatro etapas visibles:

1. Lectura.
2. Validación.
3. Comparación.
4. Clasificación.

Indicar cuál está ejecutándose, cuáles terminaron y si alguna presentó problemas.

**Pantalla 3. Vista previa**

Resumen de cambios, clasificación e incidencias, con acceso a sus detalles.

**Pantalla 4. Confirmación y sincronización**

Una confirmación y visualización del progreso de sincronización.

**Pantalla 5. Resultados**

Resumen final con cantidades reales y acceso al detalle de incidencias.

No añadir pantallas de auditoría, conciliación obligatoria ni aprobación individual de productos.

Conservar los componentes existentes que puedan adaptarse al diseño.

---

## 9. SEGURIDAD E INTEGRIDAD

### Permisos

Únicamente los roles `ADMIN` y `SUPER_ADMIN` podrán utilizar la importación.

El backend deberá verificar los permisos de manera independiente; no depender solamente de la interfaz Android.

### Integridad de datos

- Validar nuevamente los datos en el backend.
- Evitar duplicados por reimportación.
- Hacer que repetir el mismo archivo produzca el mismo estado final del inventario.
- Proteger las escrituras relacionadas con un producto para evitar actualizaciones parciales inconsistentes.
- Evitar que una importación incompleta se informe como totalmente finalizada.
- No borrar documentos de series cuando su cantidad deba pasar a cero.

### Rendimiento

- Evitar descargar innecesariamente todas las colecciones.
- Reutilizar consultas y estructuras existentes.
- Procesar los datos por lotes cuando sea conveniente.
- Controlar el consumo de lecturas y escrituras de Firestore.
- Considerar los límites y tiempos de ejecución del servicio alojado en Render.
- Evitar llamadas redundantes a Gemini.

---

## 10. LISTA MAESTRA DE TAREAS

Codex deberá crear `TASKS.md` y utilizar la siguiente estructura inicial, ampliándola cuando la inspección lo justifique.

### Fase A. Inspección

- [ ] T01. Analizar la arquitectura actual del módulo.
- [ ] T02. Identificar pantallas, servicios, endpoints y componentes reutilizables.
- [ ] T03. Inspeccionar la estructura de Firestore y el almacenamiento de series.
- [ ] T04. Identificar diferencias entre la implementación actual y los nuevos requisitos.
- [ ] T05. Documentar el plan técnico y sus dependencias.

### Fase B. Lectura y validación

- [ ] T06. Adaptar la lectura de XLSX, XLS y CSV.
- [ ] T07. Implementar detección automática y asignación de columnas.
- [ ] T08. Distinguir filas de resumen y filas de series/lotes.
- [ ] T09. Implementar validaciones y detección de incidencias.
- [ ] T10. Separar productos válidos y productos omitidos.

### Fase C. Comparación

- [ ] T11. Comparar productos válidos con Firestore.
- [ ] T12. Identificar productos nuevos y existentes.
- [ ] T13. Identificar series nuevas, modificadas y ausentes.
- [ ] T14. Calcular el stock final sin duplicar resúmenes.
- [ ] T15. Generar la propuesta de cambios.

### Fase D. Clasificación

- [ ] T16. Conservar categoría, marca y descripción existentes.
- [ ] T17. Aplicar reglas automáticas de clasificación.
- [ ] T18. Integrar o adaptar Gemini desde Node.js.
- [ ] T19. Validar las propuestas de clasificación.
- [ ] T20. Permitir sincronizar productos válidos con clasificación pendiente.

### Fase E. Sincronización

- [ ] T21. Adaptar la creación y actualización de productos.
- [ ] T22. Adaptar la actualización de series existentes.
- [ ] T23. Implementar creación de series nuevas.
- [ ] T24. Establecer cantidad cero en series ausentes del alcance válido.
- [ ] T25. Proteger completamente los productos omitidos.
- [ ] T26. Implementar escrituras consistentes e importaciones idempotentes.
- [ ] T27. Devolver resultados verificables de sincronización.

### Fase F. Interfaz Android

- [ ] T28. Adaptar la pantalla de selección de archivo.
- [ ] T29. Implementar el progreso automático de cuatro etapas.
- [ ] T30. Adaptar la vista previa.
- [ ] T31. Implementar la confirmación única.
- [ ] T32. Adaptar el progreso de sincronización.
- [ ] T33. Implementar el resumen final.
- [ ] T34. Adaptar las vistas de incidencias y clasificación.
- [ ] T35. Verificar fidelidad al mockup aprobado.

### Fase G. Integración y pruebas

- [ ] T36. Verificar comunicación Android–Render.
- [ ] T37. Probar la importación de productos nuevos.
- [ ] T38. Probar la actualización de productos existentes.
- [ ] T39. Probar series nuevas, modificadas y ausentes.
- [ ] T40. Probar exclusión de productos con incidencias.
- [ ] T41. Probar clasificación con reglas, Gemini y fallos de IA.
- [ ] T42. Probar reimportación sin duplicados.
- [ ] T43. Probar permisos y seguridad.
- [ ] T44. Evaluar consumo de Firestore y rendimiento.
- [ ] T45. Ejecutar pruebas de regresión.
- [ ] T46. Verificar compilación y funcionamiento integral.

### Fase H. Entrega

- [ ] T47. Revisar el estado final de todas las tareas.
- [ ] T48. Documentar archivos y componentes modificados.
- [ ] T49. Documentar errores o limitaciones pendientes.
- [ ] T50. Entregar instrucciones de despliegue y verificación final.

---

## 11. REGLAS PARA ADMINISTRAR `TASKS.md`

Utilizar los siguientes estados:

- `[ ]` Pendiente.
- `[-]` En progreso.
- `[x]` Completada y verificada.

Cada tarea deberá registrar:

- Identificador.
- Descripción.
- Archivos afectados.
- Dependencias.
- Pruebas ejecutadas.
- Resultado.
- Estado final.

No marcar una tarea como completada únicamente porque el código fue escrito.

Para marcar `[x]`, verificar que:

1. La funcionalidad está implementada.
2. Cumple sus criterios de aceptación.
3. Las pruebas relevantes pasan.
4. No rompe funcionalidades existentes.

Si una tarea falla, registrar el problema, corregirlo y volver a probar.

Actualizar `TASKS.md` conforme avance la implementación.

---

## 12. CRITERIOS DE ACEPTACIÓN FINAL

El módulo se considerará terminado cuando:

1. El usuario pueda seleccionar un Excel compatible.
2. El sistema ejecute automáticamente lectura, validación, comparación y clasificación.
3. Los productos válidos se procesen sin quedar bloqueados por incidencias de otros productos.
4. Los productos con incidencias críticas permanezcan intactos en Firestore.
5. Los productos nuevos se creen correctamente.
6. Los productos existentes se actualicen correctamente.
7. Las series ausentes del alcance válido pasen a cantidad cero sin eliminar sus documentos.
8. No se dupliquen existencias por filas de resumen.
9. Se conserven las categorías, marcas y descripciones existentes.
10. Gemini complete información cuando sea posible, sin bloquear inventario si falla.
11. El usuario pueda revisar los cambios antes de confirmar.
12. La sincronización se ejecute únicamente después de la confirmación.
13. Los resultados reflejen las escrituras efectivamente realizadas.
14. Una reimportación no genere duplicados ni alteraciones injustificadas.
15. El diseño respete el mockup aprobado.
16. El backend continúe funcionando mediante Render.
17. Las pruebas automatizadas relevantes sean satisfactorias.
18. Las tareas estén documentadas con sus resultados.

---

## 13. RESTRICCIONES IMPORTANTES

**NO DEBES:**

- Reconstruir todo el módulo desde cero.
- Eliminar funcionalidades existentes sin justificación.
- Borrar documentos de existencias para representar cantidad cero.
- Poner a cero existencias de productos con incidencias críticas.
- Duplicar stock sumando resúmenes y series.
- Bloquear toda la importación por errores aislados.
- Exigir auditorías o conciliaciones obligatorias.
- Obligar al usuario a corregir cada incidencia.
- Bloquear existencias válidas por falta de categoría, marca o descripción.
- Inventar categorías, marcas o datos técnicos mediante IA.
- Exponer claves privadas en Android.
- Realizar modificaciones destructivas en producción durante las pruebas.
- Cambiar innecesariamente otras pantallas de CLISA.

---

## 14. INSTRUCCIÓN FINAL DE EJECUCIÓN

Comienza inspeccionando el proyecto actual.

Crea `TASKS.md` y documenta qué funcionalidades ya están implementadas y cuáles necesitan adaptación.

Después, ejecuta las tareas en el orden técnicamente adecuado, reutilizando el código funcional existente.

Trabaja de forma autónoma, implementando, probando y actualizando el estado de las tareas.

Utiliza pruebas locales, simuladas o Firebase Emulator para validar los cambios. No ejecutes importaciones de prueba sobre datos reales de producción.

Cuando una funcionalidad esté implementada y verificada, márcala automáticamente como completada.

Si identificas una incompatibilidad importante con la estructura actual de Firestore, documenta la causa y el cambio mínimo necesario antes de continuar. No realices migraciones destructivas automáticamente.

Al finalizar, entrega:

- Resumen de funcionalidades implementadas.
- Lista de tareas completadas y pendientes.
- Archivos modificados.
- Pruebas ejecutadas y resultados.
- Problemas detectados.
- Cambios de configuración necesarios en Render o Firebase.
- Pasos pendientes para publicar y verificar la nueva versión.

**Objetivo final:** obtener un módulo Importar Excel rápido, automático, seguro y funcional, adaptado a la arquitectura existente de CLISA Technology y preparado para futuras mejoras.
