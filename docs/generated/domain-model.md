# Generated Domain Model

This file is generated from JPA entity classes under:

```text
src/main/java/com/pretriage/backend/model
```

Regenerate with:

```powershell
python scripts\generate_domain_diagram.py
```

## Entities

- `AdmisionRecepcion`: `src/main/java/com/pretriage/backend/model/recepcion/AdmisionRecepcion.java`
- `AsignacionMedicoHospital`: `src/main/java/com/pretriage/backend/model/personas/AsignacionMedicoHospital.java`
- `AtencionMedica`: `src/main/java/com/pretriage/backend/model/consultas/AtencionMedica.java`
- `AuditoriaHospital`: `src/main/java/com/pretriage/backend/model/acceso/AuditoriaHospital.java`
- `BloqueoCambioContrasenia`: `src/main/java/com/pretriage/backend/model/personas/BloqueoCambioContrasenia.java`
- `CambioContraseniaToken`: `src/main/java/com/pretriage/backend/model/personas/CambioContraseniaToken.java`
- `Chat`: `src/main/java/com/pretriage/backend/model/chat/Chat.java`
- `ConsultaMedica`: `src/main/java/com/pretriage/backend/model/consultas/ConsultaMedica.java`
- `Coordenada`: `src/main/java/com/pretriage/backend/model/hospitales/Coordenada.java`
- `Credencial`: `src/main/java/com/pretriage/backend/model/hospitales/Credencial.java`
- `CredencialProfesional`: `src/main/java/com/pretriage/backend/model/personas/CredencialProfesional.java`
- `Direccion`: `src/main/java/com/pretriage/backend/model/hospitales/Direccion.java`
- `EntradaCola`: `src/main/java/com/pretriage/backend/model/consultas/EntradaCola.java`
- `EspecialidadMedica`: `src/main/java/com/pretriage/backend/model/hospitales/EspecialidadMedica.java`
- `EstudioClinico`: `src/main/java/com/pretriage/backend/model/consultas/EstudioClinico.java`
- `GestorDeCola`: `src/main/java/com/pretriage/backend/model/consultas/GestorDeCola.java`
- `Hospital`: `src/main/java/com/pretriage/backend/model/hospitales/Hospital.java`
- `InvitacionHospital`: `src/main/java/com/pretriage/backend/model/acceso/InvitacionHospital.java`
- `Medico`: `src/main/java/com/pretriage/backend/model/personas/Medico.java`
- `MembresiaHospital`: `src/main/java/com/pretriage/backend/model/acceso/MembresiaHospital.java`
- `Mensaje`: `src/main/java/com/pretriage/backend/model/chat/Mensaje.java`
- `ObraSocial`: `src/main/java/com/pretriage/backend/model/hospitales/ObraSocial.java`
- `Paciente`: `src/main/java/com/pretriage/backend/model/personas/Paciente.java`
- `Recepcionista`: `src/main/java/com/pretriage/backend/model/personas/Recepcionista.java`
- `RevisionPrioridadConsulta`: `src/main/java/com/pretriage/backend/model/consultas/RevisionPrioridadConsulta.java`
- `Sala`: `src/main/java/com/pretriage/backend/model/hospitales/Sala.java`
- `Sector`: `src/main/java/com/pretriage/backend/model/hospitales/Sector.java`
- `SesionAtencionMedica`: `src/main/java/com/pretriage/backend/model/consultas/SesionAtencionMedica.java`
- `SesionRecepcion`: `src/main/java/com/pretriage/backend/model/recepcion/SesionRecepcion.java`
- `Sintoma`: `src/main/java/com/pretriage/backend/model/consultas/Sintoma.java`
- `UsuarioAuth`: `src/main/java/com/pretriage/backend/model/personas/UsuarioAuth.java`

## Mermaid ER Diagram

