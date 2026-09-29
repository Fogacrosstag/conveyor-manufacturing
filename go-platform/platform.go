package platform

import (
	"context"
	"embed"
	"encoding/json"
	"errors"
	"fmt"
	"log/slog"
	"net/http"
	"os"
	"strings"
	"sync/atomic"
	"time"

	"github.com/gin-gonic/gin"
	"github.com/golang-migrate/migrate/v4"
	_ "github.com/golang-migrate/migrate/v4/database/postgres"
	"github.com/golang-migrate/migrate/v4/source/iofs"
	"github.com/google/uuid"
	"github.com/jackc/pgx/v5"
	"github.com/jackc/pgx/v5/pgxpool"
	"github.com/prometheus/client_golang/prometheus"
	"github.com/prometheus/client_golang/prometheus/promhttp"
	"github.com/redis/go-redis/v9"
	"github.com/twmb/franz-go/pkg/kgo"
	"go.opentelemetry.io/otel"
	"go.opentelemetry.io/otel/attribute"
	"go.opentelemetry.io/otel/exporters/otlp/otlptrace/otlptracehttp"
	"go.opentelemetry.io/otel/sdk/resource"
	sdktrace "go.opentelemetry.io/otel/sdk/trace"
)

//go:embed migrations/*.sql
var migrations embed.FS

// Doc is the JSON object persisted by the shared record store. Numeric JSON values
// arrive as float64; business handlers validate their range before writing.
type Doc map[string]any

func S(d Doc, k string) string {
	v, _ := d[k].(string)
	return v
}

func N(d Doc, k string) float64 {
	switch v := d[k].(type) {
	case float64:
		return v
	case int:
		return float64(v)
	case int64:
		return float64(v)
	case json.Number:
		n, e := v.Float64()
		Must(e)
		return n
	}
	return 0
}

func B(d Doc, k string) bool {
	v, _ := d[k].(bool)
	return v
}

func List(d Doc, k string) []Doc {
	out := []Doc{}
	switch values := d[k].(type) {
	case []any:
		for _, v := range values {
			switch x := v.(type) {
			case map[string]any:
				out = append(out, Doc(x))
			case Doc:
				out = append(out, x)
			default:
				panic(Fault{422, "VALIDATION", "Expected object array"})
			}
		}
	case []Doc:
		out = values
	}
	return out
}

// Clone detaches nested maps and slices before a handler changes an event payload.
func Clone(d Doc) Doc {
	b, e := json.Marshal(d)
	Must(e)
	var out Doc
	Must(json.Unmarshal(b, &out))
	return out
}

func JSON(v any) []byte {
	b, e := json.Marshal(v)
	Must(e)
	return b
}

func ID() string { return uuid.NewString() }

func Require(d Doc, k string) string {
	v := S(d, k)
	Check(v != "", 422, "Missing "+k)
	return v
}

func Check(ok bool, status int, message string) {
	if !ok {
		panic(Fault{status, "INVALID_STATE", message})
	}
}

func Must(e error) {
	if e != nil {
		panic(e)
	}
}

type Fault struct {
	Status  int
	Code    string
	Message string
}

func (f Fault) Error() string { return f.Message }

type Event struct {
	EventID       string `json:"eventId"`
	EventType     string `json:"eventType"`
	EventVersion  int    `json:"eventVersion"`
	Timestamp     string `json:"timestamp"`
	Source        string `json:"source"`
	CorrelationID string `json:"correlationId"`
	AggregateID   string `json:"aggregateId"`
	Payload       Doc    `json:"payload"`
}

