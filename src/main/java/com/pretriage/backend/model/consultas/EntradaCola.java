package com.pretriage.backend.model.consultas;

import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToOne;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

@Getter
@Setter
@Entity
public class EntradaCola {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne
    @JoinColumn(name = "id_gestor_de_cola", referencedColumnName = "id")
    private GestorDeCola gestorDeCola;

    @OneToOne
    @JoinColumn(name = "id_consulta_medica", referencedColumnName = "id")
    private ConsultaMedica consultaMedica;

    @Enumerated(EnumType.STRING)
    private EstadoEntradaCola estado;

    @Enumerated(EnumType.STRING)
    private TipoPausaCola tipoPausa;

    private int prioridad;

    private long ordenRelativo;

    private LocalDateTime fechaHoraIngreso;

    private LocalDateTime fechaHoraLlamado;

    private LocalDateTime fechaHoraSalidaTemporal;

    private LocalDateTime fechaHoraUltimaRepregunta;

    private LocalDateTime fechaHoraLimiteRespuesta;
}
