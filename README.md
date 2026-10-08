# Code Completion Service (Java, process-local)

面向代码编辑器的补全与智能提示服务。编辑器把文档内容与光标位置提交给
服务，服务返回候选列表以及每项的来源与排序依据。整条链路只在本进程与本地
回环（`127.0.0.1`）内运行，用内存数据和本地文件模拟客户端，不依赖任何外部
服务。

- 语言/框架：Java 17、Spring Boot 3.4（仅用其内嵌 HTTP 与配置绑定）。
- 分析对象：一个容错的小型 Java 风格语言（类/接口/枚举/记录、字段、方法、
  参数、局部变量、块与控制语句）。
- 端口：随机空闲本地端口（`server.port=0`），仅绑定回环。

## 构建与验证

```bash
mvn test                 # 全部 40+ 个自动化测试
mvn spring-boot:run      # 启动 HTTP 服务（127.0.0.1，随机端口）
mvn exec:java \
  -Dexec.mainClass=com.github.shikejing.codecompletionservicejava.demo.LocalLoopbackDemo
```

## 模块结构

```
model/        LSP 风格请求/响应、版本与策略枚举、不可变记录
document/     文档快照、范围替换编辑、版本/乱序/陈旧/重复仲裁（每文档锁）
parse/        LineIndex、容错词法/语法、增量分析（窗口重词法 + 区域重解析）
analysis/     作用域树、符号、补全引擎（遮蔽/成员/期望类型/排序）、recent
cache/        版本门控、有界 LRU 的补全缓存
runtime/      有界查询线程池、可取消任务、超时、确定性延迟钩子
persistence/  原子快照存储（临时文件 + 原子移动）
service/      进程内门面 CompletionService 与装配工厂 ServiceFactory
web/          回环 HTTP 控制器与 Spring Bean
demo/         内存“编辑器”直连服务的可运行演示
```

## HTTP 接口（均在 /api/documents 下，POST JSON）

| 路径 | 作用 |
|---|---|
| `/open` | 打开文档（uri、text、version） |
| `/change` | 增量（范围替换，可带多个不相交编辑）或全文同步 |
| `/close` | 关闭并清理派生状态/缓存/快照 |
| `/completion` | 返回 `CompletionResponse`（终态状态 + 有序候选） |

变更结果 `ChangeOutcome.status`：`APPLIED`、`DUPLICATE`、`REBASED`、
`BUFFERED`、`REJECTED_STALE`、`REJECTED_OVERLAP`、`REJECTED_FUTURE_LIMIT`、
`CLOSED`。补全终态 `CompletionStatus`：`OK`、`CANCELLED`、`TIMEOUT`、
`CLOSED`（非 OK 必为空候选，绝不返回半成品）。

## 文档

- [补全语义与排序权重](docs/COMPLETION_SEMANTICS.md)
- [一致性、并发与持久化边界](docs/CONSISTENCY.md)
- [全部配置项](docs/CONFIGURATION.md)

## 测试覆盖

- `DocumentSyncTest`：顺序/重复幂等/版本回退/乱序缓冲/跳号/重叠/REBASE/全文
- `CompletionSemanticsTest`：分类、遮蔽、成员补全、关键字、前缀过滤、去重、
  语法错误与未闭合块、recent 加权、确定性
- `IncrementalAnalysisTest`：增量与全量逐项等价（含花括号变化回退、多点）
- `CacheInvalidationTest`：失效、版本门控、旧结果不覆盖新结果、有界
- `CancellationTimeoutTest`：确定性超时、取消无副作用、慢查询不阻塞编辑
- `ConcurrencyTest`：并发编辑/查询只读完整提交版本、无字段级混合、LRU 上限
- `PersistenceRecoveryTest`：重启恢复最后提交、崩溃不留半成品、重放逐项一致
- `HttpLoopbackIntegrationTest`：回环 HTTP 端到端
