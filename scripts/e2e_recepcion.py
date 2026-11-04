#!/usr/bin/env python3
import argparse
import json
import os
import random
import urllib.error
import urllib.request


def request(method, url, token, body=None):
    data = json.dumps(body).encode() if body is not None else None
    headers = {"Accept": "application/json", "Authorization": f"Bearer {token}"}
    if data is not None:
        headers["Content-Type"] = "application/json"
    try:
        with urllib.request.urlopen(
                urllib.request.Request(url, data=data, headers=headers, method=method), timeout=120) as response:
            raw = response.read().decode()
            return response.status, json.loads(raw) if raw else None
    except urllib.error.HTTPError as error:
        raw = error.read().decode()
        raise RuntimeError(f"{method} {url} -> {error.code}: {raw}") from error


def main():
    parser = argparse.ArgumentParser(description="Reception admission HTTP E2E")
    parser.add_argument("--base-url", default="http://localhost:8080")
    parser.add_argument("--token", default=os.environ.get("E2E_RECEPCION_TOKEN"))
    parser.add_argument("--hospital-id", type=int)
    parser.add_argument("--mode", choices=("finalize", "cancel"), default="finalize")
    args = parser.parse_args()
    if not args.token:
        parser.error("--token or E2E_RECEPCION_TOKEN is required")

    api = args.base_url.rstrip("/") + "/api/recepcion"
    _, hospitals = request("GET", f"{api}/hospitales", args.token)
    hospital = next((item for item in hospitals if item["id"] == args.hospital_id), None) \
        if args.hospital_id else hospitals[0]
    if hospital is None:
        raise RuntimeError("The receptionist is not assigned to the requested hospital")

    status, session = request("GET", f"{api}/sesiones/activa", args.token)
    if status == 204 or session is None:
        _, session = request("POST", f"{api}/sesiones", args.token, {"hospitalId": hospital["id"]})
    elif session["hospitalId"] != hospital["id"]:
        raise RuntimeError("The active session belongs to another hospital")

    dni = str(random.randint(40000000, 49999999))
    specialty = hospital["especialidades"][0]["codigo"]
    _, admission = request("POST", f"{api}/admisiones", args.token, {
        "sesionId": session["id"], "dni": dni, "nombre": "Paciente", "apellido": "E2E",
        "fechaNacimiento": "1990-05-10", "generoBiologico": "FEMENINO",
        "telefono": "+54 11 5555-0101", "correoElectronico": "paciente.e2e@example.com",
        "calle": "Calle E2E", "alturaDomicilio": "1234", "piso": "2",
        "codigoPostal": "C1000",
        "codigoEspecialidad": specialty,
    })
    admission_id = admission["id"]

    _, opened = request("GET", f"{api}/admisiones?sesionId={session['id']}", args.token)
    if admission_id not in [item["id"] for item in opened]:
        raise RuntimeError("Created admission is missing from the open-admission list")

    if args.mode == "cancel":
        _, result = request("POST", f"{api}/admisiones/{admission_id}/cancelar", args.token)
        expected = "CANCELADA"
    else:
        _, result = request("POST", f"{api}/admisiones/{admission_id}/finalizar", args.token, {
            "motivoConsulta": "Dolor abdominal", "sintomas": ["dolor"],
            "inicio": "Hace dos horas", "evolucion": "EMPEORA", "intensidadDolor": 7,
            "localizacionDolor": "Abdomen inferior", "fiebre": False, "signosAlarma": [],
            "antecedentesRelevantes": [], "medicamentos": [], "alergias": [],
            "posibilidadEmbarazo": "NO", "observaciones": "",
        })
        expected = "FINALIZADA"

    _, detail = request("GET", f"{api}/admisiones/{admission_id}", args.token)
    if result["estado"] != expected or detail["estado"] != expected:
        raise RuntimeError(f"Unexpected terminal state: {detail['estado']}")
    print(json.dumps(detail, indent=2, ensure_ascii=False))


if __name__ == "__main__":
    main()
