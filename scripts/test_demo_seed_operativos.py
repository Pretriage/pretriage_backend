import re
import unittest
from pathlib import Path

from demo_hospitales_operativos import CASOS, OperativoError, build_sql, cargar_catalogo
from demo_sectores import ESPECIALIDADES, NOMBRE_SECTOR
from demo_seed_aws import _check_sql_is_seed_only

ROOT = Path(__file__).resolve().parents[1]


class DemoHospitalesOperativosTest(unittest.TestCase):
    def test_catalogo_valido_y_especialidades_con_sector_y_casos(self):
        catalogo = cargar_catalogo()
        self.assertGreaterEqual(len(catalogo["hospitales_nuevos"]), 10)
        for codigo in ESPECIALIDADES:
            self.assertIn(codigo, NOMBRE_SECTOR)
            self.assertTrue(CASOS.get(codigo), codigo)

    def test_especialidades_coinciden_con_el_seeder_del_backend(self):
        seeder = (ROOT / "src/main/java/com/pretriage/backend/config/EspecialidadesMedicasSeeder.java").read_text(encoding="utf-8")
        declaradas = dict(re.findall(r'new EspecialidadInicial\("([A-Z_]+)", "([^"]+)"\)', seeder))
        self.assertEqual(ESPECIALIDADES, declaradas)

    def test_sql_transaccional_sin_borrados_para_el_ejecutor_aws(self):
        sql = build_sql(cargar_catalogo(), pacientes_por_sector=3, semilla="2026-11-14")
        _check_sql_is_seed_only(sql)
        self.assertTrue(sql.startswith("-- Generado") and "BEGIN;" in sql and sql.rstrip().endswith("COMMIT;"))
        self.assertIn("Guardia de Pediatría", sql)
        self.assertIn("estado_consulta NOT IN ('FINALIZADA', 'CANCELADA')", sql)
        self.assertIn("Hospital de Clínicas José de San Martín", sql)

    def test_sin_pacientes_ni_medicos_de_guardia(self):
        sql = build_sql(cargar_catalogo(), pacientes_por_sector=0, medicos_de_guardia=False, semilla="x")
        self.assertNotIn("demo_op_casos", sql)
        self.assertNotIn("GUARDIA-DEMO-", sql)

    def test_rechaza_cantidades_invalidas(self):
        with self.assertRaises(OperativoError):
            build_sql(cargar_catalogo(), pacientes_por_sector=11)


if __name__ == "__main__":
    unittest.main()
