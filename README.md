# Code Completion Service (Java)

面向代码编辑器的**补全与智能提示服务**。编辑器把文档内容与光标位置提交给服务，
服务返回候选列表，每一项都带有：

- 分类（局部变量 / 方法参数 / 成员字段 / 方法 / 类型 / 关键字）；
- 全部来源（声明位置、所属容器，含被遮蔽的同名声明）；
- 排序依据（总分、逐项打分明细 `scoreBreakdown`、稳定的 `sortText`）。

整套链路只运行在**本进程与本地回环（127.0.0.1）**内：

- HTTP API 由内嵌 Tomcat 在随机本地端口（或 `--server.port` 指定）提供；
- “客户端”是 JDK 内置 `HttpClient` 实现的 `LocalEditorClient`，不依赖任何外部服务；
- 持久化只写本地文件（默认 `data/completion-service`）。

技术栈：Java 17、Spring Boot 3.4、无数据库、无外部语言服务器。

---

## 1. 快速开始

```bash
# 构建并运行全部自动化测试（51 个测试，离线可跑）
mvn -o test

# 启动 HTTP 服务（随机本地端口，仅监听 127.0.0.1）
mvn -o spring-boot:run

# 端到端本地冒烟演示：打开文档、重复/陈旧编辑、补全、关闭
mvn -o package -DskipTests
java -jar target/code-completion-service-java-1.0-SNAPSHOT.jar --demo
```

---

## 2. HTTP 接口（仅本地回环）

URI 中的文档标识在路径里使用 **Base64-URL 无填充编码**（`UriEncoding`），
因此 `file:///a/b.java`、`untitled:1` 这类含 `/`、`:` 的标识不会与 URL
路径规则冲突；客户端 `LocalEditorClient` 已封装该编码。

| 方法 | 路径 | 说明 |
|------|------|------|
| POST | `/api/documents/{encUri}/edits` | 文档同步：`OPEN` / `CHANGE` / `CLOSE` |
| POST | `/api/documents/completions` | 补全查询（可带 `timeoutMillis`） |
| GET  | `/api/documents/{encUri}/snapshot` | 当前已提交快照 |
| DELETE | `/api/documents/{encUri}` | 关闭文档 |
| POST | `/api/requests/{id}/cancel` | 取消在途请求 |

### 2.1 编辑（范围替换）

```json
{
  "version": 3,
  "editId": "e3-abc",
  "kind": "CHANGE",
  "range": { "start": {"line": 2, "character": 4},
             "end":   {"line": 2, "character": 9} },
  "text": "value"
}
```

- `line`、`character` 均为 **0 基**，范围为**半开区间**；
- `OPEN` 用 `fullText` 携带全文；
- `range` 为 `null` 的 `CHANGE` 表示全文替换。

返回：

```json
{ "status": "APPLIED", "appliedVersion": 3, "message": "applied" }
```

`status` 取值：`APPLIED`、`DUPLICATE`、`REJECTED_STALE`、
`REJECTED_VERSION_GAP`、`REJECTED_INVALID_RANGE`、`REJECTED_CLOSED`、`REJECTED_LIMIT`；
陈旧/跳号冲突对应 HTTP 409，非法范围/超大文档 400，已关闭 410，
跟踪文档数超过 `limits.max-documents` 为 429（关闭文档会腾位）。

### 2.2 补全

```json
{
  "uri": "file:///Demo.java",
  "position": {"line": 4, "character": 12},
  "triggerCharacter": ".",
  "desiredType": "String",
  "timeoutMillis": 500
}
```

响应（节选）：

```json
{
  "uri": "...",
  "documentVersion": 3,
  "prefix": "mes",
  "items": [
    {
      "label": "message",
      "kind": "LOCAL_VARIABLE",
      "detail": "String",
      "score": 104.9,
      "sortText": "0000000104.94|message|116",
      "sources": [ { "kind": "LOCAL_VARIABLE", "containerName": "",
                     "declarationOffset": 116, "declarationLine": 4 } ],
      "scoreBreakdown": [
        "category(LOCAL_VARIABLE)=90.0",
        "prefix(mes)=20.0",
        "typeMatch(String==String)=25.0",
        "scopeDistance(0)=-0.0",
        "recentlyEdited=14.9"
      ]
    }
  ],
  "truncated": false,
  "cacheHit": true,
  "status": "OK"
}
```

`status` 为 `OK` / `CANCELLED` / `TIMEOUT` / `UNKNOWN_DOCUMENT` / `ERROR`；
后三种终止态都返回**空列表与确定结论**，绝不返回半成品。

---

## 3. 文档同步语义（需求 1）

核心实现：`document.DocumentManager`、`document.TextDocument`、`document.LineIndex`。

