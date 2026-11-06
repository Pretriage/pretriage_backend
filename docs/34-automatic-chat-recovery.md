# Recuperación automática y cierre parcial

Actualizado el 5 de noviembre de 2026. Esta es la política vigente para texto,
voz y procesamiento final; reemplaza los desenlaces terminales por fallos técnicos
descritos en las notas históricas de los documentos 19, 20, 23 y 24.

## Resultados observables

El backend confirma `POST /api/chat/{id}/mensajes` con HTTP 202 solo después de
guardar el mensaje y su `solicitudId`. El cliente consulta `GET /api/chat/{id}`,
incluido al regresar a la pantalla. No repite el POST ni necesita botones nuevos.

Un fallo interno de generación, formato, validación o procesamiento permite hasta
dos ejecuciones por fase. Cada ejecución conserva el presupuesto de 45 segundos
para generación, verificación, corrección y reintentos HTTP internos. La espera
entre ejecuciones es de 2 segundos para un turno y 5 segundos para clasificación.
Los errores deterministas de configuración pasan directamente al desenlace parcial.

Si no se obtiene otra pregunta válida, el backend cierra la entrevista con sus
respuestas guardadas e inicia una clasificación con los datos disponibles. Los
datos no informados siguen desconocidos y se mantienen todos los validadores.
Una clasificación válida termina en `COMPLETADA`; si también agota su presupuesto
interno, termina en `PARCIAL`, con `resultadoTriageJson=null`. No se crea un resultado
de IA local ni se asigna una prioridad nueva para ocultar el fallo.

La selección del hospital ya creó una consulta y una `EntradaCola` con prioridad
provisional `NORMAL`. El cierre parcial vincula el chat a esa consulta, conserva
su prioridad, sector y posición, y no vuelve a ingresar al paciente. Una respuesta
válida recibida durante `EN_ATENCION` puede completar el registro, pero tampoco
reingresa al paciente. Cancelaciones, consultas terminadas y chats reemplazados
impiden aplicar trabajo atrasado a otra consulta.

## Indisponibilidad real y persistencia

`ProveedorIaException.Categoria` distingue `API_NO_DISPONIBLE`,
`VALIDACION_RESPUESTA`, `CONFIGURACION` y `OPERACION_INTERNA`. Se conservan status
HTTP y `Retry-After` tipados, sin interpretar mensajes arbitrarios de error.
Timeouts de transporte y HTTP 408/429/5xx representan indisponibilidad; un HTTP
200 con JSON o contenido inválido no. Autenticación/configuración tampoco es una
caída temporal del proveedor.

Ante indisponibilidad real el trabajo espera 30 segundos, 2 minutos y luego
5 minutos entre ejecuciones, respetando una espera mayor pedida por el proveedor.
Estos fallos no consumen el presupuesto de rechazos internos. El trabajo sigue
siendo durable hasta recuperarse o invalidarse la consulta. Un fallo de base de
datos no se comunica como caída de Gemini ni como un guardado confirmado.

El escaneo de trabajos vencidos ocurre cada segundo; las reservas duran 150
segundos. Se confirman en una transacción separada, con contadores monotónicos y
la clave del turno para rechazar trabajadores obsoletos. La cola de ejecución
tiene 2 trabajadores y 64 posiciones; una saturación deja el trabajo persistido
para el próximo escaneo, sin reenviar mensajes ni dormir trabajadores.

## HTTP, WebSocket y compatibilidad

`ChatDTO` añade `cierreParcial`, `estadoRecuperacion`, `proximoReintentoEn` y
`versionEstado`. El próximo intento se expone como instante RFC 3339 UTC (`Z`),
independiente de la zona horaria del dispositivo. Recuperación puede ser `SIN_RECUPERACION`, `REINTENTANDO` o
`ESPERANDO_API`. La versión aumenta con cada transición confirmada; mobile ignora
snapshots antiguos y reconcilia el caché `NO_DISPONIBLE` con el estado canónico.

