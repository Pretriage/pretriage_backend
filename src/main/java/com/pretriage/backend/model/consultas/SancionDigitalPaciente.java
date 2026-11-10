package com.pretriage.backend.model.consultas;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

@Getter
@Setter
@Entity
@Table(indexes = @Index(name = "idx_sancion_dni_id", columnList = "dni, id"))
public class SancionDigitalPaciente {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "discrepancia_id", nullable = false)
    private DiscrepanciaSintomas discrepancia;

    @Column(nullable = false, length = 8)
    private String dni;

    @Column(nullable = false)
    private Instant calculadaEn;

    @Column(nullable = false)
    private long cantidadVigentes;

    private Instant fechaInicio;

    private Instant fechaFin;

    @Column(nullable = false)
    private boolean permanente;

    @Column(length = 64)
    private String avisoId;

    @Enumerated(EnumType.STRING)
    @Column(length = 16)
    private TipoAvisoSancion tipoAviso;
}
