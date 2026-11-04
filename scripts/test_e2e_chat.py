import json
import tempfile
import unittest
import uuid
from pathlib import Path
from types import SimpleNamespace
from unittest.mock import patch

import e2e_chat


class E2EHelpersTest(unittest.TestCase):
    @staticmethod
    def result(origin="GEMINI", priority=3):
        return {"motivoConsulta": "fiebre", "sintomas": ["fiebre"], "inicio": "ayer",
                "evolucion": "estable", "intensidadDolor": None, "signosAlarma": [],
                "antecedentesRelevantes": [], "medicamentos": [], "alergias": [],
                "posibilidadEmbarazo": "no informado", "observaciones": "sin alarmas",
                "nivelPrioridad": priority, "requiereAtencionInmediata": False,
                "recomendacionSeguridad": "consultar", "origenClasificacion": origin}

    def test_messages_file_ignores_utf8_bom_comment(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "case.txt"
            path.write_text("# comentario\nPrimer mensaje\n", encoding="utf-8-sig")
            self.assertEqual(e2e_chat.load_messages_file(path), ["Primer mensaje"])

    def test_parse_result_requires_meaningful_complete_result(self):
        value = self.result()
        self.assertEqual(e2e_chat.parse_result_json(json.dumps(value))["nivelPrioridad"], 3)
        value["nivelPrioridad"] = None
        with self.assertRaises(RuntimeError):
            e2e_chat.parse_result_json(json.dumps(value))

    def test_validation_rejects_mismatched_queue_state(self):
        row = {
            "chat_id": 10, "chat_patient_id": 3, "consulta_patient_id": 3,
            "resultado_triage_json": json.dumps(self.result()),
            "consulta_id": 9, "estado_consulta": "EN_COLA", "entrada_estado": "EN_ESPERA",
            "id_hospital": 1, "id_especialidad_medica": 2, "id_sector": 4,
            "gestor_hospital_id": 1, "gestor_especialidad_id": 2, "gestor_sector_id": 4,
            "nivel_de_gravedad_bot": "URGENTE", "sector_activa": True, "sala_activa": True, "prioridad": 3,
        }
        args = SimpleNamespace(db_user="u", db_name="d", db_container="c", allow_fallback=False)
        with patch.object(e2e_chat, "run_psql", return_value=json.dumps(row)):
            with self.assertRaisesRegex(RuntimeError, "queue entry state is EN_COLA"):
                e2e_chat.validate_persisted_state(args, "subject", 10, ["fiebre"])

    def test_validation_rejects_fallback_by_default(self):
        result = self.result("FALLBACK_LOCAL")
        row = {"chat_id": 10, "chat_patient_id": 3, "consulta_patient_id": 3,
               "resultado_triage_json": json.dumps(result), "consulta_id": 9,
               "estado_consulta": "EN_COLA", "entrada_estado": "EN_COLA", "id_hospital": 1,
               "id_especialidad_medica": 2, "id_sector": 4, "gestor_hospital_id": 1,
               "gestor_especialidad_id": 2, "gestor_sector_id": 4, "nivel_de_gravedad_bot": "URGENTE",
               "sector_activa": True, "sala_activa": True, "prioridad": 3}
        args = SimpleNamespace(db_user="u", db_name="d", db_container="c", allow_fallback=False)
        with patch.object(e2e_chat, "run_psql", return_value=json.dumps(row)):
            with self.assertRaisesRegex(RuntimeError, "origin is GEMINI"):
                e2e_chat.validate_persisted_state(args, "subject", 10, ["fiebre"])

    def test_validation_accepts_consistent_persisted_state(self):
        row = {"chat_id": 10, "chat_patient_id": 3, "consulta_patient_id": 3,
               "resultado_triage_json": json.dumps(self.result()), "consulta_id": 9,
               "estado_consulta": "EN_COLA", "entrada_estado": "EN_COLA", "id_hospital": 1,
               "id_especialidad_medica": 2, "id_sector": 4, "gestor_hospital_id": 1,
               "gestor_especialidad_id": 2, "gestor_sector_id": 4, "nivel_de_gravedad_bot": "URGENTE",
               "sector_activa": True, "sala_activa": True, "prioridad": 3}
        args = SimpleNamespace(db_user="u", db_name="d", db_container="c", allow_fallback=False)
        with patch.object(e2e_chat, "run_psql", return_value=json.dumps(row)) as query:
            _, result = e2e_chat.validate_persisted_state(args, "subject", 10, ["fiebre"])
        self.assertIn("cm.id_chat = c.id", query.call_args.args[0])
        self.assertEqual(result["nivelPrioridad"], 3)

    def test_gemini_precheck_uses_key_header_and_generation_capability(self):
        with patch.object(e2e_chat, "http_json", return_value={"supportedGenerationMethods": ["generateContent"]}) as http:
            e2e_chat.check_gemini("models/gemini-test", "dummy-key", "https://example.invalid/v1beta")
        self.assertNotIn("dummy-key", http.call_args.args[1])
        self.assertEqual(http.call_args.kwargs["extra_headers"], {"x-goog-api-key": "dummy-key"})
        with patch.object(e2e_chat, "http_json", return_value={"supportedGenerationMethods": ["bidiGenerateContent"]}):
            with self.assertRaisesRegex(RuntimeError, "generateContent"):
                e2e_chat.check_gemini("gemini-test", "dummy-key", "https://example.invalid/v1beta")

    def test_validation_rejects_priority_severity_mismatch(self):
        row = {"chat_id": 10, "chat_patient_id": 3, "consulta_patient_id": 3,
               "resultado_triage_json": json.dumps(self.result()), "consulta_id": 9,
               "estado_consulta": "EN_COLA", "entrada_estado": "EN_COLA", "id_hospital": 1,
               "id_especialidad_medica": 2, "id_sector": 4, "gestor_hospital_id": 1,
               "gestor_especialidad_id": 2, "gestor_sector_id": 4, "nivel_de_gravedad_bot": "NORMAL",
               "sector_activa": True, "sala_activa": True, "prioridad": 3}
        args = SimpleNamespace(db_user="u", db_name="d", db_container="c", allow_fallback=False)
        with patch.object(e2e_chat, "run_psql", return_value=json.dumps(row)):
            with self.assertRaisesRegex(RuntimeError, "severity matches priority"):
                e2e_chat.validate_persisted_state(args, "subject", 10, ["fiebre"])

    def test_early_finalization_is_rejected(self):
        with self.assertRaisesRegex(RuntimeError, "finalized early"):
            e2e_chat.ensure_no_early_finalization(1, 4)

    def test_text_turn_posts_once_then_polls_chatdto_until_completed(self):
        request_id = "c7c614d7-f335-4ebd-8fa0-ddfd1cfe2d31"
        pending = {"estadoTurnoTexto": "PENDIENTE", "estadoClasificacion": "NO_SOLICITADA",
                   "solicitudTurnoTextoId": request_id, "mensajes": []}
        completed = {"estadoTurnoTexto": "COMPLETADA", "estadoClasificacion": "COMPLETADA",
                     "solicitudTurnoTextoId": request_id, "finalizado": True,
                     "mensajes": [{"autor": "PACIENTE", "contenido": "Tengo fiebre"},
                                  {"autor": "BOT", "contenido": "Gracias. Ya quedó registrado."}]}
        with patch.object(e2e_chat.uuid, "uuid4", return_value=uuid.UUID(request_id)), \
                patch.object(e2e_chat, "http_json", side_effect=[pending, pending, completed]) as http:
            result = e2e_chat.submit_text_turn("http://backend", 7, "Tengo fiebre", "token", poll_interval=0)

        self.assertEqual(result["chat"], completed)
        self.assertEqual(e2e_chat.last_bot_message(result["chat"]), "Gracias. Ya quedó registrado.")
        self.assertEqual(http.call_count, 3)
        self.assertEqual(http.call_args_list[0].args[:2],
                         ("POST", "http://backend/api/chat/7/mensajes"))
        self.assertEqual(http.call_args_list[0].args[2],
                         {"contenido": "Tengo fiebre", "solicitudId": request_id})
        self.assertEqual([call.args[0] for call in http.call_args_list[1:]], ["GET", "GET"])

    def test_text_turn_fails_on_unavailable_or_failed_classification(self):
        request_id = "c7c614d7-f335-4ebd-8fa0-ddfd1cfe2d31"
        for state, classification in (("NO_DISPONIBLE", "NO_SOLICITADA"),
                                      ("PENDIENTE", "FALLIDA"), ("COMPLETADA", "FALLIDA")):
            with self.subTest(state=state, classification=classification), \
                    patch.object(e2e_chat, "http_json", return_value={
                        "estadoTurnoTexto": state,
                        "estadoClasificacion": classification,
                        "solicitudTurnoTextoId": request_id,
                    }), patch.object(e2e_chat.uuid, "uuid4", return_value=uuid.UUID(request_id)):
                with self.assertRaisesRegex(RuntimeError, "Text turn failed"):
                    e2e_chat.submit_text_turn("http://backend", 7, "Tengo fiebre", "token", poll_interval=0)

    def test_text_turn_keeps_compatibility_with_synchronous_response(self):
        legacy = {"respuesta": {"contenido": "¿Desde cuándo?"}, "atencionEstimada": None}
        with patch.object(e2e_chat, "http_json", return_value=legacy):
            result = e2e_chat.submit_text_turn("http://backend", 7, "Tengo fiebre", "token")
        self.assertEqual(result["legacyResponse"], legacy)

    def test_alarm_assertions_reject_missing_alarm_sign(self):
        result = self.result()
        result["requiereAtencionInmediata"] = True
        row = {"chat_id": 10, "chat_patient_id": 3, "consulta_patient_id": 3,
               "resultado_triage_json": json.dumps(result), "consulta_id": 9,
               "estado_consulta": "EN_COLA", "entrada_estado": "EN_COLA", "id_hospital": 1,
               "id_especialidad_medica": 2, "id_sector": 4, "gestor_hospital_id": 1,
               "gestor_especialidad_id": 2, "gestor_sector_id": 4, "nivel_de_gravedad_bot": "URGENTE",
               "sector_activa": True, "sala_activa": True, "prioridad": 3}
        args = SimpleNamespace(db_user="u", db_name="d", db_container="c", allow_fallback=False)
        with patch.object(e2e_chat, "run_psql", return_value=json.dumps(row)):
            with self.assertRaisesRegex(RuntimeError, "expected alarm signs"):
                e2e_chat.validate_persisted_state(args, "subject", 10, ["fiebre"], ["pecho"], True)

    def test_alarm_assertions_reject_non_immediate_result(self):
        result = self.result()
        result["signosAlarma"] = ["dolor de pecho", "dificultad para respirar"]
        row = {"chat_id": 10, "chat_patient_id": 3, "consulta_patient_id": 3,
               "resultado_triage_json": json.dumps(result), "consulta_id": 9,
               "estado_consulta": "EN_COLA", "entrada_estado": "EN_COLA", "id_hospital": 1,
               "id_especialidad_medica": 2, "id_sector": 4, "gestor_hospital_id": 1,
               "gestor_especialidad_id": 2, "gestor_sector_id": 4, "nivel_de_gravedad_bot": "URGENTE",
               "sector_activa": True, "sala_activa": True, "prioridad": 3}
        args = SimpleNamespace(db_user="u", db_name="d", db_container="c", allow_fallback=False)
        with patch.object(e2e_chat, "run_psql", return_value=json.dumps(row)):
            with self.assertRaisesRegex(RuntimeError, "immediate attention"):
                e2e_chat.validate_persisted_state(args, "subject", 10, ["fiebre"], ["pecho"], True)

    def test_result_rejects_invalid_types_and_unknown_clinical_content(self):
        invalid_values = [
            ("nivelPrioridad", True), ("intensidadDolor", 11),
            ("intensidadDolor", True), ("inicio", []),
            ("sintomas", [" "]), ("sintomas", ["no informado"]),
            ("motivoConsulta", "no informado"), ("medicamentos", "ninguno"),
        ]
        for field, invalid in invalid_values:
            with self.subTest(field=field, invalid=invalid):
                result = self.result()
                result[field] = invalid
                with self.assertRaises(RuntimeError):
                    e2e_chat.parse_result_json(json.dumps(result))

    def test_alarm_mode_requires_signs_even_without_expected_terms(self):
        result = self.result(priority=5)
        result["requiereAtencionInmediata"] = True
        row = {"chat_id": 10, "chat_patient_id": 3, "consulta_patient_id": 3,
               "resultado_triage_json": json.dumps(result), "consulta_id": 9,
               "estado_consulta": "EN_COLA", "entrada_estado": "EN_COLA", "id_hospital": 1,
               "id_especialidad_medica": 2, "id_sector": 4, "gestor_hospital_id": 1,
               "gestor_especialidad_id": 2, "gestor_sector_id": 4,
               "nivel_de_gravedad_bot": "RIESGO_VITAL_INMEDIATO",
               "sector_activa": True, "sala_activa": True, "prioridad": 5}
        args = SimpleNamespace(db_user="u", db_name="d", db_container="c", allow_fallback=False)
        with patch.object(e2e_chat, "run_psql", return_value=json.dumps(row)):
            with self.assertRaisesRegex(RuntimeError, "immediate attention has alarm signs"):
                e2e_chat.validate_persisted_state(args, "subject", 10, [], [], True)

        result["signosAlarma"] = ["dificultad respiratoria"]
        row["resultado_triage_json"] = json.dumps(result)
        with patch.object(e2e_chat, "run_psql", return_value=json.dumps(row)):
            e2e_chat.validate_persisted_state(args, "subject", 10, [], [], True)


if __name__ == "__main__":
    unittest.main()