`estadoTurnoTexto=COMPLETADA` significa turno resuelto, incluido cierre automático;
no equivale a clasificación completa. `NO_DISPONIBLE` queda reservado para un
turno que espera a la API y se recupera automáticamente.

Voz conserva `entrevista_finalizada` para cierre aceptado y `triage_finalizado`
para un resultado válido. `estado_chat` entrega `{chat: ChatDTO}` durante una
recuperación; `procesamiento_finalizado` entrega el mismo snapshot para un final
parcial. El GET sigue siendo autoritativo al terminar o perder el WebSocket.
Los fragmentos abiertos de reconocimiento se guardan excluidos del triage;
únicamente los turnos confirmados se usan como evidencia clínica.

La lectura médica existente expone el estado parcial y las respuestas literales
con los mismos permisos. Las métricas de pretriage completado requieren una
clasificación válida; las prioridades provisionales se distinguen mediante el
grupo `SIN_CLASIFICACION_IA`, sin cambiar los conteos de ingresos.

## Actualización y comprobación

Para una base existente, aplicar primero
`scripts/migrations/20261105_chat_recovery.sql`. El cambio es aditivo e idempotente;
amplía el CHECK del enum que Hibernate `update` no necesariamente modifica.
Las columnas nuevas toleran filas legadas nulas. El rollback de aplicación debe
mantener esta ampliación de esquema y revisar la compatibilidad del enum `PARCIAL`.

`chat.recuperacion.legacy-activos=true` habilita la recuperación de chats abiertos
antiguos `NO_DISPONIBLE` con solicitud persistida y consulta activa. La marca
`recuperacionLegacyAplicada` evita enrolarlos otra vez. No reabre chats cerrados,
cancelados ni historias completadas. Puede deshabilitarse durante una actualización
para inspeccionar candidatos antes de habilitarla.

Configuración: `chat.recuperacion.scan-ms=1000`, `texto-demora-ms=2000`,
`clasificacion-demora-ms=5000` y `api-demora-inicial-ms=30000`, todas bajo el prefijo
`chat.recuperacion`. Los contadores `intentos*` identifican ejecuciones y no se
reutilizan; los campos `fallosInternos*` controlan el presupuesto interno.

Verificar mediante `python scripts/test_backend_isolated.py`. Usar la skill
Android para observar recuperación, salir/reingresar, cierre parcial, mensajes
conservados y ausencia de controles nuevos de reintento/cierre. La voz acústica
requiere un teléfono físico; los fallos de proveedor se simulan en un entorno
aislado y no alterando conversaciones clínicas reales.

## Verificación al 5 de noviembre de 2026

La suite backend completa terminó con 792 pruebas, 773 aprobadas, 19 omitidas,
cero fallos y cero errores. `scripts/test_backend_isolated.py` utilizó una base
desechable y la eliminó al terminar. El log quedó en `target/isolated-tests.log`.

En Android se confirmó la recuperación automática ante una indisponibilidad de
API inyectada por el harness y la adopción de un chat legado activo
`NO_DISPONIBLE`. También se verificó el cierre parcial en la app: el snapshot
`PARCIAL` quedó finalizado con `cierreParcial=true`, siete mensajes únicos y sin
composer, spinner ni acción de reintento. La consulta 1 quedó vinculada al chat
1717 y conservó su misma entrada de cola, sector, prioridad 2 y orden 1 en
`EN_COLA`; `resultadoTriageJson` y el resumen permanecieron nulos.

La verificación encontró un problema de polling móvil: una respuesta `PENDIENTE`
con `cierreParcial=true` se interpretaba prematuramente como procesamiento
terminado. El polling ahora continúa hasta el estado terminal real, y la
reconciliación respeta `versionEstado`; pasaron 63 pruebas Android con este ajuste.

La migración `scripts/migrations/20261105_chat_recovery.sql` es requisito antes
de arrancar esta versión contra un esquema PostgreSQL existente: amplía el CHECK
para permitir `PARCIAL`, que Hibernate `update` no garantiza modificar. La guía
de actualización no debe interpretar el éxito de `ddl-auto=update` como sustituto
de esa migración.
