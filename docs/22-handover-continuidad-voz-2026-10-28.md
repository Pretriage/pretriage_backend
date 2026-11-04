# Handover: continuidad y cierre del chat de voz

Actualizado el 28/10/2026, zona horaria America/Buenos_Aires.

## Instrucción para continuar

Este documento conserva el estado histórico previo a los commits del 28/10/2026.
Las referencias a cambios sin commit, procesos y revisiones corresponden a ese
momento. El estado posterior de recuperación está en
`24-durable-text-turns.md`, y los commits y la integración de desarrollo en
`25-development-integration.md`. Leer también los `AGENTS.md` de cada repositorio.

## Repositorios y estado verificado

| Repositorio | Ruta | Rama | HEAD |
| --- | --- | --- | --- |
| Backend | `C:\Users\valen\Documents\Facultad\Proyecto\pretriage_backend` | `feature/chat-voz-gemini-live` | `ccf3458` |
| Mobile | `C:\Users\valen\Documents\Facultad\Proyecto\pretriage_frontend_mobile` | `feature/chat-voz-gemini-live` | `aa005bc` |
| Web, referencia histórica | `C:\Users\valen\Documents\Facultad\Proyecto\pretriage_frontend_web` | No revisada en esta tarea | No verificado |

El estado de backend y mobile se volvió a consultar al crear este documento. No se modificó la rama `desarrollo` en esta tarea.

## Última comprobación de conectividad

- La IP LAN sigue siendo **`192.168.100.241`**, gateway `192.168.100.1`.
- Radmin VPN usa `26.28.179.176`; no confundirla con la IP LAN del APK.
- El código generado del último build mobile usa **`http://192.168.100.241:8080`**.
- Por ese motivo **no fue necesario recompilar por cambio de IP**.
- La última prueba TCP a `192.168.100.241:8080` falló desde la propia PC. Esto prueba falta de conectividad en ese momento; no distingue por sí solo entre backend apagado, enlace de escucha o firewall. Se informó al usuario que compruebe que el backend esté iniciado.
- No se inició ni reinició ningún proceso de su aplicación.

## Objetivo y preferencias del usuario

El chat de voz repetía preguntas o quedaba en silencio después de una respuesta sin cerrar la entrevista. El paciente no conoce el protocolo interno ni sabe que debe pedir que termine: necesita saber si se está procesando, si debe responder o si ya terminó.

Preferencias que deben conservarse:

- Implementar cambios mediante subagentes ligeros cuando lo solicite; se ha utilizado `gpt-6-luna` con razonamiento alto.
- Cerrar por **datos resueltos**, con límites razonables contra bucles y consumo excesivo. No implementar la propuesta guardada en `docs/18-protocolo-pretriage-estructurado.md`.
- Preguntas sencillas, una por vez; cuantificar los síntomas aportados por el paciente. Evitar listas interminables de síntomas ajenos al motivo inicial.
- No mencionar al paciente preclasificación, prioridad ni resultados destinados al médico.
- No restaurar fallback clínico local en el chat. El fallback del formulario web del recepcionista debe conservarse.
- Mantener interrupción de voz y control de eco; el botón de evitar eco dejó de ser necesario.

## Problema que motivó el último arreglo

En el chat histórico 31, el paciente informó dolor de cabeza, fiebre, temperatura de 38 grados y medio, comienzo ayer a la noche, intensidad ocho y luego «Me cuesta un poco respirar». Gemini hizo una advertencia, pero la entrevista continuó entre silencios y frases de despedida. «termina el chat» tampoco se reconocía como solicitud de cierre. Acabó con motivo local `SIN_AVANCE`, aunque había datos suficientes y una alarma.

La clasificación histórica sí terminó: `COMPLETADA`, prioridad 4, atención inmediata. Los logs de `COMUNICACION` mostraban una corrección aceptada; no prueban por sí solos un fallo definitivo. No se editó ni reclasificó manualmente ese chat.

## Cambios implementados: backend

### Cierre controlado por el servidor

`SesionVozChat` recupera la omisión de `finalizar_entrevista` para todos los motivos terminales: `DATOS_RESUELTOS`, `ALARMA`, `SOLICITUD_PACIENTE`, `SIN_AVANCE` y `LIMITE_RECURSOS`.

