package com.pretriage.backend.services;

import com.pretriage.backend.controllers.dtos.acceso.StaffAccessDtos.*;
import com.pretriage.backend.exceptions.ConflictoDeEstadoException;
import com.pretriage.backend.exceptions.RecursoNoEncontradoException;
import com.pretriage.backend.model.acceso.*;
import com.pretriage.backend.model.hospitales.EspecialidadMedica;
import com.pretriage.backend.model.hospitales.Hospital;
import com.pretriage.backend.model.consultas.EstadoSesionMedica;
import com.pretriage.backend.model.personas.*;
import com.pretriage.backend.model.recepcion.EstadoSesionRecepcion;
import com.pretriage.backend.repositories.*;
import com.pretriage.backend.services.ports.InvitationEmailPort;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.*;

@Service
@RequiredArgsConstructor
public class StaffAccessService {
    private static final Duration VIGENCIA_INVITACION = Duration.ofDays(7);

    private final RepoUsuariosAuth usuarios;
    private final RepoHospitales hospitales;
    private final RepoMembresiasHospital membresias;
    private final RepoInvitacionesHospital invitaciones;
    private final RepoAuditoriasHospital auditorias;
    private final RepoRecepcionistas recepcionistas;
    private final RepoMedico medicos;
    private final RepoSesionesAtencionMedica sesionesMedicas;
    private final RepoSesionesRecepcion sesionesRecepcion;
    private final RepoAsignacionesMedicoHospital asignaciones;
    private final RepoEspecialidadesMedicas especialidades;
    private final RepoCredencialesProfesionales credencialesProfesionales;
    private final AuthService authService;
    private final InvitationEmailPort invitationEmailPort;

    @Transactional
    public StaffMeResponse obtenerContexto(String subject) {
        UsuarioAuth usuario = usuario(subject);
        migrarAccesosLegados(usuario);
        List<MembresiaResponse> accesos = membresias
                .findByUsuarioIdAndEstado(subject, EstadoMembresiaHospital.ACTIVA).stream()
                .filter(m -> !m.getRolesEfectivos().isEmpty())
                .map(this::aMembresiaContextoResponse).toList();
        return new StaffMeResponse(usuario.getId(), usuario.getNombre(), usuario.getApellido(),
                usuario.getCorreoElectronico(), usuario.getRol() == RolSistema.ADMIN, accesos);
    }

    @Transactional
    public List<PersonalResponse> listarPersonal(String subject, Long hospitalId) {
        exigirAdminHospital(subject, hospitalId);
        return membresias.findByHospitalIdOrderByUsuarioApellidoAscUsuarioNombreAsc(hospitalId).stream()
                .map(m -> new PersonalResponse(m.getId(), m.getUsuario().getNombre(), m.getUsuario().getApellido(),
                        m.getUsuario().getCorreoElectronico(), m.getEstado(), Set.copyOf(m.getRoles()),
                        Set.copyOf(m.getRolesSuspendidos())))
                .toList();
    }

    @Transactional
    public List<InvitacionResponse> listarInvitaciones(String subject, Long hospitalId) {
        exigirAdminHospital(subject, hospitalId);
        return invitaciones.findByHospitalIdOrderByFechaCreacionDesc(hospitalId).stream()
                .map(i -> aInvitacionResponse(i, null, null)).toList();
    }

    @Transactional
    public InvitacionResponse crearInvitacion(String subject, Long hospitalId, CrearInvitacionRequest request) {
        UsuarioAuth actor = exigirAdminHospital(subject, hospitalId);
        return crearInvitacion(actor, hospitalId, request);
    }

    @Transactional
    public InvitacionResponse crearPrimerAdmin(String subject, Long hospitalId, CrearInvitacionRequest request) {
        UsuarioAuth actor = usuario(subject);
        if (actor.getRol() != RolSistema.ADMIN) {
            throw new AccessDeniedException("Se requiere administración de plataforma");
        }
        Set<RolMembresiaHospital> roles = new LinkedHashSet<>(request.roles());
        roles.add(RolMembresiaHospital.ADMIN_HOSPITAL);
        return crearInvitacion(actor, hospitalId,
                new CrearInvitacionRequest(request.email(), roles, request.matricula(),
                        request.tipoMatricula(), request.jurisdiccionMatricula(), request.especialidadIds()));
    }

