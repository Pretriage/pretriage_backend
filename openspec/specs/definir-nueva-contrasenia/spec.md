# definir-nueva-contrasenia Specification

## Purpose

Gestiona las credenciales de los usuarios de la plataforma (pacientes, médicos y recepcionistas): registro, inicio de sesión y cambio de contraseña autenticado con reglas de fortaleza y protección contra intentos fallidos repetidos.

## Requirements

### Requirement: Cambio de contraseña requiere sesión iniciada
El sistema SHALL exponer un endpoint de cambio de contraseña autenticado que MUST rechazar cualquier invocación sin un JWT válido, de modo que un usuario sin sesión no pueda definir una contraseña nueva. El cambio de contraseña MUST solicitarse siempre con la contraseña actual y la contraseña nueva.

#### Scenario: Sin JWT no se puede cambiar la contraseña
- **WHEN** se invoca el endpoint de cambio de contraseña sin cabecera `Authorization` o con un token inválido o expirado
- **THEN** el sistema responde `401` y no realiza ningún cambio

#### Scenario: Con sesión iniciada se piden ambas contraseñas
- **WHEN** un usuario autenticado invoca el endpoint de cambio de contraseña
- **THEN** el sistema espera la contraseña actual y la contraseña nueva y, si la actual no coincide con la registrada, responde `400` con mensaje de fracaso apto para el usuario y no cambia la contraseña

#### Scenario: JWT válido sin cuenta local correspondiente
- **WHEN** el JWT es válido pero no existe el usuario local correspondiente al sujeto del token
- **THEN** el sistema rechaza el cambio con `500` y un mensaje genérico de error del sistema que invite a reintentar más tarde, sin revelar detalles internos, y registra un log WARN que explique la divergencia entre Auth0 y la base local

### Requirement: La contraseña nueva cumple reglas de fortaleza
El sistema SHALL validar que la contraseña nueva tenga entre 8 y 32 caracteres, contenga al menos una letra mayúscula, al menos una letra minúscula y al menos un número o carácter especial. Los caracteres se SHALL contar por punto de código Unicode (por ejemplo, `ñ` y las letras acentuadas cuentan como un carácter cada uno). Se define carácter especial a todo carácter que no sea letra, ni dígito, ni espacio (por ejemplo `! @ # $ % ^ & * ¿ ? ¡`). Esta validación se SHALL expresar con las anotaciones estándar de Bean Validation (`@NotBlank` y `@Pattern` por regla) sobre el DTO del request, y se MUST ejecutar antes de invocar la lógica de cambio de contraseña. Si la contraseña nueva no cumple alguna regla, el sistema MUST rechazarla con `400` y un mensaje que indique la regla incumplida, sin modificar la contraseña actual y sin contar el intento como intento fallido.

#### Scenario: Contraseña nueva válida
- **WHEN** un usuario autenticado envía una contraseña actual correcta y una contraseña nueva que cumple todas las reglas (por ejemplo, 8-32 caracteres con mayúscula, minúscula y número o especial)
- **THEN** el sistema la acepta y procede a guardarla

#### Scenario: Contraseña nueva inválida
- **WHEN** la contraseña nueva tiene menos de 8 o más de 32 caracteres, o le falta mayúscula, o le falta minúscula, o no contiene ni número ni carácter especial
- **THEN** el sistema responde `400` con mensaje que aclara la regla incumplida, no cambia la contraseña y no cuenta como intento fallido (la validación pertenece al request, se ejecuta antes de la lógica de cambio)

#### Scenario: Contraseña con caracteres Unicode
- **WHEN** la contraseña nueva contiene `ñ`, letras acentuadas u otros caracteres Unicode
- **THEN** el sistema cuenta cada carácter por su punto de código y aplica las reglas de fortaleza sobre ellos sin rechazarlos por su codificación

### Requirement: La contraseña nueva debe ser distinta de la actual
El sistema SHALL rechazar la contraseña nueva cuando coincida con la contraseña actual.

#### Scenario: Contraseña nueva igual a la actual
- **WHEN** un usuario autenticado envía la contraseña actual correcta pero la contraseña nueva es idéntica a la actual
- **THEN** el sistema responde `400` con un mensaje explicativo apto para el usuario, no cambia la contraseña y cuenta como un intento fallido

