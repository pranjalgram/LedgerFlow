# ADR-003: Immutable double-entry ledger

Status: accepted design, 2026-09-25. Implementation pending.

## Context

Wallet counters alone cannot explain funding, correction or historical movement.

## Decision

Balanced single-currency journals with positive debit/credit entries, append guards, restricted posting function and deferred commit validation. Compensate instead of rewriting.

## Alternatives

Mutable transaction rows; application-only balance validation.

## Consequences and tradeoffs

Auditable derivation and defense against runtime misuse; more schema/function testing and storage. Superuser access remains a trust boundary.
