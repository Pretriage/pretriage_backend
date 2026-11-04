1. Flujo normal: selección, pretriage y encolamiento
```mermaid
flowchart TD
    A["Inicio"] --> B["Seleccionar hospital"]
    B --> C{"¿Realiza pretriage?"}

    C -->|"No"| D["Encolar con prioridad mínima"]
    C -->|"Sí"| E["Realizar pretriage"]
    
    E --> F{"¿El caso tiene mayor gravedad?"}
    F -->|"No"| D
    F -->|"Sí"| G["Encolar con prioridad según gravedad"]

    D --> H["Paciente en espera"]
    G --> H
```
2. Flujo de ausencia y atraso (`POST /api/paciente/consulta/cola/pausa-manual` → `POST /api/paciente/consulta/cola/reincorporar`)
```mermaid
flowchart TD
    A["Paciente en espera"] --> B{"¿Se ausenta? (cola/pausa-manual)"}

    B -->|"No"| A
    B -->|"Sí"| C["Desencolar paciente"]
    
    C --> D["Iniciar ventana de espera: 1 hora"]
    D --> E{"¿Marca 'llegué'? (cola/reincorporar)"}

    E -->|"Sí"| F["Volver a encolar"]
    E -->|"No"| G["Timeout de 1 hora"]
    
    G --> H["Cancelar turno"]

    F --> I["Paciente nuevamente en espera"]
```
3. Flujo de cancelación voluntaria (`POST /api/paciente/consulta/cancelar`)
```mermaid
flowchart TD
    A["Selección de hospital activa (EN_COLA / LLAMADO / EN_ESPERA / ATRASADO)"] --> B{"¿Cancela la selección?"}

    B -->|"No"| C["Continúa con la atención"]
    B -->|"Sí"| D["Confirmación (diálogo del cliente)"]
    D --> E["Desencolar automáticamente"]
    E --> F["Estado CANCELADA"]

    F --> G["Deja de contar para la estimación"]
    F --> H["El médico ya no lo puede llamar ni ver"]
    F --> I["Si el triage IA sigue abierto, se cierra"]
    F --> J["La selección no se puede retomar"]
```
Note: `EN_ATENCION` (consultation in progress) and `FINALIZADA` cannot be cancelled (`409 ConflictoDeEstadoException`). Cancellation is idempotent: retrying against an entry already `CANCELADA` succeeds without changes. The endpoint always acts on the patient's most recent `EntradaCola` (the current selection) via `RepoEntradasCola.findFirstByConsultaMedicaPacienteIdOrderByIdDesc`, so historical `CANCELADA`/`FINALIZADA` entries from previous selections are ignored. Out of scope: reception admissions (cancelled via `POST /api/recepcion/admisiones/{admisionId}/cancelar`).
4. Flujo de llamado médico, ausencia y reencolamiento (`cola/atraso/confirmar` / `cola/atraso/renovar` / `cola/reincorporar`)
```mermaid
flowchart TD
    A["Paciente en espera"] --> B["Médico ejecuta llamarPróxima"]
    B --> C["Estado del paciente: LLAMADO"]
    C --> D{"¿Paciente atiende presencialmente?"}

    D -->|"Sí"| E["Atención médica"]
    D -->|"No"| F["Médico marca AUSENTE"]

    F --> G["Desencolar paciente"]
    G --> H["Iniciar ventana de espera: 1 hora"]
    H --> I{"¿Marca 'estoy atrasado'? (cola/atraso/confirmar)"}

    I -->|"No"| J{"¿Marca 'llegué'? (cola/reincorporar)"}
    I -->|"Sí"| K["Extender ventana 30 minutos (cola/atraso/renovar)"]

    J -->|"Sí"| L["Volver a encolar con prioridad previa"]
    J -->|"No"| M["Timeout de 1 hora"]
    M --> N["Cancelar turno"]

    K --> O{"¿Marca 'llegué' dentro de los 30 min? (cola/reincorporar)"}
    O -->|"Sí"| L
    O -->|"No"| P["Timeout de 30 minutos"]
    P --> Q["Cancelar turno"]

    L --> R["Paciente nuevamente en espera"]
    R --> S["Médico puede ejecutar llamarPróxima nuevamente"]
    S --> C
```
