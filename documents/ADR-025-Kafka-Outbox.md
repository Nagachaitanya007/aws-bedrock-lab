# ADR-025: Migration to Apache Kafka for Outbox Event Streaming

## Context and Problem Statement
The current checkout and payment flows rely on synchronous HTTP calls between microservices, resulting in cascading failures whenever payment gateways experience latency spikes.

## Decision
We decided to adopt the Transactional Outbox Pattern using Apache Kafka. Outbox events will be committed atomically within the PostgreSQL transaction log and published to Kafka topics using Debezium CDC connectors.

## Consequences
- Decouples downstream payment processing from synchronous client checkouts.
- Guaranteed at-least-once message delivery.
- Consumer microservices must implement idempotent message deduplication using Redis transaction locks.
