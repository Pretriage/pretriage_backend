package com.pretriage.backend.services;

import com.pretriage.backend.controllers.dtos.CredencialRequest;
import com.pretriage.backend.controllers.dtos.CredencialResponse;
import com.pretriage.backend.controllers.dtos.ObraSocialDTO;
import com.pretriage.backend.controllers.dtos.ObraSocialResponse;
import com.pretriage.backend.exceptions.ObraSocialNoExisteException;
import com.pretriage.backend.exceptions.ObraSocialYaExisteException;
import com.pretriage.backend.exceptions.CredencialValidaYaExisteException;
import com.pretriage.backend.mappers.MapperCredencial;
import com.pretriage.backend.model.hospitales.Credencial;
import com.pretriage.backend.model.hospitales.ObraSocial;
import com.pretriage.backend.model.personas.Paciente;
import com.pretriage.backend.model.personas.RolSistema;
import com.pretriage.backend.model.personas.UsuarioAuth;
import com.pretriage.backend.repositories.*;
import com.pretriage.backend.services.validadoresObrasociales.FabricaValidadoresCredencialesObraSocial;
import com.pretriage.backend.services.validadoresObrasociales.ValidadorCredencialObraSocial;
import jakarta.transaction.Transactional;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.NonNull;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;

@Service
@RequiredArgsConstructor
@Transactional
public class CredencialService {

    private final PacienteService pacienteService;
    private final RecepcionistaService recepcionistaService;
    private final RepoObraSociales repoObraSociales;
    private final RepoCredenciales repoCredenciales;
    private final ValidacionCredencialObraSocialService validacionCredencialObraSocialService;
    private final FabricaValidadoresCredencialesObraSocial fabricaValidadoresCredencialesObraSocial;

    public void cargarCredencialPaciente(
            String auth0IdPaciente,
            CredencialRequest request) {

        Paciente paciente = this.obtenerPaciente(auth0IdPaciente);

        this.cargarCredencial(request, paciente);
    }

    @Transactional
    public List<CredencialResponse> obtenerCredencialesPaciente(String auth0IdPaciente) {

        Paciente paciente = obtenerPaciente(auth0IdPaciente);

        return this.obtenerCredencialesDePaciente(paciente.getId());
    }

    public List<CredencialResponse> obtenerCredencialesPacienteRecepcionista(String authoIdRecepcionista,
            Long idPaciente) {
        this.verificarSiEsRecepcionista(authoIdRecepcionista);

        return this.obtenerCredencialesDePaciente(idPaciente);
    }

    private List<CredencialResponse> obtenerCredencialesDePaciente(Long idPaciente) {
        return repoCredenciales
                .findByPacienteId(idPaciente)
                .stream()
                .map(MapperCredencial::toDTOResponse)
                .toList();
    }

    public void cargarCredencialRecepcionista(String auth0IdRecepcionista, Long idPaciente,
            @Valid CredencialRequest request) {

        verificarSiEsRecepcionista(auth0IdRecepcionista);

        Paciente paciente = pacienteService.obtenerPaciente(idPaciente);

        cargarCredencial(request, paciente);
    }

    private Paciente obtenerPaciente(String auth0IdPaciente) {

        Optional<Paciente> opPaciente = pacienteService
                .obtenerPacienteConUsuarioAuthId(auth0IdPaciente);
        if (opPaciente.isEmpty()) {
            throw new AccessDeniedException(
                    "No tiene permisos para cargar la credencial");
        } else {
            return opPaciente.get();
        }
    }

    private void verificarSiEsRecepcionista(String auth0IdRecepcionista) {

        boolean esRecepcionista = recepcionistaService
                .esRecepcionistaConUsuarioId(auth0IdRecepcionista);
        if (!esRecepcionista) {
            throw new AccessDeniedException(
                    "No tiene permisos para cargar la credencial");
        }
    }

    private ObraSocial obtenerObraSocial(String nombre) {

        return repoObraSociales
                .findByNombreEqualsIgnoreCaseAndVirgenteTrue(nombre)
                .orElseThrow(ObraSocialNoExisteException::new);
    }

    private void cargarCredencial(CredencialRequest request, Paciente paciente) {
        ObraSocial obraSocial = obtenerObraSocial(
                request.getNombreObraSocial());

        validarCredencial(request, paciente);

        Credencial credencial = new Credencial();
        credencial.setObraSocial(obraSocial);
        credencial.setNumeroAfiliado(request.getNumeroAfiliado());
        credencial.setPlan(request.getPlan());
        credencial.setFechaVencimiento(request.getFechaVencimiento());
        credencial.setPaciente(paciente);

        repoCredenciales.save(credencial);
    }

    private void validarCredencial(CredencialRequest request, Paciente paciente) {
        ValidadorCredencialObraSocial validador = fabricaValidadoresCredencialesObraSocial
                .obtenerValidador(request.getNombreObraSocial());

        validacionCredencialObraSocialService
                .validarCredencialObraSocial(request, paciente, validador);

        Optional<Credencial> opCredencial = this.repoCredenciales.findByNumeroAfiliadoAndObraSocialNombre(request.getNumeroAfiliado(), request.getNombreObraSocial().toUpperCase());
        
        if(opCredencial.isPresent()){
            throw new CredencialValidaYaExisteException();
        }
    }

