# Interrupciones por voz en Android

## Objetivo y alcance

Permitir que el paciente interrumpa al asistente hablando mientras éste reproduce
audio, sin reenviar como voz del paciente el sonido del parlante. La clasificación
clínica, el proveedor y la persistencia conservan sus contratos. No se agregan
servicios de audio externos ni un segundo proveedor de IA.

## Plan de implementación

1. Configurar Android como una sesión de comunicación: captura
   `VOICE_COMMUNICATION`, reproducción `USAGE_VOICE_COMMUNICATION`, modo
   `MODE_IN_COMMUNICATION` y cancelación de eco nativa cuando esté disponible.
   Administrar foco, rutas y recursos durante inicio, errores, fin y reapertura.
2. Habilitar captura simultánea cuando la sesión tenga las capacidades necesarias.
   Conservar la protección por turnos si faltan. La disponibilidad de AEC no demuestra
   por sí sola que el eco acústico haya sido eliminado.
3. Revisar la detección automática de voz y las interrupciones de Gemini Live.
   Ante `interrumpido`, cortar la reproducción local, vaciar ambos buffers y evitar
   escrituras pendientes de la respuesta anterior. No sustituir esta solución por
   desactivar las interrupciones de Gemini.
4. Exponer el modo efectivo en la interfaz con una indicación breve, sin controles
   manuales de eco, términos de implementación ni cambios en el flujo clínico.
5. Probar políticas y eventos con proveedores simulados, compilar el APK Android
   con el backend LAN y verificar su firma. Reservar la validación acústica para un
   dispositivo físico, ya que los tests de software no cubren el parlante real.

## Contrato interno de mobile

`AudioVoz.modoEntrada` expone un `StateFlow<ModoEntradaVoz>`, con valores
`INTERRUMPIBLE` y `POR_TURNOS`. La interfaz usa la selección automática de Android;
el método nativo `configurarModoPorTurnos(activar)` queda interno y no se expone como
control del paciente. La interfaz muestra el modo efectivo,
que puede cambiar al iniciar la captura o cambiar de ruta; no presupone soporte
por haber seleccionado el modo automático.

`AudioVoz.errorAudio` publica un error recuperable si se pierde el foco o falla la
captura/reproducción. El ViewModel cierra la conversación abierta con estado
`DETENIENDO` y muestra el mensaje; no mantiene una pantalla de escucha con el
micrófono apagado. No cancela una clasificación que ya esté en `PROCESANDO`.
Los errores se limpian al iniciar explícitamente una nueva captura.

En modo por turnos, se conserva el control por frames reproducidos y el margen de
300 ms de eco residual. En modo interrumpible, el micrófono permanece activo durante
la respuesta y Gemini decide cuándo se produjo una interrupción. Las escrituras de
audio local deben conservar su invalidación al vaciar la reproducción.

La captura usa fragmentos de 20 ms y la cola de salida conserva como máximo ocho
fragmentos (~160 ms) antes del transporte WebSocket. Al atrasarse la red se descarta
el audio más viejo para no demorar las interrupciones. Esto limita esa cola local;
no constituye una garantía de latencia total ni evita el atraso de redes externas.

Android usa el volumen de comunicación. Respeta auriculares compatibles y selecciona
el parlante cuando corresponde; al terminar restaura sólo las decisiones propias.
No inicia Bluetooth SCO ni solicita un nuevo permiso Bluetooth: en Android anterior
a 12 utiliza SCO sólo si ya estaba activo. Rutas genéricas USB, A2DP o parlantes
Bluetooth sin las condiciones necesarias conservan la protección por turnos.

## Criterios de aceptación

- Sin hablar durante tres respuestas: no aparecen transcripciones del asistente
  como si fueran del paciente ni se interrumpe a sí mismo.
- Al hablar durante una respuesta en modo interrumpible: se recibe la interrupción,
  se descarta el audio anterior y la respuesta del paciente se transcribe una vez.
- La selección automática usa el modo por turnos cuando no tiene las condiciones
  necesarias; la pantalla no promete interrupciones en ese estado.
- Dispositivo sin AEC o ruta no soportada: continúa funcionando con protección y
  comunica el modo efectivo.
- Detener, salir y reabrir: libera micrófono, efectos, foco y selección de ruta
  propios; no deja audio anterior pendiente.
- Cambiar entre parlante y auriculares y probar distintos volúmenes, tanto en
  Android 7–11 como en Android 12 o superior cuando haya equipos disponibles.

## Fuentes de referencia

- [Android: AcousticEchoCanceler](https://developer.android.com/reference/android/media/audiofx/AcousticEchoCanceler).
- [Android: AudioManager y rutas de comunicación](https://developer.android.com/reference/android/media/AudioManager).
- [Android: foco de audio](https://developer.android.com/media/optimize/audio-focus).
- [Gemini Live: protocolo de interrupciones](https://ai.google.dev/api/live).

## Validación de backend

La suite completa terminó con **399 tests, sin fallos ni errores**, mediante
`python scripts/test_backend_isolated.py`. El runner deshabilitó Docker Compose,
creó una base descartable con nombre aleatorio y la eliminó al terminar. No se
ejecutaron pruebas contra la base de la aplicación ni llamadas reales a Gemini.

Las nuevas regresiones comprueban mensajes con `interrupted`, audio cancelado,
transcripción de entrada y `turnComplete` combinados; también salida tardía del
turno cancelado, continuidad del micrófono, persistencia del texto previo y
reanudación del turno siguiente. El setup hace explícitos el VAD automático y
`START_OF_ACTIVITY_INTERRUPTS`; conserva la sensibilidad y tiempos predeterminados
del proveedor. Las transcripciones recibidas no confirman qué audio oyó físicamente
el paciente.

## Validación Android

Las **nueve pruebas** de `ControlEcoVozTest` pasaron y `:composeApp:assembleDebug`
generó el APK. Se compiló para `http://192.168.100.231:8080`, sin modificar la URL
del emulador en `local.properties`. La salida se guardó en un log temporal y se
detuvo Gradle al terminar, según el procedimiento autorizado para esta sesión.

Las pruebas cubren drenaje real frente a cola vacía, margen de eco, invalidación
de contadores tras interrupciones, lecturas que cruzan el margen, capacidades de AEC/auriculares,
ausencia de foco o ruta conocida, preferencia manual y cambio a protección durante
una respuesta. No ejercitan el hardware Android, los cambios reales de ruta/foco ni
el VAD con voz acústica.

No hay un Android conectado por ADB. Queda pendiente ejecutar la lista de aceptación
física con el APK instalado. La cancelación nativa es una primera implementación:
si no suprime suficientemente el eco en un modelo concreto, se debe usar la protección
por turnos internamente y evaluar procesamiento de audio adicional con referencia de
reproducción. El 21/10 se retiró el control manual a pedido del usuario tras confirmar
que las interrupciones funcionaban bien en su teléfono; la protección automática sigue activa.

El APK firmado para pruebas se entrega como
`target/apk/pretriage-interrupciones-debug.apk` en el backend. Hay que reinstalarlo
y reiniciar el backend para cargar los cambios de la sesión Live. La configuración
local revisada tiene `JPA_DDL_AUTO=update`; conservar ese valor al reiniciar.
