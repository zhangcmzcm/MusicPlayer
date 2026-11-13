# T04 歌曲搜索真实 MySQL 查询计划

验证日期为2026-11-13。使用真实MySQL 8.4与生产TrackListRepository SQL工厂，固定10000首数据，DRAFT 3334首、PUBLISHED 3333首、ARCHIVED 3333首。标题、艺术家、专辑包含中文、英文和空专辑。内容与COUNT均使用2000毫秒执行预算。

测试类为src/test/java/org/example/music/track/TrackSearchQueryPlanIntegrationTest.java，完整88组原始产物为build/reports/track-search-query-plans.md。各组实际ID、排序、分页和精确总数均通过独立样本断言。原列表V7到当前迁移的查询计划回归也已执行。

以下耗时取EXPLAIN ANALYZE根节点actual time的终点，单位为毫秒，数据来自一次本机容器验证。匹配总数来自实际COUNT断言，返回行数来自内容根节点。

## 全部查询组合

| 状态、关键词、排序与分页 | 匹配总数 | 返回行数 | 内容毫秒 | COUNT毫秒 |
| --- | --- | --- | --- | --- |
| 全部状态 / [晴] / id ASC / offset=0 / size=20 | 10000 | 20 | 0.0788 | 2.19 |
| 全部状态 / [晴] / id DESC / offset=0 / size=20 | 10000 | 20 | 0.0955 | 2.44 |
| 全部状态 / [晴] / title ASC / offset=0 / size=20 | 10000 | 20 | 0.554 | 2.05 |
| 全部状态 / [晴] / title DESC / offset=0 / size=20 | 10000 | 20 | 0.111 | 2.11 |
| 全部状态 / [晴] / artistName ASC / offset=0 / size=20 | 10000 | 20 | 0.08 | 2.02 |
| 全部状态 / [晴] / artistName DESC / offset=0 / size=20 | 10000 | 20 | 0.0871 | 2.1 |
| 全部状态 / [晴] / createdAt ASC / offset=0 / size=20 | 10000 | 20 | 0.0995 | 2.08 |
| 全部状态 / [晴] / createdAt DESC / offset=0 / size=20 | 10000 | 20 | 0.102 | 2.07 |
| 全部状态 / [周杰伦, 晴天] / id ASC / offset=0 / size=20 | 5000 | 20 | 0.0772 | 2.92 |
| 全部状态 / [周杰伦, 晴天] / id DESC / offset=0 / size=20 | 5000 | 20 | 0.0878 | 2.83 |
| 全部状态 / [周杰伦, 晴天] / title ASC / offset=0 / size=20 | 5000 | 20 | 0.189 | 2.76 |
| 全部状态 / [周杰伦, 晴天] / title DESC / offset=0 / size=20 | 5000 | 20 | 0.156 | 2.8 |
| 全部状态 / [周杰伦, 晴天] / artistName ASC / offset=0 / size=20 | 5000 | 20 | 3.39 | 2.76 |
| 全部状态 / [周杰伦, 晴天] / artistName DESC / offset=0 / size=20 | 5000 | 20 | 0.0905 | 2.77 |
| 全部状态 / [周杰伦, 晴天] / createdAt ASC / offset=0 / size=20 | 5000 | 20 | 0.193 | 2.73 |
| 全部状态 / [周杰伦, 晴天] / createdAt DESC / offset=0 / size=20 | 5000 | 20 | 0.142 | 2.69 |
| 全部状态 / [晴, 天, 现, 场, 周] / id ASC / offset=0 / size=20 | 5000 | 20 | 0.0801 | 4.12 |
| 全部状态 / [晴, 天, 现, 场, 周] / id DESC / offset=0 / size=20 | 5000 | 20 | 0.0937 | 3.85 |
| 全部状态 / [晴, 天, 现, 场, 周] / title ASC / offset=0 / size=20 | 5000 | 20 | 0.156 | 3.73 |
| 全部状态 / [晴, 天, 现, 场, 周] / title DESC / offset=0 / size=20 | 5000 | 20 | 0.16 | 3.73 |
| 全部状态 / [晴, 天, 现, 场, 周] / artistName ASC / offset=0 / size=20 | 5000 | 20 | 4.4 | 3.72 |
| 全部状态 / [晴, 天, 现, 场, 周] / artistName DESC / offset=0 / size=20 | 5000 | 20 | 0.0888 | 3.88 |
| 全部状态 / [晴, 天, 现, 场, 周] / createdAt ASC / offset=0 / size=20 | 5000 | 20 | 0.13 | 3.7 |
| 全部状态 / [晴, 天, 现, 场, 周] / createdAt DESC / offset=0 / size=20 | 5000 | 20 | 0.13 | 4.36 |
| 全部状态 / [不存在词XYZ] / id ASC / offset=0 / size=20 | 0 | 0 | 2.78 | 2.93 |
| 全部状态 / [不存在词XYZ] / id DESC / offset=0 / size=20 | 0 | 0 | 3.76 | 3.05 |
| 全部状态 / [不存在词XYZ] / title ASC / offset=0 / size=20 | 0 | 0 | 7.23 | 2.61 |
| 全部状态 / [不存在词XYZ] / title DESC / offset=0 / size=20 | 0 | 0 | 8.5 | 2.7 |
| 全部状态 / [不存在词XYZ] / artistName ASC / offset=0 / size=20 | 0 | 0 | 7.18 | 2.8 |
| 全部状态 / [不存在词XYZ] / artistName DESC / offset=0 / size=20 | 0 | 0 | 8.85 | 2.53 |
| 全部状态 / [不存在词XYZ] / createdAt ASC / offset=0 / size=20 | 0 | 0 | 7.51 | 2.47 |
| 全部状态 / [不存在词XYZ] / createdAt DESC / offset=0 / size=20 | 0 | 0 | 9.36 | 2.78 |
| 全部状态 / [November] / id ASC / offset=0 / size=20 | 8000 | 20 | 0.0718 | 3.07 |
| 全部状态 / [November] / id DESC / offset=0 / size=20 | 8000 | 20 | 0.0813 | 3.02 |
| 全部状态 / [November] / title ASC / offset=0 / size=20 | 8000 | 20 | 0.136 | 3.35 |
| 全部状态 / [November] / title DESC / offset=0 / size=20 | 8000 | 20 | 0.136 | 3.45 |
| 全部状态 / [November] / artistName ASC / offset=0 / size=20 | 8000 | 20 | 0.0917 | 2.92 |
| 全部状态 / [November] / artistName DESC / offset=0 / size=20 | 8000 | 20 | 0.144 | 3.21 |
| 全部状态 / [November] / createdAt ASC / offset=0 / size=20 | 8000 | 20 | 0.108 | 2.92 |
| 全部状态 / [November] / createdAt DESC / offset=0 / size=20 | 8000 | 20 | 0.125 | 2.95 |
| 全部状态 / [晴] / id DESC / offset=0 / size=100 | 10000 | 100 | 0.193 | 2.04 |
| 全部状态 / [晴] / id DESC / offset=10000 / size=100 | 10000 | 0 | 3.1 | 3.22 |
| 全部状态 / [晴, 天, 现, 场, 周] / id DESC / offset=0 / size=100 | 5000 | 100 | 0.263 | 3.6 |
| 全部状态 / [晴, 天, 现, 场, 周] / id DESC / offset=10000 / size=100 | 5000 | 0 | 5.44 | 3.8 |
| PUBLISHED / [晴] / id ASC / offset=0 / size=20 | 3333 | 20 | 0.0904 | 2.73 |
| PUBLISHED / [晴] / id DESC / offset=0 / size=20 | 3333 | 20 | 0.187 | 2.53 |
| PUBLISHED / [晴] / title ASC / offset=0 / size=20 | 3333 | 20 | 0.158 | 2.35 |
| PUBLISHED / [晴] / title DESC / offset=0 / size=20 | 3333 | 20 | 0.159 | 2.5 |
| PUBLISHED / [晴] / artistName ASC / offset=0 / size=20 | 3333 | 20 | 0.104 | 2.57 |
| PUBLISHED / [晴] / artistName DESC / offset=0 / size=20 | 3333 | 20 | 0.108 | 2.39 |
| PUBLISHED / [晴] / createdAt ASC / offset=0 / size=20 | 3333 | 20 | 0.141 | 2.46 |
| PUBLISHED / [晴] / createdAt DESC / offset=0 / size=20 | 3333 | 20 | 0.157 | 2.45 |
| PUBLISHED / [周杰伦, 晴天] / id ASC / offset=0 / size=20 | 1666 | 20 | 0.1 | 2.61 |
| PUBLISHED / [周杰伦, 晴天] / id DESC / offset=0 / size=20 | 1666 | 20 | 0.116 | 2.63 |
| PUBLISHED / [周杰伦, 晴天] / title ASC / offset=0 / size=20 | 1666 | 20 | 0.153 | 2.84 |
| PUBLISHED / [周杰伦, 晴天] / title DESC / offset=0 / size=20 | 1666 | 20 | 0.168 | 2.71 |
| PUBLISHED / [周杰伦, 晴天] / artistName ASC / offset=0 / size=20 | 1666 | 20 | 1.67 | 2.64 |
| PUBLISHED / [周杰伦, 晴天] / artistName DESC / offset=0 / size=20 | 1666 | 20 | 0.131 | 2.63 |
| PUBLISHED / [周杰伦, 晴天] / createdAt ASC / offset=0 / size=20 | 1666 | 20 | 0.152 | 2.68 |
| PUBLISHED / [周杰伦, 晴天] / createdAt DESC / offset=0 / size=20 | 1666 | 20 | 0.166 | 2.82 |
| PUBLISHED / [晴, 天, 现, 场, 周] / id ASC / offset=0 / size=20 | 1666 | 20 | 0.105 | 16.4 |
| PUBLISHED / [晴, 天, 现, 场, 周] / id DESC / offset=0 / size=20 | 1666 | 20 | 0.119 | 3.03 |
| PUBLISHED / [晴, 天, 现, 场, 周] / title ASC / offset=0 / size=20 | 1666 | 20 | 0.179 | 2.98 |
| PUBLISHED / [晴, 天, 现, 场, 周] / title DESC / offset=0 / size=20 | 1666 | 20 | 0.176 | 2.88 |
| PUBLISHED / [晴, 天, 现, 场, 周] / artistName ASC / offset=0 / size=20 | 1666 | 20 | 1.8 | 3.58 |
| PUBLISHED / [晴, 天, 现, 场, 周] / artistName DESC / offset=0 / size=20 | 1666 | 20 | 0.183 | 3.63 |
| PUBLISHED / [晴, 天, 现, 场, 周] / createdAt ASC / offset=0 / size=20 | 1666 | 20 | 0.158 | 2.97 |
| PUBLISHED / [晴, 天, 现, 场, 周] / createdAt DESC / offset=0 / size=20 | 1666 | 20 | 0.221 | 3.29 |
| PUBLISHED / [不存在词XYZ] / id ASC / offset=0 / size=20 | 0 | 0 | 2.47 | 2.45 |
| PUBLISHED / [不存在词XYZ] / id DESC / offset=0 / size=20 | 0 | 0 | 3.51 | 2.52 |
| PUBLISHED / [不存在词XYZ] / title ASC / offset=0 / size=20 | 0 | 0 | 2.93 | 2.47 |
| PUBLISHED / [不存在词XYZ] / title DESC / offset=0 / size=20 | 0 | 0 | 3.27 | 2.47 |
| PUBLISHED / [不存在词XYZ] / artistName ASC / offset=0 / size=20 | 0 | 0 | 3.21 | 2.52 |
| PUBLISHED / [不存在词XYZ] / artistName DESC / offset=0 / size=20 | 0 | 0 | 3.41 | 2.86 |
| PUBLISHED / [不存在词XYZ] / createdAt ASC / offset=0 / size=20 | 0 | 0 | 2.87 | 2.63 |
| PUBLISHED / [不存在词XYZ] / createdAt DESC / offset=0 / size=20 | 0 | 0 | 3.37 | 2.79 |
| PUBLISHED / [November] / id ASC / offset=0 / size=20 | 2667 | 20 | 0.102 | 3.01 |
| PUBLISHED / [November] / id DESC / offset=0 / size=20 | 2667 | 20 | 0.119 | 2.98 |
| PUBLISHED / [November] / title ASC / offset=0 / size=20 | 2667 | 20 | 0.152 | 2.68 |
| PUBLISHED / [November] / title DESC / offset=0 / size=20 | 2667 | 20 | 0.16 | 2.59 |
| PUBLISHED / [November] / artistName ASC / offset=0 / size=20 | 2667 | 20 | 0.102 | 2.71 |
| PUBLISHED / [November] / artistName DESC / offset=0 / size=20 | 2667 | 20 | 0.115 | 2.94 |
| PUBLISHED / [November] / createdAt ASC / offset=0 / size=20 | 2667 | 20 | 0.145 | 3.21 |
| PUBLISHED / [November] / createdAt DESC / offset=0 / size=20 | 2667 | 20 | 0.154 | 2.65 |
| PUBLISHED / [晴] / id DESC / offset=0 / size=100 | 3333 | 100 | 0.253 | 2.37 |
| PUBLISHED / [晴] / id DESC / offset=10000 / size=100 | 3333 | 0 | 2.63 | 2.33 |
| PUBLISHED / [晴, 天, 现, 场, 周] / id DESC / offset=0 / size=100 | 1666 | 100 | 0.424 | 2.89 |
| PUBLISHED / [晴, 天, 现, 场, 周] / id DESC / offset=10000 / size=100 | 1666 | 0 | 3.74 | 2.89 |

