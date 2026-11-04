#Requires -Version 5.1
<#
.SYNOPSIS
Starts the local backend with Maven, without an IDE.
.EXAMPLE
.\scripts\start_backend.ps1
.EXAMPLE
.\scripts\start_backend.ps1 -Port 8081 -MaxHeapMb 1024 -NoDockerCompose
.EXAMPLE
.\scripts\start_backend.ps1 -CheckOnly
#>
[CmdletBinding()]
param(
    [ValidateRange(1, 65535)]
    [int]$Port = 8080,

    [ValidateRange(256, 8192)]
    [int]$MaxHeapMb = 768,

    [switch]$NoDockerCompose,

    [switch]$CheckOnly
)

Set-StrictMode -Version 2.0
$ErrorActionPreference = 'Stop'

$projectRoot = Split-Path -Parent $PSScriptRoot
$previousJavaHome = $env:JAVA_HOME
$previousMavenOpts = $env:MAVEN_OPTS
$locationPushed = $false
$scriptExitCode = 1

try {
    $mavenWrapper = Join-Path $projectRoot 'mvnw.cmd'
    if (-not (Test-Path -LiteralPath $mavenWrapper -PathType Leaf)) {
        throw 'No se encontro mvnw.cmd. Conserva este script dentro de scripts/ del repositorio.'
    }

    $jdkDirectory = $env:JAVA_HOME
    if ([string]::IsNullOrWhiteSpace($jdkDirectory)) {
        $javacCommand = Get-Command javac.exe -CommandType Application -ErrorAction SilentlyContinue |
            Select-Object -First 1
        if ($null -eq $javacCommand) {
            throw 'Instala un JDK 21 y configura JAVA_HOME con su carpeta, o agrega su bin al PATH.'
        }
        $jdkDirectory = Split-Path -Parent (Split-Path -Parent $javacCommand.Source)
    }
    $jdkDirectory = $jdkDirectory.Trim('"')
    foreach ($requiredFile in @('bin\java.exe', 'bin\javac.exe', 'release')) {
        if (-not (Test-Path -LiteralPath (Join-Path $jdkDirectory $requiredFile) -PathType Leaf)) {
            throw 'JAVA_HOME debe apuntar a un JDK completo, no a un JRE ni a una carpeta bin.'
        }
    }
    $javaRelease = Get-Content -LiteralPath (Join-Path $jdkDirectory 'release') -Raw
    $javaVersion = [regex]::Match($javaRelease, '(?m)^JAVA_VERSION="([^"]+)"')
    if (-not $javaVersion.Success -or [int]($javaVersion.Groups[1].Value.Split('.')[0]) -lt 21) {
        throw 'El proyecto requiere Java 21 o superior. Revisa JAVA_HOME.'
    }

    # Only change the current process environment; restore it on exit.
    $env:JAVA_HOME = $jdkDirectory
    if ([string]::IsNullOrWhiteSpace($env:MAVEN_OPTS)) {
        $env:MAVEN_OPTS = '-Xms64m -Xmx512m'
    }

    $jvmArguments = "-Xms128m -Xmx${MaxHeapMb}m -Dspring.devtools.restart.enabled=false -Dspring.devtools.livereload.enabled=false"
    $composeEnabled = 'true'
    if ($NoDockerCompose) {
        $composeEnabled = 'false'
    }
    # Application arguments take precedence over .env and environment settings.
    # Never inherit the project's destructive create-drop default for this launcher.
    $applicationArguments = @(
        "--server.port=$Port"
        '--server.address=0.0.0.0'
        '--spring.jpa.hibernate.ddl-auto=update'
        '--spring.jpa.show-sql=false'
        '--spring.jpa.properties.hibernate.format_sql=false'
        "--spring.docker.compose.enabled=$composeEnabled"
        '--spring.docker.compose.lifecycle-management=start-only'
    ) -join ' '
    $mavenArguments = @(
        '--batch-mode'
        '--no-transfer-progress'
        '-Dmaven.test.skip=true'
        "-Dspring-boot.run.jvmArguments=$jvmArguments"
        "-Dspring-boot.run.arguments=$applicationArguments"
        'spring-boot:run'
    )

    Push-Location -LiteralPath $projectRoot
    $locationPushed = $true
    Write-Host "Proyecto: $projectRoot"
    Write-Host "Java: $($javaVersion.Groups[1].Value)"
    Write-Host "Backend: http://localhost:$Port (tambien escucha en la red local)"
    Write-Host "Heap maximo del backend: $MaxHeapMb MB; DevTools desactivado."
    Write-Host 'Base de datos: ddl-auto=update; no se ejecutan tests ni seeds.'
    if ($NoDockerCompose) {
        Write-Host 'PostgreSQL debe estar iniciado; Spring no administrara Docker Compose.'
    } else {
        Write-Host 'Docker Desktop debe estar iniciado. PostgreSQL se conserva al cerrar el backend.'
    }

    if ($CheckOnly) {
        Write-Host 'Verificacion correcta. No se inicio Maven, Docker ni el backend.'
        $scriptExitCode = 0
    } else {
        Write-Host 'Spring cargara el .env de la raiz. Para detener el backend: Ctrl+C.'
        & $mavenWrapper @mavenArguments
        $scriptExitCode = $LASTEXITCODE
    }
} catch {
    Write-Host ("No se pudo iniciar el backend: " + $_.Exception.Message) -ForegroundColor Red
    $scriptExitCode = 1
} finally {
    if ($locationPushed) {
        Pop-Location
    }
    $env:JAVA_HOME = $previousJavaHome
    $env:MAVEN_OPTS = $previousMavenOpts
}

exit $scriptExitCode
