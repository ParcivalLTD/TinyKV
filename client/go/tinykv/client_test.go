package tinykv

import (
	"context"
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"sync"
	"testing"
	"time"
)

func TestClientCRUD(t *testing.T) {
	data := make(map[string][]byte)
	var mu sync.Mutex

	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		mu.Lock()
		defer mu.Unlock()

		switch {
		case r.Method == http.MethodPut && r.URL.Path == "/api/v1/keys/user:101":
			body := make([]byte, r.ContentLength)
			r.Body.Read(body)
			data["user:101"] = body
			w.WriteHeader(http.StatusOK)
			w.Write([]byte(`{"status":"OK"}`))

		case r.Method == http.MethodGet && r.URL.Path == "/api/v1/keys/user:101":
			val, ok := data["user:101"]
			if !ok {
				w.WriteHeader(http.StatusNotFound)
				return
			}
			w.WriteHeader(http.StatusOK)
			w.Write(val)

		case r.Method == http.MethodDelete && r.URL.Path == "/api/v1/keys/user:101":
			delete(data, "user:101")
			w.WriteHeader(http.StatusNoContent)

		case r.Method == http.MethodGet && r.URL.Path == "/healthz":
			w.WriteHeader(http.StatusOK)
			w.Write([]byte(`{"status":"UP"}`))

		case r.Method == http.MethodGet && r.URL.Path == "/readyz":
			w.WriteHeader(http.StatusOK)
			w.Write([]byte(`{"status":"READY"}`))

		case r.Method == http.MethodGet && r.URL.Path == "/api/v1/stats":
			stats := Stats{ActiveKeys: int64(len(data)), TotalSegments: 1, TotalDiskBytes: 1024}
			json.NewEncoder(w).Encode(stats)

		case r.Method == http.MethodPost && r.URL.Path == "/api/v1/compact":
			w.WriteHeader(http.StatusOK)
			w.Write([]byte(`{"status":"COMPACTED"}`))

		default:
			w.WriteHeader(http.StatusNotFound)
		}
	}))
	defer srv.Close()

	client, err := NewClient(srv.URL)
	if err != nil {
		t.Fatalf("NewClient failed: %v", err)
	}

	ctx := context.Background()

	// 1. Healthz and Readyz
	if err := client.Healthz(ctx); err != nil {
		t.Errorf("Healthz failed: %v", err)
	}
	if err := client.Readyz(ctx); err != nil {
		t.Errorf("Readyz failed: %v", err)
	}

	// 2. Put
	if err := client.Put(ctx, "user:101", []byte("Julian")); err != nil {
		t.Errorf("Put failed: %v", err)
	}

	// 3. Get
	val, err := client.Get(ctx, "user:101")
	if err != nil {
		t.Errorf("Get failed: %v", err)
	}
	if string(val) != "Julian" {
		t.Errorf("expected 'Julian', got '%s'", string(val))
	}

	// 4. Stats
	stats, err := client.Stats(ctx)
	if err != nil {
		t.Errorf("Stats failed: %v", err)
	}
	if stats.ActiveKeys != 1 {
		t.Errorf("expected 1 active key, got %d", stats.ActiveKeys)
	}

	// 5. Compact
	if err := client.Compact(ctx); err != nil {
		t.Errorf("Compact failed: %v", err)
	}

	// 6. Delete
	if err := client.Delete(ctx, "user:101"); err != nil {
		t.Errorf("Delete failed: %v", err)
	}

	// 7. Get non-existent returns ErrNotFound
	_, err = client.Get(ctx, "user:101")
	if err != ErrNotFound {
		t.Errorf("expected ErrNotFound, got %v", err)
	}
}

func TestClientWriteTokenAuth(t *testing.T) {
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		auth := r.Header.Get("Authorization")
		if auth != "Bearer valid-token" {
			w.WriteHeader(http.StatusUnauthorized)
			return
		}
		w.WriteHeader(http.StatusOK)
	}))
	defer srv.Close()

	ctx := context.Background()

	// Without token -> ErrUnauthorized
	c1, _ := NewClient(srv.URL)
	if err := c1.Put(ctx, "k", []byte("v")); err != ErrUnauthorized {
		t.Errorf("expected ErrUnauthorized, got %v", err)
	}

	// With valid token -> success
	c2, _ := NewClient(srv.URL, WithWriteToken("valid-token"))
	if err := c2.Put(ctx, "k", []byte("v")); err != nil {
		t.Errorf("expected success with token, got %v", err)
	}
}

func TestClientErrorMappings(t *testing.T) {
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		switch r.URL.Path {
		case "/api/v1/keys/large":
			w.WriteHeader(http.StatusRequestEntityTooLarge)
		case "/api/v1/keys/full":
			w.WriteHeader(http.StatusInsufficientStorage)
		case "/api/v1/keys/limited":
			w.WriteHeader(http.StatusTooManyRequests)
		case "/readyz":
			w.WriteHeader(http.StatusServiceUnavailable)
		default:
			w.WriteHeader(http.StatusOK)
		}
	}))
	defer srv.Close()

	client, _ := NewClient(srv.URL)
	ctx := context.Background()

	if err := client.Put(ctx, "large", []byte("v")); err != ErrPayloadTooLarge {
		t.Errorf("expected ErrPayloadTooLarge, got %v", err)
	}

	if err := client.Put(ctx, "full", []byte("v")); err != ErrCapacityExceeded {
		t.Errorf("expected ErrCapacityExceeded, got %v", err)
	}

	if err := client.Put(ctx, "limited", []byte("v")); err != ErrRateLimited {
		t.Errorf("expected ErrRateLimited, got %v", err)
	}

	if err := client.Readyz(ctx); err != ErrNotReady {
		t.Errorf("expected ErrNotReady, got %v", err)
	}
}

func TestClientContextTimeout(t *testing.T) {
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		time.Sleep(100 * time.Millisecond)
		w.WriteHeader(http.StatusOK)
	}))
	defer srv.Close()

	client, _ := NewClient(srv.URL)
	ctx, cancel := context.WithTimeout(context.Background(), 20*time.Millisecond)
	defer cancel()

	err := client.Put(ctx, "k", []byte("v"))
	if err == nil {
		t.Fatal("expected timeout error, got nil")
	}
}