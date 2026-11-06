
# M02 — 产品、套餐与价格版本管理

状态：M02-05 实现完成；M02 定向 JUnit：44/44 通过。Apifox 未执行。

## M02-01 产品基础模型

### 01 需求分析

目标：
- 建立平台产品模板
- 产品编码全局唯一
- 创建产品默认 DRAFT
- 平台管理员才能管理产品
- 支持分页查询

暂不实现套餐、价格、订阅和产品发布。

### 02 技术设计

数据库：
- V4__product_schema.sql
- V5__product_foundation_fix.sql

持久化：
- MyBatis-Plus Generator
- Product Entity
- ProductMapper
- ProductMapper.xml

权限：
- 平台管理员与租户 OWNER 分离
- mb_product 不包含 tenant_id
- 普通租户成员不能管理平台产品

### 03 开发实现

- [ ] ProductStatus
- [ ] CreateProductRequest
- [ ] ProductVO
- [ ] PageResponse
- [ ] PlatformAdminGuard
- [ ] ProductService
- [ ] ProductServiceImpl
- [ ] ProductController
- [ ] ProductIntegrationTest

### 04 功能与自动化测试

- [ ] 产品创建成功
- [ ] 默认状态 DRAFT
- [ ] 重复编码返回 409
- [ ] 非法参数返回 400
- [ ] 未认证访问返回 401
- [ ] 普通租户 OWNER 返回 403
- [ ] 分页查询正确
- [ ] JUnit 集成测试通过
- [ ] Apifox 自动化测试通过
- [ ] Maven 全量回归通过

### 05 并发与故障测试

当前：
- 数据库唯一索引避免重复产品编码
- Service 通过事务管理写入
- 权限查询失败时不能放行
- 分页限制单次查询数据量

后续：
- 并发创建相同产品编码
- 数据库故障恢复
- 产品发布状态一致性
- 产品数据规模增长后的查询性能

### 06 验收总结

状态：待验收

Maven：待填写
Apifox：待填写
数据库检查：待填写
Git Commit：待填写
遗留问题：待填写

下一阶段：M02-02 套餐模型


---

# M02-02 — 套餐 Plan 模型

状态：开发中

## 01 需求分析

目标：
- 产品与套餐一对多
- 套餐编码在产品内唯一
- 创建默认 DRAFT
- 支持分页与详情
- 支持草稿信息修改
- 防止并发修改覆盖
- 仅平台管理员可以管理

本轮不包含：
- 价格及价格版本
- 套餐发布
- 租户订阅
- 真实计费规则

## 02 技术设计

数据库：
- V6__plan_schema.sql
- mb_plan
- product_id 外键
- (product_id, plan_code) 唯一约束
- status CHECK 约束
- version 乐观并发控制

持久层：
- PersistenceCodeGenerator
- Plan Entity
- PlanMapper
- PlanMapper.xml

状态：
- DRAFT
- ACTIVE
- RETIRED

本轮只允许创建 DRAFT。

## 03 开发实现

- [ ] V6 数据库迁移
- [ ] Generator 自动生成持久层
- [ ] PlanStatus
- [ ] CreatePlanRequest
- [ ] UpdatePlanRequest
- [ ] PlanVO
- [ ] PlanService
- [ ] PlanController
- [ ] PlanIntegrationTest

## 04 功能和自动化测试

- [ ] 创建套餐成功
- [ ] 默认状态 DRAFT
- [ ] 同产品重复编码返回 409
- [ ] 不同产品可使用相同套餐编码
- [ ] 不存在的产品返回 404
- [ ] 普通租户 OWNER 返回 403
- [ ] 未认证访问返回 401
- [ ] 参数校验返回 400
- [ ] 套餐分页和详情正确
- [ ] 草稿修改成功
- [ ] 过期 version 返回 409
- [ ] 已发布套餐不能修改
- [ ] 已归档产品不能新增套餐
- [ ] Testcontainers 测试通过
- [ ] Apifox 自动化通过
- [ ] Maven 全量回归通过

## 05 并发与故障测试

已实现：
- MySQL 唯一索引
- 产品外键约束
- 更新版本号 CAS
- 状态条件更新
- 平台级权限保护

后续：
- 产品状态与套餐创建的并发竞争
- 套餐发布一致性
- 价格版本不可变规则
- 高并发查询与分页性能

## 06 验收总结

M02-02：待验收

JUnit：待填写
Apifox：待填写
Flyway：待填写
Git Commit：待填写
遗留问题：待填写

下一阶段：M02-03 价格版本管理

---

# M02-03 — 价格版本管理

状态：开发中

## 01 需求分析

目标：
- 套餐支持多币种、多计费周期
- 价格金额使用最小货币单位
- 每条价格拥有独立版本序列
- 支持草稿修改与版本发布
- 支持指定未来时间生效
- 已发布价格不可修改
- 历史价格能够按时间重现
- 敏感价格变更有审计记录

