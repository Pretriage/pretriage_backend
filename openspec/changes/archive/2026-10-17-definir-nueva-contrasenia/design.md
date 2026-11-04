# Design

## Context

Ver `proposal.md` para la motivación. Contexto técnico relevante:

- Las contraseñas viven en Auth0 (realm `Username-Password-Authentication`); `UsuarioAuth` no guarda password. El registro (`AuthService.registrarUsuarioYObtenerAuth0Id`) y el login (`obtenerTokenParaLogearUsuario`, grant `password-realm`) ya usan Auth0.
- Ya existe un cambio de contraseña **público** por token de email (`CambioContraseniaService` + `CambioContraseniaController`), con endpoints en la lista `permitAll` de `SpringSecurityConfig`. Reutiliza `PATCH /api/v2/users/{id}` con token M2M (`llamarApiCambioContrasenia`) — ese mecanismo de guardado es el mismo que necesita el flujo nuevo.
- La sesión es stateless (JWT validado por `NimbusJwtDecoder`); el sujeto (`jwt.getSubject()`) es el `auth0Id`, que es el `@Id` de `UsuarioAuth`.
- El usuario autenticado se resuelve con `RepoUsuariosAuth.findById(auth0Id)`; el email para verificar la contraseña actual sale de `UsuarioAuth.correoElectronico`.

## Goals / Non-Goals

**Goals:**
- Endpoint autenticado de cambio de contraseña (actual + nueva) que reutilice Auth0 como store y verificador.
- Reglas de fortaleza validadas en el backend.
- Conteo persistente de reintentos fallidos con bloqueo de 1 día, configurable por properties.
- Mensajes de éxito/fracaso aptos para el frontend.

**Non-Goals:**
- Cambiar el flujo existente de restablecimiento por token (olvidé mi contraseña) — queda intacto.
- Cambiar las reglas de fortaleza del registro o del realm de Auth0.
- Recuperación de contraseña olvidada sin sesión (ya cubierta por el flujo con token).

## Decisions

### D1: Endpoint autenticado, fuera de la lista `permitAll`
Nuevo endpoint `POST /api/auth/cambio-contrasenia/actual` (o similar) en un controller propio o extendiendo `CambioContraseniaController`. Al NO agregarlo a los `permitAll` de `SpringSecurityConfig`, cae en `.anyRequest().authenticated()` y Spring rechaza sin JWT con `401` automáticamente.
*Alternativa descartada:* agregarlo a `permitAll` y validar el JWT manualmente — duplica infraestructura ya resuelta por el resource server.

### D2: Verificación de la contraseña actual vía Auth0 `password-realm`
El backend no tiene la password; la única fuente de verdad es Auth0. Se verifica reutilizando `AuthService.obtenerTokenParaLogearUsuario(email, passwordActual)`:
- Éxito → la contraseña actual es correcta (el token devuelto se descarta, no se retorna al usuario).
- `invalid_grant` / `wrong email or password` → contraseña actual incorrecta, se cuenta como intento fallido.
*Alternativa descartada:* guardar un hash local de la contraseña — viola la restricción de que el almacenamiento sea "como está hecho actualmente para el flujo principal de inicio sesión y registro".

### D3: Reglas de fortaleza con anotaciones estándar de Bean Validation sobre el DTO
Validación (8-32 caracteres por punto de código Unicode, mayúscula, minúscula, número o especial) implementada con las anotaciones estándar de Bean Validation sobre `CambiarContraseniaAutenticadaRequest`: `@NotBlank` en ambos campos y un `@Pattern` por regla de fortaleza en la contraseña nueva. El controller marca el request con `@Valid`, de modo que la validación la ejecuta el propio marco de Spring MVC **antes** de llegar al servicio (antes de verificar la contraseña actual y de llamar a Auth0). Una password nueva inválida por reglas responde `400` con la regla incumplida y **no cuenta como intento fallido**: no se invoca la lógica de cambio, por lo que no hay intento que contar. Auth0 puede rechazar igualmente por su propia política — ese sí es fracaso con reintento.
*Alternativa descartada:* anotación de constraints propia (`@Constraint` + `ConstraintValidator`), que duplica lo que el marco ya resuelve y complica el mensaje por regla; y validar en el servicio, que obligaría a contar el fallo de fortaleza como intento fallido.