    @Transactional(readOnly = true)
    public List<HospitalPlataformaResponse> listarHospitalesPlataforma(String subject) {
        exigirAdminPlataforma(subject);
        return hospitales.findAll().stream()
                .sorted(Comparator.comparing(Hospital::getNombre, String.CASE_INSENSITIVE_ORDER))
                .map(hospital -> new HospitalPlataformaResponse(hospital.getId(), hospital.getNombre(),
                        membresias.countByHospitalIdAndEstadoAndRolesContaining(hospital.getId(),
                                EstadoMembresiaHospital.ACTIVA, RolMembresiaHospital.ADMIN_HOSPITAL)))
                .toList();
    }

    private UsuarioAuth exigirAdminPlataforma(String subject) {
        UsuarioAuth actor = usuario(subject);
        if (actor.getRol() != RolSistema.ADMIN) {
            throw new AccessDeniedException("Se requiere administración de plataforma");
        }
        return actor;
    }

    private InvitacionResponse crearInvitacion(UsuarioAuth actor, Long hospitalId, CrearInvitacionRequest request) {
        Hospital hospital = hospitales.findById(hospitalId)
                .orElseThrow(() -> new RecursoNoEncontradoException("Hospital inexistente"));
        String email = normalizarEmail(request.email());
        if (invitaciones.existsByHospitalIdAndEmailNormalizadoAndEstado(hospitalId, email,
                EstadoInvitacionHospital.PENDIENTE)) {
            throw new ConflictoDeEstadoException("Ya existe una invitación pendiente para ese correo");
        }
        String jurisdiccion = validarDatosMedicos(request.roles(), request.matricula(),
                request.tipoMatricula(), request.jurisdiccionMatricula(), request.especialidadIds(), hospital);

        String token = nuevoToken();
        InvitacionHospital invitacion = new InvitacionHospital();
        invitacion.setHospital(hospital);
        invitacion.setEmailNormalizado(email);
        invitacion.setRolesSolicitados(new LinkedHashSet<>(request.roles()));
        invitacion.setEspecialidadIds(request.especialidadIds() == null
                ? new LinkedHashSet<>()
                : new LinkedHashSet<>(request.especialidadIds()));
        invitacion.setMatricula(limpiar(request.matricula()));
        invitacion.setTipoMatricula(request.roles().contains(RolMembresiaHospital.MEDICO)
                ? request.tipoMatricula()
                : null);
        invitacion.setJurisdiccionMatricula(jurisdiccion);
        invitacion.setTokenHash(hash(token));
        invitacion.setVenceEn(Instant.now().plus(VIGENCIA_INVITACION));
        invitacion.setInvitadaPor(actor);
        invitaciones.save(invitacion);
        InvitationEmailPort.DeliveryResult delivery = entregarInvitacion(invitacion, token);
        auditar(hospital, actor, "INVITACION_CREADA", "invitacion:" + invitacion.getId(),
                invitacion.getRolesSolicitados().toString());
        return aInvitacionResponse(invitacion, delivery.sent(),
                delivery.revealTokenToAdministrator() ? token : null);
    }

    @Transactional
    public InvitacionResponse reenviarInvitacion(String subject, Long hospitalId, Long invitacionId) {
        UsuarioAuth actor = exigirAdminHospital(subject, hospitalId);
        InvitacionHospital invitacion = invitaciones.findById(invitacionId)
                .filter(i -> i.getHospital().getId().equals(hospitalId))
                .orElseThrow(() -> new RecursoNoEncontradoException("Invitación inexistente"));
        EstadoInvitacionHospital estado = estadoActual(invitacion);
        if (estado == EstadoInvitacionHospital.ACEPTADA || estado == EstadoInvitacionHospital.REVOCADA) {
            throw new ConflictoDeEstadoException("La invitación ya no puede reenviarse");
        }
        String token = nuevoToken();
        invitacion.setEstado(EstadoInvitacionHospital.PENDIENTE);
        invitacion.setTokenHash(hash(token));
        invitacion.setVenceEn(Instant.now().plus(VIGENCIA_INVITACION));
        invitaciones.save(invitacion);
        InvitationEmailPort.DeliveryResult delivery = entregarInvitacion(invitacion, token);
        auditar(invitacion.getHospital(), actor, "INVITACION_REENVIADA",
                "invitacion:" + invitacionId, delivery.sent() ? "ENVIADA" : "PENDIENTE_ENTREGA");
        return aInvitacionResponse(invitacion, delivery.sent(),
                delivery.revealTokenToAdministrator() ? token : null);
    }

