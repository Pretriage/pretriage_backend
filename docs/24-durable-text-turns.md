# Turnos de texto persistidos

Actualizado el 5 de noviembre de 2026. La [política de recuperación automática](34-automatic-chat-recovery.md)
define los presupuestos y los desenlaces compartidos con voz y clasificación.

## Aceptación y estados

`POST /api/chat/{id}/mensajes` acepta `contenido` y un UUID `solicitudId` opcional.
Mobile crea una sola clave por envío. El backend confirma HTTP `202 ChatDTO` tras
guardar el mensaje y la solicitud en la misma transacción, antes de llamar a IA.
Una clave repetida con el mismo contenido devuelve el snapshot actual; si cambia
el contenido devuelve 409. Nunca se reenvía el mensaje para recuperar su respuesta.

| Estado del turno | Significado |
| --- | --- |
| `SIN_SOLICITAR` | Sin envío de texto pendiente |
| `PENDIENTE` | Mensaje guardado, procesamiento o reintento interno pendiente |
| `COMPLETADA` | Turno resuelto, con respuesta válida o cierre automático parcial |
| `NO_DISPONIBLE` | Espera temporal por caída real de API; reintento automático |

La clasificación tiene su propio estado: `PENDIENTE`, `COMPLETADA` o `PARCIAL` tras
cerrar. `NO_SOLICITADA` cubre conversaciones sin clasificación solicitada y
`FALLIDA` se conserva para compatibilidad con historias anteriores. Un turno
completado no implica que exista un resultado de IA válido.

## Trabajo y recuperación

`TextoTurnoService` coordina turnos y clasificaciones mediante una cola acotada.
Las reservas se confirman en una transacción independiente; los contadores
monotónicos, la solicitud y el estado impiden confirmar un trabajador obsoleto.
Un escaneo cada segundo retoma trabajos vencidos, incluidas reservas expiradas
tras 150 segundos. Las esperas se guardan en `proximoReintentoEn`; no ocupan
trabajadores durmiendo. Una saturación deja el trabajo disponible para otro escaneo.

Dos ejecuciones fallidas por causas internas cierran la entrevista de forma
parcial y procesan lo guardado. Una clasificación que también agota ese presupuesto
termina `PARCIAL`, sin resultado inventado y sin modificar la cola existente.
Una caída real de API usa backoff persistido y no consume rechazos internos.

El GET incluye `cierreParcial`, `estadoRecuperacion`, `proximoReintentoEn` y
`versionEstado`. El cliente consulta automáticamente durante la recuperación y al
volver a la pantalla, descarta snapshots viejos y reconcilia bloqueos del caché.
No necesita botones de reintento ni de cierre.

Los chats abiertos antiguos bloqueados pueden recuperarse una sola vez si siguen
ligados a una consulta activa. Se excluyen chats reemplazados, cerrados y consultas
canceladas o terminadas. Aplicar primero la migración aditiva
`scripts/migrations/20261105_chat_recovery.sql` sobre esquemas existentes.

## Verificación

Ejecutar `python scripts/test_backend_isolated.py`. Las pruebas de persistencia
verifican confirmación del mensaje, replay, GET sin efectos, reservas, backoff,
cierre parcial, clasificación y recuperación de chats activos sin duplicados.
Las pruebas Spring se ejecutan únicamente con base desechable y Compose deshabilitado.
