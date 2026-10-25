package io.github.nextentity.jpa.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OneToOne;
import jakarta.persistence.Table;

/// 唯一外键关联的**最小**载体：除主键、一个基本属性、一个 `@OneToOne` 外什么都没有。
///
/// 存在的理由是给「写侧跳过关联列」一条不被别的形态污染的样本：`Article` 同时带
/// `@ManyToMany`，注入「不跳过关联」后集合属性会先在 `CriteriaUpdate.set(集合属性)` 处抛异常，
/// `doesNotContain("seal_id")` 那行根本执行不到——那条断言就不是被自己的变异杀死的。
/// 本类没有集合属性，注入后唯一能红的就是 `SET` 子句里出现了 `seal_id`。
@Entity
@Table(name = "seal_holder")
public class SealHolder {

    @Id
    private Long id;

    @Column(name = "name")
    private String name;

    @OneToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "seal_id")
    private Seal seal;

    public SealHolder() {}

    public SealHolder(Long id, String name, Seal seal) {
        this.id = id;
        this.name = name;
        this.seal = seal;
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public Seal getSeal() {
        return seal;
    }

    public void setSeal(Seal seal) {
        this.seal = seal;
    }
}
