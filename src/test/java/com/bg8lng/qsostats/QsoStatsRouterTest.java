package com.bg8lng.qsostats;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.security.web.server.csrf.CsrfToken;
import org.springframework.security.web.server.csrf.DefaultCsrfToken;
import org.springframework.web.reactive.function.server.HandlerStrategies;
import org.springframework.web.reactive.function.server.ServerRequest;
import reactor.core.publisher.Mono;

/**
 * 路由层测试：CSRF 令牌下发与限流用的客户端标识解析。
 *
 * <p>这两处都直接影响公开接口的可用性与防滥用效果：
 * 令牌取不到 → 访客的 OQRS 申请会被 Spring Security 拒为 403；
 * 客户端标识解析错误 → 反向代理后所有访客共用一个限流桶。
 */
class QsoStatsRouterTest {

    private static ServerRequest request(MockServerHttpRequest httpRequest, CsrfToken token) {
        MockServerWebExchange exchange = MockServerWebExchange.from(httpRequest);
        if (token != null) {
            exchange.getAttributes().put(CsrfToken.class.getName(), Mono.just(token));
        }
        return ServerRequest.create(exchange, HandlerStrategies.withDefaults().messageReaders());
    }

    @Test
    void csrfBodyExposesTokenWhenCsrfProtectionIsActive() {
        CsrfToken token = new DefaultCsrfToken("X-XSRF-TOKEN", "_csrf", "token-value");
        Map<String, Object> body = QsoStatsRouter
            .csrfBody(request(MockServerHttpRequest.get("/qso-stats/api/csrf").build(), token))
            .block();

        assertEquals(true, body.get("required"));
        assertEquals("X-XSRF-TOKEN", body.get("headerName"));
        assertEquals("_csrf", body.get("parameterName"));
        assertEquals("token-value", body.get("token"));
    }

    @Test
    void csrfBodyReportsNotRequiredWhenFilterAbsent() {
        Map<String, Object> body = QsoStatsRouter
            .csrfBody(request(MockServerHttpRequest.get("/qso-stats/api/csrf").build(), null))
            .block();

        assertEquals(false, body.get("required"));
        assertFalse(body.containsKey("token"));
    }

    @Test
    void clientIdPrefersFirstForwardedForHop() {
        ServerRequest req = request(MockServerHttpRequest.get("/qso-stats/api/search")
            .header("X-Forwarded-For", "203.0.113.7, 70.41.3.18, 150.172.238.178")
            .header("X-Real-IP", "10.0.0.9")
            .build(), null);
        assertEquals("203.0.113.7", QsoStatsRouter.clientId(req));
    }

    @Test
    void clientIdFallsBackToRealIpThenRemoteAddress() {
        ServerRequest withRealIp = request(MockServerHttpRequest.get("/x")
            .header("X-Real-IP", "203.0.113.9").build(), null);
        assertEquals("203.0.113.9", QsoStatsRouter.clientId(withRealIp));

        ServerRequest bare = request(MockServerHttpRequest.get("/x").build(), null);
        // MockServerHttpRequest 无远端地址时回落到 unknown，不应抛异常
        assertTrue(QsoStatsRouter.clientId(bare) != null);
    }

    @Test
    void clientIdIgnoresBlankForwardedForAndTruncatesOverlongValues() {
        ServerRequest blank = request(MockServerHttpRequest.get("/x")
            .header("X-Forwarded-For", "   ")
            .header("X-Real-IP", "203.0.113.5").build(), null);
        assertEquals("203.0.113.5", QsoStatsRouter.clientId(blank));

        ServerRequest overlong = request(MockServerHttpRequest.get("/x")
            .header("X-Forwarded-For", "a".repeat(500)).build(), null);
        assertTrue(QsoStatsRouter.clientId(overlong).length() <= 64,
            "限流键长度必须有上限，避免被超长头部撑爆内存");
    }

    @Test
    void serverHttpRequestTypeIsAvailable() {
        ServerHttpRequest req = MockServerHttpRequest.get("/x").build();
        assertEquals("/x", req.getPath().value());
    }
}
