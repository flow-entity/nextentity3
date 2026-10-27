package io.github.nextentity.tests.jdbc.review;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.nextentity.api.EntityPath;
import io.github.nextentity.api.StringPath;
import io.github.nextentity.jdbc.JdbcEntityOperationsFactory;
import io.github.nextentity.tests.jdbc.JdbcItemSupport;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import org.junit.jupiter.api.Test;

/// 评审发现 R5（已裁决：拒绝，不支持）：`@ManyToOne @JoinColumn(name=..., referencedColumnName=...)`
/// 指定非目标主键列时，meta 只认 `name`、JOIN 固定 `t1.<目标 @Id 列> = t0.<joinColumn>`；若不拒绝会静默拼错
/// （H2 报类型比较错，MySQL 8.0.18 隐式转 0 静默 0 行）。
///
/// 裁决（architect，2026-10-28）：本轮只支持「@ManyToOne 指向目标 @Id」这一种形态，`referencedColumnName`
/// 非空且不等于目标 @Id 列时在 meta 期抛 `UnsupportedOperationException`（设计 §4.3 #27）。
///
/// 复跑：`bash tmp/scripts/mvnl.sh -pl nextentity-tests-jdbc -am test -Dtest=ReviewR5JoinReferencedColumnTest`
class ReviewR5JoinReferencedColumnTest {

    @Test
    void joinOnReferencedNonIdColumnIsRejectedInsteadOfSilentlyWrong() {
        try (JdbcItemSupport support = new JdbcItemSupport("review_r5_join")) {
            support.execute("create table review_r5_owner (id bigint primary key, name varchar(255))");
            support.execute(
                    "create table review_r5_asset (id bigint primary key, name varchar(255), owner_name varchar(255))");
            support.execute("insert into review_r5_owner (id, name) values (1, 'alpha'), (2, 'beta')");
            support.execute("insert into review_r5_asset (id, name, owner_name) values (10, 'a1', 'alpha')");

            // 裁决（architect，2026-10-28）：不支持 referencedColumnName，meta 期显式拒绝（UOE），
            // 不按被引用列拼 JOIN（被引用列不保证唯一，会放大行数、破坏「count/实体行数不放大」契约）。
            assertThatThrownBy(
                            () -> new JdbcEntityOperationsFactory(support.dataSource()).operations(ReviewAsset.class))
                    .as("引用非主键列时必须在 meta 期显式拒绝（UOE），不得静默拼错 JOIN")
                    .isInstanceOf(UnsupportedOperationException.class)
                    .hasMessageContaining("referencedColumnName")
                    .hasMessageContaining("@JoinColumn(referencedColumnName=name) 未支持")
                    .hasMessageContaining("ReviewAsset.owner")
                    .hasMessageContaining("的 @Id 列是 id");
        }
    }

    @Test
    void referencedIdColumnIsAcceptedAndQueryWorks() {
        try (JdbcItemSupport support = new JdbcItemSupport("review_r5_join_ok")) {
            support.execute("create table review_r5_owner_ok (id bigint primary key, name varchar(255))");
            support.execute(
                    "create table review_r5_asset_ok (id bigint primary key, name varchar(255), owner_id bigint)");
            support.execute("insert into review_r5_owner_ok (id, name) values (1, 'alpha'), (2, 'beta')");
            support.execute("insert into review_r5_asset_ok (id, name, owner_id) values (10, 'a1', 1), (11, 'a2', 2)");

            var operations = new JdbcEntityOperationsFactory(support.dataSource()).operations(ReviewAssetOk.class);
            EntityPath<ReviewAssetOk, ReviewOwnerOk> ownerPath = EntityPath.of(ReviewAssetOk::getOwner);
            StringPath<ReviewAssetOk> ownerName = ownerPath.get(ReviewOwnerOk::getName);
            assertThat(operations.query().where(ownerName).eq("alpha").list())
                    .as("referencedColumnName 显式等于目标 @Id 列时应放行，JOIN 仍按 t1.id = t0.owner_id")
                    .extracting(ReviewAssetOk::getId)
                    .containsExactly(10L);
        }
    }

    @Entity
    @Table(name = "review_r5_owner")
    static class ReviewOwner {
        @Id
        private Long id;

        private String name;

        protected ReviewOwner() {}

        Long getId() {
            return id;
        }

        String getName() {
            return name;
        }
    }

    @Entity
    @Table(name = "review_r5_asset")
    static class ReviewAsset {
        @Id
        private Long id;

        private String name;

        @ManyToOne
        @JoinColumn(name = "owner_name", referencedColumnName = "name")
        private ReviewOwner owner;

        protected ReviewAsset() {}

        Long getId() {
            return id;
        }

        String getName() {
            return name;
        }

        ReviewOwner getOwner() {
            return owner;
        }
    }

    @Entity
    @Table(name = "review_r5_owner_ok")
    static class ReviewOwnerOk {
        @Id
        private Long id;

        private String name;

        protected ReviewOwnerOk() {}

        Long getId() {
            return id;
        }

        String getName() {
            return name;
        }
    }

    @Entity
    @Table(name = "review_r5_asset_ok")
    static class ReviewAssetOk {
        @Id
        private Long id;

        private String name;

        @ManyToOne
        @JoinColumn(name = "owner_id", referencedColumnName = "id")
        private ReviewOwnerOk owner;

        protected ReviewAssetOk() {}

        Long getId() {
            return id;
        }

        String getName() {
            return name;
        }

        ReviewOwnerOk getOwner() {
            return owner;
        }
    }
}
