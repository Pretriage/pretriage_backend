package com.pretriage.backend.model.hospitales;

import java.util.ArrayList;
import java.util.List;

import com.pretriage.backend.model.personas.Paciente;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToMany;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@Entity
public class Sector {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String nombre;

    private boolean activa = true;

    @ManyToOne
    @JoinColumn(name = "id_hospital", referencedColumnName = "id")
    private Hospital hospital;

    @OneToMany(mappedBy = "sector")
    private List<Sala> salas;

    @ManyToOne
    @JoinColumn(name = "id_especialidad", referencedColumnName = "id")
    private EspecialidadMedica especialidad;

    @OneToMany
    @JoinColumn(name = "id_sector_asignado", referencedColumnName = "id")
    private List<Paciente> pacientesAsignados;

    public Sector() {
        this.salas = new ArrayList<>();
        this.pacientesAsignados = new ArrayList<>();
    }

}
