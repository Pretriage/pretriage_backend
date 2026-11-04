package com.pretriage.backend.services;

import com.pretriage.backend.controllers.dtos.EstudioClinicoDTO;
import com.pretriage.backend.exceptions.PacienteNoExisteException;
import com.pretriage.backend.model.consultas.EstudioClinico;
import com.pretriage.backend.model.personas.Paciente;
import com.pretriage.backend.repositories.RepoEstudiosClinicos;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.NoSuchElementException;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
public class EstudioClinicoServiceTest {

    @Mock
    private GestionDeArchivosService gestionDeArchivosService;

    @Mock
    private RepoEstudiosClinicos repoEstudiosClinicos;

    @Mock
    private PacienteService pacienteService;

    @InjectMocks
    private EstudioClinicoService estudioClinicoService;

    private static final String AUTH0_ID = "auth0|123";
    private static final String FILE_NAME = "test.pdf";
    private static final String FILE_CONTENT = "Test file content";
    private Paciente paciente;
    private EstudioClinicoDTO estudioClinicoDTO;
    private MultipartFile multipartFile;

    @BeforeEach
    void setUp() {
        paciente = new Paciente();
        paciente.setId(1L);

        estudioClinicoDTO = new EstudioClinicoDTO();
        estudioClinicoDTO.setTipoArchivo("PDF");
        estudioClinicoDTO.setDescripcion("Test description");

        multipartFile = new MockMultipartFile(
                "file",
                FILE_NAME,
                "application/pdf",
                FILE_CONTENT.getBytes());
    }

    @Test
    void subirArchivoEstudioClinico_Success() {
        when(pacienteService.obtenerPacienteConUsuarioAuthId(AUTH0_ID)).thenReturn(Optional.of(paciente));

        estudioClinicoService.subirArchivoEstudioClinico(AUTH0_ID, multipartFile, estudioClinicoDTO);

        // la key en S3 ahora es un UUID, no el nombre original -> solo validamos que
        // termine en la extensión correcta
        verify(gestionDeArchivosService).subirArchivo(any(MultipartFile.class), argThat(key -> key.endsWith(".pdf")));

        // capturamos la entidad guardada para verificar nombreArchivo vs rutaArchivo
        // por separado
        ArgumentCaptor<EstudioClinico> captor = ArgumentCaptor.forClass(EstudioClinico.class);
        verify(repoEstudiosClinicos).save(captor.capture());

        EstudioClinico guardado = captor.getValue();
        assertEquals(FILE_NAME, guardado.getNombreArchivo()); // el nombre original se conserva para mostrar
        assertNotEquals(FILE_NAME, guardado.getRutaArchivo()); // la key real ya no es el nombre original
        assertTrue(guardado.getRutaArchivo().endsWith(".pdf")); // pero mantiene la extensión
    }

    @Test
    void subirArchivoEstudioClinico_FileNull_ThrowsIllegalArgumentException() {
        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> estudioClinicoService.subirArchivoEstudioClinico(AUTH0_ID, null, estudioClinicoDTO));