本轮不包含：
- 阶梯计价
- 用量计费
- 租户订阅价格锁定
- 产品与套餐对外发布

## 02 技术设计

数据库：
- V7__price_version_schema.sql
- mb_price
- mb_price_version

价格维度：
- plan_id
- currency
- billing_period

价格版本：
- price_id
- version_no
- base_amount_minor
- status
- effective_at
- revision

核心约束：
- 同套餐、币种、周期的价格定义唯一
- 版本号唯一
- 金额非负
- 已发布版本必须包含生效时间
- 生效时间按版本严格递增
- 同一价格同时最多一个草稿
- 已发布价格不可修改

并发策略：
- SELECT FOR UPDATE 锁定价格定义行
- 锁定读获取最新版本
- revision 条件更新
- 数据库唯一约束最终兜底

## 03 开发实现

持久层：
- [ ] Flyway V7 迁移
- [ ] Generator 生成 Price Entity、Mapper、XML
- [ ] Generator 生成 PriceVersion Entity、Mapper、XML

业务：
- [ ] BillingPeriod
- [ ] PriceVersionStatus
- [ ] CreatePriceRequest
- [ ] CreatePriceVersionRequest
- [ ] UpdatePriceVersionRequest
- [ ] PublishPriceVersionRequest
- [ ] PriceVO
- [ ] PriceVersionVO
- [ ] PriceService
- [ ] PriceController
- [ ] 价格操作审计

## 04 功能与自动化测试

- [ ] 价格定义创建成功
- [ ] 同维度重复定义返回 409
- [ ] 不同周期、不同套餐允许独立价格
- [ ] V1 草稿创建成功
- [ ] 同价格重复草稿返回 409
- [ ] 草稿修改 revision 递增
- [ ] 过期 revision 返回 409
- [ ] 已发布版本不可修改
- [ ] 未来生效价格按时间正确查询
- [ ] 历史版本金额不变
- [ ] 回溯发布时间被拒绝
- [ ] 非递增生效时间被拒绝
- [ ] 跨套餐价格访问被拒绝
- [ ] 普通用户及未认证访问被拒绝
- [ ] 非法货币和金额被拒绝
- [ ] 发布审计正确
- [ ] Testcontainers 集成测试通过
- [ ] Apifox 自动化通过
- [ ] Maven 全量回归通过

## 05 并发与故障测试

已实现：
- 价格定义行锁
- 版本号唯一索引
- 锁定读获取最新版本
- 草稿更新 CAS
- 发布状态条件更新
- 发布事件事务内审计

待后续加强：
- 两个管理员同时创建价格版本
- 并发发布与草稿修改竞争
- 多实例时间同步验证
- 数据库故障恢复
- 价格发布权限细分
- 金额上限与财务审批规则
- 历史价格快照校验

## 06 验收总结

M02-03：待验收

Flyway：待填写
JUnit：待填写
Apifox：待填写
全量回归：待填写
Git Commit：待填写
遗留问题：待填写

下一阶段：M02-04 计费规则模型

---

# M02-04 — 用量计费规则模型

状态：开发中

## 01 需求分析

目标：
- 产品支持多个计量项
- 支持计量单位和业务编码
- 计费规则绑定 PriceVersion
- 支持固定单价 PER_UNIT
- 支持阶梯累进 GRADUATED
- 支持阶梯全量 VOLUME
- 支持免费额度
- 支持纯费用试算
- 已发布价格版本的规则不可修改

本轮不实现：
- 用量事件接入
- 用量去重与聚合
- 真实账单
- 折扣、税费、退款
- 订阅价格锁定

## 02 技术设计

Flyway：
- V8__usage_pricing_schema.sql

数据库：
- mb_usage_meter
- mb_usage_price_rule
- mb_usage_price_tier

关联：
- Meter → Product
- UsagePriceRule → PriceVersion + Meter
- UsagePriceTier → UsagePriceRule

核心约束：
- 同产品 meter_code 唯一
- 同版本、同计量项只有一条规则
- 数量、单价不能为负
- 阶梯累计上限严格递增
- 最后一档必须无上限
- 只能修改 DRAFT 价格版本的规则
- 修改规则必须增加 PriceVersion.revision

计费算法：
- PER_UNIT：收费数量 × 单价
- GRADUATED：分阶梯累加费用
- VOLUME：全部收费数量采用所属阶梯单价

免费额度：
billableQuantity = max(0, actualQuantity - includedQuantity)

金额：
- 中间计算使用 BigInteger
- 输出使用 long 最小货币单位
- 溢出时拒绝试算，不静默截断
- 本模块只计算单计量项用量费用
- 基础费用将在账单聚合时只收取一次

## 03 开发实现