1. **打开 / 增量 / 关闭**：`OPEN` 建立全文；`CHANGE` 是对最近一次已提交文本的
   范围替换；`CLOSE` 后不再接受变更。
2. **乱序**：到达顺序无关，只认版本号。`version < 当前版本` 一律视为陈旧：
   - 与已应用编辑**完全相同**（同版本 + 同 `editId`）→ `DUPLICATE`，幂等无副作用；
   - 不同编辑 → `REJECTED_STALE`，不会静默接受、不回退内容。
3. **重叠**：每次替换都作用于“当前已提交文本”，结果确定；同版本的第二次不同编辑
   被拒绝（`REJECTED_STALE`），从而避免 LSP 式基准偏移歧义。
4. **重复**：同一编辑重复送达严格幂等（基于有界的 `editId` 集合，容量 1024，FIFO 淘汰）。
5. **版本号跳号**：
   - `STRICT`（默认）：`v != current+1` 且不重复 → `REJECTED_VERSION_GAP`；
   - `LENIENT_GAP`：允许跳号，仍拒绝回退；
   - `ACCEPT_ALL`：仅拒绝不同编辑的同版本冲突（不推荐）。
6. **非法范围**（行/列越界、终点早于起点、超出文本长度）→
   `REJECTED_INVALID_RANGE`，状态完全不动。
7. **原子提交**：校验 + 应用 + 发布在每文档的写锁内一次完成；监听器（分析、缓存失效、
   持久化）在同一把写锁内同步执行。

---

## 4. 补全语义（需求 2）

实现：`analysis.Lexer` / `FaultTolerantParser` / `Scope`，
`completion.ScopeResolver` / `PrefixResolver`。

### 4.1 识别的声明类别

`LOCAL_VARIABLE`（含 `for` / `catch` / `try` 头中的变量、`var` 推断、
多变量声明 `int a, b;`）、`METHOD_PARAMETER`、`MEMBER_FIELD`、`METHOD`、
`TYPE`（class/interface/enum/record）、`KEYWORD`。

### 4.2 作用域与遮蔽

- 每个 `{}` 产生一个词法作用域，方法参数通过“参数作用域 → 方法体作用域”的
  `parameterOwner` 链接入方法体；
- 可见性：声明偏移 **严格小于** 光标（正在键入的声明名不算已声明）且光标位于该作用域内；
- 从最内层向外遍历，**最近作用域的同名声明胜出**；外层同名声明被遮蔽但仍保留在
  候选的 `sources` 里作为来源证据，不会产生重复候选项；
- 方法 `a()` 的局部变量在方法 `b()` 中不可见（不是上下文无关的全量符号倾倒）。

### 4.3 容错：未闭合块与语法错误

- 词法层：未闭合字符串/注释扫描到 EOF 也不中止；非法字符产出 token 而非抛错；
- 语法层：每个 `{` 入栈、每个 `}` 出栈；游离 `}` 被跳过并标记错误；
  EOF 时所有未闭合块在文本末尾**隐式闭合**；
- 结果始终是确定的最优努力（best-effort）作用域树，`hasSyntaxErrors=true`
  仅作标记——在任意光标位置都返回确定候选，绝不整段放弃。

### 4.4 成员访问与前缀

- 自动提取光标处标识符前缀；
- `receiver.foo^` 触发成员补全：接收者为局部/参数/字段时用其声明类型，
  `this.` 绑定到光标所在类型，大写简单名按类型引用处理；支持一层父类型成员；
- 前缀匹配：精确 / 大小写敏感前缀 / 忽略大小写前缀 / 驼峰缩写（camel-hump）。

---

## 5. 去重、排序与权重（需求 3）

实现：`completion.CandidateRanker`。

### 5.1 去重与来源

同名候选只保留一项（取最高分声明为代表），但 `sources` 保留全部贡献声明
（含遮蔽链），实现“按名称去重并保留来源”。

### 5.2 单项评分公式

```
score = 类别基础分
      + 前缀匹配加分
      + 类型匹配加分
      + 最近编辑加分（随时间线性衰减）
      - 作用域距离惩罚 × 跨越的作用域层数
```

| 组成 | 默认权重 | 配置键 |
|------|---------:|--------|
| 局部变量 | 90 | `completion.weights.local-variable` |
| 方法参数 | 85 | `completion.weights.method-parameter` |
| 成员字段 | 70 | `completion.weights.member-field` |
| 方法 | 60 | `completion.weights.method` |
| 类型 | 40 | `completion.weights.type` |
| 关键字 | 10 | `completion.weights.keyword` |
| 前缀完全相等 | 30 | `...prefix-exact` |
| 大小写敏感前缀 | 20 | `...prefix-case-sensitive` |
| 忽略大小写前缀 | 10 | `...prefix-case-insensitive` |
| 驼峰缩写 | 8 | `...prefix-camel-hump` |
| 类型匹配 | 25 | `...type-match` |
| 最近编辑（窗口起点满分） | 15 | `...recently-edited` |
| 最近编辑窗口 | 60000 ms | `...recently-edited-window-millis` |
| 每层作用域距离惩罚 | 4 | `...scope-distance-penalty` |

