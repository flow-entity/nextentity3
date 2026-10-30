package io.github.nextentity.jpa.entity;

import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

/// fetch 前缀缓存用例夹具：同一个父实体上有两个关联，可构造「共享父前缀的两条 fetch」。
@Entity
@Table(name = "branch")
public class Branch {

    @Id
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "first_org_id")
    private Organization firstOrg;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "second_org_id")
    private Organization secondOrg;

    public Branch() {}

    public Branch(Long id, Organization firstOrg, Organization secondOrg) {
        this.id = id;
        this.firstOrg = firstOrg;
        this.secondOrg = secondOrg;
    }

    public Long getId() {
        return id;
    }

    public Organization getFirstOrg() {
        return firstOrg;
    }

    public Organization getSecondOrg() {
        return secondOrg;
    }
}
