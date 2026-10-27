FROM golang:1.24-bookworm AS build
WORKDIR /src
COPY go.mod go.sum ./
RUN go mod download
COPY *.go ./
RUN CGO_ENABLED=1 go build -trimpath -ldflags="-s -w" -o /bagagli-server .

FROM debian:bookworm-slim
RUN apt-get update && apt-get install -y --no-install-recommends ca-certificates && rm -rf /var/lib/apt/lists/* \
    && useradd --system --uid 10001 bagagli && mkdir /data && chown bagagli /data
COPY --from=build /bagagli-server /usr/local/bin/bagagli-server
USER bagagli
ENV DATA_DIR=/data ADDR=:8080
VOLUME /data
EXPOSE 8080
HEALTHCHECK --interval=30s --timeout=5s CMD ["/bin/bash", "-c", "exec 3<>/dev/tcp/127.0.0.1/8080"]
ENTRYPOINT ["bagagli-server"]
