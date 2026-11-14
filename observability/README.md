# Observabilidade da oficina

Para a demonstracao local com todos os componentes em containers e sem bind mounts do Windows, use [o guia e roteiro de 2min30](DEMONSTRACAO.md). O `demo-start.ps1` preserva o schema existente, usa um volume compartilhado para os logs e inicia a stack de `compose.demo.yaml`.

O monolito publica metricas Micrometer/Prometheus, logs JSON ECS e traces OpenTelemetry via OTLP/HTTP. A stack local provisiona Prometheus, Grafana, Loki, Alloy e Tempo. O dashboard inclui throughput, erros 5xx, latencia p95, memoria JVM, pool JDBC, duracao Kafka e logs com link para traces.

Esta entrega instrumenta o servico existente. Para rastrear um fluxo inteiro entre microsservicos, todos os participantes precisam extrair e propagar o mesmo contexto W3C. A separacao dos microsservicos e a implementacao da Saga sao trabalhos independentes.

## Executar localmente

Requisitos: Java 21, Docker Desktop com engine Linux ativa e portas 3000, 3200, 4318, 5432, 8080, 9090 e 9092 disponiveis. Execute os comandos na raiz do repositorio. O perfil `local` existente recria o schema do banco; use apenas o banco de desenvolvimento.

```powershell
docker compose -f docker-local/docker-compose.yaml up -d
$env:GRAFANA_ADMIN_PASSWORD = 'defina-uma-senha-local'
docker compose -f observability/compose.yaml up -d
.\mvnw.cmd spring-boot:run '-Dspring-boot.run.profiles=local,observability'
```

Em outro terminal:

```powershell
Invoke-RestMethod http://localhost:8080/actuator/health
Invoke-WebRequest http://localhost:8080/actuator/prometheus
Invoke-WebRequest http://localhost:8080/
```

- Grafana: http://localhost:3000, usuario `admin`, senha definida no ambiente. Abra **Oficina - Observabilidade**.
- Prometheus: http://localhost:9090/targets e http://localhost:9090/alerts.
- Grafana Explore / Tempo: busque `{ resource.service.name = "mechanic-workshop-system" }` (TraceQL).
- Grafana Explore / Loki: `{job="workshop"} | json`. O campo derivado **TraceID** abre o trace; Tempo tambem oferece o caminho de volta para os logs.

Aguarde pelo menos dois intervalos de coleta (30 segundos). Metricas Kafka aparecem depois de produzir/consumir eventos; pool JDBC requer banco ativo. As janelas de taxa usam cinco minutos. Traces ficam retidos por 24 horas; metricas e logs, sete dias. Logs locais giram em 10 MB com limite total de 100 MB. Volumes preservam os dados entre reinicios. `docker compose -f observability/compose.yaml down` encerra a stack sem apagar os volumes.

Alloy le os arquivos `logs/*.json` gerados pelo perfil adicional `observability`, sem montar o socket Docker. No perfil padrao, os logs JSON vao apenas para stdout, para coleta pela infraestrutura do ambiente.

## Configuracao por ambiente

| Variavel | Padrao | Uso |
| --- | --- | --- |
| `SPRING_APPLICATION_NAME` | `mechanic-workshop-system` | Nome unico de cada servico nos traces e metricas |
| `APP_ENVIRONMENT` | `local` | Tag de ambiente nas metricas |
| `TRACING_EXPORT_ENABLED` | `false` | Ativa o envio OTLP sem exigir o perfil local |
| `OTEL_EXPORTER_OTLP_TRACES_ENDPOINT` | `http://localhost:4318/v1/traces` | URL completa do receptor OTLP/HTTP |
| `TRACING_SAMPLING_PROBABILITY` | `0.1` | Amostragem fora do perfil de demonstracao |
| `GRAFANA_ADMIN_PASSWORD` | obrigatoria no Compose | Senha do Grafana local |

O perfil `observability` habilita exportacao e amostragem de 100% para demonstracao. Fora dele, configure as variaveis de tracing no Deployment. A aplicacao nao depende do backend de telemetria para atender requisicoes.

Health e Prometheus permitem coleta sem JWT; detalhes internos do health ficam ocultos. Os demais caminhos Actuator sao bloqueados pela seguranca e apenas health/Prometheus sao expostos. No ambiente Kubernetes, mantenha `/actuator` fora do Ingress publico e permita coleta somente pela rede interna. As portas publicadas pelo Compose ficam limitadas ao loopback.

O log de acesso contem metodo, rota normalizada, status e duracao. O contexto de tracing acrescenta `traceId` e `spanId`. Nao sao registrados corpo, query string ou cabecalhos pelo filtro de acesso. Rotas ainda nao resolvidas (por exemplo, rejeicao pela seguranca) usam `UNMATCHED`; IDs de OS/clientes nao sao labels de metricas ou Loki.

## HTTP e Kafka distribuidos

