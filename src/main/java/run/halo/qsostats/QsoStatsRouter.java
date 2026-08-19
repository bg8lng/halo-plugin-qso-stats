package run.halo.qsostats;

import static org.springframework.web.reactive.function.server.RequestPredicates.GET;
import static org.springframework.web.reactive.function.server.RequestPredicates.POST;
import static org.springframework.web.reactive.function.server.RouterFunctions.route;

import java.util.Map;
import org.springframework.context.annotation.Bean;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.server.RouterFunction;
import org.springframework.web.reactive.function.server.ServerRequest;
import org.springframework.web.reactive.function.server.ServerResponse;
import reactor.core.publisher.Mono;
import run.halo.app.infra.utils.JsonUtils;
import run.halo.app.theme.TemplateNameResolver;

/**
 * 前台路由：
 * <ul>
 *   <li>GET /qso-stats/api/statistics —— 展示组件的数据接口（公开，无需认证）</li>
 *   <li>GET /qso-stats/api/search —— 按呼号查询通联（公开）</li>
 *   <li>POST /qso-stats/api/oqrs —— 提交 OQRS 卡片申请（公开）</li>
 *   <li>GET /qso-stats —— 独立统计页面</li>
 * </ul>
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

    private final QsoStatsService qsoStatsService;
    private final TemplateNameResolver templateNameResolver;

    public QsoStatsRouter(QsoStatsService qsoStatsService,
                          TemplateNameResolver templateNameResolver) {
        this.qsoStatsService = qsoStatsService;
        this.templateNameResolver = templateNameResolver;
    }

    @Bean
    RouterFunction<ServerResponse> qsoStatsRouterFunction() {
        return route(GET("/qso-stats/api/statistics"), this::statistics)
            .andRoute(GET("/qso-stats/api/search"), this::search)
            .andRoute(POST("/qso-stats/api/oqrs"), this::oqrs)
            .andRoute(GET("/qso-stats"), this::page);
    }

    private Mono<ServerResponse> statistics(ServerRequest request) {
        return qsoStatsService.buildPayload()
            .flatMap(payload -> ServerResponse.ok()
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(payload));
    }

    private Mono<ServerResponse> search(ServerRequest request) {
        String callsign = request.queryParam("callsign").orElse("");
        return qsoStatsService.searchQsos(callsign)
            .flatMap(payload -> ServerResponse.ok()
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(payload));
    }

    private Mono<ServerResponse> oqrs(ServerRequest request) {
        // 与 WavelogClient 一致：以字符串接收并手动解析，兼容 Halo 2.26 的 Jackson 3 核心
        return request.bodyToMono(String.class)
            .map(body -> parseOqrsRequest(body))
            .defaultIfEmpty(null)
            .flatMap(req -> {
                if (req == null) {
                    return Mono.just(StatsPayload.oqrsResult(false, "请求参数解析失败，请稍后再试"));
                }
                return qsoStatsService.submitOqrs(req);
            })
            .flatMap(result -> ServerResponse.ok()
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(result));
    }

    private StatsPayload.OqrsSubmitRequest parseOqrsRequest(String body) {
        try {
            return JsonUtils.mapper().readValue(body, StatsPayload.OqrsSubmitRequest.class);
        } catch (Exception e) {
            return null;
        }
    }

    private Mono<ServerResponse> page(ServerRequest request) {
        String viewName = PAGE_LAYOUT_SUPPORTED ? "qso-stats" : "qso-stats-standalone";
        return templateNameResolver.resolveTemplateNameOrDefault(request.exchange(), viewName)
            .flatMap(name -> qsoStatsService.pageTitle()
                .flatMap(title -> ServerResponse.ok().render(name, Map.of("title", title))));
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
