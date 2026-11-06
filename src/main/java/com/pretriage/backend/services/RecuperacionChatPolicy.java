package com.pretriage.backend.services;

import com.pretriage.backend.exceptions.ProveedorIaException;
import com.pretriage.backend.model.chat.Chat;
import com.pretriage.backend.model.chat.EstadoRecuperacionChat;
import java.time.Duration;
import java.time.LocalDateTime;

final class RecuperacionChatPolicy {
    private RecuperacionChatPolicy() { }
    static boolean api(RuntimeException error) { return error instanceof ProveedorIaException p && p.isApiNoDisponible(); }
    static boolean configuracion(RuntimeException error) { return error instanceof ProveedorIaException p && p.getCategoria() == ProveedorIaException.Categoria.CONFIGURACION; }
    static void programar(Chat chat, RuntimeException error, long demoraInternaMs, long demoraApiMs) {
        Duration demora = Duration.ofMillis(demoraInternaMs);
        if (api(error)) {
            int fallos = chat.getFallosDisponibilidad() + 1;
            chat.setFallosDisponibilidad(fallos);
            demora = Duration.ofMillis(fallos == 1 ? demoraApiMs : fallos == 2 ? Math.max(demoraApiMs, 120000) : Math.max(demoraApiMs, 300000));
            Duration retryAfter = ((ProveedorIaException) error).getRetryAfter();
            if (retryAfter != null && retryAfter.compareTo(demora) > 0) demora = retryAfter;
            chat.setEstadoRecuperacion(EstadoRecuperacionChat.ESPERANDO_API);
        } else chat.setEstadoRecuperacion(EstadoRecuperacionChat.REINTENTANDO);
        chat.setProximoReintentoEn(LocalDateTime.now().plus(demora));
    }
}
