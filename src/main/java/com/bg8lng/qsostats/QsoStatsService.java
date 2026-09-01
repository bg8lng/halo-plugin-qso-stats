package com.bg8lng.qsostats;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.security.MessageDigest;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import reactor.core.publisher.Mono;
import run.halo.app.infra.utils.JsonUtils;
import run.halo.app.plugin.ReactiveSettingFetcher;

/**
 * 通联统计数据服务：读取插件配置，拉取并缓存 Wavelog 数据，构建前台组件载荷，
 * 并提供呼号查询与 OQRS 申请能力。
 */
@Service
public class QsoStatsService {

    private static final String GROUP_API = "api";
    private static final String GROUP_STATS = "stats";
    private static final String GROUP_DISPLAY = "display";
    private static final String GROUP_LAYOUT = "layout";
    private static final String GROUP_SECURITY = "security";

    /** 业余无线电呼号：字母数字，允许 / 前后缀（如 BY1CRA/8、VP8/BG8LNG） */
    private static final Pattern CALLSIGN_PATTERN =
        Pattern.compile("^[A-Z0-9]{1,12}(/[A-Z0-9]{1,10}){0,2}$");
    /** 邮箱地址的保守校验：仅用于挡掉明显非法输入，真实性由 Wavelog 侧确认 */
    private static final Pattern EMAIL_PATTERN =
        Pattern.compile("^[A-Za-z0-9._%+\\-]+@[A-Za-z0-9.\\-]+\\.[A-Za-z]{2,}$");
    /** OQRS 留言长度上限，与前端 maxlength 保持一致 */
    private static final int OQRS_MESSAGE_MAX_LENGTH = 500;
    /** OQRS 邮箱长度上限 */
    private static final int OQRS_EMAIL_MAX_LENGTH = 128;

    private final ReactiveSettingFetcher settingFetcher;
    private final WavelogClient wavelogClient;
    private final PublicApiGuard guard;
    private static final int DASHBOARD_PER_PAGE = 5000;
    private static final int DASHBOARD_MAX_PAGES = 20;

    private final Map<String, CacheEntry> cache = new ConcurrentHashMap<>();
    private final Map<String, CacheListEntry> rowsCache = new ConcurrentHashMap<>();

    public QsoStatsService(ReactiveSettingFetcher settingFetcher, WavelogClient wavelogClient,
                           PublicApiGuard guard) {
        this.settingFetcher = settingFetcher;
        this.wavelogClient = wavelogClient;
        this.guard = guard;
    }

    /**
     * 构建 /qso-stats/api/statistics 的完整载荷。
     *
     * <p>统计接口失败时整体返回错误载荷；仅「最近通联」失败时降级为
     * 空列表，不影响其他区块。
     */
    public Mono<StatsPayload.Payload> buildPayload() {
        return fetchDisplayConfig()
            .flatMap(config -> fetchApi().flatMap(api -> {
                if (!api.isConfigured()) {
                    return Mono.just(StatsPayload.error(
                        "未配置 Wavelog API 地址或 Token，请在插件「设置」中完成配置。", config));
                }
                return fetchStats(api)
                    .flatMap(qsoCache -> fetchStatsGroup()
                        .flatMap(stats -> buildSections(api, qsoCache.value(), stats))
                        .map(sections -> StatsPayload.success(sections,
                            qsoCache.updatedAt(), config)))
                    .onErrorResume(e -> Mono.just(StatsPayload.error(friendlyMessage(e), config)));
            }));
    }


