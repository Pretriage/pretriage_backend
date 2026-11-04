package com.pretriage.backend.services;

import com.pretriage.backend.controllers.dtos.EsperaNuevaConsultaCalculo;
import com.pretriage.backend.controllers.dtos.TiempoEstimadoAtencionResponse;
import com.pretriage.backend.exceptions.NoSePudoEstimarElHorarioDeAtencion;
import com.pretriage.backend.model.consultas.ConsultaMedica;
import com.pretriage.backend.model.consultas.EntradaCola;
import com.pretriage.backend.model.consultas.EstadoEntradaCola;
import com.pretriage.backend.model.consultas.EstadoSesionMedica;
import com.pretriage.backend.repositories.RepoEntradasCola;
import com.pretriage.backend.repositories.RepoSesionesAtencionMedica;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;

@Service
@RequiredArgsConstructor
public class EstimacionAtencionService {

    private static final String MENSAJE_SIN_MEDICOS_ACTIVOS = "No hay medicos atendiendo esta especialidad en este momento. La hora es una estimacion tentativa.";

    private final RepoEntradasCola repoEntradasCola;
    private final RepoSesionesAtencionMedica repoSesionesAtencionMedica;

    @Value("${pretriage.estimacion.minutos-promedio-atencion:10}")
    private int minutosPromedioAtencion;

    public TiempoEstimadoAtencionResponse calcularPara(ConsultaMedica consultaMedica) {
        EntradaCola entradaPaciente = repoEntradasCola.findByConsultaMedicaId(consultaMedica.getId())
                .filter(entrada -> entrada.getEstado() == EstadoEntradaCola.EN_COLA)
                .orElseThrow(NoSePudoEstimarElHorarioDeAtencion::new);

        List<EntradaCola> cola = repoEntradasCola
                .findByGestorDeColaIdAndEstadoOrderByPrioridadDescOrdenRelativoAscFechaHoraIngresoAsc(
                        entradaPaciente.getGestorDeCola().getId(),
                        EstadoEntradaCola.EN_COLA);

        int posicionBaseCero = buscarPosicion(cola, entradaPaciente);
        if (posicionBaseCero < 0) {
            throw new NoSePudoEstimarElHorarioDeAtencion();
        }

        int medicosActivos = repoSesionesAtencionMedica
                .countByHospitalIdAndEspecialidadIdAndSectorIdAndEstado(
                        consultaMedica.getHospital().getId(),
                        consultaMedica.getEspecialidad().getId(),
                        consultaMedica.getSector().getId(),
                        EstadoSesionMedica.ACTIVA);
        int medicosParaEstimacion = Math.max(medicosActivos, 1);
        int bloquesEspera = bloquesEspera(posicionBaseCero, medicosParaEstimacion);

        TiempoEstimadoAtencionResponse response = new TiempoEstimadoAtencionResponse();
        response.setConsultaId(consultaMedica.getId());
        response.setFechaHoraAtencionEstimada(
                LocalDateTime.now().plusMinutes((long) bloquesEspera * minutosPromedioAtencion));
        response.setHayMedicosActivos(medicosActivos > 0);
        response.setMedicosActivos(medicosActivos);
        response.setMedicosParaEstimacion(medicosParaEstimacion);
        response.setPacientesAntes(posicionBaseCero);
        response.setPosicionEnCola(posicionBaseCero + 1);
        response.setMinutosPromedioAtencion(minutosPromedioAtencion);
        response.setSectorId(consultaMedica.getSector() != null ? consultaMedica.getSector().getId() : null);
        response.setNombreSector(consultaMedica.getSector() != null ? consultaMedica.getSector().getNombre() : null);
        response.setCodigoSala(consultaMedica.getCodigoSala());

        if (medicosActivos == 0) {
            response.setMensaje(MENSAJE_SIN_MEDICOS_ACTIVOS);
        }
        return response;
    }

    public EsperaNuevaConsultaCalculo calcularEsperaParaNuevaConsulta(Long hospitalId, Long especialidadId) {
        int pacientesEnCola = (int) repoEntradasCola.countByGestorDeColaHospitalIdAndGestorDeColaEspecialidadIdAndEstado(
                hospitalId, especialidadId, EstadoEntradaCola.EN_COLA);
        int medicosActivos = repoSesionesAtencionMedica.countByHospitalIdAndEspecialidadIdAndEstado(
                hospitalId, especialidadId, EstadoSesionMedica.ACTIVA);
        int medicosParaEstimacion = Math.max(medicosActivos, 1);
        int bloquesEspera = bloquesEspera(pacientesEnCola, medicosParaEstimacion);
        long minutosEspera = (long) bloquesEspera * minutosPromedioAtencion;
        LocalDateTime fechaHoraAtencionEstimada = LocalDateTime.now().plusMinutes(minutosEspera);
        boolean hayMedicosActivos = medicosActivos > 0;
        return new EsperaNuevaConsultaCalculo(
                pacientesEnCola, minutosEspera, fechaHoraAtencionEstimada, hayMedicosActivos);
    }

    private int bloquesEspera(int pacientesAntes, int medicosParaEstimacion) {
        return pacientesAntes / medicosParaEstimacion;
    }

    private int buscarPosicion(List<EntradaCola> cola, EntradaCola entradaPaciente) {
        for (int i = 0; i < cola.size(); i++) {
            EntradaCola entrada = cola.get(i);
            if (entrada.getId() != null && entrada.getId().equals(entradaPaciente.getId())) {
                return i;
            }
            if (entrada.getConsultaMedica() != null
                    && entradaPaciente.getConsultaMedica() != null
                    && entrada.getConsultaMedica().getId() != null
                    && entrada.getConsultaMedica().getId().equals(entradaPaciente.getConsultaMedica().getId())) {
                return i;
            }
        }
        return -1;
    }
}
