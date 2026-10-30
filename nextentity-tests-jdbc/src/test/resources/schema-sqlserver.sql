-- 建表脚本（SQL Server 2022）：每个接线类一个独立 database（`类名小写`），故 DDL 可以 drop + create 幂等。
-- 不写 COLLATE：夹具与查询参数全小写、无大小写非匹配断言（理由见 tmp/review/design-v2-tester.md §7.2）。
-- 标识符全部裸小写（JDBC 后端渲染裸标识符）；`version`/`status` 在 T-SQL 不是保留字（version 只是 rowversion 别名的类型名）。
drop table if exists asset;
drop table if exists owner;
drop table if exists item;
drop table if exists generated_item;
drop table if exists versioned_item;
drop table if exists ordinal_item;
drop table if exists managed_association;
drop table if exists [order];
create table item (id bigint primary key, name varchar(255), score int, score2 int, note varchar(255), status varchar(32));
create table owner (id bigint primary key, name varchar(255));
create table asset (id bigint primary key, name varchar(255), owner_id bigint, constraint fk_asset_owner foreign key (owner_id) references owner (id));
create table generated_item (id bigint identity(1,1) primary key, name varchar(255));
create table versioned_item (id bigint primary key, name varchar(255), version bigint not null default 0);
create table ordinal_item (id bigint primary key, status int);
create table managed_association (id bigint primary key, owner_id bigint);
create table [order] ([id] bigint primary key, [name] varchar(255), [desc] varchar(255), [key] int);

drop table if exists extended_item;
drop table if exists embedded_profile;
drop table if exists converted_item;
drop table if exists flag_column_item;
drop table if exists short_versioned_item;
drop table if exists short_generated_item;
create table extended_item (id bigint primary key, flag_col bit, ratio_col float, weight_col real, count_col smallint, price_col decimal(18,2), serial_col bigint, birth_date date, created_at datetime2(3), wake_time time, synced_at datetime2(3), sql_date date, sql_time time, sql_timestamp datetime2(3));
create table embedded_profile (id bigint primary key, town varchar(64), address_street varchar(128), label varchar(64));
create table converted_item (id bigint primary key, status varchar(32), note varchar(64));
create table flag_column_item (id bigint primary key, name varchar(64), created_by varchar(64));
create table short_versioned_item (id bigint primary key, name varchar(255), version smallint not null default 0);
create table short_generated_item (id smallint identity(1,1) primary key, name varchar(255));
