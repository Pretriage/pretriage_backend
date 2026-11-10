package com.pretriage.backend.services;

import com.pretriage.backend.controllers.dtos.*;
import com.pretriage.backend.exceptions.AtencionEnCursoException;
import com.pretriage.backend.exceptions.ConflictoDeEstadoException;
import com.pretriage.backend.exceptions.ProveedorIaException;
import com.pretriage.backend.exceptions.RecursoNoEncontradoException;
import com.pretriage.backend.model.consultas.*;
import com.pretriage.backend.model.acceso.EstadoMembresiaHospital;
import com.pretriage.backend.model.acceso.MembresiaHospital;
import com.pretriage.backend.model.acceso.RolMembresiaHospital;
import com.pretriage.backend.model.hospitales.Credencial;
import com.pretriage.backend.model.hospitales.EspecialidadMedica;
import com.pretriage.backend.model.hospitales.Direccion;
import com.pretriage.backend.model.hospitales.Hospital;
import com.pretriage.backend.model.personas.*;
import com.pretriage.backend.model.recepcion.*;
import com.pretriage.backend.repositories.*;
import jakarta.transaction.Transactional;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class AdmisionRecepcionService {
    private static final ZoneId ZONA_ARGENTINA = ZoneId.of("America/Argentina/Buenos_Aires");
    private static final List<EstadoConsulta> ESTADOS_ACTIVOS = List.of(
            EstadoConsulta.PENDIENTE, EstadoConsulta.HOSPITAL_SELECCIONADO,
            EstadoConsulta.PRETRIAGE_EN_PROCESO, EstadoConsulta.PRETRIAGE_FINALIZADO,
            EstadoConsulta.EN_COLA, EstadoConsulta.EN_REVISION, EstadoConsulta.LLAMADO, EstadoConsulta.EN_ESPERA,
            EstadoConsulta.ATRASADO, EstadoConsulta.EN_ATENCION);
    private final RepoRecepcionistas repoRecepcionistas;
    private final RepoHospitales repoHospitales;
    private final RepoMembresiasHospital repoMembresiasHospital;
    private final RepoSesionesRecepcion repoSesionesRecepcion;
    private final RepoAdmisionesRecepcion repoAdmisionesRecepcion;
    private final RepoPacientes repoPacientes;
    private final RepoConsultasMedicas repoConsultasMedicas;
    private final RepoEspecialidadesMedicas repoEspecialidadesMedicas;
    private final RepoDirecciones repoDirecciones;
    private final RepoCredenciales repoCredenciales;
    private final TriageFormularioService triageFormularioService;
    private final IngresoColaService ingresoColaService;
    private final AsignacionSectorService asignacionSectorService;
    private final EstimacionAtencionService estimacionAtencionService;
    private final ObjectMapper objectMapper;

    
    public List<RecepcionHospitalDTO> obtenerHospitales(String auth0Id) {
        obtenerRecepcionista(auth0Id);
        return repoHospitales.findByRecepcionistasUsuarioAuthId(auth0Id).stream()
                .filter(h -> repoMembresiasHospital.findByUsuarioIdAndHospitalId(auth0Id, h.getId())
                        .map(m -> m.getEstado() == EstadoMembresiaHospital.ACTIVA
                                && m.getRolesEfectivos().contains(RolMembresiaHospital.RECEPCIONISTA))
                        .orElse(true))
                .map(h -> new RecepcionHospitalDTO(h.getId(), h.getNombre(), h.getEspecialidades().stream()
                        .map(e -> { EspecialidadMedicaDTO dto = new EspecialidadMedicaDTO(); dto.setCodigo(e.getCodigo()); dto.setNombre(e.getNombre()); return dto; })
                        .toList()))
                .toList();
    }

    public SesionRecepcionDTO obtenerSesionActiva(String auth0Id) {
        return repoSesionesRecepcion.findFirstByRecepcionistaUsuarioAuthIdAndEstado(auth0Id, EstadoSesionRecepcion.ACTIVA)
                .filter(s -> s.getHospital() == null || repoMembresiasHospital.findByUsuarioIdAndHospitalId(auth0Id, s.getHospital().getId())
                        .map(m -> m.getEstado() == EstadoMembresiaHospital.ACTIVA
                                && m.getRolesEfectivos().contains(RolMembresiaHospital.RECEPCIONISTA))
                        .orElse(true))
                .map(this::mapear).orElse(null);
    }

    public PacienteRecepcionDTO buscarPaciente(String auth0Id, Long sesionId, String dniIngresado) {
        obtenerSesionActiva(auth0Id, sesionId);
        String dni = dniIngresado.replaceAll("\\D", "");
        return repoPacientes.findByNumeroDocumentoOrUsuarioAuthNumeroDocumento(dni, dni)
                .map(p -> {
                    Direccion d = p.getDireccion();
                    UsuarioAuth usuario = p.getUsuarioAuth();
                    EstadoConsulta estadoAtencion = repoConsultasMedicas
                            .findFirstByPacienteIdAndEstadoConsultaIn(p.getId(), ESTADOS_ACTIVOS)
                            .map(ConsultaMedica::getEstadoConsulta)
                            .orElse(null);
                    return new PacienteRecepcionDTO(p.getId(), dni,
                            p.getNombre() != null ? p.getNombre() : usuario != null ? usuario.getNombre() : null,
                            p.getApellido() != null ? p.getApellido() : usuario != null ? usuario.getApellido() : null,
                            p.getFechaNacimiento(), p.getGeneroBiologico(), p.getTelefono(),
                            p.getCorreoElectronico(), d != null ? d.getCalle() : null,
                            d != null ? d.getAltura() : null, d != null ? d.getPiso() : null,
                            d != null ? d.getCiudad() : null, d != null ? d.getProvincia() : null,
                            d != null ? d.getCodigoPostal() : null, p.getUsuarioAuth() != null,
                            estadoAtencion != null, estadoAtencion);
                })
                .orElse(null);
    }
@Transactional
    public SesionRecepcionDTO iniciarSesion(String auth0Id, Long hospitalId) {
        Recepcionista recepcionista = obtenerRecepcionista(auth0Id);
        repoHospitales.findByIdForUpdate(hospitalId)
                .orElseThrow(() -> new AccessDeniedException("El recepcionista no esta asignado al hospital"));
        Hospital hospital = repoHospitales.findByIdAndRecepcionistasId(hospitalId, recepcionista.getId())
                .orElseThrow(() -> new AccessDeniedException("El recepcionista no esta asignado al hospital"));
        repoMembresiasHospital.findByUsuarioIdAndHospitalId(auth0Id, hospitalId).ifPresent(membresia -> {
            if (membresia.getEstado() != EstadoMembresiaHospital.ACTIVA
                    || !membresia.getRolesEfectivos().contains(RolMembresiaHospital.RECEPCIONISTA)) {
                throw new AccessDeniedException(
                        "No tenés una membresía activa con rol de recepción en este hospital");
            }
        });
        if (repoSesionesRecepcion.existsByRecepcionistaIdAndEstado(recepcionista.getId(), EstadoSesionRecepcion.ACTIVA)) {
            throw new ConflictoDeEstadoException("El recepcionista ya tiene una sesion activa");
        }
        SesionRecepcion sesion = new SesionRecepcion();
        sesion.setRecepcionista(recepcionista);
        sesion.setHospital(hospital);
        sesion.setEstado(EstadoSesionRecepcion.ACTIVA);
        sesion.setFechaHoraInicio(LocalDateTime.now());
        return mapear(repoSesionesRecepcion.save(sesion));
    }

    @Transactional
    public SesionRecepcionDTO cerrarSesion(String auth0Id, Long sesionId) {
        SesionRecepcion sesion = obtenerSesion(auth0Id, sesionId);
        if (sesion.getEstado() != EstadoSesionRecepcion.ACTIVA) throw new ConflictoDeEstadoException("La sesion no esta activa");
        if (repoAdmisionesRecepcion.existsBySesionRecepcionIdAndEstadoIn(sesionId,
                List.of(EstadoAdmisionRecepcion.INICIADA, EstadoAdmisionRecepcion.FORMULARIO_COMPLETO))) {
            throw new ConflictoDeEstadoException("Debe finalizar o cancelar las admisiones abiertas");
        }
        sesion.setEstado(EstadoSesionRecepcion.FINALIZADA);
        sesion.setFechaHoraFin(LocalDateTime.now());
        return mapear(repoSesionesRecepcion.save(sesion));
    }

    @Transactional
    public AdmisionRecepcionDTO crearAdmision(String auth0Id, CrearAdmisionRecepcionRequest request) {
        SesionRecepcion sesion = obtenerSesionActiva(auth0Id, request.sesionId());
        LocalDate hoy = LocalDate.now(ZONA_ARGENTINA);
        if (!request.fechaNacimiento().isBefore(hoy) || request.fechaNacimiento().isBefore(hoy.minusYears(120))) {
            throw new IllegalArgumentException("La fecha de nacimiento debe ser anterior a hoy y de hace como máximo 120 años.");
        }
        String dni = request.dni().replaceAll("\\D", "");
        var pacienteExistente = repoPacientes.findByNumeroDocumentoOrUsuarioAuthNumeroDocumento(dni, dni);
        Paciente paciente = pacienteExistente.orElseGet(() -> crearPacientePresencial(request, dni));
        if (repoConsultasMedicas.findFirstByPacienteIdAndEstadoConsultaIn(paciente.getId(), ESTADOS_ACTIVOS).isPresent()) {
            throw new AtencionEnCursoException();
        }
        EspecialidadMedica especialidad = repoEspecialidadesMedicas.findByCodigo(request.codigoEspecialidad())
                .orElseThrow(() -> new RecursoNoEncontradoException("Especialidad inexistente"));
        boolean disponible = sesion.getHospital().getEspecialidades().stream()
                .anyMatch(actual -> actual.getId().equals(especialidad.getId()));
        if (!disponible) throw new RecursoNoEncontradoException("El hospital no atiende la especialidad");
        pacienteExistente.ifPresent(existente -> actualizarDatosPaciente(existente, request));
        ConsultaMedica consulta = new ConsultaMedica();
        consulta.setPaciente(paciente);
        consulta.setHospital(sesion.getHospital());
        consulta.setEspecialidad(especialidad);
        consulta.setFechaHoraCreacion(LocalDateTime.now());
        consulta.setEstadoConsulta(EstadoConsulta.PRETRIAGE_EN_PROCESO);
        consulta.setCodigoLlamado(generarCodigoLlamado());
        asignacionSectorService.asignarSector(consulta);
        repoConsultasMedicas.save(consulta);
        AdmisionRecepcion admision = new AdmisionRecepcion();
        admision.setConsultaMedica(consulta);
        admision.setSesionRecepcion(sesion);
        admision.setEstado(EstadoAdmisionRecepcion.INICIADA);
        admision.setFechaHoraInicio(LocalDateTime.now());
        return mapear(repoAdmisionesRecepcion.save(admision), null);
    }

    @Transactional
    public CoberturaRecepcionDTO seleccionarCobertura(String auth0Id, Long admisionId, Long credencialId) {
        AdmisionRecepcion admision = obtenerAdmisionParaModificar(auth0Id, admisionId);
        if (!admisionAbierta(admision) || admision.getSesionRecepcion().getEstado() != EstadoSesionRecepcion.ACTIVA) {
            throw new ConflictoDeEstadoException("Solo podés seleccionar la cobertura de una admisión abierta en una sesión activa.");
        }

        String nombreObraSocial = null;
        if (credencialId != null) {
            Long pacienteId = admision.getConsultaMedica().getPaciente().getId();
            Credencial credencial = repoCredenciales.findByIdAndPacienteId(credencialId, pacienteId)
                    .orElseThrow(() -> new RecursoNoEncontradoException("La credencial no está disponible para este paciente."));
            if (credencial.getFechaVencimiento() == null || !credencial.getFechaVencimiento().isAfter(LocalDate.now(ZONA_ARGENTINA))) {
                throw new IllegalArgumentException("La fecha de vencimiento de la credencial debe ser posterior a hoy.");
            }
            nombreObraSocial = credencial.getObraSocial().getNombre();
        }
        admision.setCredencialCoberturaId(credencialId);
        admision.setNombreObraSocialCobertura(nombreObraSocial);
        admision.setCoberturaConfirmada(true);
        return new CoberturaRecepcionDTO(credencialId, nombreObraSocial);
    }

    @Transactional
    public AdmisionRecepcionDTO finalizar(String auth0Id, Long admisionId, FormularioTriageRecepcionRequest formulario) {
        AdmisionRecepcion admision = obtenerAdmisionParaModificar(auth0Id, admisionId);
        if (!admisionAbierta(admision)) {
            throw new ConflictoDeEstadoException("La admision ya esta finalizada o cancelada");
        }
        if (admision.getSesionRecepcion().getEstado() != EstadoSesionRecepcion.ACTIVA) {
            throw new ConflictoDeEstadoException("La sesion de recepcion no esta activa");
        }
        admision.setFormularioJson(escribir(formulario));
        admision.setEstado(EstadoAdmisionRecepcion.FORMULARIO_COMPLETO);
        TriageResultDTO resultado;
        try { resultado = triageFormularioService.clasificar(formulario); }
        catch (ProveedorIaException error) { resultado = triageFormularioService.resultadoFallback(formulario); }
        NivelDeGravedad prioridad = prioridad(resultado.nivelPrioridad());
        String resultadoJson = escribir(resultado);
        admision.setResultadoTriageJson(resultadoJson);
        admision.getConsultaMedica().setResumenPretriageJson(resultadoJson);
        repoConsultasMedicas.save(admision.getConsultaMedica());
        admision.setEstado(EstadoAdmisionRecepcion.FINALIZADA);
        admision.setFechaHoraFinalizacion(LocalDateTime.now());
        repoAdmisionesRecepcion.save(admision);
        TiempoEstimadoAtencionResponse estimacion = ingresoColaService.ingresar(admision.getConsultaMedica(), prioridad);
        return mapear(admision, estimacion);
    }

    public AdmisionRecepcionDetalleDTO obtenerDetalle(String auth0Id, Long admisionId) {
        AdmisionRecepcion admision = obtenerAdmision(auth0Id, admisionId);
        return mapearDetalle(admision, estimacionActual(admision));
    }

    public List<AdmisionRecepcionDetalleDTO> listarAbiertas(String auth0Id, Long sesionId) {
        obtenerSesionActiva(auth0Id, sesionId);
        return repoAdmisionesRecepcion
                .findBySesionRecepcionIdAndEstadoInOrderByFechaHoraInicioAsc(sesionId,
                        List.of(EstadoAdmisionRecepcion.INICIADA, EstadoAdmisionRecepcion.FORMULARIO_COMPLETO))
                .stream()
                .map(admision -> mapearDetalle(admision, null))
                .toList();
    }

    @Transactional
    public AdmisionRecepcionDetalleDTO cancelar(String auth0Id, Long admisionId) {
        AdmisionRecepcion admision = obtenerAdmisionParaModificar(auth0Id, admisionId);
        obtenerSesionActiva(auth0Id, admision.getSesionRecepcion().getId());
        if (!admisionAbierta(admision)) {
            throw new ConflictoDeEstadoException("Solo se puede cancelar una admision abierta");
        }
        admision.setEstado(EstadoAdmisionRecepcion.CANCELADA);
        admision.setFechaHoraCancelacion(LocalDateTime.now());
        admision.getConsultaMedica().setEstadoConsulta(EstadoConsulta.CANCELADA);
        repoConsultasMedicas.save(admision.getConsultaMedica());
        repoAdmisionesRecepcion.save(admision);
        return mapearDetalle(admision, null);
    }

    private Paciente crearPacientePresencial(CrearAdmisionRecepcionRequest r, String dni) {
        Paciente paciente = new Paciente();
        paciente.setNumeroDocumento(dni); paciente.setTipoDocumento(TipoDocumento.DNI);
        paciente.setOrigenRegistro(OrigenRegistroPaciente.RECEPCION);
        actualizarDatosPaciente(paciente, r);
        return repoPacientes.save(paciente);
    }

    private void actualizarDatosPaciente(Paciente paciente, CrearAdmisionRecepcionRequest r) {
        paciente.setNombre(r.nombre().trim());
        paciente.setApellido(r.apellido().trim());
        paciente.setFechaNacimiento(r.fechaNacimiento());
        paciente.setGeneroBiologico(r.generoBiologico());
        paciente.setTelefono(r.telefono().trim());
        if (r.correoElectronico() != null) {
            paciente.setCorreoElectronico(r.correoElectronico().isBlank() ? null : r.correoElectronico().trim());
        }
        actualizarDireccion(paciente, r);
    }

    private void actualizarDireccion(Paciente paciente, CrearAdmisionRecepcionRequest r) {
        Direccion direccion = paciente.getDireccion() == null ? new Direccion() : paciente.getDireccion();
        direccion.setCalle(r.calle().trim());
        direccion.setAltura(r.alturaDomicilio().trim());
        direccion.setPiso(r.piso() == null || r.piso().isBlank() ? null : r.piso().trim());
        direccion.setCiudad(r.ciudad().trim());
        direccion.setProvincia(r.provincia().trim());
        if (r.codigoPostal() != null) {
            direccion.setCodigoPostal(r.codigoPostal().isBlank() ? null : r.codigoPostal().trim());
        }
        paciente.setDireccion(repoDirecciones.save(direccion));
    }
    private Recepcionista obtenerRecepcionista(String auth0Id) {
        return repoRecepcionistas.findRecepcionistaByUsuarioAuthId(auth0Id)
                .orElseThrow(() -> new org.springframework.security.access.AccessDeniedException("No tiene permisos de recepcionista"));
    }
    private SesionRecepcion obtenerSesion(String auth0Id, Long id) {
        SesionRecepcion sesion = repoSesionesRecepcion.findByIdAndRecepcionistaUsuarioAuthId(id, auth0Id)
                .orElseThrow(() -> new org.springframework.security.access.AccessDeniedException("No tiene permisos sobre la sesion"));
        if (sesion.getHospital() != null) exigirRolRecepcionistaEnHospital(auth0Id, sesion.getHospital().getId());
        return sesion;
    }

    private void exigirRolRecepcionistaEnHospital(String auth0Id, Long hospitalId) {
        Optional<MembresiaHospital> membresia = repoMembresiasHospital.findByUsuarioIdAndHospitalId(auth0Id, hospitalId);
        if (membresia.isPresent()
                && (membresia.get().getEstado() != EstadoMembresiaHospital.ACTIVA
                        || !membresia.get().getRolesEfectivos().contains(RolMembresiaHospital.RECEPCIONISTA)))
            throw new AccessDeniedException("No tenés un rol de recepción activo en este hospital");
    }
    private SesionRecepcion obtenerSesionActiva(String auth0Id, Long id) {
        SesionRecepcion s = obtenerSesion(auth0Id, id);
        if (s.getEstado() != EstadoSesionRecepcion.ACTIVA) throw new ConflictoDeEstadoException("La sesion no esta activa");
        return s;
    }
    private AdmisionRecepcion obtenerAdmision(String auth0Id, Long id) {
        if (!repoAdmisionesRecepcion.existsById(id)) {
            throw new RecursoNoEncontradoException("Admision inexistente");
        }
        AdmisionRecepcion admision = repoAdmisionesRecepcion.findByIdAndSesionRecepcionRecepcionistaUsuarioAuthId(id, auth0Id)
                .orElseThrow(() -> new org.springframework.security.access.AccessDeniedException("No tiene permisos sobre la admision"));
        if (admision.getSesionRecepcion().getHospital() != null)
            exigirRolRecepcionistaEnHospital(auth0Id, admision.getSesionRecepcion().getHospital().getId());
        return admision;
    }
    private AdmisionRecepcion obtenerAdmisionParaModificar(String auth0Id, Long id) {
        AdmisionRecepcion admision = repoAdmisionesRecepcion.findForUpdateByIdAndSesionRecepcionRecepcionistaUsuarioAuthId(id, auth0Id)
                .orElseThrow(() -> repoAdmisionesRecepcion.existsById(id)
                        ? new AccessDeniedException("No tenés permisos sobre la admisión.")
                        : new RecursoNoEncontradoException("Admisión inexistente."));
        if (admision.getSesionRecepcion().getHospital() != null)
            exigirRolRecepcionistaEnHospital(auth0Id, admision.getSesionRecepcion().getHospital().getId());
        return admision;
    }

    private boolean admisionAbierta(AdmisionRecepcion admision) {
        return admision.getEstado() == EstadoAdmisionRecepcion.INICIADA
                || admision.getEstado() == EstadoAdmisionRecepcion.FORMULARIO_COMPLETO;
    }
    private String generarCodigoLlamado() { return "R-" + UUID.randomUUID().toString().substring(0, 6).toUpperCase(); }
    private String escribir(Object valor) { try { return objectMapper.writeValueAsString(valor); } catch (Exception e) { throw new IllegalStateException("No se pudo guardar el formulario", e); } }
    private NivelDeGravedad prioridad(int valor) { return switch (valor) { case 5 -> NivelDeGravedad.RIESGO_VITAL_INMEDIATO; case 4 -> NivelDeGravedad.MUY_URGENTE; case 3 -> NivelDeGravedad.URGENTE; case 2 -> NivelDeGravedad.NORMAL; default -> NivelDeGravedad.NO_URGENTE; }; }
    private SesionRecepcionDTO mapear(SesionRecepcion s) { return new SesionRecepcionDTO(s.getId(), s.getHospital().getId(), s.getHospital().getNombre(), s.getEstado(), s.getFechaHoraInicio(), s.getFechaHoraFin()); }
    private AdmisionRecepcionDTO mapear(AdmisionRecepcion a, TiempoEstimadoAtencionResponse e) { ConsultaMedica c=a.getConsultaMedica(); return new AdmisionRecepcionDTO(a.getId(), c.getId(), c.getPaciente().getId(), c.getCodigoLlamado(), a.getEstado(), c.getNivelDeGravedadBot(), c.getSector() != null ? c.getSector().getId() : null, c.getSector() != null ? c.getSector().getNombre() : null, e); }

    private TiempoEstimadoAtencionResponse estimacionActual(AdmisionRecepcion admision) {
        ConsultaMedica consulta = admision.getConsultaMedica();
        return consulta.getEstadoConsulta() == EstadoConsulta.EN_COLA
                ? estimacionAtencionService.calcularPara(consulta)
                : null;
    }

    private AdmisionRecepcionDetalleDTO mapearDetalle(
            AdmisionRecepcion admision, TiempoEstimadoAtencionResponse estimacion) {
        ConsultaMedica consulta = admision.getConsultaMedica();
        Paciente paciente = consulta.getPaciente();
        Hospital hospital = consulta.getHospital();
        EspecialidadMedica especialidad = consulta.getEspecialidad();
        String dni = paciente.getNumeroDocumento() != null
                ? paciente.getNumeroDocumento()
                : paciente.getUsuarioAuth() != null ? paciente.getUsuarioAuth().getNumeroDocumento() : null;
        String nombre = paciente.getNombre() != null
                ? paciente.getNombre()
                : paciente.getUsuarioAuth() != null ? paciente.getUsuarioAuth().getNombre() : null;
        String apellido = paciente.getApellido() != null
                ? paciente.getApellido()
                : paciente.getUsuarioAuth() != null ? paciente.getUsuarioAuth().getApellido() : null;
        return new AdmisionRecepcionDetalleDTO(
                admision.getId(), consulta.getId(), admision.getSesionRecepcion().getId(), paciente.getId(),
                dni, nombre, apellido, hospital.getId(), hospital.getNombre(),
                especialidad.getCodigo(), especialidad.getNombre(), consulta.getCodigoLlamado(),
                admision.getEstado(), consulta.getNivelDeGravedadBot(), admision.getFechaHoraInicio(),
                admision.getFechaHoraFinalizacion(), admision.getFechaHoraCancelacion(), estimacion,
                Boolean.TRUE.equals(admision.getCoberturaConfirmada()),
                admision.getCredencialCoberturaId() == null ? null
                        : new CoberturaRecepcionDTO(admision.getCredencialCoberturaId(), admision.getNombreObraSocialCobertura()));
    }
}
