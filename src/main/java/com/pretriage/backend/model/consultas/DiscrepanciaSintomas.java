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
import jakarta.persistence.OneToOne;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.ColumnDefault;

import com.pretriage.backend.model.personas.UsuarioAuth;

import java.time.Instant;

@Getter
@Setter
@Entity
@Table(indexes = @Index(name = "idx_discrepancia_dni_estado_fecha", columnList = "dni, estado, registrada_en"))
public class DiscrepanciaSintomas {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "id_atencion_medica", nullable = false, unique = true)
    private AtencionMedica atencionMedica;

    @Column(length = 500)
    private String motivo;

    @Column(length = 8)
    private String dni;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    @ColumnDefault("'ANULADA'")
    private EstadoDiscrepanciaSintomas estado;

    @Column(nullable = false)
    private Instant registradaEn;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "anulada_por_id")
    private UsuarioAuth anuladaPor;

    private Instant anuladaEn;
}