O Spring instrumenta as requisicoes HTTP recebidas. `KafkaConfig` habilita Observation explicitamente no `KafkaTemplate` e nas propriedades do listener, pois ambos sao criados manualmente. Assim, spans de envio/consumo podem compartilhar o contexto `traceparent` nos headers Kafka.

Nos outros servicos, habilite Observation tambem e use nomes de aplicacao diferentes. Clientes HTTP devem ser construidos a partir de `RestClient.Builder`, `RestTemplateBuilder` ou `WebClient.Builder` gerenciados pelo Spring. Lambdas e outros runtimes precisam da instrumentacao OpenTelemetry correspondente. Tokens de aprovacao nao devem ser incluidos em logs ou atributos de spans.

## Alertas e investigacao

As regras ficam em `prometheus/alerts.yml`:

- `WorkshopUnavailable`: coleta falha durante um minuto. Verifique processo, endpoint e conectividade.
- `WorkshopHighErrorRate`: mais de 5% de respostas 5xx por cinco minutos, com pelo menos 20 requisicoes na janela. Inspecione logs e traces com erro.
- `WorkshopHighLatency`: p95 HTTP acima de dois segundos por cinco minutos, com pelo menos 20 requisicoes na janela. Investigue spans lentos, banco e Kafka.

Os alertas ficam visiveis no Prometheus; esta stack nao configura envio de notificacoes. Para notificacoes externas, conecte o Alertmanager do ambiente aos canais da equipe.

## Kubernetes

`kubernetes/servicemonitor.yaml` usa o seletor real `app: workshop-backend` do repositorio de infraestrutura. Requer Prometheus Operator e o namespace `oficina-mecanica` existentes. Ajuste `metadata.labels.release` para o release Helm do Prometheus e confira `serviceMonitorSelector`/`serviceMonitorNamespaceSelector` da instalacao antes de aplicar. Nao ha deploy remoto automatico nesta entrega.

No Deployment da aplicacao, configure `TRACING_EXPORT_ENABLED=true`, a URL interna OTLP terminando em `/v1/traces`, `APP_ENVIRONMENT` e a amostragem desejada. Use `/actuator/health/liveness` e `/actuator/health/readiness` nas probes HTTP da porta 8080; readiness inclui o banco e liveness nao depende dele. Provisione dashboards/datasources e regras equivalentes na stack do cluster. Para logs do cluster, colete stdout JSON com o agente ja instalado, em vez do leitor de arquivos local.

## Validacao e demonstracao

```powershell
.\mvnw.cmd '-Dtest=ObservabilityIntegrationTest' test
.\mvnw.cmd '-Dtest=*Test,!*IntegrationTest,!CucumberTest' test
docker compose -f observability/compose.yaml config --quiet
docker compose -f observability/compose.yaml exec prometheus promtool check config /etc/prometheus/prometheus.yml
docker compose -f observability/compose.yaml exec prometheus promtool check rules /etc/prometheus/alerts.yml
```

O teste HTTP com servidor real verifica propagacao de `traceparent`, correlacao e ausencia da query secreta no log, histograma Prometheus, tags, probes e protecao dos endpoints. Um terceiro teste usa o produtor Kafka em memoria para verificar a injecao de `traceparent` com o mesmo trace pai. Os testes usam uma aplicacao sem banco e sem broker; nao comprovam exportacao ao Tempo nem o consumo por outro servico real.

Validacao realizada em 11/11/2026: 515 testes unitarios e os tres testes de observabilidade passaram, sem falhas. A configuracao Compose e o JSON do dashboard foram validados. A engine Docker estava indisponivel; a stack, o `promtool`, os testes existentes que dependem de containers e o fluxo distribuido real nao foram executados. Os relatorios locais ficam em `target/surefire-reports`; nao foi medida a cobertura completa nesta execucao.

Validacao adicional em 14/11/2026: a stack de `compose.demo.yaml` foi executada no Docker. Foram confirmados health UP, coleta Prometheus UP, validacao de configuracao e das tres regras pelo promtool, dashboard renderizado, logs no Loki e trace HTTP no Tempo aberto a partir do log. O script de inicializacao foi executado com sucesso. O fluxo de negocio distribuido por Kafka continua fora dessa demonstracao; veja [o roteiro](DEMONSTRACAO.md).

Para evidenciar o fluxo distribuido: abra uma OS e finalize a inspecao pelo fluxo da API; processe a decisao no servico de orcamento instrumentado. No Tempo, confira os spans HTTP, producer e consumer no mesmo trace. Partindo do trace, abra os logs correspondentes. Capture o dashboard sob trafego e um alerta de indisponibilidade ao parar a aplicacao por mais de um minuto. Esses passos exigem a stack e os servicos em execucao.

Referencias: [tracing no Spring Boot 4](https://docs.spring.io/spring-boot/4.0/reference/actuator/tracing.html) e [Observation no Spring Kafka](https://docs.spring.io/spring-kafka/reference/kafka/micrometer.html).
