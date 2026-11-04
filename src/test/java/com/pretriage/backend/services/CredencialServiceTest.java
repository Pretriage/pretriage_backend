package com.pretriage.backend.services;

import com.pretriage.backend.controllers.dtos.CredencialRequest;
import com.pretriage.backend.controllers.dtos.CredencialResponse;
import com.pretriage.backend.controllers.dtos.ObraSocialDTO;
import com.pretriage.backend.exceptions.CredencialInvalidaException;
import com.pretriage.backend.exceptions.ObraSocialNoExisteException;
import com.pretriage.backend.exceptions.ObraSocialYaExisteException;
import com.pretriage.backend.model.hospitales.Credencial;
import com.pretriage.backend.model.hospitales.ObraSocial;
import com.pretriage.backend.model.personas.Paciente;
import com.pretriage.backend.model.personas.RolSistema;
import com.pretriage.backend.model.personas.UsuarioAuth;
import com.pretriage.backend.repositories.RepoCredenciales;
import com.pretriage.backend.repositories.RepoObraSociales;
import com.pretriage.backend.services.validadoresObrasociales.FabricaValidadoresCredencialesObraSocial;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.access.AccessDeniedException;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CredencialServiceTest {

    @Mock
    private PacienteService pacienteService;

    @Mock
    private RecepcionistaService recepcionistaService;

    @Mock
    private RepoObraSociales repoObraSociales;

    @Mock
    private RepoCredenciales repoCredenciales;

    @Mock
    private ValidacionCredencialObraSocialService validacionCredencialObraSocialService;

    @Mock
    private FabricaValidadoresCredencialesObraSocial fabricaValidadoresCredencialesObraSocial;

    @InjectMocks
    private CredencialService service;

    @Test
    void pacientePuedeCargarCredencial() {

        UsuarioAuth usuario = new UsuarioAuth();
        usuario.setId("auth0|paciente");

        Paciente paciente = new Paciente();
        paciente.setUsuarioAuth(usuario);
        paciente.setId(1L);

        ObraSocial obraSocial = new ObraSocial();
        obraSocial.setId(2L);
        String nombreObraSocial = "OSDE";
        String numeroAfiliado = "123456" ;
        String plan = "210";
        obraSocial.setNombre(nombreObraSocial);

        CredencialRequest request = new CredencialRequest();
        request.setNombreObraSocial(nombreObraSocial);
        request.setNumeroAfiliado(numeroAfiliado);
        request.setPlan(plan);
        request.setFechaVencimiento(LocalDate.now().plusYears(1));

        when(pacienteService.obtenerPacienteConUsuarioAuthId("auth0|paciente"))
                .thenReturn(Optional.of(paciente));

        when(repoObraSociales.findByNombreEqualsIgnoreCaseAndVirgenteTrue(nombreObraSocial))
                .thenReturn(Optional.of(obraSocial));

        service.cargarCredencialPaciente(
                "auth0|paciente",
                request);

        verify(repoCredenciales).save(any(Credencial.class));
    }

    @Test
    void permiteCargarCredencialAunqueYaTengaCredencialVigente() {

        UsuarioAuth usuario = new UsuarioAuth();
        usuario.setId("auth0|paciente");

        Paciente paciente = new Paciente();
        paciente.setId(1L);
        paciente.setUsuarioAuth(usuario);

        Credencial credencialVigente = new Credencial();
        credencialVigente.setPaciente(paciente);
        credencialVigente.setFechaVencimiento(LocalDate.now().plusMonths(1));
        paciente.getCredenciales().add(credencialVigente);

        CredencialRequest request = new CredencialRequest();
        request.setNombreObraSocial("OSDE");
        request.setNumeroAfiliado("123456");
        request.setPlan("210");
        request.setFechaVencimiento(LocalDate.now().plusYears(1));

        ObraSocial obraSocial = new ObraSocial();
        obraSocial.setNombre("OSDE");

        when(pacienteService.obtenerPacienteConUsuarioAuthId("auth0|paciente"))
                .thenReturn(Optional.of(paciente));

        when(repoObraSociales.findByNombreEqualsIgnoreCaseAndVirgenteTrue("OSDE"))
                .thenReturn(Optional.of(obraSocial));

        service.cargarCredencialPaciente(
                "auth0|paciente",
                request);

        verify(repoCredenciales).save(any(Credencial.class));
    }

    @Test
    void pacientePuedeObtenerSusCredenciales() {

        UsuarioAuth usuario = new UsuarioAuth();
        usuario.setId("auth0|paciente");

        Paciente paciente = new Paciente();
        paciente.setId(1L);
        paciente.setUsuarioAuth(usuario);

        ObraSocial obraSocial = new ObraSocial();
        obraSocial.setId(2L);
        obraSocial.setNombre("OSDE");

        LocalDate fechaVencimiento = LocalDate.now().plusYears(1);

        Credencial credencial = new Credencial();
        credencial.setObraSocial(obraSocial);
        credencial.setNumeroAfiliado("123456");
        credencial.setPlan("210");
        credencial.setFechaVencimiento(fechaVencimiento);
        credencial.setPaciente(paciente);

        when(pacienteService.obtenerPacienteConUsuarioAuthId("auth0|paciente"))
                .thenReturn(Optional.of(paciente));

        when(repoCredenciales.findByPacienteId(1L))
                .thenReturn(List.of(credencial));

        List<CredencialResponse> credenciales = service.obtenerCredencialesPaciente("auth0|paciente");

        assertEquals(1, credenciales.size());
        assertEquals("123456", credenciales.getFirst().getNumeroAfiliado());
        assertEquals("210", credenciales.getFirst().getPlan());
        assertEquals(fechaVencimiento, credenciales.getFirst().getFechaVencimiento());
    }

    @Test
    void pacientePuedeEliminarSuCredencial() {

        UsuarioAuth usuario = new UsuarioAuth();
        String authoIdUser = "auth0|paciente";
        usuario.setId(authoIdUser);

        Paciente paciente = new Paciente();
        paciente.setId(1L);
        paciente.setUsuarioAuth(usuario);

        Credencial credencial = new Credencial();
        credencial.setId(1L);
        credencial.setPaciente(paciente);

        when(pacienteService.obtenerPacienteConUsuarioAuthId(authoIdUser))
                .thenReturn(Optional.of(paciente));

        when(repoCredenciales.findById(1L))
                .thenReturn(Optional.of(credencial));

        service.eliminarCredencial(1L, authoIdUser);

        verify(repoCredenciales).deleteById(1L);
    }

    @Test
    void pacienteNoPuedeEliminarCredencialDeOtroPaciente() {

        UsuarioAuth usuario = new UsuarioAuth();
        String authoIdUser1 = "auth0|paciente1";
        String authoIdUser2 = "auth0|paciente2";
        usuario.setId(authoIdUser1);

        Paciente paciente1 = new Paciente();
        paciente1.setId(1L);
        paciente1.setUsuarioAuth(usuario);

        UsuarioAuth usuario2 = new UsuarioAuth();
        usuario2.setId(authoIdUser2);

        Paciente paciente2 = new Paciente();
        paciente2.setId(2L);
        paciente2.setUsuarioAuth(usuario2);

        Credencial credencial = new Credencial();
        credencial.setId(1L);
        credencial.setPaciente(paciente2);

        when(pacienteService.obtenerPacienteConUsuarioAuthId(authoIdUser1))
                .thenReturn(Optional.of(paciente1));

        when(repoCredenciales.findById(1L))
                .thenReturn(Optional.of(credencial));

        assertThrows(
                AccessDeniedException.class,
                () -> service.eliminarCredencial(1L, authoIdUser1),
                "Debería lanzar AccessDeniedException al intentar eliminar credencial de otro paciente"
        );
    }

    @Test
    void recepcionistaPuedeEliminarCredencialDePaciente() {
        String recepcionistaAuth0Id = "auth0|recepcionista";

        UsuarioAuth pacienteUser = new UsuarioAuth();
        pacienteUser.setId("auth0|paciente");

        Paciente paciente = new Paciente();
        paciente.setId(1L);
        paciente.setUsuarioAuth(pacienteUser);

        Credencial credencial = new Credencial();
        credencial.setId(1L);
        credencial.setPaciente(paciente);

        when(recepcionistaService.esRecepcionistaConUsuarioId(recepcionistaAuth0Id))
                .thenReturn(true);

        when(pacienteService.obtenerPaciente(1L))
                .thenReturn(paciente);

        when(repoCredenciales.findById(1L))
                .thenReturn(Optional.of(credencial));

        service.eliminarCredencialRecepcionista(1L, 1L, recepcionistaAuth0Id);

        verify(repoCredenciales).deleteById(1L);
    }

    @Test
    void recepcionistaNoPuedeEliminarCredencialDeOtroPaciente() {
        String recepcionistaAuth0Id = "auth0|recepcionista";

        UsuarioAuth paciente1 = new UsuarioAuth();
        paciente1.setId("auth0|paciente1");

        Paciente paciente1Entity = new Paciente();
        paciente1Entity.setId(1L);
        paciente1Entity.setUsuarioAuth(paciente1);

        UsuarioAuth paciente2 = new UsuarioAuth();
        paciente2.setId("auth0|paciente2");

        Paciente paciente2Entity = new Paciente();
        paciente2Entity.setId(2L);
        paciente2Entity.setUsuarioAuth(paciente2);

        Credencial credencial = new Credencial();
        credencial.setId(1L);
        credencial.setPaciente(paciente2Entity);

        when(recepcionistaService.esRecepcionistaConUsuarioId(recepcionistaAuth0Id))
                .thenReturn(true);

        when(pacienteService.obtenerPaciente(1L))
                .thenReturn(paciente1Entity);

        when(repoCredenciales.findById(1L))
                .thenReturn(Optional.of(credencial));

        assertThrows(
               AccessDeniedException.class,
                () -> service.eliminarCredencialRecepcionista(1L, 1L, recepcionistaAuth0Id),
                "Debería lanzar AccessDeniedException al intentar eliminar credencial de paciente incorrecto"
        );
    }

    @Test
    void noEsRecepcionistaNoPuedeEliminarCredencial() {
        String recepcionistaAuth0Id = "auth0|recepcionista";

        when(recepcionistaService.esRecepcionistaConUsuarioId(recepcionistaAuth0Id))
                .thenReturn(false);

        assertThrows(
                AccessDeniedException.class,
                () -> service.eliminarCredencialRecepcionista(1L, 1L, recepcionistaAuth0Id),
                "Debería lanzar AccessDeniedException al intentar eliminar credencial sin ser recepcionista"
        );
    }

    @Test
    void eliminarCredencialCredencialNoEncontrada() {

        UsuarioAuth usuario = new UsuarioAuth();
        usuario.setId("auth0|paciente");

        Paciente paciente = new Paciente();
        paciente.setId(1L);
        paciente.setUsuarioAuth(usuario);

        when(pacienteService.obtenerPacienteConUsuarioAuthId("auth0|paciente"))
                .thenReturn(Optional.of(paciente));

        when(repoCredenciales.findById(999L))
                .thenReturn(Optional.empty());

        assertThrows(
                AccessDeniedException.class,
                () -> service.eliminarCredencial(999L, "auth0|paciente"),
                "Debería lanzar AccessDeniedException al intentar eliminar credencial que no existe"
        );
    }

    @Test
    void eliminarCredencialRecepcionistaCredencialNoEncontrada() {

        String recepcionistaAuth0Id = "auth0|recepcionista";

        UsuarioAuth paciente = new UsuarioAuth();
        paciente.setId("auth0|paciente");

        Paciente pacienteEntity = new Paciente();
        pacienteEntity.setId(1L);
        pacienteEntity.setUsuarioAuth(paciente);

        when(recepcionistaService.esRecepcionistaConUsuarioId(recepcionistaAuth0Id))
                .thenReturn(true);

        when(pacienteService.obtenerPaciente(1L))
                .thenReturn(pacienteEntity);

        when(repoCredenciales.findById(999L))
                .thenReturn(Optional.empty());

        assertThrows(
                AccessDeniedException.class,
                () -> service.eliminarCredencialRecepcionista(999L, 1L, recepcionistaAuth0Id),
                "Debería lanzar AccessDeniedException al intentar eliminar credencial inexistente como recepcionista"
        );
    }

    @Test
    void pacientePuedeEditarSuCredencial() {

        UsuarioAuth usuario = new UsuarioAuth();
        String auth0IdPaciente = "auth0|paciente";
        usuario.setId(auth0IdPaciente);

        Paciente paciente = new Paciente();
        paciente.setId(1L);
        paciente.setUsuarioAuth(usuario);

        Credencial credencial = new Credencial();
        credencial.setId(1L);
        credencial.setPaciente(paciente);

        ObraSocial obraSocial = new ObraSocial();
        obraSocial.setId(2L);
        String nombreObraSocial = "OSDE";
        String numeroAfiliado = "654321" ;
        String plan = "321";
        obraSocial.setNombre(nombreObraSocial);

        CredencialRequest request = new CredencialRequest();
        request.setNombreObraSocial(nombreObraSocial);
        request.setNumeroAfiliado(numeroAfiliado);
        request.setPlan(plan);
        request.setFechaVencimiento(LocalDate.now().plusYears(2));

        when(pacienteService.obtenerPacienteConUsuarioAuthId(auth0IdPaciente))
                .thenReturn(Optional.of(paciente));

        when(repoCredenciales.findById(1L))
                .thenReturn(Optional.of(credencial));

        when(repoObraSociales.findByNombreEqualsIgnoreCaseAndVirgenteTrue(nombreObraSocial))
                .thenReturn(Optional.of(obraSocial));

        service.editarCredencialPaciente(1L, auth0IdPaciente, request);

        assertEquals(nombreObraSocial, credencial.getObraSocial().getNombre());
        assertEquals(numeroAfiliado, credencial.getNumeroAfiliado());
        assertEquals(plan, credencial.getPlan());
        assertEquals(LocalDate.now().plusYears(2), credencial.getFechaVencimiento());
        verify(repoCredenciales).save(credencial);
    }

    @Test
    void pacienteNoPuedeEditarCredencialDeOtroPaciente() {

        UsuarioAuth usuario = new UsuarioAuth();
        usuario.setId("auth0|paciente1");

        Paciente paciente1 = new Paciente();
        paciente1.setId(1L);
        paciente1.setUsuarioAuth(usuario);

        UsuarioAuth usuario2 = new UsuarioAuth();
        usuario2.setId("auth0|paciente2");

        Paciente paciente2 = new Paciente();
        paciente2.setId(2L);
        paciente2.setUsuarioAuth(usuario2);

        Credencial credencial = new Credencial();
        credencial.setId(1L);
        credencial.setPaciente(paciente2);

        CredencialRequest request = new CredencialRequest();
        request.setNombreObraSocial("OSDE NUEVO");

        when(pacienteService.obtenerPacienteConUsuarioAuthId("auth0|paciente1"))
                .thenReturn(Optional.of(paciente1));

        when(repoCredenciales.findById(1L))
                .thenReturn(Optional.of(credencial));

        assertThrows(
                AccessDeniedException.class,
                () -> service.editarCredencialPaciente(1L, "auth0|paciente1", request),
                "Debería lanzar AccessDeniedException al intentar editar credencial de otro paciente"
        );
    }

    @Test
    void recepcionistaPuedeEditarCredencialDePaciente() {

        String recepcionistaAuth0Id = "auth0|recepcionista";

        UsuarioAuth pacienteUser = new UsuarioAuth();
        pacienteUser.setId("auth0|paciente");

        Paciente paciente = new Paciente();
        paciente.setId(1L);
        paciente.setUsuarioAuth(pacienteUser);

        Credencial credencial = new Credencial();
        credencial.setId(1L);
        credencial.setPaciente(paciente);

        ObraSocial obraSocial = new ObraSocial();
        obraSocial.setId(2L);
        String nombreObraSocial = "OSDE EDITADO";
        String numeroAfiliado = "999888" ;
        String plan = "777";
        obraSocial.setNombre(nombreObraSocial);

        CredencialRequest request = new CredencialRequest();
        request.setNombreObraSocial(nombreObraSocial);
        request.setNumeroAfiliado(numeroAfiliado);
        request.setPlan(plan);
        request.setFechaVencimiento(LocalDate.now().plusYears(1));

        when(recepcionistaService.esRecepcionistaConUsuarioId(recepcionistaAuth0Id))
                .thenReturn(true);

        when(pacienteService.obtenerPaciente(1L))
                .thenReturn(paciente);

        when(repoCredenciales.findById(1L))
                .thenReturn(Optional.of(credencial));

        when(repoObraSociales.findByNombreEqualsIgnoreCaseAndVirgenteTrue(nombreObraSocial))
                .thenReturn(Optional.of(obraSocial));

        service.editarCredencialRecepcionista(1L, 1L, recepcionistaAuth0Id, request);

        assertEquals(nombreObraSocial, credencial.getObraSocial().getNombre());
        assertEquals(numeroAfiliado, credencial.getNumeroAfiliado());
        assertEquals(plan, credencial.getPlan());
        verify(repoCredenciales).save(credencial);
    }

    @Test
    void recepcionistaNoPuedeEditarCredencialDeOtroPaciente() {

        String recepcionistaAuth0Id = "auth0|recepcionista";

        UsuarioAuth paciente1 = new UsuarioAuth();
        paciente1.setId("auth0|paciente1");

        Paciente paciente1Entity = new Paciente();
        paciente1Entity.setId(1L);
        paciente1Entity.setUsuarioAuth(paciente1);

        UsuarioAuth paciente2 = new UsuarioAuth();
        paciente2.setId("auth0|paciente2");

        Paciente paciente2Entity = new Paciente();
        paciente2Entity.setId(2L);
        paciente2Entity.setUsuarioAuth(paciente2);

        Credencial credencial = new Credencial();
        credencial.setId(1L);
        credencial.setPaciente(paciente2Entity);

        CredencialRequest request = new CredencialRequest();
        request.setNombreObraSocial("OSDE NUEVO");

        when(recepcionistaService.esRecepcionistaConUsuarioId(recepcionistaAuth0Id))
                .thenReturn(true);

        when(pacienteService.obtenerPaciente(1L))
                .thenReturn(paciente1Entity);

        when(repoCredenciales.findById(1L))
                .thenReturn(Optional.of(credencial));

        assertThrows(
                AccessDeniedException.class,
                () -> service.editarCredencialRecepcionista(1L, 1L, recepcionistaAuth0Id, request),
                "Debería lanzar AccessDeniedException al intentar editar credencial de paciente incorrecto"
        );
    }

    @Test
    void noEsRecepcionistaNoPuedeEditarCredencial() {

        String recepcionistaAuth0Id = "auth0|recepcionista";

        when(recepcionistaService.esRecepcionistaConUsuarioId(recepcionistaAuth0Id))
                .thenReturn(false);

        assertThrows(
                AccessDeniedException.class,
                () -> service.editarCredencialRecepcionista(1L, 1L, recepcionistaAuth0Id, new CredencialRequest()),
                "Debería lanzar AccessDeniedException al intentar editar credencial sin ser recepcionista"
        );
    }

    @Test
    void editarCredencialCredencialNoEncontrada() {

        UsuarioAuth usuario = new UsuarioAuth();
        usuario.setId("auth0|paciente");

        Paciente paciente = new Paciente();
        paciente.setId(1L);
        paciente.setUsuarioAuth(usuario);

        when(pacienteService.obtenerPacienteConUsuarioAuthId("auth0|paciente"))
                .thenReturn(Optional.of(paciente));

        when(repoCredenciales.findById(999L))
                .thenReturn(Optional.empty());

        assertThrows(
                AccessDeniedException.class,
                () -> service.editarCredencialPaciente(999L, "auth0|paciente", new CredencialRequest()),
                "Debería lanzar AccessDeniedException al intentar editar credencial que no existe"
        );
    }

    @Test
    void editarCredencialRecepcionistaCredencialNoEncontrada() {

        String recepcionistaAuth0Id = "auth0|recepcionista";

        UsuarioAuth paciente = new UsuarioAuth();
        paciente.setId("auth0|paciente");

        Paciente pacienteEntity = new Paciente();
        pacienteEntity.setId(1L);
        pacienteEntity.setUsuarioAuth(paciente);

        when(recepcionistaService.esRecepcionistaConUsuarioId(recepcionistaAuth0Id))
                .thenReturn(true);

        when(pacienteService.obtenerPaciente(1L))
                .thenReturn(pacienteEntity);

        when(repoCredenciales.findById(999L))
                .thenReturn(Optional.empty());

        assertThrows(
                AccessDeniedException.class,
                () -> service.editarCredencialRecepcionista(999L, 1L, recepcionistaAuth0Id, new CredencialRequest()),
                "Debería lanzar AccessDeniedException al intentar editar credencial inexistente como recepcionista"
        );
    }

    @Test
    void rechazaCredencialInvalidaAlCargarPaciente() {

        UsuarioAuth usuario = new UsuarioAuth();
        usuario.setId("auth0|paciente");

        Paciente paciente = new Paciente();
        paciente.setUsuarioAuth(usuario);
        paciente.setId(1L);

        ObraSocial obraSocial = new ObraSocial();
        obraSocial.setId(2L);
        obraSocial.setNombre("OSDE");

        CredencialRequest request = new CredencialRequest();
        request.setNombreObraSocial("OSDE");
        request.setNumeroAfiliado("123456");
        request.setPlan("210");
        request.setFechaVencimiento(LocalDate.now().plusYears(1));

        when(pacienteService.obtenerPacienteConUsuarioAuthId("auth0|paciente"))
                .thenReturn(Optional.of(paciente));

        when(repoObraSociales.findByNombreEqualsIgnoreCaseAndVirgenteTrue("OSDE"))
                .thenReturn(Optional.of(obraSocial));

        doThrow(new CredencialInvalidaException())
                .when(validacionCredencialObraSocialService)
                .validarCredencialObraSocial(any(), any(), any());

        assertThrows(CredencialInvalidaException.class,
                () -> service.cargarCredencialPaciente("auth0|paciente", request));

        verify(repoCredenciales, never()).save(any(Credencial.class));
    }

    @Test
    void rechazaCredencialInvalidaAlCargarRecepcionista() {

        String recepcionistaAuth0Id = "auth0|recepcionista";

        Paciente paciente = new Paciente();
        paciente.setId(1L);

        ObraSocial obraSocial = new ObraSocial();
        obraSocial.setId(2L);
        obraSocial.setNombre("OSDE");

        CredencialRequest request = new CredencialRequest();
        request.setNombreObraSocial("OSDE");
        request.setNumeroAfiliado("123456");
        request.setPlan("210");
        request.setFechaVencimiento(LocalDate.now().plusYears(1));

        when(recepcionistaService.esRecepcionistaConUsuarioId(recepcionistaAuth0Id))
                .thenReturn(true);

        when(pacienteService.obtenerPaciente(1L))
                .thenReturn(paciente);

        when(repoObraSociales.findByNombreEqualsIgnoreCaseAndVirgenteTrue("OSDE"))
                .thenReturn(Optional.of(obraSocial));

        doThrow(new CredencialInvalidaException())
                .when(validacionCredencialObraSocialService)
                .validarCredencialObraSocial(any(), any(), any());

        assertThrows(CredencialInvalidaException.class,
                () -> service.cargarCredencialRecepcionista(recepcionistaAuth0Id, 1L, request));

        verify(repoCredenciales, never()).save(any(Credencial.class));
    }

    @Test
    void rechazaCredencialInvalidaAlEditarPaciente() {

        UsuarioAuth usuario = new UsuarioAuth();
        usuario.setId("auth0|paciente");

        Paciente paciente = new Paciente();
        paciente.setUsuarioAuth(usuario);
        paciente.setId(1L);

        Credencial credencial = new Credencial();
        credencial.setId(1L);
        credencial.setPaciente(paciente);

        CredencialRequest request = new CredencialRequest();
        request.setNombreObraSocial("OSDE");
        request.setNumeroAfiliado("654321");
        request.setPlan("321");
        request.setFechaVencimiento(LocalDate.now().plusYears(2));

        when(pacienteService.obtenerPacienteConUsuarioAuthId("auth0|paciente"))
                .thenReturn(Optional.of(paciente));

        when(repoCredenciales.findById(1L))
                .thenReturn(Optional.of(credencial));

        doThrow(new CredencialInvalidaException())
                .when(validacionCredencialObraSocialService)
                .validarCredencialObraSocial(any(), any(), any());

        assertThrows(CredencialInvalidaException.class,
                () -> service.editarCredencialPaciente(1L, "auth0|paciente", request));

        verify(repoCredenciales, never()).save(any(Credencial.class));
    }

    @Test
    void rechazaCredencialInvalidaAlEditarRecepcionista() {

        String recepcionistaAuth0Id = "auth0|recepcionista";

        Paciente paciente = new Paciente();
        paciente.setId(1L);

        Credencial credencial = new Credencial();
        credencial.setId(1L);
        credencial.setPaciente(paciente);

        CredencialRequest request = new CredencialRequest();
        request.setNombreObraSocial("OSDE");
        request.setNumeroAfiliado("999888");
        request.setPlan("777");
        request.setFechaVencimiento(LocalDate.now().plusYears(1));

        when(recepcionistaService.esRecepcionistaConUsuarioId(recepcionistaAuth0Id))
                .thenReturn(true);

        when(pacienteService.obtenerPaciente(1L))
                .thenReturn(paciente);

        when(repoCredenciales.findById(1L))
                .thenReturn(Optional.of(credencial));

        doThrow(new CredencialInvalidaException())
                .when(validacionCredencialObraSocialService)
                .validarCredencialObraSocial(any(), any(), any());

        assertThrows(CredencialInvalidaException.class,
                () -> service.editarCredencialRecepcionista(1L, 1L, recepcionistaAuth0Id, request));

        verify(repoCredenciales, never()).save(any(Credencial.class));

        verify(repoCredenciales, never()).save(any(Credencial.class));
    }

    @Test
    void recepcionistaAdminPuedeCargarNuevasObrasSociales() {

        String recepcionistaAuth0Id = "auth0|recepcionista";

        ObraSocialDTO requestDto = new ObraSocialDTO();
        String nombreObraSocial = "OSDE";
        requestDto.setNombre(nombreObraSocial);

        UsuarioAuth usuarioAuthRecepcionista = new UsuarioAuth();
        usuarioAuthRecepcionista.setRol(RolSistema.ADMIN);
        usuarioAuthRecepcionista.setId(recepcionistaAuth0Id);

        when(recepcionistaService.obtenerUsuarioAuth(recepcionistaAuth0Id))
                .thenReturn(usuarioAuthRecepcionista);

        when(repoObraSociales.findByNombreEqualsIgnoreCaseAndVirgenteTrue(nombreObraSocial))
                .thenReturn(Optional.empty());

        this.service.cargarObraSocialAdmin(recepcionistaAuth0Id, requestDto);

        verify(repoObraSociales).save(any(ObraSocial.class));
    }

    @Test
    void noEsrecepcionistaAdminNoPuedeCargarNuevasObrasSociales() {

        String recepcionistaAuth0Id = "auth0|recepcionista";

        ObraSocialDTO requestDto = new ObraSocialDTO();
        String nombreObraSocial = "OSDE";
        requestDto.setNombre(nombreObraSocial);

        UsuarioAuth usuarioAuthRecepcionista = new UsuarioAuth();
        usuarioAuthRecepcionista.setRol(RolSistema.USER);
        usuarioAuthRecepcionista.setId(recepcionistaAuth0Id);

        when(recepcionistaService.obtenerUsuarioAuth(recepcionistaAuth0Id))
                .thenReturn(usuarioAuthRecepcionista);

        assertThrows(AccessDeniedException.class,
                () -> this.service.cargarObraSocialAdmin(recepcionistaAuth0Id, requestDto));
    }

    @Test
    void recepcionistaAdminNoPuedeCargarNuevasObrasSocialesSiYaExiste() {

        String recepcionistaAuth0Id = "auth0|recepcionista";

        ObraSocialDTO requestDto = new ObraSocialDTO();
        String nombreObraSocial = "OSDE";
        requestDto.setNombre(nombreObraSocial);

        UsuarioAuth usuarioAuthRecepcionista = new UsuarioAuth();
        usuarioAuthRecepcionista.setRol(RolSistema.ADMIN);
        usuarioAuthRecepcionista.setId(recepcionistaAuth0Id);

        when(recepcionistaService.obtenerUsuarioAuth(recepcionistaAuth0Id))
                .thenReturn(usuarioAuthRecepcionista);

        ObraSocial obraSocial = new ObraSocial();
        obraSocial.setNombre(nombreObraSocial);

        when(repoObraSociales.findByNombreEqualsIgnoreCaseAndVirgenteTrue(nombreObraSocial))
                .thenReturn(Optional.of(obraSocial));

        assertThrows(ObraSocialYaExisteException.class,
                () -> this.service.cargarObraSocialAdmin(recepcionistaAuth0Id, requestDto));
    }


    @Test
    void recepcionistaAdminPuedeEliminarObrasSociales() {

        String recepcionistaAuth0Id = "auth0|recepcionista";

        String nombreObraSocial = "OSDE";

        ObraSocial obraSocial = new ObraSocial();
        obraSocial.setNombre(nombreObraSocial);

        UsuarioAuth usuarioAuthRecepcionista = new UsuarioAuth();
        usuarioAuthRecepcionista.setRol(RolSistema.ADMIN);
        usuarioAuthRecepcionista.setId(recepcionistaAuth0Id);

        when(recepcionistaService.obtenerUsuarioAuth(recepcionistaAuth0Id))
                .thenReturn(usuarioAuthRecepcionista);

        when(repoObraSociales.findById(1L))
                .thenReturn(Optional.of(obraSocial));

        this.service.eliminarObraSocial(recepcionistaAuth0Id, 1L);

        verify(repoObraSociales).save(any(ObraSocial.class)); //borrado lógico
        assertFalse(obraSocial.isVirgente());
    }


    @Test
    void recepcionistaAdminNoPuedeEliminarObrasSocialesSiNoExisten() {

        String recepcionistaAuth0Id = "auth0|recepcionista";


        UsuarioAuth usuarioAuthRecepcionista = new UsuarioAuth();
        usuarioAuthRecepcionista.setRol(RolSistema.ADMIN);
        usuarioAuthRecepcionista.setId(recepcionistaAuth0Id);

        when(recepcionistaService.obtenerUsuarioAuth(recepcionistaAuth0Id))
                .thenReturn(usuarioAuthRecepcionista);

        when(repoObraSociales.findById(2L))
                .thenReturn(Optional.empty());

        assertThrows(ObraSocialNoExisteException.class,
                ()-> this.service.eliminarObraSocial(recepcionistaAuth0Id, 2L));

    }

    @Test
    void noEsrecepcionistaAdminNoPuedeEliminarObrasSociales() {

        String recepcionistaAuth0Id = "auth0|recepcionista";

        UsuarioAuth usuarioAuthRecepcionista = new UsuarioAuth();
        usuarioAuthRecepcionista.setRol(RolSistema.USER);
        usuarioAuthRecepcionista.setId(recepcionistaAuth0Id);

        when(recepcionistaService.obtenerUsuarioAuth(recepcionistaAuth0Id))
                .thenReturn(usuarioAuthRecepcionista);


        assertThrows(AccessDeniedException.class,
                ()-> this.service.eliminarObraSocial(recepcionistaAuth0Id, 1L));

    }

}