- [ ] Flyway V8
- [ ] Generator 生成三个 Entity
- [ ] Generator 生成三个 Mapper 和 XML
- [ ] PricingMode
- [ ] CreateUsageMeterRequest
- [ ] UpsertUsageRuleRequest
- [ ] PreviewUsageRequest
- [ ] UsageMeterVO
- [ ] UsageRuleVO
- [ ] UsageChargePreviewVO
- [ ] UsageChargeCalculator
- [ ] UsagePricingService
- [ ] UsagePricingController

## 04 功能与自动化测试

- [ ] 创建计量项正常
- [ ] 同产品重复编码返回 409
- [ ] 不同产品允许相同编码
- [ ] PER_UNIT 计算正确
- [ ] GRADUATED 计算正确
- [ ] VOLUME 计算正确
- [ ] 免费额度正确
- [ ] 零用量不收费
- [ ] 阶梯边界正确
- [ ] 非法阶梯参数返回 400
- [ ] 旧 revision 返回 409
- [ ] 规则完整替换成功
- [ ] 已发布版本不能修改
- [ ] 跨产品计量项被拒绝
- [ ] 无规则试算返回 404
- [ ] 普通用户越权返回 403
- [ ] 无 JWT 返回 401
- [ ] 负用量被拒绝
- [ ] 金额溢出被拒绝
- [ ] 审计记录正确
- [ ] 并发修改无静默覆盖
- [ ] 7 项纯单元测试通过
- [ ] 19 项数据库集成测试通过
- [ ] Apifox 场景通过
- [ ] Maven 全量回归通过

## 05 并发与故障测试

已实现：
- 唯一索引防止重复规则
- 事务保证规则与阶梯整体更新
- 锁定 mb_price 行
- 通过当前读检查价格版本状态
- revision CAS 防止过期覆盖
- 业务成功审计与事务同步

后续增强：
- 大规模历史用量聚合
- 用量事件幂等接入
- 流水与账单金额对账
- 多实例负载下计费一致性
- 价格规则查询性能和缓存
- 规则异常后的人工恢复流程
- 计费结果固定快照

## 06 验收总结

M02-04：待验收

Flyway：待填写
Generator：待填写
JUnit：待填写
Apifox：待填写
并发测试：待填写
全量回归：待填写
Git Commit：待填写
遗留风险：待填写

下一阶段：M03 订阅生命周期


---

# M02-05 — 产品与套餐发布校验

## 01 需求分析

- 套餐只能从 DRAFT 发布。
- 套餐发布前必须存在至少一个当前已生效的 MONTHLY 价格版本。
- 产品只能从 DRAFT 发布，且必须至少有一个 ACTIVE 套餐。
- 跨租户用户不能执行平台目录发布。

## 02 技术设计

- 使用产品/套餐行锁串行化发布校验与状态迁移。
- 产品和套餐状态仍由原有 CHECK 约束约束。
- 发布行为与审计记录在同一数据库事务提交。

## 03 开发实现

- `ProductServiceImpl.publish` 与 `POST /api/v1/products/{productId}/publish`。
- `PlanService.publish` 与 `POST /api/v1/products/{productId}/plans/{planId}/publish`。

## 04 功能与自动化测试

- `PlanIntegrationTest.productAndPlanPublicationRequireAnEffectiveMonthlyPrice`：空套餐/空产品拒绝发布；有效月价发布套餐后可发布产品；审计记录数量为 2。
- 执行命令：`mvn -Dtest=ProductIntegrationTest,PlanIntegrationTest,PriceVersionIntegrationTest,UsageChargeCalculatorTest test`。
- 结果：44 tests, 0 failures, 0 errors。
- Apifox：未执行。

## 05 并发与故障测试

- 发布时锁定产品行和套餐行；更新语句仍带状态条件，竞争状态迁移不能静默覆盖。
- 本轮未做多实例并发压测。

## 06 验收总结

- M02-05 targeted JUnit：通过。
- M02 模块后续跨模块/全量回归按用户要求不执行。
- 下一阶段：M03 订阅生命周期。

## 2026-10-12 修复复验

规则替换显式持久化 null 单价，修复 PER_UNIT 转阶梯模式的约束错误。PriceVersionIntegrationTest 15 项通过。

本地模块门槛通过；完整原因、指标与验证边界见[修复复验报告](../../M00-M08-修复复验报告-2026-10-12.md)。此前章节记录为历史实施结果。

## 2026-10-12 G01–G04 补齐与性能复验

V15 新增模型目录及不可变模型—计量项关系；新模型使用专属输入/输出计量项，通过既有价格版本配置不同费率。两个模型四种计量项的端到端计费、错误模型和未定价计量项拒收已覆盖于 InvoiceIntegrationTest。

接口及设计见[实现说明](../../G01-G04-实现与性能实验说明.md)，验收证据与待办见[本轮报告](../../G01-G04-修复与性能优化报告-2026-10-12.md)。Apifox 由用户执行。此前结果保留为历史记录。
