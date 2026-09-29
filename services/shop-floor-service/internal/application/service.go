package application

import (
	p "conveyor.local/platform"
	"github.com/google/uuid"
	"math"
	"net/url"
	"sort"
	"strings"
	"time"
)

type Service struct{}

func (s *Service) Kinds() []string {
	return []string{"operation", "shop-order", "equipment", "equipment-observation"}
}

func (s *Service) Handle(u *p.Unit, method, path string, b p.Doc) any {
	parsed, err := url.Parse(path)
	p.Must(err)
	parts := strings.Split(parsed.Path, "/")
	p.Check(len(parts) >= 3 && parts[1] == "shop-floor", 404, "Unknown resource")
	if method == "GET" {
		if len(parts) == 3 {
			items := []p.Doc{}
			for _, op := range u.All("operation") {
				if id := parsed.Query().Get("productionOrderId"); id == "" || p.S(op, "productionOrderId") == id {
					items = append(items, op)
				}
			}
			sort.Slice(items, func(i, j int) bool { return p.N(items[i], "sequence") < p.N(items[j], "sequence") })
			return p.Doc{"items": items}
		}
		return u.Get("operation", parts[3])
	}
	if len(parts) == 3 && parts[2] == "events" {
		s.equipment(u, b)
		return p.Doc{"accepted": true}
	}
	p.Check(len(parts) == 5 && parts[2] == "operations", 404, "Unknown action")
	op := u.Get("operation", parts[3])
	orderID := p.S(op, "productionOrderId")
	order := u.Get("shop-order", orderID)
	p.Check(p.S(order, "status") != "CANCELLED", 409, "Order cancelled")
	action := parts[4]
	switch action {
	case "start":
		p.Check(p.B(order, "authorized"), 409, "Order start authorization required")
		p.Check(p.S(op, "status") == "PENDING", 409, "Operation is not pending")
		for _, earlier := range u.All("operation") {
			if p.S(earlier, "productionOrderId") == orderID && p.N(earlier, "sequence") < p.N(op, "sequence") {
				p.Check(p.S(earlier, "status") == "COMPLETED", 409, "Predecessor not completed")
			}
		}
		available(u, op)
		op["status"] = "IN_PROGRESS"
		op["actualStartTime"] = time.Now().UTC().Format(time.RFC3339Nano)
	case "pause":
		p.Check(p.S(op, "status") == "IN_PROGRESS", 409, "Operation is not active")
		op["status"] = "PAUSED"
	case "resume":
		p.Check(p.S(op, "status") == "PAUSED", 409, "Operation is not paused")
		available(u, op)
		op["status"] = "IN_PROGRESS"
	case "complete":
		if p.S(op, "status") == "COMPLETED" {
			return op
		}
		p.Check(p.S(op, "status") == "IN_PROGRESS", 409, "Operation is not active")
		_, hasProcessed := b["processedQuantity"]
		_, hasRejected := b["rejectedQuantity"]
		p.Check(hasProcessed && hasRejected, 422, "Quantity fields required")
		pn, rn := p.N(b, "processedQuantity"), p.N(b, "rejectedQuantity")
		p.Check(pn >= 0 && rn >= 0 && math.Trunc(pn) == pn && math.Trunc(rn) == rn && pn+rn == p.N(order, "quantity"), 422, "Processed and rejected quantities must equal order quantity")
		p.Check(rn == 0, 422, "Rejected units require rework before completing this operation")
		op["processedQuantity"] = pn
		op["rejectedQuantity"] = rn
		op["status"] = "COMPLETED"
		op["actualCompletionTime"] = time.Now().UTC().Format(time.RFC3339Nano)
	default:
		panic(p.Fault{Status: 404, Code: "NOT_FOUND", Message: "Unknown operation action"})
	}
	u.Put("operation", parts[3], op)
	events := map[string]string{"start": "ManufacturingOperationStarted", "pause": "ManufacturingOperationPaused", "resume": "ManufacturingOperationResumed", "complete": "ManufacturingOperationCompleted"}
	u.Emit(events[action], orderID, op)
	done := true
	count := 0
	for _, operation := range u.All("operation") {
		if p.S(operation, "productionOrderId") == orderID {
			count++
			done = done && p.S(operation, "status") == "COMPLETED"
		}
	}
	if done && count == 7 && !p.B(order, "operationsComplete") {
		order["operationsComplete"] = true
		u.Put("shop-order", orderID, order)
		u.Emit("AllManufacturingOperationsCompleted", orderID, order)
	}
	return op
}

// Paused operations retain their lane until resumed or cancelled.
func available(u *p.Unit, op p.Doc) {
	for _, other := range u.All("operation") {
		p.Check(!(p.S(other, "id") != p.S(op, "id") && p.S(other, "workCenterId") == p.S(op, "workCenterId") && p.N(other, "lane") == p.N(op, "lane") && (p.S(other, "status") == "IN_PROGRESS" || p.S(other, "status") == "PAUSED")), 409, "Work-center lane is occupied")
	}
	if equipment, ok := u.Find("equipment", p.S(op, "equipmentId")); ok {
		p.Check(p.S(equipment, "status") == "AVAILABLE", 409, "Equipment unavailable")
	}
}

