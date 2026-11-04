# Proposal

## Why

El paciente no puede priorizar los hospitales mejor referenciados al momento de elegir institución: el listado cercano solo se ordena por distancia o tiempo de atención, y la valoración de Google Places (API de Google Maps: puntaje promedio y cantidad de reseñas) no se solicita ni se expone. Se necesita ahora para que la selección se apoye también en la reputación de la atención.

## What Changes

- `GET /api/hospitales/cercanos` acepta un nuevo criterio en `ordenarPor`: `valoracion`, combinable con `distancia` y `tiempo-atencion` mediante `|` (misma semántica de suma de rankings que los criterios existentes).
- `GooglePlacesService` agrega `places.rating` y `places.userRatingCount` al field mask de Nearby Search y los mapea al DTO de respuesta.
- `HospitalCercanoDTO` expone dos campos nuevos junto a cada hospital: `valoracionPromedio` (flotante 1.0–5.0, escala de Google Places) y `cantidadValoraciones`; cuando Google no provee valoración el campo ausente se devuelve `null`, para que el frontend mobile lo renderice y lo distinga de un puntaje real.
- Los hospitales sin valoración de Google se ordenan como puntaje 0 y quedan al final del listado; no se excluyen.
- Se generaliza la rama de orden combinado hoy hardcodeada para `distancia|tiempo-atencion` de modo que soporte cualquier subconjunto de los criterios válidos.
- El mensaje "sin hospitales valorados" sigue siendo responsabilidad del frontend (lo deriva de los campos nuevos cuando todos vienen `null`); se aclara en `docs/06-api-reference.md` y `docs/12-hospital-selection-and-arrival.md`, y el backend mantiene la respuesta como `List<HospitalCercanoDTO>` sin envolver.

No hay cambios de contrato en la forma de la respuesta, ni cambios JPA, ni migraciones.

## Capabilities

### New Capabilities

- `filtro-valoracion-hospitales`: selección de hospitales por el paciente: listado cercano filtrado por especialidad, criterios de orden (`distancia`, `tiempo-atencion`, `valoracion`) y datos expositos por hospital para elegir institución.

### Modified Capabilities

<!-- Ninguna: hospital-metrics (métricas de administración) y pretriage-chat-link (vínculo consulta-chat) no cambian. -->

## Impact

- **Código**: `GooglePlacesService` (field mask + mapeo), `GooglePlaceDTO`, `HospitalCercanoDTO`, `AtencionHospitalService` (`ORDENES_VALIDOS`, comparador de valoración, generalización del orden combinado).
- **API**: valores aceptados de `ordenarPor` y campos del DTO en `GET /api/hospitales/cercanos`; la forma de la respuesta (lista) no cambia.
- **Dependencias externas**: Google Places API (New) Nearby Search — `places.rating`/`places.userRatingCount` suben el field mask al tier Enterprise (verificado 2026-10: más caro que el tier Pro de `displayName`; ver Javadoc de `GooglePlacesService.NEARBY_FIELD_MASK`); se informa al usuario antes de mergear.
- **Tests**: `AtencionHospitalServiceTest` (orden y combinados), `GooglePlacesServiceTest` (fixtures WireMock con rating y ausencia de campos).
- **Docs**: `docs/06-api-reference.md`, `docs/12-hospital-selection-and-arrival.md`, `docs/04-queue-and-estimation.md`, `docs/ai-agent-context.md`. Sin regeneración de `docs/generated/domain-model.*` (no hay cambios en entidades JPA).
