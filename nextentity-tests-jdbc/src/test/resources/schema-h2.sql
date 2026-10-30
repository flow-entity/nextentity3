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
-- 3.1.0 能力面：扩展列类型 / @Embedded 扁平列 / @Convert / insertable=false 列 / short 版本与生成键。
create table if not exists extended_item (id bigint primary key, flag_col boolean, ratio_col double, weight_col real, count_col smallint, price_col decimal(18,2), serial_col bigint, birth_date date, created_at timestamp, wake_time time, synced_at timestamp, sql_date date, sql_time time, sql_timestamp timestamp);
create table if not exists embedded_profile (id bigint primary key, town varchar(64), address_street varchar(128), label varchar(64));
create table if not exists converted_item (id bigint primary key, status varchar(32), note varchar(64));
create table if not exists flag_column_item (id bigint primary key, name varchar(64), created_by varchar(64));
create table if not exists short_versioned_item (id bigint primary key, name varchar(255), version smallint not null default 0);
create table if not exists short_generated_item (id smallint auto_increment primary key, name varchar(255));
