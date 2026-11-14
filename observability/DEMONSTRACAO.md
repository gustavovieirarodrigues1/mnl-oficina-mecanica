# Demonstracao de observabilidade em 2min30

Validado localmente em 14/11/2026: aplicacao e banco saudaveis, seis containers da demonstracao ativos, target Prometheus UP, tres regras aprovadas pelo promtool, dashboard preenchido, logs no Loki e navegacao do log ao trace HTTP no Tempo (incluindo spans de seguranca). Foram geradas 120 chamadas publicas, alem de uma chamada identificada e 12 rejeicoes 401 esperadas. O Kafka esta conectado, mas nao foi gerado um evento de negocio nesta demonstracao.

## Preparacao antes da gravacao

Na raiz do repositorio, abra PowerShell 7 e execute:

```powershell
./observability/demo-start.ps1
./observability/demo-traffic.ps1
```

O primeiro script inicia a aplicacao e a stack em containers, sem exigir compartilhamento da pasta Windows. Usa `ddl-auto=update` e desabilita seed para preservar o banco existente. A porta 8080 precisa estar livre. O JAR existente e reutilizado; se voce alterar o codigo Java, rode `.\mvnw.cmd -DskipTests package` antes.

O segundo script faz 120 chamadas publicas e algumas chamadas sem autenticacao, que retornam 401 de proposito. Nao altera ordens de servico. Esses 401 demonstram rejeicao de acesso, nao falha interna 5xx. Registra um trace identificavel em `logs/demo-request.json`.

Aguarde 30 segundos depois do trafego. Antes de gravar, deixe abertas estas abas:

| Tela | Acesso | O que conferir |
| --- | --- | --- |
| Grafana | http://localhost:3000/d/workshop-observability | Usuario `admin`; senha em `observability/.env`. Intervalo Last 15 minutes, atualizacao 30s |
| Prometheus Targets | http://localhost:9090/targets | Job `workshop` com estado UP |
| Prometheus Alerts | http://localhost:9090/alerts | As tres regras carregadas |
| Saude da API | http://localhost:8080/actuator/health | `status: UP` |

A senha inicial do Grafana so e aplicada quando o volume e criado. Se ja existia volume, use a senha anterior; editar `.env` nao redefine a senha existente.

## Como ler o dashboard

- **Requisicoes por segundo:** volume de chamadas atendidas, separado por metodo e rota. E uma taxa calculada em janela de cinco minutos, nao o total absoluto.
- **Taxa de erros 5xx:** proporcao de falhas internas. Zero e o esperado numa execucao saudavel. O painel mostra zero quando existem chamadas e nenhuma serie de erro 5xx.
- **Latencia HTTP p95:** aproximadamente 95% das requisicoes terminaram em tempo menor ou igual ao exibido. Nao e media nem pior tempo.
- **Memoria JVM:** memoria utilizada pela aplicacao Java, separada por area. Nao equivale a toda a memoria da maquina.
- **Conexoes JDBC:** conexoes de banco em uso e requisicoes aguardando uma conexao. Pendencias persistentes podem indicar saturacao.
- **Duracao maxima Kafka:** tempo maximo recente observado em envio/consumo. Chamadas GET nao geram eventos Kafka; sem eventos, este painel pode ficar vazio.
- **Logs correlacionados:** registros com metodo, rota, status, duracao, `traceId` e `spanId`. Expanda um registro e use o link **TraceID** para investigar a mesma chamada no Tempo.

## Abrir logs e traces

No Grafana, abra **Explore**, escolha a fonte **Loki**, selecione o modo Code e execute:

```logql
{job="workshop"} | json
```

Expanda uma linha `HTTP request completed`. Procure o campo derivado **TraceID** e abra o link para Tempo. Se preferir, em Explore escolha **Tempo** e pesquise pelo Trace ID salvo em `logs/demo-request.json`, ou use TraceQL:

```traceql
{ resource.service.name = "mechanic-workshop-system" }
```

