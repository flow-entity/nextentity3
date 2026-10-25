package io.github.nextentity.tests.jpa;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;

/// Spring 接线容器用例的实体：只为「共享 `EntityManager` 代理 + 真 MySQL + 事务边界」这条链准备。
///
/// 不复用 [ItemEntity] 的理由：容器是**全 JVM 单例**，本类要独占一张表才能自己决定建表/清表，
/// 否则与其它容器用例的 `create-drop` 时序纠缠——共用一张表时，先跑的那个类关闭装置会顺手把
/// 别人刚建好的表删掉。
@Entity(name = "SpringWiredEntity")
public class SpringWiredEntity {

    @Id
    private Long id;

    private String name;

    private Integer score;

    protected SpringWiredEntity() {}

    SpringWiredEntity(Long id, String name, Integer score) {
        this.id = id;
        this.name = name;
        this.score = score;
    }

    public Long getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public Integer getScore() {
        return score;
    }
}
