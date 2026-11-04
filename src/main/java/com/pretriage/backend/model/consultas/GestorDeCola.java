package com.pretriage.backend.model.consultas;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import com.pretriage.backend.model.hospitales.EspecialidadMedica;
import com.pretriage.backend.model.hospitales.Hospital;
import com.pretriage.backend.model.hospitales.Sector;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@Entity
@Table(uniqueConstraints = @UniqueConstraint(columnNames = { "id_hospital", "id_especialidad_medica", "id_sector" }))
public class GestorDeCola {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne
    @JoinColumn(name = "id_hospital", referencedColumnName = "id")
    private Hospital hospital;

    @ManyToOne
    @JoinColumn(name = "id_especialidad_medica", referencedColumnName = "id")
    private EspecialidadMedica especialidad;

    @OneToMany
    @JoinColumn(name = "id_gestor_de_cola", referencedColumnName = "id")
    private List<ConsultaMedica> consultasEnEspera;

    @OneToMany(mappedBy = "gestorDeCola")
    private List<EntradaCola> entradas;

    @OneToOne
    @JoinColumn(name = "id_sector", referencedColumnName = "id")
    private Sector sector;

    public GestorDeCola() {
        this.consultasEnEspera = new ArrayList<>();
        this.entradas = new ArrayList<>();
    }

    private void reordenarColaPorPrioridad() {

        this.eliminarAtendidosDeLaCola();

        this.consultasEnEspera.sort(
                Comparator
                        .comparingInt(
                                (ConsultaMedica consulta) -> obtenerPrioridad(consulta.getNivelDeGravedadBot()))
                        .reversed()
                        .thenComparing(ConsultaMedica::getFechaHoraCreacion));
    }

    public int obtenerPrioridad(NivelDeGravedad nivel) {
        if (nivel == null) {
            return 2;
        }

        return switch (nivel) {
            case RIESGO_VITAL_INMEDIATO -> 5;
            case MUY_URGENTE -> 4;
            case URGENTE -> 3;
            case NORMAL -> 2;
            case NO_URGENTE -> 1;
        };
    }

    public void agregarConsultaMedicaALaCola(ConsultaMedica consultaMedica) {
        if (!this.consultasEnEspera.contains(consultaMedica)) {
            this.consultasEnEspera.add(consultaMedica);
        }
        reordenarColaPorPrioridad();
    }

    private void eliminarAtendidosDeLaCola() {
        this.consultasEnEspera
                .removeIf(consultaMedica -> consultaMedica.getEstadoConsulta().equals(EstadoConsulta.FINALIZADA)
                        || consultaMedica.getEstadoConsulta().equals(EstadoConsulta.CANCELADA));
    }
}
