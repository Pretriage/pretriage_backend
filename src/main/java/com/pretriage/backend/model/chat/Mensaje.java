package com.pretriage.backend.model.chat;

import com.pretriage.backend.model.personas.Paciente;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(uniqueConstraints = @UniqueConstraint(name = "uk_mensaje_chat_solicitud", columnNames = {"chat_id", "solicitud_id"}))
public class Mensaje {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(columnDefinition = "TEXT", nullable = false)
    private String contenido;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private AutorMensaje autor;

    @ManyToOne
    @JoinColumn(name = "paciente_id", referencedColumnName = "id")
    private Paciente pacienteAutor;

    @Column(nullable = false)
    private LocalDateTime fechaHoraEnvio;

    /** Null/false for legacy and ordinary messages; true means it is retained for display but excluded from triage. */
    @Column(name = "excluido_del_triage", nullable = true)
    private Boolean excluidoDelTriage;

    /** Idempotency identity for a patient text turn; null on legacy and voice messages. */
    @Column(name = "solicitud_id", length = 36)
    private String solicitudId;

    public Mensaje(String contenido, AutorMensaje autor, Paciente pacienteAutor) {
        this.contenido = contenido;
        this.autor = autor;
        this.pacienteAutor = pacienteAutor;
        this.fechaHoraEnvio = LocalDateTime.now();
    }
}
