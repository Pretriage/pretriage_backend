package com.pretriage.backend.services;

import com.pretriage.backend.controllers.dtos.EsperaNuevaConsultaCalculo;
import com.pretriage.backend.controllers.dtos.EspecialidadMedicaDTO;
import com.pretriage.backend.controllers.dtos.HospitalCercanoDTO;
import com.pretriage.backend.controllers.dtos.HospitalSeleccionadoResponse;
import com.pretriage.backend.controllers.dtos.SalaDTO;
import com.pretriage.backend.controllers.dtos.TiempoEstimadoArriboHospitalResponse;
import com.pretriage.backend.controllers.dtos.TiempoEstimadoAtencionResponse;
import com.pretriage.backend.exceptions.AtencionEnCursoException;
import com.pretriage.backend.model.chat.Chat;
import com.pretriage.backend.model.consultas.ConsultaMedica;
import com.pretriage.backend.model.consultas.EstadoConsulta;
import com.pretriage.backend.model.consultas.NivelDeGravedad;
import com.pretriage.backend.model.hospitales.Direccion;
import com.pretriage.backend.model.hospitales.EspecialidadMedica;
import com.pretriage.backend.model.hospitales.Hospital;
import com.pretriage.backend.model.hospitales.Sala;
import com.pretriage.backend.model.hospitales.Sector;
import com.pretriage.backend.model.personas.Paciente;
import com.pretriage.backend.repositories.RepoConsultasMedicas;
import com.pretriage.backend.repositories.RepoEspecialidadesMedicas;
import com.pretriage.backend.repositories.RepoHospitales;
import jakarta.transaction.Transactional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

@Service
@RequiredArgsConstructor
@Slf4j
public class AtencionHospitalService {

    private static final List<EstadoConsulta> ESTADOS_CONSULTA_ACTIVA = List.of(
            EstadoConsulta.PENDIENTE,
            EstadoConsulta.HOSPITAL_SELECCIONADO,
            EstadoConsulta.PRETRIAGE_FINALIZADO,
            EstadoConsulta.PRETRIAGE_EN_PROCESO,
            EstadoConsulta.EN_COLA,
            EstadoConsulta.LLAMADO,
            EstadoConsulta.EN_ESPERA,
            EstadoConsulta.ATRASADO,
            EstadoConsulta.EN_ATENCION);

    private static final String ORDEN_DISTANCIA = "distancia";
    private static final String ORDEN_TIEMPO_ATENCION = "tiempo-atencion";
    private static final String ORDEN_VALORACION = "valoracion";

    private static final List<String> ORDENES_VALIDOS = List.of(
            ORDEN_DISTANCIA,
            ORDEN_TIEMPO_ATENCION,
            ORDEN_VALORACION);

    private static final List<EstadoConsulta> ESTADOS_CONSULTA_CON_HOSPITAL = List.of(
            EstadoConsulta.HOSPITAL_SELECCIONADO,
            EstadoConsulta.PRETRIAGE_EN_PROCESO,
            EstadoConsulta.PRETRIAGE_FINALIZADO,
            EstadoConsulta.EN_COLA,
            EstadoConsulta.LLAMADO,
            EstadoConsulta.EN_ESPERA,
            EstadoConsulta.ATRASADO,
            EstadoConsulta.EN_ATENCION);

    private final RepoConsultasMedicas repoConsultasMedicas;
    private final RepoHospitales repoHospitales;
    private final RepoEspecialidadesMedicas repoEspecialidadesMedicas;

    private final EstimacionAtencionService estimacionAtencionService;
    private final IngresoColaService ingresoColaService;
    private final AsignacionSectorService asignacionSectorService;

    private final PacienteService pacienteService;
    private final GooglePlacesService googlePlacesService;

    public List<HospitalCercanoDTO> buscarHospitalesCercanos(Double latitud, Double longitud, String codigoEspecialidad,
            String transporte, String auth0Id) {
        return buscarHospitalesCercanos(latitud, longitud, codigoEspecialidad, transporte, auth0Id, "distancia");
    }

