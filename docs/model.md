# 内容建模与发布服务 — 模型语义与接口说明

集中管理内容类型（ContentType）的字段定义与内容条目（Entry）的数据，按内容类型对外提供
条目读写接口。实现为内存存储的 Spring Boot 服务，线程安全；重启后数据重置。

- 包根：`com.github.cuixudong74.contentmodeljava`
- 领域模型：`domain`
- 校验 / 投影 / 引用完整性 / 业务服务：`service`
- 存储：`store`
- HTTP 接口与 DTO：`web`

---

## 1. 数据模型

### 1.1 内容类型 ContentType

| 属性 | 说明 |
| --- | --- |
| `id` | 类型标识，匹配 `[A-Za-z][A-Za-z0-9_-]*` |
| `displayName` | 展示名，缺省取 id |
| `version` | 模式版本，初始为 1，每次被接受的演化为单调递增 +1 |
| `fields` | 有序字段集合，同类型内字段 key 唯一 |

### 1.2 字段定义 FieldDefinition

| 属性 | 适用类型 | 说明 |
| --- | --- | --- |
| `key` | 全部 | 字段标识，匹配 `[A-Za-z][A-Za-z0-9_]*`，同类型内唯一 |
| `type` | 全部 | `STRING` / `INTEGER` / `NUMBER` / `BOOLEAN` / `DATETIME` / `REFERENCE` |
| `required` | 全部 | 仅在**发布**时强制；草稿允许缺省 |
| `localized` | `STRING` | 是否为多语言字段；值为 `{locale: text}` 的翻译表 |
| `defaultValue` | 全部 | 演化后读取旧条目、缺省值与空翻译时的回填来源 |
| `minLength` / `maxLength` | `STRING` | 长度闭区间，`minLength <= maxLength` |
| `pattern` | `STRING` | Java 正则，创建/演化为定义时会编译校验 |
| `min` / `max` | `INTEGER` / `NUMBER` | 数值闭区间，`min <= max` |
| `refType` | `REFERENCE` | 被引用条目必须属于该内容类型，必填 |
| `onDeletePolicy` | `REFERENCE` | `RESTRICT`（默认）/ `CASCADE` |

标量与 JSON 形状对应：`STRING→string`、`INTEGER→整数`、`NUMBER→数字`、
`BOOLEAN→boolean`、`DATETIME→ISO-8601 字符串`（如 `2026-10-07T10:00:00Z`）、
`REFERENCE→被引用条目 id 字符串`。定义级非法组合（负长度、`min>max`、非法正则、
REFERENCE 缺 `refType`、默认值与类型不符等）一律在创建/演化为定义时拒绝。

### 1.3 条目 Entry 与两阶段

每个条目同时维护两份互不影响的数据：

| 数据 | 版本 | 说明 |
| --- | --- | --- |
| 草稿 `draftValues` | `draftVersion`（新建即 1，每次整单替换 +1） | 编辑工作区 |
| 发布快照 `publishedValues` | `publishedVersion`（发布时捕获当时的草稿版本；未发布或缺省为缺省态） | 已发布读取方唯一可见的数据 |

条目还记录 `schemaVersionAtDraft` / `schemaVersionAtPublished`，表示数据写入时所依据的
内容类型版本。

### 1.4 读取投影与字段状态

读取永远以**当前模式**对存储的原始值做只读投影，绝不因旧数据而失败：

| state | 含义 |
| --- | --- |
| `PRESENT` | 存储值存在且满足当前约束 |
| `DEFAULTED` | 存储值缺失，由字段 `defaultValue` 回填 |
| `UNSET` | 缺失且无默认值；或旧值不再满足收紧后的约束（此时不暴露非法旧值） |

多语言字段的投影为 `{state, value, locale, fallback}`，见第 4 节。

---

## 2. 模式演化规则

演化通过 `PUT /api/content-types/{id}/evolve` 提交“新增或变更的字段”。所有检查先于任何
修改执行；被拒绝时内容类型与既有条目均不变，版本不递增。

允许的平滑演为（保证旧条目不被破坏）：

1. **新增可选字段**：旧条目读取时该字段为 `UNSET`。
2. **新增带默认值的字段**：旧条目读取时为 `DEFAULTED` 并给出默认值；新增必填字段时
   **必须**提供默认值，否则拒绝。
3. **调整校验范围**：放宽或收紧 `min/max`、长度、正则等。旧条目中不再合法的值读取时投影为
   `UNSET`，而不会导致读取失败或篡改存储；草稿再次保存时会按当前约束校验。
4. **已有字段改为必填**：必须同时提供 `defaultValue`，否则拒绝。

