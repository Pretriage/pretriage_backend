# Cierre de entrevista según datos resueltos

La ejecución vigente del cierre por voz y los reintentos de clasificación desde el
servidor están documentados en [23-server-owned-voice-closure.md](23-server-owned-voice-closure.md).
El cierre no requiere otra negativa, una cortesía ni el botón de reprocesar de mobile.

## Objetivo y plan

La entrevista conserva el relato espontáneo y las preguntas sencillas sobre los
síntomas que el paciente mencionó. El cierre deja de depender de ocho mensajes o
cuatro preguntas. El trabajo se organiza en cuatro partes:

1. Reconstruir el progreso por síntoma y dato desde el historial, con una sola
   política compartida entre texto y voz.
2. Asociar cada respuesta a su pregunta, mantener las aclaraciones en contexto y
   separar un turno ambiguo de un hecho clínico confirmado.
3. Aplicar límites independientes de intentos, estancamiento y recursos totales,
   conservando un motivo distinto cuando el cierre es parcial.
4. Verificar las transiciones y la integración con clasificación, cola y voz en
   pruebas aisladas, además de actualizar las instrucciones de Gemini.

No se agregan entidades JPA ni migraciones. El progreso es una proyección del
historial; no genera síntomas, diagnósticos, prioridades ni preguntas clínicas
locales. Gemini continúa redactando las preguntas y la clasificación final.

## Datos y estados

`ProgresoEntrevista` identifica el síntoma destinatario y la dimensión pendiente:
inicio/duración, intensidad del dolor o temperatura cuando hay fiebre. El impacto
funcional informado se conserva como dato opcional. Dos síntomas distintos conservan sus propios datos; una
respuesta sobre uno no completa automáticamente al otro.

Las preguntas de inicio pueden usar variantes como «cuántas horas o días hace que
empezó» o «cuánto tiempo lleva». Una duración compuesta conserva sus componentes;
una referencia como «desde que se fue», cuando responde a la pregunta de inicio
de un síntoma concreto, se guarda como referencia relativa sin inventar una fecha.
Esa asociación no completa el inicio de otro síntoma ni transforma una referencia
sin contexto en evidencia espontánea sobre todos los síntomas.

Una pregunta plural como «¿Desde cuándo tenés estos síntomas?» puede vincular la
respuesta a los síntomas ya identificados a los que se refiere esa pregunta. La
asociación se fija al preguntar: un síntoma agregado después conserva su inicio
pendiente. Una pregunta que nombra solo un síntoma mantiene su destinatario único.

| Estado | Significado |
| --- | --- |
| `PENDIENTE` | Falta una respuesta que pueda interpretarse para ese dato. |
| `RESPONDIDO` | Hay información comprensible vinculada a ese síntoma y dato. |
| `DESCONOCIDO` | La persona no sabe, no recuerda o no realizó la medición. |
| `RECHAZADO` | La persona prefiere no responder ese dato. |
| `AGOTADO` | Se consumieron los intentos de aclaración sin obtenerlo. |

Los tres últimos estados no equivalen a una respuesta clínica negativa. Un valor
desconocido o agotado no se transforma en ausencia del síntoma, temperatura normal
ni intensidad cero. Una respuesta inválida no se convierte en un valor conocido.
En particular, `38 kg`, `38 cm` o una respuesta afirmativa a «¿te mediste?» no
establecen una temperatura; «8» después de la escala de dolor sí puede establecer
su intensidad, mientras que una cifra acompañada de una unidad o asociación
incoherente requiere aclaración.

La confirmación de que la persona se midió es un subpaso distinto del valor de
temperatura. «Sí, la medí» responde a esa confirmación y constituye avance una sola
vez; no consume un intento de obtener los grados ni completa el valor. Un «no» a
esa confirmación deja la temperatura desconocida. Repetir una confirmación ya
conocida no reinicia los presupuestos.
Se reconocen formas habituales como «¿mediste la temperatura?» o «¿te tomaste
la temperatura?». «No tengo termómetro» y «no me la tomé» dejan el valor desconocido
tanto ante la confirmación como ante una pregunta directa por los grados.

