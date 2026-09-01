# 审核复核指南（主要功能路径）

> 适用版本：v1.6.0 · 面向 Halo 应用市场审核与任何希望自行复核的使用者

本文提供**可复核的主要功能路径**：既包含线上演示环境的直接访问路径，
也包含**无需 Wavelog 账号**即可在本地完整复核的方式。

---

## 一、线上演示环境

| 项目 | 地址 | 说明 |
| --- | --- | --- |
| 统计页面（现代仪表盘） | <https://bg8lng.com/qso-stats> | 插件的独立统计页，复用主题布局 |
| 统计数据接口 | <https://bg8lng.com/qso-stats/api/dashboard> | 只读 JSON，可直接在浏览器打开 |
| 组件数据接口 | <https://bg8lng.com/qso-stats/api/statistics> | 只读 JSON |
| 呼号查询 | <https://bg8lng.com/qso-stats/api/search?callsign=N9EAT> | 只读 JSON |

在统计页面上可直接操作：

1. 在页面顶部的**呼号查询卡**中输入一个呼号（例如 `BA4TB`、`N9EAT`）并回车；
2. 结果表格列出该呼号的通联记录（日期 / 模式 / 频段）；
3. 点击「一键 OQRS 申请」展开申请表单 → 可见**邮箱输入、寄送方式、留言、数据处理告知勾选框**；
4. 未勾选告知时点击「提交申请」会被前端拦截并提示；服务端亦有完整校验（见第三节）。

> **演示环境不可访问时**：该站点为个人自建站点，可能因维护短暂不可用。
> 此时请使用下面的「本地复核」路径，它不依赖任何外部服务。

---

## 二、本地复核（不需要 Wavelog 账号）

### 2.1 前端组件预览（零依赖，1 分钟）

```bash
git clone https://github.com/bg8lng/halo-plugin-qso-stats.git
cd halo-plugin-qso-stats
python3 -m http.server 8080
# 浏览器打开 http://localhost:8080/demo/preview.html
```

该页面使用 `demo/mock-stats.json` 与 `demo/mock-search.json` 模拟数据，
可复核组件在**亮 / 暗两种主题**下的完整视觉与交互（含呼号查询与 OQRS 表单）。

### 2.2 后端逻辑复核（单元测试，覆盖全部安全要求）

```bash
# 需要 JDK 17+
./gradlew test
```

测试会启动**本地假 Wavelog 服务**（`com.sun.net.httpserver.HttpServer`），
端到端验证「读取配置 → 请求 Wavelog → 校验 → 构建载荷」全链路，无需真实账号。

与本次审核意见直接对应的用例（`src/test/java/com/bg8lng/qsostats/`）：

| 用例 | 验证内容 |
| --- | --- |
| `searchIsRejectedWhenFeatureDisabled` | 开关关闭后查询接口返回 **403** 且不返回任何日志数据 |
| `oqrsIsRejectedWhenFeatureDisabled` | OQRS 开关关闭后写接口返回 **403** |
| `oqrsIsRejectedWhenSearchDisabled` | 总开关关闭时 OQRS 一并关闭 |
| `searchIsRateLimitedPerClient` | 查询频率限制生效（超限 **429**），且按来源隔离 |
| `oqrsIsRateLimitedPerClient` | OQRS 频率限制生效（超限 **429**） |
| `oqrsRejectsQsosThatDoNotExistInTheLog` | **服务端记录校验**：伪造的通联记录被拒（**400**）且**不转发**到 Wavelog |
| `oqrsRejectsCallsignWithoutAnyLoggedQso` | 无任何日志记录的呼号不得提交 |
| `duplicateOqrsSubmissionIsRejected` | **防重复提交**：相同内容二次提交返回 **409** 且不重复转发 |
| `oqrsRejectsTooManyQsos` | 单次提交条数上限 |
| `oqrsWithMalformedEmailReturnsError` | 邮箱格式服务端校验 |
| `malformedCallsignIsRejectedBeforeReachingWavelog` | 呼号格式校验，非法输入不透传上游 |
| `PublicApiGuardTest`（9 个用例） | 限流窗口、重复窗口过期、失败释放、内存清理 |
| `oqrsSwitchDependsOnSearchSwitch` | 总开关关闭时 OQRS 开关一并失效 |
| `securityDefaultsProtectPublicEndpoints` | 防滥用参数未配置时回落到内置默认值而非「不限制」 |

