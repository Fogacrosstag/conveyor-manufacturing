package main

import (
	"context"
	"conveyor.local/manufacturing-audit/internal/application"
	p "conveyor.local/platform"
	"log/slog"
	"os"
	"os/signal"
	"syscall"
)

func main() {
	ctx, cancel := signal.NotifyContext(context.Background(), syscall.SIGINT, syscall.SIGTERM)
	defer cancel()
	app, err := p.New(ctx, "manufacturing-audit-service", &application.Service{})
	if err != nil {
		slog.Error("startup failed", "error", err)
		os.Exit(1)
	}
	if err = app.Start(ctx); err != nil {
		slog.Error("server failed", "error", err)
		os.Exit(1)
	}
}