    public List<HospitalCercanoDTO> buscarHospitalesCercanos(Double latitud, Double longitud, String codigoEspecialidad,
            String transporte, String auth0Id, String ordenarPor) {
        this.obtenerPaciente(auth0Id);// valida si es un paciente válido

        String transporteEfectivo = transporte != null ? transporte : "transporte-publico";
        String ordenarPorEfectivo = ordenarPor != null ? ordenarPor : "distancia";
        List<String> criterios = Arrays.asList(ordenarPorEfectivo.split("\\|"));
        Set<String> sinDuplicados = new HashSet<>(criterios);
        boolean valido = !criterios.isEmpty()
                && criterios.stream().allMatch(ORDENES_VALIDOS::contains)
                && sinDuplicados.size() == criterios.size();
        if (!valido) {
            throw new IllegalArgumentException("Parametro ordenarPor invalido");
        }

        EspecialidadMedica especialidad = obtenerEspecialidad(codigoEspecialidad);
        List<Hospital> registrados = repoHospitales.findDistinctByEspecialidadesCodigo(especialidad.getCodigo());
        if (registrados.isEmpty()) return List.of();
        List<HospitalCercanoDTO> hospitalesCercanos = googlePlacesService
                .buscarHospitalesRegistrados(latitud, longitud, registrados);
        Map<String, Hospital> hospitalesPorPlaceId = registrados.stream()
                .filter(hospital -> hospital.getPlaceId() != null && !hospital.getPlaceId().isBlank())
                .collect(Collectors.toMap(Hospital::getPlaceId, Function.identity()));

        List<HospitalCercanoDTO> resultado = hospitalesCercanos.stream()
                .filter(hospitalCercano -> hospitalesPorPlaceId.containsKey(hospitalCercano.getPlaceId()))
                .map(hospitalCercano -> {
                    Hospital hospital = hospitalesPorPlaceId.get(hospitalCercano.getPlaceId());
                    hospitalCercano.setIdHospital(hospital.getId());

                    hospitalCercano.setTiempoEstimadoArriboMejorRuta(
                            googlePlacesService.calcularTiempoEstimadoArriboMejorRuta(
                                    hospital, transporteEfectivo, latitud, longitud));

                    EsperaNuevaConsultaCalculo espera = estimacionAtencionService.calcularEsperaParaNuevaConsulta(
                            hospital.getId(), especialidad.getId());
                    hospitalCercano.setPacientesEnCola(espera.pacientesEnCola());
                    hospitalCercano.setMinutosEsperaEstimados(espera.minutosEspera());
                    hospitalCercano.setFechaHoraAtencionEstimada(espera.fechaHoraAtencionEstimada());
                    hospitalCercano.setDisponible(espera.hayMedicosActivos());

                    return completarEspecialidades(hospitalCercano, hospital);
                })
                .filter(HospitalCercanoDTO::isDisponible)
                .toList();

        boolean porTiempo = criterios.contains(ORDEN_TIEMPO_ATENCION);
        boolean porValoracion = criterios.contains(ORDEN_VALORACION);

        if (criterios.size() > 1) {
            return ordenarPorSumaDeRankings(resultado, criterios);
        }

        if (porTiempo) {
            return resultado.stream()
                    .sorted(comparatorPorTiempoAtencion())
                    .toList();
        }

        if (porValoracion) {
            return resultado.stream()
                    .sorted(comparatorPorValoracion())
                    .toList();
        }

        // distancia: conserva el orden geodesico de los hospitales registrados
        return resultado;
    }