## 成本核对

高频首屏可较早获得本页ID，精确COUNT仍评估全部候选歌曲。零命中、文本排序和深分页也可扫描完整候选范围。最大页大小同时覆盖返回100行和合法空页，五词组合、英文正命中及全部八种排序均有实际计划。LIKE前置通配符的扫描取舍已由用户在书面设计中确认，应用通过词长、词数、账户额度和单条SQL预算控制成本。

## 代表性完整计划

## 全部状态 / [晴] / id ASC / offset=0 / size=20

匹配总数为 10000。

```sql
select /*+ MAX_EXECUTION_TIME(2000) */ t.id,t.title,t.artist_name,t.album_name,t.duration_ms,t.status,t.cover_asset_id from tracks t join (select i.id from tracks i where (i.title like :q0 escape '!' or i.artist_name like :q0 escape '!' or i.album_name like :q0 escape '!') order by i.id asc limit :size offset :offset) page_ids on page_ids.id=t.id order by t.id asc
```

```text
[{id=1, select_type=PRIMARY, table=<derived2>, partitions=null, type=ALL, possible_keys=null, key=null, key_len=null, ref=null, rows=20, filtered=100.0, Extra=Using temporary; Using filesort}, {id=1, select_type=PRIMARY, table=t, partitions=null, type=eq_ref, possible_keys=PRIMARY, key=PRIMARY, key_len=8, ref=page_ids.id, rows=1, filtered=100.0, Extra=null}, {id=2, select_type=DERIVED, table=i, partitions=null, type=index, possible_keys=null, key=PRIMARY, key_len=8, ref=null, rows=20, filtered=29.76, Extra=Using where}]
-> Sort: t.id  (actual time=0.0779..0.0788 rows=20 loops=1)
    -> Stream results  (cost=10.2 rows=5.95) (actual time=0.0485..0.0673 rows=20 loops=1)
        -> Nested loop inner join  (cost=10.2 rows=5.95) (actual time=0.0462..0.061 rows=20 loops=1)
            -> Table scan on page_ids  (cost=2.48..4.61 rows=5.95) (actual time=0.0385..0.0398 rows=20 loops=1)
                -> Materialize  (cost=2.05..2.05 rows=5.95) (actual time=0.0378..0.0378 rows=20 loops=1)
                    -> Limit: 20 row(s)  (cost=1.45 rows=5.95) (actual time=0.0224..0.0288 rows=20 loops=1)
                        -> Filter: ((i.title like '%晴%' escape '!') or (i.artist_name like '%晴%' escape '!') or (i.album_name like '%晴%' escape '!'))  (cost=1.45 rows=5.95) (actual time=0.0164..0.0221 rows=20 loops=1)
                            -> Index scan on i using PRIMARY  (cost=1.45 rows=20) (actual time=0.0113..0.0158 rows=20 loops=1)
            -> Single-row index lookup on t using PRIMARY (id=page_ids.id)  (cost=0.255 rows=1) (actual time=781e-6..799e-6 rows=1 loops=20)

```

