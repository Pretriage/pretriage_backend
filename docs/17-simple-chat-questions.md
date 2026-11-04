# Entrevista breve y cuantificación de síntomas

La entrevista de texto y voz empieza por el relato espontáneo del paciente. El bot
puede aclarar un motivo que no se entiende y, cuando ya conoce los síntomas que el
paciente enumeró, puede preguntar por su inicio o duración y por cuánto lo afecta.
La cuantificación se limita a esos síntomas: no se pregunta por síntomas nuevos ni
se traslada a uno la duración, intensidad o impacto informado para otro.

El alcance de salud incluye el malestar emocional y la salud mental. Un relato de
angustia, tristeza, ansiedad, una pérdida o una relación puede ser un motivo pertinente
sin que la persona mencione un síntoma físico. En ese caso el bot puede hacer una sola
invitación abierta para entender qué siente o cómo le afecta; no le exige intensidad
del dolor, temperatura ni un cuestionario de síntomas físicos. Una metáfora como
«vacío en el pecho» no se interpreta como dolor torácico o alarma sin afirmación literal.
Si también afirma un síntoma físico, se mantiene la cuantificación pertinente para ese
síntoma. Si aclara que una sensación es física, esa aclaración sirve de contexto para
entenderla, pero no autoriza a renombrarla como dolor. Una intensidad de una sensación
no dolorosa no se copia en `intensidadDolor`. Una emoción negada, por sí sola, no activa
la entrevista emocional.

Cuando el motivo todavía es ambiguo, la primera aclaración invita a explicarlo
con palabras propias y puede retomar la expresión original de forma neutral.
No ofrece interpretaciones físicas o emocionales ni pregunta todavía por dolor,
duración, intensidad o causas. Una pregunta concreta posterior solo corresponde
si la respuesta deja una ambigüedad relevante. Esta aclaración para entender el
motivo es distinta de la invitación única a agregar información al final.

En texto, la invitación puede estar precedida inmediatamente por un reconocimiento
neutral muy breve, como «Entiendo». Sigue siendo una sola invitación pertinente;
no habilita otra pregunta, datos nuevos, interpretaciones, anuncio de registro ni
cierre. Tanto la generación como la verificación semántica comparten ese criterio.

El cierre normal depende del progreso por síntoma y dato: respondido, desconocido
o rechazado. Se mantiene una única invitación abierta para que el paciente agregue
información útil. Ya no se cierra por contar cuatro preguntas u ocho respuestas.
Hay presupuestos técnicos independientes de 24 turnos totales, tres intentos por
dato y cuatro respuestas consecutivas sin avance. Al agotarse un dato se continúa
con los restantes; el agotamiento se distingue de una respuesta clínica conocida.
Cada turno trata un solo concepto o dato, según las reglas que se indican al modelo. El bot aprovecha
la información espontánea y no vuelve a preguntar lo que
ya quedó respondido. Para dolor puede preguntar intensidad de 0 a 10 o el impacto en
las actividades cotidianas; para fiebre puede pedir la temperatura solo si el
paciente la midió. Si no sabe la cifra o no tiene una medición, ese dato queda como
desconocido y no se insiste. Como el resultado estructurado no tiene campos
específicos para temperatura e impacto, una temperatura medida se conserva con su
unidad en los síntomas u observaciones y el impacto en observaciones.

Una respuesta como «no tengo otros síntomas» detiene la búsqueda de síntomas nuevos,
pero permite completar la cuantificación de los síntomas que el paciente ya nombró,
dentro de los presupuestos de aclaración. «No quiero seguir» detiene la entrevista de
inmediato. La invitación abierta se hace como máximo una vez y nunca se repite. Un
relato que ya incluye espontáneamente la cuantificación evita preguntas redundantes.
Después de resolver los datos, se ofrece la invitación una vez si
todavía no se hizo ni el paciente dijo que no tenía más síntomas. Si responde que no
a esa invitación, la entrevista se cierra con los datos disponibles; no se agregan
preguntas no solicitadas sobre antecedentes, medicamentos o alergias. Un
signo de alarma claro cierra la entrevista de inmediato para la clasificación urgente
y la validación de prioridad existentes. Los límites son resguardos de recursos,
no una cantidad mínima ni una prueba de suficiencia clínica. Los cierres por límite
o estancamiento conservan los datos desconocidos y el motivo de cierre parcial.

Una negativa se interpreta según la pregunta anterior. Un «no» a una pregunta de
medición no equivale a una cifra ni a ausencia del síntoma; un «no» a la invitación
indica que no desea agregar información. Ninguna negativa permite inferir que se
descartaron alarmas que no se mencionaron. Los datos no informados permanecen
desconocidos en la clasificación.

Confirmar «sí, la medí» constituye un subpaso distinto de informar los grados:
cuenta como avance una sola vez y no consume intentos del valor. Una corrección
válida posterior puede recuperar un dato agotado cuando se vincula a la pregunta
o al síntoma correcto; no habilita a insistir indefinidamente en ese dato.

Una referencia temporal relativa comprensible puede responder el inicio del
síntoma preguntado. No se exige traducirla a horas o días ni se inventa una fecha.
Las aclaraciones abiertas usan vocabulario cotidiano y no afirman por sí mismas
un síntoma, una causa o un origen físico o emocional.

## Implementación

- `LenguajeClinicoChat` agrupa las instrucciones de entrevista y comunicación;
  `ProgresoEntrevista` reconstruye los objetivos por síntoma y dato;
  `ValidadorCierreEntrevistaVoz` comparte ese criterio entre texto y voz.
