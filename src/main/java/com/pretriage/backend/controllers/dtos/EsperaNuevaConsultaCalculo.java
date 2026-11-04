package com.pretriage.backend.controllers.dtos;

import java.time.LocalDateTime;

public record EsperaNuevaConsultaCalculo(
        int pacientesEnCola,
        long minutosEspera,
        LocalDateTime fechaHoraAtencionEstimada,
        boolean hayMedicosActivos) {
}
