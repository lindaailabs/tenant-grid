package io.github.lindaailabs.tenantgrid.core.migration;

/**
 * 切流前的一致性校验（SPI）。
 *
 * <p>这是迁移里<b>最不能省的一步</b>：双写和搬迁都可能在局部静默失败，
 * 只有校验能发现"源库 100 行、目标库 97 行"这种问题。
 * 跳过校验直接切流，等于拿数据正确性赌运气。
 */
public interface MigrationVerifier {

    /**
     * 校验源库与目标库的数据是否一致。
     *
     * @return 校验结果
     * @throws Exception 校验过程本身出错时抛出
     */
    MigrationCheck verify(String tenantId, String sourceDsKey, String targetDsKey) throws Exception;

    record MigrationCheck(boolean consistent, String detail) {

        public static MigrationCheck ok(String detail) {
            return new MigrationCheck(true, detail);
        }

        public static MigrationCheck mismatch(String detail) {
            return new MigrationCheck(false, detail);
        }
    }
}
