package com.pretriage.backend.model.consultas;

import com.pretriage.backend.model.personas.Medico;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

@Getter
@Setter
@Entity
public class RevisionPrioridadConsulta {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(optional = false)
    @JoinColumn(name = "id_consulta_medica", nullable = false)
    private ConsultaMedica consultaMedica;

    @ManyToOne(optional = false)
    @JoinColumn(name = "id_medico", nullable = false)
    private Medico medico;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private DecisionRevisionPrioridad decision;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private NivelDeGravedad prioridadAnterior;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private NivelDeGravedad prioridadNueva;

    @Column(length = 500)
    private String motivo;

    @Column(nullable = false)
    private LocalDateTime fechaHora;
}