func (e Event) Validate() error {
	switch e.EventType {
	case "EngineeringSpecificationCreated",
		"EngineeringSpecificationApproved",
		"BillOfMaterialsCreated",
		"BillOfMaterialsApproved",
		"ProductionOrderCreated",
		"ProductionOrderSubmitted",
		"ProductionOrderStarted",
		"ProductionOrderCancelled",
		"ProductionOrderCompleted",
		"MaterialReceived",
		"MaterialReserved",
		"MaterialReservationFailed",
		"MaterialReservationReleased",
		"MaterialConsumed",
		"ProductionScheduleApproved",
		"ProductionScheduleChanged",
		"ManufacturingOperationStarted",
		"ManufacturingOperationPaused",
		"ManufacturingOperationResumed",
		"ManufacturingOperationCompleted",
		"AllManufacturingOperationsCompleted",
		"EquipmentStateChanged",
		"EquipmentEventReceived",
		"QualityInspectionCreated",
		"QualityInspectionPassed",
		"ManufacturingReworkRequired",
		"FinalQualityApproved":
	default:
		return fmt.Errorf("unknown event type %q", e.EventType)
	}
	if e.EventVersion != 1 || e.EventType == "" || e.Source == "" || e.Payload == nil {
		return fmt.Errorf("invalid envelope")
	}
	for _, id := range []string{e.EventID, e.CorrelationID, e.AggregateID} {
		if _, err := uuid.Parse(id); err != nil {
			return err
		}
	}
	_, err := time.Parse(time.RFC3339Nano, e.Timestamp)
	return err
}

type Unit struct {
	Tx          pgx.Tx
	Ctx         context.Context
	Service     string
	Correlation string
}

func (u *Unit) Find(kind, id string) (Doc, bool) {
	var b []byte
	err := u.Tx.QueryRow(u.Ctx, "select body from records where kind=$1 and id=$2", kind, id).Scan(&b)
	if errors.Is(err, pgx.ErrNoRows) {
		return nil, false
	}
	Must(err)
	var d Doc
	Must(json.Unmarshal(b, &d))
	return d, true
}

func (u *Unit) Get(kind, id string) Doc {
	d, ok := u.Find(kind, id)
	Check(ok, 404, kind+" "+id+" not found")
	return d
}

func (u *Unit) Put(kind, id string, d Doc) {
	_, err := u.Tx.Exec(u.Ctx, "insert into records(kind,id,body) values ($1,$2,$3::jsonb) on conflict(kind,id) do update set body=excluded.body", kind, id, string(JSON(d)))
	Must(err)
}

func (u *Unit) All(kind string) []Doc {
	rows, err := u.Tx.Query(u.Ctx, "select body from records where kind=$1 order by id", kind)
	Must(err)
	defer rows.Close()
	out := []Doc{}
	for rows.Next() {
		var b []byte
		Must(rows.Scan(&b))
		var d Doc
		Must(json.Unmarshal(b, &d))
		out = append(out, d)
	}
	Must(rows.Err())
	return out
}

func (u *Unit) Owner() string {
	if d, ok := u.Find("_owner", strings.TrimSuffix(u.Service, "-service")); ok {
		return S(d, "state")
	}
	return Env("INITIAL_OWNER", "LOCAL")
}

// Emit joins the caller's transaction: state changes and outgoing facts commit together.
func (u *Unit) Emit(kind, id string, payload Doc) {
	e := Event{ID(), kind, 1, time.Now().UTC().Format(time.RFC3339Nano), u.Service, u.Correlation, id, Clone(payload)}
	topic := strings.TrimSuffix(u.Service, "-service") + ".events"
	_, err := u.Tx.Exec(u.Ctx, "insert into outbox_events(event_id,aggregate_id,topic,payload) values ($1,$2,$3,$4::jsonb)", e.EventID, id, topic, string(JSON(e)))
	Must(err)
}

type Capability interface {
	Kinds() []string
	Handle(*Unit, string, string, Doc) any
	On(*Unit, Event)
}

type App struct {
	DB             *pgxpool.Pool
	Producer       *kgo.Client
	Consumer       *kgo.Client
	Redis          *redis.Client
	Service        string
	Cap            Capability
	Processed      prometheus.Counter
	Failed         prometheus.Counter
	ConsumerFailed atomic.Bool
}

func Env(k, fallback string) string {
	if v := os.Getenv(k); v != "" {
		return v
	}
	return fallback
}

