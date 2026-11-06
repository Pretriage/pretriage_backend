# Estado de la demo AWS

Última operación verificada el **05/11/2026**: carga integral aplicada, acceso
comprobado para las 29 cuentas demo, catálogo de 16 obras sociales y tableros
con datos de los 8 hospitales. Ver [datos y validaciones](#datos-y-validaciones).

## Infraestructura histórica del 31/10/2026

Estado de infraestructura verificado el 31/10/2026. El backend estaba **en
ejecución** por pedido del usuario; el frontend se había desplegado
correctamente en Amplify. La sección de infraestructura y la imagen ECR de
abajo se conservan como referencia histórica de esa fecha, no como descripción
de la imagen actualmente desplegada.
`app.pretriage.com.ar` responde por HTTPS con certificado válido y Amplify
informa `AVAILABLE`.

La instancia se detuvo y volvió a iniciar para comprobar el arranque en frío;
conservó la Elastic IP `52.1.161.207` y el volumen
`vol-07be65d0e72b72b10`. Tras una detención previa, el usuario pidió dejarla
encendida y volvió a arrancar. Está **RUNNING**, SSM está Online y
`https://api.pretriage.com.ar/actuator/health/readiness` respondió HTTP `200`
con estado `UP`. La Elastic IP y el almacenamiento continúan facturándose; el
cómputo también se factura mientras EC2 permanezca encendida.

## Componentes desplegados (referencia histórica del 31/10/2026)

| Componente | Estado |
| --- | --- |
| CloudFormation `pretriage-demo` | `UPDATE_COMPLETE`, incluida la Elastic IP elegida por el usuario |
| EC2 | `i-02cb7756f580fa419`, actualmente `RUNNING`, `t3a.medium` (2 vCPU, 4 GiB), volumen raíz cifrado gp3 de 24 GiB con retención al eliminar el stack |
| IPv4 pública | Elastic IP `52.1.161.207`, administrada por el stack; se conservó al detener e iniciar EC2 |
| Contenedores | El inicio automático por `pretriage.service` completó sin intervención manual: PostgreSQL 18 y backend `healthy`, Caddy en ejecución |
| Acceso operativo | SSM Online; sin acceso SSH configurado |
| S3 | `pretriage-demo-clinicalfilesbucket-45qsffs15mc2`, privado, cifrado SSE-S3 y versionado; la instancia accede mediante su rol |
| ECR (imagen documentada al 31/10; histórica) | `827641190526.dkr.ecr.us-east-1.amazonaws.com/pretriage-demo-backendimagerepository-92vmgwg0evhc:demo-e153b1f`; digest `sha256:38f3d3209a6f4f04a12dfc65bd3c19586b4071b8bdf34537b59604927d09921b` |
| API | Tras el arranque en frío, `https://api.pretriage.com.ar/actuator/health/readiness` respondió HTTP `200` y `UP` por Cloudflare; SSL/TLS Full (strict) y proxy DNS habilitados |

El usuario eligió una Elastic IP para conservar la dirección pública al detener
y volver a iniciar EC2. El registro A proxied de Cloudflare se actualizó una vez
a `52.1.161.207`. El DNS final resolvió el error `522`: readiness respondió
HTTP `200` y `UP` por HTTPS después del arranque en frío. Las paradas normales
no requieren cambiar DNS. La reserva de IPv4 continúa facturándose mientras EC2
está apagada. El helper mantiene opcionalmente la actualización explícita de
Cloudflare; ver [despliegue del backend](28-aws-demo-deployment.md).

La actualización agregó la dirección y su asociación; el plan validado marcó
`Replacement: False` para EC2. En el ciclo de parada e inicio, la instancia
volvió a asociar la misma Elastic IP y mantuvo adjunto el mismo volumen raíz.
`pretriage.service` estaba habilitado y su inicio automático terminó con código
0; PostgreSQL y backend quedaron saludables sin iniciar manualmente los
contenedores. La prueba readiness HTTPS posterior confirmó el acceso por el
dominio final.

La imagen del backend fue publicada en el repositorio ECR. La autenticación
temporal de ECR se limpió después de la publicación. El paquete temporal de
configuración y las versiones temporales del certificado subidos a S3 se
eliminaron; no se dejaron secretos de aplicación en S3. La copia local del
runtime está bajo `target/aws-deploy/runtime`, ignorada por Git y restringida al
usuario local. En EC2, el archivo de entorno y la clave privada del certificado
tienen permisos `600`; el certificado público tiene permisos `644`.

## Repositorios

Los cambios de backend y frontend que se documentaron el 31/10/2026 se habían
publicado en la rama `desarrollo` de sus respectivos repositorios. En ese
registro, el backend desplegado correspondía al commit
`e153b1f4369ac6f60d922a0a6864e9b0c76a49d1` y el frontend al commit
`2732268ed9734e02654e2eb8b5e1d9790113dae4`. La imagen ECR de ese despliegue
histórico correspondía al commit de backend `e153b1f`.

Los checks de GitHub Actions del código publicado pasaron en
[backend](https://github.com/MatiasCanevaro/pretriage_backend/actions/runs/41233853414)
y [frontend](https://github.com/MatiasCanevaro/pretriage_frontend_web/actions/runs/41233812125).

## Frontend en Amplify

Amplify Hosting se creó en `us-east-1` (App ID `dy8mupnkj7a55`) para el repositorio
`Pretriage/pretriage_frontend_web`, con plataforma `WEB_COMPUTE` y la variable
`BACKEND_API_URL` apuntando a `https://api.pretriage.com.ar`. El despliegue de la
rama `desarrollo`, job 1, terminó `SUCCEED` el 31/10/2026 (20:22:45 UTC). La URL
de Amplify está disponible en
[desarrollo.dy8mupnkj7a55.amplifyapp.com](https://desarrollo.dy8mupnkj7a55.amplifyapp.com).

La asociación personalizada actual es `app.pretriage.com.ar` para la rama
`desarrollo`. Sus CNAME están configurados en Cloudflare como DNS only; la raíz
`/` y `/login` respondieron HTTP 200 y el certificado TLS es válido para
`*.pretriage.com.ar` y `pretriage.com.ar`, con vencimiento el 17/05/2027. En la
última consulta, Amplify informó `AVAILABLE` con `verified=true`; la aplicación
responde por el hostname. El CloudFront
`403` reportado anteriormente no se reprodujo en esta verificación final.
Amplify no tiene reglas personalizadas de reescritura (`customRules=[]`), por lo
que se conserva el enrutamiento SSR de Next.js.

La metadata de Amplify confirma el repositorio y la rama configurados; la
lectura de la API de GitHub para verificar el contenido actual de ese repositorio
no estuvo disponible durante esta revisión.

## APK Android para la demo

Se compiló un APK de depuración firmado desde el repositorio móvil, sin cambios
en sus archivos versionados:

```powershell
./gradlew.bat :composeApp:assembleDebug -PBACKEND_BASE_URL=https://api.pretriage.com.ar --console=plain
```

La salida se capturó en un log temporal y se detuvo Gradle al finalizar. Pasaron
la compilación, la inspección del paquete y la verificación de la firma. La URL
generada y empaquetada es `https://api.pretriage.com.ar`; el cliente deriva
`wss://api.pretriage.com.ar/api/chat/{id}/voz` para voz. Esto no sustituye una
prueba autenticada desde un dispositivo.

El archivo local, excluido de Git, es
`target/aws-deploy/android/pretriage-android-debug-api-pretriage-com-ar.apk`
(82.285.073 bytes), con SHA-256
`cb081358ebcd686b6e65408aa175bbdf44fbd4511df3de238f14eed620f4c0e0`.

## Datos y validaciones

### Actualización del 05/11/2026

La carga AWS de demo se aplicó con `seed_key` `demo-caba-v1`, después de renovar
la sesión AWS. EC2 ya estaba encendida por su horario diario y no se inició ni
detuvo durante esta tarea. PostgreSQL confirmó 8 hospitales, 16 obras sociales,
29 identidades Auth0 nuevas, 32 filas `usuario_auth` (se conservaron 3), 646
pacientes (640 históricos, 3 asociados a cuentas demo y 3 previos), 2.600
consultas y entradas de cola, 2.208 atenciones finalizadas, 392 canceladas,
1.716 chats con pretriage, 40 sectores, 80 salas, 32 membresías, 16 sesiones
activas en Clínica Médica y Traumatología, y 480 sesiones históricas finalizadas.
El periodo de datos es 06/10–04/11/2026.

Las comprobaciones por API confirmaron que `GET /api/obrasocial` devuelve 16 y
que una cuenta de administración hospitalaria puede consultar métricas de los
ocho hospitales para ese periodo. La API devolvió 2.600 ingresos, 2.208
atendidos, 392 no atendidos y 1.716 con pretriage. Pasaron 38 pruebas Python.

El backup PostgreSQL previo a la carga se conserva en EC2 como
`/opt/pretriage/backups/pre-seed-20261105.XSUICz.sql.gz`, con modo `600` y gzip
verificado. Se eliminó la versión exacta del objeto temporal S3 usado para la
carga. El estado privado de Auth0 está en
`target/demo-seed/demo-caba-v1/state.json` y tiene una copia duradera con ACL
solo para el usuario en
`C:/Users/valen/.pretriage/demo-seed/demo-caba-v1/state.json`; el archivo
incluye secretos y no debe copiarse a logs ni repositorios.

El primer intento de `--verify-logins` recibió HTTP 403 con el agente genérico
de Python. Tras identificar el cliente como `PretriageDemoSeed/1.0`, las 29
cuentas completaron el login por HTTPS y devolvieron el subject esperado.
No se ejecutaron pruebas de consulta ni voz con Gemini durante esta carga;
el escenario ya dispone de médicos demo activos para realizarlas. Ver
[guía de carga de datos sintéticos](31-demo-data-seeding.md).

### Historial de validaciones al 31/10/2026

En la verificación del 31/10 no se habían cargado usuarios ni datos clínicos y
la base contenía el catálogo inicial de especialidades. El 04/11 se verificaron
desde Android el acceso autenticado, perfil, visualización de un estudio,
especialidades y mapa; la API devolvió cero hospitales disponibles y cero obras
sociales. Posteriormente se agregaron scripts SQL manuales al repositorio. La
prueba de seed descrita entonces cubría generación aislada y persistencia
PostgreSQL antes de la aplicación real del 05/11.

La plantilla pasó `cfn-lint` y las comprobaciones seleccionadas de `cfn-guard`;
dos patrones estructurales produjeron falsos positivos documentados. CloudTrail
dispone del historial de eventos de 90 días, pero no se configuró un trail pago.
Las comprobaciones públicas por HTTPS se repitieron después del arranque con
la IP fija y el DNS definitivo y pasaron: readiness `200`, Actuator
restringido `401`, ruta de voz con cabeceras Upgrade sin token `401`, ruta SSE
sin token `401`, preflight del origen autorizado `200` y origen desconocido
`403`. Esto verifica alcance y control de acceso; la sesión real de voz con
Gemini y la entrega de SSE autenticada quedan pendientes de la carga de usuarios.

La prueba de IMDSv2 desde el contenedor del backend devolvió el rol esperado,
sin exponer tokens ni credenciales. Una comprobación desde la instancia, sin
credenciales estáticas, confirmó `PutObject`, `GetObject` y `DeleteObject` con
cifrado AES256. Se retiraron el objeto de prueba, sus versiones y marcadores
de borrado; no se tocaron archivos de la aplicación.

## Operación y costos

EventBridge Scheduler administra ahora los [horarios diarios](33-aws-instance-schedule.md):
encendido a las 10:00 y apagado a las 22:00, hora de Buenos Aires, todos los días.
Una parada manual conserva esos horarios; para mantener apagada la demo hay que
deshabilitarlos antes del siguiente encendido.

Consultar el estado y operar la instancia con el script documentado en
[despliegue del backend](28-aws-demo-deployment.md):

```powershell
./scripts/aws-demo.ps1 -Action status -Profile <perfil>
./scripts/aws-demo.ps1 -Action stop -Profile <perfil>
./scripts/aws-demo.ps1 -Action start -Profile <perfil>
```

Al detener EC2 dejan de cobrarse sus horas de cómputo. La Elastic IP, el volumen
EBS retenido, el bucket/versiones S3 y las imágenes ECR permanecen y pueden
generar cargos. Las referencias de esta región son USD 0,0376 por hora de EC2
activa, USD 0,005 por hora de IP reservada (unos USD 3,65 al mes), y USD 1,92
al mes por el volumen de 24 GiB; S3, ECR, transferencia, servicios de IA y Amplify no
están incluidos. Ver el detalle y las fuentes en
[despliegue del backend](28-aws-demo-deployment.md#costos-para-planificar).

El stack conserva el volumen raíz y el bucket está versionado. El helper ofrece
acciones de consulta, inicio y parada; no elimina recursos. Cualquier limpieza
debe revisar expresamente los datos retenidos y las versiones del bucket antes
de borrar recursos.

La configuración de DNS, frontend y Caddy se describe en
[frontend y DNS](29-aws-frontend-and-dns.md).
