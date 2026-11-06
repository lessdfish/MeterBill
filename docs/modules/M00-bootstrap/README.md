
# M00 — 基础工程与环境初始化

> 历史增量实施记录：下文“待执行/待验收”保留当时语境，不代表当前状态。当前完整回归为 126 项全部通过，部署与最终结论见 `../../MeterBill-成品实施记录-2026-11-04.md`。

状态：实现中

## 01 需求分析

目标：创建 MeterBill 最小可运行工程，
统一 Java、Maven、MySQL、Flyway、API 和测试环境。

范围：
- Spring Boot 基础工程
- Docker Desktop MySQL
- Flyway 版本管理
- 基础 API 与 OpenAPI
- JUnit / Testcontainers 集成测试

非目标：
- 暂不实现具体计费业务
- 暂不引入 MQ、Redis、微服务

## 02 技术设计

- JDK：21
- Spring Boot：4.1.1
- MyBatis-Plus：3.5.17
- 数据库：MySQL 8.4
- Schema 管理：Flyway
- 应用在 IDEA 启动
- MySQL 在 Docker Desktop 运行

安全约束：
- 密码不进入 Git
- 数据库只映射到本机
- 业务表由 Flyway 正式创建

## 03 开发实现

记录本轮新增文件、核心改动和 Git Commit。

当前任务：M00-01 基础工程初始化。

## 04 功能与自动化测试

待执行：
- Maven 编译
- Testcontainers MySQL 集成测试
- Flyway 初次迁移
- Flyway 重复启动校验
- Apifox ping 接口测试
- Actuator 健康检查
- OpenAPI 文档检查

## 05 并发与故障测试

M00 不进行性能压测。

重点验证：
- MySQL 容器不可用时应用的启动行为
- 数据库恢复后能否正常启动
- 测试数据库与开发数据库是否隔离

## 06 验收总结

当前状态：未验收

测试结果：待填写
Git Commit：待填写
已知问题：待填写
下一阶段：M00-02 通用组件与工程规范


---

# M00-02 通用组件与工程规范

## 01 需求分析

目标：
- 统一 API 成功与失败响应
- 建立业务异常与全局异常处理机制
- 统一请求追踪标识
- 配置 MyBatis-Plus 分页
- 建立独立的 Web 层测试

## 02 技术设计

- 统一响应：ApiResponse<T>
- 错误码：ErrorCode
- 业务异常：BusinessException
- 全局异常：RestControllerAdvice
- 参数校验：Jakarta Validation
- 数据库分页：PaginationInnerInterceptor
- 请求追踪：RequestTraceFilter + MDC

约束：
- 不向客户端返回系统异常堆栈
- 不把全部异常转换成 HTTP 200
- 不把 404、405 错误误报为 500
- 不依赖 Redis 保存请求追踪标识
- 不在生产接口中保留测试专用 Controller

## 03 开发实现

记录本轮新增、修改及删除的文件路径。

Git Commit：待填写

## 04 功能测试

- [ ] ApiResponse 单元测试
- [ ] 全局异常 MockMvc 测试
- [ ] MyBatis-Plus 配置检查
- [ ] Request ID 测试
- [ ] Apifox 接口回归
- [ ] Testcontainers 数据库集成测试

## 05 并发与故障测试

本轮不进行性能压测。

检查：
- 异常响应不泄漏内部信息
- 连续请求的追踪标识相互独立
- 测试不依赖开发数据库
- 数据库集成测试在独立容器中运行

## 06 验收总结

状态：待验收

Maven 测试结果：待填写
Apifox 测试结果：待填写
异常问题：待填写
Git Commit：待填写
