package io.github.lindaailabs.tenantgrid.core.migration;

/**
 * 数据搬迁（SPI）。
 *
 * <p>本库<b>不实现</b>具体的搬迁逻辑——那是 DataX / Debezium / 数据库原生工具的工作，
 * 各家的基础设施不同，硬塞进库里只会变成没人能用的半成品。
 * 这里只定义契约：把源库里该租户的数据搬到目标库，并追平搬迁期间的增量。
 *
 * <p>实现需自行保证幂等：{@code advance()} 可能因为上一步超时而重跑。
 */
public interface DataMover {

    /**
     * 全量搬迁 + 增量追平。
     *
     * @return 搬迁的行数，用于日志与校验提示
     * @throws Exception 搬迁失败时抛出，协调器会把任务置为 FAILED
     */
    long move(String tenantId, String sourceDsKey, String targetDsKey) throws Exception;
}
