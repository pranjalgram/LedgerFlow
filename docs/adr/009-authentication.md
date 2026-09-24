# ADR-009: First-party credentials and Spring JWT resource server

Status: accepted design, 2026-09-25. Implementation pending.

## Context

A mandatory identity server complicates initial development, but handwritten JWT parsing is unnecessary and risky.

## Decision

Argon2id credentials, asymmetric short JWTs validated by Spring Security, hashed rotating refresh tokens, database memberships/scopes. API keys use Security authentication provider.

## Alternatives

Local Keycloak OIDC; session-only auth; custom JWT filter.

## Consequences and tradeoffs

Fewer runtime dependencies but ownership of refresh/reuse/signing-key security. OIDC can replace login while subject mapping and tenant RBAC remain. MFA/email verification are later scope and must be disclosed.