    private InvitationEmailPort.DeliveryResult entregarInvitacion(InvitacionHospital invitacion, String token) {
        InvitationEmailPort.DeliveryResult result = invitationEmailPort.deliver(
                new InvitationEmailPort.InvitationEmailMessage(invitacion.getEmailNormalizado(),
                        invitacion.getHospital().getNombre(), Set.copyOf(invitacion.getRolesSolicitados()),
                        invitacion.getVenceEn(), token));
        invitacion.setEmailEnviado(result.sent());
        invitacion.setUltimoIntentoEnvio(Instant.now());
        invitacion.setCantidadIntentosEnvio(Optional.ofNullable(invitacion.getCantidadIntentosEnvio()).orElse(0) + 1);
        return result;
    }

    @Transactional(readOnly = true)
    public InvitacionResumenResponse resumir(String token) {
        InvitacionHospital invitacion = invitacionValida(token, false);
        return new InvitacionResumenResponse(invitacion.getHospital().getId(), invitacion.getHospital().getNombre(),
                invitacion.getEmailNormalizado(), estadoActual(invitacion),
                Set.copyOf(invitacion.getRolesSolicitados()),
                Set.copyOf(invitacion.getEspecialidadIds()), invitacion.getMatricula(),
                invitacion.getTipoMatricula(), invitacion.getJurisdiccionMatricula(), invitacion.getVenceEn(),
                usuarios.findByCorreoElectronicoIgnoreCase(invitacion.getEmailNormalizado()).isPresent());
    }

    @Transactional
    public MembresiaResponse aceptar(String subject, String token) {
        UsuarioAuth usuario = usuario(subject);
        InvitacionHospital invitacion = invitacionValida(token, true);
        if (!normalizarEmail(usuario.getCorreoElectronico()).equals(invitacion.getEmailNormalizado())) {
            throw new AccessDeniedException("La invitación pertenece a otro correo electrónico");
        }
        return aceptar(invitacion, usuario);
    }

    @Transactional
    public MembresiaResponse registrarYAceptar(String token, RegistrarInvitadoRequest request) {
        InvitacionHospital invitacion = invitacionValida(token, true);
        if (usuarios.findByCorreoElectronicoIgnoreCase(invitacion.getEmailNormalizado()).isPresent()) {
            throw new ConflictoDeEstadoException("La cuenta ya existe; iniciá sesión para aceptar la invitación");
        }
        String numeroDocumento = request.numeroDocumento().replaceAll("\\D", "");
        if (usuarios.existsByNumeroDocumento(numeroDocumento)) {
            throw new ConflictoDeEstadoException("Ya existe una cuenta con ese número de documento");
        }
        String auth0Id = authService.registrarUsuarioYObtenerAuth0Id(invitacion.getEmailNormalizado(),
                request.password());
        UsuarioAuth usuario = new UsuarioAuth();
        usuario.setId(auth0Id);
        usuario.setNombre(request.nombre().trim());
        usuario.setApellido(request.apellido().trim());
        usuario.setNumeroDocumento(numeroDocumento);
        usuario.setTipoDocumento(request.tipoDocumento());
        usuario.setCorreoElectronico(invitacion.getEmailNormalizado());
        usuario.setRol(RolSistema.USER);
        usuario = usuarios.save(usuario);
        return aceptar(invitacion, usuario);
    }

    private MembresiaResponse aceptar(InvitacionHospital invitacion, UsuarioAuth usuario) {
        Long hospitalId = invitacion.getHospital().getId();
        Hospital hospital = bloquearHospital(hospitalId);
        Optional<MembresiaHospital> membresiaExistente = membresias
                .findByUsuarioIdAndHospitalId(usuario.getId(), hospitalId);
        if (membresiaExistente.isPresent()
                && membresiaExistente.get().getEstado() == EstadoMembresiaHospital.SUSPENDIDA) {
            throw new ConflictoDeEstadoException(
                    "La membresía está suspendida. Pedile a la administración del hospital que reactive el acceso explícitamente.");
        }
        MembresiaHospital membresia = membresiaExistente.orElseGet(MembresiaHospital::new);
        membresia.setUsuario(usuario);
        membresia.setHospital(hospital);
        membresia.setCreadaPor(invitacion.getInvitadaPor());
        membresia.getRoles().addAll(invitacion.getRolesSolicitados());
        membresia.setEstado(EstadoMembresiaHospital.ACTIVA);
        membresia.setFechaAceptacion(Instant.now());
        membresia.setFechaSuspension(null);
        membresias.save(membresia);
        materializarPerfilesCompatibles(usuario, invitacion);
        invitacion.setEstado(EstadoInvitacionHospital.ACEPTADA);
        invitacion.setAceptadaPor(usuario);
        invitacion.setFechaAceptacion(Instant.now());
        invitaciones.save(invitacion);
        auditar(invitacion.getHospital(), usuario, "INVITACION_ACEPTADA", "membresia:" + membresia.getId(),
                membresia.getRoles().toString());
        return aMembresiaResponse(membresia);
    }

