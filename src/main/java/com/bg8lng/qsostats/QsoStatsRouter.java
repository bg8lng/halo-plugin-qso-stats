package com.bg8lng.qsostats;

import static org.springframework.web.reactive.function.server.RequestPredicates.GET;
import static org.springframework.web.reactive.function.server.RequestPredicates.POST;
import static org.springframework.web.reactive.function.server.RouterFunctions.route;

import java.net.InetSocketAddress;
import java.util.Map;
import org.apache.commons.lang3.StringUtils;
import org.springframework.context.annotation.Bean;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.web.server.csrf.CsrfToken;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.server.RouterFunction;
import org.springframework.web.reactive.function.server.ServerRequest;
import org.springframework.web.reactive.function.server.ServerResponse;
import reactor.core.publisher.Mono;
import run.halo.app.infra.utils.JsonUtils;
import run.halo.app.plugin.PluginContext;
import run.halo.app.theme.TemplateNameResolver;

/**
 * 前台路由：
 * <ul>
 *   <li>GET /qso-stats/api/statistics —— 展示组件的数据接口（公开，无需认证）</li>
 *   <li>GET /qso-stats/api/search —— 按呼号查询通联（公开，受开关与限流控制）</li>
 *   <li>GET /qso-stats/api/csrf —— 下发 CSRF 令牌，供 OQRS 提交使用（公开只读）</li>
 *   <li>POST /qso-stats/api/oqrs —— 提交 OQRS 卡片申请（公开写操作，受开关、
 *       限流、记录校验与防重复提交控制）</li>
 *   <li>GET /qso-stats —— 独立统计页面</li>
 * </ul>
 *
 * <p>search 与 oqrs 的访问控制全部在服务端完成：后台关闭「启用呼号查询与 OQRS」
 * 后，即使直接调用接口也会得到 403，不会返回任何日志数据或转发写请求。
 * 具体状态码：403 功能关闭、400 参数非法、409 重复提交、429 频率超限、
 * 502 上游 Wavelog 异常。
 *
 * <p>页面模板通过 {@link TemplateNameResolver} 解析：主题提供同名模板
 * （templates/qso-stats.html）时优先使用主题模板，否则使用插件自带模板。
 * Halo ≥ 2.26 时自带模板通过 {@code layout :: html} 复用主题布局
 * （qso-stats.html）；老版本使用自带外壳（qso-stats-standalone.html）。
 */
@Component
public class QsoStatsRouter {

    /** Halo 2.26 起核心内置页面布局契约（run.halo.app.theme.PageLayoutContract） */
    private static final boolean PAGE_LAYOUT_SUPPORTED = isPageLayoutSupported();

    /** OQRS 请求体长度上限（字符），超出直接判为非法请求 */
    private static final int MAX_OQRS_BODY_LENGTH = 64 * 1024;

    private final QsoStatsService qsoStatsService;
    private final TemplateNameResolver templateNameResolver;
    private final PluginContext pluginContext;

    public QsoStatsRouter(QsoStatsService qsoStatsService,
                          TemplateNameResolver templateNameResolver,
                          PluginContext pluginContext) {
        this.qsoStatsService = qsoStatsService;
        this.templateNameResolver = templateNameResolver;
        this.pluginContext = pluginContext;
    }

    @Bean
    RouterFunction<ServerResponse> qsoStatsRouterFunction() {
        return route(GET("/qso-stats/api/statistics"), this::statistics)
            .andRoute(GET("/qso-stats/api/dashboard"), this::dashboard)
            .andRoute(GET("/qso-stats/api/search"), this::search)
            .andRoute(GET("/qso-stats/api/csrf"), this::csrf)
            .andRoute(POST("/qso-stats/api/oqrs"), this::oqrs)
            .andRoute(GET("/qso-stats"), this::page);
    }

    private Mono<ServerResponse> statistics(ServerRequest request) {
        return qsoStatsService.buildPayload()
            .flatMap(payload -> ServerResponse.ok()
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(payload));
    }

    private Mono<ServerResponse> dashboard(ServerRequest request) {
        return qsoStatsService.buildDashboard()
            .flatMap(payload -> ServerResponse.ok()
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(payload));
    }

    private Mono<ServerResponse> search(ServerRequest request) {
        String callsign = request.queryParam("callsign").orElse("");
        return qsoStatsService.searchQsos(callsign, clientId(request))
            .flatMap(QsoStatsRouter::toResponse);
    }

    private Mono<ServerResponse> oqrs(ServerRequest request) {
        String client = clientId(request);
        // 与 WavelogClient 一致：以字符串接收并手动解析，兼容 Halo 2.26 的 Jackson 3 核心
        return request.bodyToMono(String.class)
            // 请求体上限，避免公开写接口被超大报文拖垮
            .filter(body -> body.length() <= MAX_OQRS_BODY_LENGTH)
            .map(QsoStatsRouter::parseOqrsRequest)
            .flatMap(req -> qsoStatsService.submitOqrs(req, client))
            .defaultIfEmpty(StatsPayload.ApiResponse.of(400,
                StatsPayload.oqrsResult(false, "请求参数解析失败，请检查后重试")))
            .onErrorResume(e -> Mono.just(StatsPayload.ApiResponse.of(400,
                StatsPayload.oqrsResult(false, "请求参数解析失败，请检查后重试"))))
            .flatMap(QsoStatsRouter::toResponse);
    }

