package com.pretriage.backend.services;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;
import com.pretriage.backend.controllers.dtos.AtencionMedicaDTO;
import com.pretriage.backend.controllers.dtos.AsignacionMedicoDTO;
import com.pretriage.backend.controllers.dtos.ConsultaLlamadaDTO;
import com.pretriage.backend.controllers.dtos.EstudioClinicoDTO;
import com.pretriage.backend.controllers.dtos.PretriajeConsultaDTO;
import com.pretriage.backend.controllers.dtos.RevisionPrioridadDTO;
import com.pretriage.backend.controllers.dtos.RevisionPrioridadRequest;
import com.pretriage.backend.controllers.dtos.SalaDTO;
import com.pretriage.backend.controllers.dtos.SectorDTO;
import com.pretriage.backend.controllers.dtos.SesionAtencionMedicaDTO;
import com.pretriage.backend.controllers.dtos.SesionMedicaActualDTO;
import com.pretriage.backend.controllers.dtos.TriageResultDTO;
import com.pretriage.backend.exceptions.ConflictoDeEstadoException;
import com.pretriage.backend.model.consultas.*;
import com.pretriage.backend.model.hospitales.EspecialidadMedica;
import com.pretriage.backend.model.hospitales.Hospital;
import com.pretriage.backend.model.hospitales.Sala;
import com.pretriage.backend.model.hospitales.Sector;
import com.pretriage.backend.model.personas.AsignacionMedicoHospital;
import com.pretriage.backend.model.personas.Medico;
import com.pretriage.backend.model.personas.Paciente;
import com.pretriage.backend.repositories.*;
import jakarta.transaction.Transactional;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Objects;

import org.springframework.data.domain.PageRequest;

@Service
@RequiredArgsConstructor
public class AtencionMedicoService {

    private static final List<EstadoSesionMedica> SESIONES_RESERVAN_RECURSOS = List.of(
            EstadoSesionMedica.ACTIVA,
            EstadoSesionMedica.PAUSADA);

    private static final int MINUTOS_MAXIMOS_EN_ESPERA = 60;	

    private final RepoMedico repoMedico;
    private final RepoHospitales repoHospitales;
    private final RepoEspecialidadesMedicas repoEspecialidadesMedicas;

    private final RepoAsignacionesMedicoHospital repoAsignacionesMedicoHospital;
    private final RepoSesionesAtencionMedica repoSesionesAtencionMedica;
    private final RepoGestoresDeColas repoGestoresDeColas;
    private final RepoEntradasCola repoEntradasCola;
    private final RepoConsultasMedicas repoConsultasMedicas;
    private final RepoAtencionesMedicas repoAtencionesMedicas;
    private final RepoEstudiosClinicos repoEstudiosClinicos;
    private final RepoRevisionesPrioridadConsulta repoRevisionesPrioridadConsulta;
    private final RepoAdmisionesRecepcion repoAdmisionesRecepcion;
    private final ObjectMapper objectMapper;

    private final PacienteService pacienteService;
    private final UsuariosService usuariosService;
    private final SalaService salaService;
    private final EstudioClinicoService estudioClinicoService;

    public List<AsignacionMedicoDTO> obtenerAsignaciones(String auth0Id) {
        Medico medico = obtenerMedico(auth0Id);
        return repoAsignacionesMedicoHospital.findByMedicoId(medico.getId()).stream()
                .map(this::mapearAsignacion)
                .toList();
    }

    public List<SectorDTO> obtenerSectores(Long hospitalId, String codigoEspecialidad, String auth0Id) {
        usuariosService.validarSiEsUsuarioValido(auth0Id);
        return salaService.obtenerSectores(hospitalId, codigoEspecialidad);
    }

    public List<SalaDTO> obtenerSalas(Long hospitalId, Long sectorId, String codigoEspecialidad, String auth0Id) {
        usuariosService.validarSiEsUsuarioValido(auth0Id);

        return salaService.obtenerSalas(hospitalId, sectorId, codigoEspecialidad);
    }

