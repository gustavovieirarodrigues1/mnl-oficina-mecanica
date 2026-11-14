$ErrorActionPreference = 'Stop'
$repo = Split-Path $PSScriptRoot -Parent
Push-Location $repo
try {
    docker info --format '{{.ServerVersion}}'
    if ($LASTEXITCODE -ne 0) { throw 'Inicie o Docker Desktop e aguarde a engine Linux ficar pronta.' }
    New-Item -ItemType Directory -Force logs | Out-Null
    if (-not (Test-Path observability/.env)) {
        $password = 'Oficina-' + [guid]::NewGuid().ToString('N').Substring(0, 16)
        "GRAFANA_ADMIN_PASSWORD=$password" | Set-Content observability/.env -Encoding utf8NoBOM
    }
    if (-not (Test-Path target/mechanic-workshop-system-1.1.0.jar)) {
        & .\mvnw.cmd -B -DskipTests package
        if ($LASTEXITCODE -ne 0) { throw 'Falha no build.' }
    }
    docker compose -f docker-local/docker-compose.yaml up -d
    if ($LASTEXITCODE -ne 0) { throw 'Falha ao iniciar banco/Kafka. Confira portas e containers existentes.' }
    docker compose --env-file observability/.env -f observability/compose.demo.yaml up -d --build
    if ($LASTEXITCODE -ne 0) { throw 'Falha ao iniciar a stack. Confira se a porta 8080 esta livre.' }
    $ready = $false
    for ($i = 0; $i -lt 60; $i++) {
        try {
            $health = Invoke-RestMethod 'http://127.0.0.1:8080/actuator/health' -TimeoutSec 5
            if ($health.status -eq 'UP') { $ready = $true; break }
        } catch { }
        Start-Sleep -Seconds 2
    }
    if (-not $ready) { throw 'Aplicacao nao ficou saudavel. Consulte docker compose -f observability/compose.demo.yaml logs application.' }
    Write-Host 'Aplicacao UP. Grafana: http://localhost:3000/d/workshop-observability'
    Write-Host 'Usuario: admin. Senha local: consulte observability/.env (nao versionado).'
    Write-Host 'Gere dados: ./observability/demo-traffic.ps1'
} finally { Pop-Location }
