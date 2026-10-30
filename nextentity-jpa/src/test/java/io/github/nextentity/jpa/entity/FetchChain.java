package io.github.nextentity.jpa.entity;

import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

/// fetch 前缀缓存用例夹具：`FetchChain.branch` 是两条 fetch 共享的父前缀。
@Entity
@Table(name = "fetch_chain")
public class FetchChain {

    @Id
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "branch_id")
    private Branch branch;

    public FetchChain() {}

    public FetchChain(Long id, Branch branch) {
        this.id = id;
        this.branch = branch;
    }

    public Long getId() {
        return id;
    }

    public Branch getBranch() {
        return branch;
    }
}
