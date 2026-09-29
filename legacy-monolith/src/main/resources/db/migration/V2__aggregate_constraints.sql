-- Database-enforced invariants complement application validation and transaction locks.
CREATE UNIQUE INDEX product_code_unique ON records ((body->>'code')) WHERE kind='product';
CREATE UNIQUE INDEX material_code_unique ON records ((body->>'materialCode')) WHERE kind='material';
CREATE UNIQUE INDEX engineering_revision_unique ON records (kind,(body->>'productId'),(body->>'revision')) WHERE kind IN ('specification','bom');
CREATE UNIQUE INDEX work_center_code_unique ON records ((body->>'code')) WHERE kind='work-center';
CREATE UNIQUE INDEX work_center_sequence_unique ON records ((body->>'sequence')) WHERE kind='work-center';
ALTER TABLE records ADD CONSTRAINT material_balance CHECK (
 kind <> 'material' OR (
 body ? 'quantityOnHand' AND body ? 'reservedQuantity' AND body ? 'availableQuantity'
 AND (body->>'quantityOnHand')::numeric >= (body->>'reservedQuantity')::numeric
 AND (body->>'reservedQuantity')::numeric >= 0
 AND (body->>'availableQuantity')::numeric = (body->>'quantityOnHand')::numeric - (body->>'reservedQuantity')::numeric
 ));
ALTER TABLE records ADD CONSTRAINT production_quantity CHECK (
 kind <> 'order' OR (body ? 'quantity' AND (body->>'quantity')::numeric > 0 AND (body->>'quantity')::numeric = trunc((body->>'quantity')::numeric)));
