# Reintentos acotados de Gemini REST

`GeminiJsonClient` puede repetir una petición REST a `generateContent` ante errores transitorios de transporte y algunos estados HTTP. `max-attempts=2` significa dos intentos HTTP en total por solicitud Gemini, contando la llamada original. Los intentos comparten el presupuesto de esa operación configurado con `pretriage.ia.gemini.request-timeout` (por defecto, 45 segundos), reducido cuando el llamador pasa un `maxWait` menor. Cada intento usa el menor valor entre `attempt-timeout` (20 segundos por defecto) y el tiempo que queda.

Por defecto se permiten dos intentos en total (`max-attempts=2`), con una espera inicial de 500 ms y jitter entre la mitad y una vez y media ese valor. Las esperas crecen exponencialmente hasta cinco segundos antes del jitter. No se inicia otro intento si la espera no cabe en el presupuesto. Los valores se pueden configurar con `GEMINI_MAX_ATTEMPTS`, `GEMINI_ATTEMPT_TIMEOUT` y `GEMINI_RETRY_INITIAL_BACKOFF`; el máximo admitido es cinco intentos y el retroceso inicial admite de cero a cinco segundos.

Se reintentan HTTP 408, 500, 502, 503 y 504, además de errores `IOException` de transporte, incluidos los timeouts de `HttpClient`. Errores TLS, de handshake o validación de certificados no se reintentan, incluso si están anidados en la causa de otra `IOException`. Un 503 usa `Retry-After` cuando contiene segundos o una fecha HTTP válida. Un 429 solo se reintenta cuando trae ese encabezado válido; así, una respuesta de cuota agotada sin una indicación temporal no genera otra llamada. Las fechas vencidas permiten un reintento inmediato. El cliente no reintenta errores de autenticación o petición, respuestas de seguridad, JSON inválido ni respuestas semánticas incompletas.

Una interrupción cancela el flujo y conserva el estado de interrupción del hilo. Los logs de reintento contienen el número de intento, estado técnico, tiempo transcurrido y espera; no incluyen la clave, URL, cuerpo de la respuesta ni texto clínico. El cuerpo de la solicitud se serializa una vez y se reutiliza entre los intentos. Una corrección semántica de nivel superior es otra solicitud Gemini: el orquestador debe pasarle el presupuesto restante mediante `maxWait` para que ambas solicitudes compartan el límite de 45 segundos. Esta política se limita a `generateContent`; no reintenta POST de clientes móviles ni solicitudes de otras integraciones.

