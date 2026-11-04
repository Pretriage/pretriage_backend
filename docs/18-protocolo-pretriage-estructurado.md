# Pretriage estructurado con fundamento clínico

**Estado:** propuesta para evaluación futura; pendiente de implementación y revisión del contenido clínico.

**Fecha:** 21 de octubre de 2026.

**Decisión del usuario:** conservar este plan como documentación, sin implementar los cambios.

## 1. Objetivo y fundamento

Diseñar un **protocolo académico propio para adultos**, que conserve el chat actual y produzca dos resultados:

- Una **prioridad de atención justificada**, que modifique efectivamente la cola.
- Una **historia clínica breve y organizada**, que ayude al médico durante la evaluación, sin generar diagnósticos.

La estructura tomará como referencia la organización de Manchester por motivo de consulta y criterios de urgencia. Manchester establece que sus herramientas priorizan la atención y no son diagnósticas; además, no avala versiones alteradas. Por eso, el proyecto se presentará como un protocolo propio fundamentado, sin atribuirle la validación de Manchester. [Documento oficial de Manchester](https://www.triagenet.net/classroom/CaptivateF2F/MTS%20Liability%20Statement.pdf).

Para ampliar la información destinada al médico se utilizará **SAMPLE**, contemplado en el material OMS/CICR: síntomas, alergias, medicación, antecedentes, última ingesta y circunstancias del episodio. Se preguntará únicamente lo pertinente al caso. [Atención básica de emergencia — OMS](https://www.who.int/es/publications/i/item/9789241513081).

**Alcance inicial:** adultos de 18 años o más, chat de texto y voz. Pediatría y cuadros obstétricos quedarán fuera de la clasificación automática inicial. El formulario de recepción conservará su funcionamiento y su fallback.

## 2. Protocolo de entrevista y clasificación

### Catálogo clínico versionado

Crear una primera versión con seis motivos: dolor de cabeza, fiebre o malestar general, síntomas respiratorios, dolor torácico, dolor abdominal y lesiones.

Cada ruta tendrá una matriz explícita con:

- Preguntas y datos necesarios para evaluar urgencia.
- Criterios que activan cada prioridad y condiciones que impiden asignarla.
- Información adicional útil para la consulta médica.
- Referencia bibliográfica, adaptación realizada y responsable de revisión.

Un profesional médico deberá revisar y aprobar esa matriz antes de habilitarla en la demo. Los umbrales clínicos no quedarán a criterio del programador ni de Gemini. Esta revisión del contenido se documentará como tal, sin presentarla como validación clínica del sistema.

### Secuencia de la conversación

1. **Identificar el motivo y confirmar la población aplicable.**
2. **Explorar primero los criterios de urgencia** relevantes para ese motivo.
3. **Completar una historia breve**, cuando no corresponda interrumpir la entrevista por urgencia.
4. **Cerrar con un resultado fundamentado o derivar a revisión profesional.**

Se conservará una pregunta sencilla por turno. Se aprovechará lo que el paciente ya haya contado y no se repetirán preguntas respondidas. El límite actual de 20 respuestas será un límite operativo, nunca un motivo para inventar una clasificación.

### Responsabilidades

**Gemini interpretará las respuestas; el backend decidirá la siguiente pregunta, el cierre y la prioridad.**

El backend mantendrá un único estado de entrevista para texto y voz. Gemini Live conservará la conversación por audio, pero solicitará al backend el siguiente paso y no podrá cerrar por su cuenta.

Las reglas aplicarán estos principios:

- Distinguir datos afirmados, negados, desconocidos y no evaluados.
- Conservar la respuesta original y su evidencia; confirmar términos ambiguos.
- No interpretar “no medido” como un valor normal.
- Elegir la mayor urgencia justificada por las reglas.
- No asignar una prioridad menor si faltan datos necesarios para descartar criterios de mayor urgencia.
- Ante un criterio de atención inmediata, interrumpir las preguntas adicionales y activar el aviso correspondiente.

Se conservarán los cinco niveles internos: **5 es la prioridad máxima y 1 la mínima**, sin equipararlos automáticamente con categorías o tiempos de Manchester.

## 3. Integración con la cola y revisión profesional

Actualmente, elegir hospital ya crea una entrada en cola con prioridad normal. La propuesta diferirá ese ingreso hasta tener una prioridad válida:

| Situación | Estado de la consulta | Cola |
|---|---|---|
| Hospital seleccionado | `HOSPITAL_SELECCIONADO` | Sin entrada |
| Entrevista en curso | `PRETRIAGE_EN_PROCESO` | Sin entrada |
| Clasificación fundamentada | `EN_COLA` | Ingreso con prioridad calculada |
| Información crítica insuficiente o caso no cubierto | Nuevo `PENDIENTE_REVISION` | Sin entrada |
| Resolución profesional | `EN_COLA` | Ingreso con prioridad médica |

Así, la demo utilizará la cola real y su ordenamiento actual. Los pendientes tendrán una bandeja visible y un contador independiente en el panel médico.

La revisión inicial permitirá consultar respuestas, faltantes y contradicciones, completar información y asignar prioridad con fundamento. Estará autorizada para médicos del hospital y especialidad correspondientes, sin exigir una atención iniciada ni reservar una sala.

Se agregarán contratos para:

- Consultar los casos pendientes y su detalle.
- Resolver la evaluación inicial indicando prioridad, motivo y datos completados.

La auditoría existente se ampliará con una decisión `ASIGNAR`, que admita prioridad anterior ausente. Se registrarán médico, fecha y fundamento. Una prioridad asignada por el profesional no se almacenará como decisión del bot.

También se adaptarán cancelación, consulta activa y prevención de duplicados para funcionar antes de que exista una entrada en cola. La estimación individual comenzará al ingresar; la estimación general del hospital seguirá disponible durante la selección.

## 4. Resultado, trazabilidad y compatibilidad

El resultado almacenará, mediante contratos tipados:

- Protocolo, versión y ruta utilizada.
- Prioridad, regla determinante y evidencias.
- Historia breve, datos desconocidos y limitaciones.
- Origen de la decisión: reglas o profesional; Gemini quedará identificado como intérprete de las respuestas.

Se mantendrá `Chat.resultadoTriageJson` para resultados completos y se incorporará un resumen parcial tipado para casos pendientes. Se actualizarán conjuntamente DTO, serialización y lectores médicos.

La entrevista podrá quedar cerrada con tres desenlaces diferentes:

- `COMPLETADA`: clasificación válida e ingreso a cola.
- `REQUIERE_REVISION`: información clínica insuficiente, conservada para el profesional.
- `FALLIDA`: problema técnico, con reintento sobre lo ya guardado.

El paciente no tendrá que enviar “ok” para destrabar el proceso. Verá un mensaje sencillo sobre el estado de su evaluación; el fundamento y la clasificación quedarán destinados al médico.

La activación será configurable para consultas nuevas, con versión fijada al seleccionar hospital. Las consultas anteriores conservarán su comportamiento. Se actualizarán la documentación clínica, los flujos, los contratos y los diagramas afectados.

## 5. Verificación y criterios de aceptación

- Casos de referencia revisados por el profesional: urgentes, no urgentes, ambiguos, incompletos y fuera del alcance.
- Mismos datos clínicos producen la misma prioridad en texto y voz.
- Toda prioridad automática permite reconstruir qué regla se aplicó y con qué respuestas.
- Datos desconocidos no se convierten en negativos ni generan prioridad baja por defecto.
- Una urgencia no espera a completar antecedentes secundarios.
- Cierre y reintentos funcionan sin nuevos mensajes del paciente.
- Ingreso y revisión son transaccionales: sin duplicados ni carreras con cancelación.
- Los pendientes no participan del llamado ni de la estimación de la cola.
- Recepción, resultados históricos y revisión médica posterior mantienen compatibilidad.
- Las pruebas de integración se ejecutan exclusivamente en una base aislada mediante el procedimiento del repositorio.

La evaluación académica informará concordancia con el profesional, subclasificación, sobreclasificación y casos derivados a revisión. Esto permitirá justificar el diseño y mostrar sus límites, además de demostrar el funcionamiento de la aplicación.
