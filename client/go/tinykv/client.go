package tinykv

import (
	"bytes"
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"net/http"
	"net/url"
	"strings"
	"time"
)

var (
	ErrNotFound         = errors.New("tinykv: key not found")
	ErrUnauthorized     = errors.New("tinykv: unauthorized (write token required)")
	ErrForbidden        = errors.New("tinykv: forbidden (server in read-only mode)")
	ErrPayloadTooLarge  = errors.New("tinykv: payload too large")
	ErrCapacityExceeded = errors.New("tinykv: maximum storage key capacity reached")
	ErrRateLimited      = errors.New("tinykv: rate limit exceeded")
	ErrNotReady         = errors.New("tinykv: storage engine not ready")
)

// Stats holds telemetry returned by GET /api/v1/stats.
type Stats struct {
	ActiveKeys       int64   `json:"activeKeys"`
	TotalSegments    int64   `json:"totalSegments"`
	TotalDiskBytes   int64   `json:"totalDiskBytes"`
	ActiveDataBytes  int64   `json:"activeDataBytes"`
	DeadDataBytes    int64   `json:"deadDataBytes"`
	DeadSpacePercent float64 `json:"deadSpacePercent"`
}

// Client is a Go client for TinyKV's HTTP REST API.
type Client struct {
	baseURL    string
	httpClient *http.Client
	writeToken string
}

// Option configures a Client.
type Option func(*Client)

// WithHTTPClient overrides the default http.Client.
func WithHTTPClient(c *http.Client) Option {
	return func(client *Client) {
		if c != nil {
			client.httpClient = c
		}
	}
}

// WithWriteToken sets the authorization token for mutating operations.
func WithWriteToken(token string) Option {
	return func(client *Client) {
		client.writeToken = token
	}
}

// WithTimeout sets a default HTTP request timeout on the client.
func WithTimeout(d time.Duration) Option {
	return func(client *Client) {
		client.httpClient.Timeout = d
	}
}

// NewClient creates a new TinyKV client.
func NewClient(rawBaseURL string, opts ...Option) (*Client, error) {
	u, err := url.Parse(strings.TrimRight(rawBaseURL, "/"))
	if err != nil {
		return nil, fmt.Errorf("invalid base URL: %w", err)
	}
	if u.Scheme == "" {
		u.Scheme = "http"
	}

	c := &Client{
		baseURL: u.String(),
		httpClient: &http.Client{
			Timeout: 10 * time.Second,
		},
	}

	for _, opt := range opts {
		opt(c)
	}

	return c, nil
}

// Put writes or overwrites a key with value bytes.
func (c *Client) Put(ctx context.Context, key string, val []byte) error {
	if key == "" {
		return errors.New("tinykv: key cannot be empty")
	}

	endpoint := fmt.Sprintf("%s/api/v1/keys/%s", c.baseURL, url.PathEscape(key))
	req, err := http.NewRequestWithContext(ctx, http.MethodPut, endpoint, bytes.NewReader(val))
	if err != nil {
		return err
	}
	req.Header.Set("Content-Type", "application/octet-stream")
	c.addAuthHeader(req)

	resp, err := c.httpClient.Do(req)
	if err != nil {
		return err
	}
	defer resp.Body.Close()

	return c.handleErrorStatus(resp)
}

// Get retrieves the raw value for a key. Returns ErrNotFound if missing.
func (c *Client) Get(ctx context.Context, key string) ([]byte, error) {
	if key == "" {
		return nil, errors.New("tinykv: key cannot be empty")
	}

	endpoint := fmt.Sprintf("%s/api/v1/keys/%s", c.baseURL, url.PathEscape(key))
	req, err := http.NewRequestWithContext(ctx, http.MethodGet, endpoint, nil)
	if err != nil {
		return nil, err
	}

	resp, err := c.httpClient.Do(req)
	if err != nil {
		return nil, err
	}
	defer resp.Body.Close()

	if resp.StatusCode == http.StatusNotFound {
		return nil, ErrNotFound
	}
	if err := c.handleErrorStatus(resp); err != nil {
		return nil, err
	}

	return io.ReadAll(resp.Body)
}

