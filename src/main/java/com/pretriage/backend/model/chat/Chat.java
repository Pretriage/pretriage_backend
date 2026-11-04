package com.pretriage.backend.model.chat;

import com.pretriage.backend.model.personas.Paciente;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Getter
@Setter
@NoArgsConstructor
@Entity
public class Chat {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @OneToMany(cascade = CascadeType.ALL, orphanRemoval = true)
    @JoinColumn(name = "chat_id", referencedColumnName = "id")
    @OrderBy("fechaHoraEnvio ASC, id ASC")
    private List<Mensaje> mensajes = new ArrayList<>();

    private LocalDateTime fechaHoraCreacion;

    @ManyToOne
    @JoinColumn(name = "paciente_id", referencedColumnName = "id")
    private Paciente paciente;

    private boolean finalizado;

    @Enumerated(EnumType.STRING)
    private EstadoClasificacionChat estadoClasificacion = EstadoClasificacionChat.NO_SOLICITADA;

    /** Durable budget and ownership for backend-owned recovery of newly closed voice chats. */
    private Integer intentosClasificacionAutomaticos = 0;
    private Boolean recuperacionAutomaticaClasificacion = false;
    private Boolean clasificacionAutomaticaEnCurso = false;
    private Boolean clasificacionAutomaticaVoz = false;

    /** Durable ownership/status of the most recently accepted text turn. */
    @Enumerated(EnumType.STRING)
    private EstadoTurnoTexto estadoTurnoTexto = EstadoTurnoTexto.SIN_SOLICITAR;
    private String solicitudTurnoTextoId;
    private Integer intentosTurnoTexto = 0;
    private Boolean turnoTextoEnCurso = false;
    private String errorTurnoTexto;
    private LocalDateTime turnoTextoIniciadoEn;

    public int getIntentosClasificacionAutomaticos() {
        return intentosClasificacionAutomaticos == null ? 0 : intentosClasificacionAutomaticos;
    }

    public boolean isRecuperacionAutomaticaClasificacion() {
        return Boolean.TRUE.equals(recuperacionAutomaticaClasificacion);
    }

    public boolean isClasificacionAutomaticaEnCurso() {
        return Boolean.TRUE.equals(clasificacionAutomaticaEnCurso);
    }

    public boolean isClasificacionAutomaticaVoz() {
        return Boolean.TRUE.equals(clasificacionAutomaticaVoz);
    }

    public int getIntentosTurnoTexto() { return intentosTurnoTexto == null ? 0 : intentosTurnoTexto; }
    public boolean isTurnoTextoEnCurso() { return Boolean.TRUE.equals(turnoTextoEnCurso); }
    /** Legacy rows predate text-turn tracking and are idle by default. */
    public EstadoTurnoTexto getEstadoTurnoTexto() {
        return estadoTurnoTexto == null ? EstadoTurnoTexto.SIN_SOLICITAR : estadoTurnoTexto;
    }

    /** Legacy rows have no state column value; infer only an existing completed result. */
    public EstadoClasificacionChat getEstadoClasificacion() {
        return estadoClasificacion != null ? estadoClasificacion
                : resultadoTriageJson != null ? EstadoClasificacionChat.COMPLETADA
                : EstadoClasificacionChat.NO_SOLICITADA;
    }

    @Column(columnDefinition = "TEXT")
    private String resultadoTriageJson;

    public Chat(Paciente paciente) {
        this.paciente = paciente;
        this.fechaHoraCreacion = LocalDateTime.now();
        this.finalizado = false;
    }

    public void agregarMensaje(Mensaje mensaje) {
        mensajes.add(mensaje);
    }
}