- **类型匹配**：当候选的声明类型等于上下文期望类型（请求中的 `desiredType`，
  例如赋值左侧、return 期望类型）时加 `type-match`；
- **最近编辑**：文档最后一次提交时间在窗口内时按
  `recentlyEdited × (1 - age / window)` 线性衰减，窗口外为 0；
- 关键字不参与作用域距离惩罚（固定距离 0），因此类别基础分次序稳定。

### 5.3 确定性（逐项一致）

总分相同时，比较键依次为：`分数降序 → 类别序号 → 名称 → 容器名 → 声明偏移`。
这些键全部来自固定输入，不依赖时间随机数、迭代顺序或线程调度。
`sortText` 是该次序的可复现字符串表示。相同文档 + 相同光标 + 相同配置下，
多次（以及跨重启重放）结果逐项一致（见 `RankingDeterminismTest`）。

候选项数受 `completion.max-candidates`（默认 128）截断，截断前先完成全量稳定排序。

---

## 6. 增量处理、并发与隔离（需求 4、5）

### 6.1 增量与全量等价

`analysis.IncrementalAnalyzer` 对受影响区域做判定：

- 同一文本/版本的幂等重放直接复用旧结果，零分配；
- 全文替换、跨结构边界或受影响字符占比超过
  `completion.incremental.full-rebuild-region-ratio`（默认 0.6）→ 全量重建；
- 否则按局部更新返回（`fullRebuild=false`）。

关键一致性手段：**无论增量还是全量，作用域树都只从“新文本本身”重新派生**，
绝不复用旧树偏移，因此增量结果与从头重建按构造必然一致；
`IncrementalAndCacheTest.incrementalResultEqualsFullRebuild` 以符号名+偏移+类别
的扁平序列逐项断言等价。

### 6.2 读已提交（read-committed）

- 每个文档一把 `ReentrantReadWriteLock`；编辑全程持写锁，查询持读锁取一个
  **不可变快照**（`DocumentSnapshot`），随后的分析/排序都基于该快照；
- 查询只能看到某个完整已提交版本，看不到半解析状态，也不会字段级混合
  （`ConcurrencyTest` 用正则 + 版本号一致性压测）；
- 查询在独立的有界线程池（`runtime.QueryExecutor`，默认 4 线程）中执行；
  **编辑在调用线程同步处理，不会排在慢查询后面**。

### 6.3 取消与超时

- 每个请求一个 `CancellationToken`，管线在各阶段之间协作检查；
- 取消后返回 `CANCELLED` + 空列表，**不写缓存、无任何副作用**；
- `Future.get(timeout)` 到期 → 中断任务 + 取消令牌，返回 `TIMEOUT` + 空列表，
  永远是确定结论而非半成品（`CancelTimeoutTest`、`TimeoutLoopbackTest`）。

### 6.4 资源有界

- 文档数上限 `completion.limits.max-documents`（默认 256）：关闭的文档在提交后淘汰；
- 单文档字符上限 `completion.limits.max-document-chars`（默认 1,000,000）；
- 缓存为有界 LRU（默认 512 条），工作线程数固定（默认 4）；
- 在途请求注册表在结束时立即移除，规模不随运行时间增长。

---

## 7. 跨请求缓存（需求 6）

`completion.CompletionCache`：有界 `LinkedHashMap`（access-order LRU）。

- 键：`(uri, version, offset, configHash)`；
- 编辑提交后失效**旧版本**条目（`invalidateVersionOlderThan`），关闭则清空该文档；
- 缓存只不可变地存放已算好的结果，**从不就地更新某个键**，所以旧结果不可能覆盖新结果；
- 配置哈希（`ConfigurationHasher`）覆盖全部排序权重与策略，改配置不会命中旧权重结果；
- 命中时返回不可变拷贝；`cacheHit` 仅用于观测，**命中与否不改变对外结果**
  （`IncrementalAndCacheTest`、集成测试均有断言）。

---

## 8. 持久化与重启恢复（需求 7）

`persistence.DocumentStore / PersistenceService / RecoveryService`。

### 8.1 存储布局

```
<completion.persistence.dir>/<enc(uri)>/
    snapshot.dat   —— 最近一次全量快照
    journal.log    —— 快照之后的增量记录（追加）
```

- 记录帧：`<字节长度>:<CRC32>:<JSON 载荷>`；
- 快照写入采用 **临时文件 + 原子 rename（+ fsync）**；日志为逐记录追加 + force；
- 每 20 条增量压缩一次快照并清空日志；
- **提交边界即持久化边界**：监听器在文档写锁内同步落盘，编辑返回成功时记录已持久；
  未完成的处理不可能留下部分数据。

