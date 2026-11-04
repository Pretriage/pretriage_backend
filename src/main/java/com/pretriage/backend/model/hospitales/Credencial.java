package com.pretriage.backend.model.hospitales;

import java.time.LocalDate;

import com.pretriage.backend.model.personas.Paciente;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@Entity
public class Credencial {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String numeroAfiliado;

    private String plan;

    private LocalDate fechaVencimiento;

    @ManyToOne
    @JoinColumn(name="id_obra_social", referencedColumnName = "id")
    private ObraSocial obraSocial;

    @ManyToOne
    @JoinColumn(name = "id_paciente", referencedColumnName = "id")
    private Paciente paciente;
}