    @Transactional
    public MembresiaResponse actualizarEstado(String subject, Long hospitalId, Long membresiaId,
            ActualizarMembresiaRequest request) {
        bloquearHospital(hospitalId);
        UsuarioAuth actor = exigirAdminHospital(subject, hospitalId);
        MembresiaHospital membresia = membresiaDelHospital(membresiaId, hospitalId);
        if (request.estado() != EstadoMembresiaHospital.ACTIVA) {
            protegerSesionesActivas(membresia, Set.of(), true);
        }
        protegerUltimoAdmin(membresia, request.estado(), membresia.getRolesEfectivos());
        membresia.setEstado(request.estado());
        membresia.setFechaSuspension(request.estado() == EstadoMembresiaHospital.SUSPENDIDA ? Instant.now() : null);
        membresias.save(membresia);
        auditar(membresia.getHospital(), actor, "MEMBRESIA_ESTADO", "membresia:" + membresiaId,
                request.estado().name());
        return aMembresiaResponse(membresia);
    }

    @Transactional
    public MembresiaResponse actualizarRoles(String subject, Long hospitalId, Long membresiaId,
            ActualizarRolesRequest request) {
        bloquearHospital(hospitalId);
        UsuarioAuth actor = exigirAdminHospital(subject, hospitalId);
        MembresiaHospital membresia = membresiaDelHospital(membresiaId, hospitalId);
        if (membresia.getEstado() == EstadoMembresiaHospital.ACTIVA) {
            protegerSesionesActivas(membresia, request.roles(), false);
        }
        Set<RolMembresiaHospital> rolesSuspendidos = new LinkedHashSet<>(membresia.getRolesSuspendidos());
        rolesSuspendidos.retainAll(request.roles());
        Set<RolMembresiaHospital> efectivosNuevos = new LinkedHashSet<>(request.roles());
        efectivosNuevos.removeAll(rolesSuspendidos);
        protegerUltimoAdmin(membresia, membresia.getEstado(), efectivosNuevos);
        membresia.setRoles(new LinkedHashSet<>(request.roles()));
        membresia.setRolesSuspendidos(rolesSuspendidos);
        membresias.save(membresia);
        auditar(membresia.getHospital(), actor, "MEMBRESIA_ROLES", "membresia:" + membresiaId,
                request.roles().toString());
        return aMembresiaResponse(membresia);
    }

    @Transactional
    public MembresiaResponse actualizarEstadoRol(String subject, Long hospitalId, Long membresiaId,
            RolMembresiaHospital rol, ActualizarEstadoRolRequest request) {
        bloquearHospital(hospitalId);
        UsuarioAuth actor = exigirAdminHospital(subject, hospitalId);
        MembresiaHospital membresia = membresiaDelHospital(membresiaId, hospitalId);
        if (!membresia.getRoles().contains(rol)) {
            throw new ConflictoDeEstadoException("El rol no está asignado a esta membresía");
        }
        if (request.estado() != EstadoMembresiaHospital.ACTIVA
                && request.estado() != EstadoMembresiaHospital.SUSPENDIDA) {
            throw new ConflictoDeEstadoException("El estado del rol debe ser ACTIVA o SUSPENDIDA");
        }
        boolean suspendiendo = request.estado() == EstadoMembresiaHospital.SUSPENDIDA;
        boolean yaSuspendido = membresia.getRolesSuspendidos().contains(rol);
        if (suspendiendo == yaSuspendido) return aMembresiaResponse(membresia);

        Set<RolMembresiaHospital> efectivosNuevos = new LinkedHashSet<>(membresia.getRolesEfectivos());
        if (suspendiendo) efectivosNuevos.remove(rol);
        else efectivosNuevos.add(rol);

        if (membresia.getEstado() == EstadoMembresiaHospital.ACTIVA && suspendiendo) {
            protegerSesionesActivas(membresia, efectivosNuevos, false);
        }
        if (rol == RolMembresiaHospital.ADMIN_HOSPITAL && suspendiendo) {
            protegerUltimoAdmin(membresia, membresia.getEstado(), efectivosNuevos);
        }
        if (suspendiendo) membresia.getRolesSuspendidos().add(rol);
        else membresia.getRolesSuspendidos().remove(rol);
        membresias.save(membresia);
        auditar(membresia.getHospital(), actor, "MEMBRESIA_ROL_ESTADO", "membresia:" + membresiaId + ":rol:" + rol,
                request.estado().name());
        return aMembresiaResponse(membresia);
    }