### 8.2 恢复

启动时按“最近快照 + 之后所有增量”顺序重放，范围替换逐条作用于累积文本；
末尾**截断/CRC 不符的半条记录直接丢弃**（模拟崩溃 torn write 的测试
`tornTrailingRecordIsDiscardedNotPartiallyApplied`）。
关闭文档会删除其目录（与内存语义一致：关闭后无状态可恢复）。
固定输入 + 固定配置重放同一批编辑，补全结果逐项可复现
（`RestartRecoveryIntegrationTest` 用两个 Spring 上下文验证）。

---

## 9. 全部配置项

前缀 `completion.`（见 `src/main/resources/application.properties` 与
`config.ServiceProperties`）：

| 键 | 默认值 | 含义 |
|----|--------|------|
| `max-candidates` | 128 | 每次返回候选上限 |
| `min-prefix-length` | 0 | 产生候选所需最小前缀长度 |
| `version-policy` | `STRICT` | 版本异常策略：`STRICT/LENIENT_GAP/ACCEPT_ALL` |
| `request-timeout-millis` | 2000 | 请求硬超时（0 关闭） |
| `cancellation-enabled` | true | 是否响应协作取消 |
| `weights.*` | 见第 5.2 节 | 各类别/前缀/类型/最近编辑/距离权重 |
| `incremental.enabled` | true | 是否启用区域增量分析 |
| `incremental.full-rebuild-region-ratio` | 0.6 | 超过该受影响比例则全量重建 |
| `cache.enabled` | true | 是否启用跨请求缓存 |
| `cache.max-entries` | 512 | LRU 容量上限 |
| `persistence.enabled` | true | 是否启用本地持久化 |
| `persistence.dir` | `data/completion-service` | 本地存储目录 |
| `persistence.fsync` | true | 快照/日志是否 force 落盘 |
| `limits.max-documents` | 256 | 跟踪文档数上限 |
| `limits.max-document-chars` | 1000000 | 单文档字符上限 |
| `limits.worker-threads` | 4 | 查询工作线程数 |

非 `completion.*` 配置：`server.address=127.0.0.1`（仅回环）、
`server.port=0`（随机端口）。

---

## 10. 一致性边界（明确不保证的事）

为避免过度承诺，下列边界是有意为之：

1. 语言支持是**完成导向的 Java 子集**，不是完整 Java 编译器：不做完整类型推导，
   超过一跳的链式调用（`a.b().c`）不推断接收者类型，此时退化为确定性的
   标识符前缀补全，而不是猜测错误类型；
2. 增量分析在结构上复用“全量重解析新文本”的等价结果，区域判定影响的是标记位
   与未来扩展，而不是改变输出（输出与全量完全一致）；
3. 持久化保证“记录整体存在或不存在”（原子 rename + 长度/CRC 帧），
   JDK 无法可移植地对目录项做 fsync，这一点不额外承诺；
4. 同版本号、不同 `editId` 的并发提交被视为冲突，后到者被拒绝
   （正确的客户端应当用单调版本 + 稳定 editId）；
5. `ACCEPT_ALL` 策略只用于兼容特殊发送方，默认 `STRICT` 才是推荐边界。

---

## 11. 自动化测试地图

| 测试类 | 覆盖场景 |
|--------|---------|
| `DocumentManagerSyncTest` | 打开/范围替换、乱序、重叠、重复幂等、版本回退、跳号（严格/宽松）、非法范围、关闭、多行 |
| `ParserScopeTest` | 各类声明识别、遮蔽、未闭合块、乱码 token、成员接收者类型、offset 往返 |
| `RankingDeterminismTest` | 稳定次序、去重、类别次序、来源保留、权重可配置、上限、前缀过滤 |
| `IncrementalAndCacheTest` | 增量≡全量、全文替换触发全量、缓存版本失效、LRU 有界、configHash |
| `ConcurrencyTest` | 并发编辑+查询下只读到完整版本、无字段级混合 |
| `PersistenceRecoveryTest` | 快照+增量回放、torn 记录丢弃、关闭清理、多文档、快照压缩覆盖日志 |
| `ResourceBoundTest` | 文档数上限（429 + 关闭腾位）、单文档大小上限 |
| `CancelTimeoutTest` | 预取消返回确定空结果、执行器超时终止态 |
| `LocalLoopbackIntegrationTest` | 真实 HTTP 回环：编辑→补全→关闭、幂等、陈旧 409、缓存透明、结果稳定 |
| `TimeoutLoopbackTest` | 大文档 1ms 超时返回 `TIMEOUT`+空列表 |
| `RestartRecoveryIntegrationTest` | 两个 Spring 上下文间的提交/恢复一致性 |