    public SesionMedicaActualDTO obtenerSesionActual(String auth0Id) {
        obtenerMedico(auth0Id);
        SesionAtencionMedicaDTO sesion = repoSesionesAtencionMedica
                .findFirstByMedicoUsuarioAuthIdAndEstadoInOrderByFechaHoraInicioDesc(
                        auth0Id, SESIONES_RESERVAN_RECURSOS)
                .map(this::mapearSesion)
                .orElse(null);
        ConsultaLlamadaDTO consultaActual = repoEntradasCola
                .findFirstByConsultaMedicaMedicoUsuarioAuthIdAndEstadoInOrderByFechaHoraLlamadoDesc(
                        auth0Id, List.of(EstadoEntradaCola.LLAMADO, EstadoEntradaCola.EN_ATENCION))
                .map(EntradaCola::getConsultaMedica)
                .map(this::mapearConsultaLlamada)
                .orElse(null);
        return new SesionMedicaActualDTO(sesion, consultaActual);
    }

    public List<ConsultaLlamadaDTO> listarPacientesDisponibles(String auth0Id, Long sesionId, String dni) {
        SesionAtencionMedica sesion = obtenerSesionActiva(auth0Id, sesionId);
        GestorDeCola gestor = obtenerGestorDeCola(sesion);
        String dniNormalizado = dni == null ? null : dni.trim();
        if (dniNormalizado != null && dniNormalizado.isBlank()) {
            dniNormalizado = null;
        }
        List<EntradaCola> entradas;
        if (dniNormalizado == null) {
            entradas = repoEntradasCola
                    .findByGestorDeColaIdAndEstadoOrderByPrioridadDescOrdenRelativoAscFechaHoraIngresoAsc(
                            gestor.getId(), EstadoEntradaCola.EN_COLA);
        } else {
            entradas = repoEntradasCola
                    .findByGestorDeColaIdAndEstadoAndConsultaMedicaPacienteNumeroDocumentoOrderByPrioridadDescOrdenRelativoAscFechaHoraIngresoAsc(
                            gestor.getId(), EstadoEntradaCola.EN_COLA, dniNormalizado);
        }
        return entradas.stream()
                .map(EntradaCola::getConsultaMedica)
                .map(this::mapearConsultaLlamada)
                .toList();
    }

    public List<AtencionMedicaDTO> obtenerHistorial(String auth0Id) {
        obtenerMedico(auth0Id);
        return repoAtencionesMedicas
                .findBySesionAtencionMedicaMedicoUsuarioAuthIdOrderByFechaHoraInicioDesc(auth0Id)
                .stream()
                .map(this::mapearAtencion)
                .toList();
    }

    public List<EstudioClinicoDTO> obtenerHistorialClinico(String auth0Id, Long pacienteId) {
        this.obtenerMedico(auth0Id);
        return this.obtenerHistorialClinicoDe(pacienteId);
    }

    public EstudioClinicoDTO obtenerEstudioClinico(String auth0Id, Long pacienteId, Long estudioId) {
        this.obtenerMedico(auth0Id);
        return this.obtenerEstudioClinicoDe(pacienteId, estudioId);
    }

    public List<EstudioClinicoDTO> obtenerUltimosEstudiosClinicos(String auth0Id, Long pacienteId, int limite) {
        this.obtenerMedico(auth0Id);
        return this.obtenerUltimosEstudiosClinicosDe(pacienteId, limite);
    }

    public byte[] descargarArchivo(String auth0Id, Long pacienteId, Long estudioId) {
        this.obtenerMedico(auth0Id);
        Paciente paciente = pacienteService.obtenerPaciente(pacienteId);

        return estudioClinicoService.descargarEstudioClinicoDePaciente(paciente, estudioId);
    }

