# Tenant Grid

> Multi-tenant database placement & lifecycle for SaaS — hybrid physical/logical shard routing, made simple.

[![CI](https://github.com/lindaailabs/tenant-grid/actions/workflows/ci.yml/badge.svg)](https://github.com/lindaailabs/tenant-grid/actions/workflows/ci.yml)
[![License](https://img.shields.io/badge/License-Apache%202.0-blue.svg)](LICENSE)

[English](README.md) | **简体中文**

## 简介

Tenant Grid 是一个多租户数据库路由的 Java 库，以 Spring Boot Starter 形式交付：大客户走独占物理库，长尾客户走共享逻辑库，由它按当前租户把每次数据库操作路由到正确的数据源——路由、隔离、迁移状态全部不侵入业务代码。

**你会得到：**

- **混合分片路由**：`tenantId → DataSource`，物理/逻辑分片一视同仁；元数据可来自配置文件、JDBC 或自定义 SPI
- **数据源热插拔**：新库热加入、旧库优雅退役，无需重启
- **防吵闹邻居**：按租户的并发配额、令牌桶限流、SQL 执行超时、自动降级与恢复
- **在线迁移编排**：双写 → 追平 → 校验 → 切流 → 回滚的状态机；数据搬迁本身通过 SPI 接入（DataX / Debezium / 自研）
- **SQL 租户列校验**（可选）：基于 AST 检查行级 SQL 是否携带租户条件
- **可观测性**：单个 Actuator 端点聚合数据源、租户用量、缓存与迁移状态

路由内核 `tenant-grid-core` 零 Spring 依赖、可独立使用；starter 为 Spring Boot 4.x 提供自动装配。要求 Java 17+。

## 它解决什么

SaaS 和海外仓这类多租户系统，数据放置通常不是单一模式：

- **大客户**：独占物理库，物理隔离保 SLA
- **长尾客户**：共享逻辑库，靠 `tenant_id` 分片降成本

Tenant Grid 把这套混合分治做成一个可复用的路由层，并向上补齐三件每家 SaaS 都在手工糊的事：
**租户升降级迁移**、**共享库内的租户隔离**、**按租户维度的可观测性**。

## 为什么不直接用别的

| 方案 | 定位 | 为什么不够 |
|------|------|-----------|
| Apache ShardingSphere | SQL 解析改写、分库分表、跨库归并 | 没有一等公民的多租户路由；本场景一次请求只落单库，用不上跨库归并，却要背上 SQL 兼容性与版本耦合 |
| `dynamic-datasource` | 动态数据源切换 | 只解决"切数据源"，没有租户模型、没有迁移、没有隔离配额 |
| 手工胶水（Spring 动态数据源 + ShardingSphere） | 网上主流做法 | 每家都自己糊一遍 |

**设计取舍**：混合分治是**库级路由**（`tenant_id → DataSource`），不是表级分片，一次请求只落单库。
因此内核基于 `AbstractRoutingDataSource` 自研，不依赖 ShardingSphere。
只有当共享库内部还需要分表时（如 `stock_log` 按月分表），才在该层嵌套 ShardingSphere——做成可选项，不强依赖。

嵌套点是在**注册数据源之前**把分表能力套上去。tenant-grid 只认 `DataSource` 这个抽象，
它底下是 HikariCP 还是 ShardingSphere 并不关心：

```java
// ds_std_0 内部把 stock_log 按月分表
DataSource sharding = ShardingSphereDataSourceFactory.createDataSource(schemaConfig);

registry.register("ds_std_0", sharding);   // 注册后，路由照常按租户落到它
```

于是一次请求仍是两级：先按租户选库（tenant-grid），再在库内分表（ShardingSphere）。
前者管租户落在哪，后者管单表多大，互不干扰。

## 架构

```
┌──────────────────────────────────────────────────────┐
│                    业务代码                            │
│        只认 tenantId，不感知数据落在哪个库              │
└───────────────────────┬──────────────────────────────┘
                        │
┌───────────────────────▼──────────────────────────────┐
│              TenantRouter（路由内核）                  │
│   tenantId → ShardPlan{ dsKey, status }               │
│   PHYSICAL：直返独立库                                 │
│   LOGICAL ：hash 到共享库 0..N                         │
└───────────────────────┬──────────────────────────────┘
                        │
┌───────────────────────▼──────────────────────────────┐
│        tenant_shard 元数据（配置中心 / DB / SPI）       │
└──────────────────────────────────────────────────────┘
```

分层职责：

| 层 | 模块 | 职责 |
|----|------|------|
| 路由内核 | `tenant-grid-core` | 租户 → 分片解析，零 Spring 依赖 |
| 接入层 | `tenant-grid-spring-boot-starter` | 自动装配、上下文传播、动态数据源、Actuator |
| 隔离层 | `tenant-grid-core` · `quota` 包 | 防吵闹邻居：连接配额、令牌桶限流、自动降级 |
| 迁移层 | `tenant-grid-core` · `migration` 包 | 迁移编排：双写 → 追平 → 校验 → 切流 → 回滚 |

隔离与迁移没有拆成独立模块：它们直接依赖路由内核的类型（`TenantShard`、`ShardPlan`），
拆出去要么反向依赖 core，要么复制一份类型定义。按包隔离比按模块隔离划算。

## 模块

| 模块 | 说明 | 状态 |
|------|------|------|
| `tenant-grid-core` | 路由内核：`TenantRouter` / `TenantShard` / `MetadataProvider` SPI（内存 / JDBC）/ `TenantContext`(TTL) / `DataSourceRegistry` / `CachingMetadataProvider` / `QuotaGuard` / `RateLimiter` / `MigrationCoordinator` + `JdbcMigrationStore` | ✅ |
| `tenant-grid-spring-boot-starter` | 自动装配 / `RoutingDataSource` / 上下文传播 / SQL 校验与配额织入 / `TenantGridEndpoint`（迁移部分需显式装配） | ✅ |
| `tenant-grid-bom` | 依赖版本管理 | ✅ |
| `tenant-grid-console` | 管理台 | 不做——UI 属使用方，本库提供 Actuator 端点与 SPI 供其对接 |

## 快速开始

**1. 加依赖**

```xml
<dependency>
  <groupId>io.github.lindaailabs</groupId>
  <artifactId>tenant-grid-spring-boot-starter</artifactId>
  <version>0.5.0</version>
</dependency>
```

**2. 配置**

```yaml
tenant-grid:
  strict: true                        # 无租户上下文时直接失败（默认 true，强烈建议保持）
  datasources:
    ds_vip_1:                         # 大客户独立库
      url: jdbc:mysql://vip-db:3306/vip1
      username: root
      password: secret
    ds_std_0:                         # 长尾共享库
      url: jdbc:mysql://shared-db:3306/std0
      username: root
      password: secret
    ds_std_1:
      url: jdbc:mysql://shared-db:3306/std1
      username: root
      password: secret
  logical-groups:
    std:
      nodes: [ds_std_0, ds_std_1]     # 顺序即分片顺序
  tenants:
    vip_1: { shard-type: physical, ds-key: ds_vip_1 }
    t_001: { shard-type: logical,  logical-group: std }
    t_002: { shard-type: logical,  logical-group: std }
```

**3. 绑定租户**

```java
// 声明式
TenantContext.runAs("vip_1", () -> orderService.create(dto));

// 异步任务必须包装，否则线程池中拿不到租户
executor.submit(TenantContext.wrap(() -> orderService.create(dto)));
```

之后业务代码照常写 SQL，数据源由 `RoutingDataSource` 按租户自动选择。

> 业务 SQL 请自行携带 `tenant_id` 条件。共享库内的行级隔离依赖这个条件，
> 强制校验见下方[SQL 租户列校验](#sql-租户列校验)（默认关闭，建议先 `warn` 灰度）。

## 动态数据源热插拔

注入 `DataSourceRegistry` 即可在运行时增删数据源，无需重启：

```java
@Service
public class TenantOnboardingService {

    private final DataSourceRegistry registry;

    // 新大客户入驻：建好独立库后热加入
    public void onboardVip(String tenantId, String dsKey, DataSourceProperties spec) {
        registry.register(dsKey, buildDataSource(spec));
    }

    // 旧库退役：先切断流量，宽限 30s 让在途请求跑完，再关闭连接池
    public void retire(String dsKey) {
        registry.unregisterAndClose(dsKey, Duration.ofSeconds(30));
    }
}
```

**热下线的顺序是刻意的**：先从注册表摘除（立即不再接受新流量）→ 宽限期内在途请求跑完 → 才真正 `close()` 连接池。顺序颠倒就会出现"新请求还在进来、连接已被关掉"。

摘除后立即生效，下一次路由就不会再命中该库；若此时仍有租户指向它，会得到 `UnknownDataSourceException`（带已注册列表），而不是静默落到别的库。

## 元数据持久化

配置文件里的租户和内存实现，都存不住两样会变的东西：**运行期新入驻的租户**，和
**迁移期间打在租户上的 `MIGRATING` 标记**。后者尤其危险——进程重启后标记消失，
路由不再认为该租户处于双写期，双写静默停止，而迁移任务本身还停在 `CATCH_UP`，
目标库会少掉整段双写期的数据。

落库用 `JdbcMetadataProvider`，对应 `tenant_shard` 表：

```sql
-- JdbcMetadataProvider.ddl() 的输出，标准类型，MySQL / PostgreSQL / H2 可直接执行
CREATE TABLE IF NOT EXISTS tenant_shard (
    tenant_id     VARCHAR(64)   NOT NULL PRIMARY KEY,
    shard_type    VARCHAR(16)   NOT NULL,
    ds_key        VARCHAR(128),
    logical_group VARCHAR(128),
    status        VARCHAR(32)   NOT NULL,
    updated_at    TIMESTAMP     NOT NULL
)
```

**必须套上缓存**——`find()` 在每次获取连接时被调用：

```java
@Bean
MetadataProvider tenantGridMetadata(DataSource metaDataSource) {   // 元数据库，不是路由数据源
    JdbcMetadataProvider jdbc = new JdbcMetadataProvider(metaDataSource);
    return new CachingMetadataProvider(jdbc, Duration.ofSeconds(30), Duration.ofSeconds(5), 100_000);
}
```

`metaDataSource` **不能**是 tenant-grid 的路由数据源：查元数据正是为了确定用哪个库，
用路由数据源去查等于先有鸡还是先有鸡蛋。理由与 `JdbcMigrationStore` 相同。

`CachingMetadataProvider` 在委托可变时也支持写——切流时先写穿到库、再失效本地条目，
保证写完立刻读到新值（读到切流前的旧分片会把流量打回源库）。但它只保证**本实例**
一致：其他实例的缓存要等 TTL 过期才看到变更，收敛时间由 TTL 决定。跨实例强一致要靠
使用方的广播机制（配置中心推送 / Redis pub-sub），不属本库范围；迁移这种场景靠
`MigrationCoordinator` 的 `onMetadataChanged` 回调在每个实例上失效缓存。

迁移的两张表各管一半：`tenant_grid_migration` 存阶段进度，`tenant_shard` 存路由状态。
只持久化前者不够，只持久化后者也不够。

## 元数据缓存

路由发生在**每次获取连接**时，是最热的读路径。默认套上本地缓存：

```yaml
tenant-grid:
  metadata-cache:
    enabled: true      # 关闭后每次取连接都会查后端，仅限调试
    ttl: 30s           # 正常条目存活时间
    negative-ttl: 5s   # "租户不存在"条目存活时间，应显著短于 ttl
    max-size: 100000   # 条目上限，防止租户 churn 导致无界增长
```

四个设计点，对应的坑分别是：

| 机制 | 不做的后果 |
|---|---|
| **TTL** | 租户迁移后路由长期指向旧库 |
| **击穿保护**（`computeIfAbsent` 单飞） | 同一租户并发未命中时，所有线程一起打后端 |
| **负缓存** | 不存在的租户每次请求都穿透，等于给后端开流量放大口子 |
| **有界** | 租户 churn 时缓存只增不减，最终 OOM |

后端异常**不会**被当成"租户不存在"缓存下来——否则后端恢复后仍会一直报错。

租户迁移完成后应主动失效，而不是等 TTL：

```java
@Autowired CachingMetadataProvider metadata;

metadata.invalidate("vip_1");   // 单个租户
metadata.invalidateAll();        // 全量
metadata.stats().hitRate();      // 命中率，用于判断 ttl / max-size 是否合理
```

> 若你自行提供 `MetadataProvider`（接配置中心 / 数据库），自动装配会整体退让，
> 缓存需要你自己套：`new CachingMetadataProvider(yourProvider, ttl, negativeTtl, maxSize)`。

## SQL 租户列校验

共享库里多个租户的行靠 `tenant_id` 区分，路由只决定"落到哪个库"。
**库内的行级隔离完全依赖 SQL 里的租户条件**——漏了就是跨租户查询。

在 JDBC 层拦截每条 SQL，检查过滤条件是否带租户列：

```yaml
tenant-grid:
  sql-guard:
    mode: enforce        # off（默认） / warn / enforce
    tenant-column: tenant_id
    exempt-tables: [sys_*, dict_*]   # 全局表豁免，支持 * 和 ? 通配
```

```xml
<!-- mode 不是 off 时需要显式引入（可选依赖，不强制） -->
<dependency>
  <groupId>com.github.jsqlparser</groupId>
  <artifactId>jsqlparser</artifactId>
  <version>5.3</version>
</dependency>
```

**建议接入路径**：先 `warn` 跑一段时间观察日志，清理完历史 SQL 再切 `enforce`。存量系统直接上 `enforce` 大概率起不来。

拦截两个入口：`Connection.prepareStatement/prepareCall`（ORM 都走这里）和 `Statement.execute*`（拼字符串执行）。

### 校验范围

| 判定 | 示例 |
|---|---|
| ✅ 放行 | `WHERE tenant_id = ?`、`WHERE o.tenant_id = ?`、`INSERT INTO t (tenant_id, ..)`、JOIN ON 条件、子查询的 WHERE |
| ❌ 拦截 | `WHERE id = ?`、无条件查询、**`SELECT tenant_id FROM t WHERE id = ?`**（投影列不起过滤作用） |
| ⏭ 跳过 | 非 DML（DDL/SHOW）、无表语句（`SELECT 1`，健康检查常用）、豁免表、解析失败、UNION 分支 |

### 两个值得说明的实现取舍

**1. 用已验证的 getter，不用 visitor 派发**
JSqlParser 5.x 的 `expression.accept(visitor)` 不会回调到自定义 visitor（实测 `visit(Column)` 一次都不触发）。因此这里用 `getWhere()` / `getHaving()` / `getOnExpressions()` / `getColumns()` 这些直接 getter 沿 AST 定位过滤区域，再在区域的 AST 节点文本上做标识符分词（先剔除字符串字面量和数字）。相比对原始 SQL 正则，区域由 AST 精确定位，不会把投影列、表名、字符串字面量误判成过滤条件。

**2. 不确定时放行，不阻断**
遇到无法下钻的结构（UNION 分支）或解析失败时标记为"无法确定"并放行。**校验器的误判代价不对称**：误放行只是少一层防护，误拦截是线上故障。

## 租户配额（防吵闹邻居）

大客户独占物理库解决了物理隔离，但**共享逻辑库内部**的长尾租户仍共用同一连接池：
一个租户的慢查询或突发流量占满连接，同库所有租户一起不可用。

按租户限制并发连接数，超限快速失败，而不是让它把连接池耗干：

```yaml
tenant-grid:
  quota:
    enabled: true             # 默认开启
    permits-per-tenant: 50    # 单租户并发连接上限，默认给得宽松
    slow-hold-threshold: 1s   # 超过该时长的连接持有计入慢持有
    max-tracked-tenants: 100000
    statement-timeout: 0s     # 单条 SQL 的执行超时，0 = 不限制（默认）
    min-statement-timeout: 1s # 降级能把超时压到的下限
```

超限抛出 `TenantQuotaExceededException`，**只影响超限的那一个租户**，同库其他租户照常服务。

### SQL 执行超时

配额管的是"同时占着多少条连接"，管不了"一条查询占着连接多久"。只有并发限制时，
几个慢查询就能把配额吃满——它们不释放，后面所有请求都被拒，而配额看起来"工作正常"。
超时是第三个维度：让慢查询自己让位，而不是一直占着连接。

默认 `0s`（不限制）：合适的超时与具体业务的 SQL 耗时强相关，给错默认值很容易大面积误伤，
建议先观察 P99 再设。被自动降级的租户，超时与配额共用一套惩罚档位同步收紧——降一级砍一半，
不低于 `min-statement-timeout`（下限至少 1 秒，因为 JDBC 里 0 表示不限时，
降级到 0 等于解除限制，与意图相反）。

两个实现上的注意点：

- **只调 setter，不代理 Statement**：`PreparedStatement` 的实现类往往带有连接池自己的内部
  接口，代理会破坏类型；超时在创建后调一次 `setQueryTimeout` 即可，不需要代理。
  `createStatement` / `prepareStatement` / `prepareCall` 三条路径都会织入。
- **驱动层的 statement 缓存会让超时跨租户残留**：若开启 MySQL 的 `cachePrepStmts=true`，
  statement 会被跨请求复用，上一个租户的超时可能留到下一个租户。默认配置下连接池不缓存
  statement，无此问题；开了缓存就改用统一的全局超时，或不要依赖本机制。

配套的可观测性：

```java
@Autowired QuotaGuard quotaGuard;

for (TenantUsage u : quotaGuard.usage()) {
    u.tenantId(); u.active(); u.utilisation(); u.rejected(); u.slowHolds(); u.maxHoldMillis();
    u.penaltyLevel(); u.effectiveTimeoutSeconds();
}
```

- `rejected` 持续增长 → 该租户并发被打满：要么治理它，要么配额配小了
- `slowHolds` 持续增长 → 该租户有慢查询，是拖垮共享库的主要嫌疑

### 两个容易出错的实现细节

**1. 被拒绝的连接必须还回池里**
`super.getConnection()` 已经拿到连接了，超限时若不 `close()` 就抛出，连接池会被逐步漏光——**比不做防护更糟**。限流和配额各有独立测试断言这一点。

**2. 归还绑定在连接 `close()` 上，且必须幂等**
用代理把归还动作织进 `close()`，业务代码无需感知。但 `close()` 可能被重复调用，若归还两次许可会凭空增多、配额形同虚设，因此用 `AtomicBoolean` 保证只还一次。关闭失败时也要在 `finally` 里归还，否则一次故障就永久泄漏一个许可。

## 限流与自动降级

配额管**并发数**（占着不放），限流管**速率**（来得太多）——两个维度正交，缺一不可。只挡并发不挡速率，短平快的高频请求照样能把下游打垮。

```yaml
tenant-grid:
  rate-limit:
    enabled: false           # 默认关闭
    permits-per-second: 250  # 稳态 QPS
    burst-capacity: 80       # 允许的瞬时突发
  degradation:
    enabled: true            # 默认开启
    evaluation-interval: 30s
    min-permits: 5           # 降到底的下限
    rejection-ratio-threshold: 0.2
    recovery-after-clean-evaluations: 3
```

**限流**用令牌桶，惰性补充令牌（按时间差计算，不需要后台线程）。

**自动降级**不预先假设谁是吵闹租户，而是看实际表现：某个租户持续被配额拒绝（窗口内拒绝比例超阈值），就压低它的配额；连续若干次评估都干净，再自动回升一级。是**惩罚性收敛**而非永久封禁，避免一次抖动把租户永久钉死在低配额上。

### 四个默认值为什么不一样

| 开关 | 默认 | 理由 |
|---|---|---|
| 配额 | **开** | 50 并发的宽松上限，正常租户碰不到；只有真失控的才会被挡 |
| 降级 | **开** | 只在租户真被拒绝时才生效，是兜底而非前置限制，不会误伤 |
| 限流 | **关** | QPS 上限与具体部署容量强相关，给错默认值很容易误伤，建议先观察再开 |
| SQL 超时 | **关** | 超时阈值与业务的 SQL 耗时强相关，给错默认值会大面积误伤，建议先观察 P99 |
| SQL 校验 | **关** | 误判会直接阻断业务，存量系统需先 `warn` 灰度 |

判据是**误判代价**：误放行只是少一层防护，误拦截是线上故障。越靠近"会阻断业务"的，默认越保守。

## 租户迁移协调器

小卖家涨成大客户时，需要把它的共享库数据迁到独立库，且不能停服。

```
INIT → DUAL_WRITE → CATCH_UP → VERIFY → CUT_OVER → COMPLETED
                                                 ↘ ROLLED_BACK
```

**这个库负责什么、不负责什么**，边界划得很清楚：

| | 归属 |
|---|---|
| 阶段状态机、迁移标记、切流时的路由切换、回滚 | tenant-grid |
| 真实的数据搬迁、一致性校验 | 使用方（DataX / Debezium / 自研脚本） |

硬塞一个通用搬迁实现只会变成谁都用不了的半成品——各家的表结构、增量日志格式、基础设施完全不同。所以搬迁和校验做成 SPI。

```java
@Configuration
public class MigrationConfig {

    /**
     * 迁移状态要落在<b>元数据库</b>——不是 tenant-grid 的路由数据源。
     * 路由数据源取连接时必须解析 TenantContext，而迁移推进发生在没有租户上下文的
     * 环境里（定时任务 / 运维接口），传进去只会拿到 MissingTenantContextException，
     * 或在 strict=false 时静默落到默认库。若两者恰好是同一个库，请单独定义一个
     * 普通 DataSource Bean 并用 @Qualifier 指定。
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
        // 切流后必须立刻失效元数据缓存，否则路由还会打到旧库
        return new MigrationCoordinator(metadata, store, mover, verifier, cache::invalidateAll);
    }
}
```

### 迁移状态存哪

一次迁移从 `DUAL_WRITE` 走到 `CUT_OVER` 常常隔着几小时（DataX 跑存量），状态必须落库：
生产用 `JdbcMigrationStore`，`InMemoryMigrationStore` 仅供测试——进程重启会丢掉
"这个租户正在双写"这个事实，后果是路由悄悄回到源库、目标库少掉整个双写期的数据。

```sql
-- JdbcMigrationStore.ddl() 的输出，标准类型，MySQL / PostgreSQL / H2 可直接执行
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

建表不藏在构造函数里：生产库通常不给应用 DDL 权限，多实例同时启动还会撞上建表竞争。
两条路任选——把上面的 DDL 交给 Flyway / DBA，或启动时显式调用一次 `store.ensureTable()`（幂等）。

`source_shard_*` 四列存的是原始分片定义，不是解析后的 dsKey：原始分片可能是 `LOGICAL`
（只有 `logicalGroup`，没有 `dsKey`），回滚时只凭一个 dsKey 还原不回去。

用法：

```java
coordinator.start("t_001", "ds_std_0", "ds_vip_1");   // 标记 MIGRATING，进入双写期
while (!coordinator.status("t_001").isTerminal()) {
    coordinator.advance("t_001");                      // 逐步推进
}
coordinator.rollback("t_001");                         // 任何一步出问题都可回滚
```

业务写路径用 `coordinator.shouldDualWrite(tenantId)` 判断是否需要双写——**双写由业务代码负责**，协调器只提供状态。

### 为什么没有自动装配

迁移必须由使用方提供 `DataMover` 和 `MigrationVerifier`，也就是说你本来就要写配置类。再套一层条件装配（判断这些 Bean 是否存在、元数据提供者是否可写）只会增加脆弱性，收益却很小。因此迁移部分显式装配。

### 两个设计要点

**1. 校验不过绝不切流**
`VERIFY` 阶段不通过会停在 `FAILED`，租户仍指向源库。切流前发现不一致，总好过切完才发现——那时代价是数据损坏。测试 `stopsAtFailedWhenVerificationFails` 断言了这一点。

**2. 回滚还原完整分片而非只改 dsKey**
`MigrationTask` 保存了原始 `TenantShard`。因为原始分片可能是 `LOGICAL`（只有 `logicalGroup`，没有 `dsKey`），仅凭一个 dsKey 无法还原。测试 `rollbackRestoresOriginalLogicalShard` 覆盖了这个场景。

## 可观测性

Actuator 端点聚合全部运行状态：

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

所有依赖都是可选的——没启用对应功能时字段为 `null` 或空列表，而不是让整个端点起不来（`endpointSurvivesMissingOptionalComponents` 覆盖该场景）。

排查"共享库最近很慢"的典型路径：看 `tenantUsage` 里谁的 `slowHolds` 在涨 → 找到具体租户 → 再看它的 `maxHoldMillis` 判断是慢查询还是连接没释放。

## 与 Spring `AbstractRoutingDataSource` 的关系

`RoutingDataSource` 继承了它，但**重写了 `determineTargetDataSource()`**。原因：父类在 `afterPropertiesSet()` 时把 `targetDataSources` 快照进一张私有 map，之后注册的新数据源它永远看不到——这与热插拔直接冲突。所以父类只持有一个空壳，真正的查找走 `DataSourceRegistry`。

连接池优雅驱逐（`softEvictConnections`）通过反射调用，这样 core 模块不必强依赖 HikariCP；换成 Tomcat JDBC / DBCP2 也能正常工作，只是少一层优化。

## 路线

- **v0.1 ✅** 路由内核 + Starter + 上下文传播，跑通"VIP 走独立库、长尾走共享库"最小闭环
- **v0.2 ✅** 动态数据源热插拔 · 元数据缓存 · SQL 租户列校验
- **v0.3 ✅** 软隔离：租户并发配额 · 令牌桶限流 · 慢持有统计 · 自动降级
- **v0.4 ✅** Migration 迁移协调器（双写 → 追平 → 校验 → 切流 → 回滚）
- **v0.5 ✅** Observability：Actuator 端点（管理台 UI 不属库的范围，留给使用方按需实现）

## 技术基线

- Java 17+
- Spring Boot 4.x（starter 以 `provided` 引入，不绑定使用方版本）
- Maven 多模块

## 构建

项目以 `--release 17` 编译，可用 **JDK 17 及以上**构建：

```bash
export JAVA_HOME=<JDK-17-及以上的安装路径>
mvn clean install
```

用 JDK 8 构建时 enforcer 会直接给出明确提示，而不是晦涩的 javac 报错。

## License

[Apache License 2.0](LICENSE) — Copyright 2026 lindaailabs
