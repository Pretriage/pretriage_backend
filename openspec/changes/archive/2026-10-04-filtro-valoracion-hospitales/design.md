# Design

## Context

`GET /api/hospitales/cercanos` (`HospitalController:34`) orquesta `AtencionHospitalService.buscarHospitalesCercanos:84`: busca lugares cercanos vía `GooglePlacesService.buscarHospitales` (Nearby Search, radio 5 km, máximo 20), filtra por especialidad contra la DB, enriquece con tiempos/colas y ordena según `ordenarPor`. La valoración de Google no se solicita hoy: `NEARBY_FIELD_MASK` (`GooglePlacesService.java:62`) solo pide `places.id,displayName,formattedAddress,location,types`, y ni `GooglePlaceDTO` ni `HospitalCercanoDTO` tienen campos de valoración.

El orden combinado está hardcodeado para el par `distancia|tiempo-atencion` (`AtencionHospitalService.java:144-168`): suma la posición de Google (distancia) con la posición según `comparatorPorTiempoAtencion()`. Ver proposal.md para la motivación y specs/filtro-valoracion-hospitales/spec.md para los requisitos.

## Goals / Non-Goals

**Goals:**

- Exponer `valoracionPromedio` y `cantidadValoraciones` por hospital en la respuesta.
- Soportar `valoracion` como criterio de orden individual y combinado, reutilizando la semántica existente de suma de rankings.
- Mantener la forma de respuesta (`List<HospitalCercanoDTO>`) y el comportamiento de lista vacía ya contratado.

**Non-Goals:**

- Persistir valoraciones en entidades JPA ni sincronizarlas periódicamente (la valoración se lee en vivo de Google en cada request, como ya se hace con distancia y tiempos).
- Agregar un filtro independiente de valoración (mínimo, solo valorados) más allá del orden.
- Envolver la respuesta en un objeto con metadata (mensaje vacío a cargo del frontend).

## Decisions

**D1: Fuente de datos en vivo desde Google Places (no persistir en `Hospital`).**
La valoración solo se usa para presentar y ordenar el listado; no interviene en lógica clínica ni de cola. Persistir exigiría entidad/campos nuevos, política de sincronización y regeneración del diagrama de dominio — complejidad que la historia no pide. Alternativa descartada: campos `rating`/`userRatingCount` en `Hospital` con job de refresh (más partes móviles y datos potencialmente desactualizados).

**D2: Agregar `places.rating` y `places.userRatingCount` solo a `NEARBY_FIELD_MASK`.**
El listado cercano es el único flujo que necesita la valoración; `DETAILS_FIELD_MASK` (Place Details) no se toca. `GooglePlaceDTO` gana `rating` (`Double`, escala de Google 1.0–5.0) y `userRatingCount` (`Integer`), y `mapearAHospitalCercanoDTO` los copia al DTO de respuesta. Ausencia de datos → `null` en el campo ausente del DTO (el frontend la distingue de un puntaje real); el ordenamiento la trata como 0.

**D3: `valoracion` entra a `ORDENES_VALIDOS` con un comparador propio.**
`comparatorPorValoracion()`: `valoracionPromedio` DESC (nulls últimos ≡ tratarse como 0 → final), `cantidadValoraciones` DESC, nombre del hospital (el devuelto por Google) ASC; nombres iguales o ausentes conservan el orden de Google (estable). La validación de `ordenarPor` (`:95-102`) es case-sensitive exacta sobre los valores de `ORDENES_VALIDOS`, por lo que `Valoracion` y variantes ya caen en el rechazo 400 sin cambios adicionales. El parámetro ausente o vacío se resuelve como `distancia` en el controller (`HospitalController:40`, `defaultValue`), tal como hoy.

**D4: Generalizar la rama de orden combinado.**
Hoy `:144-168` maneja un único par fijo. La propuesta: para cada criterio pedido construir un orden (distancia = posición del orden de Google, tiempo = `comparatorPorTiempoAtencion`, valoración = `comparatorPorValoracion`) y sumar posiciones por `placeId`, con nombre ASC y luego orden de Google como desempate final. Con un criterio solo se aplica su comparador directo. Si cualquier criterio es inválido, la validación previa (`:97-102`) ya responde 400 antes de ordenar, incluso cuando otro criterio de la misma combinación sea válido. Esto reemplaza el `if (porTiempo && porDistancia)` por lógica extensible y cubre `valoracion`, `valoracion|distancia` y los tres combinados con la misma rama. Alternativa descartada: sumar ramas `if` por par (crece cuadráticamente y ya rompió la mantenibilidad).

**D5: Sin cambios de contrato en la respuesta.**
El endpoint sigue devolviendo `List<HospitalCercanoDTO>`; los campos nuevos son aditivos. El mensaje "sin hospitales valorados" es responsabilidad del frontend: se aclarará en `docs/06-api-reference.md` y `docs/12-hospital-selection-and-arrival.md` que, cuando todos los `valoracionPromedio` vienen `null`, el frontend muestra el mensaje correspondiente (decisión acordada; no es un requisito del backend).

## Risks / Trade-offs

- [Costo SKU de Google Places por los campos nuevos] → verificado al implementar (2026-10): `places.rating`/`places.userRatingCount` suben el mask de Nearby Search del tier Pro al tier **Enterprise** (se factura el SKU más alto del mask; Enterprise incluye 1.000 requests gratis/mes). Resultado documentado en el Javadoc de `GooglePlacesService.NEARBY_FIELD_MASK`. Como abre un SKU más caro que el tier Pro, se informa al usuario antes de mergear para que confirme que el costo es aceptable; si no lo fuera, la mitigación es degradar a no pedirlos y suspender el criterio (no hay fallback alternativo barato).
- [Orden combinado generalizado toca lógica existente de `distancia|tiempo-atencion`] → cubrir con los tests existentes de orden combinado (`AtencionHospitalServiceTest:681`, `:725`) que deben seguir pasando sin cambios, más nuevos tests para las combinaciones con `valoracion`.
- [Valoraciones volátiles (cambian entre requests)] → aceptado: es el mismo carácter de distancia/tiempos ya en vivo; no hay requisito de consistencia entre llamadas.
- [Hospitales pequeños sin reseñas quedan sistemáticamente al final] → comportamiento pedido por la historia (tratar como 0); el frontend muestra "sin valoraciones" y el paciente decide.

## Migration Plan

Despliegue sin migraciones ni cambios de contrato: los campos nuevos son aditivos y el frontend mobile puede adoptarlos de forma incremental. Rollback = revertir el código (el endpoint vuelve a los criterios previos).

## Open Questions

- ~~Verificar al implementar que `places.rating` y `places.userRatingCount` no abren un SKU de Google Places más caro que el tier Pro actual (D2).~~ **Resuelto (2026-10):** sí abren uno nuevo (tier Enterprise, más caro que Pro); el resultado está documentado en el Javadoc de `GooglePlacesService.NEARBY_FIELD_MASK` y se informa al usuario antes de mergear — no cambia specs ni diseño, solo puede afectar la decisión de seguir adelante.
