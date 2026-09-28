# Manual Search SDK

英文技术文档检索 SDK（设备厂商售后场景），基于 Java 17 + Lucene 9.12.1。
支持批量写入、BM25 检索（标题权重 3 / 正文 1）、设备术语别名扩展、快照隔离的分页会话与精确命中范围。

## 模块结构

- `com.example.manualsdk.model` — ManualDocument、SearchHit、HitRange 数据模型
- `com.example.manualsdk.index` — ManualIndex（索引生命周期、原子批量写入）、DocumentOp
- `com.example.manualsdk.query` — ManualQuery 查询树、QueryCompiler、HitRangeExtractor
- `com.example.manualsdk.session` — SearchSession（固定快照分页）、SearchPage
- `com.example.manualsdk.terminology` — TerminologyRule、内存不可变 TerminologyDictionary
- `com.example.manualsdk.demo` — DemoMain 端到端示例

## API 概览

```java
ManualIndex index = ManualIndex.open(Path.of("index-dir"));

index.applyBatch(List.of(
        DocumentOp.add(new ManualDocument("M-001", "Engine maintenance", "Replace the oil filter.")),
        DocumentOp.replace(new ManualDocument("M-002", "Transmission", "Fluid check.")),
        DocumentOp.delete("M-003")));

index.replaceTerminology(List.of(
        new TerminologyRule("ecu", List.of("engine control unit", "electronic control module")),
        TerminologyRule.of("oil filter", "lubricant filter")));

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

- `replaceTerminology(List<TerminologyRule>)` 是整表替换，不是增量合并；空列表会停用扩展。
- 每条规则将一个英文词或词组映射到多个替代词组。源和替代项都通过现有 `StandardAnalyzer` 归一化；
  null、空白或分析后为空的输入会导致发布失败，失败时旧词典继续生效。
- 相同源规则会合并并去重替代项；发布结果为不可变快照，调用方之后修改传入列表不会影响已发布词典。
- 映射只按声明方向生效：查询源表达式可命中原文或替代项，查询替代项不会反向命中源表达式；替代项自身不再递归扩展。
- 只有 term 和 phrase 扩展，prefix 始终按原前缀查询。短语自左向右选择最长源规则；每处匹配保留原文路径及所有替代路径，
  多处规则独立组合。每条路径仍要求词序连续，不跨布尔分支或字段拼接。
- 词典只在 JVM 内存中；重新打开索引时为空，更新词典不写入 Lucene、不需要重建索引，并可与检索并行。

### 查询规则

- 索引与查询均使用 StandardAnalyzer，保留停用词，分析行为一致。
- 评分为 Lucene 默认 BM25；标题子句加权 3，正文加权 1。
- 排序：分数降序，同分按 id 升序。
- 字段限定、AND/OR 和标题权重在词典扩展后保持不变。
- 空文本、空子句、null 查询等非法结构抛出 QueryException。

### 命中范围

HitRange(field, start, end) 为原字段文本的 UTF-16 左闭右开偏移，通过对存储文本
重新分析（与索引同一 Analyzer）并匹配查询树得到，不做字符串替换猜测。短语只标出
连续匹配片段，替代词组标出原文中完整、连续的实际命中片段；不标记未成立的布尔分支。范围去重并按字段与位置排序。

## 资源生命周期

- ManualIndex 实现 AutoCloseable；close() 会收回所有未关闭会话并释放
  writer / searcher / 目录资源。已提交批次在关闭重开后仍然保留。
- SearchSession 固定创建时的索引快照与词典快照：翻页期间的新增、替换、删除或词典替换不会导致
  结果重复、遗漏或正文漂移；新会话可见最新提交。会话用毕必须 close()，
  关闭后继续使用抛出 IllegalStateException。
- 已打开会话的后续页和高亮继续使用创建时的查询编译结果；更新词典后，只有新会话采用新词典。
- 写入与查询可在单进程内并行；applyBatch 串行提交，读者始终看到最近一次提交。
- 写入失败会回滚未提交批次并重建 writer，失败批次中的新增/替换/删除不会被后续成功提交夹带；
  Add 也按 id 去重，重复 id 只保留批次内最后一份文档。

## 构建、测试与运行

```bash
mvn test
mvn package
mvn exec:java -Dexec.mainClass=com.example.manualsdk.demo.DemoMain
```

示例演示：批量写入 → 分页会话（翻页中执行替换/删除/新增）→ 新会话看到最新提交 →
词典替换前后查询差异 → 关闭重开后索引保留且内存词典为空。