    /**
     * 下发本次会话的 CSRF 令牌，供前端提交 OQRS 时携带。
     *
     * <p><b>为什么需要它</b>：插件的自定义路径（{@code /qso-stats/**}）不在 Halo 的
     * CSRF 豁免前缀（{@code /apis/**}、{@code /api/**}）内，因此 {@code POST /qso-stats/api/oqrs}
     * 受 Spring Security CSRF 保护。常规做法是前端从 {@code XSRF-TOKEN} Cookie 读取令牌，
     * 但当站点（或其反向代理，如 Nginx 的 {@code proxy_cookie_flags ~ httponly}）把该 Cookie
     * 标记为 HttpOnly 时，JavaScript 无法读取，访客的申请会一律得到 403 Access Denied。
     * 这里由服务端把令牌回给同源前端，兼容两种部署。
     *
     * <p><b>安全性</b>：跨源页面受同源策略限制无法读取本响应体（插件不输出任何 CORS 头），
     * 因此不会削弱 CSRF 防护；令牌本身与访客会话绑定，且本接口为只读 GET。
     */
    private Mono<ServerResponse> csrf(ServerRequest request) {
        return csrfBody(request).flatMap(body -> ServerResponse.ok()
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(body));
    }

    /** 从 exchange 属性中取出本次会话的 CSRF 令牌（拆分出来便于单元测试） */
    static Mono<Map<String, Object>> csrfBody(ServerRequest request) {
        Mono<CsrfToken> tokenMono = request.exchange().getAttribute(CsrfToken.class.getName());
        if (tokenMono == null) {
            // 站点未启用 CSRF 保护（或过滤器未注册）：前端无需附加令牌
            return Mono.just(Map.of("required", false));
        }
        return tokenMono
            .<Map<String, Object>>map(token -> Map.of(
                "required", true,
                "headerName", token.getHeaderName(),
                "parameterName", token.getParameterName(),
                "token", token.getToken()))
            .defaultIfEmpty(Map.of("required", false));
    }

    /** 统一按业务结果设置 HTTP 状态码，便于调用方与审计区分「关闭 / 限流 / 失败」 */
    private static Mono<ServerResponse> toResponse(StatsPayload.ApiResponse<?> result) {
        return ServerResponse.status(result.status())
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(result.body());
    }

    /**
     * 客户端标识（用于频率限制）：优先取反向代理透传的 X-Forwarded-For 首段，
     * 其次 X-Real-IP，最后回落到连接的远端地址。
     *
     * <p>Halo 通常部署在 Nginx / Caddy 之后，请确保反向代理正确设置这两个头部，
     * 否则所有访客会共用同一个限流桶。
     */
    static String clientId(ServerRequest request) {
        HttpHeaders headers = request.headers().asHttpHeaders();
        String forwarded = headers.getFirst("X-Forwarded-For");
        if (StringUtils.isNotBlank(forwarded)) {
            String first = StringUtils.trimToEmpty(StringUtils.split(forwarded, ',')[0]);
            if (StringUtils.isNotBlank(first)) {
                return StringUtils.abbreviate(first, 64);
            }
        }
        String realIp = headers.getFirst("X-Real-IP");
        if (StringUtils.isNotBlank(realIp)) {
            return StringUtils.abbreviate(StringUtils.trimToEmpty(realIp), 64);
        }
        return request.remoteAddress()
            .map(InetSocketAddress::getAddress)
            .map(address -> address == null ? "unknown" : address.getHostAddress())
            .orElse("unknown");
    }

    private static StatsPayload.OqrsSubmitRequest parseOqrsRequest(String body) {
        try {
            return JsonUtils.mapper().readValue(body, StatsPayload.OqrsSubmitRequest.class);
        } catch (Exception e) {
            throw new IllegalArgumentException("OQRS 请求体解析失败", e);
        }
    }

    private Mono<ServerResponse> page(ServerRequest request) {
        String viewName = PAGE_LAYOUT_SUPPORTED ? "qso-stats" : "qso-stats-standalone";
        return templateNameResolver.resolveTemplateNameOrDefault(request.exchange(), viewName)
            .flatMap(name -> qsoStatsService.pageTitle()
                .flatMap(title -> ServerResponse.ok().render(name, Map.of(
                    "title", title,
                    // 供模板中的 echarts.min.js 等静态资源携带版本号，避免 CDN 缓存旧版
                    "version", pluginContext.getVersion()))));
    }

    private static boolean isPageLayoutSupported() {
        try {
            Class.forName("run.halo.app.theme.PageLayoutContract");
            return true;
        } catch (ClassNotFoundException e) {
            return false;
        }
    }
}