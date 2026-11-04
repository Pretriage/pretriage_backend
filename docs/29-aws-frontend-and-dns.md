# Despliegue web y DNS

Esta guía documenta la configuración web, móvil y DNS de la demo. Amplify ya
desplegó la rama `desarrollo`; `app.pretriage.com.ar` sirve la aplicación por
HTTPS con certificado válido y Amplify informa `AVAILABLE`. La API responde en
su hostname de Cloudflare.

El [estado real de la demo y los pendientes](30-aws-demo-status.md) registra
la infraestructura creada después de esta preparación.

## Frontend web y soporte de Next.js

El frontend web se adapta a Next.js 15.5.28 para Amplify Hosting compute,
con App Router, Route Handlers, cookies de sesión y middleware Node.js.
Requiere SSR; no es una exportación estática. La carga de datos y usuarios de
la base nueva queda expresamente para después.

La documentación de AWS Amplify Hosting declara soporte SSR para Next.js 12 a
15, incluye middleware y lista middleware Edge como no soportado. La versión
anterior del proyecto, Next.js 16.4.3, quedaba fuera de ese soporte documentado.
El usuario eligió aplicar la adaptación a Next.js 15 y continuar con Amplify.
La validación del adaptador del hosting sigue requiriendo una vista previa.
[Soporte de Next.js en Amplify](https://docs.aws.amazon.com/amplify/latest/userguide/ssr-amplify-support.html)

### Alcance de la adaptación a Next.js 15

La inspección de `pretriage_frontend_web` no encontró `use cache`, `connection`,
`cacheComponents`, `reactCompiler`, `after`, `node:crypto`, configuración
Next.js 16 en `next.config.ts`, ni usos síncronos de cookies o parámetros.
`cookies()` y `searchParams` ya se usan de forma asíncrona, como requiere Next.js
15. React 19 es compatible con Next.js 15 según la guía oficial de migración.

La adaptación aprobada comprende estos cambios:

1. En `package.json`, alinear `next` y `eslint-config-next` en la misma versión
   15.5.28 y actualizar `package-lock.json`. La versión anterior era 16.4.3;
   React y React DOM se conservan en `~19.2.4`, compatibles con la versión elegida.
2. Renombrar `proxy.ts` a `middleware.ts` y exportar `middleware` en lugar de
   `proxy`. El matcher y el uso de `NextRequest`/`NextResponse` corresponden a
   middleware de Next.js.
3. El código de renovación de sesión decodifica el JWT con `Buffer` en
   `proxy.ts`. Next.js 15.5 habilitó estable el runtime Node.js para middleware;
   se puede declarar `runtime: "nodejs"` en `config`. Alternativamente, se
   puede reemplazar la decodificación de `Buffer` por APIs web, pero Amplify
   declara que Edge middleware no está soportado. La documentación de Amplify
   no confirma expresamente la compatibilidad del adaptador SSR con middleware
   Node.js 15.5; no se debe dar por compatible hasta que pase una vista previa
   de Amplify con renovación de sesión real.
4. Adaptar `eslint.config.mjs`: los presets de `eslint-config-next` 16 usados
   aquí son arrays flat config; `eslint-config-next` 15 expone presets legacy.
   En la copia de validación, `FlatCompat` de `@eslint/eslintrc` permitió
   conservar ESLint 9 y ambos presets. `@eslint/eslintrc` 3.3.5 se declara
   explícitamente como dependencia de desarrollo.
5. Next.js 15.5 cambió `tsconfig.json` de `jsx: "react-jsx"` a
   `jsx: "preserve"` durante el build y regeneró `next-env.d.ts` para referir
   `.next/types/routes.d.ts` en lugar de los tipos `.next/dev/types` de Next 16.
   Incluir y revisar estos cambios generados si se adopta la versión 15.

La migración es pequeña a mediana: paquetes/lockfile, `middleware.ts`,
`eslint.config.mjs` y el ajuste de `tsconfig.json`. La comprobación de
compatibilidad del adaptador sigue pendiente; la vulnerabilidad transitiva
descrita abajo se corrige con un override comprobado. Next.js 15 usa Webpack por defecto mientras Next.js 16 usa
Turbopack por defecto; no hay una configuración Webpack propia en este frontend.
Fuentes: [guía oficial de actualización a Next.js 15](https://nextjs.org/docs/app/guides/upgrading/version-15),
[middleware de Next.js 15](https://nextjs.org/docs/15/pages/api-reference/file-conventions/middleware),
[Next.js 15.5](https://nextjs.org/blog/next-15-5) y
[guía de actualización a Next.js 16](https://nextjs.org/docs/app/guides/upgrading/version-16).

### Aplicación en el repositorio web

El ajuste se aplicó al repositorio real `pretriage_frontend_web`. Pasaron
`npm ci`, typecheck, ESLint, las tres pruebas de contratos y el build de
producción de Next.js 15.5.28. La auditoría del lockfile registra 16 avisos
moderados y ninguno alto. Se agregó `amplify.yml` en su raíz y se actualizó la
documentación del frontend para el middleware y la limitación de MCP.

El servidor de desarrollo se restablece en el puerto 3000. La comprobación
local cubre `/login` y la redirección de una ruta protegida sin sesión;
los flujos autenticados completos quedan para después de la carga de datos y
usuarios, conforme a la decisión del usuario. No se publicaron cambios Git ni
se crearon recursos AWS durante esta adaptación.

### Evaluación previa en copia aislada

Se creó una copia temporal en `target/next15-compat` sin `.env*`, `node_modules`,
`.next` ni `.git`; no se modificó el repo web original. En esa copia, Next.js y
`eslint-config-next` 15.5.28 compilaron con React/React DOM 19.2.4. Después de
convertir el middleware y la configuración ESLint, pasaron `npm run typecheck`,
`npm run lint`, los tres casos de `npm run test:contracts` y `npm run build`.
El manifiesto del build registra `/_middleware` con runtime `nodejs` y los
matchers de sesión esperados.

La primera auditoría de la copia Next.js 15 encontró 17 moderadas y una alta:
`postcss` 8.4.31 bajo `node_modules/next/node_modules/postcss`. Para evaluar una
corrección acotada se agregó solo en la copia el override de npm
`"overrides": { "next": { "postcss": "8.5.23" } }`, igual a la versión anidada
del lockfile Next.js 16 original. La segunda auditoría encontró 16 moderadas y
ninguna alta. El override cubre el aviso de PostCSS con arreglo incompleto hasta
8.5.22 ([GHSA-fxqj-rqcc-2cmp](https://github.com/advisories/GHSA-fxqj-rqcc-2cmp));
no se corrigieron automáticamente los avisos moderados restantes ni se afirma
que el árbol completo esté libre de vulnerabilidades. Como la versión de
PostCSS queda fijada por una regla propia del proyecto; hay que conservarla y
volver a revisar compatibilidad/auditoría al actualizar
Next.js.

Con el override, la copia aislada pasó typecheck, lint, las tres pruebas de
contrato y `next build`. El manifiesto de funciones del build contiene
`/_middleware` con runtime `nodejs` y seis matchers. El build confirma la
compilación de Next.js 15 con middleware Node. La rama `desarrollo` ya se
desplegó en Amplify, y `/` y `/login` responden HTTP 200 por HTTPS en
`app.pretriage.com.ar`. Quedan pendientes las comprobaciones autenticadas de
login, refresco/rotación de cookies y respuestas de API.

La adaptación actualiza `pretriage_frontend_web/AGENTS.md` y su documentación
vigente. `docs/integration-2026-10-25.md` permanece como historial fechado.
Next.js 15 no ofrece el endpoint MCP integrado de Next.js 16: no usar
`/_next/mcp` como comprobación de runtime de esta versión.

El archivo de build se agrega como `pretriage_frontend_web/amplify.yml`;
el backend conserva [una copia de referencia](../infra/aws/amplify.yml.example).
Amplify espera ese archivo en la raíz del repo web. Antes del build se debe definir `BACKEND_API_URL` en el
entorno de build y escribirlo en `.env.production`: el entorno de build no
garantiza por sí mismo que la variable esté disponible cuando se ejecute SSR.
No agregar secretos ni variables `NEXT_PUBLIC_*` a ese archivo. El build usa
Node.js 22, `npm ci`, `npm run build`, artefactos `.next` y la caché de Next.
No hace falta una regla SPA `/<*> -> /index.html`; las rutas y Route Handlers
deben seguir bajo el runtime de Next.js.

## Dominios y flujo de solicitudes

Usar estos nombres:

- Web: `https://app.pretriage.com.ar`.
- API: `https://api.pretriage.com.ar`.

Configurar `BACKEND_API_URL=https://api.pretriage.com.ar` en el frontend web,
sin `/api` al final. El navegador llama al mismo origen web y los Route
Handlers pasan las solicitudes al backend; la app móvil accede directamente a
la API. El origen del backend se termina en Caddy y se reenvía al servicio
Docker `backend:8080`, dentro de la red Compose. Spring Boot no debe publicar
un puerto del host.

Para Android, compilar con
`-PBACKEND_BASE_URL=https://api.pretriage.com.ar` desde el repositorio móvil:

```powershell
./gradlew.bat :composeApp:assembleDebug -PBACKEND_BASE_URL=https://api.pretriage.com.ar
```

El parámetro de Gradle tiene prioridad sobre `local.properties` y genera la URL
para ese APK; no modifica la configuración local de desarrollo. Capturar la
salida en un log temporal y detener Gradle al terminar, como indica el
`AGENTS.md` móvil. El cliente móvil deriva WSS
para `/api/chat/{chatId}/voz` y usa HTTPS/SSE para
`/api/atencion/tiempos/suscribirse/{consultaId}`. La app Android declara permiso
de micrófono, pero el manifiesto todavía permite HTTP sin cifrar: las builds de
release deben usar siempre el origen HTTPS. La captura de voz y el
almacenamiento de tokens de iOS siguen como implementaciones vacías; iOS no está
listo para producción con estos flujos.

## DNS y certificados en Cloudflare

Conservar los nameservers existentes de Cloudflare. La asociación actual de
Amplify es `app.pretriage.com.ar`, vinculada a `desarrollo`. Mantener el destino
de aplicación y los CNAME de verificación de Amplify como **DNS only** (nube
gris); el hostname responde HTTP 200 y presenta un certificado válido. Amplify
informa `AVAILABLE` y el dominio está verificado. No activar proxy
para estos registros ni cambiar una opción global de proxy o redirección que
afecte otros nombres. No configurar otros dominios ni redirecciones.

Crear `api.pretriage.com.ar` como un registro A a la Elastic IP asociada a EC2 y
mantenerlo **Proxied** (nube naranja). La API usa un certificado Cloudflare
Origin CA, que cifra el tramo Cloudflare-origen y funciona con `Full (strict)`;
los navegadores no confían directamente en ese certificado. Por eso el nombre
API debe permanecer proxied y no se debe cambiar a DNS only. [Cloudflare Origin CA](https://developers.cloudflare.com/ssl/origin-configuration/origin-ca/)
y [Full (strict)](https://developers.cloudflare.com/ssl/origin-configuration/ssl-modes/full-strict/).

La Elastic IP asignada debe mantenerse asociada a la instancia al detenerla;
así el registro A no requiere cambios en cada inicio. Configurar ese registro
una vez con la dirección comunicada por CloudFormation.

Crear el certificado Origin CA para `api.pretriage.com.ar` y guardar sus PEM en
el host:

```text
/opt/pretriage/certs/origin.pem
/opt/pretriage/certs/origin-key.pem
```

La clave privada debe quedar con propietario `root:root` y modo `600`. La
distribución de archivos del host coloca `compose.aws.yaml`, `Caddyfile`, el
archivo de entorno y el directorio `certs/` juntos en `/opt/pretriage`, que
coincide con el bind mount `./certs:/etc/caddy/certs:ro` de Compose. Caddy carga
ambos archivos desde `/etc/caddy/certs/`.

Configurar el SSL/TLS de Cloudflare en `Full (strict)`, activar WebSockets y
crear una regla de caché que omita `/api/*`. Aplicar la redirección HTTP a HTTPS
con una regla limitada al hostname `api.pretriage.com.ar`, no con un ajuste
global de zona. Caddy ya reenvía WebSocket al servicio `backend:8080` y usa
`flush_interval -1` para enviar SSE inmediatamente; confirmar SSE con heartbeat
y WSS desde fuera de la red. Cloudflare puede reiniciar conexiones WebSocket,
por lo que las pruebas deben cubrir una reconexión del cliente. Fuentes:
[WebSockets de Cloudflare](https://developers.cloudflare.com/network/websockets/),
[proxy DNS](https://developers.cloudflare.com/dns/proxy-status/) y
[reglas de caché](https://developers.cloudflare.com/cache/how-to/cache-rules/).

## Acceso de red a la instancia

La instancia acepta TCP 443 solo desde los rangos IPv4 oficiales de Cloudflare
(15 rangos verificados para el diseño actual). Mantener la lista sincronizada
con [las direcciones IP de Cloudflare](https://www.cloudflare.com/ips/) si
cambia. No abrir 22, 80, 8080 ni 5432: administrar la instancia con AWS Systems
Manager Session Manager. El puerto 443 es el único ingreso web a Caddy; el
puerto 8080 queda privado en la red Docker. El origen público de la instancia
no debe ser usado directamente por navegador o app móvil; el registro proxied
de Cloudflare es el punto de entrada.

## Lista de verificación de la migración y despliegue

- [x] Elegir Next.js 15.5.28 para continuar con Amplify.
- [x] Alinear paquetes y React, usar middleware Node y adaptar ESLint.
- [x] Validar el despliegue de `runtime: "nodejs"` en Amplify.
- [x] Ejecutar en el repo web `npm ci`, `npm run typecheck`, `npm run lint`,
      `npm run test:contracts` y `npm run build`.
- [ ] En la vista previa, comprobar rutas SSR, login, refresco/rotación de
      cookies, expiración de sesión y los Route Handlers `/api/*`.
- [ ] Configurar `BACKEND_API_URL` y confirmar que no se publique en bundles
      `NEXT_PUBLIC_*`.
- [x] Configurar los CNAME de `app.pretriage.com.ar` en DNS only y verificar
      HTTP 200, TLS válido y estado `AVAILABLE` en Amplify.
- [ ] Mantener `api.pretriage.com.ar` proxied; validar Full (strict), Caddy,
      bypass de caché `/api/*`, WebSocket de voz, SSE y heartbeats.
- [ ] Confirmar que el Security Group solo permita TCP 443 desde la lista
      vigente de Cloudflare y que Session Manager administra la instancia.
- [ ] Compilar Android con `BACKEND_BASE_URL=https://api.pretriage.com.ar`;
      completar las implementaciones iOS antes de anunciar soporte para iOS.

## Referencias de implementación

- Web: `pretriage_frontend_web/package.json`, `next.config.ts`, `.env.example`,
  `lib/api/server.ts`, `middleware.ts`, `amplify.yml` y `app/api/`.
- Infraestructura: `compose.aws.yaml` y `infra/aws/Caddyfile`.
- Móvil: `pretriage_frontend_mobile/composeApp/build.gradle.kts`,
  `composeApp/src/commonMain/kotlin/com/proyecto_final/triage/network/ChatVozCliente.kt`,
  `composeApp/src/commonMain/kotlin/com/proyecto_final/triage/network/estadoConsulta/ConsultaSseClient.kt`,
  `composeApp/src/androidMain/AndroidManifest.xml` y las implementaciones iOS
  de `audio/AudioVoz.ios.kt` y `storage/TokenStorage.kt`.
- Backend: [perfil runtime de AWS](27-aws-backend-runtime.md), que documenta
  la autenticación y los endpoints de salud.
