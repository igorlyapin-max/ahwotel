ARG GO_IMAGE
ARG BASE_IMAGE
FROM ${GO_IMAGE} AS build
WORKDIR /src
COPY go.mod main.go main_test.go ./
RUN go test ./... && CGO_ENABLED=0 go build -trimpath -ldflags="-s -w" -o /lab-supervisor .

FROM ${BASE_IMAGE}
ARG BASE_IMAGE
LABEL org.opencontainers.image.version="code13" \
      org.opencontainers.image.source="https://github.com/igorlyapin-max/ahwotel" \
      org.opencontainers.image.base.name="${BASE_IMAGE}"
# Inherit the pinned upstream runtime identity; do not create an interactive user or home.
COPY --from=build /lab-supervisor /lab-supervisor