```sql
select /*+ MAX_EXECUTION_TIME(2000) */ count(*) from tracks i where (i.title like :q0 escape '!' or i.artist_name like :q0 escape '!' or i.album_name like :q0 escape '!')
```

```text
[{id=1, select_type=SIMPLE, table=i, partitions=null, type=ALL, possible_keys=null, key=null, key_len=null, ref=null, rows=10080, filtered=29.76, Extra=Using where}]
-> Aggregate: count(0)  (cost=1332 rows=1) (actual time=2.19..2.19 rows=1 loops=1)
    -> Filter: ((i.title like '%晴%' escape '!') or (i.artist_name like '%晴%' escape '!') or (i.album_name like '%晴%' escape '!'))  (cost=1032 rows=3000) (actual time=0.0195..1.97 rows=10000 loops=1)
        -> Table scan on i  (cost=1032 rows=10080) (actual time=0.0175..1.34 rows=10000 loops=1)

```

## 全部状态 / [不存在词XYZ] / id ASC / offset=0 / size=20

匹配总数为 0。

```sql
select /*+ MAX_EXECUTION_TIME(2000) */ t.id,t.title,t.artist_name,t.album_name,t.duration_ms,t.status,t.cover_asset_id from tracks t join (select i.id from tracks i where (i.title like :q0 escape '!' or i.artist_name like :q0 escape '!' or i.album_name like :q0 escape '!') order by i.id asc limit :size offset :offset) page_ids on page_ids.id=t.id order by t.id asc
```

