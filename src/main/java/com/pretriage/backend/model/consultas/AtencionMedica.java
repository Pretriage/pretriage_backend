package com.pretriage.backend.model.consultas;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

@Getter
@Setter
@Entity
public class AtencionMedica {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @OneToOne(optional = false)
    @JoinColumn(name = "id_consulta_medica", nullable = false, unique = true)
    private ConsultaMedica consultaMedica;

    @ManyToOne(optional = false)
    @JoinColumn(name = "id_sesion_atencion_medica", nullable = false)
    private SesionAtencionMedica sesionAtencionMedica;

    @Enumerated(EnumType.STRING)
    private EstadoAtencionMedica estado;

    private LocalDateTime fechaHoraInicio;
    private LocalDateTime fechaHoraFin;
}
