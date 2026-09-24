# ADR-001: Modular monolith before microservices

Status: accepted design, 2026-09-25. Implementation pending.

## Context

Financial commands span payment, accounts, ledger, audit and event intent. Splitting these first would replace a straightforward transaction with a distributed protocol.

## Decision

One Spring deployment, feature-owned tables/APIs, ArchUnit and Modulith boundary checks. Separate worker deployment remains possible with the same codebase.

## Alternatives

Independent services; a global layered CRUD structure.

## Consequences and tradeoffs

Atomic commands and simpler local operation; shared database/deployment and risk of package erosion. Extract only when measured scaling or ownership requires it.