    @Transactional
    public SesionAtencionMedicaDTO iniciarSesion(String auth0Id, Long hospitalId, String codigoEspecialidad,
            Long sectorId, Long salaId) {
        Medico medico = obtenerMedico(auth0Id);
        Hospital hospital = repoHospitales.findById(hospitalId)
                .orElseThrow(() -> new NoSuchElementException("Hospital inexistente"));
        EspecialidadMedica especialidad = repoEspecialidadesMedicas.findByCodigo(codigoEspecialidad)
                .orElseThrow(() -> new NoSuchElementException("Especialidad medica inexistente"));
        Sector sector = salaService.obtenerSector(sectorId, hospitalId);
        Sala sala = salaService.obtenerSala(salaId, hospitalId);

        validarAsignacion(medico, hospital, especialidad);
        validarSector(sector, especialidad);
        validarSala(sala, hospital, especialidad, sector);
        validarRecursosDisponibles(medico, sala);

        SesionAtencionMedica sesion = new SesionAtencionMedica();
        sesion.setMedico(medico);
        sesion.setHospital(hospital);
        sesion.setEspecialidad(especialidad);
        sesion.setSector(sector);
        sesion.setSala(sala);
        sesion.setEstado(EstadoSesionMedica.ACTIVA);
        sesion.setFechaHoraInicio(LocalDateTime.now());

        return mapearSesion(repoSesionesAtencionMedica.save(sesion));
    }

    @Transactional
    public SesionAtencionMedicaDTO pausarSesion(String auth0Id, Long sesionId) {
        SesionAtencionMedica sesion = obtenerSesionDelMedicoParaActualizar(auth0Id, sesionId);
        validarSesionNoFinalizada(sesion);
        validarSinConsultaEnCurso(sesion);
        sesion.setEstado(EstadoSesionMedica.PAUSADA);
        sesion.setFechaHoraPausa(LocalDateTime.now());
        return mapearSesion(repoSesionesAtencionMedica.save(sesion));
    }

    @Transactional
    public SesionAtencionMedicaDTO reanudarSesion(String auth0Id, Long sesionId) {
        SesionAtencionMedica sesion = obtenerSesionDelMedicoParaActualizar(auth0Id, sesionId);
        if (sesion.getEstado() != EstadoSesionMedica.PAUSADA) {
            throw new IllegalStateException("Solo se puede reanudar una sesion pausada");
        }
        sesion.setEstado(EstadoSesionMedica.ACTIVA);
        sesion.setFechaHoraPausa(null);
        return mapearSesion(repoSesionesAtencionMedica.save(sesion));
    }

    @Transactional
    public SesionAtencionMedicaDTO cerrarSesion(String auth0Id, Long sesionId) {
        SesionAtencionMedica sesion = obtenerSesionDelMedicoParaActualizar(auth0Id, sesionId);
        validarSesionNoFinalizada(sesion);
        validarSinConsultaEnCurso(sesion);
        sesion.setEstado(EstadoSesionMedica.FINALIZADA);
        sesion.setFechaHoraFin(LocalDateTime.now());
        return mapearSesion(repoSesionesAtencionMedica.save(sesion));
    }

    @Transactional
    public ConsultaLlamadaDTO llamarProximo(String auth0Id, Long sesionId) {
        SesionAtencionMedica sesion = obtenerSesionActivaParaActualizar(auth0Id, sesionId);
        validarSinConsultaEnCurso(sesion);
        GestorDeCola gestorDeCola = repoGestoresDeColas
                .findByHospitalIdAndEspecialidadIdAndSectorId(
                        sesion.getHospital().getId(), sesion.getEspecialidad().getId(),
                        sesion.getSector().getId())
                .orElseThrow(() -> new NoSuchElementException("No existe cola para la especialidad del sector"));

        EntradaCola entrada = repoEntradasCola
                .findFirstByGestorDeColaIdAndEstadoOrderByPrioridadDescOrdenRelativoAsc(gestorDeCola.getId(),
                        EstadoEntradaCola.EN_COLA)
                .orElseThrow(() -> new NoSuchElementException("No hay pacientes en cola"));

        ConsultaMedica consulta = entrada.getConsultaMedica();
        entrada.setEstado(EstadoEntradaCola.LLAMADO);
        entrada.setFechaHoraLlamado(LocalDateTime.now());
        consulta.setEstadoConsulta(EstadoConsulta.LLAMADO);
        consulta.setMedico(sesion.getMedico());
        consulta.setSala(sesion.getSala());

        repoConsultasMedicas.save(consulta);
        repoEntradasCola.save(entrada);
        return mapearConsultaLlamada(consulta);
    }

