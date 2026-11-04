package com.pretriage.backend.model.recepcion;

import com.pretriage.backend.model.consultas.ConsultaMedica;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

@Getter @Setter @Entity
public class AdmisionRecepcion {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @OneToOne(optional = false) @JoinColumn(name = "id_consulta_medica", nullable = false, unique = true)
    private ConsultaMedica consultaMedica;
    @ManyToOne(optional = false) @JoinColumn(name = "id_sesion_recepcion", nullable = false)
    private SesionRecepcion sesionRecepcion;
    @Enumerated(EnumType.STRING)
    private EstadoAdmisionRecepcion estado;
    @Column(columnDefinition = "TEXT")
    private String formularioJson;
    @Column(columnDefinition = "TEXT")
    private String resultadoTriageJson;
    private LocalDateTime fechaHoraInicio;
    private LocalDateTime fechaHoraFinalizacion;
    private LocalDateTime fechaHoraCancelacion;
}
