package run.halo.qsostats;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import reactor.core.publisher.Mono;
import run.halo.app.infra.utils.JsonUtils;
import run.halo.app.plugin.ReactiveSettingFetcher;

/**
 * 通联统计数据服务：读取插件配置，拉取并缓存 Wavelog 数据，构建前台组件载荷。
 */
@Service
public class QsoStatsService {

    private static final String GROUP_API = "api";
    private static final String GROUP_STATS = "stats";
    private static final String GROUP_DISPLAY = "display";

    private final ReactiveSettingFetcher settingFetcher;
    private final WavelogClient wavelogClient;
    private final Map<String, CacheEntry> cache = new ConcurrentHashMap<>();

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
        return fetchDisplay()
            .flatMap(display -> fetchApi().flatMap(api -> {
                StatsPayload.DisplayConfig config = displayConfig(display);
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

    private static StatsPayload.DisplayConfig displayConfig(WavelogSettings.Display display) {
        return new StatsPayload.DisplayConfig(display.sectionTitleOrDefault(),
            display.showSectionTitleOrDefault(), display.showUpdatedAtOrDefault(),
            display.fallbackTextOrDefault());
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

    private String cacheKey(String prefix, WavelogSettings.Api api) {
        return prefix + "|" + api.baseUrlOrDefault() + "|" + api.apiTokenOrDefault();
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

    // ---------- 错误信息（不泄露 Token） ----------

    String friendlyMessage(Throwable e) {
        if (e instanceof WebClientResponseException wcre) {
            String body = wcre.getResponseBodyAsString();
            if (StringUtils.isNotBlank(body)) {
                try {
                    JsonNode err = JsonUtils.mapper().readTree(body);
                    String code = err.path("error").path("code").asText("");
                    String message = err.path("error").path("message").asText("");
                    if (StringUtils.isNotBlank(message)) {
                        return StringUtils.isNotBlank(code)
                            ? "Wavelog 接口错误（" + code + "）：" + message
                            : "Wavelog 接口错误：" + message;
                    }
                } catch (Exception ignored) {
                    // 响应体不是 JSON，走通用文案
                }
            }
            return "Wavelog 接口返回 HTTP " + wcre.getStatusCode().value();
        }
        String message = StringUtils.defaultString(e.getMessage());
        if (StringUtils.containsIgnoreCase(message, "timed out")) {
            return "请求 Wavelog 超时，请检查站点地址与网络连接";
        }
        if (StringUtils.containsIgnoreCase(message, "Connection refused")
            || StringUtils.containsIgnoreCase(message, "UnknownHost")) {
            return "无法连接 Wavelog 服务，请检查站点地址与网络连接";
        }
        return "获取通联统计失败：" + StringUtils.defaultString(message,
            e.getClass().getSimpleName());
    }
}
