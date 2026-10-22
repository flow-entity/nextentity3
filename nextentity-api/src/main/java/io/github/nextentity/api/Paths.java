package io.github.nextentity.api;

import java.util.ServiceLoader;

/// `PathFactory` 的查找入口：实现由后端模块通过 `ServiceLoader` 提供。
final class Paths {

    private static final PathFactory FACTORY = ServiceLoader.load(PathFactory.class)
            .findFirst()
            .orElseThrow(
                    () -> new IllegalStateException("classpath 上找不到 PathFactory 实现：请把 nextentity-core 加入 classpath"));

    private Paths() {}

    static PathFactory factory() {
        return FACTORY;
    }
}
