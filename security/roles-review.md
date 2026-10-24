# Revisión de permisos CLISA

No se consultó producción ni se desplegaron reglas. La referencia local es la
última copia de reglas entregada por el usuario (`Texto pegado.txt`); no certifica
el contenido actualmente publicado en Firebase.

## Android

- Dashboard y rutas comparten AccessPolicy. USER: salidas/historial; ADMIN añade
  productos/reportes; SUPER_ADMIN añade usuarios. Cerrar sesión permanece visible.
- El rol efectivo debe coincidir con usuarios/{uid}.role, estado ACTIVO.
  Sin claim role y sin claims administrativos, el rol efectivo es USER.
  Un claim explícito desconocido, nulo o incompleto se rechaza, no se convierte
  en USER. ADMIN exige role=ADMIN y admin=true;
  SUPER_ADMIN role=SUPER_ADMIN, admin=true y superAdmin=true.
- Correo, Google y restauración de sesión comprueban el mismo documento propio.
  UserProfiles crea USER/ACTIVO solo cuando falta, dentro de una transacción.
  Los documentos existentes se devuelven sin ninguna escritura, incluso si
  están bloqueados o tienen rol administrativo. No se usa isNewUser como prueba
  de existencia del perfil. El registro por correo ya no cierra sesión.
- Se verifica al entrar en módulos y reanudar la actividad. Un listener limitado
  al perfil propio invalida la sesión al bloquear/cambiar el rol y se libera al
  cerrar sesión o destruir el ViewModel. No hay listener sobre todos los usuarios.
  También se observa la renovación del token de Auth y se retira ese observador
  al destruir el ViewModel; el observador no consulta Firestore.
- Las consultas administrativas validan token/perfil en el repositorio. Cada
  validación de repositorio añade una lectura puntual del perfil; no descarga
  colecciones ni hace consultas por pulsación. La transacción de salida reutiliza
  su lectura existente del perfil para validar acceso, sin añadir documentos.
- Bloquear/activar excluye cuentas SUPER_ADMIN, cuenta propia y roles desconocidos;
  vuelve a comprobar actor y destino dentro de la transacción.
- Detalle de Producto, búsquedas, escaneo, categorías, filtros y paginación se
  conservan. Creación, ajuste e importación de inventario no tienen flujo Android
  implementado actualmente; este trabajo no agrega esos flujos.

## Propuesta de reglas (no publicada)

Ver roles-proposal.rules.fragment. La copia suministrada contiene:

| Condición actual | Riesgo / revisión propuesta |
|---|---|
| usuarios.update: cuentaActiva() && esSuperAdmin() | Permite cualquier campo, cuenta propia y otras cuentas SUPER_ADMIN. Restringir a estado/actualizadoEn de otros USER/ADMIN. |
| rolValido exige claim role; leer el perfil exige cuentaActiva | Admitir USER básico sin claim; permitir get del documento propio aunque falte o esté bloqueado para comprobarlo. Listar usuarios sigue limitado a SUPER_ADMIN. |
| cuentaActiva solo comprueba estado y rol del token | Añadir igualdad entre role del perfil y rol efectivo para cerrar accesos con token anterior a una degradación. |
| esAdmin exige admin=true; esSuperAdmin exige superAdmin=true | El backend debe emitir claims coherentes. Un campo role editado manualmente no concede acceso. |
| productos/existencias: lectura de cualquier cuenta activa | Necesaria para las búsquedas exactas y validación de salidas de USER. No puede distinguirse la pantalla desde reglas. |
| registros: lectura de cualquier cuenta activa | Necesaria para historial y pagos. USER puede calcular estadísticas con datos que puede leer; ocultar Reportes no impide esa derivación. |
| escrituras de inventario mediante SALIDA/AJUSTE | Conservar todas las validaciones atómicas; revisar esAdmin en creación, ajuste e importación. No conceder stock directo a USER. |

La copia revisada ya exige esAdmin en nuevoProductoValido,
edicionProductoValida, registroAjusteValido e importacionValida y en creación de
existencias. Las SALIDA deben seguir permitidas a los tres roles, con registro,
producto y existencia consistentes en una misma transacción. No bloquear esas
actualizaciones necesarias bajo el requisito de impedir edición arbitraria.

## Backend pendiente

No hay servicio administrativo configurado. El alta inicial USER no necesita
asignar custom claims: requiere publicar la propuesta de reglas compatible.
Los cambios de rol entre USER/ADMIN sí requieren un endpoint privilegiado
que verifique token, perfil ACTIVO y rol SUPER_ADMIN
del actor; deniegue actor==destino y cualquier destino SUPER_ADMIN; permita solo
USER/ADMIN; actualice role del perfil y custom claims, preservando claims ajenos;
y trate fallos parciales entre Auth/Firestore de forma segura. No son una sola
transacción entre servicios: primero restringir el perfil deja la sesión sin
acceso hasta sincronizar claims. Nunca incluir credenciales de servicio en APK.

El bloqueo actual cambia estado en Firestore y las reglas deben rechazar
operaciones de la cuenta. No deshabilita la identidad en Firebase Auth; eso
también requiere backend. No se implementa un cambio de role solo en Firestore.

## Alcance de las pruebas

Pruebas locales de matriz de roles, bloqueo, claims incompletos, discrepancias
perfil/token, llamadas directas a políticas, protección del destino y stock
existentes. Compilación Android. No verifican reglas publicadas: falta ejecutar
la matriz de reglas en Emulator Suite con el ruleset publicado y el backend
administrativo cuando exista. No se crean usuarios ni salidas reales de prueba.

Fuentes: https://firebase.google.com/docs/auth/admin/custom-claims y
https://firebase.google.com/docs/firestore/manage-data/transactions

## Archivos de esta entrega

- Nuevos: data/AccessPolicy.kt, data/FirestoreAccess.kt y AccessPolicyTest.kt.
- Modificados: MainActivity.kt, ui/auth/AuthViewModel.kt, ui/home/HomeScreen.kt,
  ui/users/UsersScreen.kt, data/UserDirectory.kt, data/UserManagementPolicy.kt,
  data/ProductCatalog.kt, data/ReportRepository.kt y data/FirestoreInventory.kt.
- Propuestas locales: security/roles-proposal.rules.fragment y este informe.
- ProductsScreen.kt (incluido Detalle de Producto) no se modificó.

## Corrección de registro automático USER

- Android: ui/auth/AuthViewModel.kt, data/AccessPolicy.kt y el nuevo data/UserProfiles.kt.
- Pruebas: UserProfilesTest.kt, 12 escenarios adicionales de alta, acceso básico,
  bloqueo, claims incompletos y preservación de todos los perfiles existentes.
- Actualizada la propuesta roles-proposal.rules.fragment, sin publicar reglas.
- No se modificaron AuthScreen.kt, diseño, roles existentes ni inventario.
- La creación del perfil es idempotente: si existe, cero escrituras; si falta,
  una escritura y una lectura en la transacción (salvo reintentos por concurrencia).
- Sin las reglas compatibles, Firestore puede rechazar la transacción inicial o
  el acceso al inventario: compilar Android no valida las reglas de producción.