不支持的破坏性演化（返回 `FIELD_EVOLUTION_UNSUPPORTED`）：

- 改变字段 `type`；
- 改变字段 `localized` 标志；
- 改变 `REFERENCE` 字段的 `refType`；
- 新增无默认值的必填字段、把已有字段改必填且无默认值。

字段不允许删除（需求仅要求增字段与调整校验范围）；请求中未包含的已有字段原样保留。

---

## 3. 草稿 / 发布与乐观并发

### 3.1 草稿编辑隔离

- 草稿为整单替换：`PUT /api/content-types/{typeId}/entries/{entryId}/draft`。
- 草稿阶段只校验值形状（类型、长度/范围、正则、引用形状），**不校验必填、不校验引用目标**，
  允许半成品；未知字段一律拒绝。
- 已发布读取方只能看到发布快照，草稿的后续修改不可见。

### 3.2 确定版本发布、无半发布

- `POST .../{entryId}/publish`，请求体 `{ "expectedDraftVersion": N }`（首次发布可省略）。
- 发布在条目级互斥区内**先全部校验、后一次写入快照**：
  1. 草稿版本必须等于 `expectedDraftVersion`（版本不符 → `VERSION_CONFLICT`）；
  2. 所有必填字段必须有当前合法值；
  3. 所有引用必须存在、已发布且类型匹配（见第 5 节）。
- 任何一步失败，发布快照保持上一个已发布版本不变，不存在半发布状态。
- 成功后 `publishedVersion` 固化为被发布的草稿版本。

### 3.3 乐观并发控制

- 草稿更新必须携带 `expectedVersion`（当前读到的 `draftVersion`）。
- 服务端当前版本不同即拒绝（`VERSION_CONFLICT`, HTTP 409），不覆盖先到提交。
- 冲突详情可解释、可重试：

```json
{
  "code": "VERSION_CONFLICT",
  "message": "draft version 1 is stale; current version is 2",
  "details": {
    "entryId": "entry-7",
    "currentVersion": 2,
    "suppliedVersion": 1,
    "retry": "re-read the draft, merge your changes and resubmit with expectedVersion=2"
  }
}
```

- 发布接口的 `expectedDraftVersion` 语义相同，防止“以为发布的是 N，实际发布了 N+1”。

---

## 4. 多语言与回退

- 仅 `STRING` 字段可声明 `localized=true`，存储值为翻译表，如
  `{ "zh-CN": "你好", "en": "hello" }`。
- 读取参数：`locale` 为期望语言，`fallback` 为**有序**回退语言链（逗号分隔，如
  `fallback=zh-CN,en`）。解析顺序：`locale` → fallback 链按顺序首个命中的合法翻译。
- 结果可区分原文与回退：

```json
{ "state": "PRESENT", "value": "hello", "locale": "en", "fallback": true }
```

- 命中 `locale` 原文：`fallback=false` 且 `locale=期望语言`；命中回退链：`fallback=true`
  且 `locale` 为实际命中的语言；链上均无翻译且字段无默认值：`UNSET`；有默认值：
  `DEFAULTED`。草稿与发布读取均支持。

---

## 5. 条目引用与完整性

### 5.1 发布时引用校验

对每个 `REFERENCE` 字段（值非空时），要求目标：

1. 存在，否则 `REFERENCE_TARGET_NOT_FOUND`；
2. 已发布，否则 `REFERENCE_TARGET_NOT_PUBLISHED`；
3. 类型等于字段 `refType`，否则 `VALIDATION_FAILED`（`REFERENCE_TYPE_MISMATCH`）；
4. 不允许引用自身（`SELF_REFERENCE`）。

所有违规字段一次性收集到 `details` 数组（含 `field` / `reason` / `targetEntryId` /
`message`）。顶层错误码可区分：同时存在缺失与未发布时以**缺失**优先返回。
草稿中的引用不拦截（引用未发布/不存在条目可先存草稿），发布时再统一校验。

### 5.2 删除 / 取消发布策略

仅基于**已发布引用图**执行（草稿引用不阻塞）：

- `RESTRICT`（默认）：仍有已发布引用指向目标时，取消发布与删除均被拒绝
  （`REFERENCE_INTEGRITY_VIOLATION`），`details` 给出引用方条目与字段；
- `CASCADE`：
  - 取消发布目标 → 级联取消发布整条引用链上 CASCADE 的引用方（草稿保留）；
  - 删除目标 → 级联删除整条引用链上 CASCADE 的引用方。
