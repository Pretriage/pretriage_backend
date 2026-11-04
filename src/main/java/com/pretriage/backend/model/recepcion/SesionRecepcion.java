package com.pretriage.backend.model.recepcion;

import com.pretriage.backend.model.hospitales.Hospital;
import com.pretriage.backend.model.personas.Recepcionista;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

@Getter @Setter @Entity
public class SesionRecepcion {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @ManyToOne(optional = false) @JoinColumn(name = "id_recepcionista", nullable = false)
    private Recepcionista recepcionista;
    @ManyToOne(optional = false) @JoinColumn(name = "id_hospital", nullable = false)
    private Hospital hospital;
    @Enumerated(EnumType.STRING)
    private EstadoSesionRecepcion estado;
    private LocalDateTime fechaHoraInicio;
    private LocalDateTime fechaHoraFin;
}
