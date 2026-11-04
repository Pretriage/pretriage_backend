package com.pretriage.backend.model.hospitales;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@Entity
public class Sala {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String nombre;

    @ManyToOne
    @JoinColumn(name = "id_sector", referencedColumnName = "id")
    private Sector sector;

    private boolean activa = true;

    @ManyToOne
    @JoinColumn(name = "id_hospital", referencedColumnName = "id")
    private Hospital hospital;

    @ManyToOne
    @JoinColumn(name = "id_especialidad_medica", referencedColumnName = "id")
    private EspecialidadMedica especialidad;
}