    @Transactional
    public ConsultaLlamadaDTO confirmarPresente(String auth0Id, Long sesionId, Long consultaId) {
        SesionAtencionMedica sesion = obtenerSesionActivaParaActualizar(auth0Id, sesionId);
        EntradaCola entrada = obtenerEntradaDeConsulta(consultaId);
        validarConsultaTomadaPorSesion(entrada.getConsultaMedica(), sesion);
        validarEstadoEntrada(entrada, EstadoEntradaCola.LLAMADO);

        ConsultaMedica consulta = entrada.getConsultaMedica();
        entrada.setEstado(EstadoEntradaCola.EN_ATENCION);
        consulta.setEstadoConsulta(EstadoConsulta.EN_ATENCION);

        AtencionMedica atencion = new AtencionMedica();
        atencion.setConsultaMedica(consulta);
        atencion.setSesionAtencionMedica(sesion);
        atencion.setEstado(EstadoAtencionMedica.EN_CURSO);
        atencion.setFechaHoraInicio(LocalDateTime.now());
        repoAtencionesMedicas.save(atencion);
        repoEntradasCola.save(entrada);
        repoConsultasMedicas.save(consulta);
        return mapearConsultaLlamada(consulta);
    }

    @Transactional
    public PretriajeConsultaDTO obtenerPretriaje(String auth0Id, Long sesionId, Long consultaId) {
        SesionAtencionMedica sesion = obtenerSesionActivaParaActualizar(auth0Id, sesionId);
        EntradaCola entrada = obtenerEntradaDeConsulta(consultaId);
        validarConsultaTomadaPorSesion(entrada.getConsultaMedica(), sesion);
        validarEstadoEntrada(entrada, EstadoEntradaCola.EN_ATENCION);
        return mapearPretriaje(entrada.getConsultaMedica());
    }

    @Transactional
    public PretriajeConsultaDTO revisarPrioridad(
            String auth0Id, Long sesionId, Long consultaId, RevisionPrioridadRequest request) {
        SesionAtencionMedica sesion = obtenerSesionActivaParaActualizar(auth0Id, sesionId);
        EntradaCola entrada = obtenerEntradaDeConsulta(consultaId);
        validarConsultaTomadaPorSesion(entrada.getConsultaMedica(), sesion);
        validarEstadoEntrada(entrada, EstadoEntradaCola.EN_ATENCION);

        ConsultaMedica consulta = entrada.getConsultaMedica();
        NivelDeGravedad preliminar = consulta.getNivelDeGravedadBot();
        if (preliminar == null) {
            throw new ConflictoDeEstadoException("La consulta no tiene una prioridad preliminar");
        }

        NivelDeGravedad nuevaPrioridad;
        if (request.decision() == DecisionRevisionPrioridad.CONFIRMAR) {
            nuevaPrioridad = preliminar;
        } else {
            if (request.prioridad() == null) {
                throw new IllegalArgumentException("Debe indicar la prioridad corregida");
            }
            if (request.prioridad() == preliminar) {
                throw new IllegalArgumentException("La prioridad corregida debe ser distinta de la preliminar");
            }
            nuevaPrioridad = request.prioridad();
        }

        String motivo = normalizarMotivo(request.motivo());
        RevisionPrioridadConsulta ultima = repoRevisionesPrioridadConsulta
                .findFirstByConsultaMedicaIdOrderByFechaHoraDescIdDesc(consultaId)
                .orElse(null);
        if (ultima != null
                && ultima.getDecision() == request.decision()
                && ultima.getPrioridadNueva() == nuevaPrioridad
                && Objects.equals(ultima.getMotivo(), motivo)) {
            return mapearPretriaje(consulta, ultima);
        }

        RevisionPrioridadConsulta revision = new RevisionPrioridadConsulta();
        revision.setConsultaMedica(consulta);
        revision.setMedico(sesion.getMedico());
        revision.setDecision(request.decision());
        revision.setPrioridadAnterior(consulta.getNivelDeGravedadMedico() == null
                ? preliminar
                : consulta.getNivelDeGravedadMedico());
        revision.setPrioridadNueva(nuevaPrioridad);
        revision.setMotivo(motivo);
        revision.setFechaHora(LocalDateTime.now());
        repoRevisionesPrioridadConsulta.save(revision);

        consulta.setNivelDeGravedadMedico(nuevaPrioridad);
        repoConsultasMedicas.save(consulta);
        return mapearPretriaje(consulta, revision);
    }

