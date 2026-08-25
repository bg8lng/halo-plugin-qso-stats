package run.halo.qsostats;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.Duration;
import java.util.List;
import org.apache.commons.lang3.StringUtils;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.reactive.function.BodyInserters;
import org.springframework.web.reactive.function.client.ExchangeStrategies;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.util.UriBuilder;
import reactor.core.publisher.Mono;
import run.halo.app.infra.utils.JsonUtils;

/**
 * Wavelog API v2 客户端。
 *
 * <p>接口文档：https://docs.wavelog.org/developer/api-v2/
 * 认证方式：Authorization: Bearer wl2_xxx（需 statistic:read / qso:read 权限）。
 *
 * <p>注意：Halo 2.26 起核心切换到 Jackson 3，WebClient 的默认解码器无法直接
 * 反序列化为 Jackson 2 的 {@link JsonNode}，因此这里统一以字符串接收响应，
 * 再通过 {@link JsonUtils#mapper()}（各版本均返回 Jackson 2 的 ObjectMapper）解析，
 * 保证插件在 Halo 2.20 ~ 2.26 全版本可运行。
 */
@Component
public class WavelogClient {

    private static final String STATISTIC_PATH = "/api/v2/statistic";
    private static final String QSO_PATH = "/api/v2/qso";


    /**
     * 分页拉取 QSO 列表（newest first）。
     *
     * <p>支持 qso_since / qso_until 日期过滤（含当天，闭区间）；响应 meta 携带
     * has_more 供调用方翻页，直至拉取全部数据。
     */
    public Mono<JsonNode> fetchQsosPage(WavelogSettings.Api api, int page, int perPage,
                                        String since, String until) {
        return webClient(api).get()
            .uri(uri -> {
                UriBuilder b = uri.path(QSO_PATH)
                    .queryParam("page", page)
                    .queryParam("per_page", perPage);
                if (StringUtils.isNotBlank(since)) {
                    b = b.queryParam("qso_since", since);
                }
                if (StringUtils.isNotBlank(until)) {
                    b = b.queryParam("qso_until", until);
                }
                return b.build();
            })
            .retrieve()
            .bodyToMono(String.class)
            .map(WavelogClient::parseJson);
    }

    /** OQRS 公开申请端点（非 API v2；Wavelog 默认关闭 CSRF，可直接 POST 表单） */
    private static final String OQRS_SAVE_PATH = "/oqrs/save_oqrs_request_grouped";

    /** 拉取统计（profile=qso，包含总数、活跃度、波段/模式分布、DXCC） */
    public Mono<JsonNode> fetchStatistics(WavelogSettings.Api api) {
        return webClient(api).get()
            .uri(uri -> uri.path(STATISTIC_PATH).queryParam("profile", "qso").build())
            .retrieve()
            .bodyToMono(String.class)
            .map(WavelogClient::parseJson);
    }

    /** 拉取最近 N 条通联（newest first） */
    public Mono<JsonNode> fetchRecentQsos(WavelogSettings.Api api, int limit) {
        return webClient(api).get()
            .uri(uri -> uri.path(QSO_PATH).queryParam("limit", limit).build())
            .retrieve()
            .bodyToMono(String.class)
            .map(WavelogClient::parseJson);
    }

    /** 按呼号精确查询通联（newest first，大小写不敏感） */
    public Mono<JsonNode> fetchQsosByCallsign(WavelogSettings.Api api, String callsign,
                                              int limit) {
        return webClient(api).get()
            .uri(uri -> uri.path(QSO_PATH)
                .queryParam("callsign", callsign)
                .queryParam("limit", limit)
                .build())
            .retrieve()
            .bodyToMono(String.class)
            .map(WavelogClient::parseJson);
    }

    /**
     * 向 Wavelog 提交 OQRS 卡片申请（公开端点，无需 Token）。
     *
     * <p>使用 grouped 接口，逐条 QSO 携带 station_id；表单字段与
     * Wavelog 前端 {@code oqrs/save_oqrs_request_grouped} 一致：
     * {@code qsos[i] = [date, time, band, mode, station_id]}。
     *
     * @return 成功时完成；失败时抛出 {@link org.springframework.web.reactive.function.client.WebClientResponseException}
     */
    public Mono<Void> submitOqrsRequest(WavelogSettings.Api api, String callsign, String email,
                                        String message, String qslroute,
                                        List<StatsPayload.OqrsQso> qsos) {
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("callsign", StringUtils.trimToEmpty(callsign));
        form.add("email", StringUtils.trimToEmpty(email));
        form.add("message", StringUtils.trimToEmpty(message));
        form.add("qslroute", StringUtils.defaultIfBlank(qslroute, "B"));
        for (int i = 0; i < qsos.size(); i++) {
            StatsPayload.OqrsQso qso = qsos.get(i);
            form.add("qsos[" + i + "][0]", StringUtils.trimToEmpty(qso.date()));
            form.add("qsos[" + i + "][1]", StringUtils.trimToEmpty(qso.time()));
            form.add("qsos[" + i + "][2]", StringUtils.trimToEmpty(qso.band()));
            form.add("qsos[" + i + "][3]", StringUtils.trimToEmpty(qso.mode()));
            form.add("qsos[" + i + "][4]", String.valueOf(qso.stationId()));
        }
        return webClient(api).post()
            .uri(uri -> uri.path(OQRS_SAVE_PATH).build())
            .contentType(MediaType.APPLICATION_FORM_URLENCODED)
            .body(BodyInserters.fromFormData(form))
            .retrieve()
            .bodyToMono(Void.class);
    }

    private static JsonNode parseJson(String body) {
        try {
            return JsonUtils.mapper().readTree(body);
        } catch (Exception e) {
            throw new WavelogResponseParseException("无法解析 Wavelog 响应", e);
        }
    }

    private WebClient webClient(WavelogSettings.Api api) {
        return WebClient.builder()
            .baseUrl(normalizeBase(api.baseUrlOrDefault()))
            .defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + api.apiTokenOrDefault())
            .defaultHeader(HttpHeaders.ACCEPT, MediaType.APPLICATION_JSON_VALUE)
            // 拉取全部 QSO 的响应可能达到数 MB（如 5000 条/页），放宽内存缓冲上限，
            // 避免超出 WebFlux 默认 256KB 导致 DataBufferLimitException
            .exchangeStrategies(ExchangeStrategies.builder()
                .codecs(codecs -> codecs.defaultCodecs().maxInMemorySize(16 * 1024 * 1024))
                .build())
            // 通过 filter 统一设置请求/响应超时，避免依赖底层连接器实现
            .filter((request, next) -> next.exchange(request)
                .timeout(Duration.ofSeconds(api.timeoutSecondsOrDefault())))
            .build();
    }

    /**
     * 归一化站点地址：用户可填写 {@code https://log.example.com} 或
     * {@code https://log.example.com/index.php}，统一为带 {@code /index.php} 的形式，
     * 与 {@link UriBuilder} 拼接的 {@code /api/v2/...} 路径一致。
     */
    static String normalizeBase(String base) {
        String b = StringUtils.defaultString(base).trim();
        if (StringUtils.isBlank(b)) {
            return b;
        }
        b = StringUtils.removeEnd(b, "/");
        return StringUtils.endsWithIgnoreCase(b, "/index.php") ? b : b + "/index.php";
    }
}