    /**
     * Orden combinado: para cada criterio pedido se calcula la posicion del
     * hospital en su orden individual y se suman; desempata por nombre (el
     * devuelto por Google) y, con nombres iguales o ausentes, conserva el orden
     * por distancia geodesica (el sort es estable sobre esa lista).
     */
    private List<HospitalCercanoDTO> ordenarPorSumaDeRankings(List<HospitalCercanoDTO> resultado,
            List<String> criterios) {
        Map<String, Integer> sumaRankings = new HashMap<>();
        for (HospitalCercanoDTO dto : resultado) {
            sumaRankings.put(dto.getPlaceId(), 0);
        }

        for (String criterio : criterios) {
            List<HospitalCercanoDTO> ordenCriterioBase = resultado;
            if (ORDEN_TIEMPO_ATENCION.equals(criterio)) {
                ordenCriterioBase = resultado.stream()
                        .sorted(comparatorPorTiempoAtencion())
                        .toList();
            } else if (ORDEN_VALORACION.equals(criterio)) {
                ordenCriterioBase = resultado.stream()
                        .sorted(comparatorPorValoracion())
                        .toList();
            }
            // distancia: el propio orden de resultado (orden de Google)
            final List<HospitalCercanoDTO> ordenCriterio = ordenCriterioBase;

            Map<String, Integer> posiciones = IntStream.range(0, ordenCriterio.size())
                    .boxed()
                    .collect(Collectors.toMap(
                            i -> ordenCriterio.get(i).getPlaceId(),
                            i -> i,
                            (a, b) -> a));
            for (HospitalCercanoDTO dto : resultado) {
                sumaRankings.merge(dto.getPlaceId(),
                        posiciones.getOrDefault(dto.getPlaceId(), Integer.MAX_VALUE),
                        Integer::sum);
            }
        }

        Map<String, Integer> rankings = sumaRankings;
        return resultado.stream()
                .sorted(Comparator
                        .<HospitalCercanoDTO>comparingInt(dto -> rankings.getOrDefault(dto.getPlaceId(), Integer.MAX_VALUE))
                        .thenComparing(HospitalCercanoDTO::getNombre,
                                Comparator.nullsLast(Comparator.naturalOrder())))
                .toList();
    }

    private Comparator<HospitalCercanoDTO> comparatorPorTiempoAtencion() {
        return Comparator.comparing(HospitalCercanoDTO::getMinutosEsperaEstimados,
                Comparator.nullsLast(Comparator.naturalOrder()))
                .thenComparing(HospitalCercanoDTO::getTiempoEstimadoArriboMejorRuta,
                        Comparator.nullsLast(Comparator.naturalOrder()));
    }

    /**
     * Valoracion de mayor a menor; sin valoracion (null) se trata como puntaje 0.
     * Desempate por cantidad de valoraciones (mayor a menor, null = 0) y luego
     * por nombre del hospital (el devuelto por Google) ASC.
     */
    private Comparator<HospitalCercanoDTO> comparatorPorValoracion() {
        return Comparator
                .comparing((HospitalCercanoDTO dto) -> dto.getValoracionPromedio() != null
                        ? dto.getValoracionPromedio() : 0d,
                        Comparator.reverseOrder())
                .thenComparing(dto -> dto.getCantidadValoraciones() != null
                        ? dto.getCantidadValoraciones() : 0,
                        Comparator.reverseOrder())
                .thenComparing(HospitalCercanoDTO::getNombre,
                        Comparator.nullsLast(Comparator.naturalOrder()));
    }

    @Transactional
    public void seleccionarHospital(String auth0Id, String placeId, String codigoEspecialidad) {
        Paciente paciente = this.obtenerPaciente(auth0Id);
        EspecialidadMedica especialidad = obtenerEspecialidad(codigoEspecialidad);
        ConsultaMedica consultaMedica = obtenerOCrearConsultaParaSeleccionarHospital(paciente);

        Optional<Hospital> opHospital = repoHospitales.findByPlaceId(placeId);
        Hospital hospital;

        if (opHospital.isEmpty()) {
            hospital = googlePlacesService.obtenerHospitalDesdeGoogle(placeId);
            if (hospital == null) {
                throw new NoSuchElementException("Hospital inexistente");
            }
            repoHospitales.save(hospital);
        } else {
            hospital = opHospital.get();
        }

        validarHospitalAtiendeEspecialidad(hospital, especialidad);

        consultaMedica.setHospital(hospital);
        consultaMedica.setEspecialidad(especialidad);
        asignarSectorSiEsNecesario(consultaMedica);
        ingresoColaService.ingresar(consultaMedica, NivelDeGravedad.NORMAL, consultaMedica.getSector());
    }

