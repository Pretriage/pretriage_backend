package com.pretriage.backend.services;

import com.pretriage.backend.controllers.dtos.TiempoEstimadoAtencionResponse;
import com.pretriage.backend.exceptions.ConflictoDeEstadoException;
import com.pretriage.backend.model.consultas.*;
import com.pretriage.backend.model.hospitales.Sector;
import com.pretriage.backend.repositories.RepoConsultasMedicas;
import com.pretriage.backend.repositories.RepoEntradasCola;
import com.pretriage.backend.repositories.RepoGestoresDeColas;
import com.pretriage.backend.repositories.RepoSectores;
import jakarta.transaction.Transactional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.NoSuchElementException;

@Service
@RequiredArgsConstructor
public class IngresoColaService {
    private final RepoConsultasMedicas repoConsultasMedicas;
    private final RepoGestoresDeColas repoGestoresDeColas;
    private final RepoSectores repoSectores;
    private final RepoEntradasCola repoEntradasCola;

    private final EstimacionAtencionService estimacionAtencionService;

    @Transactional
    public TiempoEstimadoAtencionResponse ingresar(ConsultaMedica consulta, NivelDeGravedad prioridad, Sector sector) {
        consulta.setSector(sector);
        return ingresar(consulta, prioridad);
    }

    @Transactional
    public TiempoEstimadoAtencionResponse ingresar(ConsultaMedica consulta, NivelDeGravedad prioridad) {
        if (consulta.getHospital() == null || consulta.getEspecialidad() == null) {
            throw new NoSuchElementException("La consulta debe tener hospital y especialidad");
        }
        if (consulta.getSector() == null) {
            throw new NoSuchElementException("La consulta debe tener un sector asignado para ingresar a la cola");
        }
        Sector sector = repoSectores.findByIdForOperationalUse(consulta.getSector().getId())
                .orElseThrow(() -> new NoSuchElementException("El sector asignado a la consulta no está disponible"));
        validarSectorOperativo(sector);
        if (sector.getHospital() == null || !consulta.getHospital().getId().equals(sector.getHospital().getId())
                || sector.getEspecialidad() == null
                || !consulta.getEspecialidad().getId().equals(sector.getEspecialidad().getId())) {
            throw new ConflictoDeEstadoException("El sector no corresponde al hospital y especialidad de la consulta");
        }
        consulta.setSector(sector);
        consulta.setNivelDeGravedadBot(prioridad);
        consulta.setEstadoConsulta(EstadoConsulta.EN_COLA);
        repoConsultasMedicas.save(consulta);
        GestorDeCola gestor = repoGestoresDeColas
                .findByHospitalIdAndEspecialidadIdAndSectorId(
                        consulta.getHospital().getId(), consulta.getEspecialidad().getId(), sector.getId())
                .orElseGet(() -> {
                    GestorDeCola nuevo = new GestorDeCola();
                    nuevo.setHospital(consulta.getHospital());
                    nuevo.setEspecialidad(consulta.getEspecialidad());
                    nuevo.setSector(sector);
                    return repoGestoresDeColas.save(nuevo);
                });
        gestor.agregarConsultaMedicaALaCola(consulta);
        repoGestoresDeColas.save(gestor);
        EntradaCola entrada = repoEntradasCola.findByConsultaMedicaId(consulta.getId())
                .orElseGet(() -> {
                    EntradaCola nueva = new EntradaCola();
                    nueva.setGestorDeCola(gestor);
                    nueva.setConsultaMedica(consulta);
                    nueva.setOrdenRelativo(proximoOrdenRelativo(gestor.getId()));
                    nueva.setFechaHoraIngreso(LocalDateTime.now());
                    return nueva;
                });
        if (gestor.getId() == null || entrada.getGestorDeCola() == null
                || !gestor.getId().equals(entrada.getGestorDeCola().getId())) {
            entrada.setGestorDeCola(gestor);
            entrada.setOrdenRelativo(proximoOrdenRelativo(gestor.getId()));
        }
        entrada.setEstado(EstadoEntradaCola.EN_COLA);
        entrada.setPrioridad(gestor.obtenerPrioridad(prioridad));
        repoEntradasCola.save(entrada);
        return estimacionAtencionService.calcularPara(consulta);
    }

    private void validarSectorOperativo(Sector sector) {
        if (sector == null || !sector.isActiva() || sector.isArchivado()) {
            throw new ConflictoDeEstadoException("El sector está inactivo y no admite nuevas consultas");
        }
    }

    private long proximoOrdenRelativo(Long gestorId) {
        return repoEntradasCola
                .findFirstByGestorDeColaIdOrderByOrdenRelativoDesc(gestorId)
                .map(actual -> actual.getOrdenRelativo() + 1)
                .orElse(1L);
    }
}
