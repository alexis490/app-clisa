# Paso 4A — Revisión de reglas y contrato futuro (NO DESPLEGAR)

Fuente inspeccionada: `security/audit-2026-10-21/deployed-0-firestore.rules`, descargada en la auditoría del 21/10/2026. Se conserva intacta. No se descargó otra versión durante 4A; esta revisión no certifica cambios publicados después de esa auditoría.

## Permisos y límites actuales

| Acción | USER activo | ADMIN activo | SUPER_ADMIN activo |
|---|---|---|---|
| Leer inventario/catálogos/historial | Sí, necesario para Registrar | Sí | Sí |
| Ejecutar importación | No hay contrato seguro de importación | No hay contrato completo | Igual que ADMIN |
| Crear producto | No | Sí, solo esquema mínimo y stock 0 | Igual |
| Editar producto | No | Solo nombre, estado, actualizadoEn | Igual |
| Cambiar stock | Solo SALIDA validada | SALIDA/AJUSTE validados | Igual |
| Crear existencia | No | Sí, validación insuficiente | Igual |
| Borrar existencia o producto | No | No | No |
| Cambiar usuarios/roles | Fuera de este paso | No | Gestión restringida; no cambios de rol por las reglas actuales |

`rolValido()` exige claims firmados coherentes. ADMIN necesita role=ADMIN y admin=true; SUPER_ADMIN añade superAdmin=true. `cuentaActiva()` exige perfil ACTIVO y coincidencia del role del perfil con el token. No convertir un campo de rol enviado por Android en autorización. Un perfil bloqueado no debe superar la validación. La interfaz ni el plan local son fronteras de seguridad.

## Contradicciones verificadas

1. `registroSalidaValido`: usa `r.estadoPago`, pero la lista `hasOnly` no lo admite. Propuesta mínima: incluirlo en **ambas** listas y conservar su validación Pagado/Pendiente. Esto no es una autorización para cambiar estados de pago históricos.
2. `registroAjusteValido`: exige recibidoPor/estadoPago en `hasAll` y los prohíbe en `hasOnly`. Definir primero el contrato del ajuste; se propone que los ajustes internos utilicen motivo, origen e importacionId, sin campos de receptor/pago propios de SALIDA. Si se acuerda otro contrato, ambas listas deberán coincidir. No se cambió ninguna lista.
3. `nuevoProductoValido`: permite solo codigo/nombre/stock/estado/creadoEn/actualizadoEn y stock 0. Crear inventario importado con stock positivo o campos de clasificación requiere otro contrato explícito; no reemplazar documentos antiguos para hacerlos encajar.
4. `edicionProductoValida`: no permite categoriaId/marcaId/descripcion/cantidadRestante. Mantener la protección de esos campos; no ampliar indiscriminadamente `allow update`.
5. `existencias.create`: solo verifica cuentaActiva/esAdmin. Faltan esquema, enteros no negativos, SKU/serie, existencia del producto, coherencia de referencia y del stock posterior.
6. `existencias.delete`: false. Las propuestas DELETE_EXISTENCE del Dry Run siempre están bloqueadas. Una baja lógica conservando el documento es preferible para las referencias históricas, pero necesita política y reglas aprobadas; no se implementó ni se transformó silenciosamente en cantidad 0.
7. Helpers SALIDA/AJUSTE: `getAfter(registro)` no prueba por sí solo que el registro no existiera antes del commit. Propuesta: exigir `!exists(registroAntes)` y que el registro se cree en esa unidad atómica con ID único, usuario y relación de producto/existencia correctos. Validar los tres documentos y los cambios permitidos; no basta con la presencia de un ID antiguo.
8. `importaciones`: el resumen solo se crea COMPLETADO y es inmutable. No sirve como ejecución reanudable. Una nueva máquina de estados y un journal necesitarían aprobación de esquema/reglas; el cliente no puede declarar COMPLETADO sin evidencia de commits.