    /**
     * 构建 /qso-stats/api/dashboard 的完整载荷（原生统计页面）。
     *
     * <p>包含 KPI（总数/活跃度/DXCC）与图表数据（近 30 日、当年各月、历年、
     * 波段/模式分布）以及最近通联。QSO 列表分页拉取并在服务端聚合，
     * 一次性输出到单个页面，避免前端分页。
     */
    public Mono<StatsPayload.DashboardPayload> buildDashboard() {
        return fetchDisplayConfig()
            .flatMap(config -> fetchApi().flatMap(api -> {
                if (!api.isConfigured()) {
                    return Mono.just(StatsPayload.dashboardError(
                        "未配置 Wavelog API 地址或 Token，请在插件「设置」中完成配置。", config));
                }
                return fetchStats(api)
                    .flatMap(qsoCache -> fetchAllQsoRows(api)
                        .flatMap(rowsCache -> fetchLayout().map(layout ->
                            new StatsPayload.DashboardPayload(
                                buildStatistics(qsoCache.value(), rowsCache.rows()),
                                buildRecent(rowsCache.rows(), 10),
                                latestUpdate(qsoCache.updatedAt(), rowsCache.updatedAt()),
                                null,
                                config.fallbackText(),
                                config.searchEnabled(),
                                config.searchMaxResults(),
                                config.displayStyle(),
                                config.defaultTheme(),
                                resolveLayout(layout.panelsOrDefault()),
                                config.oqrsEnabled()))))
                    .onErrorResume(e -> Mono.just(StatsPayload.dashboardError(friendlyMessage(e), config)));
            }));
    }

    /**
     * 按呼号查询通联记录（供 GET /qso-stats/api/search 使用）。
     *
     * <p>访问控制与防滥用：
     * <ol>
     *   <li>后台「启用呼号查询与 OQRS」关闭时，服务端直接返回 403，
     *       公开接口不再返回任何日志数据；</li>
     *   <li>按客户端标识（IP）做每分钟频率限制，超限返回 429；</li>
     *   <li>呼号格式非法时返回 400，不向 Wavelog 透传任意字符串；</li>
     *   <li>结果按缓存策略复用，避免高频请求日志平台。</li>
     * </ol>
     */
    public Mono<StatsPayload.ApiResponse<StatsPayload.SearchPayload>> searchQsos(String callsign,
                                                                                 String clientId) {
        return fetchDisplay().flatMap(display -> {
            if (!display.searchEnabledOrDefault()) {
                return Mono.just(StatsPayload.ApiResponse.of(403,
                    StatsPayload.searchError("呼号查询功能已关闭")));
            }
            return fetchSecurity().flatMap(security -> {
                if (!guard.allow("search", clientId, security.searchRateLimitOrDefault(),
                    60_000L)) {
                    return Mono.just(StatsPayload.ApiResponse.of(429,
                        StatsPayload.searchError("查询过于频繁，请稍后再试")));
                }
                String call = StringUtils.trimToEmpty(callsign).toUpperCase(Locale.ROOT);
                if (StringUtils.isBlank(call)) {
                    return Mono.just(StatsPayload.ApiResponse.of(400,
                        StatsPayload.searchError("请输入要查询的呼号")));
                }
                if (!CALLSIGN_PATTERN.matcher(call).matches()) {
                    return Mono.just(StatsPayload.ApiResponse.of(400,
                        StatsPayload.searchError("呼号格式不正确，请输入有效的业余无线电呼号")));
                }
                return searchInLog(call, display.searchMaxResultsOrDefault())
                    .map(StatsPayload.ApiResponse::ok)
                    .onErrorResume(e -> Mono.just(StatsPayload.ApiResponse.of(502,
                        StatsPayload.searchError(friendlyMessage(e)))));
            });
        });
    }

