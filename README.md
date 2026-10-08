# Content Model Service（内容建模与发布服务）

集中管理**内容类型（Content Type）的字段定义**与**条目（Entry）数据**，
提供按内容类型读写条目的 HTTP 接口，支持字段平滑演进、草稿/发布两阶段、
条目引用完整性、乐观并发控制与多语言回退。

- 运行：`mvn spring-boot:run`（默认随机端口，见 `application.properties`）
- 测试：`mvn test`（42 个用例：字段演化 / 草稿发布隔离 / 引用完整性 /
  并发冲突 / 语言回退 / HTTP 端到端）
- 实现为单体内存存储（`Repositories`），无外部依赖；领域逻辑全部在
  `service` 包内，不依赖 Spring，可直接单测。

---

## 1. 领域模型语义

### 1.1 内容类型 ContentType

| 属性 | 说明 |
| --- | --- |
| `id` / `displayName` | 类型标识 / 展示名 |
| `version` | 单调递增的类型版本，每次被接受的字段变更 +1 |
| `fields[]` | 有序的字段定义，同一类型内 `field.id` 唯一 |

### 1.2 字段 FieldDefinition 与标量类型

| 类型 | JSON 值形态 | 适用约束 |
| --- | --- | --- |
| `STRING` | 字符串 | `maxLength`、`allowedValues`（枚举范围） |
| `LOCALIZED_STRING` | `{ "语言标签": "译文" }` | 同 STRING |
| `INTEGER` | 整数 | `min` / `max` |
| `NUMBER` | 数值 | `min` / `max` |
| `BOOLEAN` | 布尔 | — |
| `DATE` | `yyyy-MM-dd` 字符串 | — |
| `REFERENCE` | 条目 id 字符串，或 id 字符串数组 | `referencedTypeId`（必填） |

其它字段属性：

- `required`：是否必填；
- `defaultValue`：字段级默认值（JSON 形态必须与字段类型相符，定义时校验）；
  带默认值的字段在语义上等同可选——旧条目读取时自动投影出默认值。

字段定义在创建/修改时按类型做完整校验，非法定义返回
`FIELD_INVALID_DEFINITION` 并带逐条原因（如 min > max、REFERENCE 缺少
`referencedTypeId`、默认值与类型不符、不相关的约束出现在错误的类型上）。

### 1.3 条目 Entry：草稿与发布

每个条目持有两份**独立版本化**的数据快照：

| 状态 | 字段 | 语义 |
| --- | --- | --- |
| 草稿 draft | `draftVersion`（从 1 开始）、`draft` | 唯一可变的工作副本 |
| 发布 published | `publishedVersion`（从 0 开始）、`published`、`publishedBaseDraftVersion` | 某次**确定草稿版本**的不可变快照 |

- 草稿修改对已发布读取方**完全不可见**：`GET /published` 永远读上一次
  成功发布的快照，即便草稿已推进到更高版本。
- 发布必须携带 `expectDraftVersion`，等于当前草稿版本才允许发布；
  草稿已被他人推进时返回 `STAGE_CONFLICT`（含期望/实际版本，可重试）。
- 发布是“先全部校验、后原子换快照”：
  1. 按当前类型定义做全量数据校验；
  2. 做发布态引用完整性校验；
  3. 全部通过才深拷贝草稿并替换已发布快照。
  
  任何一步失败都不改动旧发布版本、不推进 `publishedVersion`，
  **不存在半发布状态**；草稿保持原样供编辑者修正后重试。
- 取消发布（unpublish）只移除发布快照与引用索引，草稿保留。

---

## 2. 字段演进规则（旧条目永不被破坏）

所有读取都经过 **投影（ValueProjector）**：用**当前**类型定义解释快照。

| 演进操作 | 已有条目时 | 旧条目读取效果 |
| --- | --- | --- |
| 新增**可选**字段 | 允许 | 缺失值标记 `UNSET` |
| 新增**带默认值**字段（含必填） | 允许 | 缺失值投影为 `DEFAULT` + 默认值 |
| 新增**必填且无默认值**字段 | **拒绝** `FIELD_EVOLUTION_UNSAFE` | —— |
| 把已有可选字段改为必填且无默认值 | **拒绝** `FIELD_EVOLUTION_UNSAFE` | —— |
| 收紧/放宽 `min/max`、`maxLength`、`allowedValues` | 允许 | 旧数据不重写、仍可读；新约束在**下次写入/发布**时生效 |
| 删除字段 | 允许 | 投影中隐藏该字段；底层值保留（孤儿数据，不影响读取） |

