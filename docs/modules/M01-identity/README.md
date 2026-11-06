
# M01 — 多租户与身份认证

状态：实现中

## 01 需求分析

目标：
- 支持平台用户加入多个租户
- 支持租户独立成员关系
- 后续增加 RBAC、API Key 与审计
- 所有业务数据严格按租户授权访问

核心不变量：
- 租户编码唯一
- 用户邮箱唯一
- 同一用户在同一租户只能有一条成员关系
- 不允许成员引用不存在的租户或用户
- 跨租户访问必须经过授权

## 02 技术设计

M01-01 数据模型：
- mb_tenant
- mb_user
- mb_tenant_member

数据库：
- MySQL 8.4
- Flyway V2
- 唯一约束、外键和状态约束
- MyBatis-Plus Entity、Mapper、XML

本轮不开放业务接口。

## 03 开发实现

M01-01：基础数据模型及持久层

新增：
- V2__identity_foundation.sql
- IdentityCodeGenerator.java
- 生成的 Entity、Mapper、XML
- IdentitySchemaIntegrationTest.java

## 04 功能与自动化测试

- [ ] V2 迁移成功
- [ ] 三张业务表创建成功
- [ ] Mapper 插入与查询正常
- [ ] 租户编码唯一约束通过
- [ ] 无效成员外键被拒绝
- [ ] 无效租户状态被拒绝
- [ ] M00 Apifox 回归通过

## 05 并发与故障测试

M01-01 暂不进行 JMeter 压测。

M01-02、M01-03 重点验证：
- 并发注册和成员加入
- 登录安全
- 跨租户越权
- API Key 吊销及重复请求

## 06 验收总结

M01-01：待验收
M01-02：未开始
M01-03：未开始

Git Commit：待填写
测试结果：待填写
遗留问题：待填写



---

# M01-02 — 认证与租户访问控制

状态：开发中

## 01 需求分析

- 全局账号通过邮箱密码登录
- 登录后取得短期 JWT
- 用户可以属于多个租户
- 租户级请求必须验证成员关系
- 停用账号、租户或成员后不得继续访问对应资源

## 02 技术设计

认证：Spring Security + JWT
签名：HS256，密钥通过环境配置注入
密码：BCrypt
Token 有效期：15 分钟
租户识别：X-Tenant-Id
租户校验：数据库成员关系及状态
接口约定：/api/v1/tenant/**

安全原则：
- 默认要求身份认证
- 不信任客户端租户 ID
- 不返回密码哈希
- 不向日志输出 JWT 或密码
- 本地初始化账号不用于生产部署
- JWT 认证时重新检查账号状态
- 租户上下文请求结束后清理

## 03 开发实现

新增：
- JwtConfig
- TokenService
- SecurityConfig
- SecurityErrorWriter
- AuthService
- AuthController
- TenantContext
- TenantAccessService
- TenantAccessFilter
- TenantController
- LocalBootstrapConfig
- AuthTenantIntegrationTest

修改：
- pom.xml
- ErrorCode.java
- application.yaml
- .env.example
- 现有测试配置

Git Commit：待填写

## 04 功能与自动化测试

- [ ] 正确登录与 JWT 签发
- [ ] 错误密码返回 401
- [ ] 未认证访问返回 401
- [ ] 所属租户可访问
- [ ] 跨租户访问返回 403
- [ ] 缺少租户标识返回 400
- [ ] 停用账号后拒绝访问
- [ ] 停用租户后拒绝访问
- [ ] 停用成员后拒绝访问
- [ ] 无效 JWT 返回 401
- [ ] Apifox 场景自动化通过
- [ ] M00、M01-01 回归通过

## 05 并发与故障测试

本轮不进行 JMeter 性能压测。

后续补充：
- 登录限流和暴力破解防护
- 令牌吊销与密钥轮换
- 多实例 JWT 验证
- 缓存引入后的权限状态一致性
- 多线程任务中的租户上下文传播

## 06 验收总结

状态：待验收

JUnit：待填写
Apifox：待填写
已知问题：待填写
Git Commit：待填写

下一阶段：
M01-03 RBAC、API Key、吊销与审计


---

# M01-03 — RBAC、API Key、JWT 吊销与安全审计

状态：开发中

## 01 需求分析

目标：
- 租户级角色与权限管理
- 受控成员角色变更
- 服务端 API Key 管理与认证
- JWT 退出登录和吊销
- 敏感安全操作审计

核心约束：
- 权限必须绑定当前租户
- 用户不能修改自己的 OWNER 角色
- API Key 不得访问管理员接口
- 密钥原文只返回一次
- 已吊销凭证不能继续发起新的认证请求

## 02 技术设计

RBAC：
- OWNER、ADMIN、VIEWER
- 数据库存储角色与权限关系
- 跨租户关联由复合外键约束

API Key：
- SecureRandom 生成
- SHA-256 摘要存储
- 有效期不超过 90 天
- MySQL 状态检查
- 只允许计量接入权限

JWT：
- 通过 jti 记录吊销状态
- MySQL 校验
- 不依赖 Redis

审计：
- 保存关键安全操作
- 登录失败使用独立事务
- 权限与凭证变更审计和业务事务关联
- 不记录敏感凭证原文

## 03 开发实现

数据库：
- V3__identity_security.sql

主要组件：
- TenantRoleSetup
- TenantRoleService
- SecurityAuditService
- ApiKeyService
- ApiKeyAuthenticationFilter
- ApiKeyController
- IngestAuthController
- TokenRevocationService
- TenantSecurityController
- SecurityConfig 更新

测试：
- SecurityLifecycleIntegrationTest

## 04 功能与自动化测试

- [ ] 默认角色与权限初始化
- [ ] OWNER 权限查询
- [ ] VIEWER 越权拦截
- [ ] 成员角色修改
- [ ] API Key 创建
- [ ] API Key 明文只返回一次
- [ ] API Key 认证
- [ ] API Key 吊销
- [ ] JWT 退出登录及吊销
- [ ] 跨租户访问拒绝
- [ ] 安全审计查询
- [ ] Apifox 自动化场景通过
- [ ] JUnit 集成测试通过
- [ ] 全量 Maven 回归通过

## 05 并发与故障测试

已设计：
- JWT jti 唯一约束
- API Key 前缀唯一约束
- 成员角色变更行锁
- API Key 原子状态更新
- 事务内记录重要变更审计

待后续验证：
- 多实例权限并发修改
- 凭证吊销与进行中请求的竞态
- 高并发 API Key 鉴权性能
- 数据库短暂不可用时的安全行为
- 过期吊销记录清理
- JWT 密钥轮换与强制全设备退出

## 06 验收总结

M01-03：待验收

JUnit：待填写
Apifox：待填写
数据库核对：待填写
Git Commit：待填写
遗留问题：待填写

下一阶段：M01 整体验收，随后进入 M02 产品与定价

## 2026-10-12 修复复验

业务写操作新增 SUBSCRIPTION_MANAGE、PAYMENT_MANAGE、RECONCILIATION_RUN；V14 同步旧 OWNER/ADMIN，VIEWER 保持只读。身份定向测试 14 项通过。

本地模块门槛通过；完整原因、指标与验证边界见[修复复验报告](../../M00-M08-修复复验报告-2026-10-12.md)。此前章节记录为历史实施结果。