    @Transactional
    public void eliminarCredencial(Long idCredencial, String auth0IdPaciente) {

        Paciente paciente = obtenerPaciente(auth0IdPaciente);

        this.obtenerCredencialYVerificarPermiso(paciente.getId(), idCredencial);

        repoCredenciales.deleteById(idCredencial);
    }

    @Transactional
    public void eliminarCredencialRecepcionista(Long idCredencial, Long idPaciente, String auth0IdRecepcionista) {

        this.obtenerCredencialYVerificarPermisoRecepcionista(auth0IdRecepcionista, idPaciente, idCredencial);

        repoCredenciales.deleteById(idCredencial);
    }

    @Transactional
    public void editarCredencialPaciente(
            Long idCredencial,
            String auth0IdPaciente,
            CredencialRequest request) {

        Paciente paciente = obtenerPaciente(auth0IdPaciente);

        Credencial credencial = this.obtenerCredencialYVerificarPermiso(paciente.getId(), idCredencial);

        validarCredencial(request, paciente);

        ObraSocial obraSocial = obtenerObraSocial(
                request.getNombreObraSocial());

        credencial.setObraSocial(obraSocial);
        credencial.setNumeroAfiliado(request.getNumeroAfiliado());
        credencial.setPlan(request.getPlan());
        credencial.setFechaVencimiento(request.getFechaVencimiento());

        repoCredenciales.save(credencial);
    }

    @Transactional
    public void editarCredencialRecepcionista(
            Long idCredencial,
            Long idPaciente,
            String auth0IdRecepcionista,
            CredencialRequest request) {

        Credencial credencial = this.obtenerCredencialYVerificarPermisoRecepcionista(auth0IdRecepcionista, idPaciente,
                idCredencial);

        this.validarCredencial(request, credencial.getPaciente());

        ObraSocial obraSocial = this.obtenerObraSocial(
                request.getNombreObraSocial());

        credencial.setObraSocial(obraSocial);
        credencial.setNumeroAfiliado(request.getNumeroAfiliado());
        credencial.setPlan(request.getPlan());
        credencial.setFechaVencimiento(request.getFechaVencimiento());

        repoCredenciales.save(credencial);
    }

    @Transactional
    public ObraSocial cargarObraSocialAdmin(String auth0id, @NonNull ObraSocialDTO request) {
        this.verificarSiEsAdmin(auth0id);

        if (this.repoObraSociales.findByNombreEqualsIgnoreCaseAndVirgenteTrue(request.getNombre()).isPresent()) {
            throw new ObraSocialYaExisteException();
        } else {
            ObraSocial obraSocial = new ObraSocial();
            obraSocial.setNombre(request.getNombre());
            return repoObraSociales.save(obraSocial);
        }
    }

    @Transactional
    public void eliminarObraSocial(String auth0Id, Long idObraSocial) {
        this.verificarSiEsAdmin(auth0Id);

        ObraSocial obraSocial = repoObraSociales.findById(idObraSocial)
                .orElseThrow(ObraSocialNoExisteException::new);

        obraSocial.setVirgente(false); // borrado lógico
        repoObraSociales.save(obraSocial);
    }

    @Transactional
    private Credencial obtenerCredencialYVerificarPermiso(Long idPaciente, Long idCredencial) {

        Credencial credencial = repoCredenciales.findById(idCredencial)
                .orElseThrow(() -> new AccessDeniedException(
                        "No tiene permisos para editar la credencial"));

        if (!credencial.getPaciente().getId().equals(idPaciente)) {
            throw new AccessDeniedException(
                    "No tiene permisos para editar esta credencial");
        }

        return credencial;
    }

    @Transactional
    private Credencial obtenerCredencialYVerificarPermisoRecepcionista(String auth0IdRecepcionista, Long idPaciente,
            Long idCredencial) {
        verificarSiEsRecepcionista(auth0IdRecepcionista);

        Paciente paciente = pacienteService.obtenerPaciente(idPaciente);

        Credencial credencial = repoCredenciales.findById(idCredencial)
                .orElseThrow(() -> new AccessDeniedException(
                        "No tiene permisos para editar la credencial"));

        if (!credencial.getPaciente().getId().equals(paciente.getId())) {
            throw new AccessDeniedException(
                    "No tiene permisos para editar esta credencial");
        }

        return credencial;
    }

    @Transactional
    private void verificarSiEsAdmin(String auth0Id) { // asumo que los admins son recepcionistas
        UsuarioAuth recepcionistaUser = recepcionistaService
                .obtenerUsuarioAuth(auth0Id);
        if (!recepcionistaUser.getRol().equals(RolSistema.ADMIN)) {
            throw new AccessDeniedException(
                    "No tiene permisos para cargar la credencial");
        }
    }

    public List<ObraSocialResponse> obtenerObrasSociales() {
        return repoObraSociales.findByVirgenteTrue()
            .stream()
            .map(obraSocial -> new ObraSocialResponse(
                    obraSocial.getNombre()
            ))
            .toList();
    }
}