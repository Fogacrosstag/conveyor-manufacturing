package application

import (
	p "conveyor.local/platform"
	"encoding/json"
	"net/url"
	"sort"
	"strconv"
	"strings"
)

type Service struct{}

func (s *Service) Kinds() []string { return []string{"audit-event"} }

func (s *Service) Handle(u *p.Unit, method, path string, b p.Doc) any {
	p.Check(method == "GET", 405, "Audit history is read only")
	parsed, err := url.Parse(path)
	p.Must(err)
	parts := strings.Split(parsed.Path, "/")
	p.Check(len(parts) >= 3 && parts[1] == "audit", 404, "Unknown audit resource")
	if parts[2] == "events" && len(parts) == 4 {
		return u.Get("audit-event", parts[3])
	}
	limit, err := strconv.Atoi(parsed.Query().Get("limit"))
	if err != nil {
		limit = 100
	}
	p.Check(limit > 0 && limit <= 500, 422, "Limit must be between 1 and 500")
	offset, err := strconv.Atoi(parsed.Query().Get("offset"))
	if err != nil {
		offset = 0
	}
	p.Check(offset >= 0, 422, "Offset cannot be negative")
	items := []p.Doc{}
	for _, event := range u.All("audit-event") {
		payload, _ := event["payload"].(map[string]any)
		match := true
		if len(parts) == 4 {
			field := map[string]string{"production-orders": "productionOrderId", "materials": "materialId", "equipment": "equipmentId", "work-centers": "workCenterId"}[parts[2]]
			p.Check(field != "", 404, "Unknown filter")
			match = p.S(p.Doc(payload), field) == parts[3] || p.S(event, "aggregateId") == parts[3]
		}
		if t := parsed.Query().Get("eventType"); t != "" {
			match = match && p.S(event, "eventType") == t
		}
		if match {
			items = append(items, event)
		}
	}
	sort.Slice(items, func(i, j int) bool {
		left, right := p.S(items[i], "timestamp"), p.S(items[j], "timestamp")
		if left == right {
			return p.S(items[i], "eventId") < p.S(items[j], "eventId")
		}
		return left < right
	})
	total := len(items)
	if offset > total {
		offset = total
	}
	end := offset + limit
	if end > total {
		end = total
	}
	return p.Doc{"items": items[offset:end], "total": total, "offset": offset, "limit": limit}
}

func (s *Service) On(u *p.Unit, e p.Event) {
	var event p.Doc
	p.Must(json.Unmarshal(p.JSON(e), &event))
	u.Put("audit-event", e.EventID, event)
}
