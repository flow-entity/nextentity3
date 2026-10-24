package io.github.nextentity.spring;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/// 真实接线用例的落库实体：只带「能区分行」的最小字段，够验证查询/插入与并发隔离。
@Entity(name = "ThingEntity")
@Table(name = "thing")
public class ThingEntity {

    @Id
    private Long id;

    private String name;

    private Integer score;

    protected ThingEntity() {}

    public ThingEntity(Long id, String name, Integer score) {
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
