$ErrorActionPreference = 'Stop'
$repo = Split-Path $PSScriptRoot -Parent
$sample = Get-Content (Join-Path $repo 'logs/demo-request.json') -Raw | ConvertFrom-Json
$started = [DateTimeOffset]$sample.generatedAt
$range = @{ from = [string]$started.AddMinutes(-1).ToUnixTimeMilliseconds(); to = [string]([DateTimeOffset]::Now.AddMinutes(1).ToUnixTimeMilliseconds()) }
$tracePane = @{ range = $range; datasource = 'tempo'; queries = @(@{query = $sample.traceId; queryType = 'traceql'}) }
$logPane = @{ range = $range; datasource = 'loki'; queries = @(@{expr = ('{{job="workshop"}} | json | traceId="{0}"' -f $sample.traceId); refId = 'A'}) }
$traceUrl = 'http://localhost:3000/explore?left=' + [uri]::EscapeDataString(($tracePane | ConvertTo-Json -Depth 8 -Compress))
$logUrl = 'http://localhost:3000/explore?left=' + [uri]::EscapeDataString(($logPane | ConvertTo-Json -Depth 8 -Compress))
$dashboardUrl = "http://localhost:3000/d/workshop-observability?from=$($range.from)&to=$($range.to)"
$links = @{ generatedAt = (Get-Date).ToString('o'); traceId = $sample.traceId; dashboard = $dashboardUrl; logs = $logUrl; trace = $traceUrl; alerts = 'http://localhost:9090/alerts'; targets = 'http://localhost:9090/targets'; health = 'http://localhost:8080/actuator/health' }
$links | ConvertTo-Json | Set-Content (Join-Path $repo 'logs/demo-links.json') -Encoding utf8NoBOM
@"
# Telas prontas da demonstracao

Dados gerados em $($sample.generatedAt). Trace: $($sample.traceId).

| Momento | Tela pronta |
| --- | --- |
| 0:00 a 1:00 | [Dashboard com o periodo dos dados fixado]($dashboardUrl) |
| 1:00 a 1:20 | [Log HTTP no Loki, ja filtrado pelo trace]($logUrl) |
| 1:20 a 1:40 | [Trace correspondente no Tempo]($traceUrl) |
| 1:40 a 2:10 | [Regras de alerta](http://localhost:9090/alerts) |
| 2:10 a 2:30 | [Voltar ao dashboard]($dashboardUrl) |

Apoio: [Target UP](http://localhost:9090/targets) e [Health](http://localhost:8080/actuator/health).

Grafana: usuario admin; senha em observability/.env. Os links fixam o periodo para os dados nao desaparecerem ao passar de quinze minutos. Os traces tem retencao de 24 horas; gere outra rodada depois desse prazo.
"@ | Set-Content (Join-Path $repo 'logs/demo-links.md') -Encoding utf8NoBOM
Write-Host 'Links prontos em logs/demo-links.md'