// Delete removes a key from the database.
func (c *Client) Delete(ctx context.Context, key string) error {
	if key == "" {
		return errors.New("tinykv: key cannot be empty")
	}

	endpoint := fmt.Sprintf("%s/api/v1/keys/%s", c.baseURL, url.PathEscape(key))
	req, err := http.NewRequestWithContext(ctx, http.MethodDelete, endpoint, nil)
	if err != nil {
		return err
	}
	c.addAuthHeader(req)

	resp, err := c.httpClient.Do(req)
	if err != nil {
		return err
	}
	defer resp.Body.Close()

	if resp.StatusCode == http.StatusNotFound {
		return ErrNotFound
	}
	return c.handleErrorStatus(resp)
}

// Stats returns engine storage statistics and telemetry.
func (c *Client) Stats(ctx context.Context) (*Stats, error) {
	endpoint := fmt.Sprintf("%s/api/v1/stats", c.baseURL)
	req, err := http.NewRequestWithContext(ctx, http.MethodGet, endpoint, nil)
	if err != nil {
		return nil, err
	}

	resp, err := c.httpClient.Do(req)
	if err != nil {
		return nil, err
	}
	defer resp.Body.Close()

	if err := c.handleErrorStatus(resp); err != nil {
		return nil, err
	}

	var stats Stats
	if err := json.NewDecoder(resp.Body).Decode(&stats); err != nil {
		return nil, fmt.Errorf("tinykv: failed to decode stats: %w", err)
	}
	return &stats, nil
}

// Compact triggers online log compaction to reclaim dead space.
func (c *Client) Compact(ctx context.Context) error {
	endpoint := fmt.Sprintf("%s/api/v1/compact", c.baseURL)
	req, err := http.NewRequestWithContext(ctx, http.MethodPost, endpoint, nil)
	if err != nil {
		return err
	}
	c.addAuthHeader(req)

	resp, err := c.httpClient.Do(req)
	if err != nil {
		return err
	}
	defer resp.Body.Close()

	return c.handleErrorStatus(resp)
}

// Healthz queries the liveness probe endpoint.
func (c *Client) Healthz(ctx context.Context) error {
	endpoint := fmt.Sprintf("%s/healthz", c.baseURL)
	req, err := http.NewRequestWithContext(ctx, http.MethodGet, endpoint, nil)
	if err != nil {
		return err
	}

	resp, err := c.httpClient.Do(req)
	if err != nil {
		return err
	}
	defer resp.Body.Close()

	return c.handleErrorStatus(resp)
}

// Readyz queries the readiness probe endpoint.
func (c *Client) Readyz(ctx context.Context) error {
	endpoint := fmt.Sprintf("%s/readyz", c.baseURL)
	req, err := http.NewRequestWithContext(ctx, http.MethodGet, endpoint, nil)
	if err != nil {
		return err
	}

	resp, err := c.httpClient.Do(req)
	if err != nil {
		return err
	}
	defer resp.Body.Close()

	if resp.StatusCode == http.StatusServiceUnavailable {
		return ErrNotReady
	}
	return c.handleErrorStatus(resp)
}

func (c *Client) addAuthHeader(req *http.Request) {
	if c.writeToken != "" {
		req.Header.Set("Authorization", "Bearer "+c.writeToken)
	}
}

func (c *Client) handleErrorStatus(resp *http.Response) error {
	if resp.StatusCode >= 200 && resp.StatusCode < 300 {
		return nil
	}

	body, _ := io.ReadAll(resp.Body)
	msg := string(body)

	switch resp.StatusCode {
	case http.StatusNotFound:
		return ErrNotFound
	case http.StatusUnauthorized:
		return ErrUnauthorized
	case http.StatusForbidden:
		return ErrForbidden
	case http.StatusRequestEntityTooLarge:
		return ErrPayloadTooLarge
	case http.StatusInsufficientStorage:
		return ErrCapacityExceeded
	case http.StatusTooManyRequests:
		return ErrRateLimited
	case http.StatusServiceUnavailable:
		return ErrNotReady
	default:
		return fmt.Errorf("tinykv: unexpected HTTP %d: %s", resp.StatusCode, msg)
	}
}