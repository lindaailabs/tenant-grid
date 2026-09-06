# Tenant Grid

> Multi-tenant database placement & lifecycle for SaaS — hybrid physical/logical shard routing, made simple.

[![CI](https://github.com/lindaailabs/tenant-grid/actions/workflows/ci.yml/badge.svg)](https://github.com/lindaailabs/tenant-grid/actions/workflows/ci.yml)
[![License](https://img.shields.io/badge/License-Apache%202.0-blue.svg)](LICENSE)

**English** | [简体中文](README-zh.md)

## Introduction

Tenant Grid is a Java library for multi-tenant database routing, delivered as a Spring Boot starter: key accounts go to dedicated physical databases, long-tail tenants to shared logical ones — and Tenant Grid resolves the right datasource for every operation, keeping routing, isolation and migration state out of your business code.

**What you get:**

- **Hybrid shard routing** — `tenantId → DataSource` resolution for dedicated (physical) and shared (logical) databases alike; metadata from config, JDBC, or your own SPI
- **Hot-swappable datasources** — onboard new tenants' databases and retire old ones at runtime, no restart
- **Noisy-neighbor isolation** — per-tenant connection quotas, token-bucket rate limiting, statement timeouts, and automatic degradation & recovery
- **Online migration** — an orchestration state machine (dual-write → catch-up → verify → cut-over → rollback); the actual data movement stays pluggable via SPI (DataX / Debezium / homegrown)
- **SQL tenant-column guard** (optional) — AST-based verification that row-level SQL carries its tenant predicate
- **Observability** — a single Actuator endpoint aggregating datasources, per-tenant usage, cache stats and active migrations

The routing core (`tenant-grid-core`) has zero Spring dependencies and works standalone; the starter adds auto-configuration for Spring Boot 4.x. Requires Java 17+.

## What It Solves

Multi-tenant SaaS systems — overseas warehousing being a typical case — rarely settle on a single data-placement mode:

- **Key accounts**: a dedicated physical database; physical isolation protects the SLA
- **Long-tail customers**: shared logical databases, sharded by `tenant_id` to drive costs down

Tenant Grid packages this hybrid strategy into a reusable routing layer, and layers on the three
things every SaaS team ends up hand-rolling anyway: **tenant tier-up/down migration**,
**tenant isolation within shared databases**, and **per-tenant observability**.

## Why Not an Existing Solution

| Option | What it is | Why it falls short |
|--------|------------|-------------------|
| Apache ShardingSphere | SQL parsing/rewriting, sharding, cross-DB merge | Multi-tenancy isn't a first-class citizen; in this scenario one request lands on exactly one DB, so you'd never use cross-DB merging — yet you'd carry the SQL-compatibility and version-coupling burden |
| `dynamic-datasource` | Dynamic datasource switching | Solves only "which datasource" — no tenant model, no migration, no isolation quotas |
| Hand-rolled glue (Spring dynamic datasource + ShardingSphere) | The mainstream recipe online | Every team re-invents it |

**Design tradeoff**: the hybrid strategy is **database-level routing** (`tenant_id → DataSource`),
not table-level sharding — one request lands on exactly one database. The core is therefore built on
`AbstractRoutingDataSource`, with no ShardingSphere dependency. Only when a shared database also
needs internal table sharding (say `stock_log` partitioned by month) do you nest ShardingSphere at
that level — as an option, never a hard dependency.

The nesting point is **before datasource registration**: wrap the sharding capability on first.
Tenant Grid knows only the `DataSource` abstraction — whether HikariCP or ShardingSphere sits
underneath is none of its business:

```java
// ds_std_0 shards stock_log by month internally
DataSource sharding = ShardingSphereDataSourceFactory.createDataSource(schemaConfig);

registry.register("ds_std_0", sharding);   // once registered, routing lands tenants on it as usual
```

A request therefore still has two levels: pick the database by tenant (tenant-grid), then shard
within the database (ShardingSphere). The former decides where a tenant lives; the latter decides
how big a single table gets. They stay out of each other's way.

## Architecture

```
┌──────────────────────────────────────────────────────┐
│                   Business code                       │
│    Knows only tenantId — never which DB data is in   │
└───────────────────────┬──────────────────────────────┘
                        │
┌───────────────────────▼──────────────────────────────┐
│              TenantRouter (routing core)              │
│   tenantId → ShardPlan{ dsKey, status }               │
│   PHYSICAL: returns the dedicated DB as-is            │
│   LOGICAL : hashes onto shared DB 0..N                │
└───────────────────────┬──────────────────────────────┘
                        │
┌───────────────────────▼──────────────────────────────┐
│   tenant_shard metadata (config center / DB / SPI)    │
└──────────────────────────────────────────────────────┘
```

Layered responsibilities:

| Layer | Module | Responsibility |
|-------|--------|----------------|
| Routing core | `tenant-grid-core` | tenant → shard resolution, zero Spring dependencies |
| Integration | `tenant-grid-spring-boot-starter` | auto-configuration, context propagation, dynamic datasource, Actuator |
| Isolation | `tenant-grid-core` · `quota` package | noisy-neighbor defense: connection quotas, token-bucket rate limiting, auto-degradation |
| Migration | `tenant-grid-core` · `migration` package | migration orchestration: dual-write → catch-up → verify → cut over → rollback |

Isolation and migration are not separate modules: they depend directly on routing-core types
(`TenantShard`, `ShardPlan`). Splitting them out would mean either a reverse dependency on core or
duplicated type definitions. Package-level separation beats module-level here.

## Modules

| Module | Description | Status |
|--------|-------------|--------|
| `tenant-grid-core` | Routing core: `TenantRouter` / `TenantShard` / `MetadataProvider` SPI (in-memory / JDBC) / `TenantContext` (TTL) / `DataSourceRegistry` / `CachingMetadataProvider` / `QuotaGuard` / `RateLimiter` / `MigrationCoordinator` + `JdbcMigrationStore` | ✅ |
| `tenant-grid-spring-boot-starter` | Auto-configuration / `RoutingDataSource` / context propagation / SQL-guard & quota weaving / `TenantGridEndpoint` (migration parts need explicit wiring) | ✅ |
| `tenant-grid-bom` | Dependency version management | ✅ |
| `tenant-grid-console` | Admin console | Not built — the UI belongs to the consumer; this library offers the Actuator endpoint and SPI to integrate with |

## Quick Start

**1. Add the dependency**

```xml
<dependency>
  <groupId>io.github.lindaailabs</groupId>
  <artifactId>tenant-grid-spring-boot-starter</artifactId>
  <version>0.5.0</version>
</dependency>
```

**2. Configure**

```yaml
tenant-grid:
  strict: true                        # fail fast when no tenant context (default true; strongly recommended to keep)
  datasources:
    ds_vip_1:                         # dedicated DB for a key account
      url: jdbc:mysql://vip-db:3306/vip1
      username: root
      password: secret
    ds_std_0:                         # long-tail shared DB
      url: jdbc:mysql://shared-db:3306/std0
      username: root
      password: secret
    ds_std_1:
      url: jdbc:mysql://shared-db:3306/std1
      username: root
      password: secret
  logical-groups:
    std:
      nodes: [ds_std_0, ds_std_1]     # order is the shard order
  tenants:
    vip_1: { shard-type: physical, ds-key: ds_vip_1 }
    t_001: { shard-type: logical,  logical-group: std }
    t_002: { shard-type: logical,  logical-group: std }
```

**3. Bind the tenant**

```java
// Declarative
TenantContext.runAs("vip_1", () -> orderService.create(dto));

// Async tasks must be wrapped, or the tenant is invisible inside the pool
executor.submit(TenantContext.wrap(() -> orderService.create(dto)));
```

From then on, business code writes SQL as usual; `RoutingDataSource` picks the datasource per tenant automatically.

> Business SQL must carry its own `tenant_id` predicate. Row-level isolation inside shared databases
> depends on it. For enforced checking see the [SQL tenant-column guard](#sql-tenant-column-guard)
> below (off by default; gray-release with `warn` first).

## Hot-swappable Datasources

Inject `DataSourceRegistry` to add or remove datasources at runtime — no restart required:

```java
@Service
public class TenantOnboardingService {

    private final DataSourceRegistry registry;

    // New key account onboarding: build the dedicated DB, then hot-add it
    public void onboardVip(String tenantId, String dsKey, DataSourceProperties spec) {
        registry.register(dsKey, buildDataSource(spec));
    }

    // Retire an old DB: cut traffic first, give in-flight requests a 30s grace period, then close the pool
    public void retire(String dsKey) {
        registry.unregisterAndClose(dsKey, Duration.ofSeconds(30));
    }
}
```

**The retirement order is deliberate**: remove from the registry first (new traffic stops
immediately) → let in-flight requests drain during the grace period → only then actually `close()`
the pool. Reverse the order and you get "new requests still arriving while connections are already closed".

Removal takes effect immediately — the next route will no longer hit that DB. If some tenant still
points at it, you get an `UnknownDataSourceException` (with the list of registered keys), never a
silent fall-through to another database.

## Metadata Persistence

Tenants from the config file — and the in-memory implementation — cannot persist two things that
change: **tenants onboarded at runtime**, and the **`MIGRATING` mark stamped on a tenant during
migration**. The latter is the dangerous one: after a process restart the mark is gone, routing no
longer sees the tenant as dual-writing, and dual-write silently stops — while the migration task
itself still sits at `CATCH_UP`, and the target database ends up missing the entire dual-write window.

Use `JdbcMetadataProvider` to persist, backed by the `tenant_shard` table:

```sql
-- Output of JdbcMetadataProvider.ddl(); standard types, runs as-is on MySQL / PostgreSQL / H2
CREATE TABLE IF NOT EXISTS tenant_shard (
    tenant_id     VARCHAR(64)   NOT NULL PRIMARY KEY,
    shard_type    VARCHAR(16)   NOT NULL,
    ds_key        VARCHAR(128),
    logical_group VARCHAR(128),
    status        VARCHAR(32)   NOT NULL,
    updated_at    TIMESTAMP     NOT NULL
)
```

**You must put a cache in front** — `find()` is invoked on every connection acquisition:

```java
@Bean
MetadataProvider tenantGridMetadata(DataSource metaDataSource) {   // the metadata DB, NOT the routing datasource
    JdbcMetadataProvider jdbc = new JdbcMetadataProvider(metaDataSource);
    return new CachingMetadataProvider(jdbc, Duration.ofSeconds(30), Duration.ofSeconds(5), 100_000);
}
```

`metaDataSource` must **not** be tenant-grid's routing datasource: the whole point of reading
metadata is to decide which database to use — querying it through the routing datasource is a
chicken-and-egg problem. The same reasoning applies to `JdbcMigrationStore`.

When the delegate is mutable, `CachingMetadataProvider` supports writes too — at cut-over it writes
through to the database first, then invalidates the local entry, guaranteeing an immediate read of
the new value (reading the pre-cut-over shard would send traffic back to the source DB). But it
guarantees **only this instance's** consistency: other instances' caches see the change when the TTL
expires; convergence time is TTL-bound. Cross-instance strong consistency relies on the consumer's
broadcast mechanism (config-center push / Redis pub-sub) and is out of scope; migration scenarios
rely on `MigrationCoordinator`'s `onMetadataChanged` callback to invalidate caches on every instance.

Migration's two tables each own half: `tenant_grid_migration` stores stage progress, `tenant_shard`
stores routing state. Persisting only the former isn't enough; neither is only the latter.

## Metadata Cache

Routing happens on **every connection acquisition** — the hottest read path. A local cache is
applied by default:

```yaml
tenant-grid:
  metadata-cache:
    enabled: true      # off = every connection acquisition queries the backend; debugging only
    ttl: 30s           # lifetime of a normal entry
    negative-ttl: 5s   # lifetime of a "tenant does not exist" entry; keep well below ttl
    max-size: 100000   # entry cap; prevents unbounded growth from tenant churn
```

Four design points, and the pitfalls each one closes:

| Mechanism | What happens without it |
|---|---|
| **TTL** | routing keeps pointing at the old DB long after a tenant migrates |
| **Stampede protection** (single-flight `computeIfAbsent`) | on a concurrent miss for the same tenant, every thread hammers the backend at once |
| **Negative caching** | nonexistent tenants punch through on every request — an amplified-traffic hole into the backend |
| **Bounded** | tenant churn makes the cache grow-only; eventually OOM |

Backend exceptions are **never** cached as "tenant doesn't exist" — otherwise errors would persist
even after the backend recovers.

After a tenant migration, invalidate proactively instead of waiting for the TTL:

```java
@Autowired CachingMetadataProvider metadata;

metadata.invalidate("vip_1");   // a single tenant
metadata.invalidateAll();       // everything
metadata.stats().hitRate();     // hit rate, for judging whether ttl / max-size fit
```

> If you provide your own `MetadataProvider` (backed by a config center / database),
> auto-configuration backs off entirely and the cache is yours to apply:
> `new CachingMetadataProvider(yourProvider, ttl, negativeTtl, maxSize)`.

## SQL Tenant-Column Guard

Inside a shared database, rows of different tenants are told apart by `tenant_id`; routing only
decides *which database*. **Row-level isolation within the DB depends entirely on the tenant
condition in the SQL** — miss it and you have a cross-tenant query.

Every SQL statement is intercepted at the JDBC layer and checked for a tenant column in its filter
conditions:

```yaml
tenant-grid:
  sql-guard:
    mode: enforce        # off (default) / warn / enforce
    tenant-column: tenant_id
    exempt-tables: [sys_*, dict_*]   # global-table exemptions; * and ? wildcards supported
```

```xml
<!-- Required explicitly when mode is not off (optional dependency, not forced) -->
<dependency>
  <groupId>com.github.jsqlparser</groupId>
  <artifactId>jsqlparser</artifactId>
  <version>5.3</version>
</dependency>
```

**Recommended adoption path**: run `warn` for a while and watch the logs; switch to `enforce` after
the legacy SQL is cleaned up. Turning `enforce` on cold in an existing system most likely means it
won't even boot.

Two entry points are intercepted: `Connection.prepareStatement/prepareCall` (all ORMs go through
here) and `Statement.execute*` (string-concatenated execution).

### Verification scope

| Verdict | Example |
|---|---|
| ✅ allowed | `WHERE tenant_id = ?`, `WHERE o.tenant_id = ?`, `INSERT INTO t (tenant_id, ..)`, JOIN ON conditions, subquery WHEREs |
| ❌ blocked | `WHERE id = ?`, condition-less queries, **`SELECT tenant_id FROM t WHERE id = ?`** (a projection column filters nothing) |
| ⏭ skipped | non-DML (DDL/SHOW), table-less statements (`SELECT 1` — common in health checks), exempt tables, parse failures, UNION branches |

### Two implementation tradeoffs worth explaining

**1. Validated getters, not visitor dispatch**
In JSqlParser 5.x, `expression.accept(visitor)` never calls back into a custom visitor (`visit(Column)`
fires zero times in practice). So this guard uses direct getters — `getWhere()` / `getHaving()` /
`getOnExpressions()` / `getColumns()` — to locate filter regions along the AST, then tokenizes
identifiers over the region's AST node text (string literals and numbers stripped first). Compared
to regexing raw SQL, regions are pinpointed by the AST: projection columns, table names, and string
literals can't be mistaken for filter conditions.

**2. When uncertain, allow — don't block**
Structures it can't drill into (UNION branches) or failed parses are marked "indeterminate" and
allowed through. **A guard's misjudgment costs are asymmetric**: a false allow is one missing layer
of defense; a false block is a production incident.

## Tenant Quota (Noisy Neighbors)

Dedicated physical databases isolate the key accounts, but long-tail tenants **inside a shared
logical database** still share one connection pool: a single tenant's slow queries or traffic bursts
can fill the pool and take every co-located tenant down with it.

Cap concurrent connections per tenant and fail fast on breach, rather than letting one tenant drain
the pool:

```yaml
tenant-grid:
  quota:
    enabled: true             # on by default
    permits-per-tenant: 50    # per-tenant concurrent connection cap; the default is generous
    slow-hold-threshold: 1s   # holds longer than this count as slow holds
    max-tracked-tenants: 100000
    statement-timeout: 0s     # per-statement execution timeout; 0 = unlimited (default)
    min-statement-timeout: 1s # the floor degradation can push the timeout down to
```

Breaching the cap throws `TenantQuotaExceededException` — **it affects only that one tenant**;
everyone else in the database keeps serving.

### SQL execution timeout

Quota governs *how many connections are held at once*, not *how long one query holds its
connection*. With only a concurrency cap, a few slow queries can eat the whole quota — they never
release, every request behind them gets rejected, and the quota looks like it's "working fine".
Timeout is the third dimension: make slow queries yield instead of squatting on connections.

The default is `0s` (unlimited): a correct timeout correlates strongly with your SQL latency
profile; a wrong default easily causes mass collateral damage — watch P99 first, then set it. For
auto-degraded tenants, timeout and quota share one set of penalty tiers — each level down halves it,
floored at `min-statement-timeout` (the floor is at least 1 second: in JDBC, 0 means unlimited, so
degrading to 0 would *lift* the restriction — the opposite of the intent).

Two implementation notes:

- **Set the setter, don't proxy the Statement**: `PreparedStatement` implementations usually carry
  pool-internal interfaces; a proxy breaks the type. One `setQueryTimeout` call after creation is
  all it takes. All three paths — `createStatement` / `prepareStatement` / `prepareCall` — are woven.
- **Driver-level statement caching leaks timeouts across tenants**: with MySQL
  `cachePrepStmts=true`, statements are reused across requests and one tenant's timeout can linger
  onto the next. Default pool config doesn't cache statements, so this doesn't apply; if you enable
  caching, use a uniform global timeout or don't rely on this mechanism.

Companion observability:

```java
@Autowired QuotaGuard quotaGuard;

for (TenantUsage u : quotaGuard.usage()) {
    u.tenantId(); u.active(); u.utilisation(); u.rejected(); u.slowHolds(); u.maxHoldMillis();
    u.penaltyLevel(); u.effectiveTimeoutSeconds();
}
```

- `rejected` climbing → that tenant's concurrency is saturated: either rein it in, or your quota is too small
- `slowHolds` climbing → that tenant has slow queries — prime suspect for dragging the shared DB down

### Two error-prone implementation details

**1. A rejected connection must go back to the pool**
`super.getConnection()` has already handed us the connection; throwing on breach without `close()`
gradually bleeds the pool dry — **worse than no protection at all**. Rate limiting and quota each
have dedicated tests asserting this.

**2. Release is bound to `close()`, and must be idempotent**
A proxy weaves the release into `close()`; business code never notices. But `close()` may be called
more than once — releasing twice would conjure permits out of thin air and gut the quota — so an
`AtomicBoolean` guarantees exactly-once. Release in `finally` even when close fails, or every failure
permanently leaks one permit.

## Rate Limiting & Auto-Degradation

Quota governs **concurrency** (holding on); rate limiting governs **velocity** (pouring in) —
orthogonal dimensions, and you need both. Block only concurrency, and short fast high-frequency
requests still crush the downstream.

```yaml
tenant-grid:
  rate-limit:
    enabled: false           # off by default
    permits-per-second: 250  # steady-state QPS
    burst-capacity: 80       # permitted instantaneous burst
  degradation:
    enabled: true            # on by default
    evaluation-interval: 30s
    min-permits: 5           # the floor degradation can push permits down to
    rejection-ratio-threshold: 0.2
    recovery-after-clean-evaluations: 3
```

**Rate limiting** is a token bucket with lazy refill (computed from elapsed time; no background thread).

**Auto-degradation** doesn't pre-assume who the noisy tenant is — it watches behavior: a tenant
repeatedly rejected by quota (rejection ratio above threshold within the window) gets its quota
pushed down; several consecutive clean evaluations climb one level back. It's **punitive
convergence**, not a permanent ban — one blip mustn't nail a tenant to low quota forever.

### Why the defaults differ

| Switch | Default | Reason |
|---|---|---|
| Quota | **on** | a generous 50-concurrency cap normal tenants never touch; only genuinely runaway ones get blocked |
| Degradation | **on** | takes effect only when a tenant is actually being rejected — a safety net, not a pre-restriction; no false harm |
| Rate limiting | **off** | a QPS ceiling correlates strongly with deployment capacity; a wrong default easily misfires — observe first |
| SQL timeout | **off** | correlates with your SQL latency profile; a wrong default causes mass collateral damage — watch P99 first |
| SQL guard | **off** | a false block is a production incident; existing systems need a `warn` gray-release first |

The criterion is **misjudgment cost**: a false allow is one missing layer of defense; a false block
is a production incident. The closer a switch sits to "can halt business", the more conservative
its default.

## Tenant Migration Coordinator

When a small seller grows into a key account, its data must move from the shared database to a
dedicated one — without downtime.

```
INIT → DUAL_WRITE → CATCH_UP → VERIFY → CUT_OVER → COMPLETED
                                                 ↘ ROLLED_BACK
```

**What this library does and doesn't do** — the boundary is drawn deliberately:

| | Owned by |
|---|---|
| Stage state machine, migration marks, routing switch at cut-over, rollback | tenant-grid |
| Actual data movement, consistency verification | Consumer (DataX / Debezium / homegrown scripts) |

Forcing a generic mover in would only produce a half-baked thing nobody can use — table structures,
incremental-log formats, and infrastructure differ completely. Hence movement and verification are SPIs.

```java
@Configuration
public class MigrationConfig {

    /**
     * Migration state belongs in the <b>metadata database</b> — NOT tenant-grid's routing
     * datasource. Acquiring a connection from the routing datasource requires resolving
     * TenantContext, yet migration advances where no tenant context exists (scheduled jobs /
     * ops endpoints); passing it in yields MissingTenantContextException, or with strict=false
     * silently lands on the default DB. If the two happen to be the same database, define a
     * separate plain DataSource bean and select it with @Qualifier.
     */
    @Bean
    MigrationStore migrationStore(DataSource metaDataSource) {
        return new JdbcMigrationStore(metaDataSource);
    }

    @Bean
    DataMover dataMover() {
        return (tenantId, sourceDsKey, targetDsKey) -> dataxJob.run(tenantId, sourceDsKey, targetDsKey);
    }

    @Bean
    MigrationVerifier migrationVerifier() {
        return (tenantId, source, target) -> {
            long src = countRows(source, tenantId);
            long tgt = countRows(target, tenantId);
            return src == tgt
                    ? MigrationCheck.ok("row counts match: " + src)
                    : MigrationCheck.mismatch("source " + src + " vs target " + tgt);
        };
    }

    @Bean
    MigrationCoordinator migrationCoordinator(MutableMetadataProvider metadata,
                                              MigrationStore store,
                                              DataMover mover,
                                              MigrationVerifier verifier,
                                              CachingMetadataProvider cache) {
        // The metadata cache must be invalidated right after cut-over,
        // or routing keeps hitting the old database
        return new MigrationCoordinator(metadata, store, mover, verifier, cache::invalidateAll);
    }
}
```

### Where migration state lives

A migration from `DUAL_WRITE` to `CUT_OVER` often spans hours (DataX chewing through the backlog);
state must live in a database: `JdbcMigrationStore` in production, `InMemoryMigrationStore` for
tests only — a restart would lose the fact "this tenant is dual-writing right now", and the
consequence is routing quietly returning to the source DB while the target misses the entire
dual-write window.

```sql
-- Output of JdbcMigrationStore.ddl(); standard types, runs as-is on MySQL / PostgreSQL / H2
CREATE TABLE IF NOT EXISTS tenant_grid_migration (
    tenant_id                  VARCHAR(64)   NOT NULL PRIMARY KEY,
    source_ds_key              VARCHAR(128)  NOT NULL,
    target_ds_key              VARCHAR(128)  NOT NULL,
    source_shard_type          VARCHAR(16)   NOT NULL,
    source_shard_ds_key        VARCHAR(128),
    source_shard_logical_group VARCHAR(128),
    source_shard_status        VARCHAR(32)   NOT NULL,
    stage                      VARCHAR(32)   NOT NULL,
    detail                     VARCHAR(1024),
    updated_at                 TIMESTAMP     NOT NULL
)
```

Table creation isn't hidden in the constructor: production databases usually deny the app DDL
rights, and multiple instances starting at once would race on CREATE TABLE. Pick either path —
hand the DDL above to Flyway / a DBA, or call `store.ensureTable()` once at startup (idempotent).

The four `source_shard_*` columns store the *original* shard definition, not the resolved dsKey:
the original shard may be `LOGICAL` (a `logicalGroup`, no `dsKey`), and a bare dsKey can't restore
that on rollback.

Usage:

```java
coordinator.start("t_001", "ds_std_0", "ds_vip_1");   // marks MIGRATING, enters the dual-write window
while (!coordinator.status("t_001").isTerminal()) {
    coordinator.advance("t_001");                      // step forward
}
coordinator.rollback("t_001");                         // any step can be rolled back
```

The business write path consults `coordinator.shouldDualWrite(tenantId)` — **dual-writing is the
business code's job**; the coordinator only provides the state.

### Why no auto-configuration

Migration requires the consumer to supply `DataMover` and `MigrationVerifier` — meaning you're
writing a config class anyway. Layering conditional assembly on top (checking bean presence,
metadata-provider writability) only adds fragility for negligible gain. Migration is therefore
wired explicitly.

### Two design points

**1. Never cut over on failed verification**
A failed `VERIFY` parks the task at `FAILED`, with the tenant still pointing at the source database.
Finding inconsistency before cut-over always beats finding it after — by then the cost is data
corruption. The test `stopsAtFailedWhenVerificationFails` asserts this.

**2. Rollback restores the full shard, not just the dsKey**
`MigrationTask` keeps the original `TenantShard`. Since the original shard may be `LOGICAL` (a
`logicalGroup`, no `dsKey`), a bare dsKey cannot restore it. The test
`rollbackRestoresOriginalLogicalShard` covers this case.

## Observability

The Actuator endpoint aggregates all runtime state:

```yaml
management.endpoints.web.exposure.include: tenantgrid
```

```bash
curl localhost:8080/actuator/tenantgrid
```

```json
{
  "datasources": ["ds_std_0", "ds_std_1", "ds_vip_1"],
  "tenantUsage": [
    { "tenantId": "t_001", "permits": 8, "active": 3, "rejected": 12,
      "slowHolds": 5, "maxHoldMillis": 4820, "penaltyLevel": 1,
      "effectiveTimeoutSeconds": 15 }
  ],
  "rateLimitPermitsPerSecond": 250.0,
  "metadataCache": { "hits": 912, "misses": 33, "size": 128 },
  "activeMigrations": [
    { "tenantId": "t_042", "sourceDsKey": "ds_std_1", "targetDsKey": "ds_vip_9",
      "stage": "CATCH_UP" }
  ]
}
```

Every dependency is optional — when a feature isn't enabled, its fields are `null` or empty lists,
rather than the whole endpoint failing to start (`endpointSurvivesMissingOptionalComponents`
covers this).

Typical path for investigating "the shared DB has been slow lately": see whose `slowHolds` is
climbing in `tenantUsage` → pinpoint the tenant → check its `maxHoldMillis` to tell slow queries
from connections that were never released.

## Relationship with Spring's `AbstractRoutingDataSource`

`RoutingDataSource` extends it but **overrides `determineTargetDataSource()`**. Reason: the parent
snapshots `targetDataSources` into a private map during `afterPropertiesSet()` — datasources
registered afterwards remain forever invisible to it, which collides head-on with hot-swapping.
So the parent holds an empty shell; the real lookup goes through `DataSourceRegistry`.

Graceful pool eviction (`softEvictConnections`) is invoked via reflection, so the core module
carries no hard HikariCP dependency; Tomcat JDBC / DBCP2 work fine too, just without that one
optimization.

## Roadmap

- **v0.1 ✅** Routing core + Starter + context propagation — the minimal closed loop of "VIP on dedicated DBs, long-tail on shared DBs"
- **v0.2 ✅** Hot-swappable datasources · metadata cache · SQL tenant-column guard
- **v0.3 ✅** Soft isolation: per-tenant concurrency quota · token-bucket rate limiting · slow-hold stats · auto-degradation
- **v0.4 ✅** Migration coordinator (dual-write → catch-up → verify → cut-over → rollback)
- **v0.5 ✅** Observability: Actuator endpoint (an admin UI is out of scope — consumers build on the endpoint & SPI as needed)

## Tech Baseline

- Java 17+
- Spring Boot 3.5+ / 4.x — compiled against 4.x; the starter declares Spring `provided`, pinning nothing on the consumer, and 3.5 compatibility is verified in CI
- Maven multi-module

## Build

The project compiles with `--release 17` and builds with **JDK 17 or later**:

```bash
export JAVA_HOME=<path-to-a-jdk-17-or-newer>
mvn clean install
```

Building with JDK 8 fails fast in the enforcer with a clear message instead of cryptic javac errors.

## License

[Apache License 2.0](LICENSE) — Copyright 2026 lindaailabs