1. El progreso compartido determina si corresponde cerrar.
2. Se envía un recordatorio para que Gemini Live invoque la herramienta.
3. Si vuelve a completar un turno sin herramienta, o vence el plazo existente de 8 segundos, se reevalúa el estado y se inicia el cierre acotado.
4. Se preservan transcripciones tardías, se drena la sesión y se confirma `entrevista_finalizada`.
5. Se guarda el contexto y se ejecuta la clasificación REST existente con sus validaciones.
6. Solo una clasificación válida emite `triage_finalizado` y actualiza la cola. Un fallo de procesamiento deja la entrevista cerrada con estado recuperable, sin inventar resultados.

Hay controles de versión y exclusión para evitar doble clasificación y carreras entre herramienta, timeout y callbacks. Un rechazo de alcance sin cambios de contexto no se elude con la recuperación.

### Continuidad después de responder

Nuevo evento transitorio:

```json
{"tipo":"procesando_respuesta","activo":true}
{"tipo":"procesando_respuesta","activo":false}
```

`true` comienza con la transcripción del paciente; `false` indica una pregunta útil del bot, cierre aceptado o error recuperable. No significa clasificación ni debe detener el micrófono o la interrupción de audio.

Un watchdog espera actividad útil. Usa ventanas de 8 segundos, admite eventos reordenados y rearma el plazo mientras hay audio o transcripción real. No envía una recuperación inmediatamente al recibir `turnComplete`, ni interrumpe audio activo para hacerlo.

Ante silencio persistente se intenta una sola recuperación controlada. Si sigue sin respuesta útil o no hay un límite de transcripción confiable:

- Congela los callbacks de la sesión agotada.
- Guarda los turnos, incluido el fragmento abierto, con `registrarTurnosVoz`.
- Después de guardar, emite un error claro y termina el canal de voz.
- La entrevista permanece abierta para retomar por voz o texto; no clasifica un fragmento a la fuerza.
- Si falla la persistencia, el mensaje no afirma que se guardó.

### Progreso compartido y reconocimiento

- `ProgresoEntrevista.esperaRespuestaPaciente()` distingue una pregunta/tarea activa de una despedida o mero acuse. Las preguntas de voz pueden resolver datos pendientes en otro orden; no se fuerza el orden canónico de texto.
- La invitación a agregar algo más se resuelve con negativas o respuestas breves pertinentes. Un aporte nuevo opaco obtiene una aclaración abierta; si sigue opaco, termina parcialmente por `LIMITE_RECURSOS`, conservando el texto. Un aporte clínico útil tardío puede retomar datos pendientes.
- Se reconocen pedidos naturales como «termina el chat», «finaliza la entrevista» y «no quiero seguir respondiendo». Negaciones, citas, hipótesis y texto excluido no deben dispararlos.
- La alarma respiratoria admite «me cuesta un poco/bastante respirar», conservando defensas ante negación, hipótesis y citas. Se corrigieron límites Unicode al interpretar «sí».
- Los límites existentes no se redujeron: 24 turnos, 3 intentos por dato, 4 turnos sin avance.

### Ajustes previos incluidos en el mismo trabajo local

- Inicio compartido por una pregunta plural se vincula al conjunto de síntomas al que se refería la pregunta, no a síntomas agregados después.
- «ambos» sin nombres solo comparte un dato cuando hay exactamente dos candidatos; no selecciona arbitrariamente entre tres.
- Se conserva «ayer a la noche» y se interpreta temperatura dictada como «38 grados y medio» = 38,5.
- Valores ambiguos, unidades incompatibles, múltiples temperaturas contradictorias y decimales mal formados no se convierten silenciosamente en una medición válida.
- Confirmaciones de medición y sus paráfrasis comparten presupuesto; un valor explícito durante la confirmación puede resolver el dato.
- «Poco, puedo hacer mis actividades» puede describir impacto funcional sin convertirse en una escala de dolor inventada.

## Cambios implementados: mobile

- Se decodifica `procesando_respuesta` y se muestra un estado de procesamiento separado del cierre de entrevista.
- Una vez observada la autoridad del servidor, fragmentos STT posteriores, audio del bot y `turnComplete` no limpian prematuramente el indicador. Solo los eventos correspondientes, cierre o error lo hacen.
- Existe compatibilidad con un backend que todavía no emita el evento nuevo.
- `entrevista_finalizada` muestra procesamiento pendiente; no presenta un error anticipado mientras llega la clasificación.
- Si el socket termina sin estado definitivo, se ofrece **Actualizar conversación**, evitando un spinner sin fin.
- Un error de voz con entrevista abierta ofrece **Retomar por voz** y permite continuar por texto. Una entrevista cerrada no invita a reenviar respuestas ni a reabrirla.
- No se tocaron el controlador de audio, el algoritmo de eco o barge-in.

