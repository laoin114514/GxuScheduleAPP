package main

import (
	"context"
	"errors"
	"log"
	"net/http"
	"os"
	"os/signal"
	"syscall"
	"time"

	"github.com/laoin114514/gxuschedule-server/internal/config"
	"github.com/laoin114514/gxuschedule-server/internal/database"
	"github.com/laoin114514/gxuschedule-server/internal/handler"
	"github.com/laoin114514/gxuschedule-server/internal/oss"
	"github.com/laoin114514/gxuschedule-server/internal/router"
)

func main() {
	cfg, err := config.Load()
	if err != nil {
		log.Fatalf("config: %v", err)
	}

	db, err := database.New(cfg)
	if err != nil {
		log.Fatalf("database: %v", err)
	}
	ossClient, err := oss.New(cfg)
	if err != nil {
		log.Fatalf("oss: %v", err)
	}

	h := handler.New(db, ossClient, cfg)
	srv := &http.Server{
		Addr:    ":" + cfg.Port,
		Handler: router.New(cfg, h),
	}

	go func() {
		log.Printf("server listening on %s (env=%s)", srv.Addr, cfg.AppEnv)
		if err := srv.ListenAndServe(); err != nil && !errors.Is(err, http.ErrServerClosed) {
			log.Fatalf("listen: %v", err)
		}
	}()

	// 优雅退出
	quit := make(chan os.Signal, 1)
	signal.Notify(quit, syscall.SIGINT, syscall.SIGTERM)
	<-quit
	log.Println("shutting down ...")
	ctx, cancel := context.WithTimeout(context.Background(), 10*time.Second)
	defer cancel()
	if err := srv.Shutdown(ctx); err != nil {
		log.Printf("shutdown: %v", err)
	}
	log.Println("server exited")
}
