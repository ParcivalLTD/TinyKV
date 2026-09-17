package main

import (
	"bytes"
	"context"
	"crypto/rand"
	"flag"
	"fmt"
	"os"
	"sort"
	"sync"
	"sync/atomic"
	"time"

	"github.com/ParcivalLTD/TinyKV/client/go/tinykv"
)

type LatencyStats struct {
	Min time.Duration
	P50 time.Duration
	P90 time.Duration
	P99 time.Duration
	Max time.Duration
	Avg time.Duration
}

func calcStats(durations []time.Duration) LatencyStats {
	if len(durations) == 0 {
		return LatencyStats{}
	}
	sort.Slice(durations, func(i, j int) bool { return durations[i] < durations[j] })

	var sum time.Duration
	for _, d := range durations {
		sum += d
	}

	p50Idx := int(float64(len(durations)) * 0.50)
	p90Idx := int(float64(len(durations)) * 0.90)
	p99Idx := int(float64(len(durations)) * 0.99)
	if p99Idx >= len(durations) {
		p99Idx = len(durations) - 1
	}

	return LatencyStats{
		Min: durations[0],
		P50: durations[p50Idx],
		P90: durations[p90Idx],
		P99: durations[p99Idx],
		Max: durations[len(durations)-1],
		Avg: sum / time.Duration(len(durations)),
	}
}

func main() {
	targetURL := flag.String("url", "http://localhost:8080", "Base URL of TinyKV HTTP service")
	concurrency := flag.Int("concurrency", 10, "Number of concurrent worker goroutines")
	totalRequests := flag.Int("requests", 500, "Total number of operations to execute")
	valSize := flag.Int("val-size", 128, "Value size in bytes")
	token := flag.String("token", "", "Write token for authentication (if required)")
	timeout := flag.Duration("timeout", 5*time.Second, "Request timeout")
	flag.Parse()

	fmt.Println("===============================================================")
	fmt.Println("             TINYKV GO BENCHMARK & LOAD GENERATOR              ")
	fmt.Println("===============================================================")
	fmt.Printf("Target Gateway: %s\n", *targetURL)
	fmt.Printf("Concurrency:    %d workers\n", *concurrency)
	fmt.Printf("Total Requests: %d ops\n", *totalRequests)
	fmt.Printf("Payload Size:   %d bytes\n", *valSize)
	fmt.Println("===============================================================")
	fmt.Println()

	opts := []tinykv.Option{tinykv.WithTimeout(*timeout)}
	if *token != "" {
		opts = append(opts, tinykv.WithWriteToken(*token))
	}

	client, err := tinykv.NewClient(*targetURL, opts...)
	if err != nil {
		fmt.Fprintf(os.Stderr, "Failed to initialize client: %v\n", err)
		os.Exit(1)
	}

	// 1. Health and readiness pre-flight check
	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()

	if err := client.Healthz(ctx); err != nil {
		fmt.Fprintf(os.Stderr, "Pre-flight /healthz check failed: %v\n", err)
		os.Exit(1)
	}
	if err := client.Readyz(ctx); err != nil {
		fmt.Fprintf(os.Stderr, "Pre-flight /readyz check failed: %v\n", err)
		os.Exit(1)
	}
	fmt.Println("==> Pre-flight health and readiness probes verified [UP & READY]")

	// Generate sample payload
	payload := make([]byte, *valSize)
	rand.Read(payload)

	// Channel for distributing work
	jobs := make(chan int, *totalRequests)
	for i := 0; i < *totalRequests; i++ {
		jobs <- i
	}
	close(jobs)

	var (
		wg          sync.WaitGroup
		putMu       sync.Mutex
		getMu       sync.Mutex
		putTimes    []time.Duration
		getTimes    []time.Duration
		errCount    int64
		successPuts int64
		successGets int64
	)

	t0 := time.Now()

	for w := 0; w < *concurrency; w++ {
		wg.Add(1)
		go func(workerID int) {
			defer wg.Done()
			for reqID := range jobs {
				key := fmt.Sprintf("bench:worker_%d:key_%d", workerID, reqID)

				// 1. Benchmark PUT
				startPut := time.Now()
				putErr := client.Put(context.Background(), key, payload)
				putDuration := time.Since(startPut)

				if putErr != nil {
					atomic.AddInt64(&errCount, 1)
					fmt.Fprintf(os.Stderr, "PUT error: %v\n", putErr)
					continue
				}
				atomic.AddInt64(&successPuts, 1)
				putMu.Lock()
				putTimes = append(putTimes, putDuration)
				putMu.Unlock()

				// 2. Benchmark GET
				startGet := time.Now()
				val, getErr := client.Get(context.Background(), key)
				getDuration := time.Since(startGet)

				if getErr != nil || !bytes.Equal(val, payload) {
					atomic.AddInt64(&errCount, 1)
					if getErr != nil {
						fmt.Fprintf(os.Stderr, "GET error: %v\n", getErr)
					} else {
						fmt.Fprintf(os.Stderr, "GET payload mismatch for key %s\n", key)
					}
					continue
				}
				atomic.AddInt64(&successGets, 1)
				getMu.Lock()
				getTimes = append(getTimes, getDuration)
				getMu.Unlock()
			}
		}(w)
	}

	wg.Wait()
	totalElapsed := time.Since(t0)

	putStats := calcStats(putTimes)
	getStats := calcStats(getTimes)
	totalOps := successPuts + successGets
	opsPerSec := float64(totalOps) / totalElapsed.Seconds()

	fmt.Println("\n===============================================================")
	fmt.Println("                     BENCHMARK RESULTS                         ")
	fmt.Println("===============================================================")
	fmt.Printf("Total Elapsed:   %v\n", totalElapsed.Round(time.Millisecond))
	fmt.Printf("Successful Ops:  %d PUT, %d GET (Errors: %d)\n", successPuts, successGets, errCount)
	fmt.Printf("Throughput:      %.2f ops/sec\n", opsPerSec)
	fmt.Println("---------------------------------------------------------------")
	fmt.Printf("%-10s %-10s %-10s %-10s %-10s %-10s\n", "Operation", "Avg", "p50", "p90", "p99", "Max")
	fmt.Printf("%-10s %-10v %-10v %-10v %-10v %-10v\n", "PUT (Write)", putStats.Avg.Round(time.Microsecond), putStats.P50.Round(time.Microsecond), putStats.P90.Round(time.Microsecond), putStats.P99.Round(time.Microsecond), putStats.Max.Round(time.Microsecond))
	fmt.Printf("%-10s %-10v %-10v %-10v %-10v %-10v\n", "GET (Read)", getStats.Avg.Round(time.Microsecond), getStats.P50.Round(time.Microsecond), getStats.P90.Round(time.Microsecond), getStats.P99.Round(time.Microsecond), getStats.Max.Round(time.Microsecond))
	fmt.Println("===============================================================")
	fmt.Println()

	if errCount > 0 {
		fmt.Fprintf(os.Stderr, "Benchmark finished with %d errors.\n", errCount)
		os.Exit(1)
	}
	fmt.Println("==> All operations completed successfully through the Gateway with zero errors.")
}