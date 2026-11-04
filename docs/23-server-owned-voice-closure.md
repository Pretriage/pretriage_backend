# Cierre de entrevista y recuperación de clasificación desde el backend

## Motivo del cambio

El chat 39 del 28/10/2026 llegó a `COMPLETADA`, pero el historial conservó
negativas y acuses adicionales después de tener los datos resueltos. Los logs
mostraron dos preflights de alcance, aceptación del cierre, drenaje y una
clasificación de 24,188 segundos. La clasificación terminó 44,650 segundos
después de la última consolidación de una respuesta del paciente. Ese tiempo no
es una medición acústica del final del habla ni una garantía del proveedor.

## Entrevista por voz

`ProgresoEntrevista` sigue siendo la autoridad para determinar el cierre. En un
límite de turno confiable, una negativa explícita a la invitación, con datos
resueltos y motivo clínico admitido, inicia el cierre del servidor sin esperar
otra respuesta ni la herramienta de Live. La alarma afirmada y el pedido de
terminar también permiten ese cierre. No se interpreta un prefijo parcial de STT
como una negativa completa ni se usa un campo `finished` inexistente.

Se conserva el drenaje de transcripciones y la reevaluación de datos clínicos
tardíos. Una respuesta nueva útil se conserva y puede invalidar un cierre aún no
confirmado. Los mensajes excluidos y el texto del entrevistador no constituyen
evidencia del paciente. El flujo no depende de decir «okay» ni de repetir «no».

Si una transcripción llega después de un cierre ya aceptado, o vence el drenaje
de ese cierre, se procesa una entrevista parcial a partir del texto completo
conservado, sin el resumen de Live que quedó obsoleto. Los datos faltantes siguen
desconocidos y el control final de alcance/evidencia permanece activo. Esta vía
se elige antes de clasificar; no se usa para eludir un rechazo semántico del
clasificador. Una interrupción antes de confirmar el cierre conserva la entrevista
abierta y sus turnos para continuar por voz o texto.

Para un motivo clínico local admitido se evita el preflight remoto redundante de
alcance; la validación local de cierre/resumen y la validación final de Gemini se
conservan. Los casos ambiguos o sin hechos clínicos mantienen el control de
alcance necesario y pueden cerrar administrativamente sin fabricar un resultado.
La clasificación final puede seguir tardando: cerrar la entrevista detiene las
preguntas, mientras la pantalla informa el procesamiento pendiente.

## Clasificación y reintentos automáticos

Antes de llamar al clasificador de voz se confirma en una transacción corta el
historial literal, `finalizado=true`, `estadoClasificacion=PENDIENTE` y la propiedad
del trabajo automático. El proveedor se ejecuta después; una desconexión del
teléfono no debe cancelar ese trabajo. Se permiten hasta dos intentos de flujo en
total, con reserva persistida del presupuesto antes de cada intento; en voz hay
500 ms de espera entre intentos elegibles y un límite total de 91 s. Cada operación conserva su presupuesto de 45 s
y sus validaciones/corrección existentes. Esto es independiente del límite de
intentos HTTP de cada solicitud Gemini.

Solo un fallo técnico marcado explícitamente como transitorio habilita otro
intento de flujo. Autenticación, certificados, seguridad, JSON inválido y rechazo
semántico no se eluden con ese reintento. No se generan respuestas clínicas
locales. En texto, un fallo transitorio de clasificación final conserva la última
respuesta y programa el intento restante tras el commit; no se repite el POST ni
se recuperan automáticamente fallos de una entrevista que aún sigue abierta.

La reserva y el procesamiento usan bloqueo de fila y verifican el estado. Las
respuestas y la entrada en cola no se agregan otra vez por reintentar. Durante
la recuperación se mantiene `PENDIENTE`; solo el agotamiento o un error definitivo
dejan `FALLIDA`. Un resultado válido deja `COMPLETADA`; un cierre administrativo
deja `NO_SOLICITADA` con la entrevista cerrada.

## Persistencia y reinicio

`Chat` agrega cuatro campos internos, sin agregarlos al DTO:

- `intentosClasificacionAutomaticos`: intentos reservados/consumidos.
- `recuperacionAutomaticaClasificacion`: trabajo inscrito en este flujo.
- `clasificacionAutomaticaEnCurso`: reserva de un intento activo.
- `clasificacionAutomaticaVoz`: modalidad del trabajo, para reconstruir su entrada.

Los valores nulos de registros antiguos se leen como cero/false. Al iniciar el
backend se recuperan de forma asíncrona solamente sus trabajos pendientes
marcados, usando el presupuesto ya guardado. No se inscriben ni reclasifican los
chats históricos `FALLIDA` o `COMPLETADA`. La recuperación de una reserva interrumpida
es la recuperación al reinicio del proceso; no constituye un planificador de
trabajos distribuido con leases entre múltiples despliegues activos.

La migración explícita opcional está en
`scripts/migrations/20261028_automatic_chat_classification.sql`, para despliegues
que gestionan el esquema fuera de Hibernate. Solo agrega campos/defaults; no
activa trabajos históricos. No se ejecutó sobre la base de la aplicación en este
diagnóstico.

## Mobile

No se ofrece un botón de reprocesar ni se llama automáticamente al POST de
clasificación desde el teléfono. La app consulta únicamente `GET /api/chat/{id}`
durante `PENDIENTE`, con hasta 80 lecturas, timeout de 2 s por lectura y plazo total
de 120 s. Si no puede confirmar el resultado, sale de la espera y ofrece consultar
el estado. `FALLIDA` es definitivo para ese trabajo automático y muestra un aviso
breve sin spinner ni invitación a reenviar respuestas. La recuperación de voz
de una entrevista todavía abierta mantiene su acción «Retomar voz».

El endpoint de reintento explícito anterior se conserva por compatibilidad; el
flujo nuevo no depende de que el paciente lo use. Se mantienen el historial
ordenado, la copia de navegación y las defensas frente a lecturas antiguas.

## Verificación del 28/10/2026

La suite completa aislada registró 748 pruebas: 730 aprobadas, 18 opcionales
omitidas, cero fallos y errores. Se ejecutó `python -B scripts/test_backend_isolated.py`
con Compose desactivado y una base temporal creada y eliminada por el runner.
Mockito se cargó como agente al iniciar la JVM para evitar el attach dinámico
del sandbox. Log: `target/isolated-tests.log`.

Las regresiones verifican cierre con la primera negativa, alarma/pedido de cierre,
conservación de fragmentos tardíos, procesamiento de un cierre parcial confirmado,
continuación si el cierre no fue confirmado y honestidad ante fallo de persistencia.
Las pruebas PostgreSQL con proveedor simulado comprueban `PENDIENTE` visible,
reserva confirmada de intentos y éxito después de un fallo transitorio sin duplicar
turnos ni actualización de cola.

Android `assembleDebug` y `testDebugUnitTest` finalizaron con 43 pruebas aprobadas,
sin fallos/errores/omisiones. Se usó log temporal, se detuvo Gradle y el APK mantiene
`http://192.168.100.241:8080`. Se verifican estados iniciales y terminales,
recuperación observada por GET, historia canónica y salida acotada de la espera.

No se hicieron nuevas grabaciones desde un teléfono ni llamadas de diagnóstico
a Gemini con historiales reales. No se reescribió ni reclasificó el chat 39.
Las pruebas no garantizan una latencia concreta del proveedor. Se necesita
reiniciar el backend e instalar el APK actualizado para probar el flujo completo.
