# Validación del backend HTTP para Render

Resultado final del 23 de octubre de 2026:

- **163 pruebas aprobadas, 0 fallos, 0 omitidas** en la suite completa con Firebase Emulator: 123 de regresión existente, 29 HTTP aisladas y 11 HTTP con Auth/Firestore Emulator.
- Tokens válidos/expirados/revocados, USER/ADMIN/SUPER_ADMIN, cuenta bloqueada/deshabilitada, claims actuales y perfil ausente/inconsistente.
- Disponibilidad pública sin datos privados; ausencia de rutas de ejecución; rechazo de activación por variable de entorno y de hosts de Emulator en la configuración pública.
- Tamaños de Excel/contrato, partes multipart inválidas, identidad del expediente, SHA-256, límites por IP/UID, plazo de Auth, límite y timeout del worker, una única preparación simultánea y errores/logs sin secretos.
- La preparación HTTP conserva las versiones nativas de productos, existencias y registros de Emulator y no crea un job en `importaciones`.
- Build real `npm ci --omit=dev --ignore-scripts --no-audit --no-fund`: correcto, en copia aislada del backend sin archivos Android ni dependencias de pruebas.
- Start real `npm start`: correcto; enlazó el puerto configurado, `/health` devolvió 200, administración sin token devolvió 401 y Firebase sin configurar quedó cerrado. El servidor de prueba se detuvo.
- `render.yaml`: parseo YAML correcto y comprobación local de Root Directory, salud, despliegue manual, una instancia y ausencia de disco. Render deberá validar el Blueprint al crear el servicio; no se llamó a su API ni se realizó despliegue.
- `npm audit --omit=dev`: **0 vulnerabilidades reportadas** tras actualizar Firebase Admin. Solo describe el resultado de la comprobación de dependencias de producción en esta fecha.

Evidencia: `emulator-tests.log`, `http-tests.log`, `build-smoke.log`, `dependencies.json`. No se usaron credenciales reales ni se consultó/modificó Firestore de producción. No se desplegaron reglas, servicios ni índices y no se modificó Android.

Las pruebas de Emulator no sustituyen una validación de IAM, certificados TLS, topología del proxy y credenciales en un despliegue real. `/health` tampoco acredita disponibilidad de Firebase. Las operaciones reales de inventario permanecen bloqueadas.
