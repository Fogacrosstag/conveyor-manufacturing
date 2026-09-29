#!/bin/sh
set -eu
psql -v ON_ERROR_STOP=1 --username "$POSTGRES_USER" --dbname postgres <<'SQL'
CREATE ROLE legacy LOGIN PASSWORD 'local-development-only';
CREATE DATABASE legacy OWNER legacy;
REVOKE CONNECT ON DATABASE legacy FROM PUBLIC;
GRANT CONNECT ON DATABASE legacy TO legacy;
CREATE ROLE engineering LOGIN PASSWORD 'local-development-only';
CREATE DATABASE engineering OWNER engineering;
REVOKE CONNECT ON DATABASE engineering FROM PUBLIC;
GRANT CONNECT ON DATABASE engineering TO engineering;
CREATE ROLE production_order LOGIN PASSWORD 'local-development-only';
CREATE DATABASE production_order OWNER production_order;
REVOKE CONNECT ON DATABASE production_order FROM PUBLIC;
GRANT CONNECT ON DATABASE production_order TO production_order;
CREATE ROLE material LOGIN PASSWORD 'local-development-only';
CREATE DATABASE material OWNER material;
REVOKE CONNECT ON DATABASE material FROM PUBLIC;
GRANT CONNECT ON DATABASE material TO material;
CREATE ROLE production_planning LOGIN PASSWORD 'local-development-only';
CREATE DATABASE production_planning OWNER production_planning;
REVOKE CONNECT ON DATABASE production_planning FROM PUBLIC;
GRANT CONNECT ON DATABASE production_planning TO production_planning;
CREATE ROLE shop_floor LOGIN PASSWORD 'local-development-only';
CREATE DATABASE shop_floor OWNER shop_floor;
REVOKE CONNECT ON DATABASE shop_floor FROM PUBLIC;
GRANT CONNECT ON DATABASE shop_floor TO shop_floor;
CREATE ROLE quality_control LOGIN PASSWORD 'local-development-only';
CREATE DATABASE quality_control OWNER quality_control;
REVOKE CONNECT ON DATABASE quality_control FROM PUBLIC;
GRANT CONNECT ON DATABASE quality_control TO quality_control;
CREATE ROLE manufacturing_audit LOGIN PASSWORD 'local-development-only';
CREATE DATABASE manufacturing_audit OWNER manufacturing_audit;
REVOKE CONNECT ON DATABASE manufacturing_audit FROM PUBLIC;
GRANT CONNECT ON DATABASE manufacturing_audit TO manufacturing_audit;
SQL
