package com.pretriage.backend.repositories;

import com.pretriage.backend.model.chat.AutorMensaje;
import com.pretriage.backend.model.chat.Chat;
import com.pretriage.backend.model.chat.Mensaje;
import com.pretriage.backend.model.personas.Paciente;
import com.pretriage.backend.mappers.MapperChat;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** Real PostgreSQL regressions for stable turn order and independent chat histories. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Transactional
class ChatHistoryPersistenceTest {
    @Autowired
    private RepoChat chats;
    @Autowired
    private RepoPacientes pacientes;
    @Autowired
    private EntityManager entityManager;

    @Test
    void loteDeVozConMismaHoraConservaOrdenAunqueCambieElOrdenFisicoDeFilas() {
        Paciente paciente = pacientes.saveAndFlush(new Paciente());
        Chat chat = new Chat(paciente);
        LocalDateTime horaLote = LocalDateTime.of(2026, 10, 28, 12, 0);
        List<String> contenidoEsperado = List.of("¿Qué te pasa?", "Tengo tos", "¿Desde cuándo?",
                "Desde ayer", "¿Hay algo más?", "No, nada.");
        for (int i = 0; i < contenidoEsperado.size(); i++) {
            boolean esPaciente = i % 2 == 1;
            Mensaje mensaje = new Mensaje(contenidoEsperado.get(i),
                    esPaciente ? AutorMensaje.PACIENTE : AutorMensaje.BOT, esPaciente ? paciente : null);
            mensaje.setFechaHoraEnvio(horaLote);
            chat.agregarMensaje(mensaje);
        }
        chats.saveAndFlush(chat);
        Long chatId = chat.getId();
        List<Long> idsEsperados = chat.getMensajes().stream().map(Mensaje::getId).toList();
        entityManager.clear();

        // Only this test's rows in the disposable database: PostgreSQL MVCC writes
        // replacement tuples in reverse order, so physical order cannot stand in for conversation order.
        for (Long id : idsEsperados.reversed()) {
            entityManager.createNativeQuery("update mensaje set contenido = contenido where id = :id")
                    .setParameter("id", id).executeUpdate();
        }
        entityManager.clear();

        Chat recuperado = chats.findById(chatId).orElseThrow();
        assertEquals(idsEsperados, recuperado.getMensajes().stream().map(Mensaje::getId).toList());
        assertEquals(contenidoEsperado, MapperChat.toDTO(recuperado).mensajes().stream()
                .map(mensaje -> mensaje.contenido()).toList());
    }

    @Test
    void historialOrdenaPorHoraAntesDeDesempatarPorId() {
        Paciente paciente = pacientes.saveAndFlush(new Paciente());
        Chat chat = new Chat(paciente);
        LocalDateTime hora = LocalDateTime.of(2026, 10, 28, 12, 0);
        Mensaje posterior = new Mensaje("Respuesta posterior", AutorMensaje.PACIENTE, paciente);
        posterior.setFechaHoraEnvio(hora.plusMinutes(1));
        Mensaje anterior = new Mensaje("Pregunta anterior", AutorMensaje.BOT, null);
        anterior.setFechaHoraEnvio(hora);
        chat.agregarMensaje(posterior);
        chat.agregarMensaje(anterior);
        chats.saveAndFlush(chat);
        Long id = chat.getId();
        entityManager.clear();

        assertEquals(List.of("Pregunta anterior", "Respuesta posterior"), chats.findById(id).orElseThrow()
                .getMensajes().stream().map(Mensaje::getContenido).toList());
    }

    @Test
    void pacientePuedeCrearOtroChatSinPerderElHistorialDelAnterior() {
        Paciente paciente = pacientes.saveAndFlush(new Paciente());
        Chat anterior = new Chat(paciente);
        anterior.agregarMensaje(new Mensaje("Mensaje de la consulta anterior", AutorMensaje.PACIENTE, paciente));
        anterior.setFinalizado(true);
        chats.saveAndFlush(anterior);

        Chat nuevo = new Chat(paciente);
        nuevo.agregarMensaje(new Mensaje("Nuevo saludo", AutorMensaje.BOT, null));
        chats.saveAndFlush(nuevo);

        assertNotEquals(anterior.getId(), nuevo.getId());
        assertFalse(chats.findById(nuevo.getId()).orElseThrow().isFinalizado());
        Chat historial = chats.findById(anterior.getId()).orElseThrow();
        assertTrue(historial.isFinalizado());
        assertEquals("Mensaje de la consulta anterior", historial.getMensajes().getFirst().getContenido());
        assertEquals(2, chats.findAll().stream()
                .filter(chat -> chat.getPaciente().getId().equals(paciente.getId())).count());
    }
}
