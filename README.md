# 通联统计（QsoStats）

![Version](https://img.shields.io/badge/version-1.4.0-1677ff)
![Halo](https://img.shields.io/badge/Halo-2.20%2B-00b42a)
![License](https://img.shields.io/badge/license-GPL--3.0-blue)

> 🆕 **v1.4.0**：修复统计页偶发无数据/图表空白，仪表盘 UI 焕新（KPI 专属主色、渐变图表、入场动画、实时脉冲指示），详见 [CHANGELOG](CHANGELOG.md)。
>
> 🌐 在线演示：<https://bg8lng.com/qso-stats>

一个 Halo 2.x 插件：通过 [Wavelog](https://www.wavelog.org) 日志平台的 API v2，在个人网站上展示通联信息统计（QSO 总数、DXCC 字头、波段/模式分布、最近通联、活跃度等），并支持**按呼号查询通联记录**与**一键 OQRS 卡片申请**。

- **统计项目可后台修改**：增删、启停、排序、重命名，每个项目的展示条数可单独设置；
- **API 可后台配置**：Wavelog 站点地址与 API Token 均在插件设置页配置，无需改代码；
- **呼号查询 + 一键 OQRS**：输入呼号即可检索通联（仅展示日期 / 模式 / 频段），一键提交 QSL 卡片申请（需填写邮箱）；OQRS 由插件后端转发到 Wavelog，无需暴露 Token；
- **展示沿用主题风格**：组件以 CSS 变量驱动、继承主题字体颜色；独立页面复用主题布局（Halo ≥ 2.26），也可直接嵌入任意主题页面。

## 功能一览

| 统计项目 | 类型 | 说明 |
| --- | --- | --- |
| 通联总数 | 大数字 | Wavelog `statistic.qso.total` |
| 活跃度 | 三个指标 | 今日 / 本月 / 今年通联数 |
| DXCC 字头 | 三个指标 | 已通联 / 已确认 / 可用字头数 |
| 波段分布 | 条形分布 | 各波段通联占比（可设条数） |
| 模式分布 | 条形分布 | 各模式通联占比（可设条数） |
| 日 / 月 / 历年统计 | 图表 | 近 30 日通联、本年每月通联、历年通联（ECharts，独立统计页） |
| 模式 / 频段分布 | 图表 | 模式占比环图、频段横向条形图 + 排名列表（独立统计页） |
| 最近通联 | 列表 | 最近 N 条通联（呼号 / 波段 / 模式 / 时间） |
| 呼号查询 | 搜索框 | 按呼号检索通联，展示日期 / 模式 / 频段 |
| 一键 OQRS | 按钮 | 对查询结果一键提交 QSL 卡片申请（邮箱必填） |

## 安装

### 方式一：构建安装

```bash
# 需要 JDK 17+
./gradlew build
# 产物：build/libs/qso-stats-1.3.0.jar
```

然后在 Halo 后台「插件」→「安装」→ 上传 `qso-stats-1.3.0.jar` 并启用。

### 方式二：直接使用

在 [Releases](../../releases) 下载最新 jar，上传安装即可。

## 前置准备：Wavelog API Token

1. 登录 Wavelog，进入用户菜单的 **API** 页面；
2. 创建 **API v2 Token**（`wl2_` 开头），权限勾选：
   - `statistic:read`（统计接口）
   - `qso:read`（最近通联）
3. 复制完整 Token（只显示一次，请立即保存）。

> 要求 Wavelog ≥ 3.1.0（API v2 的最低版本）。

## 后台配置

启用插件后，进入「插件」→「通联统计」→「设置」：

**① Wavelog API 配置**
- **Wavelog 站点地址**：如 `https://log.example.com`（可带 `/index.php`，插件会自动拼接）；
- **API Token**：上一步创建的 `wl2_...` Token；
- **缓存时间**：Wavelog 数据缓存秒数，默认 300（避免每次访问都请求日志平台）；
- **请求超时**：默认 10 秒；
- **统计页面标题**：`/qso-stats` 页面的标题。

**② 统计项目**
- 列表可增删、拖拽排序、启停；
- 每项可改「显示标题」；波段/模式/最近通联可设「显示条数」。

**③ 呼号查询与 OQRS**
- **启用呼号查询与 OQRS**：是否在组件中显示呼号查询框（默认开启）；
- **查询结果上限**：呼号查询最多返回并展示的条数（默认 50，按时间倒序）。

> 呼号查询通过 API v2 的 `GET /api/v2/qso?callsign=...` 实现（需 `qso:read` 权限）；
> 一键 OQRS 由插件后端转发到 Wavelog 的公开申请端点 `oqrs/save_oqrs_request_grouped`，
> 不需要访问者持有任何 Token。前提是 Wavelog 站点已为对应电台位置**开启 OQRS**
> （Wavelog「站点设置」中为电台位置启用 OQRS，并设置公开 slug）。

**④ 统计页面布局**（新增）
- 面板顺序与显隐：搜索、KPI、日/月/历年、模式/频段、最近通联，可拖拽排序、停用；
- 占位宽度：图表面板可选半行（并排）或整行。

**⑤ 展示设置**
- 是否显示区块标题、标题文字、是否显示更新时间、加载失败提示文案。

## 前端接入

### 方式 A：嵌入任意主题页面（推荐）

统计组件资源（JS/CSS）由插件自动注入所有主题页面，你只需在目标位置放一个容器：

```html
<div class="qso-stats-widget"></div>
```

- 在「文章/独立页面」的正文中：切到 HTML 源码模式粘贴即可；
- 在主题模板中：直接写在模板文件里，或使用插件片段：

```html
<div th:insert="~{plugin:QsoStats:fragments/qso-stats :: qso-stats-widget}"></div>
```

容器可选属性：

| 属性 | 说明 |
| --- | --- |
| `data-endpoint` | 覆盖数据接口地址（默认 `/qso-stats/api/statistics`） |
| `data-search-endpoint` | 覆盖呼号查询接口（默认 `/qso-stats/api/search`） |
| `data-oqrs-endpoint` | 覆盖 OQRS 申请接口（默认 `/qso-stats/api/oqrs`） |
| `data-refresh` | 自动刷新间隔（秒，≥ 30 生效，默认关闭） |

### 方式 B：独立统计页面

直接访问 `/qso-stats`：

- **Halo ≥ 2.26**：页面复用当前主题布局（主题提供 `templates/layout.html` 时完整套用主题页头页脚），页面标题由主题渲染，插件不再重复输出；
- **Halo < 2.26**：使用插件自带页面外壳（样式与组件一致，仅保留本页标题）。

> 独立页面上组件的内嵌「区块标题」会自动隐藏，避免与页面标题形成「双重标题」；
> 嵌入式（文章 / 主题内）组件仍显示「区块标题」。
> 主题可通过提供同名模板 `qso-stats.html` 完全接管该页面。

## 主题定制

组件全部样式通过 CSS 变量控制（均带中性兜底值，默认与主题字体颜色一致）。主题可覆盖：

```css
.qso-stats-widget {
  --qso-stats-accent: #1677ff;          /* 强调色（数值、进度条、按钮），Ant 蓝 */
  --qso-stats-accent-2: #4096ff;        /* 强调色渐变终点 */
  --qso-stats-card-bg: rgba(127,127,127,.06);   /* 卡片背景 */
  --qso-stats-card-border: rgba(127,127,127,.16); /* 卡片边框 */
  --qso-stats-card-shadow: 0 1px 2px rgba(16,24,40,.05), 0 4px 16px rgba(16,24,40,.04);
  --qso-stats-radius: 14px;             /* 圆角 */
  --qso-stats-muted: rgba(127,127,127,.85);      /* 次要文字 */
  --qso-stats-track: rgba(127,127,127,.16);      /* 进度条轨道 / 悬停背景 */
  --qso-stats-danger: #dc2626;          /* 错误提示色 */
  --qso-stats-success: #16a34a;         /* 成功提示色 */
}
```

## 常见问题

**Q：页面显示「未配置 Wavelog API 地址或 Token」**
插件设置中填写 Wavelog 站点地址和 `wl2_` Token 后保存即可。

**Q：接口返回 401 / 403**
Token 无效、过期或缺少权限。请确认 Token 以 `wl2_` 开头、未过期，且勾选了 `statistic:read`（最近通联、呼号查询需要 `qso:read`）。

**Q：修改设置后数据没变化**
统计接口结果有缓存（默认 300 秒），可在设置中调低「缓存时间」。

**Q：呼号查询提示「Wavelog 接口错误」**
请确认 Token 已勾选 `qso:read` 权限；Wavelog 要求 Wavelog ≥ 3.1.0 才提供 API v2 的 QSO 查询。

**Q：一键 OQRS 提交失败**
请确认 Wavelog 站点已为该电台位置开启 OQRS，且站点开启了公开 slug；另外，如果 Wavelog 管理员开启了 CSRF 防护，公开申请端点会拒绝插件转发的请求（Wavelog 默认关闭 CSRF）。

**Q：/qso-stats 页面没有主题页头页脚**
Halo < 2.26 时布局契约不可用，会使用自带外壳；升级 Halo 或改用「方式 A」嵌入。

**Q：不想让组件加载脚本注入所有页面**
组件脚本仅几 KB 且无组件时不渲染任何内容；如需彻底移除，可停用插件。

## 本地预览

无需 Halo 环境即可查看组件效果（使用模拟数据，含亮/暗主题两种示例）：

```bash
cd qso-stats
python3 -m http.server 8080
# 浏览器打开 http://localhost:8080/demo/preview.html
```

## 开发

```bash
./gradlew build        # 构建 + 运行单元测试
./gradlew test         # 仅测试
```

- 后端：Java 17 / Spring WebFlux（`src/main/java`）
- 设置表单：`src/main/resources/extensions/settings.yaml`
- 组件样式/脚本：`src/main/resources/static/`
- 页面模板：`src/main/resources/templates/`

## 许可证

[GPL-3.0](LICENSE) © BG8LNG