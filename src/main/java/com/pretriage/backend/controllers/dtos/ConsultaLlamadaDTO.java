package com.pretriage.backend.controllers.dtos;

import com.pretriage.backend.model.consultas.EstadoConsulta;
import com.pretriage.backend.model.consultas.NivelDeGravedad;
import com.pretriage.backend.model.personas.TipoDocumento;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class ConsultaLlamadaDTO {

    private Long consultaId;
    private String codigoLlamado;

    private Long pacienteId;

    private String nombrePaciente;

    private String apellidoPaciente;

    private String numeroDocumento;

    private TipoDocumento tipoDocumento;

    private Long salaId;

    private String nombreSala;

    private NivelDeGravedad prioridad;

    private EstadoConsulta estadoConsulta;
}