    @Transactional
    public void revocarInvitacion(String subject, Long hospitalId, Long invitacionId) {
        UsuarioAuth actor = exigirAdminHospital(subject, hospitalId);
        InvitacionHospital invitacion = invitaciones.findById(invitacionId)
                .filter(i -> i.getHospital().getId().equals(hospitalId))
                .orElseThrow(() -> new RecursoNoEncontradoException("Invitación inexistente"));
        if (invitacion.getEstado() != EstadoInvitacionHospital.PENDIENTE) {
            throw new ConflictoDeEstadoException("La invitación ya no está pendiente");
        }
        invitacion.setEstado(EstadoInvitacionHospital.REVOCADA);
        invitaciones.save(invitacion);
        auditar(invitacion.getHospital(), actor, "INVITACION_REVOCADA", "invitacion:" + invitacionId, "REVOCADA");
    }

    @Transactional
    public List<AuditoriaResponse> listarAuditoria(String subject, Long hospitalId) {
        exigirAdminHospital(subject, hospitalId);
        return auditorias.findTop50ByHospitalIdOrderByFechaDesc(hospitalId).stream()
                .map(a -> new AuditoriaResponse(a.getId(), a.getFecha(),
                        a.getActor().getNombre() + " " + a.getActor().getApellido(), a.getAccion(),
                        a.getObjetivo(), a.getResultado()))
                .toList();
    }

    public UsuarioAuth exigirAdminHospital(String subject, Long hospitalId) {
        UsuarioAuth usuario = usuario(subject);
        migrarAccesosLegados(usuario, hospitalId);
        return exigirAdminHospitalSinMigrar(subject, hospitalId);
    }

    private UsuarioAuth exigirAdminHospitalSinMigrar(String subject, Long hospitalId) {
        MembresiaHospital membresia = membresias.findByUsuarioIdAndHospitalId(subject, hospitalId)
                .filter(m -> m.getEstado() == EstadoMembresiaHospital.ACTIVA)
                .filter(m -> m.getRolesEfectivos().contains(RolMembresiaHospital.ADMIN_HOSPITAL))
                .orElseThrow(() -> new AccessDeniedException("No administrás este hospital"));
        return membresia.getUsuario();
    }

    private void migrarAccesosLegados(UsuarioAuth usuario) {
        migrarAccesosLegados(usuario, null);
    }

    private void migrarAccesosLegados(UsuarioAuth usuario, Long hospitalIdObjetivo) {
        Map<Long, Hospital> hospitalesLegados = new TreeMap<>();
        Map<Long, Set<RolMembresiaHospital>> rolesPorHospital = new TreeMap<>();
        recepcionistas.findRecepcionistaByUsuarioAuthId(usuario.getId()).ifPresent(
                recepcionista -> hospitales.findByRecepcionistasUsuarioAuthId(usuario.getId()).forEach(hospital -> {
                    Long hospitalId = hospital.getId();
                    if (hospitalIdObjetivo != null && !hospitalIdObjetivo.equals(hospitalId)) return;
                    hospitalesLegados.put(hospitalId, hospital);
                    Set<RolMembresiaHospital> roles = rolesPorHospital.computeIfAbsent(hospitalId,
                            ignored -> new LinkedHashSet<>());
                    roles.add(RolMembresiaHospital.RECEPCIONISTA);
                    if (usuario.getRol() == RolSistema.ADMIN) roles.add(RolMembresiaHospital.ADMIN_HOSPITAL);
                }));
        asignaciones.findByMedicoUsuarioAuthId(usuario.getId()).forEach(asignacion -> {
            Hospital hospital = asignacion.getHospital();
            Long hospitalId = hospital.getId();
            if (hospitalIdObjetivo != null && !hospitalIdObjetivo.equals(hospitalId)) return;
            hospitalesLegados.put(hospitalId, hospital);
            rolesPorHospital.computeIfAbsent(hospitalId, ignored -> new LinkedHashSet<>())
                    .add(RolMembresiaHospital.MEDICO);
        });
        hospitalesLegados.forEach((hospitalId, hospital) -> activarLegada(usuario, hospital,
                rolesPorHospital.get(hospitalId)));
    }