Se admiten las transcripciones de mediciones con símbolo de grado, por ejemplo
`38°`, `38,5°` y `38° y medio`, así como `38 grados y medio`. La última forma se
interpreta como `38.5`, sin perder la fracción. Un valor válido que acompaña al
«sí» de la confirmación también resuelve el valor de temperatura. Las preguntas
por el número o valor que mostró, marcó o se vio en el termómetro corresponden al
mismo dato y comparten sus intentos; cambiar las palabras no reinicia el límite.
Las unidades incompatibles y una escala ambigua como `1 8` siguen sin convertirse
en mediciones válidas.
En una pregunta de impacto, una cuantificación acompañada de una explicación
funcional como «Poco, puedo hacer mis actividades» conserva esa respuesta literal;
no exige contestar únicamente una palabra ni se transforma en intensidad de dolor.

`AGOTADO` impide seguir proponiendo preguntas sobre el dato, pero no invalida una
corrección válida aportada después. Si la persona responde «38» a una pregunta
explícita de temperatura, el valor puede recuperar ese dato
sin sumar intentos nuevos; no se aplica a otro síntoma ni a una escala de dolor.
Un valor admitido correctamente reinicia el contador de falta de avance. Los
turnos excluidos nunca recuperan un dato ni se convierten en evidencia.

La invitación abierta a agregar información se ofrece una sola vez cuando
corresponde. Si aparecen más síntomas, se completan sus datos pertinentes sin
repetir esa invitación. «No tengo otros síntomas» evita explorar síntomas nuevos,
pero no completa por sí solo los datos pendientes de los síntomas ya mencionados.
«No sé» y el rechazo de un dato permiten continuar sin insistir en él.

Contestar la invitación con un aporte que el seguimiento local aún no entiende
no completa automáticamente todos los objetivos. Se permite una aclaración abierta
breve. Si esa aclaración también permanece sin interpretar, se cierra parcialmente
por `LIMITE_RECURSOS` conservando los turnos admitidos y los datos pendientes para
la evaluación de Gemini. Ese límite no convierte el léxico local en una lista de
síntomas permitidos ni crea un síntoma o prioridad por reglas. Si la aclaración
aporta un síntoma reconocido, se agregan sus propios datos pendientes. Una cortesía,
una declinación o un «no sé» en respuesta a la invitación no inicia otra invitación.

## Límites de recursos

Los valores centrales de la política son resguardos técnicos, no criterios de
prioridad médica ni una cantidad mínima de respuestas:

| Límite | Valor inicial | Efecto |
| --- | --- | --- |
| Turnos totales del paciente | 24 | Finalizar con la información disponible al agotarse. Incluye turnos excluidos del contexto clínico. |
| Intentos por dato | 3 | Una pregunta inicial y hasta dos aclaraciones; después marcar únicamente ese dato `AGOTADO` y seguir con los restantes. |
| Respuestas consecutivas sin avance | 4 | Detener una conversación estancada sin presentar los datos pendientes como completos. |

Una repetición no resuelve un dato ni reinicia sus intentos. Los intentos de
transporte y corrección contra Gemini no crean nuevos mensajes ni nuevos objetivos;
mantienen sus límites anteriores y comparten los 45 segundos de cada operación.
El cálculo del progreso no necesita una llamada adicional a un modelo.

## Objetivo del próximo turno

La misma selección que genera la instrucción de entrevista produce
`objetivoDelTurno`: tipo (`DATO`, `INVITACION`, `ACLARACION` o `CERRAR`), identificador
canónico del síntoma y dimensión cuando corresponda. Este control del backend
acompaña el historial admitido en la continuación de texto; no contiene valores,
citas ni texto excluido y no forma parte del resultado clínico.

El backend comprueba los desvíos reconocibles de la pregunta propuesta y el
verificador remoto recibe el mismo objetivo. La pregunta de intensidad usa siempre
la escala del 1 al 10, incluso en una aclaración. Una respuesta explícita de 0
se conserva. Una pregunta de temperatura o impacto corresponde a otra dimensión.
Se conservan las respuestas a preguntas de impacto de entrevistas anteriores.
Ante un término ambiguo, se pide una aclaración neutral antes de registrarlo como
síntoma. La invitación puede referirse a síntomas ya aportados.

