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
- **OpenTelemetry, push only.** The official
  [`opentelemetry-spring-boot-starter`](https://opentelemetry.io/docs/zero-code/java/spring-boot-starter/)
  exports traces, metrics and logs over OTLP to Alloy. Micrometer meters (including
  the app's custom metrics) are bridged into the OTel SDK. No Prometheus scraping.
- **Profiling** via the Grafana Pyroscope Java agent (`-javaagent`), pushed straight
  to Pyroscope.
- **Exemplars** — the request-latency histogram carries `trace_id` exemplars; click a
  ◆ on the latency panel to jump to the trace in Tempo.
- **Logs → traces** — log lines carry the `trace_id` as OTLP structured metadata; the
  Loki datasource turns it into a clickable link to Tempo.
- **`service_name`** — comes straight from `spring.application.name` (`graf-zahl-demo`)
  and appears as a label on logs and metrics and as `service.name` on traces.
- **Single tenant** everywhere (Loki `auth_enabled: false`, Mimir
  `multitenancy_enabled: false`, Tempo/Pyroscope default single tenant).

## Run it

```bash
docker compose up -d --build
```

Then open Grafana → dashboard **“Graf Zahl — Observability Showcase”**:

- http://localhost:13000 (anonymous admin, no login)

Use the **Environment** dropdown at the top to switch between `production` and `test`.
k6 generates continuous load, so data appears within ~15 seconds.

### Ports

Host ports are offset (13xxx/19xxx/14xxx) to avoid clashing with anything already
running on the standard ports.

| Service    | URL                          |
|------------|------------------------------|
| Grafana    | http://localhost:13000       |
| Mimir      | http://localhost:19009       |
| Loki       | http://localhost:13100       |
| Tempo      | http://localhost:13200       |
| Pyroscope  | http://localhost:14040       |
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

- **App → Alloy:** `OTEL_EXPORTER_OTLP_ENDPOINT=http://alloy:4318` (OTLP/HTTP).
  `OTEL_RESOURCE_ATTRIBUTES=deployment.environment=<env>` tags every signal.
- **Alloy → backends:** one `otelcol.receiver.otlp` → `batch` → three `otlphttp`
  exporters (Tempo `:4318`, Loki `/otlp`, Mimir `/otlp`).
- **Exemplars:** Mimir has `max_global_exemplars_per_user` enabled; the Grafana Mimir
  datasource maps the `trace_id` exemplar label to Tempo.
- **Resource-attribute promotion:** Mimir promotes `service.name` and
  `deployment.environment` to metric labels (`service_name`, `deployment_environment`)
  so the dashboard can filter on them. Loki promotes them automatically.
- **App → Pyroscope:** the Java agent pushes to `http://pyroscope:4040`, tagged with
  `environment=<env>`; the app name becomes the Pyroscope `service_name`.

## Versions

Java 21, Spring Boot 3.5.16, OpenTelemetry instrumentation BOM 2.16.0,
Grafana 12.2, Loki 3.7, Tempo 2.10, Mimir 3.1, Pyroscope 1.21, Alloy 1.17, k6 1.8.
