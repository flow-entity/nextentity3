-- H2（MODE=MySQL）的建表脚本：与 schema-mysql.sql 同内容，唯一差异是保留字表 `order` 用双引号引用——
-- 与 Hibernate 在 H2 上生成的 SQL 形态一致（H2 方言引用字符是 "），两个后端才能在 H2 上对拍。
-- `JdbcSchema.apply(Connection)` 按 `;` 切分逐句执行，全部 `create table if not exists`（幂等）。
-- 普通表标识符全部裸小写（JDBC 后端渲染裸标识符）；保留字表/列必须显式引用且大小写全小写
-- （H2 双引号下大小写敏感，实体注解 `` `order` `` 剥引号后是小写，两边必须一致）。
create table if not exists item (id bigint primary key, name varchar(255), score int, score2 int, note varchar(255), status varchar(32));
create table if not exists owner (id bigint primary key, name varchar(255));
create table if not exists asset (id bigint primary key, name varchar(255), owner_id bigint, constraint fk_asset_owner foreign key (owner_id) references owner (id));
create table if not exists generated_item (id bigint auto_increment primary key, name varchar(255));
create table if not exists versioned_item (id bigint primary key, name varchar(255), version bigint not null default 0);
create table if not exists ordinal_item (id bigint primary key, status int);
create table if not exists managed_association (id bigint primary key, owner_id bigint);
create table if not exists "order" (id bigint primary key, name varchar(255), "desc" varchar(255), "key" int);
