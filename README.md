# PreTriage Backend

Backend de PreTriage construido con Java 21, Spring Boot y PostgreSQL.

## Requisitos para desarrollo local

- Java 21.
- Docker Desktop, para ejecutar PostgreSQL mediante `compose.yaml`.
- Una aplicación Auth0 configurada para el entorno de desarrollo.
- Ollama con `llama3.2:3b` es recomendable para probar la clasificación con IA;
  si no responde, el flujo de recepción utiliza el clasificador determinístico de
  respaldo.

## Variables de entorno

Spring Boot importa automáticamente el archivo `.env` ubicado en la raíz del
repositorio. Este archivo no debe incluirse en Git.

Configuración mínima:

```env
AUTH0_M2M_CLIENT_ID=
AUTH0_M2M_CLIENT_SECRET=
AUTH0_M2M_SCOPE=read:users update:users delete:users create:users
AUTH0_APP_CLIENT_ID=
GOOGLE_MAPS_API_KEY=
```

### Correo de invitaciones

Por defecto se utiliza `LocalInvitationEmailAdapter`: no requiere SMTP y devuelve
el secreto una sola vez al administrador para probar el enlace localmente.

Para enviar correos con `SmtpInvitationEmailAdapter`:

```env
PRETRIAGE_INVITATIONS_EMAIL_MODE=smtp
PRETRIAGE_FRONTEND_BASE_URL=http://localhost:3000
PRETRIAGE_INVITATIONS_EMAIL_FROM=no-reply@tu-dominio.com
SMTP_HOST=localhost
SMTP_PORT=1025
SMTP_USERNAME=
SMTP_PASSWORD=
SMTP_AUTH=false
SMTP_STARTTLS=false
```

Mailpit puede utilizarse localmente en el puerto SMTP `1025`. Cuando el modo es
`smtp`, el backend nunca incluye el token de invitación en la respuesta. Si falla
la entrega, la invitación queda pendiente y puede reenviarse desde el panel; el
reenvío rota el token y vence el enlace anterior.

## Ejecutar localmente

Desde PowerShell, en la raíz del backend:

```powershell
.\iniciar-backend.cmd
```

También podés abrir `iniciar-backend.cmd` con doble clic. Requiere Java 21 y
Docker Desktop iniciado; usa el Maven Wrapper, carga `.env` desde la raíz y
arranca sin IDE ni DevTools, con un heap máximo de 768 MB para el backend.
El script fija `ddl-auto=update` para conservar los datos y no ejecuta tests
ni seeds. Detenelo con **Ctrl+C**; PostgreSQL queda iniciado.

