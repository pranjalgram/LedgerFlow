# ADR-009: First-party credentials and Spring JWT resource server

Status: accepted and implemented; see IMPLEMENTATION.md for verification gates and documented refinements.

## Context

A mandatory identity server complicates initial development, but handwritten JWT parsing is unnecessary and risky.

## Decision

Argon2id credentials, asymmetric short JWTs validated by Spring Security, hashed rotating refresh tokens, database memberships/scopes. API keys use Security authentication provider.

## Alternatives

Local Keycloak OIDC; session-only auth; custom JWT filter.

## Consequences and tradeoffs

Fewer runtime dependencies but ownership of refresh/reuse/signing-key security. OIDC can replace login while subject mapping and tenant RBAC remain. MFA/email verification are later scope and must be disclosed.

Phase 2 refinement: token transport is explicit JSON/Bearer, with frontend memory storage and re-login after reload. This avoids ambient authentication and cookie CSRF complexity. No cookie/session authentication is enabled. A future persistent browser session needs a separately reviewed cookie/CSRF design. One configured RSA pair is implemented first; overlapping key rotation remains a documented limitation.
