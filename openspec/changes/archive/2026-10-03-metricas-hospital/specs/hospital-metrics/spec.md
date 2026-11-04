# Spec Delta

## Purpose

Permite al administrador de un hospital consultar métricas de funcionamiento de su hospital (atención, espera, cola, gravedad y adopción de pretriage) para un período determinado, compararlas entre períodos y renderizarlas en gráficos a partir de un contrato de respuesta estable.

## ADDED Requirements

### Requirement: Acceso restringido al administrador del hospital
El sistema SHALL exponer las métricas de un hospital solo a usuarios con membresía `ADMIN_HOSPITAL` activa de ese hospital. El sistema MUST responder 403 cuando el usuario no administra el hospital solicitado.

#### Scenario: Administrador del hospital consulta sus métricas
- **WHEN** un usuario con membresía `ADMIN_HOSPITAL` activa del hospital 1 solicita `GET /api/admin/hospitales/1/metricas?desde=2026-09-01&hasta=2026-09-30`
- **THEN** el sistema responde 200 con las métricas del hospital 1

#### Scenario: Usuario sin administración del hospital
- **WHEN** un usuario sin membresía `ADMIN_HOSPITAL` activa del hospital solicitado solicita sus métricas
- **THEN** el sistema responde 403 y no devuelve ningún dato

### Requirement: Consulta de métricas por período
El sistema SHALL aceptar un rango de fechas `desde` y `hasta` (inclusive) y MUST rechazar con 400 una solicitud donde `desde` sea posterior a `hasta`. Todos los conteos del período SHALL anclarse en el ingreso a la cola (`EntradaCola.fechaHoraIngreso`) dentro del rango, del hospital solicitado.

#### Scenario: Período válido
- **WHEN** se solicitan métricas con `desde=2026-09-01` y `hasta=2026-09-30`
- **THEN** el sistema devuelve las métricas calculadas sobre los pacientes que ingresaron a la cola del hospital entre ambas fechas

#### Scenario: Rango invertido
- **WHEN** se solicitan métricas con `desde` posterior a `hasta`
- **THEN** el sistema responde 400

### Requirement: Embudo de pacientes atendidos
El sistema SHALL reportar la cantidad de pacientes que ingresaron a la cola en el período y, de esos, cuántos fueron atendidos (atención médica finalizada). El tiempo promedio de espera SHALL calcularse como la diferencia entre el inicio de la atención y el ingreso a la cola, solo sobre los pacientes atendidos del embudo.

#### Scenario: Embudo con pacientes no atendidos
- **WHEN** en el período ingresaron 60 pacientes y 42 de ellos finalizaron la atención
- **THEN** el sistema reporta `ingresaronACola = 60`, `pacientesAtendidos = 42` y el promedio de espera calculado sobre esos 42

#### Scenario: Sin pacientes en el período
- **WHEN** no hubo ingresos a la cola en el período
- **THEN** el sistema reporta conteos en cero y espera promedio sin valor (null)

### Requirement: Distribución de pacientes por nivel de gravedad
El sistema SHALL reportar la distribución de gravedad de los pacientes que ingresaron en el período en dos versiones: una basada en el nivel estimado por el bot de pretriage y otra basada en el nivel definido por el médico. La versión basada en el médico MUST incluir un conteo explícito de pacientes sin revisión médica, de modo que los totales cierren con la cantidad de ingresos.

#### Scenario: Ambas versiones disponibles
- **WHEN** se consultan métricas de un período con pacientes atendidos y no atendidos
- **THEN** el sistema devuelve `distribucionGravedadBot` y `distribucionGravedadMedico`, y el total de cada versión es igual a `ingresaronACola`

### Requirement: Adopción del pretriage con chatbot
El sistema SHALL reportar cuántos pacientes que ingresaron en el período realizaron el pretriage con el chatbot y cuántos no. Un paciente cuenta como realizado solo si su consulta tiene un chat vinculado (chatbot finalizado); las admisiones por recepción y los chats sin finalizar MUST contar como no realizado. Ambos conteos, más cualquier otro caso, MUST cerrar con `ingresaronACola`.

#### Scenario: Paciente con y sin chatbot
- **WHEN** en el período ingresaron 60 pacientes, 45 con chat de pretriage vinculado y 15 sin él
- **THEN** el sistema reporta `pretriageRealizado = 45` y `pretriageNoRealizado = 15`

#### Scenario: Admisión por recepción
- **WHEN** un paciente ingresa a la cola mediante admisión de recepción en el período
- **THEN** cuenta como `pretriageNoRealizado`

### Requirement: Contrato de respuesta para el frontend
El sistema SHALL devolver un payload JSON estable y completo para que el frontend del administrador renderice los gráficos sin lógica de recomposición. `desde` y `hasta` MUST devolverse como string `yyyy-MM-dd` (eco del pedido). Todos los conteos MUST ser enteros ≥ 0 y nunca null. Los porcentajes MUST estar en el rango 0–100 con un decimal, y los promedios de espera en minutos con un decimal. `esperaPromedioMinutos`, `porcentajeAtendidos`, `porcentajePretriageRealizado` y cada `porcentaje` de distribución MUST ser null cuando su denominador es 0.

