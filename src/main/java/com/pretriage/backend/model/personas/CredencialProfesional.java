package com.pretriage.backend.model.personas;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@Entity
@Table(uniqueConstraints = @UniqueConstraint(
        columnNames = {"numero", "tipo", "jurisdiccion"}))
public class CredencialProfesional {
    public static final String JURISDICCION_NACIONAL = "NACION";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(optional = false)
    @JoinColumn(name = "medico_id")
    private Medico medico;

    @Column(nullable = false)
    private String numero;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private TipoMatriculaProfesional tipo;

    @Column(nullable = false)
    private String jurisdiccion;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private EstadoCredencialProfesional estado = EstadoCredencialProfesional.PENDIENTE_VERIFICACION;
}
