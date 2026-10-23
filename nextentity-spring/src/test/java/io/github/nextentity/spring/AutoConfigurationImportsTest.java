package io.github.nextentity.spring;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

/// 自动配置的**注册文件**门禁。
///
/// `@AutoConfiguration` 只是标记，`AutoConfigurations.of(...)` 又能绕过注册表，所以只断言注解或
/// 显式注册会让「jar 里没有 imports 文件」静默通过——那样消费方引入依赖后拿不到任何 bean。
/// 这里直接读 jar 里那个固定路径的文件，把「能否被发现」这件事钉住。
class AutoConfigurationImportsTest {

    private static final String IMPORTS_PATH =
            "META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports";

    @Test
    void registersAutoConfigurationInImportsFile() throws IOException {
        try (InputStream stream = getClass().getClassLoader().getResourceAsStream(IMPORTS_PATH)) {
            assertThat(stream).as("classpath 上必须有 %s", IMPORTS_PATH).isNotNull();
            String content = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
            assertThat(content.lines().map(String::trim)).contains(NextEntityAutoConfiguration.class.getName());
        }
    }
}
