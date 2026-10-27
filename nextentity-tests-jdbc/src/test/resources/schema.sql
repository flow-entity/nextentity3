-- tests-jdbc 的建表脚本：H2（MODE=MySQL）与真 MySQL 共用一份。
-- `JdbcSchema.apply(Connection)` 按 `;` 切分逐句执行，全部 `create table if not exists`（幂等），
-- 不使用任何引号（JDBC 后端渲染的是裸标识符，见 docs/DESIGN.md 与 tmp/plan/jdbc-design.md §3.0）。
create table if not exists item (id bigint primary key, name varchar(255), score int, score2 int, note varchar(255), status varchar(32));
create table if not exists owner (id bigint primary key, name varchar(255));
create table if not exists asset (id bigint primary key, name varchar(255), owner_id bigint, constraint fk_asset_owner foreign key (owner_id) references owner (id));
create table if not exists generated_item (id bigint auto_increment primary key, name varchar(255));
create table if not exists versioned_item (id bigint primary key, name varchar(255), version bigint not null default 0);
create table if not exists ordinal_item (id bigint primary key, status int);
create table if not exists managed_association (id bigint primary key, owner_id bigint);
