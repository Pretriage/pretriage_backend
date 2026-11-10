package com.pretriage.backend.services;

import com.pretriage.backend.controllers.dtos.DiscrepanciaAdminDTO;
import com.pretriage.backend.controllers.dtos.DiscrepanciaSintomasDTO;
import com.pretriage.backend.controllers.dtos.EstadoSancionPacienteDTO;
import com.pretriage.backend.exceptions.ConflictoDeEstadoException;
import com.pretriage.backend.exceptions.RecursoNoEncontradoException;
import com.pretriage.backend.model.acceso.AuditoriaHospital;
import com.pretriage.backend.model.chat.AutorMensaje;
import com.pretriage.backend.model.consultas.AtencionMedica;
import com.pretriage.backend.model.consultas.ConsultaMedica;
import com.pretriage.backend.model.consultas.DiscrepanciaSintomas;
import com.pretriage.backend.model.consultas.EstadoAtencionMedica;
import com.pretriage.backend.model.consultas.EstadoDiscrepanciaSintomas;
import com.pretriage.backend.model.consultas.EstadoSesionMedica;
import com.pretriage.backend.model.consultas.SancionDigitalPaciente;
import com.pretriage.backend.model.consultas.SesionAtencionMedica;
import com.pretriage.backend.model.consultas.TipoAvisoSancion;
import com.pretriage.backend.model.personas.Paciente;
import com.pretriage.backend.model.personas.TipoDocumento;
import com.pretriage.backend.model.personas.UsuarioAuth;
import com.pretriage.backend.repositories.RepoAtencionesMedicas;
import com.pretriage.backend.repositories.RepoAuditoriasHospital;
import com.pretriage.backend.repositories.RepoDiscrepanciasSintomas;
import com.pretriage.backend.repositories.RepoSancionesDigitales;
import com.pretriage.backend.repositories.RepoSesionesAtencionMedica;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class DiscrepanciaSintomasService {
    private static final ZoneId ZONA_SANCION = ZoneId.of("America/Argentina/Buenos_Aires");
    private static final int ESPACIO_LOCK_DNI = 0x5054444e;
    private static final EstadoSancionPacienteDTO SIN_SANCION =
            new EstadoSancionPacienteDTO(false, false, null, null, null);

    private final RepoDiscrepanciasSintomas repoDiscrepancias;
    private final RepoSancionesDigitales repoSanciones;
    private final RepoAtencionesMedicas repoAtenciones;
    private final RepoSesionesAtencionMedica repoSesiones;
    private final RepoAuditoriasHospital repoAuditorias;
    private final StaffAccessService staffAccessService;
    private final JdbcTemplate jdbcTemplate;

    @Transactional(readOnly = true)
    public DiscrepanciaSintomasDTO obtener(String auth0Id, Long sesionId, Long consultaId) {
        AtencionMedica atencion = obtenerAtencionEnCurso(
                repoSesiones.findByIdAndMedicoUsuarioAuthId(sesionId, auth0Id), consultaId);
        boolean registrada = repoDiscrepancias.findByAtencionMedicaId(atencion.getId()).isPresent();
        ConsultaMedica consulta = atencion.getConsultaMedica();
        boolean puedeRegistrar = !registrada && dniDe(consulta.getPaciente()) != null
                && tieneDeclaracionPrevia(consulta);
        return new DiscrepanciaSintomasDTO(registrada, puedeRegistrar);
    }

    @Transactional
    public DiscrepanciaSintomasDTO registrar(String auth0Id, Long sesionId, Long consultaId) {
        AtencionMedica atencion = obtenerAtencionEnCurso(
                repoSesiones.findByIdAndMedicoUsuarioAuthIdForUpdate(sesionId, auth0Id), consultaId);
        if (repoDiscrepancias.findByAtencionMedicaId(atencion.getId()).isPresent()) {
            return new DiscrepanciaSintomasDTO(true, false);
        }
        ConsultaMedica consulta = atencion.getConsultaMedica();
        String dni = dniDe(consulta.getPaciente());
        if (dni == null || !tieneDeclaracionPrevia(consulta)) {
            throw new ConflictoDeEstadoException("La consulta requiere un DNI válido y síntomas declarados");
        }

        bloquearDni(dni);
        Instant ahora = Instant.now();
        DiscrepanciaSintomas discrepancia = new DiscrepanciaSintomas();
        discrepancia.setAtencionMedica(atencion);
        discrepancia.setDni(dni);
        discrepancia.setEstado(EstadoDiscrepanciaSintomas.VIGENTE);
        discrepancia.setRegistradaEn(ahora);
        repoDiscrepancias.save(discrepancia);

        UsuarioAuth actor = atencion.getSesionAtencionMedica().getMedico().getUsuarioAuth();
        auditar(discrepancia, actor, ahora, "DISCREPANCIA_REGISTRADA", "VIGENTE");
        recalcular(discrepancia, actor, ahora, SIN_SANCION, true);
        return new DiscrepanciaSintomasDTO(true, false);
    }

    @Transactional
    public List<DiscrepanciaAdminDTO> listar(String auth0Id, Long hospitalId) {
        staffAccessService.exigirAdminHospital(auth0Id, hospitalId);
        Instant ahora = Instant.now();
        Map<String, EstadoSancionPacienteDTO> estadosPorDni = new HashMap<>();
        return repoDiscrepancias
                .findByAtencionMedicaSesionAtencionMedicaHospitalIdOrderByRegistradaEnDescIdDesc(hospitalId).stream()
                .map(discrepancia -> {
                    AtencionMedica atencion = discrepancia.getAtencionMedica();
                    Paciente paciente = atencion.getConsultaMedica().getPaciente();
                    UsuarioAuth medico = atencion.getSesionAtencionMedica().getMedico().getUsuarioAuth();
                    String dni = discrepancia.getDni() == null ? dniDe(paciente) : discrepancia.getDni();
                    EstadoSancionPacienteDTO sancion =
                            estadosPorDni.computeIfAbsent(dni, clave -> estadoPorDni(clave, ahora));
                    String pacienteNombre = nombreCompleto(paciente.getNombre(), paciente.getApellido());
                    if (pacienteNombre.isEmpty()) {
                        pacienteNombre = nombreCompleto(paciente.getUsuarioAuth());
                    }
                    return new DiscrepanciaAdminDTO(discrepancia.getId(),
                            pacienteNombre, dni, nombreCompleto(medico), discrepancia.getRegistradaEn(),
                            discrepancia.getEstado(), sancion);
                })
                .toList();
    }

    @Transactional
    public void anular(String auth0Id, Long hospitalId, Long discrepanciaId) {
        UsuarioAuth actor = staffAccessService.exigirAdminHospital(auth0Id, hospitalId);
        DiscrepanciaSintomas discrepancia = repoDiscrepancias
                .findByIdAndAtencionMedicaSesionAtencionMedicaHospitalId(discrepanciaId, hospitalId)
                .orElseThrow(() -> new RecursoNoEncontradoException("Discrepancia no encontrada en el hospital"));
        if (discrepancia.getEstado() == EstadoDiscrepanciaSintomas.ANULADA) {
            throw new ConflictoDeEstadoException("La discrepancia ya fue anulada");
        }

        bloquearDni(discrepancia.getDni());
        // Relee el estado persistido después del lock aunque el contexto JPA ya tuviera la entidad.
        if (!repoDiscrepancias.existsByIdAndEstado(discrepanciaId, EstadoDiscrepanciaSintomas.VIGENTE)) {
            throw new ConflictoDeEstadoException("La discrepancia ya fue anulada");
        }
        Instant ahora = Instant.now();
        EstadoSancionPacienteDTO anterior = estadoPorDni(discrepancia.getDni(), ahora);
        discrepancia.setEstado(EstadoDiscrepanciaSintomas.ANULADA);
        discrepancia.setAnuladaPor(actor);
        discrepancia.setAnuladaEn(ahora);
        auditar(discrepancia, actor, ahora, "DISCREPANCIA_ANULADA", "ANULADA");
        recalcular(discrepancia, actor, ahora, anterior, false);
    }

    @Transactional(readOnly = true)
    public EstadoSancionPacienteDTO estado(Paciente paciente) {
        return estadoPorDni(dniDe(paciente), Instant.now());
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public EstadoSancionPacienteDTO estadoParaIngresoDigital(Paciente paciente) {
        String dni = dniDe(paciente);
        if (dni == null) {
            return SIN_SANCION;
        }
        bloquearDni(dni);
        return estadoPorDni(dni, Instant.now());
    }

    private void bloquearDni(String dni) {
        jdbcTemplate.query("select pg_advisory_xact_lock(?, hashtext(?))",
                (RowCallbackHandler) rs -> { }, ESPACIO_LOCK_DNI, dni);
    }

    private void recalcular(DiscrepanciaSintomas causa, UsuarioAuth actor, Instant ahora,
            EstadoSancionPacienteDTO anterior, boolean nuevaMarca) {
        String dni = causa.getDni();
        long cantidad = repoDiscrepancias.countByDniAndEstado(dni, EstadoDiscrepanciaSintomas.VIGENTE);
        SancionDigitalPaciente sancion = new SancionDigitalPaciente();
        sancion.setDni(dni);
        sancion.setDiscrepancia(causa);
        sancion.setCalculadaEn(ahora);
        sancion.setCantidadVigentes(cantidad);
        if (cantidad > 0) {
            Instant fechaInicio = repoDiscrepancias.findFirstByDniAndEstadoOrderByRegistradaEnDescIdDesc(
                    dni, EstadoDiscrepanciaSintomas.VIGENTE).orElseThrow().getRegistradaEn();
            sancion.setFechaInicio(fechaInicio);
            sancion.setPermanente(cantidad >= 3);
            ZonedDateTime inicioCalendario = fechaInicio.atZone(ZONA_SANCION);
            if (cantidad == 1) {
                sancion.setFechaFin(inicioCalendario.plusMonths(6).toInstant());
            } else if (cantidad == 2) {
                sancion.setFechaFin(inicioCalendario.plusYears(1).toInstant());
            }
        }
        if (nuevaMarca) {
            sancion.setAvisoId(UUID.randomUUID().toString());
            sancion.setTipoAviso(TipoAvisoSancion.APLICADA);
        } else if (anterior.vigente() && !esVigente(sancion, ahora)) {
            sancion.setAvisoId(UUID.randomUUID().toString());
            sancion.setTipoAviso(TipoAvisoSancion.LEVANTADA);
        } else {
            sancion.setAvisoId(anterior.avisoId());
            sancion.setTipoAviso(anterior.tipoAviso());
        }
        repoSanciones.save(sancion);
        auditar(causa, actor, ahora, nuevaMarca ? "SANCION_DIGITAL_APLICADA" : "SANCION_DIGITAL_RECALCULADA",
                "sancion:" + sancion.getId() + " vigentes=" + cantidad
                        + " permanente=" + sancion.isPermanente() + " fechaFin=" + sancion.getFechaFin());
    }

    private EstadoSancionPacienteDTO estadoPorDni(String dni, Instant ahora) {
        if (dni == null) {
            return SIN_SANCION;
        }
        return repoSanciones.findFirstByDniOrderByIdDesc(dni)
                .map(sancion -> mapearEstado(sancion, ahora))
                .orElse(SIN_SANCION);
    }

    private EstadoSancionPacienteDTO mapearEstado(SancionDigitalPaciente sancion, Instant ahora) {
        if (esVigente(sancion, ahora)) {
            return new EstadoSancionPacienteDTO(true, sancion.isPermanente(), sancion.getFechaFin(),
                    sancion.getAvisoId(), sancion.getTipoAviso());
        }
        if (sancion.getFechaFin() != null && sancion.getFechaFin().isAfter(sancion.getCalculadaEn())) {
            return new EstadoSancionPacienteDTO(false, false, null,
                    "vencimiento:" + sancion.getId(), TipoAvisoSancion.LEVANTADA);
        }
        return new EstadoSancionPacienteDTO(false, false, null, sancion.getAvisoId(), sancion.getTipoAviso());
    }

    private static boolean esVigente(SancionDigitalPaciente sancion, Instant ahora) {
        return sancion.isPermanente() || (sancion.getFechaFin() != null && ahora.isBefore(sancion.getFechaFin()));
    }

    private void auditar(DiscrepanciaSintomas discrepancia, UsuarioAuth actor, Instant fecha,
            String accion, String resultado) {
        AuditoriaHospital auditoria = new AuditoriaHospital();
        auditoria.setHospital(discrepancia.getAtencionMedica().getSesionAtencionMedica().getHospital());
        auditoria.setActor(actor);
        auditoria.setFecha(fecha);
        auditoria.setAccion(accion);
        auditoria.setObjetivo("discrepancia:" + discrepancia.getId());
        auditoria.setResultado(resultado);
        repoAuditorias.save(auditoria);
    }

    private AtencionMedica obtenerAtencionEnCurso(Optional<SesionAtencionMedica> resultado, Long consultaId) {
        SesionAtencionMedica sesion = resultado
                .orElseThrow(() -> new AccessDeniedException("No tiene permisos sobre la sesión"));
        if (sesion.getEstado() != EstadoSesionMedica.ACTIVA) {
            throw new ConflictoDeEstadoException("La sesión médica debe estar activa");
        }
        return repoAtenciones.findByConsultaMedicaIdAndSesionAtencionMedicaIdAndEstado(
                        consultaId, sesion.getId(), EstadoAtencionMedica.EN_CURSO)
                .orElseThrow(() -> new RecursoNoEncontradoException("Atención en curso no encontrada en la sesión"));
    }

    private boolean tieneDeclaracionPrevia(ConsultaMedica consulta) {
        String resumen = consulta.getResumenPretriageJson();
        return (resumen != null && !resumen.isBlank())
                || (consulta.getChat() != null && consulta.getChat().getMensajes().stream().anyMatch(
                        mensaje -> mensaje.getAutor() == AutorMensaje.PACIENTE
                                && !Boolean.TRUE.equals(mensaje.getExcluidoDelTriage())));
    }

    private static String dniDe(Paciente paciente) {
        UsuarioAuth usuario = paciente.getUsuarioAuth();
        // El perfil es editable. Cuando hay documento de registro se usa ese dato de identidad.
        boolean tieneDocumentoDeRegistro = usuario != null
                && (usuario.getTipoDocumento() != null || usuario.getNumeroDocumento() != null);
        TipoDocumento tipo = tieneDocumentoDeRegistro ? usuario.getTipoDocumento() : paciente.getTipoDocumento();
        String numero = tieneDocumentoDeRegistro ? usuario.getNumeroDocumento() : paciente.getNumeroDocumento();
        if (tipo != TipoDocumento.DNI || numero == null) {
            return null;
        }
        String digitos = numero.strip().replace(".", "");
        if (!digitos.matches("[0-9]{7,8}")) {
            return null;
        }
        int valor = Integer.parseInt(digitos);
        return valor == 0 ? null : Integer.toString(valor);
    }

    private static String nombreCompleto(String nombre, String apellido) {
        return ((nombre == null ? "" : nombre) + " " + (apellido == null ? "" : apellido)).strip();
    }

    private static String nombreCompleto(UsuarioAuth usuario) {
        return usuario == null ? "" : nombreCompleto(usuario.getNombre(), usuario.getApellido());
    }
}
