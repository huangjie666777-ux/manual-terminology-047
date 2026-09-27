# Manual Search SDK

英文技术文档检索 SDK（设备厂商售后场景），基于 Java 17 + Lucene 9.12.1。
支持批量写入、BM25 检索（标题权重 3 / 正文 1）、快照隔离的分页会话与精确命中范围。

## 模块结构

- `com.example.manualsdk.model` — ManualDocument、SearchHit、HitRange 数据模型
- `com.example.manualsdk.index` — ManualIndex（索引生命周期、原子批量写入）、DocumentOp
- `com.example.manualsdk.query` — ManualQuery 查询树、QueryCompiler、HitRangeExtractor
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
- SearchSession 固定创建时的索引快照：翻页期间的新增、替换、删除不会导致
  结果重复、遗漏或正文漂移；新会话可见最新提交。会话用毕必须 close()，
  关闭后继续使用抛出 IllegalStateException。
- 写入与查询可在单进程内并行；applyBatch 串行提交，读者始终看到最近一次提交。

## 构建、测试与运行

```bash
mvn test
mvn package
mvn exec:java -Dexec.mainClass=com.example.manualsdk.demo.DemoMain
```

示例演示：批量写入 → 分页会话（翻页中执行替换/删除/新增）→ 新会话看到最新提交 →
关闭重开后结果保留。
