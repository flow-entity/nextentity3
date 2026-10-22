package io.github.nextentity.integration.config.env;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/// 选择本次要跑的数据库环境：`-Ddbc=h2,mysql`。
///
/// 默认只有 `h2`——遗留用例要进默认门禁，就不能让一次全量构建依赖 Docker。
/// 加了 `mysql` 才起容器，见模块 README 的"怎么跑"。
public class Env {

    private static final Map<String, Supplier<DatabaseEnvironmentVariables>> SUPPLIERS = new LinkedHashMap<>();

    static {
        SUPPLIERS.put("h2", H2EnvironmentVariables::new);
        SUPPLIERS.put("mysql", MysqlEnvironmentVariables::new);
    }

    private static final Map<String, DatabaseEnvironmentVariables> CACHE = new ConcurrentHashMap<>();

    private static final List<String> SELECTED = parseSelectedDbTypes();

    private static List<String> parseSelectedDbTypes() {
        String property = System.getProperty("dbc");
        if (property == null || property.isBlank()) {
            return List.of("h2");
        }
        List<String> selected = new ArrayList<>();
        for (String name : Arrays.stream(property.split(",")).map(String::trim).toList()) {
            if (SUPPLIERS.containsKey(name)) {
                selected.add(name);
            } else {
                throw new IllegalArgumentException("未知数据库环境: " + name + "，可选: " + SUPPLIERS.keySet());
            }
        }
        return selected;
    }

    public static List<DatabaseEnvironmentVariables> dbs() {
        return SELECTED.stream().map(Env::load).toList();
    }

    private static DatabaseEnvironmentVariables load(String name) {
        return CACHE.computeIfAbsent(name, key -> SUPPLIERS.get(key).get());
    }

    public static List<String> getSelectedDbTypes() {
        return SELECTED;
    }
}