Al reconstruir el historial, una invitación no depende únicamente de frases como
«algo más». Si el objetivo inmediatamente anterior al bot era `INVITACION`, una
pregunta abierta con intención de aportar información adicional, sin dimensión
clínica ni síntomas nuevos, puede conservar esa tarea aunque use otra paráfrasis,
por ejemplo «¿Hay algún otro detalle que quieras compartir?». No se aplica si hay
datos pendientes, falta aclarar el motivo o ya
corresponde cerrar, ni a anuncios de registro/cierre. Un «no» admitido responde
esa invitación; un turno excluido sigue sin resolverla. No se agrega una llamada
al proveedor ni se persisten campos nuevos.

Las aclaraciones `AMBIGUO` también respetan el objetivo y los límites de repetición;
no pueden volver a un dato agotado. La redirección fija `FUERA_DE_CONTEXTO` conserva
su tratamiento administrativo. Un desvío permite una sola corrección remota con
feedback fijo del backend, dentro del mismo plazo de 45 segundos.

## Motivos de cierre

- `DATOS_RESUELTOS`: se completaron los pasos de la entrevista; los datos pueden
  estar respondidos, desconocidos o rechazados explícitamente.
- `ALARMA`: se conserva la vía existente de cierre ante una alarma afirmada.
- `SOLICITUD_PACIENTE`: la persona pidió terminar.
- `LIMITE_RECURSOS`: se alcanzó un presupuesto técnico o quedaron datos agotados.
- `SIN_AVANCE`: se agotó el margen de respuestas sin progreso.
- `EN_CURSO`: todavía corresponde continuar o aclarar un dato.

Agotar un único dato no cierra inmediatamente toda la entrevista. El cierre
parcial conserva la distinción entre datos obtenidos y pendientes; si hay hechos
de salud comprensibles, Gemini clasifica únicamente esos hechos con las
validaciones existentes. Si no hay hechos clínicos comprensibles, el cierre no
crea prioridad, resultado clínico ni entrada de cola.

Cerrar la entrevista y completar la clasificación siguen siendo operaciones
distintas. Un fallo del proveedor conserva las respuestas y el estado `FALLIDA`;
el reintento de procesamiento no vuelve a entrevistar ni agrega respuestas del paciente.

En voz y al reintentar un procesamiento fallido, un cierre administrativo sin
hechos clínicos locales necesita además una decisión de alcance remota. Si el
alcance es ambiguo o ajeno a salud, se guarda una única respuesta administrativa,
con `CIERRE_SIN_DATOS`, estado `NO_SOLICITADA` y resultado nulo. No se pide una
clasificación de esa entrada ni se crea prioridad o entrada en cola. Si el alcance
es relevante, se clasifica con el tiempo restante. Los fallos de proveedor o una
respuesta final contradictoria mantienen `FALLIDA`, sin ocultar el error.

Una pregunta abierta para aclarar el motivo conserva esa tarea aunque no contenga
las palabras «físico» o «emocional». Solo se usa esta asociación cuando aún no hay
un motivo comprensible; no desplaza la pregunta de un dato pendiente de síntomas
ya conocidos. Una respuesta emocional afirmativa permite continuar sin crear
una sensación física, y negar una categoría no afirma su opuesta.

Si el turno original quedó excluido, no se recupera como evidencia ni se reenvía
al proveedor. Una nueva respuesta debe aportar por sí misma los hechos que se
clasificarán. El contexto del bot tampoco puede sustituir una cita del paciente.
El contexto `BOT` asociado a ese turno excluido se omite también del envío al
proveedor, para que no vuelva a introducirlo mediante una pregunta que lo citaba.

Para un dato de inicio, una referencia temporal afirmada puede resolver el dato
aunque la respuesta comience indicando que no conoce la fecha exacta. Las
referencias negadas o inciertas siguen sin resolverlo. La clasificación conserva
la relación temporal expresada, sin deducir una fecha ni una evolución.

## Contexto y persistencia