    private ConsultaMedica obtenerOCrearConsultaParaSeleccionarHospital(Paciente paciente) {
        Optional<ConsultaMedica> opConsultaMedica = repoConsultasMedicas
                .findFirstByPacienteIdAndEstadoConsultaIn(paciente.getId(), ESTADOS_CONSULTA_ACTIVA);

        if (opConsultaMedica.isPresent()) {
            ConsultaMedica consultaMedica = opConsultaMedica.get();
            if (consultaMedica.getEstadoConsulta() != EstadoConsulta.PENDIENTE) {
                throw new AtencionEnCursoException();
            }
            return consultaMedica;
        }

        ConsultaMedica consultaMedicaNueva = new ConsultaMedica();
        consultaMedicaNueva.setPaciente(paciente);
        consultaMedicaNueva.setFechaHoraCreacion(LocalDateTime.now());
        consultaMedicaNueva.setNivelDeGravedadBot(NivelDeGravedad.NORMAL);
        return consultaMedicaNueva;
    }

    @Transactional
    public TiempoEstimadoAtencionResponse obtenerTiempoEstimadoDeAtencion(String auth0Id) {
        ConsultaMedica consultaMedica = obtenerConsultaConHospitalSeleccionado(auth0Id);
        return calcularTiempoEstimadoDeAtencion(consultaMedica);
    }

    @Transactional
    public HospitalSeleccionadoResponse obtenerHospitalSeleccionado(String auth0Id) {
        ConsultaMedica consultaMedica = obtenerConsultaConHospitalSeleccionado(auth0Id);
        Hospital hospital = consultaMedica.getHospital();

        HospitalSeleccionadoResponse response = new HospitalSeleccionadoResponse();
        response.setIdHospital(hospital.getId());
        response.setPlaceId(hospital.getPlaceId());
        response.setNombre(hospital.getNombre());
        Direccion direccion = hospital.getDireccion();
        response.setDireccion(direccion != null ? direccion.formateada() : null);
        Sector sector = consultaMedica.getSector();
        if (sector != null) {
            response.setSectorId(sector.getId());
            response.setNombreSector(sector.getNombre());
            response.setSalas(sector.getSalas().stream()
                    .filter(Sala::isActiva)
                    .map(this::mapearSalaDTO)
                    .toList());
        }
        return response;
    }

    @Transactional
    public TiempoEstimadoAtencionResponse finalizarTriageEIngresarACola(String auth0Id,
            NivelDeGravedad nivelDeGravedadBot) {
        return finalizarTriageEIngresarACola(auth0Id, nivelDeGravedadBot, null);
    }

    @Transactional
    public TiempoEstimadoAtencionResponse finalizarTriageEIngresarACola(
            String auth0Id, NivelDeGravedad nivelDeGravedadBot, String resumenPretriageJson) {
        return finalizarTriageEIngresarACola(auth0Id, nivelDeGravedadBot, resumenPretriageJson, null);
    }

    /**
     * Finaliza el pretriage del chatbot: guarda el resumen, vincula la consulta con el chat que la
     * generó (si se informa) e ingresa al paciente a la cola con la gravedad estimada por el bot.
     * Un {@code chat} null deja la consulta sin vínculo (cuenta como pretriage no realizado).
     */
    @Transactional
    public TiempoEstimadoAtencionResponse finalizarTriageEIngresarACola(
            String auth0Id, NivelDeGravedad nivelDeGravedadBot, String resumenPretriageJson, Chat chat) {
        Paciente paciente = this.obtenerPaciente(auth0Id);
        ConsultaMedica consultaMedica = obtenerConsultaConHospitalSeleccionado(paciente);
        consultaMedica.setResumenPretriageJson(resumenPretriageJson);
        if (chat != null) {
            consultaMedica.setChat(chat);
        }
        repoConsultasMedicas.save(consultaMedica);
        return ingresoColaService.ingresar(consultaMedica, nivelDeGravedadBot);
    }

    @Transactional
    public List<TiempoEstimadoArriboHospitalResponse> calcularTiempoArriboHospital(
            String auth0Id, Long idHospital, String transporte, Double latitud, Double longitud) {
        this.obtenerPaciente(auth0Id);// valido que sea paciente

        Hospital hospital = this.obtenerHospital(idHospital);

        if (!googlePlacesService.esTransporteValido(transporte)) {
            throw new IllegalArgumentException("Transporte no valido");
        }

        return googlePlacesService.calcularTiempoArriboHospital(hospital, transporte, latitud, longitud);
    }