### Requirement: Éxito y fracaso del cambio de contraseña
El sistema SHALL indicar con un mensaje claro el éxito o el fracaso del cambio de contraseña. El éxito MUST confirmar que la contraseña quedó actualizada y la nueva contraseña MUST quedar activa para el inicio de sesión: el usuario debe poder iniciar sesión con la contraseña nueva, y se espera que la aplicación le pida iniciar sesión nuevamente con ella.

#### Scenario: Cambio exitoso
- **WHEN** la contraseña actual es correcta, la nueva cumple las reglas y Auth0 acepta el guardado
- **THEN** el sistema responde `200` con mensaje de éxito, resetea el contador de intentos fallidos del usuario y el usuario puede iniciar sesión con la contraseña nueva

#### Scenario: Fracaso por rechazo del guardado
- **WHEN** Auth0 rechaza el guardado de la contraseña nueva (por ejemplo, no cumple la política del realm)
- **THEN** el sistema responde `400` con mensaje de fracaso apto para el usuario, cuenta como un intento fallido y permite reintentar mientras no se supere el límite de reintentos

### Requirement: Reintentos y bloqueo temporal
El sistema SHALL contar como intento fallido a cualquier intento de cambio de contraseña que llegue a la lógica de cambio y no termine en éxito, sin distinguir su causa (contraseña actual incorrecta, contraseña igual a la actual, rechazo de Auth0 o error interno). Los fallos de validación de fortaleza del request (respuesta `400` de Bean Validation) no MUST contar como intento fallido: no llegan a la lógica de cambio. Tras un máximo de 3 intentos fallidos consecutivos, el sistema MUST bloquear el endpoint de cambio de contraseña durante 1 día, contado desde el último intento fallido con el reloj del servidor. Mientras dure el bloqueo, cualquier intento MUST rechazarse con `423` y un mensaje que informe el bloqueo y los minutos restantes hasta el próximo intento permitido. Vencido el plazo, el contador de intentos fallidos MUST reiniciarse a 0. Un cambio exitoso MUST reiniciar el contador. El bloqueo MUST aplicar solamente al endpoint de cambio de contraseña autenticado: no afecta el uso normal de la aplicación ni el flujo de restablecimiento por token de la contraseña olvidada.

#### Scenario: Se agotan los reintentos y se bloquea por un día
- **WHEN** un usuario acumula 3 intentos fallidos consecutivos de cambio de contraseña
- **THEN** el sistema bloquea el endpoint de cambio de contraseña por 1 día desde el tercer fallo y los intentos posteriores responden `423` con mensaje de bloqueado que incluya los minutos restantes hasta el próximo intento permitido

#### Scenario: Se reintenta con éxito antes de agotar
- **WHEN** un usuario falla una o dos veces y al siguiente intento usa la contraseña actual correcta y una contraseña nueva válida
- **THEN** el sistema acepta el cambio, responde `200` con mensaje de éxito y reinicia el contador de intentos

#### Scenario: El bloqueo vence y el contador se reinicia
- **WHEN** transcurre el plazo de 1 día de bloqueo
- **THEN** el sistema vuelve a aceptar intentos de cambio de contraseña con el contador en 0, de modo que un único fallo nuevo no vuelve a bloquear

#### Scenario: El bloqueo no afecta el resto de la aplicación
- **WHEN** un usuario tiene el cambio de contraseña bloqueado
- **THEN** el sistema le permite seguir usando la aplicación con normalidad en todos los endpoints autenticados distintos al de cambio de contraseña

#### Scenario: El bloqueo no afecta el restablecimiento por token
- **WHEN** un usuario tiene el cambio de contraseña bloqueado y utiliza el flujo de restablecimiento por token de la contraseña olvidada
- **THEN** el sistema atiende ese flujo con normalidad, sin verse afectado por el bloqueo

### Requirement: Almacenamiento seguro de la contraseña
El sistema SHALL mantener el almacenamiento de la contraseña con el mismo mecanismo usado en registro e inicio de sesión: la contraseña NO MUST persistirse en la base de datos local y el guardado MUST realizarse en Auth0, que es quien la hashea. La verificación de la contraseña actual MUST realizarse contra Auth0.

#### Scenario: La contraseña no se guarda en la base local
- **WHEN** se completa un cambio de contraseña exitoso
- **THEN** la contraseña no aparece en ninguna tabla de la base local y el guardado se realizó mediante la API de Auth0
