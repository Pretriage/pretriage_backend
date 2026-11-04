package com.pretriage.backend.controllers.dtos;

import com.pretriage.backend.model.chat.AutorMensaje;

/**
 * Turno transcripto de una entrevista por voz (paciente o asistente).
 */
public record TurnoVoz(AutorMensaje autor, String contenido) {
}
