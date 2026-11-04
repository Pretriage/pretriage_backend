package com.pretriage.backend.model.consultas;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import com.pretriage.backend.model.chat.Chat;
import com.pretriage.backend.model.hospitales.EspecialidadMedica;
import com.pretriage.backend.model.hospitales.Hospital;
import com.pretriage.backend.model.hospitales.Sala;
import com.pretriage.backend.model.hospitales.Sector;
import com.pretriage.backend.model.personas.Medico;
import com.pretriage.backend.model.personas.Paciente;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@Entity
public class ConsultaMedica {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private LocalDateTime fechaHoraCreacion;

    @Column(unique = true)
    private String codigoLlamado;

    @ManyToOne
    @JoinColumn(name="id_hospital", referencedColumnName = "id")
    private Hospital hospital;

    @ManyToOne
    @JoinColumn(name="id_especialidad_medica", referencedColumnName = "id")
    private EspecialidadMedica especialidad;

    @ManyToOne
    @JoinColumn(name="id_medico", referencedColumnName = "id")
    private Medico medico;

    @ManyToOne
    @JoinColumn(name="id_sala", referencedColumnName = "id")
    private Sala sala;

    @ManyToOne
    @JoinColumn(name="id_sector", referencedColumnName = "id")
    private Sector sector;

    @ManyToOne
    @JoinColumn(name="id_paciente", referencedColumnName = "id")
    private Paciente paciente;

    @OneToMany
    @JoinColumn(name ="id_consulta_medica", referencedColumnName = "id")
    private List<Sintoma> sintomasBot;


    @Enumerated(EnumType.STRING)
    private NivelDeGravedad nivelDeGravedadBot;

    @Enumerated(EnumType.STRING)
    private NivelDeGravedad nivelDeGravedadMedico;

    @Column(columnDefinition = "TEXT")
    private String resumenPretriageJson;

    @Enumerated(EnumType.STRING)
    private EstadoConsulta estadoConsulta;

    /**
     * Chat de pretriage del chatbot que generó esta consulta. Solo se vincula cuando el bot
     * finaliza el chat e ingresa al paciente a la cola; {@code null} = pretriage no realizado.
     */
    @OneToOne
    @JoinColumn(name = "id_chat", referencedColumnName = "id", unique = true)
    private Chat chat;

    public ConsultaMedica(){
        this.sintomasBot = new ArrayList<>();
        this.estadoConsulta = EstadoConsulta.PENDIENTE;
    }

    /**
     * Oculta el message chain {@code getSala().getNombre()} y centraliza la semántica
     * de "código de sala" — en el dominio actual el código es {@link com.pretriage.backend.model.hospitales.Sala#getNombre()}.
     * Retorna {@code null} si aún no hay sala asignada (solo presente en LLAMADO/EN_ATENCION).
     */
    public String getCodigoSala() {
        return sala != null ? sala.getNombre() : null;
    }

}
