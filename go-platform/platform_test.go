package platform

import (
	"testing"
	"time"
)

func TestEnvelopeValidation(t *testing.T) {
	e := Event{ID(), "ProductionOrderStarted", 1, time.Now().UTC().Format(time.RFC3339Nano), "production-order-service", ID(), ID(), Doc{}}
	if err := e.Validate(); err != nil {
		t.Fatal(err)
	}
	e.EventVersion = 2
	if e.Validate() == nil {
		t.Fatal("accepted unknown event version")
	}
	e.EventVersion = 1
	e.EventID = "invalid"
	if e.Validate() == nil {
		t.Fatal("accepted invalid event ID")
	}
}

func TestCloneDoesNotMutateOriginal(t *testing.T) {
	original := Doc{"status": "PENDING", "tasks": []Doc{{"id": "one"}}}
	copy := Clone(original)
	List(copy, "tasks")[0]["id"] = "changed"
	if S(List(original, "tasks")[0], "id") != "one" {
		t.Fatal("clone shares nested state")
	}
}

// Event names must match one contract entry, not a substring of a joined list.
func TestEnvelopeRejectsCombinedEventNames(t *testing.T) {
	event := Event{
		EventID:       ID(),
		EventType:     "EngineeringSpecificationCreated|EngineeringSpecificationApproved",
		EventVersion:  1,
		Timestamp:     time.Now().UTC().Format(time.RFC3339Nano),
		Source:        "engineering-service",
		CorrelationID: ID(),
		AggregateID:   ID(),
		Payload:       Doc{},
	}
	if event.Validate() == nil {
		t.Fatal("accepted multiple contract names as one event type")
	}
}