// A recovered domain fault rolls back both the inbox claim and its business mutations.
func (a *App) Atomic(ctx context.Context, corr string, fn func(*Unit) any) (result any, err error) {
	tx, err := a.DB.Begin(ctx)
	if err != nil {
		return nil, err
	}
	defer tx.Rollback(ctx)
	defer func() {
		if v := recover(); v != nil {
			switch x := v.(type) {
			case error:
				err = x
			default:
				err = fmt.Errorf("%v", x)
			}
		}
	}()
	_, err = tx.Exec(ctx, "select pg_advisory_xact_lock(8301)")
	if err != nil {
		return nil, err
	}
	result = fn(&Unit{tx, ctx, a.Service, corr})
	err = tx.Commit(ctx)
	return
}

func New(ctx context.Context, service string, cap Capability) (*App, error) {
	slog.SetDefault(slog.New(slog.NewJSONHandler(os.Stdout, nil)).With("service", service))
	url := Env("DB_URL", "")
	source, err := iofs.New(migrations, "migrations")
	if err != nil {
		return nil, err
	}
	m, err := migrate.NewWithSourceInstance("iofs", source, url)
	if err != nil {
		return nil, err
	}
	err = m.Up()
	m.Close()
	if err != nil && !errors.Is(err, migrate.ErrNoChange) {
		return nil, err
	}
	db, err := pgxpool.New(ctx, url)
	if err != nil {
		return nil, err
	}
	brokers := strings.Split(Env("KAFKA_BROKERS", "kafka:9092"), ",")
	producer, err := kgo.NewClient(kgo.SeedBrokers(brokers...), kgo.RequiredAcks(kgo.AllISRAcks()), kgo.MaxProduceRequestsInflightPerBroker(1))
	if err != nil {
		return nil, err
	}
	topics := []string{"engineering.events", "production-order.events", "material.events", "production-planning.events", "shop-floor.events", "quality-control.events", "equipment.events", "legacy.events"}
	reset := kgo.NewOffset().AtStart()
	if Env("KAFKA_OFFSET_RESET", "earliest") == "latest" {
		reset = kgo.NewOffset().AtEnd()
	}
	consumer, err := kgo.NewClient(kgo.ConsumeResetOffset(reset), kgo.SeedBrokers(brokers...), kgo.ConsumerGroup(service), kgo.ConsumeTopics(topics...), kgo.DisableAutoCommit(), kgo.BlockRebalanceOnPoll())
	if err != nil {
		return nil, err
	}
	a := &App{DB: db, Producer: producer, Consumer: consumer, Redis: redis.NewClient(&redis.Options{Addr: Env("REDIS_ADDR", "redis:6379")}), Service: service, Cap: cap}
	a.Processed = prometheus.NewCounter(prometheus.CounterOpts{Name: "kafka_messages_processed_total", Help: "Committed incoming messages"})
	a.Failed = prometheus.NewCounter(prometheus.CounterOpts{Name: "kafka_messages_failed_total", Help: "Messages sent to DLQ"})
	prometheus.MustRegister(a.Processed, a.Failed, prometheus.NewGaugeFunc(prometheus.GaugeOpts{Name: "outbox_pending_events", Help: "Durable unpublished events"}, func() float64 {
		var n int64
		err := db.QueryRow(context.Background(), "select count(*) from outbox_events where published_at is null").Scan(&n)
		if err != nil {
			return 0
		}
		return float64(n)
	}))
	for _, status := range []string{"PENDING", "IN_PROGRESS", "PAUSED", "COMPLETED", "CANCELLED"} {
		state := status
		prometheus.MustRegister(prometheus.NewGaugeFunc(prometheus.GaugeOpts{Name: "manufacturing_operations", Help: "Persisted operations by current status", ConstLabels: prometheus.Labels{"status": state}}, func() float64 {
			var n int64
			err := db.QueryRow(context.Background(), "select count(*) from records where kind='operation' and body->>'status'=$1", state).Scan(&n)
			if err != nil {
				return 0
			}
			return float64(n)
		}))
	}
	prometheus.MustRegister(prometheus.NewGaugeFunc(prometheus.GaugeOpts{Name: "equipment_downtime_seconds", Help: "Persisted manufacturing measurement"}, func() float64 {
		var value float64
		err := db.QueryRow(context.Background(), "select coalesce(sum(coalesce((body->>'downtimeSeconds')::double precision,0)+case when body->>'status'='UNAVAILABLE' and body ? 'unavailableSince' then extract(epoch from (now()-(body->>'unavailableSince')::timestamptz)) else 0 end),0) from records where kind='equipment'").Scan(&value)
		if err != nil {
			return 0
		}
		return value
	}))
	prometheus.MustRegister(prometheus.NewGaugeFunc(prometheus.GaugeOpts{Name: "manufacturing_operation_average_duration_seconds", Help: "Persisted manufacturing measurement"}, func() float64 {
		var value float64
		err := db.QueryRow(context.Background(), "select coalesce(avg(extract(epoch from ((body->>'actualCompletionTime')::timestamptz-(body->>'actualStartTime')::timestamptz))),0) from records where kind='operation' and body->>'status'='COMPLETED'").Scan(&value)
		if err != nil {
			return 0
		}
		return value
	}))
	exporter, err := otlptracehttp.New(ctx)
	if err != nil {
		return nil, err
	}
	tp := sdktrace.NewTracerProvider(sdktrace.WithBatcher(exporter), sdktrace.WithResource(resource.NewWithAttributes("", attribute.String("service.name", service))))
	otel.SetTracerProvider(tp)
	go func() {
		<-ctx.Done()
		shutdown, cancel := context.WithTimeout(context.Background(), 5*time.Second)
		defer cancel()
		tp.Shutdown(shutdown)
	}()
	return a, nil
}