Los turnos marcados `excluidoDelTriage=true` se usan localmente para medir los
intentos y recursos consumidos, pero nunca como evidencia de síntomas o valores.
Su texto no se reenvía a Gemini. Para continuar, el proveedor recibe el historial
clínico admitido y una instrucción del backend sobre el dato que sigue pendiente,
sin valores ni citas del turno excluido. La clasificación final conserva
exclusivamente la evidencia admitida.

Los resultados nuevos guardan `seguimientoEntrevista` dentro de
`Chat.resultadoTriageJson`, además de `origenClasificacion`. Contiene el motivo de
cierre y los estados/intentos de los datos, sin duplicar valores ni citas. Es
metadata del backend; no se solicita a Gemini ni modifica el DTO clínico. Los
resultados históricos sin esta metadata continúan siendo legibles.

Al reintentar un chat fallido que ya estaba cerrado bajo reglas anteriores, el
procesamiento no vuelve a pedir respuestas. Primero puede aplicar la comprobación
de cierre administrativo sin datos indicada arriba; no duplica la transcripción.
Si corresponde clasificar y la proyección actual no puede
reconstruir un motivo terminal, la metadata usa `CIERRE_PREVIO` y conserva los
datos pendientes. El prompt final siempre pide clasificar la entrevista cerrada;
nunca incorpora instrucciones de continuar preguntando.

Texto y voz usan el mismo progreso. Cambiar de modalidad no reinicia los objetivos
ni sus presupuestos. El cierre de voz conserva la herramienta, validación,
drenaje de transcripciones y procesamiento posteriores existentes. El móvil no
decide el cierre contando mensajes y no requiere un protocolo nuevo.

Si el progreso permite cerrar, la sesión de voz no exige otro «no» ni un pedido de
terminación si Live omite su herramienta de finalización. La recuperación cubre
todos los motivos terminales, con reevaluación del progreso y resguardos sobre
transcripciones tardías y rechazos de alcance. Una alarma afirmada por el paciente,
incluidas expresiones como «me falta un poco el aire» o «me cuesta un poco respirar»,
permite terminar sin esperar los datos de otros síntomas. Una negación, una
hipótesis o una cita del bot no establece esa alarma.
Tras un único aviso y un margen acotado, puede cerrar usando la transcripción
literal y el procesamiento de Gemini REST existente, sin construir un resumen
clínico local. Antes de confirmar el cierre vuelve a comprobar el progreso y las
transcripciones tardías. Un rechazo de alcance pendiente no se salta por esa vía.

La proyección también expone `esperaRespuestaPaciente()`, sin persistir otro campo:
indica si el último turno del bot dejó una tarea de respuesta activa sobre un dato
realmente pendiente. No sustituye al motivo de cierre. En voz, las preguntas de
cuantificación pueden cambiar de orden entre datos pendientes; preguntar intensidad
antes de inicio no invalida por sí solo una pregunta. Los datos resueltos, agotados
y las confirmaciones de medición ya contestadas no habilitan otra espera. Un acuse
genérico no equivale a una pregunta; la puntuación de una transcripción de voz
tampoco determina por sí sola si la pregunta es válida.

El estado transitorio `procesando_respuesta` y el watchdog de continuidad aseguran
una indicación visible entre la respuesta del paciente y la siguiente pregunta o
confirmación de cierre. Un fallo de continuación antes de terminar la entrevista
guarda los turnos recibidos sin clasificarlos, conserva la entrevista abierta y
ofrece retomar. No se usa el reintento de clasificación para procesar una frase
abierta ni se requiere que el paciente adivine que debe enviar otra respuesta.

El reconocimiento local de síntomas y expresiones es acotado. No es una lista de
consultas permitidas: un motivo de salud comprensible fuera de ese léxico conserva
el chequeo semántico y la clasificación de Gemini. Los presupuestos técnicos no
validan médicamente la entrevista ni reemplazan esa comprobación de alcance.

## Verificación

La regresión incluye continuar después de ocho mensajes con datos pendientes;
resolver más de cuatro datos; distinguir síntomas y duraciones; aclarar unidades;
aceptar desconocidos y rechazos; acotar repeticiones; y cerrar sin resultado si no
se obtuvo un motivo comprensible. También conserva las pruebas de alarmas,
negaciones, correcciones de Gemini, procesamiento fallido y transcripciones tardías.