    @Transactional
    public ConsultaLlamadaDTO marcarAusente(String auth0Id, Long sesionId, Long consultaId) {
        SesionAtencionMedica sesion = obtenerSesionActivaParaActualizar(auth0Id, sesionId);
        EntradaCola entrada = obtenerEntradaDeConsulta(consultaId);
        validarConsultaTomadaPorSesion(entrada.getConsultaMedica(), sesion);
        validarEstadoEntrada(entrada, EstadoEntradaCola.LLAMADO);

        ConsultaMedica consulta = entrada.getConsultaMedica();
        entrada.setEstado(EstadoEntradaCola.EN_ESPERA);
        entrada.setTipoPausa(TipoPausaCola.AUSENTE_AL_LLAMADO);
        entrada.setFechaHoraSalidaTemporal(LocalDateTime.now());
	entrada.setFechaHoraLimiteRespuesta(LocalDateTime.now().plusMinutes(MINUTOS_MAXIMOS_EN_ESPERA));
        consulta.setEstadoConsulta(EstadoConsulta.EN_ESPERA);
        repoEntradasCola.save(entrada);
        repoConsultasMedicas.save(consulta);
        return mapearConsultaLlamada(consulta);
    }

    @Transactional
    public ConsultaLlamadaDTO finalizarConsulta(String auth0Id, Long sesionId, Long consultaId) {
        SesionAtencionMedica sesion = obtenerSesionActivaParaActualizar(auth0Id, sesionId);
        EntradaCola entrada = obtenerEntradaDeConsulta(consultaId);
        validarConsultaTomadaPorSesion(entrada.getConsultaMedica(), sesion);
        validarEstadoEntrada(entrada, EstadoEntradaCola.EN_ATENCION);

        if (repoRevisionesPrioridadConsulta
                .findFirstByConsultaMedicaIdOrderByFechaHoraDescIdDesc(consultaId).isEmpty()) {
            throw new ConflictoDeEstadoException("Debe confirmar o corregir la prioridad antes de finalizar");
        }

        ConsultaMedica consulta = entrada.getConsultaMedica();
        entrada.setEstado(EstadoEntradaCola.FINALIZADA);
        consulta.setEstadoConsulta(EstadoConsulta.FINALIZADA);
        AtencionMedica atencion = repoAtencionesMedicas
                .findByConsultaMedicaIdAndEstado(consultaId, EstadoAtencionMedica.EN_CURSO)
                .orElseThrow(() -> new IllegalStateException("No existe una atencion en curso para la consulta"));
        atencion.setEstado(EstadoAtencionMedica.FINALIZADA);
        atencion.setFechaHoraFin(LocalDateTime.now());
        repoAtencionesMedicas.save(atencion);
        repoEntradasCola.save(entrada);
        repoConsultasMedicas.save(consulta);
        return mapearConsultaLlamada(consulta);
    }

    private GestorDeCola obtenerGestorDeCola(SesionAtencionMedica sesion) {
        return repoGestoresDeColas
                .findByHospitalIdAndEspecialidadIdAndSectorId(
                        sesion.getHospital().getId(), sesion.getEspecialidad().getId(),
                        sesion.getSector().getId())
                .orElseThrow(() -> new NoSuchElementException("No existe cola para la especialidad del sector"));
    }

    private void validarSinConsultaEnCurso(SesionAtencionMedica sesion) {
        boolean consultaTomada = repoEntradasCola.existsByConsultaMedicaMedicoIdAndEstadoIn(
                sesion.getMedico().getId(),
                List.of(EstadoEntradaCola.LLAMADO, EstadoEntradaCola.EN_ATENCION));
        if (consultaTomada || repoAtencionesMedicas.existsBySesionAtencionMedicaIdAndEstado(
                sesion.getId(), EstadoAtencionMedica.EN_CURSO)) {
            throw new IllegalStateException("Debe finalizar o resolver la consulta actual antes de continuar");
        }
    }