    private void activarLegada(UsuarioAuth usuario, Hospital hospital, Set<RolMembresiaHospital> roles) {
        Optional<MembresiaHospital> existente = membresias.findByUsuarioIdAndHospitalId(usuario.getId(), hospital.getId());
        if (existente.isPresent()) {
            return;
        }
        Hospital hospitalBloqueado = bloquearHospital(hospital.getId());
        if (membresias.findByUsuarioIdAndHospitalId(usuario.getId(), hospital.getId()).isPresent()) {
            return;
        }
        MembresiaHospital membresia = new MembresiaHospital();
        membresia.setUsuario(usuario);
        membresia.setHospital(hospitalBloqueado);
        membresia.getRoles().addAll(roles);
        membresia.getRolesSuspendidos().retainAll(membresia.getRoles());
        membresia.setEstado(EstadoMembresiaHospital.ACTIVA);
        if (membresia.getFechaAceptacion() == null)
            membresia.setFechaAceptacion(Instant.now());
        membresias.save(membresia);
    }

    private Hospital bloquearHospital(Long hospitalId) {
        return hospitales.findByIdForUpdate(hospitalId)
                .orElseThrow(() -> new RecursoNoEncontradoException("Hospital inexistente"));
    }

    private void protegerSesionesActivas(MembresiaHospital membresia,
            Set<RolMembresiaHospital> rolesConservados, boolean deshabilitarMembresia) {
        String usuarioId = membresia.getUsuario().getId();
        Long hospitalId = membresia.getHospital().getId();
        Set<RolMembresiaHospital> rolesActivos = membresia.getRolesEfectivos();
        boolean quitarRolMedico = deshabilitarMembresia
                || rolesActivos.contains(RolMembresiaHospital.MEDICO)
                        && !rolesConservados.contains(RolMembresiaHospital.MEDICO);
        boolean quitarRolRecepcion = deshabilitarMembresia
                || rolesActivos.contains(RolMembresiaHospital.RECEPCIONISTA)
                        && !rolesConservados.contains(RolMembresiaHospital.RECEPCIONISTA);
        if (quitarRolMedico
                && medicos.findByUsuarioAuthId(usuarioId).filter(medico -> sesionesMedicas
                        .existsByMedicoIdAndHospitalIdAndEstadoIn(medico.getId(), hospitalId,
                                List.of(EstadoSesionMedica.ACTIVA, EstadoSesionMedica.PAUSADA)))
                        .isPresent()) {
            throw new ConflictoDeEstadoException(
                    "No se puede suspender ni quitar el rol médico mientras haya una sesión médica activa o pausada en este hospital. Cerrá la sesión primero.");
        }
        if (quitarRolRecepcion
                && recepcionistas.findRecepcionistaByUsuarioAuthId(usuarioId)
                        .filter(recepcionista -> sesionesRecepcion.existsByRecepcionistaIdAndHospitalIdAndEstado(
                                recepcionista.getId(), hospitalId, EstadoSesionRecepcion.ACTIVA))
                        .isPresent()) {
            throw new ConflictoDeEstadoException(
                    "No se puede suspender ni quitar el rol de recepción mientras haya una sesión de recepción activa en este hospital. Cerrá la sesión primero.");
        }
    }