## Inventario de cambios locales

Backend, modificados:

```text
docs/06-api-reference.md
docs/08-e2e-chat-debugging.md
docs/14-chat-voz-gemini-live.md
docs/21-cierre-por-datos-resueltos.md
src/main/java/com/pretriage/backend/services/entrevista/ProgresoEntrevista.java
src/main/java/com/pretriage/backend/services/voz/SesionVozChat.java
src/main/java/com/pretriage/backend/services/voz/ValidadorCierreEntrevistaVoz.java
src/test/java/com/pretriage/backend/services/ChatHardeningGeminiSmokeTest.java
src/test/java/com/pretriage/backend/services/entrevista/ProgresoEntrevistaTest.java
src/test/java/com/pretriage/backend/services/voz/SesionVozChatTest.java
src/test/java/com/pretriage/backend/services/voz/ValidadorCierreEntrevistaVozTest.java
```

Backend, archivos nuevos aún sin seguimiento antes de este handover:

```text
src/test/java/com/pretriage/backend/services/voz/EntrevistaVozBucleRegresionTest.java
src/test/java/com/pretriage/backend/services/voz/SesionVozContinuidadGeminiSmokeTest.java
```

Mobile, modificados:

```text
README.md
composeApp/src/commonMain/kotlin/com/proyecto_final/triage/network/ChatVozCliente.kt
composeApp/src/commonMain/kotlin/com/proyecto_final/triage/screens/ChatScreen.kt
composeApp/src/commonMain/kotlin/com/proyecto_final/triage/viewmodels/ChatStatusPresentation.kt
composeApp/src/commonMain/kotlin/com/proyecto_final/triage/viewmodels/ChatViewModel.kt
composeApp/src/commonTest/kotlin/com/proyecto_final/triage/ChatStatusPresentationTest.kt
```

Este handover agrega además `docs/22-handover-continuidad-voz-2026-10-28.md`. No hay cambios de entidades JPA ni migraciones en esta tarea.

## Verificaciones ya completadas

### Backend aislado

```powershell
python -B scripts/test_backend_isolated.py
```

- Resultado final: 735 pruebas registradas; **717 aprobadas, 18 opcionales omitidas**, cero fallos y cero errores.
- Completado el 28/10/2026 a las 01:48:59 -03:00.
- Log: `target/isolated-tests.log`.
- Base temporal `pretriage_test_96eefe2b68f44377bc626d76b6f4c129`, creada y eliminada por el runner. No se usó la base de la app.

### Clasificación Gemini real con sesiones de voz simuladas

```powershell
.\mvnw.cmd '-Dtest=SesionVozContinuidadGeminiSmokeTest' '-Dpretriage.gemini.hardening.live=true' '-Dspring.docker.compose.enabled=false' test
```

- Tres casos sintéticos aprobados: `DATOS_RESUELTOS`, `ALARMA`, `SOLICITUD_PACIENTE`.
- Real `ChatService` + cliente Gemini REST; repositorios/cola simulados y reloj manual.
- Comprueba omisión de herramienta, recuperación, clasificación única y cierre final sin un mensaje extra del paciente.
- Sin conexión real Live, micrófono, Auth0 ni base de datos. **Esto no es una prueba de audio de extremo a extremo**.
- Completado a las 01:49:32 -03:00. Log: `target/voice-continuity-gemini-smoke.log`.
- La API key se cargó solo en el entorno del proceso y se restauró; no mostrar ni guardar secretos.

### Mobile

```powershell
.\gradlew.bat :composeApp:assembleDebug :composeApp:testDebugUnitTest '-PBACKEND_BASE_URL=http://192.168.100.241:8080' --console=plain
```

- Build exitoso, **29 pruebas unitarias aprobadas**, cero fallos/errores/omitidas.
- Gradle detenido al terminar; no hay builds pendientes de esta tarea.
- Log final: `C:\Users\valen\AppData\Local\Temp\pretriage-voice-continuity-final-157999da6b214fcc829113d8a5b305ae.log`.
- `git diff --check` pasó en ambos repositorios; solo advertencias de conversión LF/CRLF.

## APK disponible

```text
C:\Users\valen\Documents\Facultad\Proyecto\pretriage_frontend_mobile\composeApp\build\outputs\apk\debug\composeApp-debug.apk
```