    private Medico obtenerMedico(String auth0Id) {
        return repoMedico.findByUsuarioAuthId(auth0Id)
                .orElseThrow(() -> new AccessDeniedException("No tiene permisos de medico"));
    }

    private SesionAtencionMedica obtenerSesionDelMedico(String auth0Id, Long sesionId) {
        return repoSesionesAtencionMedica.findByIdAndMedicoUsuarioAuthId(sesionId, auth0Id)
                .orElseThrow(() -> new AccessDeniedException("No tiene permisos sobre la sesion"));
    }

    private SesionAtencionMedica obtenerSesionDelMedicoParaActualizar(String auth0Id, Long sesionId) {
        return repoSesionesAtencionMedica.findByIdAndMedicoUsuarioAuthIdForUpdate(sesionId, auth0Id)
                .orElseThrow(() -> new AccessDeniedException("No tiene permisos sobre la sesion"));
    }

    private SesionAtencionMedica obtenerSesionActiva(String auth0Id, Long sesionId) {
        SesionAtencionMedica sesion = obtenerSesionDelMedico(auth0Id, sesionId);
        if (sesion.getEstado() != EstadoSesionMedica.ACTIVA) {
            throw new IllegalStateException("La sesion medica debe estar activa");
        }
        return sesion;
    }

    private SesionAtencionMedica obtenerSesionActivaParaActualizar(String auth0Id, Long sesionId) {
        SesionAtencionMedica sesion = obtenerSesionDelMedicoParaActualizar(auth0Id, sesionId);
        if (sesion.getEstado() != EstadoSesionMedica.ACTIVA) {
            throw new IllegalStateException("La sesion medica debe estar activa");
        }
        return sesion;
    }

    private EntradaCola obtenerEntradaDeConsulta(Long consultaId) {
        return repoEntradasCola.findByConsultaMedicaId(consultaId)
                .orElseThrow(() -> new NoSuchElementException("La consulta no esta en cola"));
    }

    private void validarAsignacion(Medico medico, Hospital hospital, EspecialidadMedica especialidad) {
        boolean asignado = repoAsignacionesMedicoHospital.existsByMedicoIdAndHospitalIdAndEspecialidadCodigo(
                medico.getId(), hospital.getId(), especialidad.getCodigo());
        if (!asignado) {
            throw new AccessDeniedException("El medico no esta asignado al hospital y especialidad indicados");
        }
    }

    private void validarSector(Sector sector, EspecialidadMedica especialidad) {
        if (!sector.isActiva()
                || !sector.getEspecialidad().getCodigo().equals(especialidad.getCodigo())) {
            throw new NoSuchElementException("El sector no corresponde a la especialidad indicada o no esta activo");
        }
    }

    private void validarSala(Sala sala, Hospital hospital, EspecialidadMedica especialidad, Sector sector) {
        if (!sala.isActiva()
                || !sala.getHospital().getId().equals(hospital.getId())
                || !sala.getEspecialidad().getCodigo().equals(especialidad.getCodigo())
                || sala.getSector() == null
                || !sala.getSector().getId().equals(sector.getId())) {
            throw new NoSuchElementException("La sala no corresponde al hospital, especialidad y sector indicados");
        }
    }

    private void validarRecursosDisponibles(Medico medico, Sala sala) {
        if (repoSesionesAtencionMedica.existsByMedicoIdAndEstadoIn(medico.getId(), SESIONES_RESERVAN_RECURSOS)) {
            throw new IllegalStateException("El medico ya tiene una sesion activa o pausada");
        }
        if (repoSesionesAtencionMedica.existsBySalaIdAndEstadoIn(sala.getId(), SESIONES_RESERVAN_RECURSOS)) {
            throw new IllegalStateException("La sala ya esta siendo usada por otro medico");
        }
    }

    private void validarSesionNoFinalizada(SesionAtencionMedica sesion) {
        if (sesion.getEstado() == EstadoSesionMedica.FINALIZADA) {
            throw new IllegalStateException("La sesion ya fue finalizada");
        }
    }