    /**
     * 提交 OQRS 卡片申请（供 POST /qso-stats/api/oqrs 使用）。
     *
     * <p>这是本插件唯一的公开写操作，会把访客邮箱与留言转发到站长自建的
     * Wavelog 站点，因此在转发前完成以下服务端校验（前端校验一律不可信）：
     * <ol>
     *   <li><b>功能开关</b>：后台关闭「呼号查询与 OQRS」或单独关闭 OQRS 时返回 403；</li>
     *   <li><b>频率限制</b>：单个客户端标识（IP）每小时可提交的次数上限，超限返回 429；</li>
     *   <li><b>参数校验</b>：呼号格式、邮箱格式与长度、留言长度、单次条数上限；</li>
     *   <li><b>记录校验</b>：逐条比对本站日志，提交的通联必须真实存在于该呼号名下，
     *       否则返回 400，杜绝伪造记录刷 OQRS；</li>
     *   <li><b>防重复提交</b>：对「呼号 + 邮箱 + 寄送方式 + 通联集合」计算指纹，
     *       去重窗口内重复提交返回 409；转发失败时释放指纹，允许访客重试。</li>
     * </ol>
     */
    public Mono<StatsPayload.ApiResponse<StatsPayload.OqrsResult>> submitOqrs(
        StatsPayload.OqrsSubmitRequest request, String clientId) {
        return fetchDisplay().flatMap(display -> {
            if (!display.oqrsEnabledOrDefault()) {
                return Mono.just(StatsPayload.ApiResponse.of(403,
                    StatsPayload.oqrsResult(false, "OQRS 卡片申请功能已关闭")));
            }
            return fetchSecurity().flatMap(security -> {
                if (!guard.allow("oqrs", clientId, security.oqrsRateLimitOrDefault(),
                    3_600_000L)) {
                    return Mono.just(StatsPayload.ApiResponse.of(429,
                        StatsPayload.oqrsResult(false, "提交过于频繁，请稍后再试")));
                }
                String invalid = validateOqrs(request, security);
                if (invalid != null) {
                    return Mono.just(StatsPayload.ApiResponse.of(400,
                        StatsPayload.oqrsResult(false, invalid)));
                }
                return submitVerifiedOqrs(request, display, security);
            });
        });
    }

    /** 参数校验：返回 null 表示通过，否则返回给访客的提示文案 */
    private String validateOqrs(StatsPayload.OqrsSubmitRequest request,
                                WavelogSettings.Security security) {
        String call = StringUtils.trimToEmpty(request.callsign()).toUpperCase(Locale.ROOT);
        if (StringUtils.isBlank(call) || !CALLSIGN_PATTERN.matcher(call).matches()) {
            return "呼号格式不正确，请输入有效的业余无线电呼号";
        }
        String email = StringUtils.trimToEmpty(request.email());
        if (StringUtils.isBlank(email)) {
            return "请填写您的邮箱地址";
        }
        if (email.length() > OQRS_EMAIL_MAX_LENGTH
            || !EMAIL_PATTERN.matcher(email).matches()) {
            return "邮箱地址格式不正确";
        }
        if (StringUtils.length(request.message()) > OQRS_MESSAGE_MAX_LENGTH) {
            return "留言过长，请控制在 " + OQRS_MESSAGE_MAX_LENGTH + " 字以内";
        }
        String route = StringUtils.defaultIfBlank(request.qslroute(), "B");
        if (!"B".equals(route) && !"D".equals(route)) {
            return "寄送方式不正确";
        }
        if (request.qsos() == null || request.qsos().isEmpty()) {
            return "没有可提交的通联记录";
        }
        if (request.qsos().size() > security.oqrsMaxQsosOrDefault()) {
            return "单次最多提交 " + security.oqrsMaxQsosOrDefault() + " 条通联记录";
        }
        return null;
    }