```text
[{id=1, select_type=PRIMARY, table=<derived2>, partitions=null, type=ALL, possible_keys=null, key=null, key_len=null, ref=null, rows=20, filtered=100.0, Extra=Using temporary; Using filesort}, {id=1, select_type=PRIMARY, table=t, partitions=null, type=eq_ref, possible_keys=PRIMARY, key=PRIMARY, key_len=8, ref=page_ids.id, rows=1, filtered=100.0, Extra=null}, {id=2, select_type=DERIVED, table=i, partitions=null, type=index, possible_keys=null, key=PRIMARY, key_len=8, ref=null, rows=20, filtered=29.76, Extra=Using where}]
-> Sort: t.id  (actual time=2.78..2.78 rows=0 loops=1)
    -> Stream results  (cost=10.2 rows=5.95) (actual time=2.77..2.77 rows=0 loops=1)
        -> Nested loop inner join  (cost=10.2 rows=5.95) (actual time=2.77..2.77 rows=0 loops=1)
            -> Table scan on page_ids  (cost=2.48..4.61 rows=5.95) (actual time=2.77..2.77 rows=0 loops=1)
                -> Materialize  (cost=2.05..2.05 rows=5.95) (actual time=2.77..2.77 rows=0 loops=1)
                    -> Limit: 20 row(s)  (cost=1.45 rows=5.95) (actual time=2.76..2.76 rows=0 loops=1)
                        -> Filter: ((i.title like '%不存在词XYZ%' escape '!') or (i.artist_name like '%不存在词XYZ%' escape '!') or (i.album_name like '%不存在词XYZ%' escape '!'))  (cost=1.45 rows=5.95) (actual time=2.76..2.76 rows=0 loops=1)
                            -> Index scan on i using PRIMARY  (cost=1.45 rows=20) (actual time=0.00986..1.47 rows=10000 loops=1)
            -> Single-row index lookup on t using PRIMARY (id=page_ids.id)  (cost=0.255 rows=1) (never executed)

```

```sql
select /*+ MAX_EXECUTION_TIME(2000) */ count(*) from tracks i where (i.title like :q0 escape '!' or i.artist_name like :q0 escape '!' or i.album_name like :q0 escape '!')
```

```text
[{id=1, select_type=SIMPLE, table=i, partitions=null, type=ALL, possible_keys=null, key=null, key_len=null, ref=null, rows=10080, filtered=29.76, Extra=Using where}]
-> Aggregate: count(0)  (cost=1332 rows=1) (actual time=2.93..2.93 rows=1 loops=1)
    -> Filter: ((i.title like '%不存在词XYZ%' escape '!') or (i.artist_name like '%不存在词XYZ%' escape '!') or (i.album_name like '%不存在词XYZ%' escape '!'))  (cost=1032 rows=3000) (actual time=2.93..2.93 rows=0 loops=1)
        -> Table scan on i  (cost=1032 rows=10080) (actual time=0.0174..1.4 rows=10000 loops=1)

```

## 全部状态 / [晴] / id DESC / offset=10000 / size=100

匹配总数为 10000。

```sql
select /*+ MAX_EXECUTION_TIME(2000) */ t.id,t.title,t.artist_name,t.album_name,t.duration_ms,t.status,t.cover_asset_id from tracks t join (select i.id from tracks i where (i.title like :q0 escape '!' or i.artist_name like :q0 escape '!' or i.album_name like :q0 escape '!') order by i.id desc limit :size offset :offset) page_ids on page_ids.id=t.id order by t.id desc
```

```text
[{id=1, select_type=PRIMARY, table=<derived2>, partitions=null, type=ALL, possible_keys=null, key=null, key_len=null, ref=null, rows=3000, filtered=100.0, Extra=Using temporary; Using filesort}, {id=1, select_type=PRIMARY, table=t, partitions=null, type=eq_ref, possible_keys=PRIMARY, key=PRIMARY, key_len=8, ref=page_ids.id, rows=1, filtered=100.0, Extra=null}, {id=2, select_type=DERIVED, table=i, partitions=null, type=index, possible_keys=null, key=PRIMARY, key_len=8, ref=null, rows=10080, filtered=29.76, Extra=Using where; Backward index scan}]
-> Sort: t.id DESC  (actual time=3.1..3.1 rows=0 loops=1)
    -> Stream results  (cost=1785 rows=0) (actual time=3.1..3.1 rows=0 loops=1)
        -> Nested loop inner join  (cost=1785 rows=0) (actual time=3.1..3.1 rows=0 loops=1)
            -> Table scan on page_ids  (cost=1035..1035 rows=0) (actual time=3.1..3.1 rows=0 loops=1)
                -> Materialize  (cost=1032..1032 rows=0) (actual time=3.09..3.09 rows=0 loops=1)
                    -> Limit/Offset: 100/10000 row(s)  (cost=1032 rows=0) (actual time=3.09..3.09 rows=0 loops=1)
                        -> Filter: ((i.title like '%晴%' escape '!') or (i.artist_name like '%晴%' escape '!') or (i.album_name like '%晴%' escape '!'))  (cost=1032 rows=3000) (actual time=0.0308..2.88 rows=10000 loops=1)
                            -> Index scan on i using PRIMARY (reverse)  (cost=1032 rows=10080) (actual time=0.0285..2.33 rows=10000 loops=1)
            -> Single-row index lookup on t using PRIMARY (id=page_ids.id)  (cost=0.25 rows=1) (never executed)

```

```sql
select /*+ MAX_EXECUTION_TIME(2000) */ count(*) from tracks i where (i.title like :q0 escape '!' or i.artist_name like :q0 escape '!' or i.album_name like :q0 escape '!')
```

