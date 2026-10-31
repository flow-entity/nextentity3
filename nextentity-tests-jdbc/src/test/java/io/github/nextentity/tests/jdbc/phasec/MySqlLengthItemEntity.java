package io.github.nextentity.tests.jdbc.phasec;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/// Phase C 夹具（MySQL 容器通道）：`length()` 的字符/字节口径用例。
///
/// 表由 `MySqlLengthCharSemanticsIntegrationTest` 用原始 SQL 建：`'香蕉'`（2 字符 / utf8mb4 下 6 字节）与
/// `'abcd'`（4 字符 / 4 字节）。`length('香蕉') = 6`、`char_length('香蕉') = 2`，两者的差异就是本用例的判据。
@Entity
@Table(name = "mysql_length_item")
public class MySqlLengthItemEntity {

    @Id
    private Long id;

    private String name;

    protected MySqlLengthItemEntity() {}

    public MySqlLengthItemEntity(Long id, String name) {
        this.id = id;
        this.name = name;
    }

    public Long id() {
        return id;
    }

    public String name() {
        return name;
    }
}
