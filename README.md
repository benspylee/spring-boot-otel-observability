# spring-boot-otel-observability
Observe the spring boot application using opentelemetry collector, Grafana,Loki,Tempo

1. Traces with [Tempo](https://github.com/grafana/tempo) 
2. Metrics with [Prometheus](https://prometheus.io/), [Spring Boot Actuator](https://docs.spring.io/spring-boot/docs/current/actuator-api/htmlsingle/), and [Micrometer](https://micrometer.io/)
3. Logs with [Loki](https://github.com/grafana/loki)
4. using   [OpenTelemetry Agent for Java](https://github.com/open-telemetry/opentelemetry-java-instrumentation) and [OpenTelemetry Collector](https://github.com/open-telemetry/opentelemetry-collector) for collecting and exporting telemetry data from the target application.

This Spring Boot (Grafana stack/Opentelemetry) project is inspired by [Spring Boot Observability](https://github.com/blueswen/spring-boot-observability/tree/main).


## Table of contents

- [spring-boot-otel-observability](#spring-boot-otel-observability)
  - [Table of contents](#table-of-contents)
  - [Quick Start](#quick-start)
  - [Explore with Grafana](#explore-with-grafana)
    - [Metrics to Traces](#metrics-to-traces)
    - [Traces to Logs](#traces-to-logs)
    - [Logs to Traces](#logs-to-traces)
  - [Detail](#detail)
    - [Spring Boot Application](#spring-boot-application)
      - [OpenTelemetry Instrumentation](#opentelemetry-instrumentation)
      - [Logs](#logs)
      - [Traces](#traces)
      - [Metrics](#metrics)
        - [Metrics with Exemplar](#metrics-with-exemplar)
    - [Prometheus - Metrics](#prometheus---metrics)
    - [Tempo - Traces](#tempo---traces)
    - [Loki - Logs](#loki---logs)
    - [Opentelemetry Collector pipeline for traces,logs,metrics](#opentelemetry-collector-pipeline-for-traceslogsmetrics)
    - [Prometheus - Metrics](#prometheus---metrics-1)
      - [Prometheus Config](#prometheus-config)
      - [Grafana Data Source](#grafana-data-source)
    - [Tempo - Traces](#tempo---traces-1)
      - [Grafana Data Source](#grafana-data-source-1)
    - [Loki - Logs](#loki---logs-1)
      - [Grafana Data Source](#grafana-data-source-2)
    - [Prometheus - AlertManager](#prometheus---alertmanager)
      - [Grafana Data Source](#grafana-data-source-3)
    - [Grafana Alertsmanager](#grafana-alertsmanager)
  
## Quick Start  
1. Initialize docker volume  setup 

   ```bash
   bash init.sh
   ```

2. Build application image and start all services with docker-compose

   ```bash
   docker-compose build
   docker-compose up -d
   ```

3. Send requests with [siege](https://linux.die.net/man/1/siege) and curl to the Spring Boot app

   ```bash
   bash request-script.sh
   ```
4. Check predefined dashboard ```Spring Boot Observability``` on Grafana [http://localhost:3000/](http://localhost:3000/)

   Dashboard screenshot:

   ![Spring Boot Monitoring Dashboard](./images/dashboard.png)   

## Explore with Grafana

Grafana provides a great solution, which could observe specific actions in service between traces, metrics, and logs through trace ID and exemplar.

![Observability Correlations](./images/observability-correlations.jpeg)

Image Source: [Grafana](https://grafana.com/blog/2021/03/31/intro-to-exemplars-which-enable-grafana-tempos-distributed-tracing-at-massive-scale/)

### Metrics to Traces

Get Trace ID from an exemplar in metrics, then query in Tempo.

Query with ```histogram_quantile(.99,sum(rate(http_server_requests_seconds_bucket{application="app-a", uri!="/actuator/prometheus"}[1m])) by(uri, le))``` and turn on Exemplars in options.

![Metrics to Traces](./images/metrics_to_traces.png)

![Metrics to Traces](./images/metrics_to_traces-2.png)


### Traces to Logs

Get Trace ID and tags (here is `compose.service`) defined in Tempo data source from span, then query with Loki.

![Traces to Logs](./images/traces_to_logs.png)

### Logs to Traces

Get Trace ID from log (regex defined in Loki data source), then query in Tempo.

![Logs to Traces](./images/logs_to_traces.png)   

## Detail

### Spring Boot Application

In this spring boot application, opentelemetry data is collected and exported to opentelemetry collector using opentelemetry agent.
#### OpenTelemetry Instrumentation

[OpenTelemetry Instrumentation for Java](https://github.com/open-telemetry/opentelemetry-java-instrumentation) provides an [automatic way](https://opentelemetry.io/docs/instrumentation/java/automatic/) to instrument the application by the agent jar as follows:

```bash
java -javaagent:path/to/opentelemetry-javaagent.jar -jar myapp.jar
```

The agent supports a lot of [libraries](https://github.com/open-telemetry/opentelemetry-java-instrumentation/blob/main/docs/supported-libraries.md), including Spring Web MVC. According to the document:

> It can be used to capture telemetry data at the “edges” of an app or service, such as inbound requests, outbound HTTP calls, database calls, and so on.

So we don't need to modify any line of code in our codebase. The agent will handle everything automatically.


In this demo project we use environment variables to set the agent configuration:

```yaml
  app-a:
    build: ./target_app/
    environment:
      - OTEL_SERVICE_NAME=app-a
      - OTEL_TRACES_EXPORTER=otlp
      - OTEL_EXPORTER_OTLP_ENDPOINT=http://collector:4317
      - OTEL_METRICS_EXPORTER=otlp
      - OTEL_LOGS_EXPORTER=otlp
      - OTEL_RESOURCE_ATTRIBUTES=service.name=app-a,compose_service=app-a
    ports:
      - "8081:8080"   
```


More configuration details can be found on the [official document](https://opentelemetry.io/docs/instrumentation/java/automatic/agent-config/).

#### Logs

OpenTelemetry Agent will add information to each logging automatically.

> OpenTelemetry Agent injects the current span information into each logging event's MDC copy:
> - trace_id - the current trace id (same as Span.current().getSpanContext().getTraceId());
> - span_id - the current span id (same as Span.current().getSpanContext().getSpanId());
> - trace_flags - the current trace flags, formatted according to W3C traceflags format (same as Span.current().getSpanContext().getTraceFlags().asHex()).

#### Traces

 OpenTelemetry Agent can capture telemetry data at the “edges” of an app or service, such as inbound requests, and outbound HTTP calls. We don't need to add anything to our code. To show this, we provide the `/chain` endpoint in the application as follows:

```java
@GetMapping("/chain")
    public String chain(@RequestParam(value = "name", defaultValue = "World") String name) throws InterruptedException, IOException {
        String TARGET_ONE_HOST = System.getenv().getOrDefault("TARGET_ONE_HOST", "localhost");
        String TARGET_TWO_HOST = System.getenv().getOrDefault("TARGET_TWO_HOST", "localhost");
        logger.debug("chain is starting");
        Request.Get("http://localhost:8080/")
                .execute().returnContent();
        Request.Get(String.format("http://%s:8080/io_task", TARGET_ONE_HOST))
                .execute().returnContent();
        Request.Get(String.format("http://%s:8080/cpu_task", TARGET_TWO_HOST))
                .execute().returnContent();
        logger.debug("chain is finished");
        return "chain";
    }
```

When calling app-a `chain` endpoint(`curl localhost:8080/chain`), it will send requests to itself root(`/`) and the other two services' `io_task`, and `cpu_task` by order. In the whole process, we don't write any line of code about OpenTelemetry, trace, or span. But the log shows all inbound requests, and outbound HTTP calls were added span information as follows: 

```log
# Start from app-a chain
2022-10-10 15:57:12.828 trace_id=743ae05db90d00fd65998ff30cf7094d span_id=6cc84ac5ed4cf68c trace_flags=01 DEBUG 1 --- [nio-8080-exec-6] com.example.app.AppApplication           : chain is starting

# In app-a root
2022-10-10 15:57:13.106 trace_id=743ae05db90d00fd65998ff30cf7094d span_id=4745d1a1f588a949 trace_flags=01 ERROR 1 --- [nio-8080-exec-7] com.example.app.AppApplication           : [traceparent:"00-743ae05db90d00fd65998ff30cf7094d-d72a1422522ce837-01", host:"localhost:8080", connection:"Keep-Alive", user-agent:"Apache-HttpClient/4.5.13 (Java/1.8.0_342)", accept-encoding:"gzip,deflate"]
2022-10-10 15:57:13.106 trace_id=743ae05db90d00fd65998ff30cf7094d span_id=4745d1a1f588a949 trace_flags=01 ERROR 1 --- [nio-8080-exec-7] com.example.app.AppApplication           : Hello World!!
2022-10-10 15:57:13.106 trace_id=743ae05db90d00fd65998ff30cf7094d span_id=4745d1a1f588a949 trace_flags=01 DEBUG 1 --- [nio-8080-exec-7] com.example.app.AppApplication           : Debugging log
2022-10-10 15:57:13.106 trace_id=743ae05db90d00fd65998ff30cf7094d span_id=4745d1a1f588a949 trace_flags=01 INFO 1 --- [nio-8080-exec-7] com.example.app.AppApplication           : Info log
2022-10-10 15:57:13.106 trace_id=743ae05db90d00fd65998ff30cf7094d span_id=4745d1a1f588a949 trace_flags=01 WARN 1 --- [nio-8080-exec-7] com.example.app.AppApplication           : Hey, This is a warning!
2022-10-10 15:57:13.106 trace_id=743ae05db90d00fd65998ff30cf7094d span_id=4745d1a1f588a949 trace_flags=01 ERROR 1 --- [nio-8080-exec-7] com.example.app.AppApplication           : Oops! We have an Error. OK

# In app-b io_task
2022-10-10 15:57:14.141 trace_id=743ae05db90d00fd65998ff30cf7094d span_id=b97df0b1834ab84a trace_flags=01 INFO 1 --- [nio-8080-exec-4] com.example.app.AppApplication           : io_task

# In app-c cpu_task
2022-10-10 15:57:14.191 trace_id=743ae05db90d00fd65998ff30cf7094d span_id=7fd693eefc0d3387 trace_flags=01 INFO 1 --- [nio-8080-exec-4] com.example.app.AppApplication           : cpu_task

# Back to app-a chain
2022-10-10 15:57:14.199 trace_id=743ae05db90d00fd65998ff30cf7094d span_id=6cc84ac5ed4cf68c trace_flags=01 DEBUG 1 --- [nio-8080-exec-6] com.example.app.AppApplication           : chain is finished
```

Each endpoint got the same trace_id `743ae05db90d00fd65998ff30cf7094d` start from app-a `chain`. The auto-injected `traceparent`(could saw in the first line in app-a root log) is how OpenTelemetry Agent passed through all these services.

#### Metrics

To get Prometheus metrics from the Spring Boot application, we need two dependencies:

1. [Spring Boot Actuator](https://github.com/spring-projects/spring-boot/tree/v2.7.4/spring-boot-project/spring-boot-actuator): Actuator provides a lot of monitoring features by HTTP or JMX endpoints for the Spring Boot applications.
2. [Micrometer](https://github.com/micrometer-metrics/micrometer): Micrometer provides a general API to collect metrics and transform the format for different monitoring systems, including Prometheus.

Add these two dependencies to the `pom.xml` and config to the `application.yaml` as follows:

```xml
<dependency>
   <groupId>org.springframework.boot</groupId>
   <artifactId>spring-boot-starter-actuator</artifactId>
</dependency>
<dependency>
   <groupId>io.micrometer</groupId>
   <artifactId>micrometer-registry-prometheus</artifactId>
</dependency>
```

```yaml
management:
  endpoints:
    web:
      exposure:
        include: prometheus # only exposure /actuator/prometheus endpoint
  metrics:
    tags:
      application: app # add tag to each prometheus metric
```

The Prometheus metrics look like this on `/actuator/prometheus`:

```txt
# HELP executor_active_threads The approximate number of threads that are actively executing tasks
# TYPE executor_active_threads gauge
executor_active_threads{application="app",name="applicationTaskExecutor",} 0.0
...
 HELP http_server_requests_seconds Duration of HTTP server request handling
# TYPE http_server_requests_seconds summary
http_server_requests_seconds_count{application="app",exception="None",method="GET",outcome="SUCCESS",status="200",uri="/",} 1.0
http_server_requests_seconds_sum{application="app",exception="None",method="GET",outcome="SUCCESS",status="200",uri="/",} 0.047062542
http_server_requests_seconds_count{application="app",exception="None",method="GET",outcome="SUCCESS",status="200",uri="/actuator/prometheus",} 2.0
http_server_requests_seconds_sum{application="app",exception="None",method="GET",outcome="SUCCESS",status="200",uri="/actuator/prometheus",} 0.053801375
# HELP http_server_requests_seconds_max Duration of HTTP server request handling
# TYPE http_server_requests_seconds_max gauge
http_server_requests_seconds_max{application="app",exception="None",method="GET",outcome="SUCCESS",status="200",uri="/",} 0.047062542
http_server_requests_seconds_max{application="app",exception="None",method="GET",outcome="SUCCESS",status="200",uri="/actuator/prometheus",} 0.045745625
...
```

##### Metrics with Exemplar

Exemplar is a new datatype proposed in [OpenMetrics](https://github.com/OpenObservability/OpenMetrics/blob/main/specification/OpenMetrics.md#exemplars). To enable the Exemplar feature there are some dependencies requirements:

1. Spring Boot >= 2.7.0: Spring Boot supported Prometheus Exemplars since [v2.7.0-RC1](https://github.com/spring-projects/spring-boot/releases/tag/v2.7.0-RC1).
2. Micrometer >= 1.10.0: Micrometer supported Exemplar for Prometheus Histogram and Prometheus Counter since [1.9.0](https://github.com/micrometer-metrics/micrometer/releases/tag/v1.9.0) and using io.prometheus.simpleclient_common 0.16.0 since [1.10.0](https://mvnrepository.com/artifact/io.micrometer/micrometer-registry-prometheus/1.10.0).

Additionally, we need to add an [Exemplar Sampler](app/src/main/java/com/example/app/PrometheusExemplarSamplerConfiguration.java) (Source from [qaware/cloud-observability-grafana-spring-boot](https://github.com/qaware/cloud-observability-grafana-spring-boot/blob/b331b87b1a7f0f5b5d57150e0356e6a26af967a2/spring-boot-app/src/main/java/de/qaware/demo/cloudobservability/PrometheusExemplarSamplerConfiguration.java)) as follows:

```java
package com.example.app;

import io.prometheus.client.exemplars.tracer.otel_agent.OpenTelemetryAgentSpanContextSupplier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class PrometheusExemplarSamplerConfiguration {
    @Bean
    public OpenTelemetryAgentSpanContextSupplier openTelemetryAgentSpanContextSupplier() {
        // OpenTelemetryAgentSpanContextSupplier is from the opentelemetry agent jar, without using the agent will cause class not found error when running.
        return new OpenTelemetryAgentSpanContextSupplier();
    }
}
```

The discussion about Exemplar Sampler is in [Exemplars support for Prometheus Histogram #2812](https://github.com/micrometer-metrics/micrometer/issues/2812#issuecomment-1086001766) on the Micrometer repository.

When all dependencies are addressed. We can add a distribution metric to Prometheus metrics in the `application.yaml`.

```yaml
management:
  metrics:
    distribution:
      percentiles-histogram:
        http:
          server:
            requests: 'true'
```

Check more options for distribution metrics on the [Spring Boot document](https://docs.spring.io/spring-boot/docs/2.7.3/reference/html/actuator.html#actuator.metrics.customizing.per-meter-properties).

As previously mentioned, Exemplar is a new datatype proposed in OpenMetrics, and the default `/actuator/prometheus` provide metrics with Prometheus format. So we need to [add some headers](https://docs.spring.io/spring-boot/docs/current/actuator-api/htmlsingle/#prometheus.retrieving:~:text=The%20default%20response%20content%20type%20is%20text/plain%3Bversion%3D0.0.4.%20The%20endpoint%20can%20also%20produce%20application/openmetrics%2Dtext%3Bversion%3D1.0.0%20when%20called%20with%20an%20appropriate%20Accept%20header%2C%20as%20shown%20in%20the%20following%20curl%2Dbased%20example%3A) to get the metrics with OpenMetrics format as follows:

```bash
curl 'http://localhost:8080/actuator/prometheus' -i -X GET \
   -H 'Accept: application/openmetrics-text; version=1.0.0; charset=utf-8'
```

The histogram metrics with trace ID (starts with `#`) look like this:

```txt
# TYPE http_server_requests_seconds histogram
# HELP http_server_requests_seconds Duration of HTTP server request handling
http_server_requests_seconds_bucket{application="app",exception="None",method="GET",outcome="SUCCESS",status="200",uri="/",le="0.001"} 0.0
http_server_requests_seconds_bucket{application="app",exception="None",method="GET",outcome="SUCCESS",status="200",uri="/",le="0.001048576"} 0.0
http_server_requests_seconds_bucket{application="app",exception="None",method="GET",outcome="SUCCESS",status="200",uri="/",le="0.001398101"} 0.0
http_server_requests_seconds_bucket{application="app",exception="None",method="GET",outcome="SUCCESS",status="200",uri="/",le="0.001747626"} 0.0
http_server_requests_seconds_bucket{application="app",exception="None",method="GET",outcome="SUCCESS",status="200",uri="/",le="0.002097151"} 0.0
http_server_requests_seconds_bucket{application="app",exception="None",method="GET",outcome="SUCCESS",status="200",uri="/",le="0.002446676"} 0.0
http_server_requests_seconds_bucket{application="app",exception="None",method="GET",outcome="SUCCESS",status="200",uri="/",le="0.002796201"} 1.0 # {span_id="55255da260e873d9",trace_id="21933703cb442151b1cef583714eb42e"} 0.002745959 1665676383.654
http_server_requests_seconds_bucket{application="app",exception="None",method="GET",outcome="SUCCESS",status="200",uri="/",le="0.003145726"} 1.0
http_server_requests_seconds_bucket{application="app",exception="None",method="GET",outcome="SUCCESS",status="200",uri="/",le="0.003495251"} 2.0 # {span_id="81222a08c4f050fe",trace_id="eddcd9569d20b4aa48c06d3b905f32ea"} 0.003224625 1665676382.620
```

### Prometheus - Metrics

Export application metrics using `otlp` exporter and send it to Opentelemetry collector,then pipeline expose metrics endpoint in collector, finally it will be scrapped by prometheus

### Tempo - Traces

Export application traces using `otlp` exporter and send it to Opentelemetry collector,finally logs will be sent to Tempo

### Loki - Logs

Export application logs using `otlp` exporter and send it to Opentelemetry collector,finally logs will be sent to Loki


### Opentelemetry Collector pipeline for traces,logs,metrics 

```yml

receivers:
  otlp:
    protocols:
      grpc:
      http:
processors:
      batch:
      attributes/renamer:
        actions:
          - key: account_id
            value: ''
            action: insert  
          - action: insert
            key: loki.format
            value: raw   
      resource:
        attributes:
          - key: host.name
            action: delete  
          - action: insert
            key: loki.format
            value: raw  
      transform/telemetry:
        log_statements:
          - context: log
            statements:
              - set(attributes["msg"], body)
              - set(body, ParseJSON("{\"msg\":true}"))
              - set(body["msg"],attributes)              
              - set(attributes["account_id"], "456678287") where resource.attributes["os.type"] == "linux"
              - keep_keys(resource.attributes, ["os.type", "service.name","account_id"])
              - keep_keys(attributes, ["account_id"])
        metric_statements:
          - context: resource
            statements:
              - keep_keys(attributes, ["uri","le","application","job","trace_id","span_id"])
          - context: datapoint
            statements:
              - keep_keys(attributes, ["uri","le","application","job","trace_id","span_id"])
exporters:
  logging:
    verbosity: detailed
  loki:
    endpoint: "http://loki:3100/loki/api/v1/push"
  otlp/tempo:
    endpoint: "http://tempo:4317"
    tls:
      insecure: true  
  prometheus:
    endpoint: 'collector:1234'
    namespace: test-space
    send_timestamps: true
    metric_expiration: 180m
    resource_to_telemetry_conversion:
      enabled: true      	
    enable_open_metrics: true  	
service:
  pipelines:
    metrics:
      receivers: [otlp]
      processors: [batch,transform/telemetry]
      exporters: [prometheus]
    logs:
      receivers: [otlp]
      processors: [batch] #transform/telemetry,,attributes/renamer ,resource
      exporters: [logging,loki]  
    traces:
      receivers: [otlp]
      processors: [batch]
      exporters: [logging,otlp/tempo]	  


```

### Prometheus - Metrics

Collects metrics from applications.

#### Prometheus Config

Define all Spring Boot applications metrics scrape jobs in `config/prometheus/prometheus.yml`.

Prometheus will scrape OpenMetrics format metrics automatically, there is no need to add specific headers config when scraping from `/actuator/prometheus`.

```yaml
...
scrape_configs:
scrape_configs:
  - job_name: "app-a"
    metrics_path: '/actuator/prometheus'   
    static_configs:
      - targets: ["app-a:8081"] # scrape from the target application [app-> prometheus]
  - job_name: "app-b"
    static_configs:
      - targets: ["collector:1234"] # scrape from collector endpoint [app-> collector -> prometheus]
```
#### Grafana Data Source

Add an Exemplars which uses the value of `trace_id` label to create a Tempo link.

Grafana data source setting example:

![Data Source of Prometheus: Exemplars](./images/prometheus-exemplars.png)

Grafana data sources config example:

```yaml
name: Prometheus
type: prometheus
typeName: Prometheus
typeLogoUrl: public/app/plugins/datasource/prometheus/img/prometheus_logo.svg
access: proxy
url: http://prometheus:9090
user: ''
database: ''
basicAuth: false
isDefault: true
jsonData:
  exemplarTraceIdDestinations:
  - datasourceUid: f30c97cf-42c0-4c2c-866c-6a0f408c8b68
    name: trace_id
  httpMethod: POST
readOnly: false

```

### Tempo - Traces

Receives spans from applications.

#### Grafana Data Source

[Trace to logs](https://grafana.com/docs/grafana/latest/datasources/tempo/#trace-to-logs) setting:

1. Data source: target log source
2. Tags: key of tags or process level attributes from the trace, which will be log query criteria if the key exists in the trace
3. Map tag names: Convert existing key of tags or process level attributes from trace to another key, then used as log query criteria. Use this feature when the values of the trace tag and log label are identical but the keys are different.

Grafana data source setting example:

![Data Source of Tempo: Trace to logs](./images/tempo-trace-to-logs.png)

Grafana data sources config example:

```yaml
name: Tempo
type: tempo
typeName: Tempo
typeLogoUrl: public/app/plugins/datasource/tempo/img/tempo_logo.svg
access: proxy
url: http://tempo:3200
user: ''
database: ''
basicAuth: false
isDefault: false
jsonData:
  tracesToLogsV2:
    customQuery: false
    datasourceUid: c616c72d-2923-44ee-a6cc-f001dc9416f1
    filterByTraceID: true
    tags:
    - key: compose_service
      value: job
readOnly: false
```
### Loki - Logs
#### Grafana Data Source

Add a TraceID derived field to extract the trace id and create a Tempo link from the trace id.

Grafana data source setting example:

![Data Source of Loki: Derived fields](./images/loki-derive-filed.png)

Grafana data source config example:

```yaml
name: Loki
type: loki
typeName: Loki
access: proxy
url: http://loki:3100
password: ''
user: ''
database: ''
basicAuth: false
isDefault: false
jsonData:
derivedFields:
   - datasourceUid: tempo
      matcherRegex: (?:trace_id)=(\w+)
      name: TraceID
      url: $${__value.raw}
      # Use $$ (double-dollar sign) when your configuration needs a literal dollar sign.
readOnly: false
editable: true
```

### Prometheus - AlertManager

#### Grafana Data Source
Add Prometheus alert manager to manage alerts and route them to corresponding notification channels.

Grafana data source config example:

```yaml
name: Alertmanager
type: alertmanager
typeName: Alertmanager
typeLogoUrl: public/app/plugins/datasource/alertmanager/img/logo.svg
access: proxy
url: http://alertmanager:9093
user: ''
database: ''
basicAuth: false
isDefault: false
jsonData:
  implementation: prometheus
readOnly: false
```

Prometheus Alertmanager configuration `config/prometheus/prometheus.yml`
Here `SNS` topic configued as default receiver for the alertmanager notification channel
```yml
route:
  group_by: [ alertname ]
  receiver: 'sns' # default receiver
  repeat_interval: 2m

receivers:
  - name: sns
    sns_configs:
      - topic_arn: arn:aws:sns:us-east-1:[ACCOUNT_ID]:alertmanager-sns-topic
        subject: |-
            High CPU Usage 
        message: |-
            High CPU Usage in the target machine.Please look into it.
        sigv4:
          region: us-east-1


```

### Grafana Alertsmanager
   we can configure alerting rules with prometheus as datasource for the metrics data that's captured 
![Data Source of Loki: Derived fields](./images/grafana-alert-mgr.png)

`System cpu usage` monitering and alerting if it exceeds the threshold.

```json
[
  {
    "id": 1,
    "uid": "a8fdfa05-654a-45ef-837c-001ce99abe04",
    "orgID": 1,
    "folderUID": "b308508c-1bdf-41c1-9e1f-bdbb8f90cddc",
    "ruleGroup": "system",
    "title": "system usage ",
    "condition": "C",
    "data": [
      {
        "refId": "A",
        "queryType": "",
        "relativeTimeRange": {
          "from": 600,
          "to": 0
        },
        "datasourceUid": "b9dbab95-0ce1-48a5-be76-b79a9e8539b5",
        "model": {
          "editorMode": "code",
          "expr": "system_cpu_usage{}",
          "hide": false,
          "intervalMs": 1000,
          "legendFormat": "__auto",
          "maxDataPoints": 43200,
          "range": true,
          "refId": "A"
        }
      },
      {
        "refId": "B",
        "queryType": "",
        "relativeTimeRange": {
          "from": 600,
          "to": 0
        },
        "datasourceUid": "__expr__",
        "model": {
          "conditions": [
            {
              "evaluator": {
                "params": [],
                "type": "gt"
              },
              "operator": {
                "type": "and"
              },
              "query": {
                "params": [
                  "B"
                ]
              },
              "reducer": {
                "params": [],
                "type": "last"
              },
              "type": "query"
            }
          ],
          "datasource": {
            "type": "__expr__",
            "uid": "__expr__"
          },
          "expression": "A",
          "hide": false,
          "intervalMs": 1000,
          "maxDataPoints": 43200,
          "reducer": "last",
          "refId": "B",
          "type": "reduce"
        }
      },
      {
        "refId": "C",
        "queryType": "",
        "relativeTimeRange": {
          "from": 600,
          "to": 0
        },
        "datasourceUid": "__expr__",
        "model": {
          "conditions": [
            {
              "evaluator": {
                "params": [
                  0.7
                ],
                "type": "gt"
              },
              "operator": {
                "type": "and"
              },
              "query": {
                "params": [
                  "C"
                ]
              },
              "reducer": {
                "params": [],
                "type": "last"
              },
              "type": "query"
            }
          ],
          "datasource": {
            "type": "__expr__",
            "uid": "__expr__"
          },
          "expression": "B",
          "hide": false,
          "intervalMs": 1000,
          "maxDataPoints": 43200,
          "refId": "C",
          "type": "threshold"
        }
      }
    ],
    "updated": "2023-06-01T11:56:06Z",
    "noDataState": "NoData",
    "execErrState": "Error",
    "for": "1m",
    "labels": {
      "env": "prod",
      "team": "devops"
    },
    "isPaused": false
  }
]


```