## Contrato de seguridad propuesto para 4B (sin código de escritura)

- Negar a USER toda operación de importación y todo cambio arbitrario de inventario. Conservar únicamente SALIDA atómica válida y su historial.
- Mantener la misma autorización de inventario para ADMIN/SUPER_ADMIN activos. Una importación no otorga administración de usuarios ni eleva roles.
- Validar en servidor el permiso, perfil activo, política, esquema, cantidades, pertenencia de cada serie y referencia al producto. No confiar en `validation=CANDIDATE` ni en hashes calculados por el cliente.
- Separar actualización descriptiva de stock. Proteger codigo, productoId, codigoProducto, creadoEn, roles e historial de cambios arbitrarios.
- Una unidad por producto debe escribir stock, todas sus diferencias de existencias y los registros de auditoría nuevos de forma coherente. Un journal emitido antes de confirmar los cambios no demuestra aplicación.
- La prevención de carreras necesita que **todos** los escritores, incluidas SALIDA/AJUSTE, participen en una revisión/cerrojo de inventario de alcance definido, o que el ejecutor use precondiciones/transacciones y una política que tolere cambios por producto. El diseño actual no tiene ese mecanismo global.
- No prometer atomicidad de un Excel completo de 22 762 series. Si se exige una publicación global atómica, hará falta staging/versionado y adaptar lecturas; si se acepta ejecución controlada por producto, la aplicación debe exponer un estado parcial/auditable y reanudable.
- Resolver cuotas/tamaños y accesos de reglas antes de escoger el tamaño de un lote. No fijar “500 operaciones” como garantía de seguridad: los límites efectivos incluyen bytes, índices, tiempo y consultas dependientes de reglas.
- Los campos categoriaId y marcaId existentes deben preservarse. Nuevos productos quedan pendientes de clasificación/reglas; no se crean catálogos ni referencias ficticias.

## Opciones compatibles con Spark

**Cliente controlado:** sería posible para unidades pequeñas con reglas nuevas estrictas, commits por producto y journal, una vez resueltos los bloqueos. Requiere certificar alcance, impedir carreras con los demás escritores y aceptar/gestionar un estado parcial del trabajo. No existe todavía un ejecutor de este tipo.

**Operador privilegiado fuera de Android:** una herramienta controlada en un equipo/servidor de confianza puede aplicar un plan aprobado con IAM/ADC y un journal. Firestore puede seguir en Spark, pero las cuotas siguen vigentes; el alojamiento externo tiene sus propios costes. Como el SDK servidor autorizado por IAM no depende de las reglas cliente, ese componente debe implementar sus propias validaciones, control de acceso, precondiciones y auditoría. Nunca distribuir credenciales ni Admin SDK en Android. No se creó ni ejecutó esa herramienta.

Cloud Functions desplegadas requieren Blaze; no es una alternativa alojada gratuitamente en Firebase Spark. No se habilitó facturación. Fuentes: [Functions](https://firebase.google.com/docs/functions/get-started), [límites](https://firebase.google.com/docs/firestore/quotas), [transacciones y reglas](https://firebase.google.com/docs/firestore/manage-data/transactions).

## Alcance de las pruebas

`ImportRulesAuditTest` confirma estáticamente las contradicciones de la copia descargada. Que esas pruebas pasen significa que se detectaron los bloqueos, **no** que se haya validado un ruleset seguro. No se ejecutó Firebase Emulator de reglas ni pruebas de permisos/escrituras en producción. Antes de 4B se necesitan pruebas de reglas en Emulator Suite con USER/ADMIN/SUPER_ADMIN, perfiles bloqueados, claims falsos/incompletos, replays, commits incompletos y escrituras concurrentes. Todavía no hay reglas nuevas aprobadas que someter a esas pruebas funcionales.

Este documento es una propuesta revisable, no un ruleset listo para copiar o desplegar.
