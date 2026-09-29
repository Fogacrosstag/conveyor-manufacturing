package com.conveyor.platform;

import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.Instant;
import java.util.UUID;

public record Event(
    String eventId,
    String eventType,
    int eventVersion,
    String timestamp,
    String source,
    String correlationId,
    String aggregateId,
    ObjectNode payload) {
  public static Event create(String type, String source, String id, ObjectNode body) {
    return new Event(
        UUID.randomUUID().toString(),
        type,
        1,
        Instant.now().toString(),
        source,
        Context.id(),
        id,
        body.deepCopy());
  }

  public void validate() {
    D.check(
        java.util.Set.of(
                "EngineeringSpecificationCreated",
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
                "FinalQualityApproved")
            .contains(eventType),
        "Unknown event type");
    D.check(eventVersion == 1, "Unsupported event version");
    UUID.fromString(eventId);
    UUID.fromString(aggregateId);
    UUID.fromString(correlationId);
    Instant.parse(timestamp);
    D.check(!eventType.isBlank() && !source.isBlank(), "Missing event metadata");
    D.check(payload != null, "Missing event payload");
  }
}
