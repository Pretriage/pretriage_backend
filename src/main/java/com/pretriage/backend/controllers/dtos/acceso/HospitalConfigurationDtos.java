package com.pretriage.backend.controllers.dtos.acceso;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;

public final class HospitalConfigurationDtos {
    private HospitalConfigurationDtos() {}

    public record EspecialidadHospitalResponse(
            Long id, String codigo, String nombre, boolean habilitada) {}

    public record SalaHospitalResponse(
            Long id, String nombre, boolean activa,
            Long sectorId, String sectorNombre,
            Long especialidadId, String especialidadCodigo, String especialidadNombre) {}

    public record ConfiguracionHospitalResponse(
            List<EspecialidadHospitalResponse> especialidades,
            List<SalaHospitalResponse> salas,
            List<SectorHospitalResponse> sectores) {}

    public record GuardarSalaRequest(
            @NotBlank @Size(max = 100) String nombre,
            @NotNull Long especialidadId) {}

    public record ActualizarEstadoSalaRequest(@NotNull Boolean activa) {}

    public record SectorHospitalResponse(
            Long id, String nombre, boolean activa, Long especialidadId,
            String especialidadCodigo, String especialidadNombre) {}

    public record GuardarSectorRequest(
            @NotBlank @Size(max = 100) String nombre,
            @NotNull Long especialidadId) {}

    public record ActualizarSectorRequest(
            @NotBlank @Size(max = 100) String nombre,
            @NotNull Long especialidadId,
            @NotNull Boolean activa) {}
}
