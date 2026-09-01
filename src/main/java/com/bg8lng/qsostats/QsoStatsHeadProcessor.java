package com.bg8lng.qsostats;

import org.springframework.stereotype.Component;
import org.thymeleaf.context.ITemplateContext;
import org.thymeleaf.model.IModel;
import org.thymeleaf.model.IModelFactory;
import org.thymeleaf.processor.element.IElementModelStructureHandler;
import reactor.core.publisher.Mono;
import run.halo.app.plugin.PluginContext;
import run.halo.app.theme.dialect.TemplateHeadProcessor;

/**
 * 向所有主题页面注入通联统计组件的静态资源。
 *
 * <p>前端任意位置放置 {@code <div class="qso-stats-widget"></div>} 即可渲染组件；
 * JS 与 CSS 由本处理器统一注入，无需手工添加。
 */
@Component
public class QsoStatsHeadProcessor implements TemplateHeadProcessor {

    private final PluginContext pluginContext;

    public QsoStatsHeadProcessor(PluginContext pluginContext) {
        this.pluginContext = pluginContext;
    }

    @Override
    public Mono<Void> process(ITemplateContext context, IModel model,
                              IElementModelStructureHandler structureHandler) {
        IModelFactory modelFactory = context.getModelFactory();
        String version = pluginContext.getVersion();
        model.add(modelFactory.createText("""
            <!-- QsoStats widget start -->
            <link rel="stylesheet" href="/plugins/QsoStats/assets/static/qso-stats.css?version=%s" />
            <script src="/plugins/QsoStats/assets/static/qso-stats.js?version=%s" defer></script>
            <!-- QsoStats widget end -->
            """.formatted(version, version)));
        return Mono.empty();
    }
}