### D4: Persistencia de intentos vía jerarquía `RegistroCambioContrasenia`
Se extraen los campos comunes de `CambioContraseniaToken` a una superclase `MAPPED_SUPERCLASS` `RegistroCambioContrasenia` (`id`, relación `usuario` a `UsuarioAuth`, `fechaHoraCreacion`) y se crean dos especializaciones:
- `CambioContraseniaToken` (existente): agrega `token`, `fechaHoraExpiracion` y `estado`. **Su tabla `cambio_contrasenia_token` no cambia**: `MAPPED_SUPERCLASS` no es entidad, cada subclase tiene su propia tabla.
- `BloqueoCambioContrasenia` (nueva): una fila por usuario con `intentosFallidos` (int) y `bloqueadoHasta` (LocalDateTime, nullable) — el estado del bloqueo actual, no un log histórico.
La relación inversa `UsuarioAuth.cambiosDeContrasenia` (`@OneToMany(mappedBy="usuario")`) sigue resolviendo el campo `usuario` de la superclase sin tocar `UsuarioAuth`.

Semántica del bloqueo:
1. Intento fallido = cualquier intento que llega a la lógica de cambio y no termina en éxito (contraseña igual a la actual, contraseña actual incorrecta, rechazo de Auth0, error interno) — sin distinguir la causa. Los fallos de fortaleza del request quedan fuera: los resuelve `@Valid` antes de llegar al servicio y no cuentan.
2. Al entrar, si `bloqueadoHasta` es futuro → `423` con mensaje que incluye los minutos restantes hasta el próximo intento (para el frontend).
3. Intento fallido → `intentosFallidos++`; si llega a 3 → `bloqueadoHasta = now + 1 día` (reloj del servidor).
4. Vencido el bloqueo → el contador se resetea a 0 (no se acumula el histórico).
5. Cambio exitoso → contador a 0 y bloqueo limpiado.
*Alternativas descartadas:* contador en memoria — se pierde con restart y no sobrevive a réplicas; tabla de intentos tipo log — más compleja sin beneficio para una ventana de 1 día; campos sueltos en `UsuarioAuth` — ensucia una entidad de identidad con estado de un flujo; `SINGLE_TABLE` — modificaría la tabla existente `cambio_contrasenia_token` (columnas nullable + discriminador), rompiendo el aislamiento del flujo por token.

### D5: Reutilización del guardado M2M existente
Extraer/llamar el `PATCH /api/v2/users/{id}` con token M2M que ya usa `CambioContraseniaService.llamarApiCambioContrasenia` (idealmente movido a un método compartido en `AuthService` o un puerto, para no duplicar la lógica M2M).

### D6: Excepciones y respuestas
- Excepción nueva `CambioContraseniaBloqueadoException` → `423 Locked` (decidido: es más expresivo que `429`, refleja que el recurso —la opción de cambiar la contraseña— está bloqueado, no que haya rate-limit de tráfico) manejada en `GlobalExceptionHandler` con mensaje que incluye los minutos restantes hasta el próximo intento.
- Cualquier intento fallido que llega al servicio (contraseña igual a la actual, contraseña actual incorrecta, rechazo de Auth0) → `400` con mensaje de fracaso apto para el usuario (agrupada con las `400` existentes del handler); la fortaleza se rechaza antes, en `@Valid` del controller, con `400` sin contar como intento fallido.
- JWT válido sin `UsuarioAuth` local → `500` con mensaje genérico de error del sistema (sin detalles internos) + log WARN explicando la divergencia con Auth0.
- Sin JWT → `401` automático del resource server.
- Éxito → `200` con `{ "message": ... }` (patrón de `AuthController`); la nueva contraseña queda activa y se espera que la aplicación pida iniciar sesión nuevamente con ella.

## Risks / Trade-offs

- [Verificar la contraseña actual genera un access token descartado en Auth0] → Se descarta sin exponerlo; efecto colateral aceptable, es el costo de no tener la password local.
- [Un atacante con JWT robado puede sondear contraseñas] → Lo mitiga el bloqueo por 3 intentos + 1 día, acorde a la historia.
- [Bloqueo dependiente de `LocalDateTime` del servidor] → Mismo criterio que el resto del sistema (tokens de cambio usan `LocalDateTime.now()`); aceptable.
- [El `permitAll` existente incluye `/api/auth/cambio-contrasenia` exacto] → Elegir un path nuevo (p. ej. `/api/auth/cambio-contrasenia/actual`) que NO pise las rutas públicas existentes para no exponer el endpoint sin JWT.

## Migration Plan

Sin migración de datos: `ddl-auto=create-drop` en dev/local genera la tabla nueva de `BloqueoCambioContrasenia` por JPA, y la refactorización a `MAPPED_SUPERCLASS` deja la tabla `cambio_contrasenia_token` con su estructura actual (los campos movidos a la superclase se mapean igual). Rollback: retirar el endpoint/controller y la subclase nueva sin impacto en el resto de los flujos.
