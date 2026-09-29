#!/bin/bash
set -euo pipefail
for topic in engineering production-order material production-planning shop-floor quality-control equipment legacy; do
 for suffix in events events.dlq; do
  /opt/kafka/bin/kafka-topics.sh --bootstrap-server kafka:9092 --create --if-not-exists --topic "$topic.$suffix" --partitions 3 --replication-factor 1
 done
done
