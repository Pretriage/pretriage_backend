package com.pretriage.backend.services;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.LocalDate;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.pretriage.backend.controllers.dtos.PerfilUsuarioDTO;
import com.pretriage.backend.exceptions.PacienteNoExisteException;
import com.pretriage.backend.model.hospitales.Direccion;
import com.pretriage.backend.model.personas.Genero;
import com.pretriage.backend.model.personas.Paciente;
import com.pretriage.backend.model.personas.TipoDocumento;

@ExtendWith(MockitoExtension.class)
class UsuarioPerfilServiceTest {

        @Mock
        private PacienteService pacienteService;

        @InjectMocks
        private UsuarioPerfilService service;

        private Paciente crearPacienteConDireccion() {
                Paciente paciente = new Paciente();
                paciente.setNombre("Juan");
                paciente.setApellido("Pérez");
                paciente.setTipoDocumento(TipoDocumento.DNI);
                paciente.setNumeroDocumento("30111222");
                paciente.setFechaNacimiento(LocalDate.of(1990, 5, 10));
                paciente.setGeneroBiologico(Genero.MASCULINO);
                paciente.setGeneroConElQueSeIdentifica(Genero.MASCULINO);
                paciente.setCorreoElectronico("juan@test.com");
                paciente.setTelefono("111222333");
                paciente.setPeso(75.5);
                paciente.setAltura(180);

                Direccion direccion = new Direccion();
                direccion.setCalle("Av. Siempre Viva");
                direccion.setAltura("742");
                direccion.setPiso("3");
                direccion.setCodigoPostal("5000");
                direccion.setCiudad("Córdoba");
                direccion.setProvincia("Córdoba");
                paciente.setDireccion(direccion);

                return paciente;
        }

        private PerfilUsuarioDTO crearPerfilRequest() {
                PerfilUsuarioDTO request = new PerfilUsuarioDTO();
                request.setNombre("Juan");
                request.setApellido("Pérez");
                request.setTipoDocumento(TipoDocumento.DNI);
                request.setNumeroDocumento("30111222");
                request.setFechaNacimiento(LocalDate.of(1990, 5, 10));
                request.setGeneroBiologico(Genero.MASCULINO);
                request.setGeneroConElQueSeIdentifica(Genero.MASCULINO);
                request.setEmail("juan@test.com");
                request.setTelefono("111222333");
                request.setCalle("Av. Siempre Viva");
                request.setAlturaDireccion("742");
                request.setPiso("3");
                request.setCodigoPostal("5000");
                request.setCiudad("Córdoba");
                request.setProvincia("Córdoba");
                request.setPeso(75.5);
                request.setAlturaPersona(180);
                return request;
        }

        @Test
        void obtenerPerfilCuandoElPacienteExisteConDireccion() {

                Paciente paciente = crearPacienteConDireccion();
                String auth0Id = "auth0|paciente";

                when(pacienteService.obtenerPacienteConUsuarioAuthId(auth0Id))
                                .thenReturn(Optional.of(paciente));

                PerfilUsuarioDTO resultado = service.obtenerPacientePerfil(auth0Id);

                assertNotNull(resultado);
                assertEquals("Juan", resultado.getNombre());
                assertEquals("Pérez", resultado.getApellido());
                assertEquals(TipoDocumento.DNI, resultado.getTipoDocumento());
                assertEquals("30111222", resultado.getNumeroDocumento());
                assertEquals(LocalDate.of(1990, 5, 10), resultado.getFechaNacimiento());
                assertEquals(Genero.MASCULINO, resultado.getGeneroBiologico());
                assertEquals(Genero.MASCULINO, resultado.getGeneroConElQueSeIdentifica());
                assertEquals("juan@test.com", resultado.getEmail());
                assertEquals("111222333", resultado.getTelefono());
                assertEquals("Av. Siempre Viva", resultado.getCalle());
                assertEquals("742", resultado.getAlturaDireccion());
                assertEquals("3", resultado.getPiso());
                assertEquals("5000", resultado.getCodigoPostal());
                assertEquals("Córdoba", resultado.getCiudad());
                assertEquals("Córdoba", resultado.getProvincia());
                assertEquals(75.5, resultado.getPeso());
                assertEquals(180, resultado.getAlturaPersona());
        }

