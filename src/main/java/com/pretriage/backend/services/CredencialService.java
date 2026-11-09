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

import java.text.Normalizer;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Pattern;

@Service
@RequiredArgsConstructor
@Transactional
public class CredencialService {
    private static final ZoneId ZONA_ARGENTINA = ZoneId.of("America/Argentina/Buenos_Aires");
    private static final Pattern DIACRITICOS = Pattern.compile("\\p{M}+");

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

        cargarCredencial(request, paciente, request.getFechaVencimiento());
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

        cargarCredencial(request, paciente, vencimientoRecepcion(request.getFechaVencimiento()));
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

    private Optional<ObraSocial> buscarObraSocial(String nombre) {
        String nombreIngresado = nombre.strip();
        return repoObraSociales
                .findByNombreEqualsIgnoreCaseAndVirgenteTrue(nombreIngresado)
                .or(() -> {
                    String clave = claveObraSocial(nombreIngresado);
                    List<ObraSocial> coincidencias = repoObraSociales.findByVirgenteTrue().stream()
                            .filter(obraSocial -> claveObraSocial(obraSocial.getNombre()).equals(clave))
                            .toList();
                    return coincidencias.size() == 1 ? Optional.of(coincidencias.getFirst()) : Optional.empty();
                });
    }

    private static String claveObraSocial(String nombre) {
        String descompuesto = Normalizer.normalize(nombre.strip(), Normalizer.Form.NFD);
        return DIACRITICOS.matcher(descompuesto).replaceAll("").toUpperCase(Locale.ROOT);
    }

    private void cargarCredencial(CredencialRequest request, Paciente paciente, LocalDate fechaVencimiento) {
        ObraSocial obraSocial = buscarObraSocial(request.getNombreObraSocial())
                .orElseThrow(ObraSocialNoExisteException::new);
        validarConProveedor(request, paciente, obraSocial.getNombre());
        validarNumeroAfiliado(request, obraSocial, null);

        Credencial credencial = new Credencial();
        credencial.setObraSocial(obraSocial);
        credencial.setNumeroAfiliado(request.getNumeroAfiliado());
        credencial.setPlan(request.getPlan());
        credencial.setFechaVencimiento(fechaVencimiento);
        credencial.setPaciente(paciente);

        repoCredenciales.save(credencial);
    }

    private void validarConProveedor(CredencialRequest request, Paciente paciente, String nombreObraSocial) {
        ValidadorCredencialObraSocial validador = fabricaValidadoresCredencialesObraSocial
                .obtenerValidador(nombreObraSocial);

        validacionCredencialObraSocialService
                .validarCredencialObraSocial(request, paciente, validador);
    }

    private void validarNumeroAfiliado(CredencialRequest request, ObraSocial obraSocial, Long idCredencialEditada) {
        Optional<Credencial> opCredencial = repoCredenciales.findByNumeroAfiliadoAndObraSocialNombre(
                request.getNumeroAfiliado(), obraSocial.getNombre());
        
        if (opCredencial.filter(registrada -> idCredencialEditada == null
                || !idCredencialEditada.equals(registrada.getId())).isPresent()) {
            throw new CredencialValidaYaExisteException();
        }
    }

    private ObraSocial validarObraSocialEditada(CredencialRequest request, Paciente paciente, Long idCredencialEditada) {
        Optional<ObraSocial> candidata = buscarObraSocial(request.getNombreObraSocial());
        validarConProveedor(request, paciente, candidata.map(ObraSocial::getNombre).orElse(request.getNombreObraSocial()));
        ObraSocial obraSocial = candidata.orElseThrow(ObraSocialNoExisteException::new);
        validarNumeroAfiliado(request, obraSocial, idCredencialEditada);
        return obraSocial;
    }

    private LocalDate vencimientoRecepcion(LocalDate fecha) {
        LocalDate finDelMes = YearMonth.from(fecha).atEndOfMonth();
        if (!finDelMes.isAfter(LocalDate.now(ZONA_ARGENTINA))) {
            throw new IllegalArgumentException("La fecha de vencimiento debe ser posterior a hoy.");
        }
        return finDelMes;
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

        ObraSocial obraSocial = validarObraSocialEditada(request, paciente, null);

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

        LocalDate fechaVencimiento = vencimientoRecepcion(request.getFechaVencimiento());

        ObraSocial obraSocial = validarObraSocialEditada(request, credencial.getPaciente(), idCredencial);

        credencial.setObraSocial(obraSocial);
        credencial.setNumeroAfiliado(request.getNumeroAfiliado());
        credencial.setPlan(request.getPlan());
        credencial.setFechaVencimiento(fechaVencimiento);

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