func (a *App) Start(ctx context.Context) error {
	go a.publish(ctx)
	go a.consume(ctx)
	router := gin.New()
	router.Use(gin.Recovery())
	router.GET("/health", func(c *gin.Context) {
		if a.ConsumerFailed.Load() {
			c.JSON(503, Doc{"status": "DOWN", "reason": "consumer stopped"})
			return
		}
		check, cancel := context.WithTimeout(c.Request.Context(), 3*time.Second)
		defer cancel()
		if err := a.DB.Ping(check); err != nil {
			c.JSON(503, Doc{"status": "DOWN"})
			return
		}
		if err := a.Producer.Ping(check); err != nil {
			c.JSON(503, Doc{"status": "DOWN"})
			return
		}
		c.JSON(200, Doc{"status": "UP"})
	})
	router.GET("/metrics", gin.WrapH(promhttp.Handler()))
	router.NoRoute(a.http)
	server := &http.Server{Addr: ":8080", Handler: router, ReadHeaderTimeout: 5 * time.Second, ReadTimeout: 15 * time.Second, WriteTimeout: 30 * time.Second}
	go func() {
		<-ctx.Done()
		stop, cancel := context.WithTimeout(context.Background(), 10*time.Second)
		defer cancel()
		server.Shutdown(stop)
	}()
	err := server.ListenAndServe()
	if errors.Is(err, http.ErrServerClosed) {
		return nil
	}
	return err
}

func (a *App) http(c *gin.Context) {
	corr := c.GetHeader("X-Correlation-ID")
	if _, err := uuid.Parse(corr); err != nil {
		corr = ID()
	}
	c.Header("X-Correlation-ID", corr)
	ctx, span := otel.Tracer(a.Service).Start(c.Request.Context(), c.Request.Method+" "+c.Request.URL.Path)
	defer span.End()
	body := Doc{}
	if c.Request.Method != "GET" && c.Request.ContentLength != 0 {
		c.Request.Body = http.MaxBytesReader(c.Writer, c.Request.Body, 1<<20)
		if err := json.NewDecoder(c.Request.Body).Decode(&body); err != nil {
			c.JSON(400, Doc{"code": "INVALID_JSON", "message": "Expected JSON object", "traceId": corr})
			return
		}
	}
	path := c.Request.URL.Path
	result, err := a.Atomic(ctx, corr, func(u *Unit) any {
		if strings.HasPrefix(path, "/internal/migration/") {
			return a.migration(u, c, body)
		}
		if c.Request.Method == "GET" {
			return a.Cap.Handle(u, "GET", c.Request.URL.RequestURI(), body)
		}
		Check(c.Request.Method == "POST", 405, "Method not allowed")
		Check(u.Owner() == "LOCAL", 409, "Capability is frozen")
		key := c.GetHeader("Idempotency-Key")
		Check(len(key) > 0 && len(key) <= 128, 422, "Idempotency-Key required")
		kind := "_request:" + strings.TrimSuffix(a.Service, "-service")
		fingerprint := Doc{"method": "POST", "path": path, "body": body}
		if prior, ok := u.Find(kind, key); ok {
			Check(string(JSON(prior["request"])) == string(JSON(fingerprint)), 409, "Idempotency key reused")
			return prior["response"]
		}
		answer := a.Cap.Handle(u, "POST", path, body)
		u.Put(kind, key, Doc{"request": fingerprint, "response": answer})
		return answer
	})
	if err != nil {
		if f, ok := err.(Fault); ok {
			c.JSON(f.Status, Doc{"code": f.Code, "message": f.Message, "traceId": corr})
			return
		}
		slog.Error("request failed", "error", err, "correlationId", corr)
		c.JSON(500, Doc{"code": "INTERNAL_ERROR", "message": "Request failed; see service logs", "traceId": corr})
		return
	}
	if strings.HasPrefix(path, "/internal/migration/") {
		c.JSON(200, result)
	} else {
		c.JSON(200, Doc{"data": result, "timestamp": time.Now().UTC().Format(time.RFC3339Nano)})
	}
}