```text
[{id=1, select_type=SIMPLE, table=i, partitions=null, type=ALL, possible_keys=null, key=null, key_len=null, ref=null, rows=10080, filtered=29.76, Extra=Using where}]
-> Aggregate: count(0)  (cost=1332 rows=1) (actual time=3.22..3.22 rows=1 loops=1)
    -> Filter: ((i.title like '%晴%' escape '!') or (i.artist_name like '%晴%' escape '!') or (i.album_name like '%晴%' escape '!'))  (cost=1032 rows=3000) (actual time=0.0197..2.86 rows=10000 loops=1)
        -> Table scan on i  (cost=1032 rows=10080) (actual time=0.018..1.98 rows=10000 loops=1)

```

## 全部状态 / [晴, 天, 现, 场, 周] / id DESC / offset=0 / size=100

匹配总数为 5000。

```sql
select /*+ MAX_EXECUTION_TIME(2000) */ t.id,t.title,t.artist_name,t.album_name,t.duration_ms,t.status,t.cover_asset_id from tracks t join (select i.id from tracks i where (i.title like :q0 escape '!' or i.artist_name like :q0 escape '!' or i.album_name like :q0 escape '!') and (i.title like :q1 escape '!' or i.artist_name like :q1 escape '!' or i.album_name like :q1 escape '!') and (i.title like :q2 escape '!' or i.artist_name like :q2 escape '!' or i.album_name like :q2 escape '!') and (i.title like :q3 escape '!' or i.artist_name like :q3 escape '!' or i.album_name like :q3 escape '!') and (i.title like :q4 escape '!' or i.artist_name like :q4 escape '!' or i.album_name like :q4 escape '!') order by i.id desc limit :size offset :offset) page_ids on page_ids.id=t.id order by t.id desc
```

```text
[{id=1, select_type=PRIMARY, table=<derived2>, partitions=null, type=ALL, possible_keys=null, key=null, key_len=null, ref=null, rows=23, filtered=100.0, Extra=Using temporary; Using filesort}, {id=1, select_type=PRIMARY, table=t, partitions=null, type=eq_ref, possible_keys=PRIMARY, key=PRIMARY, key_len=8, ref=page_ids.id, rows=1, filtered=100.0, Extra=null}, {id=2, select_type=DERIVED, table=i, partitions=null, type=index, possible_keys=null, key=PRIMARY, key_len=8, ref=null, rows=100, filtered=0.23, Extra=Using where; Backward index scan}]
-> Sort: t.id DESC  (actual time=0.26..0.263 rows=100 loops=1)
    -> Stream results  (cost=18.5 rows=0.234) (actual time=0.148..0.242 rows=100 loops=1)
        -> Nested loop inner join  (cost=18.5 rows=0.234) (actual time=0.146..0.222 rows=100 loops=1)
            -> Table scan on page_ids  (cost=12.7..12.7 rows=0.234) (actual time=0.141..0.148 rows=100 loops=1)
                -> Materialize  (cost=10.2..10.2 rows=0.234) (actual time=0.141..0.141 rows=100 loops=1)
                    -> Limit: 100 row(s)  (cost=10.2 rows=0.234) (actual time=0.0195..0.127 rows=100 loops=1)
                        -> Filter: (((i.title like '%晴%' escape '!') or (i.artist_name like '%晴%' escape '!') or (i.album_name like '%晴%' escape '!')) and ((i.title like '%天%' escape '!') or (i.artist_name like '%天%' escape '!') or (i.album_name like '%天%' escape '!')) and ((i.title like '%现%' escape '!') or (i.artist_name like '%现%' escape '!') or (i.album_name like '%现%' escape '!')) and ((i.title like '%场%' escape '!') or (i.artist_name like '%场%' escape '!') or (i.album_name like '%场%' escape '!')) and ((i.title like '%周%' escape '!') or (i.artist_name like '%周%' escape '!') or (i.album_name like '%周%' escape '!')))  (cost=10.2 rows=0.234) (actual time=0.0189..0.122 rows=100 loops=1)
                            -> Index scan on i using PRIMARY (reverse)  (cost=10.2 rows=100) (actual time=0.0142..0.0684 rows=200 loops=1)
            -> Single-row index lookup on t using PRIMARY (id=page_ids.id)  (cost=0.254 rows=1) (actual time=619e-6..637e-6 rows=1 loops=100)

```

```sql
select /*+ MAX_EXECUTION_TIME(2000) */ count(*) from tracks i where (i.title like :q0 escape '!' or i.artist_name like :q0 escape '!' or i.album_name like :q0 escape '!') and (i.title like :q1 escape '!' or i.artist_name like :q1 escape '!' or i.album_name like :q1 escape '!') and (i.title like :q2 escape '!' or i.artist_name like :q2 escape '!' or i.album_name like :q2 escape '!') and (i.title like :q3 escape '!' or i.artist_name like :q3 escape '!' or i.album_name like :q3 escape '!') and (i.title like :q4 escape '!' or i.artist_name like :q4 escape '!' or i.album_name like :q4 escape '!')
```

```text
[{id=1, select_type=SIMPLE, table=i, partitions=null, type=ALL, possible_keys=null, key=null, key_len=null, ref=null, rows=10080, filtered=0.23, Extra=Using where}]
-> Aggregate: count(0)  (cost=1035 rows=1) (actual time=3.6..3.6 rows=1 loops=1)
    -> Filter: (((i.title like '%晴%' escape '!') or (i.artist_name like '%晴%' escape '!') or (i.album_name like '%晴%' escape '!')) and ((i.title like '%天%' escape '!') or (i.artist_name like '%天%' escape '!') or (i.album_name like '%天%' escape '!')) and ((i.title like '%现%' escape '!') or (i.artist_name like '%现%' escape '!') or (i.album_name like '%现%' escape '!')) and ((i.title like '%场%' escape '!') or (i.artist_name like '%场%' escape '!') or (i.album_name like '%场%' escape '!')) and ((i.title like '%周%' escape '!') or (i.artist_name like '%周%' escape '!') or (i.album_name like '%周%' escape '!')))  (cost=1032 rows=23.5) (actual time=0.0184..3.49 rows=5000 loops=1)
        -> Table scan on i  (cost=1032 rows=10080) (actual time=0.0159..1.24 rows=10000 loops=1)

```

