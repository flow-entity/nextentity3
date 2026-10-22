package io.github.nextentity.tests.jpa.support;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/// 测试装置自检用的最小实体：只用来证明「建表 + 抓 SQL」链路通，不代表任何业务模型。
@Entity(name = "ProbeEntity")
@Table(name = "probe")
public class ProbeEntity {

    @Id
    private Long id;

    private String name;

    protected ProbeEntity() {}

    public ProbeEntity(Long id, String name) {
        this.id = id;
        this.name = name;
    }

    public Long getId() {
        return id;
    }

    public String getName() {
        return name;
    }
}