- 引用链传递生效；链式处理中遇到的 RESTRICT 节点仍会在顶层操作时被拦截。
- `DELETE /entries/{id}` 同时删除草稿与发布快照；仅在目标已发布且存在 RESTRICT
  已发布引用时拦截。

---

## 6. HTTP 接口

| 方法与路径 | 说明 |
| --- | --- |
| `POST /api/content-types` | 创建内容类型（body: `{id, displayName, fields[]}`） |
| `GET /api/content-types/{id}` | 查询内容类型（含 `version`） |
| `GET /api/content-types` | 列出全部类型 |
| `PUT /api/content-types/{id}/evolve` | 平滑演化字段 |
| `POST /api/content-types/{typeId}/entries` | 创建条目（草稿版本置 1） |
| `GET /api/content-types/{typeId}/entries?stage=DRAFT|PUBLISHED&locale=&fallback=` | 列表；PUBLISHED 阶段隐藏未发布条目 |
| `GET /api/content-types/{typeId}/entries/{entryId}?stage=DRAFT|PUBLISHED&locale=&fallback=` | 读取（默认 DRAFT） |
| `PUT /api/content-types/{typeId}/entries/{entryId}/draft` | 整单替换草稿，需 `expectedVersion` |
| `POST /api/content-types/{typeId}/entries/{entryId}/publish` | 基于确定草稿版本发布 |
| `DELETE /api/content-types/{typeId}/entries/{entryId}/publish` | 取消发布（应用引用策略） |
| `DELETE /api/content-types/{typeId}/entries/{entryId}` | 删除（应用引用策略） |

错误信封统一为：

```json
{ "code": "ERROR_CODE", "message": "human readable", "details": { } }
```

---

## 7. 错误码

| code | HTTP | 触发场景 |
| --- | --- | --- |
| `TYPE_NOT_FOUND` | 404 | 内容类型不存在（条目路径上类型不存在同样适用） |
| `TYPE_ALREADY_EXISTS` | 409 | 创建已存在的类型 id |
| `FIELD_DUPLICATE` | 422 | 同一类型定义中字段 key 重复 |
| `FIELD_INVALID_DEFINITION` | 422 | 单字段定义非法；`details` 为错误信息列表 |
| `FIELD_EVOLUTION_UNSUPPORTED` | 422 | 破坏性演化或新增无默认值必填字段 |
| `ENTRY_NOT_FOUND` | 404 | 条目不存在，或不属于路径中的类型 |
| `ENTRY_NOT_PUBLISHED` | 409 | 读取/取消发布一个没有发布快照的条目 |
| `VERSION_CONFLICT` | 409 | 乐观锁版本不符或更新未带 `expectedVersion` |
| `VALIDATION_FAILED` | 422 | 草稿值形状非法、发布时必填缺失、引用类型不符/自引用、参数非法 |
| `REFERENCE_TARGET_NOT_FOUND` | 422 | 发布时引用指向不存在的条目 |
| `REFERENCE_TARGET_NOT_PUBLISHED` | 422 | 发布时引用指向未发布的条目 |
| `REFERENCE_INTEGRITY_VIOLATION` | 422 | RESTRICT 策略拦截删除/取消发布 |

---

## 8. 并发与一致性说明

- 条目变更（草稿替换、发布、取消发布、删除）在每个条目稳定的锁对象上串行执行，
  乐观锁的“比较—写入”是原子操作；锁对象在删除后保留，避免 id 复用到新锁。
- 内容类型创建/演化为结构性操作，在存储级锁内串行；读取投影在类型对象监视器下取一致的
  字段快照。
- 发布失败不触碰发布快照；取消发布/删除在任何状态修改前先完成引用策略判定。
- 存储为进程内 `ConcurrentHashMap`，适用于单实例演示与测试；多实例部署需要替换为
  带事务与外键约束的持久化存储，但错误码与版本语义保持不变。

---

## 9. 测试

位于 `src/test/.../tests`，共 30+ 用例（`mvn test`）：

- `FieldEvolutionTest`：字段校验、重复 key、新增可选/默认字段、收紧范围的旧数据投影；
- `DraftPublishIsolationTest`：草稿不可见性、确定版本发布、失败无半发布、取消发布语义；
- `ReferenceIntegrityTest`：不存在/未发布可区分、类型不符、RESTRICT 拦截、CASCADE 级联；
- `ConcurrencyConflictTest`：冲突详情可重试、12 编辑者并发仅 1 成功；
- `LocalizationFallbackTest`：原文/回退区分、回退顺序、全缺失 UNSET、默认值、发布读取；
- `WebApiTest`：HTTP 状态码、错误信封与完整发布读取链路。
