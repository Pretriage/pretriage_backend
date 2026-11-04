package com.pretriage.backend.model.consultas;

import com.pretriage.backend.model.hospitales.EspecialidadMedica;
import com.pretriage.backend.model.hospitales.Hospital;
import com.pretriage.backend.model.hospitales.Sala;
import com.pretriage.backend.model.hospitales.Sector;
import com.pretriage.backend.model.personas.Medico;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

@Getter
@Setter
@Entity
public class SesionAtencionMedica {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne
    @JoinColumn(name = "id_medico", referencedColumnName = "id")
    private Medico medico;

    @ManyToOne
    @JoinColumn(name = "id_hospital", referencedColumnName = "id")
    private Hospital hospital;

    @ManyToOne
    @JoinColumn(name = "id_especialidad_medica", referencedColumnName = "id")
    private EspecialidadMedica especialidad;

    @ManyToOne
    @JoinColumn(name = "id_sala", referencedColumnName = "id")
    private Sala sala;

    @ManyToOne
    @JoinColumn(name = "id_sector", referencedColumnName = "id")
    private Sector sector;

    @Enumerated(EnumType.STRING)
    private EstadoSesionMedica estado;

    private LocalDateTime fechaHoraInicio;

    private LocalDateTime fechaHoraFin;

    private LocalDateTime fechaHoraPausa;
}
