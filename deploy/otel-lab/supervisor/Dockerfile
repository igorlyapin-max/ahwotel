FROM golang:1.27.1-alpine@sha256:cf6fca6641884b8433441b2b0652976f975e1d0fdd26d177eaaf8596087f3125 AS build
WORKDIR /src
COPY go.mod main.go main_test.go ./
RUN go test ./... && CGO_ENABLED=0 go build -trimpath -ldflags="-s -w" -o /lab-supervisor .

FROM otel/opentelemetry-collector-contrib:0.160.0@sha256:799dc6cf12c96192af37b5bdba804da8c10b3bc563b43cb90c3f3c58d9572ad6 AS collector
LABEL org.opencontainers.image.version="code16" \
      org.opencontainers.image.source="https://github.com/igorlyapin-max/ahwotel" \
      org.opencontainers.image.base.name="otel/opentelemetry-collector-contrib:0.160.0"
COPY --from=build /lab-supervisor /lab-supervisor
USER 10001:10001

FROM prom/prometheus:v3.14.0@sha256:5ce7540c3c00ef4ab0c9d2c995c6a5b9c421f44b4a115d97a2c7af3b1c21cbb0 AS prometheus
LABEL org.opencontainers.image.version="code16" \
      org.opencontainers.image.source="https://github.com/igorlyapin-max/ahwotel" \
      org.opencontainers.image.base.name="prom/prometheus:v3.14.0"
COPY --from=build /lab-supervisor /lab-supervisor
USER 65534:65534

FROM grafana/grafana:13.2.1@sha256:f772d434e8fab0049deb2b1b30abd43342bcfca1537614aa8d36080232cf4283 AS grafana
LABEL org.opencontainers.image.version="code16" \
      org.opencontainers.image.source="https://github.com/igorlyapin-max/ahwotel" \
      org.opencontainers.image.base.name="grafana/grafana:13.2.1"
COPY --from=build /lab-supervisor /lab-supervisor
USER 472:0
