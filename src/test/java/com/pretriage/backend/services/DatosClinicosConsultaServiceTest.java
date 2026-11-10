package com.pretriage.backend.services;

import com.pretriage.backend.controllers.DatosClinicosConsultaController;
import com.pretriage.backend.controllers.dtos.DatosClinicosConsultaDTO;
import com.pretriage.backend.exceptions.ArchivoS3Exception;
import com.pretriage.backend.exceptions.GlobalExceptionHandler;
import com.pretriage.backend.model.consultas.ConsultaMedica;
import com.pretriage.backend.model.consultas.EstudioClinico;
import com.pretriage.backend.model.personas.Genero;
import com.pretriage.backend.model.personas.OrigenRegistroPaciente;
import com.pretriage.backend.model.personas.Paciente;
import com.pretriage.backend.repositories.RepoEstudiosClinicos;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.security.web.method.annotation.AuthenticationPrincipalArgumentResolver;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.LocalDate;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
class DatosClinicosConsultaServiceTest {
    private static final String AUTH_ID = "medico";
    private static final long SESION_ID = 10L;
    private static final long CONSULTA_ID = 20L;
    private static final long ESTUDIO_ID = 30L;

    @Mock
    AtencionMedicoService atencionMedicoService;
    @Mock
    RepoEstudiosClinicos repoEstudiosClinicos;
    @Mock
    GestionDeArchivosService gestionDeArchivosService;

    private final Paciente paciente = new Paciente();
    private DatosClinicosConsultaService service;

    @BeforeEach
    void configurar() {
        paciente.setId(40L);
        EstudioClinicoService estudioService = new EstudioClinicoService(
                repoEstudiosClinicos, null, gestionDeArchivosService);
        service = new DatosClinicosConsultaService(atencionMedicoService, repoEstudiosClinicos, estudioService);
    }

    @Test
    void incluyePerfilYMetadatosSinSolicitarElArchivo() {
        autorizarConsulta();
        paciente.setFechaNacimiento(LocalDate.of(1990, 2, 1));
        paciente.setPeso(67.5);
        paciente.setAltura(174);
        EstudioClinico estudio = estudio("pdf");
        when(repoEstudiosClinicos.findAllByPacienteAndActivoTrue(paciente)).thenReturn(List.of(estudio));

        DatosClinicosConsultaDTO datos = service.obtenerDatos(AUTH_ID, SESION_ID, CONSULTA_ID);

        assertEquals(paciente.getFechaNacimiento(), datos.fechaNacimiento());
        assertEquals(67.5, datos.peso());
        assertEquals(174, datos.altura());
        assertEquals(ESTUDIO_ID, datos.estudios().getFirst().id());
        assertEquals("informe.pdf", datos.estudios().getFirst().nombreArchivo());
        verifyNoInteractions(gestionDeArchivosService);
    }

    @Test
    void devuelveDatosDeRecepcionSinHistoriaClinicaNiAlmacenamientoDeArchivos() {
        autorizarConsulta();
        paciente.setOrigenRegistro(OrigenRegistroPaciente.RECEPCION);
        paciente.setFechaNacimiento(LocalDate.of(1990, 2, 1));
        paciente.setGeneroBiologico(Genero.FEMENINO);
        when(repoEstudiosClinicos.findAllByPacienteAndActivoTrue(paciente)).thenReturn(List.of());
        DatosClinicosConsultaController controller = new DatosClinicosConsultaController(service);
        Jwt jwt = Jwt.withTokenValue("test").header("alg", "none").subject(AUTH_ID).build();

        ResponseEntity<DatosClinicosConsultaDTO> respuesta =
                controller.obtenerDatosClinicos(jwt, SESION_ID, CONSULTA_ID);
        DatosClinicosConsultaDTO datos = respuesta.getBody();

        assertEquals(HttpStatus.OK, respuesta.getStatusCode());
        assertEquals(paciente.getFechaNacimiento(), datos.fechaNacimiento());
        assertEquals(Genero.FEMENINO, datos.generoBiologico());
        assertNull(datos.generoConElQueSeIdentifica());
        assertNull(datos.peso());
        assertNull(datos.altura());
        assertEquals(List.of(), datos.estudios());
        verifyNoInteractions(gestionDeArchivosService);
    }