La suite completa se ejecuta con `python -B scripts/test_backend_isolated.py` en
una base temporal, con Docker Compose desactivado. Nunca contra la base de la app.

Verificación inicial del 25 de octubre de 2026:

- Suite backend: 624 pruebas registradas, sin fallos ni errores, siete omitidas.
- Foco de progreso, validador y conversación: 133 pruebas, todas aprobadas.
- Android: `assembleDebug` y `testDebugUnitTest`, 14 pruebas aprobadas; Gradle
  detenido al terminar. El APK de prueba apunta a `http://192.168.100.241:8080`.
- Gemini real, con repositorios y cola simulados y sin Spring ni base de datos:
  un relato sintético supera ocho respuestas, resuelve la medición y contesta la
  invitación en el noveno turno; se guarda `DATOS_RESUELTOS`, la prioridad está en
  rango y la cola se actualiza una sola vez. Se comprueba que los tres valores
  excluidos no aparezcan en las entradas enviadas al proveedor.

La prueba real es opcional y consume solicitudes del proveedor. Con
`GEMINI_API_KEY` en el entorno, puede ejecutarse de forma acotada:

```powershell
./mvnw.cmd "-Dtest=ChatHardeningGeminiSmokeTest#flujoRealCierraTrasMasDeOchoRespuestasYFiltraLosDatosExcluidos" "-Dpretriage.gemini.hardening.live=true" "-Dspring.docker.compose.enabled=false" test
```

La regresión posterior de fiebre y dolor de cabeza conserva las dos unidades
inválidas como turnos excluidos, reconoce el valor válido posterior y exige
cuantificar el dolor aún pendiente antes de cerrar. Se verificó también con
Gemini real: después de la respuesta al dato pendiente se guarda
`DATOS_RESUELTOS`, se completa la clasificación y se ingresa a cola una sola vez.
No se envían las unidades excluidas al proveedor. La suite final del backend
registró 642 pruebas, sin fallos ni errores y con ocho omitidas. No se modificó
mobile para esta corrección ni se necesitó otra compilación de Android.
Para repetir solo el caso real:

```powershell
./mvnw.cmd "-Dtest=ChatHardeningGeminiSmokeTest#chatRealPreguntaObjetivoDeDolorYClasificaTrasLaRespuesta" "-Dpretriage.gemini.hardening.live=true" "-Dspring.docker.compose.enabled=false" test
```

Las pruebas de voz usan transporte simulado. Esta validación no incluye una
grabación nueva en un teléfono físico ni una evaluación clínica de prioridades.

La corrección posterior de reconocimiento de inicio y cierre sin herramienta
pasó 663 pruebas backend registradas (diez omitidas), sin fallos ni errores.
Una reconstrucción local del historial del incidente
permite cerrar al responder la invitación por voz, con inicio e impacto resueltos.
El historial persistido no fue reescrito. Un caso ficticio independiente también
pasó generación y verificación con Gemini REST real, conservando la duración
compuesta y sin inventar intensidad numérica de dolor:

```powershell
./mvnw.cmd "-Dtest=ChatHardeningGeminiSmokeTest#flujoRealConservaDuracionNaturalDelDolorDeRodillaSinRepreguntarla" "-Dpretriage.gemini.hardening.live=true" "-Dspring.docker.compose.enabled=false" test
```

Verificación del 28 de octubre de 2026 para las repreguntas por voz:

- La reconstrucción de una entrevista compuesta resuelve el inicio compartido,
  la intensidad y la medición dictada antes de aceptar el cierre. Un síntoma
  agregado después mantiene su propio inicio pendiente.
- Las paráfrasis del termómetro comparten tres intentos. Las unidades incorrectas,
  las cifras ambiguas, los decimales parciales y los turnos excluidos no completan
  valores conocidos. La prueba de «sí» usa límites Unicode para conservar palabras
  como «síntoma» y los filtros de citas.