    private void materializarPerfilesCompatibles(UsuarioAuth usuario, InvitacionHospital invitacion) {
        if (invitacion.getRolesSolicitados().contains(RolMembresiaHospital.RECEPCIONISTA)) {
            Recepcionista recepcionista = recepcionistas.findRecepcionistaByUsuarioAuthId(usuario.getId())
                    .orElseGet(() -> {
                        Recepcionista nuevo = new Recepcionista();
                        nuevo.setUsuarioAuth(usuario);
                        return recepcionistas.save(nuevo);
                    });
            if (invitacion.getHospital().getRecepcionistas().stream()
                    .noneMatch(r -> r.getId().equals(recepcionista.getId()))) {
                invitacion.getHospital().getRecepcionistas().add(recepcionista);
                hospitales.save(invitacion.getHospital());
            }
        }
        if (invitacion.getRolesSolicitados().contains(RolMembresiaHospital.MEDICO)) {
            Medico medico = medicos.findByUsuarioAuthId(usuario.getId()).orElseGet(() -> {
                Medico nuevo = new Medico();
                nuevo.setUsuarioAuth(usuario);
                nuevo.setMatricula(invitacion.getMatricula());
                return medicos.save(nuevo);
            });
            crearCredencialSiNoExiste(medico, invitacion.getMatricula(), invitacion.getTipoMatricula(),
                    invitacion.getJurisdiccionMatricula());
            for (Long especialidadId : invitacion.getEspecialidadIds()) {
                EspecialidadMedica especialidad = especialidades.findById(especialidadId)
                        .orElseThrow(() -> new RecursoNoEncontradoException("Especialidad inexistente"));
                if (!asignaciones.existsByMedicoIdAndHospitalIdAndEspecialidadCodigo(
                        medico.getId(), invitacion.getHospital().getId(), especialidad.getCodigo())) {
                    AsignacionMedicoHospital asignacion = new AsignacionMedicoHospital();
                    asignacion.setMedico(medico);
                    asignacion.setHospital(invitacion.getHospital());
                    asignacion.setEspecialidad(especialidad);
                    asignaciones.save(asignacion);
                }
            }
        }
    }

    private String validarDatosMedicos(Set<RolMembresiaHospital> roles, String matricula,
            TipoMatriculaProfesional tipoMatricula, String jurisdiccionMatricula,
            Set<Long> especialidadIds, Hospital hospital) {
        if (!roles.contains(RolMembresiaHospital.MEDICO))
            return null;
        if (limpiar(matricula) == null || tipoMatricula == null
                || especialidadIds == null || especialidadIds.isEmpty()) {
            throw new IllegalStateException(
                    "Una invitación médica requiere número, tipo de matrícula y especialidades");
        }
        String jurisdiccion = tipoMatricula == TipoMatriculaProfesional.NACIONAL
                ? CredencialProfesional.JURISDICCION_NACIONAL
                : normalizarJurisdiccion(jurisdiccionMatricula);
        if (tipoMatricula == TipoMatriculaProfesional.PROVINCIAL && jurisdiccion == null) {
            throw new IllegalStateException("Una matrícula provincial requiere jurisdicción");
        }
        Set<Long> habilitadas = hospital.getEspecialidades().stream().map(EspecialidadMedica::getId)
                .collect(java.util.stream.Collectors.toSet());
        if (!habilitadas.containsAll(especialidadIds)) {
            throw new IllegalStateException("Las especialidades deben estar habilitadas en el hospital");
        }
        return jurisdiccion;
    }

    private void crearCredencialSiNoExiste(Medico medico, String numero,
            TipoMatriculaProfesional tipo, String jurisdiccion) {
        if (credencialesProfesionales.existsByNumeroAndTipoAndJurisdiccionIgnoreCase(
                numero, tipo, jurisdiccion))
            return;
        CredencialProfesional credencial = new CredencialProfesional();
        credencial.setMedico(medico);
        credencial.setNumero(numero);
        credencial.setTipo(tipo);
        credencial.setJurisdiccion(jurisdiccion);
        credencialesProfesionales.save(credencial);
    }

    private void protegerUltimoAdmin(MembresiaHospital membresia, EstadoMembresiaHospital estadoNuevo,
            Set<RolMembresiaHospital> rolesNuevos) {
        boolean dejaDeSerAdmin = membresia.getEstado() == EstadoMembresiaHospital.ACTIVA
                && membresia.getRolesEfectivos().contains(RolMembresiaHospital.ADMIN_HOSPITAL)
                && (estadoNuevo != EstadoMembresiaHospital.ACTIVA
                        || !rolesNuevos.contains(RolMembresiaHospital.ADMIN_HOSPITAL));
        if (dejaDeSerAdmin && contarAdminsActivos(membresia.getHospital().getId()) <= 1) {
            throw new ConflictoDeEstadoException("No se puede quitar el último administrador activo del hospital");
        }
    }

