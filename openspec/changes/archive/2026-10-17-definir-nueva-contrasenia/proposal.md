# Proposal

## Why

Hoy el sistema sólo ofrece un restablecimiento de contraseña por token enviado al correo (flujo "olvidé mi contraseña", sin sesión iniciada). Los usuarios autenticados (pacientes, médicos y recepcionistas) no pueden rotar su contraseña por seguridad desde la aplicación, que es lo que pide la historia de usuario: definir una contraseña nueva conociendo la actual.

## What Changes

- Nuevo endpoint autenticado de cambio de contraseña (requiere JWT; sin sesión no es invocable).
- Verificación de la contraseña actual contra Auth0 antes de aceptar la nueva (reutilizando el flujo password-realm ya usado en login).
- Validación de reglas de fortaleza de la nueva contraseña: 8-32 caracteres, al menos una mayúscula, una minúscula y al menos un número o carácter especial.
- Persistencia de intentos fallidos por usuario: máximo 3 reintentos y bloqueo temporal de 1 día del cambio de contraseña al superarlos.
- Respuestas explícitas de éxito y de fracaso con mensaje apto para el usuario.
- El almacenamiento de la contraseña se mantiene igual que en login/registro: Auth0 (`PATCH /api/v2/users/{id}`), sin password en la base local.

## Capabilities

### New Capabilities
- `user-auth`: autenticación y gestión de credenciales de los usuarios (login, registro y ahora también cambio de contraseña autenticado con reglas de fortaleza y bloqueo por reintentos).

### Modified Capabilities
<!-- ninguna: los specs existentes (filtro-valoracion-hospitales, hospital-metrics, pretriage-chat-link) no cambian -->

## Impact

- Código nuevo/modificado:
  - `AuthController` o nuevo controller de cambio de contraseña autenticado (endpoint bajo `/api/...` que NO se agrega a los `permitAll` de `SpringSecurityConfig`).
  - `AuthService` o `CambioContraseniaService`: verificación de la contraseña actual vía Auth0 y `PATCH /api/v2/users/{id}` con token M2M (ya existente).
  - Nueva entidad JPA + repositorio para contadores de intentos fallidos y bloqueo (patrón similar a `CambioContraseniaToken`).
  - `GlobalExceptionHandler`: manejo del bloqueo temporal (423/429) y de contraseña incorrecta.
  - DTOs de request/response del cambio de contraseña.
- Configuración: propiedades nuevas tipo `pretriage.cambio-contrasenia.*` (cantidad máxima de intentos, horas de bloqueo) siguiendo el patrón existente.
- Auth0: se reutiliza el realm `Username-Password-Authentication` y el grant `password-realm` ya habilitado para login; la password se hashea en Auth0 (la base local no guarda credenciales).
- Documentación: actualizar `docs/06-api-reference.md`, `docs/00-overview.md`, `docs/ai-agent-context.md` y regenerar `docs/generated/domain-model.*` por la nueva entidad (regla de AGENTS.md).