    private void validarConsultaTomadaPorSesion(ConsultaMedica consulta, SesionAtencionMedica sesion) {
        if (consulta.getMedico() == null || !consulta.getMedico().getId().equals(sesion.getMedico().getId())) {
            throw new AccessDeniedException("La consulta no fue llamada por el medico de la sesion");
        }
        if (consulta.getSala() == null || !consulta.getSala().getId().equals(sesion.getSala().getId())) {
            throw new AccessDeniedException("La consulta no corresponde a la sala de la sesion");
        }
    }

    private void validarEstadoEntrada(EntradaCola entrada, EstadoEntradaCola estadoEsperado) {
        if (entrada.getEstado() != estadoEsperado) {
            throw new IllegalStateException("Estado de cola invalido para la accion solicitada");
        }
    }

    private AsignacionMedicoDTO mapearAsignacion(AsignacionMedicoHospital asignacion) {
        AsignacionMedicoDTO dto = new AsignacionMedicoDTO();
        dto.setHospitalId(asignacion.getHospital().getId());
        dto.setNombreHospital(asignacion.getHospital().getNombre());
        dto.setCodigoEspecialidad(asignacion.getEspecialidad().getCodigo());
        dto.setNombreEspecialidad(asignacion.getEspecialidad().getNombre());
        return dto;
    }

    private SesionAtencionMedicaDTO mapearSesion(SesionAtencionMedica sesion) {
        SesionAtencionMedicaDTO dto = new SesionAtencionMedicaDTO();
        dto.setId(sesion.getId());
        dto.setHospitalId(sesion.getHospital().getId());
        dto.setCodigoEspecialidad(sesion.getEspecialidad().getCodigo());
        dto.setSectorId(sesion.getSector().getId());
        dto.setNombreSector(sesion.getSector().getNombre());
        dto.setSalaId(sesion.getSala().getId());
        dto.setEstado(sesion.getEstado());
        return dto;
    }

    private AtencionMedicaDTO mapearAtencion(AtencionMedica atencion) {
        AtencionMedicaDTO dto = new AtencionMedicaDTO();
        dto.setId(atencion.getId());
        dto.setConsultaId(atencion.getConsultaMedica().getId());
        dto.setSesionId(atencion.getSesionAtencionMedica().getId());
        dto.setPacienteId(atencion.getConsultaMedica().getPaciente().getId());
        dto.setHospitalId(atencion.getSesionAtencionMedica().getHospital().getId());
        dto.setCodigoEspecialidad(atencion.getSesionAtencionMedica().getEspecialidad().getCodigo());
        dto.setSalaId(atencion.getSesionAtencionMedica().getSala().getId());
        dto.setEstado(atencion.getEstado());
        dto.setFechaHoraInicio(atencion.getFechaHoraInicio());
        dto.setFechaHoraFin(atencion.getFechaHoraFin());
        return dto;
    }

    private ConsultaLlamadaDTO mapearConsultaLlamada(ConsultaMedica consulta) {
        ConsultaLlamadaDTO dto = new ConsultaLlamadaDTO();
        dto.setConsultaId(consulta.getId());
        dto.setCodigoLlamado(consulta.getCodigoLlamado());
        Paciente paciente = consulta.getPaciente();

        dto.setPacienteId(paciente.getId());
        dto.setNombrePaciente(paciente.getNombre());
        dto.setApellidoPaciente(paciente.getApellido());
        dto.setNumeroDocumento(paciente.getNumeroDocumento());
        dto.setTipoDocumento(paciente.getTipoDocumento());
        Sala sala = consulta.getSala();
        if (sala != null) {
            dto.setSalaId(sala.getId());
            dto.setNombreSala(sala.getNombre());
        }
        dto.setPrioridad(consulta.getNivelDeGravedadMedico() == null
                ? consulta.getNivelDeGravedadBot()
                : consulta.getNivelDeGravedadMedico());
        dto.setEstadoConsulta(consulta.getEstadoConsulta());
        return dto;
    }

