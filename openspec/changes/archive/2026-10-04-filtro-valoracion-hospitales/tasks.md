# Tasks

## 1. Google Places: traer y mapear la valoración

- [x] 1.1 Agregar `places.rating,places.userRatingCount` a `NEARBY_FIELD_MASK` en `GooglePlacesService` y verificar contra la tabla de precios de Google Places (Nearby Search) que no abren un SKU más caro que el tier Pro actual (ver design.md D2/Open Questions); dejar el resultado documentado en el PR
- [x] 1.2 Agregar `rating` (`Double`) y `userRatingCount` (`Integer`) a `GooglePlaceDTO` y mapearlos a `valoracionPromedio`/`cantidadValoraciones` en `HospitalCercanoDTO` dentro de `mapearAHospitalCercanoDTO` (ausentes → `null`), verificando con test de mapeo en `GooglePlacesServiceTest` que los campos llegan del JSON de Google
- [x] 1.3 Extender los fixtures WireMock de `GooglePlacesServiceTest` con `rating`/`userRatingCount` y agregar test de hospital sin valoración (campos `null`), verificando que ambos tests pasan con `./mvnw.cmd "-Dtest=GooglePlacesServiceTest" test`

## 2. Orden por valoración en el servicio

- [x] 2.1 Agregar `"valoracion"` a `ORDENES_VALIDOS` en `AtencionHospitalService` e implementar `comparatorPorValoracion()` (promedio DESC con `null` ordenado como 0 → final, cantidad DESC, nombre de Google ASC, nombres iguales conservando el orden de Google), verificando con test nuevo en `AtencionHospitalServiceTest` que `ordenarPor=valoracion` ordena de mayor a menor con los hospitales sin valoración al final
- [x] 2.2 Generalizar la rama de orden combinado (`AtencionHospitalService.java:144-168`) para sumar rankings de cualquier subconjunto de criterios (distancia = posición Google, tiempo = `comparatorPorTiempoAtencion`, valoración = `comparatorPorValoracion`, desempate por nombre ASC y luego orden de Google), verificando que los tests existentes de combinado (`ordenaConPuntuacionCombinadaSumandoRankings`, `rechazaCombinacionesInvalidasDeOrdenarPor`) pasan sin cambios
- [x] 2.3 Agregar tests de combinaciones con valoración (`valoracion|distancia` y `distancia|tiempo-atencion|valoracion`), de que `ordenarPor=valoracion` nunca vacía el resultado, de case-sensitividad (`Valoracion` → 400) y de combinación con criterio inválido (`valoracion|foo` → 400 aunque el otro criterio sea válido), verificando con `./mvnw.cmd "-Dtest=AtencionHospitalServiceTest" test` (incluye los tests previos de rechazo de `ordenarPor` inválido)

## 3. Documentación

- [x] 3.1 Actualizar `docs/06-api-reference.md` (sección Nearby Hospitals) con `valoracion` como valor de `ordenarPor` (exacto, en minúsculas; variantes → 400; ausente/vacío → default `distancia`), sus combinaciones, los campos nuevos `valoracionPromedio` (1.0–5.0, `null` sin valoración) / `cantidadValoraciones` del DTO, y la aclaración de que el mensaje "sin hospitales valorados" lo muestra el frontend cuando todos los `valoracionPromedio` son `null`, verificando que el path del endpoint documentado coincide con `HospitalController`
- [x] 3.2 Actualizar `docs/12-hospital-selection-and-arrival.md`, `docs/04-queue-and-estimation.md` (fórmula de rankings ahora con tres criterios, desempate por nombre) y `docs/ai-agent-context.md` con el criterio de valoración, la semántica `null` = sin valoraciones y la responsabilidad del frontend sobre el mensaje de "sin valoraciones", verificando que la semántica de lista vacía documentada no cambió

## 4. Verificación final

- [x] 4.1 Compilar todo con `./mvnw.cmd test -DskipTests` y correr `./mvnw.cmd "-Dtest=AtencionHospitalServiceTest,GooglePlacesServiceTest" test` sin fallos
- [x] 4.2 Correr `openspec validate "filtro-valoracion-hospitales" --type change` y confirmar que los requisitos del delta quedaron cubiertos por los tests agregados
