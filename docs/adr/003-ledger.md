# ADR-003: Immutable double-entry ledger

Status: accepted and implemented; see IMPLEMENTATION.md for verification gates and documented refinements.

## Context

Wallet counters alone cannot explain funding, correction or historical movement.

## Decision

Balanced single-currency journals with positive debit/credit entries, append guards, restricted posting function and deferred commit validation. Compensate instead of rewriting.

## Alternatives

Mutable transaction rows; application-only balance validation.

## Consequences and tradeoffs

Auditable derivation and defense against runtime misuse; more schema/function testing and storage. Superuser access remains a trust boundary.