### 2.3 在自己的 Halo 中复核

1. `./gradlew build` → 上传 `build/libs/qso-stats-1.6.0.jar` 并启用；
2. 插件设置中填写任意 Wavelog 站点地址与 Token（**未配置时页面会给出明确提示，不会报错崩溃**）；
3. 访问 `/qso-stats` 复核页面渲染；
4. 按第三节用 curl 复核访问控制。

---

## 三、公开接口的访问控制复核（curl）

以下命令可直接对任何安装了本插件的站点执行（把 `https://example.com` 换成实际域名）。

### 3.1 关闭开关后，接口必须被服务端拒绝

在后台「展示与交互」中关闭「启用呼号查询与 OQRS」，然后：

```bash
# 期望：HTTP 403
curl -i "https://example.com/qso-stats/api/search?callsign=BG8LNG"

# 期望：HTTP 403，且不会有任何数据被发往 Wavelog
curl -i -X POST "https://example.com/qso-stats/api/oqrs" \
  -H "Content-Type: application/json" \
  -d '{"callsign":"BG8LNG","email":"a@example.com","qslroute":"B","qsos":[]}'
```

只关闭「启用一键 OQRS 卡片申请」时，`search` 返回 200 而 `oqrs` 返回 403。

### 3.2 参数校验

```bash
# 呼号格式非法 → 400
curl -i "https://example.com/qso-stats/api/search?callsign=%27%3B%20DROP%20TABLE--"

# 邮箱格式非法 → 400
curl -i -X POST "https://example.com/qso-stats/api/oqrs" \
  -H "Content-Type: application/json" \
  -d '{"callsign":"BG8LNG","email":"not-an-email","qslroute":"B","qsos":[{"date":"2026-06-16","time":"17:06","band":"20m","mode":"SSB","stationId":1}]}'
```

### 3.3 服务端记录校验（核心）

```bash
# 伪造一条本站日志中不存在的通联 → 400「提交的通联记录与本站日志不一致」
curl -i -X POST "https://example.com/qso-stats/api/oqrs" \
  -H "Content-Type: application/json" \
  -d '{"callsign":"BG8LNG","email":"a@example.com","qslroute":"B","qsos":[{"date":"1999-01-01","time":"00:00","band":"160m","mode":"CW","stationId":42}]}'
```

### 3.4 频率限制

```bash
# 默认 20 次/分钟，第 21 次起返回 429
for i in $(seq 1 25); do
  curl -s -o /dev/null -w "%{http_code} " "https://example.com/qso-stats/api/search?callsign=BG8LNG"
done; echo
```

### 3.5 防重复提交

用**完全相同**的合法请求体连续提交两次：第一次 200，第二次 **409**。

---

## 四、状态码对照表

| 状态码 | 含义 |
| --- | --- |
| 200 | 成功 |
| 400 | 参数非法 / 提交的通联记录与本站日志不一致 / 超出单次条数上限 |
| 403 | 对应功能已被站长关闭 |
| 409 | 去重窗口内的重复提交 |
| 429 | 触发频率限制 |
| 502 | 上游 Wavelog 异常（超时、连接失败、接口报错） |
| 503 | 未配置 Wavelog 地址或 Token |

---

## 五、人工测试与审查记录

本次提交前已完成的人工验证：

- [x] `./gradlew build` 通过，**50 个单元测试全部通过**（含 22 个访问控制 / 防滥用用例）
- [x] 逐一走查全部 4 个公开端点的服务端分支，确认开关关闭时在**处理链第一步**即拒绝
- [x] 走查 OQRS 全链路，确认前端提交的通联记录**不被信任**，一律以本站日志为准
- [x] 复核内存结构（限流计数、去重指纹）具备过期清理与条目上限，无无界增长
- [x] 复核错误信息不泄露 API Token 与 Wavelog 内部地址
- [x] 复核前端 OQRS 表单包含数据处理告知与必选勾选
- [x] 复核 README / PRIVACY / CHANGELOG 中的版本号与全部外链可访问
- [x] 复核 Java 包名、Gradle group 已全部迁出 `run.halo.*` 保留命名空间

---

## 六、联系方式

- Issues：<https://github.com/bg8lng/halo-plugin-qso-stats/issues>
- 作者：BG8LNG · <https://bg8lng.com>

若审核过程中需要一个**可登录的 Wavelog 演示环境**，请通过 Issue 联系，作者可临时开放只读演示账号。
