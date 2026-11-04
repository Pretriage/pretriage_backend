# Tasks

## 1. Vínculo consulta-chat y vida múltiple de chats

- [x] 1.1 Reemplazar `ConsultaMedica.chat: List<Mensaje>` por `@OneToOne @JoinColumn(name = "id_chat") Chat chat` y cambiar `Chat.paciente` de `@OneToOne` a `@ManyToOne`; verificar que `./mvnw.cmd test -DskipTests` compila sin referencias rotas al mapping viejo
- [x] 1.2 Ampliar `AtencionHospitalService.finalizarTriageEIngresarACola` con parámetro `Chat` (manteniendo overloads existentes con `chat = null`) y setear `consultaMedica.setChat(chat)` junto a `setResumenPretriageJson`; verificar con `./mvnw.cmd "-Dtest=AtencionHospitalServiceTest" test` añadiendo aserción `assertSame(chat, consulta.getChat())`
- [x] 1.3 Actualizar `ChatService.enviarMensaje` para pasar el chat a `finalizarTriageEIngresarACola`; verificar con `./mvnw.cmd "-Dtest=ChatServiceTest" test` tras actualizar los 6 mocks de firma (3→4 args)
- [x] 1.4 Implementar en `ChatService.iniciarChat` que siempre cree un chat nuevo cerrando los chats abiertos previos del paciente (nueva derived query `findAllByPacienteUsuarioAuthIdAndFinalizadoFalse` en `RepoChat`); verificar con tests nuevos: crea chat nuevo, los chats abiertos previos quedan con `finalizado = true` y a lo sumo queda un chat abierto (ejecutar `./mvnw.cmd "-Dtest=ChatServiceTest" test` en verde)
- [x] 1.5 Añadir la validación de atención pendiente en `iniciarChat`: nueva excepción con mensaje apto para el paciente, mapeada a 400 en `GlobalExceptionHandler`, lanzada cuando el paciente tiene una `ConsultaMedica` con chat vinculado cuya `EntradaCola` (`findByConsultaMedicaId`) no esté en `FINALIZADA`/`CANCELADA`; la validación corre antes de cerrar chats previos. Verificar con tests: bloquea con `EN_COLA` (responde 400 y no crea ni cierra chats), permite con `FINALIZADA`, permite con `CANCELADA` y permite sin chat vinculado (ejecutar `./mvnw.cmd "-Dtest=ChatServiceTest,GlobalExceptionHandlerTest" test`)
- [x] 1.6 Regenerar `docs/generated/domain-model.md` y `docs/generated/domain-model.mmd` con `python scripts/generate_domain_diagram.py` y verificar que refleja el vínculo `ConsultaMedica` ↔ `Chat`

## 2. Queries con date-range para métricas

- [x] 2.1 Añadir a `RepoEntradasCola` la proyección de los timestamps de `fechaHoraIngreso` de los ingresos del hospital en rango de fechas (el conteo de ingresos es el tamaño de la lista) y test de repositorio que la ejecute con datos seed (verificar 60 timestamps en el período y 0 fuera)
- [x] 2.2 Añadir a `RepoAtencionesMedicas` la proyección de atenciones finalizadas cuya `EntradaCola` pertenece al hospital y cayó en el rango, con `entrada.fechaHoraIngreso`, `fechaHoraInicio` y `fechaHoraFin` (para espera promedio y serie diaria) y test que verifica que solo devuelve finalizadas del hospital y período dados
- [x] 2.3 Añadir a `RepoConsultasMedicas` las queries de distribución: `nivelDeGravedadBot` agrupada, `nivelDeGravedadMedico` agrupada y conteos de `chat IS NULL / IS NOT NULL`, todas restringidas a las consultas de los ingresados del rango; test con datos donde los totales cierran con los ingresos (los atendidos del embudo salen de la proyección de 2.2)

## 3. Endpoint de métricas

- [x] 3.1 Crear el DTO de respuesta `MetricasHospitalResponse` con la estructura aniada del contrato (D10): `hospitalId`, `desde`/`hasta` (`LocalDate`), `ingresaronACola`, `pacientesAtendidos`, `pacientesNoAtendidos`, `esperaPromedioMinutos`, `porcentajeAtendidos`, `distribucionGravedadBot` y `distribucionGravedadMedico` como arrays de `DistribucionNivelItem {nivel, cantidad, porcentaje}` con `SIN_REVISION` al final en la versión médico, `pretriageRealizado`, `pretriageNoRealizado`, `porcentajePretriageRealizado` y `serieDiaria` como array de `SerieDiariaItem {fecha, ingresados, atendidos, esperaPromedioMinutos}`; verificar compilación
- [x] 3.2 Crear `MetricasHospitalService` con `exigirAdminHospital` como primer paso, validación `desde <= hasta`, conversión LocalDate→rango, cálculo de espera promedio en Java (D6), serie diaria con zero-fill (D11) y armado de la respuesta con porcentajes redondeados a 1 decimal; verificar con `MetricasHospitalServiceTest`: embudo, cierre de totales, espera promedio, arrays de orden fijo con todos los buckets presentes, `sum(serieDiaria) = totales`, null cuando el denominador es 0, rango invertido → 400 y `AccessDeniedException` para hospital ajeno
- [x] 3.3 Crear `MetricasHospitalController` con `GET /api/admin/hospitales/{hospitalId}/metricas?desde&hasta`; verificar con test del controller (o E2E) que responde 200 para admin del hospital y 403 para otro usuario
- [x] 3.4 Documentar el endpoint en `docs/06-api-reference.md` (sección `/api/admin/hospitales/...`) con el ejemplo JSON canónico, la tabla campo→tipo→null y el mapeo métrica→gráfico sugerido (tomados del contrato de `specs/hospital-metrics/spec.md`), y verificar que la ruta y los códigos documentados coinciden con el controller

## 4. Documentación y verificación integral

- [x] 4.1 Actualizar `docs/ai-agent-context.md` con el vínculo consulta↔chat, la regla de "pretriage realizado", el comportamiento de `iniciarChat` (siempre chat nuevo, cierra previos, 400 por atención pendiente) y el nuevo endpoint con su contrato de respuesta (campos, invariantes de cierre y serie diaria); verificar que las rutas de endpoints documentados coinciden con la implementación
- [x] 4.2 Documentar en `docs/06-api-reference.md` el comportamiento de `POST /api/chat` (siempre chat nuevo, cierre de chats abiertos previos y 400 por atención pendiente) y en `docs/02-patient-flow.md` la regla de bloqueo; verificar que las rutas y códigos documentados coinciden con la implementación
- [x] 4.3 Ejecutar `./mvnw.cmd test -DskipTests` y verificar compilación limpia
- [x] 4.4 Ejecutar `./mvnw.cmd "-Dtest=ChatServiceTest,AtencionHospitalServiceTest,GlobalExceptionHandlerTest,MetricasHospitalServiceTest" test` y verificar suite en verde
- [x] 4.5 Ejecutar la suite completa `./mvnw.cmd test` (requiere Docker Desktop) y verificar sin regresiones