    /** 记录校验 + 防重复 + 转发 Wavelog */
    private Mono<StatsPayload.ApiResponse<StatsPayload.OqrsResult>> submitVerifiedOqrs(
        StatsPayload.OqrsSubmitRequest request, WavelogSettings.Display display,
        WavelogSettings.Security security) {
        String call = StringUtils.trimToEmpty(request.callsign()).toUpperCase(Locale.ROOT);
        String email = StringUtils.trimToEmpty(request.email());
        String message = StringUtils.trimToEmpty(request.message());
        String route = StringUtils.defaultIfBlank(request.qslroute(), "B");
        return fetchApi().flatMap(api -> {
            if (!api.isConfigured()) {
                return Mono.just(StatsPayload.ApiResponse.of(503, StatsPayload.oqrsResult(false,
                    "未配置 Wavelog API 地址或 Token，请在插件「设置」中完成配置。")));
            }
            return searchInLog(call, display.searchMaxResultsOrDefault())
                .flatMap(found -> {
                    if (found.qsos().isEmpty()) {
                        return Mono.just(StatsPayload.ApiResponse.of(400,
                            StatsPayload.oqrsResult(false, "本站日志中没有与该呼号的通联记录")));
                    }
                    if (!allQsosExist(request.qsos(), found.qsos())) {
                        return Mono.just(StatsPayload.ApiResponse.of(400,
                            StatsPayload.oqrsResult(false,
                                "提交的通联记录与本站日志不一致，请重新查询后再申请")));
                    }
                    String fingerprint = oqrsFingerprint(call, email, route, request.qsos());
                    long window = security.oqrsDuplicateWindowHoursOrDefault() * 3_600_000L;
                    if (guard.registerOrDuplicate(fingerprint, window)) {
                        return Mono.just(StatsPayload.ApiResponse.of(409,
                            StatsPayload.oqrsResult(false,
                                "该申请已提交过，请勿重复提交；如需修改请联系站长")));
                    }
                    return wavelogClient
                        .submitOqrsRequest(api, call, email, message, route, request.qsos())
                        .thenReturn(StatsPayload.ApiResponse.ok(
                            StatsPayload.oqrsResult(true, "OQRS 卡片申请已提交，感谢使用！")))
                        .onErrorResume(e -> {
                            // 转发失败不占用去重窗口，允许访客修正后重试
                            guard.release(fingerprint);
                            return Mono.just(StatsPayload.ApiResponse.of(502,
                                StatsPayload.oqrsResult(false, friendlyMessage(e))));
                        });
                })
                .onErrorResume(e -> Mono.just(StatsPayload.ApiResponse.of(502,
                    StatsPayload.oqrsResult(false, friendlyMessage(e)))));
        });
    }

    /** 按呼号读取本站日志（带缓存），供查询接口与 OQRS 记录校验共用 */
    private Mono<StatsPayload.SearchPayload> searchInLog(String callsign, int maxResults) {
        return fetchApi().flatMap(api -> {
            if (!api.isConfigured()) {
                return Mono.just(StatsPayload.searchError(
                    "未配置 Wavelog API 地址或 Token，请在插件「设置」中完成配置。"));
            }
            return cachedJson(cacheKey("search", api, callsign), api.cacheSecondsOrDefault(),
                wavelogClient.fetchQsosByCallsign(api, callsign, maxResults))
                .map(cached -> PayloadBuilder.buildSearchResult(callsign, cached.value()));
        });
    }

    /** 提交的每一条通联都必须能在本站日志的查询结果中找到 */
    private static boolean allQsosExist(List<StatsPayload.OqrsQso> submitted,
                                        List<StatsPayload.QsoRow> logged) {
        Set<String> known = new HashSet<>();
        for (StatsPayload.QsoRow row : logged) {
            known.add(qsoKey(row.date(), row.time(), row.band(), row.mode(), row.stationId()));
        }
        for (StatsPayload.OqrsQso qso : submitted) {
            if (!known.contains(qsoKey(qso.date(), qso.time(), qso.band(), qso.mode(),
                qso.stationId()))) {
                return false;
            }
        }
        return true;
    }

    /** 通联记录的比对键：日期 + 时间 + 频段 + 模式 + 电台位置（大小写与空白归一化） */
    private static String qsoKey(String date, String time, String band, String mode,
                                 long stationId) {
        return StringUtils.trimToEmpty(date) + '|'
            + StringUtils.trimToEmpty(time) + '|'
            + StringUtils.trimToEmpty(band).toUpperCase(Locale.ROOT) + '|'
            + StringUtils.trimToEmpty(mode).toUpperCase(Locale.ROOT) + '|'
            + stationId;
    }