## PUBLISHED / [周杰伦, 晴天] / title ASC / offset=0 / size=20

匹配总数为 1666。

```sql
select /*+ MAX_EXECUTION_TIME(2000) */ t.id,t.title,t.artist_name,t.album_name,t.duration_ms,t.status,t.cover_asset_id from tracks t join (select i.id from tracks i where i.status=:status and (i.title like :q0 escape '!' or i.artist_name like :q0 escape '!' or i.album_name like :q0 escape '!') and (i.title like :q1 escape '!' or i.artist_name like :q1 escape '!' or i.album_name like :q1 escape '!') order by i.title asc,i.id asc limit :size offset :offset) page_ids on page_ids.id=t.id order by t.title asc,t.id asc
```

```text
[{id=1, select_type=PRIMARY, table=<derived2>, partitions=null, type=ALL, possible_keys=null, key=null, key_len=null, ref=null, rows=20, filtered=100.0, Extra=Using temporary; Using filesort}, {id=1, select_type=PRIMARY, table=t, partitions=null, type=eq_ref, possible_keys=PRIMARY, key=PRIMARY, key_len=8, ref=page_ids.id, rows=1, filtered=100.0, Extra=null}, {id=2, select_type=DERIVED, table=i, partitions=null, type=ref, possible_keys=ix_tracks_status,ix_tracks_status_title,ix_tracks_status_artist_name,ix_tracks_status_created_at, key=ix_tracks_status_title, key_len=82, ref=const, rows=3333, filtered=8.86, Extra=Using where}]
-> Sort: t.title, t.id  (actual time=0.152..0.153 rows=20 loops=1)
    -> Stream results  (cost=114 rows=20) (actual time=0.117..0.138 rows=20 loops=1)
        -> Nested loop inner join  (cost=114 rows=20) (actual time=0.115..0.132 rows=20 loops=1)
            -> Table scan on page_ids  (cost=104..107 rows=20) (actual time=0.112..0.113 rows=20 loops=1)
                -> Materialize  (cost=104..104 rows=20) (actual time=0.111..0.111 rows=20 loops=1)
                    -> Limit: 20 row(s)  (cost=102 rows=20) (actual time=0.0925..0.102 rows=20 loops=1)
                        -> Filter: (((i.title like '%周杰伦%' escape '!') or (i.artist_name like '%周杰伦%' escape '!') or (i.album_name like '%周杰伦%' escape '!')) and ((i.title like '%晴天%' escape '!') or (i.artist_name like '%晴天%' escape '!') or (i.album_name like '%晴天%' escape '!')))  (cost=102 rows=295) (actual time=0.0917..0.101 rows=20 loops=1)
                            -> Index lookup on i using ix_tracks_status_title (status='PUBLISHED')  (cost=102 rows=3333) (actual time=0.0893..0.0921 rows=39 loops=1)
            -> Single-row index lookup on t using PRIMARY (id=page_ids.id)  (cost=0.255 rows=1) (actual time=811e-6..829e-6 rows=1 loops=20)

```

```sql
select /*+ MAX_EXECUTION_TIME(2000) */ count(*) from tracks i where i.status=:status and (i.title like :q0 escape '!' or i.artist_name like :q0 escape '!' or i.album_name like :q0 escape '!') and (i.title like :q1 escape '!' or i.artist_name like :q1 escape '!' or i.album_name like :q1 escape '!')
```

```text
[{id=1, select_type=SIMPLE, table=i, partitions=null, type=ref, possible_keys=ix_tracks_status,ix_tracks_status_title,ix_tracks_status_artist_name,ix_tracks_status_created_at, key=ix_tracks_status, key_len=82, ref=const, rows=3333, filtered=8.86, Extra=Using where}]
-> Aggregate: count(0)  (cost=132 rows=1) (actual time=2.84..2.84 rows=1 loops=1)
    -> Filter: (((i.title like '%周杰伦%' escape '!') or (i.artist_name like '%周杰伦%' escape '!') or (i.album_name like '%周杰伦%' escape '!')) and ((i.title like '%晴天%' escape '!') or (i.artist_name like '%晴天%' escape '!') or (i.album_name like '%晴天%' escape '!')))  (cost=102 rows=295) (actual time=0.119..2.79 rows=1666 loops=1)
        -> Index lookup on i using ix_tracks_status (status='PUBLISHED')  (cost=102 rows=3333) (actual time=0.112..2.2 rows=3333 loops=1)

```

## PUBLISHED / [November] / id ASC / offset=0 / size=20

匹配总数为 2667。

```sql
select /*+ MAX_EXECUTION_TIME(2000) */ t.id,t.title,t.artist_name,t.album_name,t.duration_ms,t.status,t.cover_asset_id from tracks t join (select i.id from tracks i where i.status=:status and (i.title like :q0 escape '!' or i.artist_name like :q0 escape '!' or i.album_name like :q0 escape '!') order by i.id asc limit :size offset :offset) page_ids on page_ids.id=t.id order by t.id asc
```

