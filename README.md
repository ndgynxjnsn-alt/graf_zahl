# Graf Zahl — Grafana observability showcase

A minimal, self-contained Grafana stack that demonstrates **logs, metrics, traces
and continuous profiling** for a Java 21 / Spring Boot 3.5 application — all pushed
(no scraping), all in single-tenant mode.

```
                     ┌───────────── OTLP (push) ─────────────┐
  Spring Boot app ───┤                                        ├──► Grafana Alloy ──► Loki   (logs)
  (× production      │  logs · metrics · traces               │                   ├─► Tempo  (traces)
   × test)           └────────────────────────────────────────┘                   └─► Mimir  (metrics + exemplars)
        │
        └── Pyroscope Java agent ─────────────────────────────────────────────────────► Pyroscope (profiles)

  k6 ──► drives load against both app instances
  Grafana ──► reads Mimir, Loki, Tempo, Pyroscope
```

## What it shows

- **One Spring Boot app, run twice** — `production` and `test`, selected via the
  `ENVIRONMENT` variable in `docker-compose.yml`.
- **OpenTelemetry, push only.** The
  [OpenTelemetry Java agent](https://opentelemetry.io/docs/zero-code/java/agent/)
  (the OTel-recommended zero-code approach) auto-instruments the app and exports
  traces, metrics and logs over OTLP to Alloy. Micrometer meters (including the
  app's custom metrics) are bridged automatically. No app-side OTel dependencies,
  no Prometheus scraping. The agent is attached via `JAVA_TOOL_OPTIONS`, so the
  Dockerfile entrypoint stays a plain `java -jar`.
- **Profiling** via the **Pyroscope Java agent 2.1.x** (`-javaagent:pyroscope.jar`),
  the real async-profiler-based profiler.
- **Trace ↔ profile correlation** via
  [`grafana/otel-profiling-java`](https://github.com/grafana/otel-profiling-java)
  loaded as a small **OTel agent extension** (`-Dotel.javaagent.extensions`). The
  extension bridges to the Pyroscope agent (over the system classloader) and stamps
  each profile with the active span (`span_name`), so profiles are filterable per
  span and a Tempo span links to its flame graph. **Agent order matters:** the
  Pyroscope agent is listed *before* the OTel agent in `JAVA_TOOL_OPTIONS` so its
  `ProfilerSdk` is loaded by the time the OTel agent builds the SDK and the
  extension looks it up — otherwise the bridge silently falls back.
- **Everything through Alloy.** Profiles are pushed to Alloy
  (`pyroscope.receive_http`), which relays to Pyroscope (`pyroscope.write`), so
  logs, metrics, traces *and* profiles all flow through Alloy.
- **Exemplars** — the request-latency histogram carries `trace_id` exemplars; click a
  ◆ on the latency panel to jump to the trace in Tempo.
- **Logs ↔ traces** — log lines carry the `trace_id` (OTLP structured metadata); the
  Loki datasource links it to Tempo, and Tempo's **Traces → Logs** puts a button on
  every span that runs `{service_name="graf-zahl-demo"} | trace_id="<id>"`.
- **Metrics → profiles** — the request-rate and JVM-memory panels have a data link
  that opens the Pyroscope flame graph for the selected `$environment` over the
  panel's time range. Click a CPU/throughput spike, see what caused it.
- **`service_name`** — comes straight from `spring.application.name` (`graf-zahl-demo`)
  and appears as a label on logs and metrics and as `service.name` on traces.
- **Single tenant** everywhere (Loki `auth_enabled: false`, Mimir
  `multitenancy_enabled: false`, Tempo/Pyroscope default single tenant).

## Run it

### Docker Compose

```bash
docker compose up -d --build
```

### Docker Swarm

The same file deploys to Swarm. All config files are shipped as top-level
`configs:` (not bind mounts) and the app runs from a locally built image:

```bash
docker swarm init                       # if not already a swarm
docker compose build                    # builds graf-zahl-app:local
docker stack deploy --resolve-image=never -c docker-compose.yml graf-zahl
```

`--resolve-image=never` tells Swarm to use the locally built image instead of
trying to pull a digest from a registry. After a **rebuild**, force the app
services to pick up the new image:

```bash
docker compose build && docker service update --force --image graf-zahl-app:local graf-zahl_app-production
```

Swarm-specific choices in the compose file (all also valid under Compose):

- **`configs:`** for every config file — portable across nodes, no host bind mounts.
- **`mode: host`** published ports — bypasses the Swarm ingress routing mesh, which
  is unreliable on single-node swarms.
- **Ring addresses pinned to `127.0.0.1`** for Mimir and Pyroscope (and Loki). These
  are single-replica monoliths that only talk to themselves; without this, Swarm's
  overlay/VIP networking makes a component's own advertised IP unreachable and the
  internal gRPC ring dials time out.

Then open Grafana → dashboard **“Graf Zahl — Observability Showcase”**:

- http://localhost:3000 (anonymous admin, no login)

Use the **Environment** dropdown at the top to switch between `production` and `test`.
k6 generates continuous load, so data appears within ~15 seconds.

Grafana's **Drilldown** apps (Logs, Traces, Metrics, Profiles) are bundled with
Grafana 13 and work out of the box against these datasources:
http://localhost:3000/drilldown

### Ports

| Service    | URL                          |
|------------|------------------------------|
| Grafana    | http://localhost:3000        |
| Mimir      | http://localhost:9009        |
| Loki       | http://localhost:3100        |
| Tempo      | http://localhost:3200        |
| Pyroscope  | http://localhost:4040        |
| Alloy UI   | http://localhost:12345       |
| app (prod) | http://localhost:8081        |
| app (test) | http://localhost:8082        |

### Try the app directly

```bash
curl localhost:8081/rolldice     # logs + trace + a custom counter
curl localhost:8081/work         # CPU-bound (shows up in the flame graph) + custom timer
curl localhost:8081/flaky        # fails ~30% of the time -> error metrics/logs/traces
```

## Layout

```
docker-compose.yml            # the whole stack
app/                          # Spring Boot app (multi-stage Docker build)
  src/main/java/...           #   endpoints: /rolldice /work /flaky
  src/main/resources/         #   application.yml -> spring.application.name
alloy/config.alloy            # single OTLP receiver -> fan-out to the three backends
loki/  tempo/  mimir/         # single-tenant backend configs
grafana/provisioning/         # datasources (exemplar + trace/log links) + dashboard provider
grafana/dashboards/           # the showcase dashboard JSON
k6/script.js                  # load generator
```

## How the pieces connect

- **Minimal app config.** Almost everything is an OTel agent default, so the app
  only sets two OTel vars: `OTEL_EXPORTER_OTLP_ENDPOINT=http://alloy:4318`
  (http/protobuf is the agent default) and
  `OTEL_RESOURCE_ATTRIBUTES=deployment.environment=<env>`. `otlp` exporters,
  `trace_based` exemplars, cumulative bucketed histograms and the Micrometer
  bridge are all on by default; `service.name` is auto-detected from
  `spring.application.name`.
- **Alloy → backends:** one `otelcol.receiver.otlp` → `batch` → three `otlphttp`
  exporters (Tempo `:4318`, Loki `/otlp`, Mimir `/otlp`).
- **Exemplars:** Mimir has `max_global_exemplars_per_user` enabled; the Grafana Mimir
  datasource maps the `trace_id` exemplar label to Tempo.
- **Resource-attribute promotion:** Mimir promotes `service.name` and
  `deployment.environment` to metric labels (`service_name`, `deployment_environment`)
  so the dashboard can filter on them. Loki promotes them automatically.
- **Profiling → Alloy → Pyroscope:** the Pyroscope agent pushes to Alloy
  (`http://alloy:9999`, `pyroscope.receive_http`), which forwards to Pyroscope
  (`pyroscope.write`). Profiles are tagged with `environment=<env>`, and the app
  name becomes the Pyroscope `service_name`.
- **Span profiles:** the agent runs the profiler (`otel.pyroscope.start.profiling=false`
  — the agent, not the extension, profiles) and the extension adds span labels
  (`otel.pyroscope.add.span.name=true`). Query a flame graph scoped to a span, e.g.
  `process_cpu:cpu:nanoseconds:cpu:nanoseconds{service_name="graf-zahl-demo", span_name="GET"}`.

## Versions

Java 21, Spring Boot 3.5.16, OpenTelemetry Java agent 2.30.0,
Pyroscope Java agent 2.1.2 + `io.pyroscope:otel` 1.0.4 extension, Grafana 13.1,
Loki 3.7, Tempo 3.0, Mimir 3.1, Pyroscope 2.1, Alloy 1.17, k6 2.1.

> Tempo 3.0 runs in **monolithic mode** (`-target=all`) — its new architecture
> only needs Kafka for microservices deployments. In-process, the distributor
> pushes spans straight to the live-store (recent-query serving, block building
> and retention) and the metrics-generator, so no `ingester` / `compactor` /
> `ingest` blocks appear in `tempo/tempo-config.yml`.


https://share.gemini.google/Zl2x6XFsrnlo