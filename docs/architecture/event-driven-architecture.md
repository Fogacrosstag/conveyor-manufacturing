# Event flow

```mermaid
sequenceDiagram
  participant Orders
  participant Materials
  participant Planning
  participant ShopFloor
  participant Quality
  Orders->>Materials: Submitted event
  Materials->>Orders: Reserved fact
  Materials->>Planning: Reserved event
  Planning->>ShopFloor: Approved schedule
  Planning->>Orders: Approved schedule fact
  Orders->>ShopFloor: Start authorization
  ShopFloor->>Materials: All operations complete
  ShopFloor->>Quality: All operations complete
  ShopFloor->>Orders: Operations complete fact
  Materials->>Orders: Consumption confirmed
  Quality->>Orders: Final quality approved
```

Arrows above are Kafka events; Kafka is omitted as a participant to keep the diagram readable. The actual envelope and topics are in `docs/events/CONTRACT.md`. Audit independently consumes every domain topic. Engineering/product validation uses synchronous HTTP before order creation.

The baseline performs equivalent handler dispatch inside the existing transaction and writes events to `legacy.events`. During partial extraction, local capabilities remain synchronous and remote capabilities consume legacy events. The legacy consumer must be enabled to receive responses from extracted owners.