- `LenguajeClinicoChat.REGLAS_PREGUNTA_TEXTO` redacta el próximo turno sin instrucciones
  contradictorias para cerrar la entrevista. El formato no exige exactamente un signo `?` ASCII:
  la puntuación y la presencia o ausencia de signos de interrogación no invalidan ni
  alteran el mensaje que devuelve Gemini.
- Las respuestas intermedias de texto contienen `mensaje` y `estadoEntrada`; Gemini genera
  cada pregunta y el backend decide si continúa o solicita la clasificación final.
  La entrada lleva un `objetivoDelTurno` controlado, compartido por el generador y
  el verificador; las aclaraciones ambiguas también deben respetarlo.
- Las entradas ambiguas reciben una aclaración verificada y pertinente al dato
  pendiente; las ajenas al triage mantienen una redirección breve. Ambas quedan
  excluidas como evidencia clínica, pero cuentan para los resguardos de recursos.
  Su texto original queda guardado localmente; las aclaraciones usan el estado del
  dato pendiente, sin reenviar ese texto al proveedor. Véase
  [alcance y validación semántica](20-chat-hardening.md).
- Una respuesta intermedia incompatible con el contrato o una pregunta repetida admite como máximo
  una corrección remota por turno dentro del presupuesto total de 45 segundos. La
  puntuación por sí sola no provoca error 500 ni una corrección. Se conserva el texto
  de Gemini; no se generan preguntas localmente ni se inventa contenido clínico.
- La política de cierre de voz usa el historial guardado y las transcripciones
  pendientes. Un resumen de voz no puede inventar datos ni ampliar el conjunto de
  síntomas aportados.
- El validador permite cerrar relatos emocionales tras una invitación abierta y su
  respuesta, sin forzar cuantificación física; el preflight semántico conserva la
  validación de alcance antes de aceptar el cierre.
- Las alarmas cierran inmediatamente; el resto de los cierres usa objetivos resueltos
  e invitación única, o un motivo explícito de agotamiento o falta de avance.
- La clasificación final conserva los datos desconocidos como no informados y aplica
  las validaciones compartidas de estructura y alarmas.

El detalle de estados, límites y metadata de auditoría está en
[cierre por datos resueltos](21-cierre-por-datos-resueltos.md).

## Verificación

Verificación del 21 de octubre de 2026: **458 pruebas Java** completas pasaron con
`python scripts/test_backend_isolated.py`, contra una base PostgreSQL temporal
aleatoria y con Docker Compose desactivado. La verificación previa de los helpers
E2E fue de **12 pruebas Python** aprobadas; esos scripts no cambiaron en el ajuste
de puntuación. No se ejecutaron pruebas de integración contra la base de la app.

Las regresiones cubren el «no tengo otros síntomas» anterior a la cuantificación,
duración e intensidad/impacto, fiebre negada, temperatura medida o desconocida,
inicios diferentes de dolor y fiebre, rechazo a continuar, límites de preguntas,
alarmas y la conservación de la transcripción y del resultado final. Una pregunta
sobre «desde cuándo te afecta» no cuenta como una respuesta de intensidad. El
resultado de una pregunta solo se considera obtenido si hay un dato válido o una
respuesta desconocida/declinada; preguntar por sí solo no completa la dimensión.

`ChatQuestionFormatTest` reproduce respuestas de Gemini sin `?`, con `??`, con
`？` y con varios interrogantes. Se conserva el mensaje y se registra un solo turno,
sin una llamada correctiva por puntuación. También verifica corrección acotada de
respuestas vacías o incompatibles y preguntas repetidas, sin fallback local ni
duplicar el historial. La continuación usa un prompt propio sin instrucciones de
cierre; la clasificación final conserva sus validaciones.

La comprobación sintética anterior con `ChatService`, `TriageIaClient` y Gemini REST reales,
usando repositorios y cola simulados, obtuvo una pregunta de duración y luego una
escala de intensidad del dolor. La llamada siguiente agotó el tiempo de espera,
por lo que esa ejecución no verificó el cierre completo contra el proveedor. La
suite con proveedores simulados sí cubre el flujo completo y los reintentos. No
se usaron datos de pacientes ni se escribió en la base de la app. Tampoco fue una
prueba del micrófono o de los WebSockets de voz.

Para verificar el ajuste de puntuación, otra comprobación usó esos tres servicios
reales y pidió a Gemini una invitación imperativa sin interrogantes. El backend
aceptó el texto con origen `GEMINI`, mantuvo la entrevista abierta y registró un
solo turno, en aproximadamente 3,4 segundos. El estilo fue forzado únicamente por
la sonda; la respuesta procedió de Gemini, sin reemplazo local. Los repositorios
y la cola estaban simulados y no se escribió en la base de la app. Es una prueba
de integración de componentes, no del endpoint HTTP completo ni de la latencia
habitual del proveedor.

Como ejemplo sintético de la política vigente: si una persona menciona dolor de
cabeza y fiebre y luego dice que no tiene otros síntomas, el bot deja de buscar
síntomas nuevos, pero todavía puede preguntar —sin repetir la invitación abierta—
por duración o impacto de esos síntomas. Si la persona no conoce su temperatura y
no la midió, se conserva como desconocida y no se vuelve a pedir la cifra. No se
incluyen datos de conversaciones reales en esta documentación.
