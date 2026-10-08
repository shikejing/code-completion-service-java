# 配置项

所有配置都以 `completion.` 为前缀，可通过 `application.properties`、Spring
环境变量或直接构造 `CompletionProperties` 设置。固定输入 + 固定配置下，补全
结果逐项一致（包括顺序与分数）。

| 键 | 默认值 | 含义 |
|---|---|---|
| `completion.type-match-weight` | `10.0` | 符号推断类型与光标处期望类型一致时的加分 |
| `completion.recency-weight` | `5.0` | 最近编辑窗口内符号的最大加分（随批次距离线性衰减） |
| `completion.recency-window-edits` | `10` | “最近编辑”保留的编辑批次数，超出后最旧批次丢弃（有界） |
| `completion.local-variable-base` | `8.0` | 局部变量基础分 |
| `completion.parameter-base` | `7.0` | 方法参数基础分 |
| `completion.field-base` | `6.0` | 成员字段基础分 |
| `completion.method-base` | `5.0` | 方法基础分 |
| `completion.type-base` | `4.0` | 类型（类/接口/枚举/记录）基础分 |
| `completion.keyword-base` | `1.0` | 关键字基础分 |
| `completion.prefix-char-weight` | `0.2` | （保留项）前缀质量权重占位，当前排序仅做前缀过滤 |
| `completion.proximity-weight` | `0.5` | 声明作用域与光标作用域每相差一层的加分 |
| `completion.stale-policy` | `REJECT` | 陈旧编辑（baseVersion < 当前版本）策略：`REJECT` 拒绝；`REBASE` 平移偏移后应用 |
| `completion.out-of-order-policy` | `BUFFER` | 未来编辑（baseVersion > 当前版本）策略：`BUFFER` 缓存并在缺口补齐后按序提交；`REJECT` 立即拒绝 |
| `completion.max-buffered-changes` | `100` | 每个文档缓存未来编辑的上限，超出返回 `REJECTED_FUTURE_LIMIT` |
| `completion.max-open-documents` | `1000` | 同时打开文档上限，按 LRU 关闭最久未使用的文档 |
| `completion.completion-cache-size` | `256` | 每个文档补全缓存条目上限（LRU） |
| `completion.query-timeout-ms` | `1000` | 单次补全请求超时（毫秒），超时返回确定的 `TIMEOUT` |
| `completion.query-workers` | `4` | 补全查询专用线程池大小（编辑处理不使用该池） |
| `completion.data-dir` | （空） | 持久化快照目录；为空表示禁用重启恢复 |

服务仅绑定本地回环：`server.address=127.0.0.1`，端口 `server.port=0` 表示
随机空闲端口，保证本地可重复启动。