```mermaid
erDiagram
    AUDITORIA_HOSPITAL {
        Long id
        Instant fecha
        String accion
        String objetivo
        String resultado
    }
    INVITACION_HOSPITAL {
        Long id
        String emailNormalizado
        EstadoInvitacionHospital estado
        RolMembresiaHospital rolesSolicitados
        Long especialidadIds
        String tokenHash
        String matricula
        TipoMatriculaProfesional tipoMatricula
        String jurisdiccionMatricula
        Instant venceEn
        Instant fechaCreacion
        Instant fechaAceptacion
        Boolean emailEnviado
        Instant ultimoIntentoEnvio
        Integer cantidadIntentosEnvio
    }
    MEMBRESIA_HOSPITAL {
        Long id
        EstadoMembresiaHospital estado
        RolMembresiaHospital roles
        Instant fechaCreacion
        Instant fechaAceptacion
        Instant fechaSuspension
    }
    CHAT {
        Long id
        LocalDateTime fechaHoraCreacion
        boolean finalizado
        EstadoClasificacionChat estadoClasificacion
        Integer intentosClasificacionAutomaticos
        Boolean recuperacionAutomaticaClasificacion
        Boolean clasificacionAutomaticaEnCurso
        Boolean clasificacionAutomaticaVoz
        EstadoTurnoTexto estadoTurnoTexto
        String solicitudTurnoTextoId
        Integer intentosTurnoTexto
        Boolean turnoTextoEnCurso
        String errorTurnoTexto
        LocalDateTime turnoTextoIniciadoEn
        Boolean cierreParcial
        EstadoRecuperacionChat estadoRecuperacion
        LocalDateTime proximoReintentoEn
        Long versionEstado
        Integer fallosInternosTurnoTexto
        Integer fallosInternosClasificacion
        Integer fallosDisponibilidad
        LocalDateTime clasificacionIniciadaEn
        Long consultaOrigenId
        Boolean recuperacionLegacyAplicada
        String resultadoTriageJson
    }
    MENSAJE {
        Long id
        String contenido
        AutorMensaje autor
        LocalDateTime fechaHoraEnvio
        Boolean excluidoDelTriage
        String solicitudId
    }
    ATENCION_MEDICA {
        Long id
        EstadoAtencionMedica estado
        LocalDateTime fechaHoraInicio
        LocalDateTime fechaHoraFin
    }
    CONSULTA_MEDICA {
        Long id
        LocalDateTime fechaHoraCreacion
        String codigoLlamado
        NivelDeGravedad nivelDeGravedadBot
        NivelDeGravedad nivelDeGravedadMedico
        String resumenPretriageJson
        EstadoConsulta estadoConsulta
    }
    ENTRADA_COLA {
        Long id
        EstadoEntradaCola estado
        TipoPausaCola tipoPausa
        int prioridad
        long ordenRelativo
        LocalDateTime fechaHoraIngreso
        LocalDateTime fechaHoraLlamado
        LocalDateTime fechaHoraSalidaTemporal
        LocalDateTime fechaHoraUltimaRepregunta
        LocalDateTime fechaHoraLimiteRespuesta
    }
    ESTUDIO_CLINICO {
        Long id
        String nombreArchivo
        String tipoArchivo
        String extensionArchivo
        String descripcion
        LocalDateTime fechaSubida
        Long tamanoArchivo
        String rutaArchivo
        boolean activo
    }
    GESTOR_DE_COLA {
        Long id
    }
    REVISION_PRIORIDAD_CONSULTA {
        Long id
        DecisionRevisionPrioridad decision
        NivelDeGravedad prioridadAnterior
        NivelDeGravedad prioridadNueva
        String motivo
        LocalDateTime fechaHora
    }
    SESION_ATENCION_MEDICA {
        Long id
        EstadoSesionMedica estado
        LocalDateTime fechaHoraInicio
        LocalDateTime fechaHoraFin
        LocalDateTime fechaHoraPausa
    }
    SINTOMA {
        Long id
        String nombre
    }
    COORDENADA {
        Long id
        Double latitud
        Double longitud
    }
    CREDENCIAL {
        Long id
        String numeroAfiliado
        String plan
        LocalDate fechaVencimiento
    }
    DIRECCION {
        Long id
        String calle
        String altura
        String piso
        String codigoPostal
        String ciudad
        String provincia
    }
    ESPECIALIDAD_MEDICA {
        Long id
        String codigo
        String nombre
    }
    HOSPITAL {
        Long id
        String placeId
        String nombre
    }
    OBRA_SOCIAL {
        Long id
        String nombre
        boolean virgente
    }
    SALA {
        Long id
        String nombre
        boolean activa
    }
    SECTOR {
        Long id
        String nombre
        boolean activa
    }
    ASIGNACION_MEDICO_HOSPITAL {
        Long id
    }
    BLOQUEO_CAMBIO_CONTRASENIA {
        int intentosFallidos
        LocalDateTime bloqueadoHasta
    }
    CAMBIO_CONTRASENIA_TOKEN {
        String token
        LocalDateTime fechaHoraExpiracion
        EstadoCambioContrasenia estado
    }
    CREDENCIAL_PROFESIONAL {
        static_final_String JURISDICCION_NACIONAL
        Long id
        String numero
        TipoMatriculaProfesional tipo
        String jurisdiccion
        EstadoCredencialProfesional estado
    }
    MEDICO {
        Long id
        String matricula
    }
    PACIENTE {
        Long id
        Genero generoBiologico
        Genero generoConElQueSeIdentifica
        String nombre
        String apellido
        String numeroDocumento
        TipoDocumento tipoDocumento
        OrigenRegistroPaciente origenRegistro
        LocalDate fechaNacimiento
        String telefono
        String correoElectronico
        Double peso
        Integer altura
    }
    RECEPCIONISTA {
        Long id
    }
    USUARIO_AUTH {
        String id
        String nombre
        String apellido
        String numeroDocumento
        TipoDocumento tipoDocumento
        String correoElectronico
        RolSistema rol
    }
    ADMISION_RECEPCION {
        Long id
        EstadoAdmisionRecepcion estado
        String formularioJson
        String resultadoTriageJson
        LocalDateTime fechaHoraInicio
        LocalDateTime fechaHoraFinalizacion
        LocalDateTime fechaHoraCancelacion
    }
    SESION_RECEPCION {
        Long id
        EstadoSesionRecepcion estado
        LocalDateTime fechaHoraInicio
        LocalDateTime fechaHoraFin
    }
    AUDITORIA_HOSPITAL }o--|| HOSPITAL : hospital
    AUDITORIA_HOSPITAL }o--|| USUARIO_AUTH : actor
    INVITACION_HOSPITAL }o--|| HOSPITAL : hospital
    INVITACION_HOSPITAL }o--|| USUARIO_AUTH : invitadaPor
    INVITACION_HOSPITAL }o--|| USUARIO_AUTH : aceptadaPor
    MEMBRESIA_HOSPITAL }o--|| USUARIO_AUTH : usuario
    MEMBRESIA_HOSPITAL }o--|| HOSPITAL : hospital
    MEMBRESIA_HOSPITAL }o--|| USUARIO_AUTH : creadaPor
    CHAT ||--o{ MENSAJE : mensajes
    CHAT }o--|| PACIENTE : paciente
    MENSAJE }o--|| PACIENTE : pacienteAutor
    ATENCION_MEDICA ||--|| CONSULTA_MEDICA : consultaMedica
    ATENCION_MEDICA }o--|| SESION_ATENCION_MEDICA : sesionAtencionMedica
    CONSULTA_MEDICA }o--|| HOSPITAL : hospital
    CONSULTA_MEDICA }o--|| ESPECIALIDAD_MEDICA : especialidad
    CONSULTA_MEDICA }o--|| MEDICO : medico
    CONSULTA_MEDICA }o--|| SALA : sala
    CONSULTA_MEDICA }o--|| SECTOR : sector
    CONSULTA_MEDICA }o--|| PACIENTE : paciente
    CONSULTA_MEDICA ||--o{ SINTOMA : sintomasBot
    CONSULTA_MEDICA ||--|| CHAT : chat
    ENTRADA_COLA }o--|| GESTOR_DE_COLA : gestorDeCola
    ENTRADA_COLA ||--|| CONSULTA_MEDICA : consultaMedica
    ESTUDIO_CLINICO }o--|| PACIENTE : paciente
    GESTOR_DE_COLA }o--|| HOSPITAL : hospital
    GESTOR_DE_COLA }o--|| ESPECIALIDAD_MEDICA : especialidad
    GESTOR_DE_COLA ||--o{ CONSULTA_MEDICA : consultasEnEspera
    GESTOR_DE_COLA ||--o{ ENTRADA_COLA : entradas
    GESTOR_DE_COLA ||--|| SECTOR : sector
    REVISION_PRIORIDAD_CONSULTA }o--|| CONSULTA_MEDICA : consultaMedica
    REVISION_PRIORIDAD_CONSULTA }o--|| MEDICO : medico
    SESION_ATENCION_MEDICA }o--|| MEDICO : medico
    SESION_ATENCION_MEDICA }o--|| HOSPITAL : hospital
    SESION_ATENCION_MEDICA }o--|| ESPECIALIDAD_MEDICA : especialidad
    SESION_ATENCION_MEDICA }o--|| SALA : sala
    SESION_ATENCION_MEDICA }o--|| SECTOR : sector
    CREDENCIAL }o--|| OBRA_SOCIAL : obraSocial
    CREDENCIAL }o--|| PACIENTE : paciente
    DIRECCION ||--|| COORDENADA : coordenada
    HOSPITAL ||--o{ RECEPCIONISTA : recepcionistas
    HOSPITAL ||--o{ SALA : salas
    HOSPITAL ||--|| DIRECCION : direccion
    OBRA_SOCIAL ||--o{ CREDENCIAL : credenciales
    SALA }o--|| SECTOR : sector
    SALA }o--|| HOSPITAL : hospital
    SALA }o--|| ESPECIALIDAD_MEDICA : especialidad
    SECTOR }o--|| HOSPITAL : hospital
    SECTOR ||--o{ SALA : salas
    SECTOR }o--|| ESPECIALIDAD_MEDICA : especialidad
    SECTOR ||--o{ PACIENTE : pacientesAsignados
    ASIGNACION_MEDICO_HOSPITAL }o--|| MEDICO : medico
    ASIGNACION_MEDICO_HOSPITAL }o--|| HOSPITAL : hospital
    ASIGNACION_MEDICO_HOSPITAL }o--|| ESPECIALIDAD_MEDICA : especialidad
    CREDENCIAL_PROFESIONAL }o--|| MEDICO : medico
    MEDICO ||--|| USUARIO_AUTH : usuarioAuth
    MEDICO ||--o{ CREDENCIAL_PROFESIONAL : credencialesProfesionales
    MEDICO ||--o{ ASIGNACION_MEDICO_HOSPITAL : asignaciones
    PACIENTE ||--|| USUARIO_AUTH : usuarioAuth
    PACIENTE ||--o{ CREDENCIAL : credenciales
    PACIENTE ||--|| COORDENADA : coordenadaActual
    PACIENTE ||--o{ ESTUDIO_CLINICO : historialClinico
    PACIENTE ||--|| DIRECCION : direccion
    RECEPCIONISTA ||--|| USUARIO_AUTH : usuarioAuth
    USUARIO_AUTH ||--o{ CAMBIO_CONTRASENIA_TOKEN : cambiosDeContrasenia
    ADMISION_RECEPCION ||--|| CONSULTA_MEDICA : consultaMedica
    ADMISION_RECEPCION }o--|| SESION_RECEPCION : sesionRecepcion
    SESION_RECEPCION }o--|| RECEPCIONISTA : recepcionista
    SESION_RECEPCION }o--|| HOSPITAL : hospital
```
