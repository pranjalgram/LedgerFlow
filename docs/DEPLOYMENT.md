# Deployment

## Full local application

Prerequisites: Docker Compose v2, Java 25 to generate local keys, and available host ports 5432, 8080 and 8088. Change POSTGRES_PORT in .env if a local PostgreSQL already uses 5432. Container-to-container database traffic always uses 5432.

On a new checkout, from the root:

```sh
java scripts/GenerateLocalEnvironment.java
java scripts/GenerateDevKeys.java
java scripts/GenerateWebhookKey.java
java scripts/GenerateObservabilitySecrets.java
SPRING_PROFILES_ACTIVE=demo EVENTS_ENABLED=true docker compose --profile app --profile events up --build -d --wait
```

On PowerShell set `$env:SPRING_PROFILES_ACTIVE='demo'` and `$env:EVENTS_ENABLED='true'` before the Compose command. Generators refuse to overwrite existing credentials. On Linux bind-mounted key/config files must be readable by container UID 10001; use a dedicated group with ACLs or a secret store. The isolated CI runner uses world-readable ephemeral files and is destroyed after the job; do not copy that permission choice to a shared host.

Open http://localhost:8088. The browser sends same-origin requests through Nginx to the API. Register a merchant, create CUSTOMER and SETTLEMENT wallets, fund the customer wallet, and create a transfer/payment. Funding exists only with the demo profile. No account credentials are hardcoded. The PostgreSQL-only profile remains the lightweight host-development workflow.

The migration container must exit successfully before the backend starts. It uses the same built jar with a standalone Flyway entry point: no web server, workers or Kafka startup. The API disables Flyway and receives only its restricted runtime database password. Runtime and frontend use non-root UIDs, read-only filesystems, dropped capabilities and writable /tmp only. Frontend CSP permits inline styles because the chart library uses them, while scripts require the same origin. API/health limits are enforced by the backend; Nginx permits only the minimal readiness route under /actuator.

Images build from source with the checked Gradle distribution checksum and dependency lockfiles. Java build/runtime images are Temurin 25.0.4+7; frontend build is Node 24.21.0 and serving image is Nginx 1.30.5. Version tags are pinned but mutable upstream; a release pipeline should record/sign image digests and scan before deployment. Builds deliberately do not run Testcontainers inside Docker; CI runs those tests separately before packaging.

`docker compose --profile app --profile events down` preserves financial volumes. Do not add `-v` unless intentionally destroying a disposable environment. Rebuild after code changes. Adding `--profile observability` enables the monitoring services; see OBSERVABILITY.md. Event workers remain disabled unless EVENTS_ENABLED=true, with outbox intent retained in PostgreSQL.

## Kubernetes / Helm

The chart under `infra/helm/ledgerflow` deploys only API, frontend, Services, ConfigMaps, optional TLS Ingress, HPA, PDB and a pre-install/pre-upgrade migration Job. It does not provision PostgreSQL, Kafka, Redis or telemetry storage. Managed data services normally simplify backups, failover and operations. No Kubernetes cluster is needed locally.

Build and push application images to a registry you control; set backend.image/frontend.image to immutable digest references in a private values file. Provide an existing namespace, TLS certificate Secret, ingress controller and metrics-server for HPA. Create these Secrets with a secret manager before installation (never commit values):

| Secret (default name) | Required keys |
| --- | --- |
| ledgerflow-runtime | db-password, metrics-password, webhook-key (base64-encoded 32-byte AES key as text), jwt-private.pem, jwt-public.pem |
| ledgerflow-migration | username, password |
| ledgerflow-tls | tls.crt, tls.key |

Configure database.url/username, kafka.bootstrapServers/securityProtocol, issuer, webhookAllowedOrigins and optional tracing in the values file. Supply PostgreSQL server trust material appropriate to the managed provider; verify-full requires trusted roots. The example Kafka setting uses server TLS; clusters requiring SASL/mTLS need provider-specific secret mounts and Spring Kafka properties added before deployment. No public plaintext broker or invented credentials are provided.

Database bootstrap must create the ledgerflow_runtime and ledgerflow_ledger_owner NOLOGIN roles, and a ledgerflow_app login inheriting runtime. The migration identity needs schema creation/alteration and permission to assign security-definer functions to ledgerflow_ledger_owner. Local Docker uses the database owner; managed-provider administrators must grant appropriate membership/schema privileges for migrations. Do not grant these privileges to the runtime login. Test this provisioning on the actual managed provider before rollout.

```sh
helm lint infra/helm/ledgerflow
helm template ledgerflow infra/helm/ledgerflow -f private-values.yaml
helm upgrade --install ledgerflow infra/helm/ledgerflow -n ledgerflow -f private-values.yaml --wait --timeout 5m
```

The migration hook blocks a release on failure. Failed jobs remain for diagnosis; a subsequent upgrade replaces the job. Migrations must use expand/contract changes compatible with existing replicas. A Helm rollback does not reverse committed SQL migrations; restore from tested backups or deploy a forward repair. Never automate journal edits as rollback.

`infra/kubernetes/ledgerflow.yaml` is a rendered example from this chart with Ingress enabled; image/provider URLs are intentionally explicit placeholders. Regenerate with `helm template ledgerflow infra/helm/ledgerflow --set ingress.enabled=true`. Plain kubectl ignores Helm hooks: apply/wait for the migration Job first, then apply workload resources. The example is not an unattended production installer.

HPA starts at two replicas and caps at five, with 300-second downscale stabilization. Ten database connections per API replica means at least 50 possible runtime connections at that cap, plus migrations/operators. CPU scaling does not solve hot-wallet contention or database bottlenecks. Readiness depends on PostgreSQL; liveness does not depend on Kafka. Graceful termination allows active requests to finish. Configure private network policies, egress restrictions, registry authentication and secret rotation for the target environment; these are not silently supplied by a generic chart.

## Validation status

Local image builds and Compose startup passed. The standalone migration upgraded the local database through V10 and the API became healthy with Flyway disabled. Non-root UIDs were inspected. Chromium passed the complete financial flow against the packaged frontend at port 8088. Helm 4.3.0 lint and template rendering passed; no Kubernetes context is configured, so cluster rollout and HPA behavior are not claimed as tested. CI includes a clean-volume Docker/browser job; remote CI execution has not yet been observed.