La política sigue la recomendación de Gemini para errores que permiten reintento: retroceso exponencial con jitter, errores transitorios seleccionados y máximo de intentos. El plazo total reducido y el tratamiento de 429 con `Retry-After` son límites propios del backend para evitar que las llamadas de triage excedan el tiempo disponible. Referencia: [Gemini API troubleshooting: Retry strategy](https://ai.google.dev/gemini-api/docs/troubleshooting#retry-strategy).

## Verificación

Desde el endurecimiento del chat, la clasificación final y las continuaciones de
texto marcadas relevantes o ambiguas hacen una petición de verificación semántica antes de
publicar o guardar la respuesta. Generación, verificación y eventual corrección
comparten el presupuesto restante de 45 segundos. Las respuestas fijas de alcance
no requieren una segunda verificación. El cierre de voz conserva una comprobación
previa de alcance para los casos ambiguos o sin hechos clínicos admitidos; evita
esa consulta redundante cuando el progreso local ya autoriza el cierre clínico.
La comprobación de alcance no escribe datos clínicos.
Véase [alcance y validación semántica](20-chat-hardening.md).

Si el verificador rechaza semánticamente una clasificación final y proporciona un motivo
seguro de la lista cerrada, el orquestador puede incluir ese motivo como orientación en
una sola solicitud de corrección. Si el verificador no proporciona motivo, se usa una
orientación genérica. El sistema no copia texto de rechazo ni citas del proveedor al
prompt correctivo o a los logs; la corrección comparte el mismo presupuesto de 45 segundos.
Un segundo rechazo semántico conserva el chat cerrado en `FALLIDA`; no habilita
otro bucle de clasificación ni un botón de reprocesamiento en mobile.

Desde el cambio de cierre controlado por el servidor, `ProveedorIaException`
transporta una marca técnica de fallo transitorio. El flujo automático de cierre
puede reservar hasta dos intentos de clasificación, con presupuesto persistido,
sin reenviar turnos ni depender del paciente. Cada intento de voz conserva un
máximo de 45 s para generación/verificación/corrección; el coordinador limita el
flujo a 91 s, incluida la espera de 500 ms. Los contadores no se reinician al
recuperar un trabajo pendiente. Texto permite un intento restante tras un primer
fallo transitorio de clasificación final y confirma la reserva antes de llamar
de nuevo al proveedor. `PENDIENTE` se mantiene visible durante esta recuperación.

Las respuestas 429 y 503 con `Retry-After` no habilitan un reintento de flujo que
ignore la espera pedida; su tratamiento HTTP sigue en `GeminiJsonClient`. TLS,
credenciales, seguridad, JSON y rechazo semántico no habilitan la recuperación
técnica. Ver [el flujo completo](23-server-owned-voice-closure.md).

`OMISION_INICIO` es un motivo permitido solo en la verificación final. Su feedback
fijo pide conservar la referencia temporal afirmada, aun si se desconoce la fecha
exacta, y vincular los distintos inicios con sus síntomas. No copia localmente un
valor sobre el resultado, no infiere evolución ni amplía los intentos o el plazo.
La corrección vuelve a pasar por la verificación final. El esquema y el validador
de preguntas no aceptan ese motivo.

El rechazo local exacto `sintomas:missing_or_uninformative` conserva el código
permitido `SINTOMAS_AUSENTES` para esa misma corrección, en texto, voz y reintento
de clasificación. El feedback fijo pide recuperar hechos descriptivos expresos,
también emocionales; no exige síntomas físicos ni permite inventarlos. Las listas
opcionales pueden quedar vacías, pero una clasificación relevante sigue requiriendo
síntomas significativos con evidencia literal y verificación semántica. No se copia
el resultado rechazado ni se interpolan campos o mensajes arbitrarios de excepción.

`Chat IA correction accepted chatId=... reason=...` indica que la corrección pasó
los validadores. No prueba por sí solo que la transacción haya confirmado ni que
el cliente haya recibido la respuesta HTTP. Un rechazo inicial seguido de esa
aceptación es distinto de un fallo definitivo del procesamiento.

En una continuación de texto, un rechazo semántico de la pregunta conserva solo el
código de motivo permitido y lo convierte en una instrucción fija para una única
corrección. Un motivo ausente o una respuesta repetida usa la orientación genérica
correspondiente; no se arrastra un motivo semántico previo. Si la pregunta corregida
vuelve a fallar la verificación, el turno se rechaza y no se persiste una respuesta del
bot ni se crea una entrada de cola. La generación y su verificación, más esa corrección
y su verificación, comparten el presupuesto de 45 segundos.

Una propuesta que se desvía del `objetivoDelTurno` también consume esa única
corrección; no abre un presupuesto ni un bucle nuevos. `AMBIGUO` no omite el control
local del objetivo ni la repetición. Las paráfrasis no reconocidas localmente se
evalúan semánticamente con el objetivo canónico, sin imponer un formato rígido.

Para una aclaración de motivo todavía indefinido (`ACLARACION` sin síntoma ni
dimensión), tanto un rechazo local como `INFERENCIA` o `SIN_RESPALDO` reciben
feedback fijo que pide explicar con palabras propias el sentido de la expresión.
No pide elegir categorías físicas/emocionales ni cuantificar todavía. Si la segunda
propuesta se acepta, se registra `Chat IA question correction accepted` con chatId y códigos
de motivo, sin texto clínico. La aceptación no prueba entrega HTTP ni commit.

Para `COMUNICACION` con objetivo `INVITACION`, el feedback exige una sola
invitación pertinente sobre lo ya contado, sin pedir otro dato, sugerir síntomas,
resumir el contexto como causa o diagnóstico, anunciar registro ni cerrar. El
generador y el verificador permiten un reconocimiento neutral muy breve justo
antes de esa invitación; esa frase no es por sí sola una despedida ni un cierre.
No se permite una respuesta de empatía sin la invitación ni otro acto clínico.
Los demás objetivos conservan su feedback y todos conservan los mismos límites:
dos propuestas como máximo y un presupuesto compartido de 45 segundos.

Los logs `Chat IA correction` de preguntas y `Chat IA question correction accepted`
incluyen `objectiveType`, únicamente el enum del objetivo controlado por el backend.
No contienen el identificador del síntoma ni texto del paciente o del proveedor.

Una aclaración necesaria puede repetir la última pregunta si el dato sigue
pendiente y conserva intentos disponibles. Esto no permite repetir preguntas
antiguas ni reinicia los presupuestos de
[cierre por datos resueltos](21-cierre-por-datos-resueltos.md).

El 21 de octubre de 2026 pasaron las **435 pruebas Java** completas con
`python scripts/test_backend_isolated.py`, contra una base temporal aleatoria,
y las **12 pruebas Python** de los helpers E2E. Las regresiones controlan 503 y
timeout recuperados, agotamiento de intentos, presupuesto restante, `Retry-After`,
autenticación/cuota dura, TLS, interrupciones y contenido privado excluido de logs.
`ChatAutomaticRetryTest` combina los servicios reales con HTTP simulado y verifica
que un reintento exitoso guarda un único turno y actualiza la cola una sola vez.
Las pruebas transaccionales comprueban que fallar la clasificación conserva un
chat cerrado en `FALLIDA`, sin resultado inventado.

Los reintentos mantienen la petición original y el indicador de espera de mobile.
La política de cierre por datos añade el origen `CIERRE_SIN_DATOS`: el cliente
actualizado sincroniza GET y muestra una entrevista cerrada sin fingir una
clasificación completada. Para cargar las clases nuevas en el proceso local es
necesario reiniciar el backend.

En el cierre de voz y en `POST /api/chat/{id}/clasificacion`, una entrevista
agotada o detenida por el paciente, sin hechos clínicos locales, se evalúa primero
con la petición reducida de alcance. Una decisión no relevante termina sin pedir
clasificación; una relevante conserva el procesamiento clínico. Esa consulta,
generación, verificación y eventual corrección comparten los 45 segundos de la
operación mediante el presupuesto restante. No agrega intentos de clasificación.
Timeouts, cuotas, errores de transporte o un alcance ausente no se interpretan
como ausencia de datos: conservan el procesamiento en `FALLIDA`.

El 25 de octubre de 2026, la regresión de síntomas vacíos pasó junto con la suite
completa: 647 pruebas registradas, sin fallos ni errores, nueve omitidas, en una
base temporal aislada. El smoke opt-in
`ChatHardeningGeminiSmokeTest#flujoRealCorrigeUnaClasificacionEmocionalConSintomasVacios`
fuerza una primera propuesta vacía y usa Gemini real para corregirla y verificarla
con una entrevista emocional ficticia. Comprueba cierre `DATOS_RESUELTOS`, estado
`COMPLETADA`, tres llamadas al generador —la primera simulada—, un solo mensaje
final y un único ingreso a cola. No usa Spring, base de datos ni entrevistas reales.

## Text turn ownership (28 October 2026)

The public message endpoint now commits the patient input and returns `202 ChatDTO`.
An asynchronous worker owns intermediate question retries as well as newly accepted
text closures. It reserves each attempt durably and permits at most two operations
of 45 seconds for typed transient failures. A semantic rejection still permits
only the existing correction within its operation; it does not itself justify a
workflow retry. The same saved patient input is used throughout. The final answer
and completed turn state commit together. Once automatic processing ends without
an acceptable answer, `NO_DISPONIBLE` stops client waiting and blocks text/voice
input. The mobile client reads status; it does not resend the patient message.
This supersedes earlier synchronous-client behavior described above. See
[durable text turns](24-durable-text-turns.md) for restart recovery and API details.