    @Test
    void rechazaDatosYArchivosSiLaConsultaNoEstaAsignada() {
        when(atencionMedicoService.obtenerConsultaParaRevision(AUTH_ID, SESION_ID, CONSULTA_ID))
                .thenThrow(new AccessDeniedException("Consulta de otro medico"));

        assertThrows(AccessDeniedException.class, () -> service.obtenerDatos(AUTH_ID, SESION_ID, CONSULTA_ID));
        assertThrows(AccessDeniedException.class,
                () -> service.obtenerArchivo(AUTH_ID, SESION_ID, CONSULTA_ID, ESTUDIO_ID));
        verifyNoInteractions(repoEstudiosClinicos, gestionDeArchivosService);
    }

    @Test
    void rechazaArchivosInactivosODeOtroPaciente() {
        autorizarConsulta();
        when(repoEstudiosClinicos.findByIdAndPacienteAndActivoTrue(ESTUDIO_ID, paciente)).thenReturn(Optional.empty());

        assertThrows(NoSuchElementException.class,
                () -> service.obtenerArchivo(AUTH_ID, SESION_ID, CONSULTA_ID, ESTUDIO_ID));
        verifyNoInteractions(gestionDeArchivosService);
    }

    @Test
    void informaAlmacenamientoNoDisponibleAlSolicitarUnAdjunto() throws Exception {
        autorizarConsulta();
        EstudioClinico estudio = estudio("pdf");
        when(repoEstudiosClinicos.findByIdAndPacienteAndActivoTrue(ESTUDIO_ID, paciente))
                .thenReturn(Optional.of(estudio));
        when(gestionDeArchivosService.descargarArchivoDesdeS3(estudio.getRutaArchivo()))
                .thenThrow(new ArchivoS3Exception("Almacenamiento deshabilitado"));
        Jwt jwt = Jwt.withTokenValue("test").header("alg", "none").subject(AUTH_ID).build();
        SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(jwt));
        try {
            MockMvcBuilders.standaloneSetup(new DatosClinicosConsultaController(service))
                    .setCustomArgumentResolvers(new AuthenticationPrincipalArgumentResolver())
                    .setControllerAdvice(new GlobalExceptionHandler())
                    .build()
                    .perform(get("/api/medico/sesiones/{sesionId}/consultas/{consultaId}/datos-clinicos/estudios/{estudioId}/archivo",
                            SESION_ID, CONSULTA_ID, ESTUDIO_ID))
                    .andExpect(status().isServiceUnavailable())
                    .andExpect(jsonPath("$.error").value("El archivo clínico no está disponible en este momento."));
        } finally {
            SecurityContextHolder.clearContext();
        }
    }

    @ParameterizedTest
    @CsvSource({"PDF, application/pdf, inline", "png, image/png, inline",
            "svg, application/octet-stream, attachment"})
    void devuelveElArchivoConUnTipoDeContenidoPermitido(String extension, String mime, String disposicion) {
        autorizarConsulta();
        EstudioClinico estudio = estudio(extension);
        byte[] contenido = {1, 2, 3};
        when(repoEstudiosClinicos.findByIdAndPacienteAndActivoTrue(ESTUDIO_ID, paciente))
                .thenReturn(Optional.of(estudio));
        when(gestionDeArchivosService.descargarArchivoDesdeS3(estudio.getRutaArchivo())).thenReturn(contenido);
        DatosClinicosConsultaController controller = new DatosClinicosConsultaController(service);
        Jwt jwt = Jwt.withTokenValue("test").header("alg", "none").subject(AUTH_ID).build();

        ResponseEntity<byte[]> respuesta = controller.obtenerArchivo(jwt, SESION_ID, CONSULTA_ID, ESTUDIO_ID);
        HttpHeaders headers = respuesta.getHeaders();

        assertEquals(mime, headers.getContentType().toString());
        assertEquals(disposicion, headers.getContentDisposition().getType());
        assertEquals(estudio.getNombreArchivo(), headers.getContentDisposition().getFilename());
        assertEquals("no-store", headers.getCacheControl());
        assertArrayEquals(contenido, respuesta.getBody());
    }

    private void autorizarConsulta() {
        ConsultaMedica consulta = new ConsultaMedica();
        consulta.setPaciente(paciente);
        when(atencionMedicoService.obtenerConsultaParaRevision(AUTH_ID, SESION_ID, CONSULTA_ID)).thenReturn(consulta);
    }

    private EstudioClinico estudio(String extension) {
        EstudioClinico estudio = new EstudioClinico();
        estudio.setId(ESTUDIO_ID);
        estudio.setNombreArchivo("informe." + extension);
        estudio.setExtensionArchivo(extension);
        estudio.setRutaArchivo("clave-interna");
        return estudio;
    }
}