```text
[{id=1, select_type=PRIMARY, table=<derived2>, partitions=null, type=ALL, possible_keys=null, key=null, key_len=null, ref=null, rows=20, filtered=100.0, Extra=Using temporary; Using filesort}, {id=1, select_type=PRIMARY, table=t, partitions=null, type=eq_ref, possible_keys=PRIMARY, key=PRIMARY, key_len=8, ref=page_ids.id, rows=1, filtered=100.0, Extra=null}, {id=2, select_type=DERIVED, table=i, partitions=null, type=ref, possible_keys=ix_tracks_status,ix_tracks_status_title,ix_tracks_status_artist_name,ix_tracks_status_created_at, key=ix_tracks_status, key_len=82, ref=const, rows=3333, filtered=29.76, Extra=Using where}]
-> Sort: t.id  (actual time=0.101..0.102 rows=20 loops=1)
    -> Stream results  (cost=184 rows=20) (actual time=0.0716..0.0902 rows=20 loops=1)
        -> Nested loop inner join  (cost=184 rows=20) (actual time=0.0697..0.0847 rows=20 loops=1)
            -> Table scan on page_ids  (cost=174..177 rows=20) (actual time=0.0661..0.0675 rows=20 loops=1)
                -> Materialize  (cost=174..174 rows=20) (actual time=0.0655..0.0655 rows=20 loops=1)
                    -> Limit: 20 row(s)  (cost=172 rows=20) (actual time=0.0497..0.0568 rows=20 loops=1)
                        -> Filter: ((i.title like '%November%' escape '!') or (i.artist_name like '%November%' escape '!') or (i.album_name like '%November%' escape '!'))  (cost=172 rows=992) (actual time=0.0488..0.0552 rows=20 loops=1)
                            -> Index lookup on i using ix_tracks_status (status='PUBLISHED')  (cost=172 rows=3333) (actual time=0.0462..0.0483 rows=25 loops=1)
            -> Single-row index lookup on t using PRIMARY (id=page_ids.id)  (cost=0.255 rows=1) (actual time=711e-6..728e-6 rows=1 loops=20)

```

```sql
select /*+ MAX_EXECUTION_TIME(2000) */ count(*) from tracks i where i.status=:status and (i.title like :q0 escape '!' or i.artist_name like :q0 escape '!' or i.album_name like :q0 escape '!')
```

```text
[{id=1, select_type=SIMPLE, table=i, partitions=null, type=ref, possible_keys=ix_tracks_status,ix_tracks_status_title,ix_tracks_status_artist_name,ix_tracks_status_created_at, key=ix_tracks_status, key_len=82, ref=const, rows=3333, filtered=29.76, Extra=Using where}]
-> Aggregate: count(0)  (cost=271 rows=1) (actual time=3.01..3.01 rows=1 loops=1)
    -> Filter: ((i.title like '%November%' escape '!') or (i.artist_name like '%November%' escape '!') or (i.album_name like '%November%' escape '!'))  (cost=172 rows=992) (actual time=0.0418..2.94 rows=2667 loops=1)
        -> Index lookup on i using ix_tracks_status (status='PUBLISHED')  (cost=172 rows=3333) (actual time=0.0392..2.39 rows=3333 loops=1)

```

## PUBLISHED / [晴] / id DESC / offset=0 / size=100

匹配总数为 3333。

```sql
select /*+ MAX_EXECUTION_TIME(2000) */ t.id,t.title,t.artist_name,t.album_name,t.duration_ms,t.status,t.cover_asset_id from tracks t join (select i.id from tracks i where i.status=:status and (i.title like :q0 escape '!' or i.artist_name like :q0 escape '!' or i.album_name like :q0 escape '!') order by i.id desc limit :size offset :offset) page_ids on page_ids.id=t.id order by t.id desc
```

```text
[{id=1, select_type=PRIMARY, table=<derived2>, partitions=null, type=ALL, possible_keys=null, key=null, key_len=null, ref=null, rows=100, filtered=100.0, Extra=Using temporary; Using filesort}, {id=1, select_type=PRIMARY, table=t, partitions=null, type=eq_ref, possible_keys=PRIMARY, key=PRIMARY, key_len=8, ref=page_ids.id, rows=1, filtered=100.0, Extra=null}, {id=2, select_type=DERIVED, table=i, partitions=null, type=ref, possible_keys=ix_tracks_status,ix_tracks_status_title,ix_tracks_status_artist_name,ix_tracks_status_created_at, key=ix_tracks_status, key_len=82, ref=const, rows=3333, filtered=29.76, Extra=Using where; Backward index scan}]
-> Sort: t.id DESC  (actual time=0.249..0.253 rows=100 loops=1)
    -> Stream results  (cost=221 rows=100) (actual time=0.137..0.232 rows=100 loops=1)
        -> Nested loop inner join  (cost=221 rows=100) (actual time=0.135..0.212 rows=100 loops=1)
            -> Table scan on page_ids  (cost=182..186 rows=100) (actual time=0.132..0.138 rows=100 loops=1)
                -> Materialize  (cost=182..182 rows=100) (actual time=0.131..0.131 rows=100 loops=1)
                    -> Limit: 100 row(s)  (cost=172 rows=100) (actual time=0.0498..0.119 rows=100 loops=1)
                        -> Filter: ((i.title like '%晴%' escape '!') or (i.artist_name like '%晴%' escape '!') or (i.album_name like '%晴%' escape '!'))  (cost=172 rows=992) (actual time=0.0489..0.114 rows=100 loops=1)
                            -> Index lookup on i using ix_tracks_status (status='PUBLISHED') (reverse)  (cost=172 rows=3333) (actual time=0.0473..0.107 rows=100 loops=1)
            -> Single-row index lookup on t using PRIMARY (id=page_ids.id)  (cost=0.251 rows=1) (actual time=616e-6..634e-6 rows=1 loops=100)

```

```sql
select /*+ MAX_EXECUTION_TIME(2000) */ count(*) from tracks i where i.status=:status and (i.title like :q0 escape '!' or i.artist_name like :q0 escape '!' or i.album_name like :q0 escape '!')
```

```text
[{id=1, select_type=SIMPLE, table=i, partitions=null, type=ref, possible_keys=ix_tracks_status,ix_tracks_status_title,ix_tracks_status_artist_name,ix_tracks_status_created_at, key=ix_tracks_status, key_len=82, ref=const, rows=3333, filtered=29.76, Extra=Using where}]
-> Aggregate: count(0)  (cost=271 rows=1) (actual time=2.37..2.37 rows=1 loops=1)
    -> Filter: ((i.title like '%晴%' escape '!') or (i.artist_name like '%晴%' escape '!') or (i.album_name like '%晴%' escape '!'))  (cost=172 rows=992) (actual time=0.0366..2.29 rows=3333 loops=1)
        -> Index lookup on i using ix_tracks_status (status='PUBLISHED')  (cost=172 rows=3333) (actual time=0.0352..2.1 rows=3333 loops=1)

```