    /** 重复提交指纹：呼号 + 邮箱 + 寄送方式 + 通联集合（与顺序无关） */
    static String oqrsFingerprint(String callsign, String email, String route,
                                  List<StatsPayload.OqrsQso> qsos) {
        List<String> keys = new ArrayList<>();
        for (StatsPayload.OqrsQso qso : qsos) {
            keys.add(qsoKey(qso.date(), qso.time(), qso.band(), qso.mode(), qso.stationId()));
        }
        java.util.Collections.sort(keys);
        String raw = StringUtils.upperCase(callsign) + '\n'
            + StringUtils.lowerCase(StringUtils.trimToEmpty(email)) + '\n'
            + StringUtils.upperCase(route) + '\n'
            + String.join(";", keys);
        try {
            return hexDigest(raw.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        } catch (Exception e) {
            return raw;
        }
    }

    /** /qso-stats 页面标题 */
    public Mono<String> pageTitle() {
        return fetchApi().map(WavelogSettings.Api::pageTitleOrDefault);
    }

    // ---------- 设置读取（SettingFetcher 自带缓存，配置变更自动刷新） ----------

    private Mono<WavelogSettings.Api> fetchApi() {
        return settingFetcher.fetch(GROUP_API, WavelogSettings.Api.class)
            .onErrorResume(e -> Mono.empty())
            .defaultIfEmpty(new WavelogSettings.Api(null, null, null, null, null));
    }

    private Mono<WavelogSettings.Stats> fetchStatsGroup() {
        return settingFetcher.fetch(GROUP_STATS, WavelogSettings.Stats.class)
            .onErrorResume(e -> Mono.empty())
            .defaultIfEmpty(new WavelogSettings.Stats(null));
    }

    private Mono<WavelogSettings.Display> fetchDisplay() {
        return settingFetcher.fetch(GROUP_DISPLAY, WavelogSettings.Display.class)
            .onErrorResume(e -> Mono.empty())
            .defaultIfEmpty(new WavelogSettings.Display(null, null, null, null, null, null,
                null, null, null));
    }

    /**
     * security 分组：公开接口的防滥用参数。
     *
     * <p>读取失败或未配置时回落到内置默认值（而非「不限制」），
     * 保证任何情况下公开写操作都处在限流保护之下。
     */
    private Mono<WavelogSettings.Security> fetchSecurity() {
        return settingFetcher.fetch(GROUP_SECURITY, WavelogSettings.Security.class)
            .onErrorResume(e -> Mono.empty())
            .defaultIfEmpty(new WavelogSettings.Security(null, null, null, null));
    }

    private Mono<WavelogSettings.Layout> fetchLayout() {
        return settingFetcher.fetch(GROUP_LAYOUT, WavelogSettings.Layout.class)
            .onErrorResume(e -> Mono.empty())
            .defaultIfEmpty(new WavelogSettings.Layout(null));
    }

    /** 读取布局配置；未配置时返回默认顺序（呼号查询最前） */
    private List<StatsPayload.PanelConfig> resolveLayout(List<WavelogSettings.Panel> panels) {
        if (panels == null || panels.isEmpty()) {
            return defaultLayout();
        }
        List<StatsPayload.PanelConfig> out = new ArrayList<>();
        for (WavelogSettings.Panel p : panels) {
            out.add(new StatsPayload.PanelConfig(StringUtils.defaultString(p.key()),
                p.enabledOrDefault(), p.spanOrDefault()));
        }
        return out;
    }

    private List<StatsPayload.PanelConfig> defaultLayout() {
        String[] keys = {"search", "kpi", "day", "month", "mode", "band", "year", "recent"};
        List<StatsPayload.PanelConfig> out = new ArrayList<>();
        for (String k : keys) {
            out.add(new StatsPayload.PanelConfig(k, true, "search".equals(k) || "kpi".equals(k) ? 2 : 1));
        }
        return out;
    }

    private Mono<StatsPayload.DisplayConfig> fetchDisplayConfig() {
        return fetchDisplay()
            .map(d -> new StatsPayload.DisplayConfig(d.sectionTitleOrDefault(),
                d.showSectionTitleOrDefault(), d.showUpdatedAtOrDefault(),
                d.fallbackTextOrDefault(), d.searchEnabledOrDefault(),
                d.searchMaxResultsOrDefault(), d.displayStyleOrDefault(),
                d.defaultThemeOrDefault(), d.oqrsEnabledOrDefault()));
    }

    private Mono<List<StatsPayload.Section>> buildSections(WavelogSettings.Api api,
                                                           JsonNode qsoNode,
                                                           WavelogSettings.Stats stats) {
        List<WavelogSettings.Item> items = stats.itemsOrDefault();
        boolean needRecent = items.stream()
            .anyMatch(item -> item.enabledOrDefault() && "recent".equals(item.key()));
        if (!needRecent) {
            return Mono.just(PayloadBuilder.buildSections(qsoNode, null, items));
        }
        return fetchRecent(api)
            .map(recentCache -> PayloadBuilder.buildSections(qsoNode,
                recentCache.value(), items))
            // 最近通联失败不拖垮整页，降级为空列表
            .onErrorResume(e -> Mono.just(PayloadBuilder.buildSections(qsoNode, null, items)));
    }

    // ---------- Wavelog 数据（变更检测缓存） ----------
    //
    // 缓存策略：按后台配置的 TTL 向 Wavelog 拉取数据，并对响应内容计算签名。
    //   - 若签名与缓存一致（数据无更新）：保留缓存的原始数据与「更新于」时间，
    //     仅延长有效期，前端展示的更新时间不会因刷新而跳动；
    //   - 若签名不一致（数据有更新）：更新缓存数据与「更新于」时间为当前时间；
    //   - 缓存有效期内直接命中，不再请求 Wavelog。

    private Mono<CachedJson> fetchStats(WavelogSettings.Api api) {
        return cachedJson(cacheKey("stats", api), api.cacheSecondsOrDefault(),
            wavelogClient.fetchStatistics(api));
    }

    private Mono<CachedJson> fetchRecent(WavelogSettings.Api api) {
        // 后台每个 recent 项目的 limit 上限为 50，这里统一拉取 50 条按需截取，便于缓存复用
        return cachedJson(cacheKey("recent", api), api.cacheSecondsOrDefault(),
            wavelogClient.fetchRecentQsos(api, 50));
    }


    /**
     * 分页拉取全部 QSO（newest first），聚合统计图表数据。
     * 结果按 API 缓存策略复用，避免高频请求日志平台。
     */
    private Mono<CachedRows> fetchAllQsoRows(WavelogSettings.Api api) {
        String key = cacheKey("qso-rows", api);
        long now = System.currentTimeMillis();
        long ttlMillis = api.cacheSecondsOrDefault() * 1000L;
        CacheListEntry entry = rowsCache.get(key);
        if (entry != null && entry.expiresAt() > now) {
            return Mono.just(new CachedRows(entry.rows(), entry.updatedAt()));
        }
        return fetchAllQsos(api).map(rows -> {
            String signature = signature(rows);
            if (entry != null && StringUtils.equals(entry.signature(), signature)) {
                // 数据无变化：保留原值与「更新于」时间，仅延长有效期
                CacheListEntry kept = new CacheListEntry(entry.rows(), entry.updatedAt(),
                    now + ttlMillis, signature);
                rowsCache.put(key, kept);
                return new CachedRows(kept.rows(), kept.updatedAt());
            }
            CacheListEntry fresh = new CacheListEntry(rows, Instant.now().toString(),
                now + ttlMillis, signature);
            rowsCache.put(key, fresh);
            return new CachedRows(fresh.rows(), fresh.updatedAt());
        });
    }

    private Mono<List<JsonNode>> fetchAllQsos(WavelogSettings.Api api) {
        return fetchQsosPageRecursive(api, 1, new ArrayList<>());
    }

    private Mono<List<JsonNode>> fetchQsosPageRecursive(WavelogSettings.Api api, int page,
                                                        List<JsonNode> acc) {
        if (page > DASHBOARD_MAX_PAGES) {
            return Mono.just(acc);
        }
        return wavelogClient.fetchQsosPage(api, page, DASHBOARD_PER_PAGE, null, null)
            .flatMap(node -> {
                JsonNode data = node.path("data");
                if (data != null && data.isArray()) {
                    for (JsonNode qso : data) {
                        acc.add(qso);
                    }
                }
                boolean hasMore = node.path("meta").path("has_more").asBoolean(false);
                return hasMore ? fetchQsosPageRecursive(api, page + 1, acc) : Mono.just(acc);
            });
    }

    /** KPI 取自 statistic 接口，图表数据取自 QSO 列表聚合结果 */
    private StatsPayload.Statistics buildStatistics(JsonNode qsoNode, List<JsonNode> rows) {
        JsonNode qso = qsoNode.path("data").path("qso");
        JsonNode activity = qso.path("activity");
        JsonNode dxcc = qso.path("dxcc");
        int year = LocalDate.now().getYear();
        StatsPayload.Statistics agg = StatsAggregator.aggregate(rows, year, LocalDate.now(), 10);
        return new StatsPayload.Statistics(
            year,
            qso.path("total").asLong(agg.total()),
            activity.path("today").asLong(0),
            activity.path("month").asLong(0),
            activity.path("year").asLong(0),
            dxcc.path("worked").asLong(0),
            dxcc.path("confirmed").asLong(0),
            dxcc.path("available").asLong(0),
            agg.byDay(), agg.byMonth(), agg.byYear(), agg.byBand(), agg.byMode());
    }

    private List<StatsPayload.RecentRow> buildRecent(List<JsonNode> rows, int limit) {
        List<StatsPayload.RecentRow> recent = new ArrayList<>();
        for (int i = 0; i < rows.size() && recent.size() < limit; i++) {
            JsonNode node = rows.get(i);
            recent.add(new StatsPayload.RecentRow(node.path("call").asText("—"),
                node.path("band").asText(""),
                node.path("mode").asText(""),
                PayloadBuilder.formatDateTime(node.path("qso_date").asText("")),
                node.path("gridsquare").asText("")));
        }
        return recent;
    }

    private String cacheKey(String prefix, WavelogSettings.Api api) {
        return prefix + "|" + api.baseUrlOrDefault() + "|" + api.apiTokenOrDefault();
    }

    private String cacheKey(String prefix, WavelogSettings.Api api, String callsign) {
        return cacheKey(prefix, api) + "|" + callsign;
    }

    /**
     * 变更检测缓存：TTL 内直接命中；TTL 到期后重新拉取 Wavelog 数据，
     * 通过签名比较判断数据是否更新——有更新才替换缓存值与「更新于」时间，
     * 无更新则保留原缓存（仅延长有效期）。
     */
    private Mono<CachedJson> cachedJson(String key, int ttlSeconds, Mono<JsonNode> loader) {
        long now = System.currentTimeMillis();
        long ttlMillis = ttlSeconds * 1000L;
        CacheEntry entry = cache.get(key);
        if (entry != null && entry.expiresAt() > now) {
            return Mono.just(new CachedJson(entry.value(), entry.updatedAt()));
        }
        return loader.map(node -> {
            String signature = signature(node);
            if (entry != null && StringUtils.equals(entry.signature(), signature)) {
                // API 数据无更新：保留原值与「更新于」时间，仅延长有效期
                CacheEntry kept = new CacheEntry(entry.value(), entry.updatedAt(),
                    now + ttlMillis, signature);
                cache.put(key, kept);
                return new CachedJson(kept.value(), kept.updatedAt());
            }
            CacheEntry fresh = new CacheEntry(node, Instant.now().toString(),
                now + ttlMillis, signature);
            cache.put(key, fresh);
            return new CachedJson(fresh.value(), fresh.updatedAt());
        });
    }

    /** 数据签名：对 Wavelog 原始响应的序列化内容计算 SHA-256，用于判断数据是否更新 */
    private static String signature(JsonNode node) {
        if (node == null) {
            return "null";
        }
        try {
            return hexDigest(JsonUtils.mapper().writeValueAsBytes(node));
        } catch (Exception e) {
            return String.valueOf(node.hashCode());
        }
    }

    private static String signature(List<JsonNode> rows) {
        if (rows == null) {
            return "null";
        }
        try {
            return hexDigest(JsonUtils.mapper().writeValueAsBytes(rows));
        } catch (Exception e) {
            return String.valueOf(rows.hashCode());
        }
    }

    private static String hexDigest(byte[] data) throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        byte[] digest = md.digest(data);
        StringBuilder sb = new StringBuilder(digest.length * 2);
        for (byte b : digest) {
            sb.append(Character.forDigit((b >> 4) & 0xF, 16));
            sb.append(Character.forDigit(b & 0xF, 16));
        }
        return sb.toString();
    }

