package com.pretriage.backend.controllers.dtos;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class TriageResultDTOTest {
    @Test
    void elResumenMedicoPuedeLeerResultadoPersistidoConOrigenDeClasificacion() {
        ObjectMapper mapper = new ObjectMapper();
        TriageResultDTO resultado = new TriageResultDTO(
                "Dolor de garganta", List.of("dolor de garganta"), "desde ayer", "sin cambios",
                5, List.of(), List.of(), List.of(), List.of(), "no informado",
                "Sin otros datos", 3, false, "Continuar evaluacion presencial");
        ObjectNode json = mapper.valueToTree(resultado);
        json.put("origenClasificacion", "GEMINI");

        assertEquals(resultado, mapper.readValue(mapper.writeValueAsString(json), TriageResultDTO.class));
        assertEquals(resultado, mapper.readValue(mapper.writeValueAsString(resultado), TriageResultDTO.class));
    }
}
