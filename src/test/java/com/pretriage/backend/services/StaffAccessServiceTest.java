package com.pretriage.backend.services;

import com.pretriage.backend.controllers.dtos.acceso.StaffAccessDtos.ActualizarMembresiaRequest;
import com.pretriage.backend.controllers.dtos.acceso.StaffAccessDtos.ActualizarRolesRequest;
import com.pretriage.backend.controllers.dtos.acceso.StaffAccessDtos.ActualizarEstadoRolRequest;
import com.pretriage.backend.controllers.dtos.acceso.StaffAccessDtos.ActualizarPerfilStaffRequest;
import com.pretriage.backend.controllers.dtos.acceso.StaffAccessDtos.CrearInvitacionRequest;
import com.pretriage.backend.controllers.dtos.acceso.StaffAccessDtos.RegistrarInvitadoRequest;
import com.pretriage.backend.exceptions.ConflictoDeEstadoException;
import com.pretriage.backend.model.acceso.*;
import com.pretriage.backend.model.hospitales.Hospital;
import com.pretriage.backend.model.hospitales.EspecialidadMedica;
import com.pretriage.backend.model.personas.RolSistema;
import com.pretriage.backend.model.personas.UsuarioAuth;
import com.pretriage.backend.model.personas.TipoMatriculaProfesional;
import com.pretriage.backend.model.personas.TipoDocumento;
import com.pretriage.backend.model.personas.CredencialProfesional;
import com.pretriage.backend.model.personas.Medico;
import com.pretriage.backend.model.personas.Recepcionista;
import com.pretriage.backend.model.consultas.EstadoSesionMedica;
import com.pretriage.backend.model.recepcion.EstadoSesionRecepcion;
import com.pretriage.backend.repositories.*;
import com.pretriage.backend.services.ports.InvitationEmailPort;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class StaffAccessServiceTest {
    @Mock RepoUsuariosAuth usuarios;
    @Mock RepoHospitales hospitales;
    @Mock RepoMembresiasHospital membresias;
    @Mock RepoInvitacionesHospital invitaciones;
    @Mock RepoAuditoriasHospital auditorias;
    @Mock RepoRecepcionistas recepcionistas;
    @Mock RepoMedico medicos;
    @Mock RepoSesionesAtencionMedica sesionesMedicas;
    @Mock RepoSesionesRecepcion sesionesRecepcion;
    @Mock RepoAsignacionesMedicoHospital asignaciones;
    @Mock RepoEspecialidadesMedicas especialidades;
    @Mock RepoCredencialesProfesionales credencialesProfesionales;
    @Mock AuthService authService;
    @Mock InvitationEmailPort invitationEmailPort;

    private StaffAccessService service;
    private UsuarioAuth admin;
    private Hospital hospital;
    private MembresiaHospital membresiaAdmin;

    @BeforeEach
    void setUp() {
        service = new StaffAccessService(usuarios, hospitales, membresias, invitaciones, auditorias,
                recepcionistas, medicos, sesionesMedicas, sesionesRecepcion, asignaciones, especialidades,
                credencialesProfesionales, authService,
                invitationEmailPort);
        lenient().when(invitationEmailPort.deliver(any())).thenReturn(
                new InvitationEmailPort.DeliveryResult(false, true));
        admin = new UsuarioAuth();
        admin.setId("auth0|admin");
        admin.setNombre("Ada");
        admin.setApellido("Admin");
        admin.setCorreoElectronico("admin@example.com");
        admin.setRol(RolSistema.USER);
        hospital = new Hospital();
        hospital.setId(7L);
        hospital.setNombre("Hospital Escuela");
        membresiaAdmin = new MembresiaHospital();
        membresiaAdmin.setId(3L);
        membresiaAdmin.setUsuario(admin);
        membresiaAdmin.setHospital(hospital);
        membresiaAdmin.setEstado(EstadoMembresiaHospital.ACTIVA);
        membresiaAdmin.setRoles(new LinkedHashSet<>(Set.of(RolMembresiaHospital.ADMIN_HOSPITAL)));
    }

    private void stubAdminAccess() {
        when(usuarios.findById(admin.getId())).thenReturn(Optional.of(admin));
        when(recepcionistas.findRecepcionistaByUsuarioAuthId(admin.getId())).thenReturn(Optional.empty());
        when(asignaciones.findByMedicoUsuarioAuthId(admin.getId())).thenReturn(java.util.List.of());
        lenient().when(membresias.findByUsuarioIdAndHospitalId(admin.getId(), hospital.getId()))
                .thenReturn(Optional.of(membresiaAdmin));
    }

    private void stubHospitalLock() {
        when(hospitales.findByIdForUpdate(hospital.getId())).thenReturn(Optional.of(hospital));
    }

    @Test
    void actualizaElNombreDeLaCuentaAutenticadaYConservaSusAccesos() {
        stubAdminAccess();
        when(membresias.findByUsuarioIdAndEstado(admin.getId(), EstadoMembresiaHospital.ACTIVA))
                .thenReturn(List.of(membresiaAdmin));

        var perfil = service.actualizarPerfil(admin.getId(),
                new ActualizarPerfilStaffRequest(" Ana María ", " Pérez "));

        assertEquals("Ana María", admin.getNombre());
        assertEquals("Pérez", admin.getApellido());
        assertEquals(admin.getNombre(), perfil.nombre());
        assertEquals(admin.getApellido(), perfil.apellido());
        assertEquals("admin@example.com", perfil.email());
        assertEquals(RolSistema.USER, admin.getRol());
        assertEquals(Set.of(RolMembresiaHospital.ADMIN_HOSPITAL), perfil.membresias().getFirst().roles());
    }

    @Test
    void entregaElSecretoUnaVezYGuardaSoloSuHash() {
        stubAdminAccess();
        when(hospitales.findById(hospital.getId())).thenReturn(Optional.of(hospital));
        when(invitaciones.existsByHospitalIdAndEmailNormalizadoAndEstado(
                eq(hospital.getId()), eq("staff@example.com"), eq(EstadoInvitacionHospital.PENDIENTE)))
                .thenReturn(false);
        when(invitaciones.save(any())).thenAnswer(invocation -> {
            InvitacionHospital saved = invocation.getArgument(0);
            saved.setId(11L);
            return saved;
        });

        var response = service.crearInvitacion(admin.getId(), hospital.getId(),
                new CrearInvitacionRequest(" Staff@Example.com ",
                        Set.of(RolMembresiaHospital.RECEPCIONISTA), null, null, null, Set.of()));

        ArgumentCaptor<InvitacionHospital> captor = ArgumentCaptor.forClass(InvitacionHospital.class);
        verify(invitaciones).save(captor.capture());
        assertNotNull(response.tokenEntregaUnica());
        assertEquals(43, response.tokenEntregaUnica().length());
        assertEquals(64, captor.getValue().getTokenHash().length());
        assertNotEquals(response.tokenEntregaUnica(), captor.getValue().getTokenHash());
        assertEquals("staff@example.com", captor.getValue().getEmailNormalizado());
        assertEquals(Boolean.FALSE, response.emailEnviado());
    }

    @Test
    void impideSuspenderAlUltimoAdministradorActivo() {
        stubAdminAccess();
        stubHospitalLock();
        when(membresias.findById(membresiaAdmin.getId())).thenReturn(Optional.of(membresiaAdmin));
        when(membresias.findByHospitalIdOrderByUsuarioApellidoAscUsuarioNombreAsc(hospital.getId()))
                .thenReturn(java.util.List.of(membresiaAdmin));

        assertThrows(ConflictoDeEstadoException.class, () -> service.actualizarEstado(
                admin.getId(), hospital.getId(), membresiaAdmin.getId(),
                new ActualizarMembresiaRequest(EstadoMembresiaHospital.SUSPENDIDA)));
        verify(membresias, never()).save(membresiaAdmin);
    }

    @Test
    void noPermiteSuspenderMedicoConSesionAbiertaEnElHospital() {
        stubAdminAccess();
        stubHospitalLock();
        MembresiaHospital membresia = new MembresiaHospital();
        UsuarioAuth medicoUsuario = new UsuarioAuth(); medicoUsuario.setId("auth0|medico");
        membresia.setId(20L); membresia.setUsuario(medicoUsuario); membresia.setHospital(hospital);
        membresia.setEstado(EstadoMembresiaHospital.ACTIVA);
        membresia.setRoles(new LinkedHashSet<>(Set.of(RolMembresiaHospital.MEDICO)));
        Medico medico = new Medico(); medico.setId(8L);
        when(membresias.findById(20L)).thenReturn(Optional.of(membresia));
        when(medicos.findByUsuarioAuthId(medicoUsuario.getId())).thenReturn(Optional.of(medico));
        when(sesionesMedicas.existsByMedicoIdAndHospitalIdAndEstadoIn(8L, hospital.getId(),
                java.util.List.of(EstadoSesionMedica.ACTIVA, EstadoSesionMedica.PAUSADA))).thenReturn(true);

        ConflictoDeEstadoException error = assertThrows(ConflictoDeEstadoException.class,
                () -> service.actualizarEstado(admin.getId(), hospital.getId(), 20L,
                        new ActualizarMembresiaRequest(EstadoMembresiaHospital.SUSPENDIDA)));

        assertTrue(error.getMessage().contains("sesión médica activa o pausada"));
        verify(membresias, never()).save(membresia);
    }

    @Test
    void noPermiteRevocarRecepcionistaConSesionActivaEnElHospital() {
        stubAdminAccess();
        stubHospitalLock();
        MembresiaHospital membresia = new MembresiaHospital();
        UsuarioAuth recepUsuario = new UsuarioAuth(); recepUsuario.setId("auth0|recepcion");
        membresia.setId(22L); membresia.setUsuario(recepUsuario); membresia.setHospital(hospital);
        membresia.setEstado(EstadoMembresiaHospital.ACTIVA);
        membresia.setRoles(new LinkedHashSet<>(Set.of(RolMembresiaHospital.RECEPCIONISTA)));
        Recepcionista recepcionista = new Recepcionista(); recepcionista.setId(10L);
        when(membresias.findById(22L)).thenReturn(Optional.of(membresia));
        when(recepcionistas.findRecepcionistaByUsuarioAuthId(recepUsuario.getId()))
                .thenReturn(Optional.of(recepcionista));
        when(sesionesRecepcion.existsByRecepcionistaIdAndHospitalIdAndEstado(10L, hospital.getId(),
                EstadoSesionRecepcion.ACTIVA)).thenReturn(true);

        ConflictoDeEstadoException error = assertThrows(ConflictoDeEstadoException.class,
                () -> service.actualizarEstado(admin.getId(), hospital.getId(), 22L,
                        new ActualizarMembresiaRequest(EstadoMembresiaHospital.REVOCADA)));

        assertTrue(error.getMessage().contains("sesión de recepción activa"));
        verify(membresias, never()).save(membresia);
    }

    @Test
    void noPermiteQuitarRolRecepcionConSesionActivaEnElHospital() {
        stubAdminAccess();
        stubHospitalLock();
        MembresiaHospital membresia = new MembresiaHospital();
        UsuarioAuth recepUsuario = new UsuarioAuth(); recepUsuario.setId("auth0|recepcion");
        membresia.setId(21L); membresia.setUsuario(recepUsuario); membresia.setHospital(hospital);
        membresia.setEstado(EstadoMembresiaHospital.ACTIVA);
        membresia.setRoles(new LinkedHashSet<>(Set.of(RolMembresiaHospital.RECEPCIONISTA)));
        Recepcionista recepcionista = new Recepcionista(); recepcionista.setId(9L);
        when(membresias.findById(21L)).thenReturn(Optional.of(membresia));
        when(recepcionistas.findRecepcionistaByUsuarioAuthId(recepUsuario.getId()))
                .thenReturn(Optional.of(recepcionista));
        when(sesionesRecepcion.existsByRecepcionistaIdAndHospitalIdAndEstado(9L, hospital.getId(),
                EstadoSesionRecepcion.ACTIVA)).thenReturn(true);

        ConflictoDeEstadoException error = assertThrows(ConflictoDeEstadoException.class,
                () -> service.actualizarRoles(admin.getId(), hospital.getId(), 21L,
                        new ActualizarRolesRequest(Set.of(RolMembresiaHospital.MEDICO))));

        assertTrue(error.getMessage().contains("sesión de recepción activa"));
        verify(membresias, never()).save(membresia);
    }

    @Test
    void suspendeSoloElRolSolicitadoYConservaRolesAsignados() {
        stubAdminAccess();
        stubHospitalLock();
        UsuarioAuth staff = new UsuarioAuth(); staff.setId("auth0|multi-role");
        MembresiaHospital membresia = new MembresiaHospital();
        membresia.setId(28L); membresia.setUsuario(staff); membresia.setHospital(hospital);
        membresia.setEstado(EstadoMembresiaHospital.ACTIVA);
        membresia.setRoles(new LinkedHashSet<>(Set.of(RolMembresiaHospital.MEDICO,
                RolMembresiaHospital.RECEPCIONISTA)));
        when(membresias.findById(28L)).thenReturn(Optional.of(membresia));
        when(membresias.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        var response = service.actualizarEstadoRol(admin.getId(), hospital.getId(), 28L,
                RolMembresiaHospital.MEDICO,
                new ActualizarEstadoRolRequest(EstadoMembresiaHospital.SUSPENDIDA));

        assertEquals(Set.of(RolMembresiaHospital.MEDICO, RolMembresiaHospital.RECEPCIONISTA), response.roles());
        assertEquals(Set.of(RolMembresiaHospital.MEDICO), response.rolesSuspendidos());
        assertEquals(Set.of(RolMembresiaHospital.RECEPCIONISTA), response.rolesEfectivos());
        assertEquals(EstadoMembresiaHospital.ACTIVA, response.estado());
        verify(auditorias).save(argThat(a -> a.getAccion().equals("MEMBRESIA_ROL_ESTADO")
                && a.getObjetivo().contains("MEDICO") && a.getResultado().equals("SUSPENDIDA")));
    }

    @Test
    void contextoExponeRolesEfectivosEnRolesYOcultaMembresiasSinRolEfectivo() {
        UsuarioAuth staff = new UsuarioAuth(); staff.setId("auth0|context");
        MembresiaHospital parcial = new MembresiaHospital();
        parcial.setId(41L); parcial.setUsuario(staff); parcial.setHospital(hospital);
        parcial.setEstado(EstadoMembresiaHospital.ACTIVA);
        parcial.setRoles(new LinkedHashSet<>(Set.of(RolMembresiaHospital.MEDICO,
                RolMembresiaHospital.RECEPCIONISTA)));
        parcial.setRolesSuspendidos(new LinkedHashSet<>(Set.of(RolMembresiaHospital.MEDICO)));
        MembresiaHospital totalmenteSuspendida = new MembresiaHospital();
        totalmenteSuspendida.setId(42L); totalmenteSuspendida.setUsuario(staff);
        totalmenteSuspendida.setHospital(hospital);
        totalmenteSuspendida.setEstado(EstadoMembresiaHospital.ACTIVA);
        totalmenteSuspendida.setRoles(new LinkedHashSet<>(Set.of(RolMembresiaHospital.ADMIN_HOSPITAL)));
        totalmenteSuspendida.setRolesSuspendidos(new LinkedHashSet<>(Set.of(RolMembresiaHospital.ADMIN_HOSPITAL)));
        when(usuarios.findById(staff.getId())).thenReturn(Optional.of(staff));
        when(membresias.findByUsuarioIdAndEstado(staff.getId(), EstadoMembresiaHospital.ACTIVA))
                .thenReturn(java.util.List.of(parcial, totalmenteSuspendida));

        var context = service.obtenerContexto(staff.getId());

        assertEquals(1, context.membresias().size());
        assertEquals(Set.of(RolMembresiaHospital.RECEPCIONISTA), context.membresias().get(0).roles());
        assertEquals(Set.of(RolMembresiaHospital.MEDICO), context.membresias().get(0).rolesSuspendidos());
        assertEquals(Set.of(RolMembresiaHospital.RECEPCIONISTA), context.membresias().get(0).rolesEfectivos());
    }

    @Test
    void noPermiteSuspenderUltimoAdminEfectivo() {
        stubAdminAccess();
        stubHospitalLock();
        when(membresias.findById(membresiaAdmin.getId())).thenReturn(Optional.of(membresiaAdmin));
        when(membresias.findByHospitalIdOrderByUsuarioApellidoAscUsuarioNombreAsc(hospital.getId()))
                .thenReturn(java.util.List.of(membresiaAdmin));

        assertThrows(ConflictoDeEstadoException.class, () -> service.actualizarEstadoRol(admin.getId(),
                hospital.getId(), membresiaAdmin.getId(), RolMembresiaHospital.ADMIN_HOSPITAL,
                new ActualizarEstadoRolRequest(EstadoMembresiaHospital.SUSPENDIDA)));
        verify(membresias, never()).save(membresiaAdmin);
    }

    @Test
    void migracionLegadaNoReactivaMembresiasSuspendidasNiRevocadas() {
        UsuarioAuth staff = new UsuarioAuth(); staff.setId("auth0|legacy");
        Recepcionista recepcionista = new Recepcionista(); recepcionista.setId(18L);
        MembresiaHospital membresia = new MembresiaHospital();
        membresia.setUsuario(staff); membresia.setHospital(hospital);
        membresia.setRoles(new LinkedHashSet<>(Set.of(RolMembresiaHospital.RECEPCIONISTA)));
        when(usuarios.findById(staff.getId())).thenReturn(Optional.of(staff));
        when(recepcionistas.findRecepcionistaByUsuarioAuthId(staff.getId())).thenReturn(Optional.of(recepcionista));
        when(hospitales.findByRecepcionistasUsuarioAuthId(staff.getId())).thenReturn(java.util.List.of(hospital));
        when(asignaciones.findByMedicoUsuarioAuthId(staff.getId())).thenReturn(java.util.List.of());
        when(membresias.findByUsuarioIdAndHospitalId(staff.getId(), hospital.getId()))
                .thenReturn(Optional.of(membresia));

        for (EstadoMembresiaHospital estado : java.util.List.of(
                EstadoMembresiaHospital.SUSPENDIDA, EstadoMembresiaHospital.REVOCADA)) {
            membresia.setEstado(estado);
            service.obtenerContexto(staff.getId());

            assertEquals(estado, membresia.getEstado());
            verify(membresias, never()).save(membresia);
        }
    }

    @Test
    void invitacionAnteriorNoReactivaSuspensionPeroUnaNuevaInvitacionPuedeReactivarRevocada() {
        UsuarioAuth staff = new UsuarioAuth();
        staff.setId("auth0|staff"); staff.setCorreoElectronico("staff@example.com");
        InvitacionHospital invitacion = new InvitacionHospital();
        invitacion.setHospital(hospital);
        invitacion.setEmailNormalizado(staff.getCorreoElectronico());
        invitacion.setTokenHash("irrelevante");
        invitacion.setVenceEn(Instant.now().plusSeconds(600));
        invitacion.setEstado(EstadoInvitacionHospital.PENDIENTE);
        invitacion.setRolesSolicitados(new LinkedHashSet<>(Set.of(RolMembresiaHospital.ADMIN_HOSPITAL)));
        MembresiaHospital existente = new MembresiaHospital();
        existente.setId(30L); existente.setUsuario(staff); existente.setHospital(hospital);
        existente.setRoles(new LinkedHashSet<>(Set.of(RolMembresiaHospital.ADMIN_HOSPITAL)));
        existente.setEstado(EstadoMembresiaHospital.SUSPENDIDA);
        existente.setFechaSuspension(Instant.now());
        when(usuarios.findById(staff.getId())).thenReturn(Optional.of(staff));
        when(invitaciones.findByTokenHash(anyString())).thenReturn(Optional.of(invitacion));
        stubHospitalLock();
        when(membresias.findByUsuarioIdAndHospitalId(staff.getId(), hospital.getId()))
                .thenReturn(Optional.of(existente));
        when(membresias.save(any(MembresiaHospital.class))).thenAnswer(invocation -> invocation.getArgument(0));

        assertThrows(ConflictoDeEstadoException.class, () -> service.aceptar(staff.getId(), "token-anterior"));
        assertEquals(EstadoMembresiaHospital.SUSPENDIDA, existente.getEstado());
        assertEquals(EstadoInvitacionHospital.PENDIENTE, invitacion.getEstado());
        verify(membresias, never()).save(existente);

        existente.setEstado(EstadoMembresiaHospital.REVOCADA);
        service.aceptar(staff.getId(), "token-reinvitacion");

        assertEquals(EstadoMembresiaHospital.ACTIVA, existente.getEstado());
        assertNull(existente.getFechaSuspension());
        assertEquals(EstadoInvitacionHospital.ACEPTADA, invitacion.getEstado());
        verify(membresias).save(existente);
    }

    @Test
    void normalizaLaJurisdiccionDeUnaMatriculaNacional() {
        stubAdminAccess();
        EspecialidadMedica clinica = new EspecialidadMedica();
        clinica.setId(1L);
        clinica.setCodigo("CLINICA_MEDICA");
        hospital.setEspecialidades(java.util.List.of(clinica));
        when(hospitales.findById(hospital.getId())).thenReturn(Optional.of(hospital));
        when(invitaciones.existsByHospitalIdAndEmailNormalizadoAndEstado(any(), any(), any()))
                .thenReturn(false);
        when(invitaciones.save(any())).thenAnswer(invocation -> {
            InvitacionHospital saved = invocation.getArgument(0);
            saved.setId(12L);
            return saved;
        });

        var response = service.crearInvitacion(admin.getId(), hospital.getId(),
                new CrearInvitacionRequest("medico@example.com", Set.of(RolMembresiaHospital.MEDICO),
                        "11111111", TipoMatriculaProfesional.NACIONAL, "Buenos Aires", Set.of(1L)));

        assertEquals(TipoMatriculaProfesional.NACIONAL, response.tipoMatricula());
        assertEquals(CredencialProfesional.JURISDICCION_NACIONAL, response.jurisdiccionMatricula());
    }

    @Test
    void smtpNoExponeElTokenAlAdministrador() {
        stubAdminAccess();
        when(hospitales.findById(hospital.getId())).thenReturn(Optional.of(hospital));
        when(invitaciones.existsByHospitalIdAndEmailNormalizadoAndEstado(any(), any(), any())).thenReturn(false);
        when(invitaciones.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(invitationEmailPort.deliver(any())).thenReturn(
                new InvitationEmailPort.DeliveryResult(true, false));

        var response = service.crearInvitacion(admin.getId(), hospital.getId(),
                new CrearInvitacionRequest("staff@example.com",
                        Set.of(RolMembresiaHospital.RECEPCIONISTA), null, null, null, Set.of()));

        assertTrue(response.emailEnviado());
        assertNull(response.tokenEntregaUnica());
        assertEquals(1, response.cantidadIntentosEnvio());
    }

    @Test
    void reenviarRotaElTokenYRenuevaLaEntrega() {
        stubAdminAccess();
        InvitacionHospital invitacion = new InvitacionHospital();
        invitacion.setId(21L);
        invitacion.setHospital(hospital);
        invitacion.setEmailNormalizado("staff@example.com");
        invitacion.setTokenHash("a".repeat(64));
        invitacion.setVenceEn(Instant.now().plusSeconds(3600));
        invitacion.setRolesSolicitados(new LinkedHashSet<>(Set.of(RolMembresiaHospital.RECEPCIONISTA)));
        when(invitaciones.findById(invitacion.getId())).thenReturn(Optional.of(invitacion));
        when(invitaciones.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        var response = service.reenviarInvitacion(admin.getId(), hospital.getId(), invitacion.getId());

        assertNotEquals("a".repeat(64), invitacion.getTokenHash());
        assertNotNull(response.tokenEntregaUnica());
        assertEquals(1, response.cantidadIntentosEnvio());
        verify(invitationEmailPort).deliver(any());
    }

    @Test
    void aceptaLaInvitacionConLaInstanciaGestionadaDevueltaPorElRepositorio() {
        stubAdminAccess();
        stubHospitalLock();
        when(hospitales.findById(hospital.getId())).thenReturn(Optional.of(hospital));
        when(invitaciones.existsByHospitalIdAndEmailNormalizadoAndEstado(
                eq(hospital.getId()), eq("new-admin@example.com"), eq(EstadoInvitacionHospital.PENDIENTE)))
                .thenReturn(false);
        when(invitaciones.save(any())).thenAnswer(invocation -> {
            InvitacionHospital saved = invocation.getArgument(0);
            if (saved.getId() == null) saved.setId(21L);
            return saved;
        });

        var creada = service.crearInvitacion(admin.getId(), hospital.getId(),
                new CrearInvitacionRequest("new-admin@example.com",
                        Set.of(RolMembresiaHospital.ADMIN_HOSPITAL), null, null, null, Set.of()));

        ArgumentCaptor<InvitacionHospital> invitacionCaptor = ArgumentCaptor.forClass(InvitacionHospital.class);
        verify(invitaciones).save(invitacionCaptor.capture());
        InvitacionHospital invitacion = invitacionCaptor.getValue();
        when(invitaciones.findByTokenHash(invitacion.getTokenHash())).thenReturn(Optional.of(invitacion));
        when(usuarios.findByCorreoElectronicoIgnoreCase("new-admin@example.com")).thenReturn(Optional.empty());
        when(usuarios.existsByNumeroDocumento("22222222")).thenReturn(false);
        when(authService.registrarUsuarioYObtenerAuth0Id(anyString(), anyString()))
                .thenReturn("auth0|new-admin");

        UsuarioAuth managed = new UsuarioAuth();
        managed.setId("auth0|new-admin");
        managed.setNombre("Nuevo");
        managed.setApellido("Admin");
        managed.setNumeroDocumento("22222222");
        managed.setTipoDocumento(TipoDocumento.DNI);
        managed.setCorreoElectronico("new-admin@example.com");
        managed.setRol(RolSistema.USER);
        when(usuarios.save(any(UsuarioAuth.class))).thenReturn(managed);
        when(membresias.findByUsuarioIdAndHospitalId("auth0|new-admin", hospital.getId()))
                .thenReturn(Optional.empty());
        when(membresias.save(any(MembresiaHospital.class))).thenAnswer(invocation -> {
            MembresiaHospital saved = invocation.getArgument(0);
            saved.setId(31L);
            return saved;
        });

        service.registrarYAceptar(creada.tokenEntregaUnica(),
                new RegistrarInvitadoRequest("Nuevo", "Admin", "22222222", TipoDocumento.DNI,
                        "Strong!2026Password"));

        ArgumentCaptor<MembresiaHospital> membresiaCaptor = ArgumentCaptor.forClass(MembresiaHospital.class);
        verify(membresias).save(membresiaCaptor.capture());
        assertSame(managed, membresiaCaptor.getValue().getUsuario());
        assertEquals(EstadoInvitacionHospital.ACEPTADA, invitacion.getEstado());
    }
}