    private long contarAdminsActivos(Long hospitalId) {
        return membresias.findByHospitalIdOrderByUsuarioApellidoAscUsuarioNombreAsc(hospitalId).stream()
                .filter(m -> m.getEstado() == EstadoMembresiaHospital.ACTIVA)
                .filter(m -> m.getRolesEfectivos().contains(RolMembresiaHospital.ADMIN_HOSPITAL))
                .count();
    }

    private InvitacionHospital invitacionValida(String token, boolean exigirPendiente) {
        InvitacionHospital invitacion = invitaciones.findByTokenHash(hash(token))
                .orElseThrow(() -> new RecursoNoEncontradoException("Invitación inexistente"));
        EstadoInvitacionHospital estado = estadoActual(invitacion);
        if (exigirPendiente && estado != EstadoInvitacionHospital.PENDIENTE) {
            throw new ConflictoDeEstadoException("La invitación está " + estado.name().toLowerCase(Locale.ROOT));
        }
        return invitacion;
    }

    private EstadoInvitacionHospital estadoActual(InvitacionHospital invitacion) {
        if (invitacion.getEstado() == EstadoInvitacionHospital.PENDIENTE
                && invitacion.getVenceEn().isBefore(Instant.now())) {
            return EstadoInvitacionHospital.EXPIRADA;
        }
        return invitacion.getEstado();
    }

    private MembresiaHospital membresiaDelHospital(Long membresiaId, Long hospitalId) {
        return membresias.findById(membresiaId).filter(m -> m.getHospital().getId().equals(hospitalId))
                .orElseThrow(() -> new RecursoNoEncontradoException("Membresía inexistente"));
    }

    private UsuarioAuth usuario(String subject) {
        return usuarios.findById(subject)
                .orElseThrow(() -> new RecursoNoEncontradoException("Identidad local inexistente"));
    }

    private MembresiaResponse aMembresiaResponse(MembresiaHospital m) {
        return new MembresiaResponse(m.getId(), m.getHospital().getId(), m.getHospital().getNombre(),
                m.getEstado(), Set.copyOf(m.getRoles()), Set.copyOf(m.getRolesSuspendidos()),
                Set.copyOf(m.getRolesEfectivos()));
    }

    private MembresiaResponse aMembresiaContextoResponse(MembresiaHospital m) {
        Set<RolMembresiaHospital> rolesEfectivos = Set.copyOf(m.getRolesEfectivos());
        return new MembresiaResponse(m.getId(), m.getHospital().getId(), m.getHospital().getNombre(),
                m.getEstado(), rolesEfectivos, Set.copyOf(m.getRolesSuspendidos()), rolesEfectivos);
    }

    private InvitacionResponse aInvitacionResponse(InvitacionHospital i, Boolean emailEnviado, String token) {
        return new InvitacionResponse(i.getId(), i.getHospital().getId(), i.getHospital().getNombre(),
                i.getEmailNormalizado(), estadoActual(i), Set.copyOf(i.getRolesSolicitados()),
                Set.copyOf(i.getEspecialidadIds()), i.getMatricula(), i.getTipoMatricula(),
                i.getJurisdiccionMatricula(), i.getVenceEn(), i.getFechaCreacion(),
                emailEnviado != null ? emailEnviado : i.getEmailEnviado(), i.getUltimoIntentoEnvio(),
                i.getCantidadIntentosEnvio(), token);
    }

    private void auditar(Hospital hospital, UsuarioAuth actor, String accion, String objetivo, String resultado) {
        AuditoriaHospital auditoria = new AuditoriaHospital();
        auditoria.setHospital(hospital);
        auditoria.setActor(actor);
        auditoria.setAccion(accion);
        auditoria.setObjetivo(objetivo);
        auditoria.setResultado(resultado);
        auditorias.save(auditoria);
    }

    private static String normalizarEmail(String email) {
        return email.trim().toLowerCase(Locale.ROOT);
    }

    private static String limpiar(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static String normalizarJurisdiccion(String value) {
        String limpia = limpiar(value);
        return limpia == null ? null : limpia.toUpperCase(Locale.ROOT);
    }

    private static String nuevoToken() {
        byte[] bytes = new byte[32];
        new SecureRandom().nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private static String hash(String token) {
        if (token == null || token.isBlank())
            throw new RecursoNoEncontradoException("Invitación inexistente");
        try {
            return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(token.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 no disponible", e);
        }
    }
}
