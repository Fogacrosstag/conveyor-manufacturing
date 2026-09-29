FROM golang:1.26.8-alpine AS build
WORKDIR /src
COPY go-platform go-platform
COPY services/manufacturing-audit-service services/manufacturing-audit-service
WORKDIR /src/services/manufacturing-audit-service
RUN go mod download && CGO_ENABLED=0 go build -mod=readonly -trimpath -o /app ./cmd/server
FROM alpine:3.21
RUN apk add --no-cache ca-certificates curl && addgroup -S conveyor && adduser -S conveyor -G conveyor
COPY --from=build /app /app
USER conveyor
EXPOSE 8080
ENTRYPOINT ["/app"]
