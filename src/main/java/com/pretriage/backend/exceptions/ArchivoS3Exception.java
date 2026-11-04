package com.pretriage.backend.exceptions;

public class ArchivoS3Exception extends RuntimeException {
    public ArchivoS3Exception(String message) {
        super(message);
    }
}
