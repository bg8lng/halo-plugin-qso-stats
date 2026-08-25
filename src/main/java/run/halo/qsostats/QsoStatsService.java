package run.halo.qsostats;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
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
    private static final String GROUP_SEARCH = "search";
    private static final String GROUP_LAYOUT = "layout";

    private final ReactiveSettingFetcher settingFetcher;
    private final WavelogClient wavelogClient;
    private static final int DASHBOARD_PER_PAGE = 5000;
    private static final int DASHBOARD_MAX_PAGES = 20;

    private final Map<String, CacheEntry> cache = new ConcurrentHashMap<>();
    private final Map<String, CacheListEntry> rowsCache = new ConcurrentHashMap<>();

    public QsoStatsService(ReactiveSettingFetcher settingFetcher, WavelogClient wavelogClient) {
        this.settingFetcher = settingFetcher;
        this.wavelogClient = wavelogClient;
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
                    .flatMap(qsoNode -> fetchStatsGroup()
                        .flatMap(stats -> buildSections(api, qsoNode, stats)))
                    .map(sections -> StatsPayload.success(sections, Instant.now().toString(), config))
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
                    .flatMap(qsoNode -> fetchAllQsoRows(api)
                        .flatMap(rows -> fetchLayout().map(layout ->
                            new StatsPayload.DashboardPayload(
                                buildStatistics(qsoNode, rows),
                                buildRecent(rows, 10),
                                Instant.now().toString(),
                                null,
                                config.fallbackText(),
                                config.searchEnabled(),
                                config.searchMaxResults(),
                                resolveLayout(layout.panelsOrDefault())))))
                    .onErrorResume(e -> Mono.just(StatsPayload.dashboardError(friendlyMessage(e), config)));
            }));
    }

    /**
     * 按呼号查询通联记录（供 /qso-stats/api/search 使用）。
     *
     * <p>结果按缓存策略复用，避免高频请求日志平台。
     */
    public Mono<StatsPayload.SearchPayload> searchQsos(String callsign) {
        String call = StringUtils.trimToEmpty(callsign).toUpperCase(Locale.ROOT);
        if (StringUtils.isBlank(call)) {
            return Mono.just(StatsPayload.searchError("请输入要查询的呼号"));
        }
        return fetchApi().flatMap(api -> {
            if (!api.isConfigured()) {
                return Mono.just(StatsPayload.searchError(
                    "未配置 Wavelog API 地址或 Token，请在插件「设置」中完成配置。"));
            }
            return fetchSearch()
                .flatMap(search -> cached(cacheKey("search", api, call),
                    api.cacheSecondsOrDefault(),
                    wavelogClient.fetchQsosByCallsign(api, call, search.maxResultsOrDefault())))
                .map(node -> PayloadBuilder.buildSearchResult(call, node))
                .onErrorResume(e -> Mono.just(StatsPayload.searchError(friendlyMessage(e))));
        });
    }

    /**
     * 提交 OQRS 卡片申请（供 POST /qso-stats/api/oqrs 使用）。
     *
     * <p>不缓存，直接转发给 Wavelog 公开申请端点。
     */
    public Mono<StatsPayload.OqrsResult> submitOqrs(StatsPayload.OqrsSubmitRequest request) {
        if (StringUtils.isBlank(request.email())) {
            return Mono.just(StatsPayload.oqrsResult(false, "请填写您的邮箱地址"));
        }
        if (request.qsos() == null || request.qsos().isEmpty()) {
            return Mono.just(StatsPayload.oqrsResult(false, "没有可提交的通联记录"));
        }
        return fetchApi().flatMap(api -> {
            if (!api.isConfigured()) {
                return Mono.just(StatsPayload.oqrsResult(false,
                    "未配置 Wavelog API 地址或 Token，请在插件「设置」中完成配置。"));
            }
            return wavelogClient.submitOqrsRequest(api,
                    StringUtils.trimToEmpty(request.callsign()).toUpperCase(Locale.ROOT),
                    StringUtils.trimToEmpty(request.email()),
                    StringUtils.trimToEmpty(request.message()),
                    StringUtils.defaultIfBlank(request.qslroute(), "B"),
                    request.qsos())
                .thenReturn(StatsPayload.oqrsResult(true, "OQRS 卡片申请已提交，感谢使用！"))
                .onErrorResume(e -> Mono.just(StatsPayload.oqrsResult(false, friendlyMessage(e))));
        });
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
            .defaultIfEmpty(new WavelogSettings.Display(null, null, null, null));
    }

    private Mono<WavelogSettings.Search> fetchSearch() {
        return settingFetcher.fetch(GROUP_SEARCH, WavelogSettings.Search.class)
            .onErrorResume(e -> Mono.empty())
            .defaultIfEmpty(new WavelogSettings.Search(null, null));
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
        return fetchDisplay().zipWith(fetchSearch())
            .map(t -> new StatsPayload.DisplayConfig(t.getT1().sectionTitleOrDefault(),
                t.getT1().showSectionTitleOrDefault(), t.getT1().showUpdatedAtOrDefault(),
                t.getT1().fallbackTextOrDefault(), t.getT2().enabledOrDefault(),
                t.getT2().maxResultsOrDefault()));
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
            .map(recentNode -> PayloadBuilder.buildSections(qsoNode, recentNode, items))
            // 最近通联失败不拖垮整页，降级为空列表
            .onErrorResume(e -> Mono.just(PayloadBuilder.buildSections(qsoNode, null, items)));
    }

    // ---------- Wavelog 数据（TTL 缓存，避免高频请求日志平台） ----------

    private Mono<JsonNode> fetchStats(WavelogSettings.Api api) {
        return cached(cacheKey("stats", api), api.cacheSecondsOrDefault(),
            wavelogClient.fetchStatistics(api));
    }

    private Mono<JsonNode> fetchRecent(WavelogSettings.Api api) {
        // 后台每个 recent 项目的 limit 上限为 50，这里统一拉取 50 条按需截取，便于缓存复用
        return cached(cacheKey("recent", api), api.cacheSecondsOrDefault(),
            wavelogClient.fetchRecentQsos(api, 50));
    }


    /**
     * 分页拉取全部 QSO（newest first），聚合统计图表数据。
     * 结果按 API 缓存策略复用，避免高频请求日志平台。
     */
    private Mono<List<JsonNode>> fetchAllQsoRows(WavelogSettings.Api api) {
        String key = cacheKey("qso-rows", api);
        long now = System.currentTimeMillis();
        CacheListEntry entry = rowsCache.get(key);
        if (entry != null && entry.expiresAt() > now) {
            return Mono.just(entry.rows());
        }
        return fetchAllQsos(api)
            .doOnNext(rows -> rowsCache.put(key,
                new CacheListEntry(rows, now + api.cacheSecondsOrDefault() * 1000L)));
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

    private Mono<JsonNode> cached(String key, int ttlSeconds, Mono<JsonNode> loader) {
        CacheEntry entry = cache.get(key);
        long now = System.currentTimeMillis();
        if (entry != null && entry.expiresAt() > now) {
            return Mono.just(entry.value());
        }
        return loader.doOnNext(node -> cache.put(key,
            new CacheEntry(node, now + ttlSeconds * 1000L)));
    }

    private record CacheEntry(JsonNode value, long expiresAt) {
    }

    private record CacheListEntry(List<JsonNode> rows, long expiresAt) {
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
        return "操作失败：" + StringUtils.defaultString(message,
            e.getClass().getSimpleName());
    }
}