    /** 取两个 ISO-8601 UTC 时间中较新的一个（同格式字符串可直接按字典序比较） */
    private static String latestUpdate(String a, String b) {
        if (StringUtils.isBlank(a)) {
            return b;
        }
        if (StringUtils.isBlank(b)) {
            return a;
        }
        return a.compareTo(b) >= 0 ? a : b;
    }

    /** 缓存条目：原始数据 + 数据实际变更时间 + 有效期 + 内容签名 */
    private record CacheEntry(JsonNode value, String updatedAt, long expiresAt,
                              String signature) {
    }

    /** QSO 行缓存条目 */
    private record CacheListEntry(List<JsonNode> rows, String updatedAt, long expiresAt,
                                  String signature) {
    }

    /** 变更检测缓存读取结果：数据值 + 数据实际变更时间（无更新时保留旧时间） */
    private record CachedJson(JsonNode value, String updatedAt) {
    }

    /** QSO 行缓存读取结果 */
    private record CachedRows(List<JsonNode> rows, String updatedAt) {
    }

    // ---------- 错误信息（不泄露 Token） ----------

    String friendlyMessage(Throwable e) {
        if (e instanceof WebClientResponseException wcre) {
            String body = wcre.getResponseBodyAsString();
            if (StringUtils.isNotBlank(body)) {
                try {
                    JsonNode err = JsonUtils.mapper().readTree(body);
                    String code = err.path("error").path("code").asText("");
                    String message = err.path("error").path("message").asText("");
                    // OQRS 等公开端点的校验错误形如 {"error":"..."}
                    if (StringUtils.isBlank(message)) {
                        message = err.path("error").asText("");
                    }
                    if (StringUtils.isNotBlank(message)) {
                        return StringUtils.isNotBlank(code)
                            ? "Wavelog 接口错误（" + code + "）：" + message
                            : "Wavelog 接口错误：" + message;
                    }
                } catch (Exception ignored) {
                    // 响应体不是 JSON，走通用文案
                }
            }
            return "Wavelog 接口返回 HTTP " + wcre.getStatusCode().value()
                + "（" + e.getClass().getSimpleName() + ": "
                + StringUtils.abbreviate(StringUtils.defaultString(wcre.getMessage()), 120) + "）";
        }
        String message = StringUtils.defaultString(e.getMessage());
        if (StringUtils.containsIgnoreCase(message, "timed out")) {
            return "请求 Wavelog 超时，请检查站点地址与网络连接";
        }
        if (StringUtils.containsIgnoreCase(message, "Connection refused")
            || StringUtils.containsIgnoreCase(message, "UnknownHost")) {
            return "无法连接 Wavelog 服务，请检查站点地址与网络连接";
        }
        return "操作失败：" + StringUtils.defaultIfBlank(message,
            e.getClass().getSimpleName());
    }
}