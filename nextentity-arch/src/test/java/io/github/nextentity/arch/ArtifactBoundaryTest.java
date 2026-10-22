package io.github.nextentity.arch;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.net.URL;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.Test;

/// 产物边界：`io.github.nextentity.api` 的 class 只能来自 `nextentity-api` 自己的产物。
///
/// 起因（真实事故）：`.m2` 里旧的 `nextentity-core-3.0.0-SNAPSHOT.jar` 曾把 api 的 class 一起打进去，
/// 编译期于是按 jar 里的旧签名（`api.QueryLock`）解析，全队排查了一轮假故障。
///
/// ArchUnit 的规则只按包名断言依赖方向，看不出「同一个类被两个产物各打一份」，所以这里直接问
/// 类加载器：这些 class 在 classpath 上有几个来源、分别来自哪个产物。
class ArtifactBoundaryTest {

    private static final String API_ARTIFACT = "nextentity-api";

    private static final List<String> API_MARKERS = List.of(
            "io/github/nextentity/api/LockMode.class",
            "io/github/nextentity/api/EntityQuery.class",
            "io/github/nextentity/api/Path.class");

    /// 每个 api 标记类的全部来源都必须落在 `nextentity-api` 产物里。
    ///
    /// @throws IOException 读取 classpath 资源失败
    @Test
    void apiClassesComeOnlyFromApiArtifact() throws IOException {
        for (String marker : API_MARKERS) {
            List<String> locations = Collections.list(
                            getClass().getClassLoader().getResources(marker))
                    .stream()
                    .map(URL::toString)
                    .toList();
            assertThat(locations).as("%s 在 classpath 上至少有一个来源", marker).isNotEmpty();
            assertThat(locations)
                    .as("%s 的来源必须全部是 %s 产物，不能有别的模块把它打进去", marker, API_ARTIFACT)
                    .allSatisfy(location -> assertThat(location).contains(API_ARTIFACT));
        }
    }
}
