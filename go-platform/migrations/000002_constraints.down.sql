ALTER TABLE records DROP CONSTRAINT production_quantity;
ALTER TABLE records DROP CONSTRAINT material_balance;
DROP INDEX work_center_sequence_unique;
DROP INDEX work_center_code_unique;
DROP INDEX engineering_revision_unique;
DROP INDEX material_code_unique;
DROP INDEX product_code_unique;
