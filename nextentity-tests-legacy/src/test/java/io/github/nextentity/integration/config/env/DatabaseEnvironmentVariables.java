package io.github.nextentity.integration.config.env;

import java.util.List;

public interface DatabaseEnvironmentVariables {
    String name();

    /// 本环境在当前机器上能不能开起来（例如需要容器的环境要先有 Docker）。
    ///
    /// 默认 `true`：内存库没有外部依赖。需要容器的实现覆盖它——「能不能开」必须能在
    /// 起容器**之前**问出来，否则失败的形态是「跑了一半才炸」，而参数化数据源每个用例
    /// 方法都会重新取一次环境表，很容易退化成「一条红、其余静默少跑一个方言」。
    default boolean isAvailable() {
        return true;
    }

    String getJdbcUrl();

    String getUsername();

    String getPassword();

    String getDriverClassName();

    List<String> ddl();
}
