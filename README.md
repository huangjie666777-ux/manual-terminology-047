# Manual Search SDK

英文技术文档检索 SDK（设备厂商售后场景），基于 Java 17 + Lucene 9.12.1。
支持批量写入、BM25 检索（标题权重 3 / 正文 1）、快照隔离的分页会话与精确命中范围。

## 模块结构

- `com.example.manualsdk.model` — ManualDocument、SearchHit、HitRange 数据模型
- `com.example.manualsdk.index` — ManualIndex（索引生命周期、原子批量写入）、DocumentOp
- `com.example.manualsdk.query` — ManualQuery 查询树、QueryCompiler、HitRangeExtractor、DeviceTermDictionary
- `com.example.manualsdk.session` — SearchSession（固定快照分页）、SearchPage
- `com.example.manualsdk.demo` — DemoMain 端到端示例

## API 概览

```java
ManualIndex index = ManualIndex.open(Path.of("index-dir"));

index.applyBatch(List.of(
        DocumentOp.add(new ManualDocument("M-001", "Engine maintenance", "Replace the oil filter.")),
        DocumentOp.replace(new ManualDocument("M-002", "Transmission", "Fluid check.")),
        DocumentOp.delete("M-003")));

ManualQuery query = ManualQuery.or(
        ManualQuery.term(Field.TITLE, "engine"),
        ManualQuery.phrase(Field.BODY, "oil filter"),
        ManualQuery.prefix(Field.ALL, "trans"));

SearchSession session = index.openSession(query, 20);
SearchPage page = session.nextPage();
for (SearchHit hit : page.hits()) {
    // hit.id(), hit.score(), hit.title(), hit.body(), hit.ranges()
}
session.close();
index.close();
```

### 设备术语词典

词典是有方向的内存映射：查询中的一个归一化英文词或词组可以匹配原文及多个替代词组；
不会自动反向扩展，也不会对替代结果再次递归扩展。词项和短语查询会使用词典，前缀查询不扩展。
短语按从左到右的最长规则匹配，多个替代词组成可选路径；多处替换可组合，但每条路径都必须按词序连续命中。

```java
index.replaceDeviceTerms(List.of(
        new DeviceTermRule("ecu", List.of("electronic control unit", "engine control module")),
        DeviceTermRule.of("brake pad", "friction material")));

ManualQuery query = ManualQuery.phrase(Field.BODY, "ecu brake pad");
index.replaceDeviceTerms(List.of()); // 发布空表即停用扩展，词典不落盘
```

`replaceDeviceTerms` 是整表替换：先使用索引的 StandardAnalyzer 完成归一化和校验，拒绝 null、空白、
空替代列表以及分析后为空的 source/alternative；重复 source 和重复 alternative 会合并。校验或分析失败时
保留旧词典。成功后发布不可变副本，调用方后续修改入参集合不会影响已发布内容。更新词典不需要重建索引，
可与检索并行；重开索引时词典为空。

### 查询规则

- 索引与查询均使用 StandardAnalyzer，保留停用词，分析行为一致。
- 评分为 Lucene 默认 BM25；标题子句加权 3，正文加权 1。
- 排序：分数降序，同分按 id 升序。
- 空文本、空子句、null 查询等非法结构抛出 QueryException。

### 命中范围

HitRange(field, start, end) 为原字段文本的 UTF-16 左闭右开偏移，通过对存储文本
重新分析（与索引同一 Analyzer）并匹配查询树得到，不做字符串替换猜测。短语只标出
连续匹配片段，前缀标出完整匹配词；范围去重并按字段与位置排序。

## 资源生命周期

- ManualIndex 实现 AutoCloseable；close() 会收回所有未关闭会话并释放
  writer / searcher / 目录资源。已提交批次在关闭重开后仍然保留。
- SearchSession 固定创建时的索引快照和已发布词典：查询在创建时完成词典扩展与 Lucene 编译，
  翻页期间的新增、替换、删除和词典替换不会导致结果重复、遗漏、正文漂移或高亮规则漂移；
  新会话采用创建时的最新提交和词典。会话用毕必须 close()，关闭后继续使用抛出 IllegalStateException。
- 写入与查询可在单进程内并行；applyBatch 串行提交，读者始终看到最近一次提交。

## 构建、测试与运行

```bash
mvn test
mvn package
mvn exec:java -Dexec.mainClass=com.example.manualsdk.demo.DemoMain
```

示例演示：批量写入 → 分页会话（翻页中执行替换/删除/新增）→ 新会话看到最新提交 →
关闭重开后结果保留。
