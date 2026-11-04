package com.pretriage.backend.services;

import com.pretriage.backend.exceptions.ArchivoS3Exception;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import software.amazon.awssdk.core.ResponseInputStream;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.*;
import lombok.extern.slf4j.Slf4j;
import java.io.IOException;

@Slf4j
@Service
@RequiredArgsConstructor
public class GestionDeArchivosService {

    private final ObjectProvider<S3Client> s3ClientProvider;

    @Value("${aws.s3.bucket-name}")
    private String bucketName;

    public byte[] descargarArchivoDesdeS3(String rutaArchivo) {
        S3Client s3Client = obtenerClienteS3();

        log.info("Intentando descargar de S3 -> bucket: '{}', key: '{}'", bucketName, rutaArchivo);
        // 1. Construir la petición de descarga para AWS S3
        GetObjectRequest getObjectRequest = GetObjectRequest.builder()
                .bucket(bucketName)
                .key(rutaArchivo)
                .build();

        // 2. Descargar el archivo desde S3 y convertirlo a un arreglo de bytes
        try (ResponseInputStream<GetObjectResponse> s3ObjectSource = s3Client.getObject(getObjectRequest)) {
            return s3ObjectSource.readAllBytes();
        } catch (S3Exception e) {
            log.error("Fallo S3 -> bucket: '{}', key: '{}', errorCode: '{}'",
                    bucketName, rutaArchivo, e.awsErrorDetails().errorCode());
            // Error específico de la API de AWS S3 (ej. no existe el objeto en el bucket)
            throw new ArchivoS3Exception(
                    "Error al descargar el archivo desde AWS S3: " + e.awsErrorDetails().errorMessage());
        } catch (IOException e) {
            // Error de entrada/salida al leer los bytes del flujo
            throw new ArchivoS3Exception("Error al procesar los bytes del archivo médico :" + e.getMessage());
        }
    }

    public void subirArchivo(MultipartFile file, String fileName) {
        S3Client s3Client = obtenerClienteS3();
        PutObjectRequest putObjectRequest = PutObjectRequest.builder()
                .bucket(this.bucketName)
                .key(fileName)
                .build();
        try {
            s3Client.putObject(putObjectRequest, RequestBody.fromBytes(file.getBytes()));
        } catch (IOException exception) {
            throw new ArchivoS3Exception("No se pudo subir el archivo a s3");
        } catch (S3Exception exception) {
            log.error("Error S3 - status: {}, errorCode: {}, errorMessage: {}",
                    exception.statusCode(),
                    exception.awsErrorDetails() != null ? exception.awsErrorDetails().errorCode() : "N/A",
                    exception.awsErrorDetails() != null ? exception.awsErrorDetails().errorMessage()
                            : exception.getMessage());
            throw new ArchivoS3Exception("Error al subir el archivo a AWS S3");
        }
    }

    public void eliminarArchivo(String rutaArchivoAEliminar) {
        S3Client s3Client = obtenerClienteS3();
        DeleteObjectRequest deleteObjectRequest = DeleteObjectRequest.builder()
                .bucket(this.bucketName)
                .key(rutaArchivoAEliminar)
                .build();

        try {
            s3Client.deleteObject(deleteObjectRequest);
        } catch (S3Exception exception) {
            throw new ArchivoS3Exception("Error al eliminar el archivo de AWS S3");
        }
    }

    private S3Client obtenerClienteS3() {
        S3Client s3Client = s3ClientProvider.getIfAvailable();
        if (s3Client == null) {
            throw new ArchivoS3Exception(
                    "El almacenamiento de estudios clínicos en S3 está deshabilitado en este entorno");
        }
        return s3Client;
    }
}