func (a *App) migration(u *Unit, c *gin.Context, body Doc) any {
	token := Env("ADMIN_TOKEN", "local-migration-token-change-me")
	Check(len(token) >= 16 && c.GetHeader("X-Admin-Token") == token, 403, "Invalid administration token")
	parts := strings.Split(c.Request.URL.Path, "/")
	Check(len(parts) == 5, 404, "Unknown migration action")
	cap := strings.TrimSuffix(a.Service, "-service")
	Check(parts[3] == cap, 404, "Unknown capability")
	kinds := append(append([]string{}, a.Cap.Kinds()...), "_request:"+cap)
	switch parts[4] {
	case "state":
		var pending int64
		Must(u.Tx.QueryRow(u.Ctx, "select count(*) from outbox_events where published_at is null").Scan(&pending))
		return Doc{"state": u.Owner(), "pendingOutbox": pending}
	case "freeze":
		u.Put("_owner", cap, Doc{"state": "FROZEN"})
		return Doc{"state": "FROZEN"}
	case "activate":
		u.Put("_owner", cap, Doc{"state": "LOCAL"})
		return Doc{"state": "LOCAL"}
	case "snapshot":
		Check(u.Owner() == "FROZEN", 409, "Freeze before snapshot")
		out := Doc{}
		for _, kind := range kinds {
			rows, err := u.Tx.Query(u.Ctx, "select id,body from records where kind=$1 order by id", kind)
			Must(err)
			items := []Doc{}
			for rows.Next() {
				var id string
				var raw []byte
				Must(rows.Scan(&id, &raw))
				var d Doc
				Must(json.Unmarshal(raw, &d))
				items = append(items, Doc{"id": id, "body": d})
			}
			Must(rows.Err())
			rows.Close()
			out[kind] = items
		}
		return out
	case "import":
		Check(u.Owner() == "FROZEN", 409, "Freeze before import")
		Check(len(body) == len(kinds), 422, "Incomplete snapshot")
		for _, kind := range kinds {
			_, ok := body[kind]
			Check(ok, 422, "Missing snapshot kind")
			_, err := u.Tx.Exec(u.Ctx, "delete from records where kind=$1", kind)
			Must(err)
			for _, row := range List(body, kind) {
				raw := JSON(row["body"])
				var doc Doc
				Must(json.Unmarshal(raw, &doc))
				u.Put(kind, S(row, "id"), doc)
			}
		}
		return Doc{"imported": true}
	default:
		panic(Fault{404, "NOT_FOUND", "Unknown migration action"})
	}
}

