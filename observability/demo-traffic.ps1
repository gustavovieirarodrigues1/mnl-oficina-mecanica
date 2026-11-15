param([int]$Requests = 120)
$ErrorActionPreference = 'Stop'
$repo = Split-Path $PSScriptRoot -Parent
New-Item -ItemType Directory -Force (Join-Path $repo 'logs') | Out-Null
$traceId = [guid]::NewGuid().ToString('N')
$headers = @{ traceparent = "00-$traceId-0123456789abcdef-01" }
Invoke-WebRequest 'http://127.0.0.1:8080/' -Headers $headers | Out-Null
@{ traceId = $traceId; generatedAt = (Get-Date).ToString('o') } |
    ConvertTo-Json | Set-Content (Join-Path $repo 'logs/demo-request.json') -Encoding utf8NoBOM
for ($i = 0; $i -lt $Requests; $i++) {
    Invoke-WebRequest 'http://127.0.0.1:8080/' | Out-Null
    if ($i % 10 -eq 0) {
        # Uma rejeicao de autenticacao esperada: 401 nao e erro interno 5xx.
        Invoke-WebRequest 'http://127.0.0.1:8080/owners' -SkipHttpErrorCheck | Out-Null
    }
    Start-Sleep -Milliseconds 500
}
& (Join-Path $PSScriptRoot 'demo-links.ps1')
Write-Host "Trace da demonstracao: $traceId"
Write-Host 'Aguarde 30 segundos e atualize o Grafana com intervalo Last 15 minutes.'