## PUBLISHED / [晴, 天, 现, 场, 周] / id DESC / offset=0 / size=100

匹配总数为 1666。

```sql
select /*+ MAX_EXECUTION_TIME(2000) */ t.id,t.title,t.artist_name,t.album_name,t.duration_ms,t.status,t.cover_asset_id from tracks t join (select i.id from tracks i where i.status=:status and (i.title like :q0 escape '!' or i.artist_name like :q0 escape '!' or i.album_name like :q0 escape '!') and (i.title like :q1 escape '!' or i.artist_name like :q1 escape '!' or i.album_name like :q1 escape '!') and (i.title like :q2 escape '!' or i.artist_name like :q2 escape '!' or i.album_name like :q2 escape '!') and (i.title like :q3 escape '!' or i.artist_name like :q3 escape '!' or i.album_name like :q3 escape '!') and (i.title like :q4 escape '!' or i.artist_name like :q4 escape '!' or i.album_name like :q4 escape '!') order by i.id desc limit :size offset :offset) page_ids on page_ids.id=t.id order by t.id desc
```

```text
[{id=1, select_type=PRIMARY, table=<derived2>, partitions=null, type=ALL, possible_keys=null, key=null, key_len=null, ref=null, rows=7, filtered=100.0, Extra=Using temporary; Using filesort}, {id=1, select_type=PRIMARY, table=t, partitions=null, type=eq_ref, possible_keys=PRIMARY, key=PRIMARY, key_len=8, ref=page_ids.id, rows=1, filtered=100.0, Extra=null}, {id=2, select_type=DERIVED, table=i, partitions=null, type=ref, possible_keys=ix_tracks_status,ix_tracks_status_title,ix_tracks_status_artist_name,ix_tracks_status_created_at, key=ix_tracks_status, key_len=82, ref=const, rows=3333, filtered=0.23, Extra=Using where; Backward index scan}]
-> Sort: t.id DESC  (actual time=0.421..0.424 rows=100 loops=1)
    -> Stream results  (cost=79.4 rows=7.79) (actual time=0.302..0.402 rows=100 loops=1)
        -> Nested loop inner join  (cost=79.4 rows=7.79) (actual time=0.3..0.382 rows=100 loops=1)
            -> Table scan on page_ids  (cost=74.6..76.9 rows=7.79) (actual time=0.295..0.302 rows=100 loops=1)
                -> Materialize  (cost=74.3..74.3 rows=7.79) (actual time=0.294..0.294 rows=100 loops=1)
                    -> Limit: 100 row(s)  (cost=73.5 rows=7.79) (actual time=0.0821..0.267 rows=100 loops=1)
                        -> Filter: (((i.title like '%晴%' escape '!') or (i.artist_name like '%晴%' escape '!') or (i.album_name like '%晴%' escape '!')) and ((i.title like '%天%' escape '!') or (i.artist_name like '%天%' escape '!') or (i.album_name like '%天%' escape '!')) and ((i.title like '%现%' escape '!') or (i.artist_name like '%现%' escape '!') or (i.album_name like '%现%' escape '!')) and ((i.title like '%场%' escape '!') or (i.artist_name like '%场%' escape '!') or (i.album_name like '%场%' escape '!')) and ((i.title like '%周%' escape '!') or (i.artist_name like '%周%' escape '!') or (i.album_name like '%周%' escape '!')))  (cost=73.5 rows=7.79) (actual time=0.0809..0.262 rows=100 loops=1)
                            -> Index lookup on i using ix_tracks_status (status='PUBLISHED') (reverse)  (cost=73.5 rows=3333) (actual time=0.0734..0.211 rows=200 loops=1)
            -> Single-row index lookup on t using PRIMARY (id=page_ids.id)  (cost=0.264 rows=1) (actual time=670e-6..688e-6 rows=1 loops=100)

```

```sql
select /*+ MAX_EXECUTION_TIME(2000) */ count(*) from tracks i where i.status=:status and (i.title like :q0 escape '!' or i.artist_name like :q0 escape '!' or i.album_name like :q0 escape '!') and (i.title like :q1 escape '!' or i.artist_name like :q1 escape '!' or i.album_name like :q1 escape '!') and (i.title like :q2 escape '!' or i.artist_name like :q2 escape '!' or i.album_name like :q2 escape '!') and (i.title like :q3 escape '!' or i.artist_name like :q3 escape '!' or i.album_name like :q3 escape '!') and (i.title like :q4 escape '!' or i.artist_name like :q4 escape '!' or i.album_name like :q4 escape '!')
```

```text
[{id=1, select_type=SIMPLE, table=i, partitions=null, type=ref, possible_keys=ix_tracks_status,ix_tracks_status_title,ix_tracks_status_artist_name,ix_tracks_status_created_at, key=ix_tracks_status, key_len=82, ref=const, rows=3333, filtered=0.23, Extra=Using where}]
-> Aggregate: count(0)  (cost=74.3 rows=1) (actual time=2.89..2.89 rows=1 loops=1)
    -> Filter: (((i.title like '%晴%' escape '!') or (i.artist_name like '%晴%' escape '!') or (i.album_name like '%晴%' escape '!')) and ((i.title like '%天%' escape '!') or (i.artist_name like '%天%' escape '!') or (i.album_name like '%天%' escape '!')) and ((i.title like '%现%' escape '!') or (i.artist_name like '%现%' escape '!') or (i.album_name like '%现%' escape '!')) and ((i.title like '%场%' escape '!') or (i.artist_name like '%场%' escape '!') or (i.album_name like '%场%' escape '!')) and ((i.title like '%周%' escape '!') or (i.artist_name like '%周%' escape '!') or (i.album_name like '%周%' escape '!')))  (cost=73.5 rows=7.79) (actual time=0.0528..2.83 rows=1666 loops=1)
        -> Index lookup on i using ix_tracks_status (status='PUBLISHED')  (cost=73.5 rows=3333) (actual time=0.0485..2.06 rows=3333 loops=1)

```