// Publish in sequence order. Stop the batch on the first failure so a later fact
// cannot overtake its prerequisite; unconfirmed rows remain available for retry.
func (a *App) publish(ctx context.Context) {
	ticker := time.NewTicker(250 * time.Millisecond)
	defer ticker.Stop()
	for {
		select {
		case <-ctx.Done():
			return
		case <-ticker.C:
			_, err := a.Atomic(ctx, ID(), func(u *Unit) any {
				rows, err := u.Tx.Query(ctx, "select sequence,aggregate_id,topic,payload::text from outbox_events where published_at is null order by sequence limit 20")
				Must(err)
				type pending struct {
					seq              int64
					key, topic, body string
				}
				items := []pending{}
				for rows.Next() {
					var p pending
					Must(rows.Scan(&p.seq, &p.key, &p.topic, &p.body))
					items = append(items, p)
				}
				Must(rows.Err())
				rows.Close()
				for _, p := range items {
					send, cancel := context.WithTimeout(ctx, 10*time.Second)
					err := a.Producer.ProduceSync(send, &kgo.Record{Topic: p.topic, Key: []byte(p.key), Value: []byte(p.body)}).FirstErr()
					cancel()
					if err != nil {
						_, e := u.Tx.Exec(ctx, "update outbox_events set retry_count=retry_count+1 where sequence=$1", p.seq)
						Must(e)
						break
					}
					_, err = u.Tx.Exec(ctx, "update outbox_events set published_at=now() where sequence=$1", p.seq)
					Must(err)
				}
				return nil
			})
			if err != nil {
				slog.Error("outbox failed", "error", err)
			}
		}
	}
}

// Commit the Kafka offset only after effects commit or the DLQ acknowledges the
// failed record. Inbox insertion and handler effects share one database transaction.
func (a *App) consume(ctx context.Context) {
	defer func() {
		if ctx.Err() == nil {
			a.ConsumerFailed.Store(true)
		}
	}()
	for ctx.Err() == nil {
		fetch := a.Consumer.PollRecords(ctx, 1)
		if fetch.IsClientClosed() {
			return
		}
		for _, failure := range fetch.Errors() {
			slog.Warn("poll failed", "error", failure.Err)
		}
		for _, record := range fetch.Records() {
			var failure error
			var event Event
			for attempt := 0; attempt < 4; attempt++ {
				failure = json.Unmarshal(record.Value, &event)
				if failure == nil {
					failure = event.Validate()
				}
				if failure == nil {
					_, failure = a.Atomic(ctx, event.CorrelationID, func(u *Unit) any {
						Check(u.Owner() == "LOCAL", 503, "Capability is frozen")
						tag, err := u.Tx.Exec(ctx, "insert into processed_events(event_id,consumer_name) values ($1,$2) on conflict do nothing", event.EventID, a.Service)
						Must(err)
						if tag.RowsAffected() > 0 {
							a.Cap.On(u, event)
						}
						return nil
					})
				}
				if failure == nil {
					break
				}
				select {
				case <-ctx.Done():
					return
				case <-time.After(time.Duration(100*(1<<attempt)) * time.Millisecond):
				}
			}
			if failure != nil {
				dead := Doc{"originalTopic": record.Topic, "partition": record.Partition, "offset": record.Offset, "failure": failure.Error(), "record": string(record.Value)}
				if err := a.Producer.ProduceSync(ctx, &kgo.Record{Topic: record.Topic + ".dlq", Key: record.Key, Value: JSON(dead)}).FirstErr(); err != nil {
					slog.Error("DLQ unavailable; stopping consumer to preserve offset", "error", err)
					a.Consumer.CloseAllowingRebalance()
					return
				}
				a.Failed.Inc()
			} else {
				a.Processed.Inc()
				if a.Service == "shop-floor-service" {
					if _, err := a.Atomic(ctx, event.CorrelationID, func(u *Unit) any {
						for _, equipment := range u.All("equipment") {
							if err := a.Redis.Set(ctx, "equipment:status:"+S(equipment, "id"), JSON(equipment), time.Minute).Err(); err != nil {
								slog.Warn("equipment cache unavailable", "error", err)
							}
						}
						return nil
					}); err != nil {
						slog.Warn("cache refresh failed", "error", err)
					}
				}
			}
			if err := a.Consumer.CommitRecords(ctx, record); err != nil {
				slog.Error("offset commit failed; stopping consumer", "error", err)
				a.Consumer.CloseAllowingRebalance()
				return
			}
		}
		a.Consumer.AllowRebalance()
	}
}