        assertEquals("El archivo no puede ser nulo", exception.getMessage());
        verify(pacienteService, never()).obtenerPacienteConUsuarioAuthId(any());
        verify(gestionDeArchivosService, never()).subirArchivo(any(MultipartFile.class), eq(FILE_NAME));// se guarda en
                                                                                                        // s3
        verify(repoEstudiosClinicos, never()).save(any(EstudioClinico.class));
    }

    @Test
    void subirArchivoEstudioClinico_PacienteNoExiste_ThrowsPacienteNoExisteException() {
        when(pacienteService.obtenerPacienteConUsuarioAuthId(AUTH0_ID)).thenReturn(Optional.empty());

        assertThrows(
                PacienteNoExisteException.class,
                () -> estudioClinicoService.subirArchivoEstudioClinico(AUTH0_ID, multipartFile, estudioClinicoDTO));

        verify(gestionDeArchivosService, never()).subirArchivo(any(MultipartFile.class), eq(FILE_NAME));// se guarda en
                                                                                                        // s3
        verify(repoEstudiosClinicos, never()).save(any(EstudioClinico.class));
    }

    @Test
    void eliminarArchivoEstudioClinico_Success() {
        Long idEstudio = 1L;
        EstudioClinico estudioClinicoMock = mock(EstudioClinico.class);

        when(pacienteService.obtenerPacienteConUsuarioAuthId(AUTH0_ID)).thenReturn(Optional.of(paciente));
        when(repoEstudiosClinicos.findById(idEstudio)).thenReturn(Optional.of(estudioClinicoMock));
        when(estudioClinicoMock.getRutaArchivo()).thenReturn("/" + FILE_NAME);

        estudioClinicoService.eliminarArchivoEstudioClinico(AUTH0_ID, idEstudio);

        verify(gestionDeArchivosService).eliminarArchivo(any(String.class));
        verify(estudioClinicoMock).setActivo(false);
        verify(repoEstudiosClinicos).save(estudioClinicoMock);
    }

    @Test
    void eliminarArchivoEstudioClinico_PacienteNoExiste_ThrowsPacienteNoExisteException() {
        Long idEstudio = 1L;
        EstudioClinico estudioClinicoMock = mock(EstudioClinico.class);

        when(pacienteService.obtenerPacienteConUsuarioAuthId(AUTH0_ID)).thenReturn(Optional.empty());

        assertThrows(PacienteNoExisteException.class,
                () -> estudioClinicoService.eliminarArchivoEstudioClinico(AUTH0_ID, idEstudio));

        verify(gestionDeArchivosService, never()).eliminarArchivo(any(String.class));
        verify(estudioClinicoMock, never()).setActivo(false);
        verify(repoEstudiosClinicos, never()).save(estudioClinicoMock);
    }

    @Test
    void eliminarArchivoEstudioClinico_EstudioNoExiste_ThrowsNoSuchElementException() {
        Long idEstudio = 1L;
        EstudioClinico estudioClinicoMock = mock(EstudioClinico.class);

        when(pacienteService.obtenerPacienteConUsuarioAuthId(AUTH0_ID)).thenReturn(Optional.of(paciente));
        when(repoEstudiosClinicos.findById(idEstudio)).thenReturn(Optional.empty());

        assertThrows(NoSuchElementException.class,
                () -> estudioClinicoService.eliminarArchivoEstudioClinico(AUTH0_ID, idEstudio));

        verify(gestionDeArchivosService, never()).eliminarArchivo(any(String.class));
        verify(estudioClinicoMock, never()).setActivo(false);
        verify(repoEstudiosClinicos, never()).save(estudioClinicoMock);
    }

    @Test
    void eliminarArchivoEstudioClinico_NoTieneRuta_ThrowsIllegalArgumentException() {
        Long idEstudio = 1L;
        EstudioClinico estudioClinicoMock = mock(EstudioClinico.class);

        when(pacienteService.obtenerPacienteConUsuarioAuthId(AUTH0_ID)).thenReturn(Optional.of(paciente));
        when(repoEstudiosClinicos.findById(idEstudio)).thenReturn(Optional.of(estudioClinicoMock));

        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class,
                () -> estudioClinicoService.eliminarArchivoEstudioClinico(AUTH0_ID, idEstudio));

        assertEquals("No se encontra cargada la ruta del archivo", exception.getMessage());

        verify(gestionDeArchivosService, never()).eliminarArchivo(any(String.class));
        verify(estudioClinicoMock, never()).setActivo(false);
        verify(repoEstudiosClinicos, never()).save(estudioClinicoMock);
    }

    @Test
    void obtenerTodosEstudiosClinicos_Success() {
        EstudioClinico estudio1 = new EstudioClinico();
        EstudioClinico estudio2 = new EstudioClinico();

        estudio1.setNombreArchivo(FILE_NAME);
        estudio2.setNombreArchivo('2' + FILE_NAME);

        estudio1.setPaciente(paciente);
        estudio2.setPaciente(paciente);

        estudio1.setActivo(true);
        estudio2.setActivo(true);

        when(pacienteService.obtenerPacienteConUsuarioAuthId(AUTH0_ID)).thenReturn(Optional.of(paciente));
        when(repoEstudiosClinicos.findAllByPacienteAndActivoTrue(paciente))
                .thenReturn(List.of(estudio1, estudio2));

        List<EstudioClinicoDTO> estudiosResult = estudioClinicoService.obtenerTodosEstudiosClinicos(AUTH0_ID);

        assertEquals(2, estudiosResult.size());
        assertEquals(estudio1.getNombreArchivo(), estudiosResult.get(0).getNombreArchivo());
        assertEquals(estudio2.getNombreArchivo(), estudiosResult.get(1).getNombreArchivo());
    }

}