    private ConsultaMedica obtenerConsultaConHospitalSeleccionado(String auth0Id) {
        Paciente paciente = obtenerPaciente(auth0Id);
        return obtenerConsultaConHospitalSeleccionado(paciente);
    }

    private ConsultaMedica obtenerConsultaConHospitalSeleccionado(Paciente paciente) {
        Optional<ConsultaMedica> opConsultaMedica = repoConsultasMedicas
                .findFirstByPacienteIdAndEstadoConsultaIn(paciente.getId(), ESTADOS_CONSULTA_CON_HOSPITAL);

        if (opConsultaMedica.isEmpty()) {
            throw new NoSuchElementException(
                    "Se debe seleccionar primero un hospital o finalizar el pretriage para estimar su tiempo de atencion");
        }
        return opConsultaMedica.get();
    }

    private TiempoEstimadoAtencionResponse calcularTiempoEstimadoDeAtencion(ConsultaMedica consultaMedica) {
        return estimacionAtencionService.calcularPara(consultaMedica);
    }

    private Paciente obtenerPaciente(String auth0Id) {
        Optional<Paciente> opPaciente = pacienteService.obtenerPacienteConUsuarioAuthId(auth0Id);

        if (opPaciente.isEmpty()) {
            throw new AccessDeniedException("No tiene permisos para seleccionar el hospital de otro paciente");
        }

        return opPaciente.get();
    }

    private EspecialidadMedica obtenerEspecialidad(String codigoEspecialidad) {
        return repoEspecialidadesMedicas.findByCodigo(codigoEspecialidad)
                .orElseThrow(() -> new NoSuchElementException("Especialidad medica inexistente"));
    }

    private void validarHospitalAtiendeEspecialidad(Hospital hospital, EspecialidadMedica especialidad) {
        List<EspecialidadMedica> especialidadesHospital = hospital.getEspecialidades();

        if (especialidadesHospital.isEmpty()) {
            throw new NoSuchElementException(
                    "En el hospital seleccionado no se cargaron las especialidades o no cuenta con ninguna especialidad en urgencias");
        }

        boolean atiendeEspecialidad = especialidadesHospital.stream()
                .anyMatch(especialidadHospital -> especialidadHospital.getCodigo().equals(especialidad.getCodigo()));

        if (!atiendeEspecialidad) {
            throw new NoSuchElementException("El hospital no atiende la especialidad seleccionada");
        }
    }

    private HospitalCercanoDTO completarEspecialidades(HospitalCercanoDTO hospitalCercano, Hospital hospital) {
        hospitalCercano.setEspecialidades(hospital.getEspecialidades().stream()
                .map(this::mapearEspecialidadADTO)
                .toList());
        return hospitalCercano;
    }

    private EspecialidadMedicaDTO mapearEspecialidadADTO(EspecialidadMedica especialidad) {
        EspecialidadMedicaDTO dto = new EspecialidadMedicaDTO();
        dto.setCodigo(especialidad.getCodigo());
        dto.setNombre(especialidad.getNombre());
        return dto;
    }

    private Hospital obtenerHospital(Long idHospital) {
        Optional<Hospital> opHospital = repoHospitales.findById(idHospital);
        if (opHospital.isEmpty()) {
            throw new NoSuchElementException("No existe el Hospital con id: " + idHospital);
        }
        return opHospital.get();
    }

    private void asignarSectorSiEsNecesario(ConsultaMedica consultaMedica) {
        Sector sector = consultaMedica.getSector();
        if (sector == null
                || !sector.getHospital().getId().equals(consultaMedica.getHospital().getId())
                || !sector.getEspecialidad().getId().equals(consultaMedica.getEspecialidad().getId())) {
            asignacionSectorService.asignarSector(consultaMedica);
        }
    }

    private SalaDTO mapearSalaDTO(Sala sala) {
        SalaDTO dto = new SalaDTO();
        dto.setId(sala.getId());
        dto.setNombre(sala.getNombre());
        return dto;
    }
}