Opciones: `-Port 8081`, `-MaxHeapMb 1024`, `-NoDockerCompose` (base ya iniciada)
y `-CheckOnly` (verificación sin arrancar servicios). Ver
[desarrollo local](docs/07-local-development.md#start-without-an-ide-windows).

El backend queda disponible en:

- API: `http://localhost:8080`
- OpenAPI: `http://localhost:8080/v3/api-docs`
- Swagger UI: `http://localhost:8080/swagger-ui/index.html`

Para conservar los datos locales, definí `JPA_DDL_AUTO=update` en `.env` antes
del primer arranque. El valor por defecto actual de `application.properties` es
`create-drop`, que elimina el esquema al cerrar y lo recrea al iniciar.
Spring DevTools también puede reiniciar el backend al compilar `target/classes`
(por ejemplo, al ejecutar Maven tests), por lo que esa compilación puede borrar
la base de una instancia que sigue usando `create-drop`. Cambiar `.env` no
protege el cierre de una instancia ya iniciada con ese modo: resguardá los datos
antes de detenerla. Ver [desarrollo local](docs/07-local-development.md).

## Almacenamiento S3 opcional

Amazon S3 guarda los archivos de estudios clínicos. El backend permite subirlos,
descargarlos y eliminarlos; los médicos pueden descargarlos mediante:

```http
GET /api/medico/pacientes/{pacienteId}/historial-clinico/{estudioId}/archivo
```

S3 está desactivado por defecto. Los flujos de recepción, triaje, cola y atención
médica pueden ejecutarse localmente sin variables AWS.

Si S3 está desactivado y se intenta descargar un archivo clínico, el backend
responde con un error explícito indicando que el almacenamiento no está disponible
en ese entorno.

Para habilitarlo, agregar al `.env`:

```env
PRETRIAGE_STORAGE_S3_ENABLED=true
AWS_S3_REGION=us-east-1
AWS_ACCESS_KEY_ID=
AWS_SECRET_ACCESS_KEY=
```

El cliente usa la cadena de credenciales del SDK cuando las propiedades locales
`aws.accessKeyId` y `aws.secretAccessKey` están vacías. En AWS se utiliza un rol de
EC2, sin claves estáticas. Los permisos requeridos sobre los objetos del bucket
configurado por `aws.s3.bucket-name` son `s3:GetObject`, `s3:PutObject` y
`s3:DeleteObject`. La base guarda las claves de objeto, actualmente `UUID.ext`.

## Demo temporal en AWS

La configuración de despliegue está separada del entorno de desarrollo:

- [Estado del despliegue y pendientes](docs/30-aws-demo-status.md).
- [Despliegue y costos de la demo](docs/28-aws-demo-deployment.md).
- [Variables, salud, S3 y CORS del backend](docs/27-aws-backend-runtime.md).
- [Compatibilidad de Amplify, frontend, móvil y Cloudflare](docs/29-aws-frontend-and-dns.md).
- [Carga de usuarios y datos sintéticos para la demo](docs/31-demo-data-seeding.md).
- [Publicación automática del backend desde la rama produccion](docs/32-github-backend-deployment.md).

`Dockerfile` construye una imagen Java 21 sin credenciales locales.
`compose.aws.yaml` ejecuta PostgreSQL 18, backend y Caddy. La infraestructura de
`infra/aws/demo-stack.yaml` prepara EC2, una Elastic IP, S3 privado y ECR;
aplicarla crea recursos facturables. El cómputo se puede detener conservando los
datos y la dirección IP; almacenamiento y la Elastic IP siguen sujetos a sus
tarifas. El frontend Next.js 15.5.28 se desplegó en Amplify Hosting compute y
`app.pretriage.com.ar` responde por HTTPS con certificado válido y Amplify
informa `AVAILABLE`. La demo usa una base nueva; la carga
inicial de datos y usuarios queda para una etapa posterior.

## Verificación

Compilar sin ejecutar pruebas:

```powershell
.\mvnw.cmd test -DskipTests
```

Ejecutar la suite completa con una base desechable:

```powershell
python scripts/test_backend_isolated.py
```

Las pruebas de integración requieren PostgreSQL/Docker disponible. S3 permanece
desactivado durante las pruebas salvo que se habilite explícitamente.

Validar la imagen AWS sin usar la base ni las claves de la aplicación:

```powershell
python scripts/test_aws_container.py --build
```

## Integración continua

GitHub Actions ejecuta `.github/workflows/ci.yml` en pull requests y pushes a
`desarrollo`, `main` y `produccion`. El check `Backend CI / build-and-test` levanta un PostgreSQL
aislado, desactiva el arranque automático de Docker Compose dentro de Spring y
ejecuta:

```bash
./mvnw --batch-mode --no-transfer-progress clean verify
```

Configurar `Backend CI / build-and-test` como status check obligatorio en las
reglas de protección de `desarrollo` y `main` evita integrar una rama mientras la
compilación o las pruebas estén pendientes o fallen.

La demo EC2 tiene [horarios diarios de encendido y apagado](docs/33-aws-instance-schedule.md)
a las 10:00 y 22:00 de Buenos Aires; son independientes del pipeline.