投影中每个字段带状态：`PRESENT` / `FALLBACK`（语言回退）/
`DEFAULT`（由默认值补齐）/ `UNSET`（可选且无值无默认）。

---

## 3. 引用与完整性

- 发布时对所有 `REFERENCE` 字段检查：

| 情形 | 错误码 |
| --- | --- |
| 目标条目不存在 | `REFERENCE_TARGET_NOT_FOUND` |
| 目标存在但未发布 | `REFERENCE_TARGET_NOT_PUBLISHED` |
| 目标类型与 `referencedTypeId` 不符 | `FIELD_VALIDATION_FAILED` |

  三类原因在 `details` 中可区分；草稿可以引用未发布条目，仅发布时拦截。

- 删除或取消发布一个**被其它已发布条目引用**的条目：
  - `referenceStrategy=BLOCK`（默认）：拒绝，返回
    `REFERENCE_INTEGRITY_VIOLATION`，details 列出引用方 id 与可选策略；
  - `referenceStrategy=CASCADE_UNPUBLISH`：连同**传递闭包内**所有已发布
    引用方一起取消发布（删除场景下目标条目本身被删除，引用方降级为
    未发布）。
- 当条目发布了不再包含该引用的新版本后，旧引用关系从索引移除，
  此时目标条目可自由取消发布/删除。

并发安全：结构变更（发布/取消发布/删除）持有全局索引锁，且按条目 id
升序获取条目锁（带 5s 超时 → `STAGE_CONFLICT`），固定加锁顺序避免死锁。

---

## 4. 乐观并发控制

草稿更新必须携带 `expectVersion`（调用方最后一次看到的 `draftVersion`）：

- 相等：校验并写入，`draftVersion += 1`；
- 不等：拒绝 `DRAFT_VERSION_CONFLICT`（HTTP 409），`details` 含
  `expectedVersion`、`actualVersion` 与“重新读取后重试”的提示。

被拒绝的写入不会改动任何数据，后提交方可以重新读取最新草稿、合并改动后
带新版本号重试。发布同样用 `expectDraftVersion` 做版本钉住，语义一致
（冲突码为 `STAGE_CONFLICT`）。

---

## 5. 多语言与回退

`LOCALIZED_STRING` 读取参数：

- `lang`：期望语言；
- `fallback`：有序回退链（如 `fallback=zh-CN&fallback=en-US`）。

解析顺序：

1. 精确命中 `lang` → 状态 `PRESENT`，`sourceLang = lang`；
2. 依次命中回退链中的语言 → 状态 `FALLBACK`，`sourceLang` 为实际来源语言；
3. 都没有但存在其它译文 → 取确定性的（字典序首个）译文，仍标记 `FALLBACK`；
4. 完全无译文 → 走默认值（`DEFAULT`）或 `UNSET`。

调用方凭 `state` 区分原文与回退值，凭 `sourceLang` 判断值来自哪种语言。
不传 `lang` / `fallback` 时返回整个译文 map（`PRESENT`）。

---

## 6. HTTP 接口

### 内容类型

| 方法 & 路径 | 说明 |
| --- | --- |
| `POST /api/content-types` | 创建类型（含初始字段），201 |
| `GET /api/content-types` | 列出类型 |
| `GET /api/content-types/{typeId}` | 查看类型（含 version 与字段） |
| `POST /api/content-types/{typeId}/fields` | 新增字段（受演进规则约束） |
| `PUT /api/content-types/{typeId}/fields/{fieldId}` | 修改字段约束 |
| `DELETE /api/content-types/{typeId}/fields/{fieldId}` | 删除字段 |

```jsonc
// POST /api/content-types
{
  "id": "article",
  "displayName": "文章",
  "fields": [
    { "id": "title", "name": "标题", "type": "STRING",
      "required": true, "maxLength": 120 },
    { "id": "author", "name": "作者", "type": "REFERENCE",
      "referencedTypeId": "author" }
  ]
}
```

### 条目