        @Test
        void obtenerPerfilSiNoTieneDireccionDevuelveCamposDeDireccionNulos() {

                Paciente paciente = crearPacienteConDireccion();
                paciente.setDireccion(null);
                String auth0Id = "auth0|paciente";

                when(pacienteService.obtenerPacienteConUsuarioAuthId(auth0Id))
                                .thenReturn(Optional.of(paciente));

                PerfilUsuarioDTO resultado = service.obtenerPacientePerfil(auth0Id);

                assertNotNull(resultado);
                assertEquals("Juan", resultado.getNombre());
                assertNull(resultado.getCalle());
                assertNull(resultado.getAlturaDireccion());
                assertNull(resultado.getPiso());
                assertNull(resultado.getCodigoPostal());
                assertNull(resultado.getCiudad());
                assertNull(resultado.getProvincia());
        }

        @Test
        void obtenerPerfilCuandoElPacienteNoExisteLanzaPacienteNoExisteException() {

                String auth0Id = "auth0|inexistente";

                when(pacienteService.obtenerPacienteConUsuarioAuthId(auth0Id))
                                .thenReturn(Optional.empty());

                assertThrows(
                                PacienteNoExisteException.class,
                                () -> service.obtenerPacientePerfil(auth0Id));
        }

        @Test
        void actualizarPerfilActualizaLosDatosDelPacienteYSuDireccion() {

                Paciente paciente = crearPacienteConDireccion();
                String auth0Id = "auth0|paciente";
                PerfilUsuarioDTO request = crearPerfilRequest();

                when(pacienteService.obtenerPacienteConUsuarioAuthId(auth0Id))
                                .thenReturn(Optional.of(paciente));

                when(pacienteService.actualizarPaciente(paciente))
                                .thenReturn(paciente);

                PerfilUsuarioDTO resultado = service.actualizarPacientePerfil(auth0Id, request);

                assertEquals("Juan", resultado.getNombre());
                assertEquals("Pérez", resultado.getApellido());
                assertEquals(TipoDocumento.DNI, resultado.getTipoDocumento());
                assertEquals("30111222", resultado.getNumeroDocumento());
                assertEquals(LocalDate.of(1990, 5, 10), resultado.getFechaNacimiento());
                assertEquals(Genero.MASCULINO, resultado.getGeneroBiologico());
                assertEquals(Genero.MASCULINO, resultado.getGeneroConElQueSeIdentifica());
                assertEquals("juan@test.com", resultado.getEmail());
                assertEquals("111222333", resultado.getTelefono());
                assertEquals("Av. Siempre Viva", resultado.getCalle());
                assertEquals("742", resultado.getAlturaDireccion());
                assertEquals("3", resultado.getPiso());
                assertEquals("5000", resultado.getCodigoPostal());
                assertEquals("Córdoba", resultado.getCiudad());
                assertEquals("Córdoba", resultado.getProvincia());
                assertEquals(75.5, resultado.getPeso());
                assertEquals(180, resultado.getAlturaPersona());

                assertEquals("juan@test.com", paciente.getCorreoElectronico());
                assertEquals("Av. Siempre Viva", paciente.getDireccion().getCalle());
                verify(pacienteService).actualizarPaciente(paciente);
        }

        @Test
        void actualizarPerfilCreaDireccionNuevaCuandoElPacienteNoTieneDireccion() {

                Paciente paciente = crearPacienteConDireccion();
                paciente.setDireccion(null);
                String auth0Id = "auth0|paciente";
                PerfilUsuarioDTO request = crearPerfilRequest();

                when(pacienteService.obtenerPacienteConUsuarioAuthId(auth0Id))
                                .thenReturn(Optional.of(paciente));

                when(pacienteService.actualizarPaciente(paciente))
                                .thenReturn(paciente);

                PerfilUsuarioDTO resultado = service.actualizarPacientePerfil(auth0Id, request);

                assertNotNull(paciente.getDireccion());
                assertEquals("Av. Siempre Viva", paciente.getDireccion().getCalle());
                assertEquals("742", paciente.getDireccion().getAltura());
                assertEquals("Córdoba", resultado.getCiudad());
                verify(pacienteService).actualizarPaciente(paciente);
        }

        @Test
        void actualizarPerfilCuandoElPacienteNoExisteLanzaPacienteNoExisteException() {

                String auth0Id = "auth0|inexistente";
                PerfilUsuarioDTO request = crearPerfilRequest();

                when(pacienteService.obtenerPacienteConUsuarioAuthId(auth0Id))
                                .thenReturn(Optional.empty());

                assertThrows(
                                PacienteNoExisteException.class,
                                () -> service.actualizarPacientePerfil(auth0Id, request));
        }
}