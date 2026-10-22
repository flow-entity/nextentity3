package io.github.nextentity.integration.config.env;

import org.testcontainers.containers.MySQLContainer;

import java.util.List;

/// MySQL 环境。
///
/// 两种取库方式，优先用外部实例：
/// - `NEXTENTITY_LEGACY_MYSQL_URL`（配套 `_USER` / `_PASSWORD`）指向一个已在跑的 MySQL；
/// - 否则用 Testcontainers 现起 `mysql:8.0.18`。
///
/// 外部实例这条路不是图省事：本机 JVM 里 Testcontainers 探不到 Docker
/// （管道与 CLI 都正常，报 `Could not find a valid Docker environment`），
/// 真方言验证只能先靠外部实例做，接线本身保留给 Docker 可用的环境。
public class MysqlEnvironmentVariables extends DbContainerEnvironmentVariables {

    private static final String EXTERNAL_URL = System.getenv("NEXTENTITY_LEGACY_MYSQL_URL");

    private static final String EXTERNAL_USER = System.getenv().getOrDefault("NEXTENTITY_LEGACY_MYSQL_USER", "root");

    private static final String EXTERNAL_PASSWORD =
            System.getenv().getOrDefault("NEXTENTITY_LEGACY_MYSQL_PASSWORD", "test");

    private static boolean external() {
        return EXTERNAL_URL != null && !EXTERNAL_URL.isBlank();
    }

    @Override
    protected MySQLContainer<?> createContainer() {
        return new MySQLContainer<>("mysql:8.0.18")
                .withDatabaseName("testdb")
                .withUsername("test")
                .withPassword("test");
    }

    @Override
    public String getJdbcUrl() {
        return external() ? EXTERNAL_URL : super.getJdbcUrl();
    }

    @Override
    public String getUsername() {
        return external() ? EXTERNAL_USER : super.getUsername();
    }

    @Override
    public String getPassword() {
        return external() ? EXTERNAL_PASSWORD : super.getPassword();
    }

    @Override
    public String getDriverClassName() {
        return "com.mysql.cj.jdbc.Driver";
    }

    @Override
    public String name() {
        return "mysql";
    }

    @Override
    public List<String> ddl() {
        return List.of(
                "DROP TABLE IF EXISTS instant_entity",
                "DROP TABLE IF EXISTS auto_increment_entity",
                "DROP TABLE IF EXISTS sales_order",
                "DROP TABLE IF EXISTS customer",
                "DROP TABLE IF EXISTS person_with_cross_layer_embedded",
                "DROP TABLE IF EXISTS person_with_nested_overridden_contact",
                "DROP TABLE IF EXISTS person_with_overridden_address",
                "DROP TABLE IF EXISTS person_with_nested_address",
                "DROP TABLE IF EXISTS person_with_address",
                "DROP TABLE IF EXISTS employee",
                "DROP TABLE IF EXISTS department",
                "DROP TABLE IF EXISTS category",
                "DROP TABLE IF EXISTS lockable_entity",
                """
                        CREATE TABLE department (
                            id BIGINT PRIMARY KEY,
                            name VARCHAR(100) NOT NULL,
                            location VARCHAR(100),
                            budget DOUBLE,
                            active BOOLEAN,
                            created_at TIMESTAMP
                        )
                        """,
                """
                        CREATE TABLE employee (
                            id BIGINT PRIMARY KEY,
                            name VARCHAR(100) NOT NULL,
                            email VARCHAR(100),
                            salary DOUBLE,
                            active BOOLEAN,
                            status INT,
                            department_id BIGINT,
                            hire_date DATE,
                            created_at TIMESTAMP
                        )
                        """,
                """
                        CREATE TABLE lockable_entity (
                            id BIGINT PRIMARY KEY,
                            name VARCHAR(100) NOT NULL,
                            description VARCHAR(255),
                            version BIGINT,
                            created_at TIMESTAMP
                        )
                        """,
                """
                        CREATE TABLE category (
                            id BIGINT PRIMARY KEY,
                            name VARCHAR(100) NOT NULL,
                            parent_id BIGINT
                        )
                        """,
                """
                        CREATE TABLE auto_increment_entity (
                            id BIGINT PRIMARY KEY AUTO_INCREMENT,
                            name VARCHAR(100) NOT NULL,
                            description VARCHAR(255),
                            priority INT,
                            active BOOLEAN,
                            created_at TIMESTAMP
                        )
                        """,
                """
                        CREATE TABLE customer (
                            id BIGINT PRIMARY KEY,
                            name VARCHAR(100) NOT NULL,
                            email VARCHAR(100)
                        )
                        """,
                """
                        CREATE TABLE sales_order (
                            id BIGINT PRIMARY KEY,
                            order_no VARCHAR(100) NOT NULL,
                            customer_id BIGINT,
                            amount DECIMAL(19,2)
                        )
                        """,
                """
                        CREATE TABLE person_with_address (
                            id BIGINT PRIMARY KEY,
                            name VARCHAR(100),
                            street VARCHAR(100),
                            city VARCHAR(100),
                            zip_code VARCHAR(20)
                        )
                        """,
                """
                        CREATE TABLE person_with_nested_address (
                            id BIGINT PRIMARY KEY,
                            name VARCHAR(100),
                            email VARCHAR(100),
                            phone VARCHAR(20),
                            street VARCHAR(100),
                            city VARCHAR(100),
                            zip_code VARCHAR(20)
                        )
                        """,
                """
                        CREATE TABLE person_with_overridden_address (
                            id BIGINT PRIMARY KEY,
                            name VARCHAR(100),
                            addr_street VARCHAR(100),
                            addr_city VARCHAR(100),
                            zip_code VARCHAR(20)
                        )
                        """,
                """
                        CREATE TABLE person_with_nested_overridden_contact (
                            id BIGINT PRIMARY KEY,
                            name VARCHAR(100),
                            contact_email VARCHAR(100),
                            phone VARCHAR(20),
                            deep_street VARCHAR(100),
                            city VARCHAR(100),
                            zip_code VARCHAR(20)
                        )
                        """,
                """
                        CREATE TABLE person_with_cross_layer_embedded (
                            id BIGINT PRIMARY KEY,
                            name VARCHAR(100),
                            city VARCHAR(100),
                            street VARCHAR(100),
                            code VARCHAR(20),
                            alt_code VARCHAR(20)
                        )
                        """,
                """
                        CREATE TABLE instant_entity (
                            id BIGINT PRIMARY KEY,
                            name VARCHAR(100) NOT NULL,
                            created_at DATETIME(6)
                        )
                        """
        );
    }
}
