package com.pretriage.backend.controllers.dtos;

import com.pretriage.backend.model.consultas.EstadoConsulta;
import com.pretriage.backend.model.consultas.EstadoEntradaCola;
import com.pretriage.backend.model.consultas.TipoPausaCola;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

@Getter
@Setter
public class EstadoConsultaPacienteDTO {

    private Long consultaId;

    private EstadoConsulta estadoConsulta;

    private EstadoEntradaCola estadoEntradaCola;

    private TipoPausaCola tipoPausa;

    private LocalDateTime fechaHoraLimiteRespuesta;

    private Long sectorId;

    private String nombreSector;

    private TiempoEstimadoAtencionResponse tiempoEstimadoAtencion;
}