- La sesión simulada comprueba cierre por alarma cuando Live omite la herramienta,
  incorporación del turno tardío, un único procesamiento y respeto del rechazo
  de alcance. El síntoma del paciente autoriza el cierre; la voz del bot no lo hace.
- 254 verificaciones enfocadas aprobadas, incluidas dos pruebas opcionales con
  Gemini REST real y relatos ficticios de tos y fiebre. Se comprueban los cierres
  `DATOS_RESUELTOS` y `ALARMA`, la conservación de `39,5` grados, clasificación
  `COMPLETADA` y una única actualización de cola. La alarma exige prioridad al
  menos 4 y atención inmediata.
- Suite completa final: 724 pruebas registradas, sin fallos ni errores y 17
  opcionales omitidas, ejecutada con `python -B scripts/test_backend_isolated.py`
  en una base temporal creada y eliminada por el runner, con Compose desactivado.

Las pruebas de voz simulan los eventos y transcripciones de Live; no incluyen
una grabación nueva desde un teléfono. Los casos con Gemini REST usan repositorios
y cola simulados, sin escribir en la base de la app. No se reescriben resultados
históricos. Reiniciar el backend carga los cambios; el contrato de mobile se mantiene.

Con `GEMINI_API_KEY` en el entorno, repetir únicamente los dos casos reales:

```powershell
./mvnw.cmd "-Dtest=ChatHardeningGeminiSmokeTest#cierreRealVozConDatosCompartidosYTemperaturaDictada" "-Dpretriage.gemini.hardening.live=true" "-Dspring.docker.compose.enabled=false" test
```

El reconocimiento de la respuesta a la invitación única acepta negativas orales
repetidas o puntuadas por STT: «No, no.», «No. Nada.», «No, nada.» y «No, nada más,
gracias.». Resuelven la invitación en la primera respuesta, sin otra confirmación.
Estas fórmulas deben abarcar la respuesta completa: «No, pero veo luces extrañas»
sigue requiriendo aclaración. Los turnos excluidos no resuelven la invitación;
una negativa tampoco completa una intensidad u otro dato pendiente. En ese caso
se conserva el dato por aclarar, sin repetir la invitación.

La regresión de estas negativas pasó 184 pruebas Java enfocadas el 28/10/2026,
sin fallos ni errores. Incluye un cierre simulado de sesión tras una sola respuesta
«No, nada.». La recuperación de mobile pasó 38 pruebas unitarias y `assembleDebug`.
No se probaron nuevas grabaciones desde un teléfono ni se modificaron los chats
históricos. Ver los límites del diagnóstico en [08-e2e-chat-debugging.md](08-e2e-chat-debugging.md).

La verificación anterior de continuidad y cierre del mismo día había registrado:

- Suite backend final: 735 pruebas registradas, cero fallos y errores, 18
  opcionales omitidas. Se usó la base temporal del runner aislado.
- Tres casos ficticios de `SesionVozContinuidadGeminiSmokeTest` aprobados:
  `DATOS_RESUELTOS`, `ALARMA` y `SOLICITUD_PACIENTE`. Se simulan los eventos de
  Live y la omisión de la herramienta, y Gemini REST clasifica realmente con
  repositorios/cola simulados. Se exige una única clasificación/actualización de
  cola, una finalización visible y ninguna respuesta adicional del paciente.
- Android: `assembleDebug` y `testDebugUnitTest`, 29 pruebas aprobadas. Gradle se
  detuvo al terminar. El APK apunta a `http://192.168.100.241:8080` mediante el
  override de compilación; la configuración local/default del repositorio no cambia.
- Las regresiones de mobile cubren transcripción fragmentada, progreso controlado
  por el servidor, acuses del bot que no cancelan el indicador, cierre pendiente
  y errores abiertos frente a entrevista ya terminada.

Esta verificación no incluye una grabación nueva en un teléfono. Es necesario
reiniciar el backend e instalar el APK actualizado para probar conjuntamente el
evento de continuidad y su presentación. Los resultados históricos no se alteran.

```powershell
./mvnw.cmd "-Dtest=SesionVozContinuidadGeminiSmokeTest" "-Dpretriage.gemini.hardening.live=true" "-Dspring.docker.compose.enabled=false" test
```