| 方法 & 路径 | 说明 |
| --- | --- |
| `POST /api/entries` | 创建条目（草稿 v1），201 |
| `GET /api/entries/{id}/draft?lang=&fallback=` | 读草稿投影 |
| `GET /api/entries/{id}/published?lang=&fallback=` | 读发布投影（未发布 409） |
| `PUT /api/entries/{id}/draft` | 乐观更新草稿（必传 `expectVersion`） |
| `POST /api/entries/{id}/publish` | 钉版本发布（必传 `expectDraftVersion`） |
| `POST /api/entries/{id}/unpublish` | 取消发布（body 可带 `referenceStrategy`） |
| `DELETE /api/entries/{id}` | 删除（body 可带 `referenceStrategy`），204 |

```jsonc
// PUT /api/entries/{id}/draft
{ "expectVersion": 3, "data": { "title": "新标题", "author": "entry-uuid" } }

// POST /api/entries/{id}/publish
{ "expectDraftVersion": 4 }

// POST /api/entries/{id}/unpublish | DELETE /api/entries/{id}
{ "referenceStrategy": "CASCADE_UNPUBLISH" }   // 或 "BLOCK"（默认）
```

读响应示例（字段值带状态与来源语言）：

```json
{
  "id": "...", "typeId": "article", "typeVersion": 3, "stage": "published",
  "draftVersion": 4, "publishedVersion": 2, "baseDraftVersion": 4,
  "requestedLanguage": "fr-FR", "fallbackLanguages": ["zh-CN", "en-US"],
  "fields": {
    "title":   { "value": "你好", "state": "FALLBACK", "sourceLang": "zh-CN" },
    "author":  { "value": "...", "state": "PRESENT", "sourceLang": null },
    "subtitle":{ "value": null,   "state": "UNSET",   "sourceLang": null }
  }
}
```

错误响应统一为：

```json
{ "code": "DRAFT_VERSION_CONFLICT", "message": "...", "details": ["..."] }
```

---

## 7. 错误码表

| 错误码 | HTTP | 触发场景与可重试性 |
| --- | --- | --- |
| `TYPE_NOT_FOUND` | 404 | 内容类型不存在 |
| `TYPE_ALREADY_EXISTS` | 409 | 创建已存在的类型 |
| `FIELD_DUPLICATE_ID` | 422 | 同类型内字段 id 重复（创建失败不留半成品） |
| `FIELD_INVALID_DEFINITION` | 422 | 字段定义本身非法，details 逐条说明 |
| `FIELD_NOT_FOUND` | 404 | 修改/删除不存在的字段 |
| `FIELD_EVOLUTION_UNSAFE` | 422 | 不安全演进（新增/改为必填无默认值），details 给出可选修复方式 |
| `ENTRY_NOT_FOUND` | 404 | 条目不存在 |
| `ENTRY_ALREADY_EXISTS` | 409 | 预留：条目 id 冲突 |
| `ENTRY_NOT_PUBLISHED` | 409 | 读取/取消发布一个没有发布版本的条目 |
| `FIELD_VALIDATION_FAILED` | 422 | 数据不符合当前字段约束（含引用目标类型错误） |
| `STAGE_CONFLICT` | 409 | 发布钉住的草稿版本已过期，或锁竞争；重新读草稿后可重试 |
| `DRAFT_VERSION_CONFLICT` | 409 | 乐观锁冲突；details 含 expected/actual 版本，合并后可重试 |
| `REFERENCE_TARGET_NOT_FOUND` | 422 | 发布时引用了不存在的条目 |
| `REFERENCE_TARGET_NOT_PUBLISHED` | 422 | 发布时引用了存在但未发布的条目 |
| `REFERENCE_INTEGRITY_VIOLATION` | 409 | 取消发布/删除被引用的已发布条目（BLOCK 策略） |
| `BAD_REQUEST` | 400 | 请求体形态错误、缺少版本号、未知策略等 |
| `INTERNAL_ERROR` | 500 | 预留：未预期错误 |

---

## 8. 代码结构

```
model/      FieldType / FieldDefinition / ContentType / Entry /
            ResolvedValue / EntryView
error/      ErrorCode / ContentModelException / ErrorResponse / GlobalExceptionHandler
dto/        ContentType* / FieldDefinitionRequest / Entry* / Publish* 请求与响应记录
service/    Repositories（内存存储+条目锁）
            ContentTypeService（类型与演进规则）
            ValueValidator（定义/数据校验、引用提取）
            ValueProjector（默认值补齐 + 多语言回退投影）
            EntryService（草稿、发布、并发、引用完整性、级联）
web/        ContentTypeController / EntryController
```
