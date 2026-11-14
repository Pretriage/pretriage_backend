package com.pretriage.backend.services;

import com.pretriage.backend.model.consultas.ConsultaMedica;
import com.pretriage.backend.model.consultas.EntradaCola;
import com.pretriage.backend.model.consultas.EstadoEntradaCola;
import com.pretriage.backend.model.consultas.TipoPausaCola;

import java.time.LocalDateTime;
import java.util.Objects;

/**
 * Cambio de estado de una consulta en la cola que el paciente tiene que conocer en el momento
 * (lo llamaron, lo marcaron ausente, se canceló su turno...). Se arma dentro de la transacción
 * que hace el cambio y se envía al celular del paciente por SSE cuando esa transacción confirma.
 *
 * <p>{@code id} identifica el estado puntual: se repite si el mismo estado se vuelve a enviar
 * (por ejemplo al reconectarse), para que la app no muestre dos veces la misma notificación.
 */
public record CambioEstadoConsultaEvento(
        String id,
        Long consultaId,
        EstadoEntradaCola estado,
        TipoPausaCola tipoPausa,
        String nombreHospital,
        String nombreSector,
        String nombreSala,
        LocalDateTime fechaHoraLimiteRespuesta) {

    public static CambioEstadoConsultaEvento desde(EntradaCola entrada) {
        ConsultaMedica consulta = entrada.getConsultaMedica();
        String nombreHospital = consulta.getHospital() != null ? consulta.getHospital().getNombre() : null;
        String nombreSector = consulta.getSector() != null ? consulta.getSector().getNombre() : null;
        String nombreSala = consulta.getSala() != null ? consulta.getSala().getNombre() : null;
        String id = consulta.getId() + ":" + entrada.getEstado()
                + ":" + Objects.toString(entrada.getTipoPausa(), "")
                + ":" + Objects.toString(entrada.getFechaHoraLlamado(), "")
                + ":" + Objects.toString(entrada.getFechaHoraLimiteRespuesta(), "");
        return new CambioEstadoConsultaEvento(
                id,
                consulta.getId(),
                entrada.getEstado(),
                entrada.getTipoPausa(),
                nombreHospital,
                nombreSector,
                nombreSala,
                entrada.getFechaHoraLimiteRespuesta());
    }

    public boolean esTerminal() {
        return estado == EstadoEntradaCola.FINALIZADA || estado == EstadoEntradaCola.CANCELADA;
    }
}
