-- 真 MySQL 的建表脚本（H2 用同内容的 schema-h2.sql：唯一差异是保留字表 `order` 的引用字符）。
-- `JdbcSchema.apply(Connection)` 按 `;` 切分逐句执行，全部 `create table if not exists`（幂等）。
-- 普通表标识符全部裸小写（JDBC 后端渲染裸标识符）；保留字表/列用反引号显式引用——
-- 实体注解同样是 `` `order` `` 形态，meta 剥掉反引号后由方言决定最终渲染（MySQL=反引号）。
create table if not exists item (id bigint primary key, name varchar(255), score int, score2 int, note varchar(255), status varchar(32));
create table if not exists owner (id bigint primary key, name varchar(255));
create table if not exists asset (id bigint primary key, name varchar(255), owner_id bigint, constraint fk_asset_owner foreign key (owner_id) references owner (id));
create table if not exists generated_item (id bigint auto_increment primary key, name varchar(255));
create table if not exists versioned_item (id bigint primary key, name varchar(255), version bigint not null default 0);
create table if not exists ordinal_item (id bigint primary key, status int);
create table if not exists managed_association (id bigint primary key, owner_id bigint);
create table if not exists `order` (id bigint primary key, name varchar(255), `desc` varchar(255), `key` int);