func (s *Service) equipment(u *p.Unit, b p.Doc) {
	eventID := p.Require(b, "eventId")
	_, err := uuid.Parse(eventID)
	p.Check(err == nil, 422, "Invalid event ID")
	if _, ok := u.Find("equipment-observation", eventID); ok {
		return
	}
	equipmentID := p.Require(b, "equipmentId")
	workCenterID := p.Require(b, "workCenterId")
	valid := false
	for _, op := range u.All("operation") {
		valid = valid || (p.S(op, "equipmentId") == equipmentID && p.S(op, "workCenterId") == workCenterID)
	}
	p.Check(valid, 422, "Unknown equipment/work-center pair")
	sequence := p.N(b, "sequence")
	p.Check(sequence > 0 && math.Trunc(sequence) == sequence, 422, "Positive integer equipment sequence required")
	eventType := p.Require(b, "eventType")
	switch eventType {
	case "EQUIPMENT_STARTED",
		"EQUIPMENT_STOPPED",
		"EQUIPMENT_FAILURE",
		"EQUIPMENT_RECOVERED",
		"PRODUCTION_CYCLE_COMPLETED",
		"MAINTENANCE_REQUIRED":
	default:
		p.Check(false, 422, "Unknown equipment event")
	}
	u.Put("equipment-observation", eventID, b)
	equipment, ok := u.Find("equipment", equipmentID)
	if !ok {
		equipment = p.Doc{"id": equipmentID, "status": "AVAILABLE", "sequence": 0}
	}
	// Retain late observations for audit, but never let them rewind equipment state.
	if sequence <= p.N(equipment, "sequence") {
		return
	}
	previous := p.S(equipment, "status")
	equipment["sequence"] = sequence
	switch eventType {
	case "EQUIPMENT_STOPPED", "EQUIPMENT_FAILURE", "MAINTENANCE_REQUIRED":
		equipment["status"] = "UNAVAILABLE"
	case "EQUIPMENT_RECOVERED":
		equipment["status"] = "AVAILABLE"
	}
	if p.S(equipment, "status") == "UNAVAILABLE" && previous != "UNAVAILABLE" {
		equipment["unavailableSince"] = time.Now().UTC().Format(time.RFC3339Nano)
	}
	if p.S(equipment, "status") == "AVAILABLE" && previous == "UNAVAILABLE" {
		if since, err := time.Parse(time.RFC3339Nano, p.S(equipment, "unavailableSince")); err == nil {
			equipment["downtimeSeconds"] = p.N(equipment, "downtimeSeconds") + time.Since(since).Seconds()
			delete(equipment, "unavailableSince")
		}
	}
	u.Put("equipment", equipmentID, equipment)
	if p.S(equipment, "status") == "UNAVAILABLE" {
		for _, op := range u.All("operation") {
			if p.S(op, "equipmentId") == equipmentID && p.S(op, "status") == "IN_PROGRESS" {
				op["status"] = "PAUSED"
				u.Put("operation", p.S(op, "id"), op)
				u.Emit("ManufacturingOperationPaused", p.S(op, "productionOrderId"), op)
			}
		}
	}
	payload := p.Clone(b)
	payload["equipmentStatus"] = p.S(equipment, "status")
	u.Emit("EquipmentStateChanged", equipmentID, payload)
}

func (s *Service) On(u *p.Unit, e p.Event) {
	id := p.S(e.Payload, "productionOrderId")
	switch e.EventType {
	case "ProductionOrderStarted":
		order, ok := u.Find("shop-order", id)
		if !ok {
			order = p.Clone(e.Payload)
		}
		if p.S(order, "status") != "CANCELLED" {
			order["authorized"] = true
			u.Put("shop-order", id, order)
		}
	case "ProductionOrderCancelled":
		// Keep the cancellation even if authorization or the schedule arrives later.
		order, ok := u.Find("shop-order", id)
		if !ok {
			order = p.Clone(e.Payload)
		}
		order["status"] = "CANCELLED"
		u.Put("shop-order", id, order)
		for _, op := range u.All("operation") {
			if p.S(op, "productionOrderId") == id {
				op["status"] = "CANCELLED"
				u.Put("operation", p.S(op, "id"), op)
			}
		}
	case "ProductionScheduleApproved":
		order, ok := u.Find("shop-order", id)
		if !ok {
			order = p.Clone(e.Payload)
		}
		if p.S(order, "status") == "CANCELLED" {
			return
		}
		u.Put("shop-order", id, order)
		tasks := p.List(e.Payload, "tasks")
		p.Check(len(tasks) == 7, 422, "Seven tasks required")
		for _, task := range tasks {
			opID := p.Require(task, "id")
			if _, exists := u.Find("operation", opID); !exists {
				op := p.Clone(task)
				op["status"] = "PENDING"
				u.Put("operation", opID, op)
			}
		}
	case "ProductionScheduleChanged":
		for _, task := range p.List(e.Payload, "tasks") {
			if op, exists := u.Find("operation", p.S(task, "id")); exists {
				op["plannedStartTime"] = task["plannedStartTime"]
				op["plannedEndTime"] = task["plannedEndTime"]
				u.Put("operation", p.S(op, "id"), op)
			}
		}
	case "EquipmentEventReceived":
		s.equipment(u, e.Payload)
	}
}