No trace, mostre a linha do tempo e a duracao do span HTTP. Em uma chamada GET simples, um unico span HTTP e normal. Somente mostre um fluxo HTTP/Kafka distribuido se os eventos tiverem sido produzidos e os outros servicos estiverem instrumentados; este gerador de trafego nao comprova uma Saga nem um fluxo entre microsservicos.

## Roteiro falado: aproximadamente 2min30

### 0:00 a 0:20 — Grafana aberto

"Esta e a observabilidade da nossa aplicacao de oficina mecanica. Organizamos a investigacao em tres tipos de dados: metricas, logs e traces. As metricas mostram o comportamento geral; os logs registram os acontecimentos; e os traces permitem acompanhar uma requisicao e identificar onde o tempo foi gasto."

### 0:20 a 1:00 — Graficos do dashboard

"Neste dashboard, acompanhamos o volume de requisicoes, a proporcao de erros internos e a latencia p95. O p95 indica que aproximadamente 95% das chamadas terminaram dentro desse tempo. Tambem observamos a memoria da JVM e as conexoes com o banco. Isso ajuda a distinguir um aumento de trafego de problemas de desempenho ou saturacao. Os dados sao coletados pelo Prometheus a cada quinze segundos."

### 1:00 a 1:40 — Log e trace previamente abertos

"Ao investigar uma chamada especifica, abrimos seu log estruturado. Aqui temos metodo, rota, status e duracao. O trace ID conecta esse registro ao trace armazenado no Tempo. Abrindo esse identificador, conseguimos ver a linha do tempo da chamada. A instrumentacao tambem foi habilitada no produtor e no consumidor Kafka para propagar o contexto quando ha troca de mensagens. Nesta tela, estou mostrando o trace HTTP que gerei para a demonstracao."

### 1:40 a 2:10 — Prometheus Alerts

"Tambem configuramos tres alertas: indisponibilidade, aumento de erros internos e latencia elevada. Eles usam janelas de tempo para evitar alertas por oscilacoes momentaneas. O alerta de indisponibilidade dispara apos um minuto de falha na coleta. Os outros observam erros acima de cinco por cento e p95 acima de dois segundos, com volume minimo de chamadas. Aqui mostramos as regras carregadas; notificacoes externas ainda nao estao configuradas."

### 2:10 a 2:30 — Voltar ao Grafana

"O Grafana centraliza a visualizacao. O Prometheus armazena metricas, o Loki recebe logs coletados pelo Alloy e o Tempo armazena traces. Assim, conseguimos partir de um sintoma no dashboard e chegar a uma chamada especifica, com evidencias para investigar sua causa."

Ensaie uma vez e ajuste o ritmo. Deixe o log e o trace abertos antes da gravacao para evitar gastar o tempo procurando registros. Nao e necessario mostrar codigo ou inicializacao durante os 2min30.

## Se uma tela ficar vazia

1. Confira `status: UP` na API e o target UP no Prometheus.
2. Selecione Last 15 minutes e execute novamente `demo-traffic.ps1`.
3. Para logs, confirme que o arquivo `/app/logs/workshop.json` do container `application` esta recebendo linhas e que Alloy/Loki estao ativos.
4. Para traces, confirme o perfil `observability`, o Tempo ativo e aguarde alguns segundos pela exportacao.
5. Para Kafka, gere um evento real pelo fluxo de negocio; nao apresente um painel vazio como fluxo validado.

Para conferir containers e regras:

```powershell
docker compose --env-file observability/.env -f observability/compose.demo.yaml ps
docker compose --env-file observability/.env -f observability/compose.demo.yaml exec prometheus promtool check config /etc/prometheus/prometheus.yml
docker compose --env-file observability/.env -f observability/compose.demo.yaml exec prometheus promtool check rules /etc/prometheus/alerts.yml
```

Para encerrar a demonstracao sem apagar dados:

```powershell
docker compose --env-file observability/.env -f observability/compose.demo.yaml stop
```

Isso para a aplicacao e a observabilidade; banco, Kafka e os outros servicos de desenvolvimento permanecem ativos. Para para-los tambem, use `docker compose -f docker-local/docker-compose.yaml stop`.
