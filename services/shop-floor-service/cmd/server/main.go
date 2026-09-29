package main

import (
	"context"
	p "conveyor.local/platform"
	"conveyor.local/shop-floor/internal/application"
	"log/slog"
	"os"
	"os/signal"
	"syscall"
)

func main() {
	ctx, cancel := signal.NotifyContext(context.Background(), syscall.SIGINT, syscall.SIGTERM)
	defer cancel()
	app, err := p.New(ctx, "shop-floor-service", &application.Service{})
	if err != nil {
		slog.Error("startup failed", "error", err)
		os.Exit(1)
	}
	if err = app.Start(ctx); err != nil {
		slog.Error("server failed", "error", err)
		os.Exit(1)
	}
}