    private PretriajeConsultaDTO mapearPretriaje(ConsultaMedica consulta) {
        RevisionPrioridadConsulta ultima = repoRevisionesPrioridadConsulta
                .findFirstByConsultaMedicaIdOrderByFechaHoraDescIdDesc(consulta.getId())
                .orElse(null);
        return mapearPretriaje(consulta, ultima);
    }

    private PretriajeConsultaDTO mapearPretriaje(
            ConsultaMedica consulta, RevisionPrioridadConsulta ultima) {
        EstadoRevisionPrioridad estado = ultima == null
                ? EstadoRevisionPrioridad.PENDIENTE
                : (ultima.getDecision() == DecisionRevisionPrioridad.CONFIRMAR
                        ? EstadoRevisionPrioridad.CONFIRMADA
                        : EstadoRevisionPrioridad.CORREGIDA);
        return new PretriajeConsultaDTO(
                consulta.getId(),
                consulta.getNivelDeGravedadBot(),
                consulta.getNivelDeGravedadMedico() == null
                        ? consulta.getNivelDeGravedadBot()
                        : consulta.getNivelDeGravedadMedico(),
                estado,
                leerResumenPretriaje(consulta),
                ultima == null ? null
                        : new RevisionPrioridadDTO(
                                ultima.getId(), ultima.getDecision(), ultima.getPrioridadAnterior(),
                                ultima.getPrioridadNueva(), ultima.getMotivo(), ultima.getFechaHora()));
    }

    private TriageResultDTO leerResumenPretriaje(ConsultaMedica consulta) {
        String resumen = consulta.getResumenPretriageJson();
        if (resumen == null || resumen.isBlank()) {
            resumen = repoAdmisionesRecepcion.findByConsultaMedicaId(consulta.getId())
                    .map(admision -> admision.getResultadoTriageJson())
                    .orElse(null);
        }
        if (resumen == null || resumen.isBlank()) {
            return null;
        }
        try {
            return objectMapper.readValue(resumen, TriageResultDTO.class);
        } catch (JacksonException exception) {
            throw new IllegalStateException("No se pudo leer el resumen de pretriaje", exception);
        }
    }

    private String normalizarMotivo(String motivo) {
        return motivo == null || motivo.isBlank() ? null : motivo.trim();
    }

    @Transactional
    public List<EstudioClinicoDTO> obtenerHistorialClinicoDe(Long pacienteId) {
        Paciente paciente = pacienteService.obtenerPaciente(pacienteId);

        return paciente.getHistorialClinico().stream()
                .map(this::mapearEstudioClinico)
                .toList();
    }

    private EstudioClinicoDTO obtenerEstudioClinicoDe(Long pacienteId, Long estudioId) {
        Paciente paciente = pacienteService.obtenerPaciente(pacienteId);
        return estudioClinicoService.obtenerEstudioClinicoDePaciente(paciente, estudioId);
    }

    private List<EstudioClinicoDTO> obtenerUltimosEstudiosClinicosDe(Long pacienteId, int limite) {
        if (limite < 1) {
            throw new IllegalArgumentException("El limite debe ser mayor a cero");
        }
        pacienteService.obtenerPaciente(pacienteId);
        return repoEstudiosClinicos
                .findByPacienteIdAndActivoTrueOrderByFechaSubidaDesc(
                        pacienteId, PageRequest.of(0, limite))
                .stream()
                .map(this::mapearEstudioClinico)
                .toList();
    }

    private EstudioClinicoDTO mapearEstudioClinico(EstudioClinico estudio) {
        EstudioClinicoDTO dto = new EstudioClinicoDTO();
        dto.setId(estudio.getId());
        dto.setPacienteId(estudio.getPaciente().getId());
        dto.setNombreArchivo(estudio.getNombreArchivo());
        dto.setTipoArchivo(estudio.getTipoArchivo());
        dto.setExtensionArchivo(estudio.getExtensionArchivo());
        dto.setDescripcion(estudio.getDescripcion());
        dto.setFechaSubida(estudio.getFechaSubida());
        dto.setTamanoArchivo(estudio.getTamanoArchivo());
        dto.setRutaArchivo(estudio.getRutaArchivo());
        return dto;
    }
}