- Tamaño: 82.272.111 bytes.
- Modificado: 28/10/2026 01:47:17 -03:00.
- Backend configurado: `http://192.168.100.241:8080`.
- Generado, pero no instalado por el agente en el teléfono. Se pidió al usuario reiniciar backend e instalarlo.
- El valor ignorado de `local.properties` puede seguir siendo `10.0.2.2`; el APK anterior se construyó con el override `-P`. No deducir la URL efectiva solo desde ese archivo.

## Pendientes y límites de la verificación

1. Resolver la conectividad actual al backend si el usuario sigue sin poder entrar: comprobar proceso, puerto 8080, dirección de escucha y firewall. No asumir un cambio de IP.
2. Probar en el teléfono con el APK nuevo y el backend actualizado: una entrevista normal, un cierre solicitado, voz con alarma y una interrupción/silencio. Observar que siempre haya pregunta, indicador de procesamiento, cierre o acción de recuperación.
3. Si reaparece un problema, revisar chat y logs actuales **en lectura**, correlacionando ID, timestamps, eventos de voz y versión ejecutada. No suponer que el proceso ya usa el código nuevo.
4. Commit/push/PR solo cuando corresponda a la siguiente solicitud. Los cambios actuales aún no están commiteados; no perder archivos nuevos de pruebas.

## Precauciones de trabajo

- Respetar los `AGENTS.md` y mantener documentación de contratos/flujo junto con el código.
- Preferir `codebase-memory-mcp` (`search_graph`, `trace_path`, `get_code_snippet`, `query_graph`, `search_code`). Indexar si falta el proyecto. Si el grafo está desactualizado o no devuelve las clases nuevas, usar archivos conocidos/`rg` como fallback.
- JDK usado: `C:\Program Files\Eclipse Adoptium\jdk-21.0.6.7-hotspot`.
- No ejecutar Maven y Gradle concurrentemente ni competir con la compilación Java del IDE sobre `target/classes`. Ante `.class` corruptos, seguir el procedimiento de limpieza documentado.
- **Nunca ejecutar integración Spring contra `pretriage_db` ni usar `ddl-auto=create-drop` sobre la app.** Existe un incidente anterior documentado en `docs/15-test-isolation-incident.md`.
- Las pruebas completas deben usar `scripts/test_backend_isolated.py`; Docker Compose debe estar deshabilitado para no reemplazar el datasource aislado.
- No exportar historiales reales a Gemini para diagnosticar sin autorización. Los smoke tests usan exclusivamente relatos sintéticos. Preservar filtrado de mensajes excluidos y sus turnos BOT asociados.
- No leer/imprimir `.env`, tokens, API keys ni `local.properties` completos. Evitar secretos en logs, commits y documentos.
- La skill `build-output-temp` requerida por mobile faltaba. El usuario ya autorizó en esta conversación el procedimiento equivalente: redirigir Gradle a un log temporal, mostrar el resultado y ejecutar `.\gradlew.bat --stop`. No pedir la misma autorización otra vez; cargar la skill si ahora está disponible.
- Los cambios fuera del workspace backend pueden necesitar escalamiento del sandbox; usar herramientas de aprobación para ello. No confundir permiso de filesystem con necesidad de volver a consultar una decisión funcional ya autorizada.
- Para delegar implementación usar subagentes, no crear chats nuevos del usuario. `followup_task` reactiva un subagente terminado; `send_message` solo entrega mensajes.

## Documentación relacionada

- `docs/14-chat-voz-gemini-live.md`: flujo de voz actualizado.
- `docs/21-cierre-por-datos-resueltos.md`: progreso y criterios de cierre.
- `docs/06-api-reference.md`: contratos y eventos.
- `docs/08-e2e-chat-debugging.md`: verificación y diagnóstico.
- `docs/16-voice-interruptions.md`: interrupción y audio.
- `docs/19-gemini-retries.md`: reintentos del proveedor.
- `docs/20-chat-hardening.md`: alcance y validaciones.

Referencias oficiales revisadas durante el trabajo: https://ai.google.dev/gemini-api/docs/live-api/tools, https://ai.google.dev/gemini-api/docs/live-api/capabilities y https://ai.google.dev/api/live. `inputTranscription` llega de forma independiente; no se debe depender de un campo `finished` no documentado. `clientContent.turnComplete=true` puede interrumpir una generación activa.
