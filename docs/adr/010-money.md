# ADR-010: Integer minor units and explicit currency

Status: accepted and implemented; see IMPLEMENTATION.md for verification gates and documented refinements.

## Context

Floating point cannot reliably represent monetary values. Arbitrary decimals require rounding policy at every boundary.

## Decision

Java long/PostgreSQL bigint, INR exponent 2 initially, checked arithmetic, bounded amounts and numeric aggregate sums. Currency metadata explicit; no FX.

## Alternatives

BigDecimal with strict scale; arbitrary precision decimal everywhere.

## Consequences and tradeoffs

Exact simple operations and clear wire units; overflow/JS precision need guards and decimal-string large balances. Adding currencies needs explicit exponent/product rules and separate accounts.
