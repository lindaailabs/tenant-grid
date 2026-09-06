package io.github.lindaailabs.tenantgrid.starter;

import io.github.lindaailabs.tenantgrid.core.ShardType;
import io.github.lindaailabs.tenantgrid.starter.guard.SqlGuardMode;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * {@code tenant-grid.*} 配置项。
 *
 * <pre>{@code
 * tenant-grid:
 *   strict: true                      # 无租户上下文时直接失败，不静默回落
 *   default-datasource: ds_std_0      # 仅非 strict 模式需要
 *   datasources:
 *     ds_vip_1: { url: jdbc:mysql://..., username: root, password: secret }
 *     ds_std_0: { url: jdbc:mysql://... }
 *     ds_std_1: { url: jdbc:mysql://... }
 *   logical-groups:
 *     std:
 *       nodes: [ds_std_0, ds_std_1]
 *   tenants:
 *     vip_1: { shard-type: physical, ds-key: ds_vip_1 }
 *     t_001: { shard-type: logical,  logical-group: std }
 * }</pre>
 */
@ConfigurationProperties(prefix = "tenant-grid")
public class TenantGridProperties {

    /**
     * 严格模式：没有租户上下文时抛异常。
     *
     * <p>关掉它会静默回落到 {@code default-datasource}——只在确有全局数据（如字典表）
     * 且确认无跨租户污染风险时才关闭。默认开启。
     */
    private boolean strict = true;

    /** 非严格模式下、且无租户上下文时使用的数据源名称。 */
    private String defaultDatasource;

    /** 数据源定义：名称 → 连接信息。 */
    private Map<String, DataSourceSpec> datasources = new LinkedHashMap<>();

    /** 共享库分组：组名 → 节点列表。 */
    private Map<String, LogicalGroupSpec> logicalGroups = new LinkedHashMap<>();

    /** 租户分片元数据。生产环境应替换为配置中心 / 数据库实现。 */
    private Map<String, TenantSpec> tenants = new LinkedHashMap<>();

    /** 元数据本地缓存。关闭后每次取连接都会查询后端，仅限调试。 */
    private MetadataCacheSpec metadataCache = new MetadataCacheSpec();

    /** SQL 租户列校验。默认关闭，需接入 JSqlParser 后开启。 */
    private SqlGuardSpec sqlGuard = new SqlGuardSpec();

    /** 租户资源配额（防吵闹邻居）。默认开启，配额给得较宽松。 */
    private QuotaSpec quota = new QuotaSpec();

    /** 租户限流。QPS 上限与部署容量强相关，默认关闭。 */
    private RateLimitSpec rateLimit = new RateLimitSpec();

    /** 配额自动降级。默认开启：只在租户真被拒绝时才生效，属于兜底。 */
    private DegradationSpec degradation = new DegradationSpec();

    public boolean isStrict() {
        return strict;
    }

    public void setStrict(boolean strict) {
        this.strict = strict;
    }

    public String getDefaultDatasource() {
        return defaultDatasource;
    }

    public void setDefaultDatasource(String defaultDatasource) {
        this.defaultDatasource = defaultDatasource;
    }

    public Map<String, DataSourceSpec> getDatasources() {
        return datasources;
    }

    public void setDatasources(Map<String, DataSourceSpec> datasources) {
        this.datasources = datasources;
    }

    public Map<String, LogicalGroupSpec> getLogicalGroups() {
        return logicalGroups;
    }

    public void setLogicalGroups(Map<String, LogicalGroupSpec> logicalGroups) {
        this.logicalGroups = logicalGroups;
    }

    public Map<String, TenantSpec> getTenants() {
        return tenants;
    }

    public void setTenants(Map<String, TenantSpec> tenants) {
        this.tenants = tenants;
    }

    public MetadataCacheSpec getMetadataCache() {
        return metadataCache;
    }

    public void setMetadataCache(MetadataCacheSpec metadataCache) {
        this.metadataCache = metadataCache;
    }

    public SqlGuardSpec getSqlGuard() {
        return sqlGuard;
    }

    public void setSqlGuard(SqlGuardSpec sqlGuard) {
        this.sqlGuard = sqlGuard;
    }

    public QuotaSpec getQuota() {
        return quota;
    }

    public void setQuota(QuotaSpec quota) {
        this.quota = quota;
    }

    public RateLimitSpec getRateLimit() {
        return rateLimit;
    }

    public void setRateLimit(RateLimitSpec rateLimit) {
        this.rateLimit = rateLimit;
    }

    public DegradationSpec getDegradation() {
        return degradation;
    }

    public void setDegradation(DegradationSpec degradation) {
        this.degradation = degradation;
    }

    public static class MetadataCacheSpec {

        private boolean enabled = true;

        /** 正常条目的存活时间。 */
        private Duration ttl = Duration.ofSeconds(30);

        /** "租户不存在"条目的存活时间，应显著短于 ttl。 */
        private Duration negativeTtl = Duration.ofSeconds(5);

        /** 最大缓存条目数，防止租户 churn 导致无界增长。 */
        private int maxSize = 100_000;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public Duration getTtl() {
            return ttl;
        }

        public void setTtl(Duration ttl) {
            this.ttl = ttl;
        }

        public Duration getNegativeTtl() {
            return negativeTtl;
        }

        public void setNegativeTtl(Duration negativeTtl) {
            this.negativeTtl = negativeTtl;
        }

        public int getMaxSize() {
            return maxSize;
        }

        public void setMaxSize(int maxSize) {
            this.maxSize = maxSize;
        }
    }

    /**
     * SQL 租户列校验配置。
     *
     * <p>{@code enforce} 会直接阻断不合规的 SQL，建议先用 {@code warn} 灰度一段时间。
     * 启用需要 JSqlParser 在 classpath 上：
     * {@code com.github.jsqlparser:jsqlparser:5.3}
     */
    public static class SqlGuardSpec {

        private SqlGuardMode mode = SqlGuardMode.OFF;

        /** 租户列名，大小写不敏感。 */
        private String tenantColumn = "tenant_id";

        /** 豁免的表名，支持 {@code *} 与 {@code ?} 通配，如 {@code sys_*}。 */
        private List<String> exemptTables = new ArrayList<>();

        public SqlGuardMode getMode() {
            return mode;
        }

        public void setMode(SqlGuardMode mode) {
            this.mode = mode;
        }

        public String getTenantColumn() {
            return tenantColumn;
        }

        public void setTenantColumn(String tenantColumn) {
            this.tenantColumn = tenantColumn;
        }

        public List<String> getExemptTables() {
            return exemptTables;
        }

        public void setExemptTables(List<String> exemptTables) {
            this.exemptTables = exemptTables;
        }
    }

    /**
     * 租户资源配额配置。
     *
     * <p>默认开启且上限宽松（每租户 50 并发），正常租户不会触发；
     * 只有真正失控的租户才会被挡住，从而保护共享库里的其他租户。
     */
    public static class QuotaSpec {

        private boolean enabled = true;

        /** 单个租户可同时持有的连接数上限。 */
        private int permitsPerTenant = 50;

        /** 超过该时长的连接持有会被计入慢持有，用于发现慢租户。 */
        private Duration slowHoldThreshold = Duration.ofSeconds(1);

        /** 最多跟踪的租户数，超出后清理空闲条目，防止 churn 导致无界增长。 */
        private int maxTrackedTenants = 100_000;

        /**
         * 单条 SQL 的最长执行时间，超过则由驱动中断查询。
         *
         * <p>默认 0（不限制）：合适的超时与具体业务的 SQL 耗时强相关，
         * 给错默认值很容易大面积误伤，建议先观察 P99 再设。
         * 被自动降级的租户，该值会按惩罚档位同步收紧。
         */
        private Duration statementTimeout = Duration.ZERO;

        /** 降级能把超时压到的下限。实际生效值不低于 1 秒——JDBC 里 0 表示不限时。 */
        private Duration minStatementTimeout = Duration.ofSeconds(1);

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public int getPermitsPerTenant() {
            return permitsPerTenant;
        }

        public void setPermitsPerTenant(int permitsPerTenant) {
            this.permitsPerTenant = permitsPerTenant;
        }

        public Duration getSlowHoldThreshold() {
            return slowHoldThreshold;
        }

        public void setSlowHoldThreshold(Duration slowHoldThreshold) {
            this.slowHoldThreshold = slowHoldThreshold;
        }

        public int getMaxTrackedTenants() {
            return maxTrackedTenants;
        }

        public void setMaxTrackedTenants(int maxTrackedTenants) {
            this.maxTrackedTenants = maxTrackedTenants;
        }

        public Duration getStatementTimeout() {
            return statementTimeout;
        }

        public void setStatementTimeout(Duration statementTimeout) {
            this.statementTimeout = statementTimeout;
        }

        public Duration getMinStatementTimeout() {
            return minStatementTimeout;
        }

        public void setMinStatementTimeout(Duration minStatementTimeout) {
            this.minStatementTimeout = minStatementTimeout;
        }
    }

    /**
     * 租户限流配置。
     *
     * <p>默认关闭——QPS 上限与具体部署容量强相关，给错默认值很容易误伤。
     * 建议先观察各租户的实际 QPS 再开启。
     */
    public static class RateLimitSpec {

        private boolean enabled = false;

        /** 稳态 QPS 上限。 */
        private double permitsPerSecond = 100;

        /** 允许的瞬时突发量（令牌桶容量）。 */
        private double burstCapacity = 50;

        private int maxTrackedTenants = 100_000;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public double getPermitsPerSecond() {
            return permitsPerSecond;
        }

        public void setPermitsPerSecond(double permitsPerSecond) {
            this.permitsPerSecond = permitsPerSecond;
        }

        public double getBurstCapacity() {
            return burstCapacity;
        }

        public void setBurstCapacity(double burstCapacity) {
            this.burstCapacity = burstCapacity;
        }

        public int getMaxTrackedTenants() {
            return maxTrackedTenants;
        }

        public void setMaxTrackedTenants(int maxTrackedTenants) {
            this.maxTrackedTenants = maxTrackedTenants;
        }
    }

    /**
     * 配额自动降级配置。
     *
     * <p>默认开启且行为保守：只有当租户真的开始被配额拒绝时才降级，
     * 表现恢复后自动回升。不会凭空限制正常租户。
     */
    public static class DegradationSpec {

        private boolean enabled = true;

        /** 评估间隔；过短会放大抖动。 */
        private Duration evaluationInterval = Duration.ofSeconds(30);

        /** 降到底的配额下限，避免把租户直接掐死。 */
        private int minPermits = 5;

        /** 窗口内被拒比例超过该值则降一级。 */
        private double rejectionRatioThreshold = 0.2;

        /** 连续多少次"零拒绝"的评估后回升一级。 */
        private int recoveryAfterCleanEvaluations = 3;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public Duration getEvaluationInterval() {
            return evaluationInterval;
        }

        public void setEvaluationInterval(Duration evaluationInterval) {
            this.evaluationInterval = evaluationInterval;
        }

        public int getMinPermits() {
            return minPermits;
        }

        public void setMinPermits(int minPermits) {
            this.minPermits = minPermits;
        }

        public double getRejectionRatioThreshold() {
            return rejectionRatioThreshold;
        }

        public void setRejectionRatioThreshold(double rejectionRatioThreshold) {
            this.rejectionRatioThreshold = rejectionRatioThreshold;
        }

        public int getRecoveryAfterCleanEvaluations() {
            return recoveryAfterCleanEvaluations;
        }

        public void setRecoveryAfterCleanEvaluations(int recoveryAfterCleanEvaluations) {
            this.recoveryAfterCleanEvaluations = recoveryAfterCleanEvaluations;
        }
    }

    public static class DataSourceSpec {

        private String url;
        private String username;
        private String password;
        private String driverClassName;

        public String getUrl() {
            return url;
        }

        public void setUrl(String url) {
            this.url = url;
        }

        public String getUsername() {
            return username;
        }

        public void setUsername(String username) {
            this.username = username;
        }

        public String getPassword() {
            return password;
        }

        public void setPassword(String password) {
            this.password = password;
        }

        public String getDriverClassName() {
            return driverClassName;
        }

        public void setDriverClassName(String driverClassName) {
            this.driverClassName = driverClassName;
        }
    }

    public static class LogicalGroupSpec {

        /** 该组包含的共享库节点名，顺序即为分片顺序。 */
        private List<String> nodes = new ArrayList<>();

        public List<String> getNodes() {
            return nodes;
        }

        public void setNodes(List<String> nodes) {
            this.nodes = nodes;
        }
    }

    public static class TenantSpec {

        private ShardType shardType = ShardType.LOGICAL;

        /** PHYSICAL 时必填。 */
        private String dsKey;

        /** LOGICAL 时必填。 */
        private String logicalGroup;

        public ShardType getShardType() {
            return shardType;
        }

        public void setShardType(ShardType shardType) {
            this.shardType = shardType;
        }

        public String getDsKey() {
            return dsKey;
        }

        public void setDsKey(String dsKey) {
            this.dsKey = dsKey;
        }

        public String getLogicalGroup() {
            return logicalGroup;
        }

        public void setLogicalGroup(String logicalGroup) {
            this.logicalGroup = logicalGroup;
        }
    }
}
