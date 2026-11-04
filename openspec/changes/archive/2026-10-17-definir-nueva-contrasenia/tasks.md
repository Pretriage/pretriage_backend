# Tasks

## 1. Modelo y persistencia de intentos

- [x] 1.1 Extraer los campos comunes de `CambioContraseniaToken` (`id`, relación `usuario`, `fechaHoraCreacion`) a la superclase `MAPPED_SUPERCLASS` `RegistroCambioContrasenia`, dejando en `CambioContraseniaToken` sólo `token`, `fechaHoraExpiracion` y `estado`; verificar con los tests existentes del flujo por token que siguen pasando (la tabla y el repo no cambian)
- [x] 1.2 Crear la entidad `BloqueoCambioContrasenia` (hereda de `RegistroCambioContrasenia`; agrega `intentosFallidos`, `bloqueadoHasta`) con su repositorio; verificar con test de repositorio que persiste por usuario, incrementa y resetea el contador
- [x] 1.3 Agregar las properties `pretriage.cambio-contrasenia-autenticado.max-intentos` (3) y `pretriage.cambio-contrasenia-autenticado.horas-bloqueo` (1) en `application.properties` con defaults; verificar que el contexto de Spring las carga (test de servicio)

## 2. Reglas de fortaleza

- [x] 2.1 Implementar el validador de fortaleza con las anotaciones estándar de Bean Validation sobre `CambiarContraseniaAutenticadaRequest` (`@NotBlank` en ambos campos y un `@Pattern` por regla sobre la contraseña nueva: 8-32 caracteres por punto de código Unicode, mayúscula, minúscula, número o especial definido como no-letra, no-dígito, no-espacio), con mensajes por regla incumplida; verificar con tests unitarios que cubren cada regla válida e inválida, los límites 8 y 32 y caracteres Unicode (`ñ`, acentuados)

## 3. Lógica de cambio de contraseña autenticado

- [x] 3.1 Implementar el servicio de cambio autenticado: resolver `UsuarioAuth` por `jwt.getSubject()` (si no existe, lanzar la excepción del caso 3.3), verificar que no esté bloqueado (si `bloqueadoHasta` es futuro lanzar `CambioContraseniaBloqueadoException`), rechazar la contraseña nueva igual a la actual, verificar la contraseña actual vía `obtenerTokenParaLogearUsuario` y, si es correcta, guardar la nueva con el `PATCH /api/v2/users/{id}` M2M; extraer ese guardado a un método compartido en `AuthService` sin duplicar la lógica M2M (la fortaleza NO se valida en el servicio: la resuelve `@Valid` antes, y su fallo no cuenta como intento fallido); verificar con tests de servicio que: bloqueo activo rechaza, cualquier intento fallido que llega al servicio (igual a la actual, incorrecta, rechazo de Auth0) incrementa intentos, alcanzar 3 bloquea 1 día desde el último fallo, vencido el bloqueo el contador vuelve a 0 y un fallo nuevo no re-bloquea, éxito limpia el contador
- [x] 3.2 Agregar `CambioContraseniaBloqueadoException` y su manejo en `GlobalExceptionHandler` con status `423` y mensaje que incluya los minutos restantes hasta el próximo intento; verificar con test del handler que devuelve `423` con los minutos
- [x] 3.3 Manejar el caso de JWT válido sin `UsuarioAuth` local: rechazar con `500` y mensaje genérico de error del sistema (sin detalles internos) y registrar log WARN que explique la divergencia con Auth0; verificar con test de servicio que se produce el `500` y el log WARN
- [x] 3.4 Verificar con tests de servicio que un usuario con el cambio bloqueado sigue pudiendo usar el resto de los endpoints autenticados con normalidad y que el flujo de restablecimiento por token no se ve afectado por el bloqueo

## 4. Endpoint y seguridad

- [x] 4.1 Crear el DTO de request (contraseñaActual y contraseñaNueva con `@NotBlank` y las reglas de fortaleza con `@Pattern`) y el endpoint `POST /api/auth/cambio-contrasenia/actual` en el controller, marcándolo con `@Valid` para que la validación de fortaleza se responda `400` ANTES de invocar al servicio (sin contar como intento fallido), devolviendo `200 {message}` en éxito y `400` en fracaso; verificar con test de controller el caso de éxito, el caso de contraseña nueva igual a la actual (`400` con mensaje explicativo) y el caso de fortaleza inválida (`400` sin invocar al servicio)
- [x] 4.2 Verificar en `SpringSecurityConfig` que el path NUEVO no está en `permitAll` (las rutas públicas existentes siguen intactas); verificar con test de seguridad que la invocación sin JWT responde `401` y con JWT inválido también

## 5. Documentación

- [x] 5.1 Actualizar `docs/06-api-reference.md` con el nuevo endpoint (paths, request/response, errores 400/401/423), `docs/00-overview.md` y `docs/ai-agent-context.md` con el flujo de cambio autenticado y las properties nuevas; verificar que los paths documentados coinciden con los implementados
- [x] 5.2 Regenerar `docs/generated/domain-model.md` y `docs/generated/domain-model.mmd` con `python scripts/generate_domain_diagram.py` tras la nueva entidad; verificar que el script termina sin errores

## 6. Integración

- [x] 6.1 Correr la compilación y los tests enfocados (incluyendo los tests nuevos del servicio de cambio autenticado, el validador de fortaleza, el manejo del `423` y el repo de bloqueo): `./mvnw.cmd "-Dtest=CambioContraseniaServiceTest,AuthServiceTest,AuthControllerTest" test` y verificar que pasan
