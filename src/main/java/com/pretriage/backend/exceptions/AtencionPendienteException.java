package com.pretriage.backend.exceptions;

/**
 * El paciente intenta iniciar un nuevo chat de pretriage mientras tiene una atención pendiente
 * (consulta con chat vinculado cuya entrada de cola no está FINALIZADA ni CANCELADA).
 * El mensaje lo lee el paciente final.
 */
public class AtencionPendienteException extends RuntimeException {
    public AtencionPendienteException() {
        super("Ya tenés una atención pendiente. Esperá a que finalice o cancelala antes de iniciar un nuevo pretriage.");
    }
}