Contrato canónico (ejemplo de una respuesta 200):

```json
{
  "hospitalId": 1,
  "desde": "2026-09-01",
  "hasta": "2026-09-02",
  "ingresaronACola": 60,
  "pacientesAtendidos": 42,
  "pacientesNoAtendidos": 18,
  "esperaPromedioMinutos": 37.5,
  "porcentajeAtendidos": 70.0,
  "distribucionGravedadBot": [
    { "nivel": "RIESGO_VITAL_INMEDIATO", "cantidad": 3,  "porcentaje": 5.0 },
    { "nivel": "MUY_URGENTE",            "cantidad": 7,  "porcentaje": 11.7 },
    { "nivel": "URGENTE",                "cantidad": 20, "porcentaje": 33.3 },
    { "nivel": "NORMAL",                 "cantidad": 22, "porcentaje": 36.7 },
    { "nivel": "NO_URGENTE",             "cantidad": 8,  "porcentaje": 13.3 }
  ],
  "distribucionGravedadMedico": [
    { "nivel": "RIESGO_VITAL_INMEDIATO", "cantidad": 2,  "porcentaje": 3.3 },
    { "nivel": "MUY_URGENTE",            "cantidad": 4,  "porcentaje": 6.7 },
    { "nivel": "URGENTE",                "cantidad": 15, "porcentaje": 25.0 },
    { "nivel": "NORMAL",                 "cantidad": 17, "porcentaje": 28.3 },
    { "nivel": "NO_URGENTE",             "cantidad": 12, "porcentaje": 20.0 },
    { "nivel": "SIN_REVISION",           "cantidad": 10, "porcentaje": 16.7 }
  ],
  "pretriageRealizado": 45,
  "pretriageNoRealizado": 15,
  "porcentajePretriageRealizado": 75.0,
  "serieDiaria": [
    { "fecha": "2026-09-01", "ingresados": 60, "atendidos": 42, "esperaPromedioMinutos": 37.5 },
    { "fecha": "2026-09-02", "ingresados": 0,  "atendidos": 0,  "esperaPromedioMinutos": null }
  ]
}
```

#### Scenario: Período sin ingresos con payload completo
- **WHEN** no hubo ingresos a la cola en el período
- **THEN** todos los conteos son 0; `esperaPromedioMinutos`, `porcentajeAtendidos` y `porcentajePretriageRealizado` son null; `distribucionGravedadBot` tiene sus 5 niveles y `distribucionGravedadMedico` sus 6 (incluyendo `SIN_REVISION`), todos con `cantidad = 0` y `porcentaje = null`; y `serieDiaria` tiene un elemento por día del rango con `ingresados = 0`, `atendidos = 0` y `esperaPromedioMinutos = null`

#### Scenario: Distribuciones de orden fijo
- **WHEN** se consultan métricas con ingresos en el período
- **THEN** `distribucionGravedadBot` contiene exactamente 5 elementos en el orden `RIESGO_VITAL_INMEDIATO`, `MUY_URGENTE`, `URGENTE`, `NORMAL`, `NO_URGENTE` — siempre presentes, aunque su `cantidad` sea 0 — y `distribucionGravedadMedico` contiene esos mismos 5 seguidos de `SIN_REVISION` al final

#### Scenario: Serie diaria continua que cierra con los totales
- **WHEN** el período abarca varios días
- **THEN** `serieDiaria` tiene un elemento por cada día calendario de `desde` a `hasta` inclusive en orden ascendente, los días sin actividad vienen con conteos en 0 (el frontend no completa huecos), `ingresados` cuenta los ingresos de ese día y `atendidos` cuenta, entre esos, los que finalizaron la atención; además `sum(serieDiaria.ingresados) = ingresaronACola` y `sum(serieDiaria.atendidos) = pacientesAtendidos`

#### Scenario: Espera promedio por día
- **WHEN** un día del período tuvo pacientes atendidos
- **THEN** el `esperaPromedioMinutos` de ese día se calcula igual que el global (inicio de la atención menos ingreso a la cola) sobre los atendidos que ingresaron ese día, y es null en los días sin atendidos

#### Scenario: Invariantes de cierre
- **WHEN** el cliente recibe una respuesta
- **THEN** `pacientesNoAtendidos = ingresaronACola - pacientesAtendidos`, `pretriageRealizado + pretriageNoRealizado = ingresaronACola` y la suma de `cantidad` de cada distribución es igual a `ingresaronACola`

### Requirement: Comparación entre períodos
El sistema SHALL permitir consultar el mismo hospital con distintos rangos de fechas; el cliente MUST poder realizar dos solicitudes independientes con períodos diferentes y recibir respuestas consistentes entre sí para comparar el funcionamiento del hospital.

#### Scenario: Dos períodos consecutivos
- **WHEN** el cliente solicita las métricas de septiembre y luego las de octubre para el mismo hospital
- **THEN** cada respuesta refiere a su propio período con la misma estructura de campos
