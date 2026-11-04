package com.pretriage.backend.controllers.dtos.acceso;

import com.pretriage.backend.model.acceso.EstadoInvitacionHospital;
import com.pretriage.backend.model.acceso.EstadoMembresiaHospital;
import com.pretriage.backend.model.acceso.RolMembresiaHospital;
import com.pretriage.backend.model.personas.TipoDocumento;
import com.pretriage.backend.model.personas.TipoMatriculaProfesional;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.List;
import java.util.Set;

public final class StaffAccessDtos {
    private StaffAccessDtos() {}

    public record StaffMeResponse(String id, String nombre, String apellido, String email,
                                  boolean administradorPlataforma,
                                  List<MembresiaResponse> membresias) {}

    public record MembresiaResponse(Long id, Long hospitalId, String hospitalNombre,
                                    EstadoMembresiaHospital estado,
                                    Set<RolMembresiaHospital> roles) {}

    public record PersonalResponse(Long membresiaId, String nombre, String apellido, String email,
                                   EstadoMembresiaHospital estado,
                                   Set<RolMembresiaHospital> roles) {}

    public record HospitalPlataformaResponse(Long id, String nombre,
                                             long administradoresActivos) {}

    public record CrearInvitacionRequest(
            @NotBlank @Email String email,
            @NotEmpty Set<RolMembresiaHospital> roles,
            String matricula,
            TipoMatriculaProfesional tipoMatricula,
            String jurisdiccionMatricula,
            Set<Long> especialidadIds) {}

    public record InvitacionResponse(Long id, Long hospitalId, String hospitalNombre, String email,
                                     EstadoInvitacionHospital estado, Set<RolMembresiaHospital> roles,
                                     Set<Long> especialidadIds, String matricula,
                                     TipoMatriculaProfesional tipoMatricula,
                                     String jurisdiccionMatricula, Instant venceEn,
                                     Instant fechaCreacion, Boolean emailEnviado,
                                     Instant ultimoIntentoEnvio, Integer cantidadIntentosEnvio,
                                     String tokenEntregaUnica) {}

    public record InvitacionResumenResponse(Long hospitalId, String hospitalNombre, String email,
                                            EstadoInvitacionHospital estado,
                                            Set<RolMembresiaHospital> roles,
                                            Set<Long> especialidadIds, String matricula,
                                            TipoMatriculaProfesional tipoMatricula,
                                            String jurisdiccionMatricula,
                                            Instant venceEn, boolean cuentaExistente) {}

    public record RegistrarInvitadoRequest(@NotBlank String nombre, @NotBlank String apellido,
                                           @NotBlank String numeroDocumento,
                                           @NotNull TipoDocumento tipoDocumento,
                                                  @NotBlank
                                                  @Size(min = 8, max = 72)
                                                  @Pattern(
                                                          regexp = "^(?=.*[a-z])(?=.*[A-Z])(?=.*\\d)(?=.*[^A-Za-z0-9]).+$",
                                                          message = "Debe incluir mayúscula, minúscula, número y símbolo"
                                                  )
                                                  String password) {}

    public record ActualizarMembresiaRequest(@NotNull EstadoMembresiaHospital estado) {}
    public record ActualizarRolesRequest(@NotEmpty Set<RolMembresiaHospital> roles) {}

    public record AuditoriaResponse(Long id, Instant fecha, String actor, String accion,
                                    String objetivo, String resultado) {}
}
