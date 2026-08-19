package run.halo.qsostats;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.Duration;
import org.apache.commons.lang3.StringUtils;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
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
