package com.pretriage.backend.services;

import com.pretriage.backend.exceptions.ArchivoS3Exception;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.web.multipart.MultipartFile;
import software.amazon.awssdk.services.s3.S3Client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class GestionDeArchivosServiceTest {
    @Test
    void informaQueS3EstaDeshabilitadoAlDescargarCuandoNoHayClienteConfigurado() {
        GestionDeArchivosService service = crearServicioSinS3();

        verificarS3Deshabilitado(() -> service.descargarArchivoDesdeS3("estudios/resultado.pdf"));
    }

    @Test
    void informaQueS3EstaDeshabilitadoAlSubirCuandoNoHayClienteConfigurado() {
        GestionDeArchivosService service = crearServicioSinS3();
        MultipartFile archivo = mock(MultipartFile.class);

        verificarS3Deshabilitado(() -> service.subirArchivo(archivo, "estudios/resultado.pdf"));
    }

    @Test
    void informaQueS3EstaDeshabilitadoAlEliminarCuandoNoHayClienteConfigurado() {
        GestionDeArchivosService service = crearServicioSinS3();

        verificarS3Deshabilitado(() -> service.eliminarArchivo("estudios/resultado.pdf"));
    }

    private GestionDeArchivosService crearServicioSinS3() {
        @SuppressWarnings("unchecked")
        ObjectProvider<S3Client> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(null);
        return new GestionDeArchivosService(provider);
    }

    private void verificarS3Deshabilitado(org.junit.jupiter.api.function.Executable operacion) {
        ArchivoS3Exception error = assertThrows(
                ArchivoS3Exception.class,
                operacion);

        assertEquals(
                "El almacenamiento de estudios clínicos en S3 está deshabilitado en este entorno",
                error.getMessage());
    }
}
