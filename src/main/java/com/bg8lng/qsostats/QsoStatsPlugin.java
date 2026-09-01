package com.bg8lng.qsostats;

import org.springframework.stereotype.Component;
import run.halo.app.plugin.BasePlugin;
import run.halo.app.plugin.PluginContext;

/**
 * 通联统计插件入口。
 *
 * <p>负责 Wavelog 通联统计的展示：通过 {@link QsoStatsRouter} 提供
 * /qso-stats 统计页面与 /qso-stats/api/statistics JSON 接口，通过
 * {@link QsoStatsHeadProcessor} 向主题页面注入展示组件所需的静态资源。
 *
 * <p>配置项（Wavelog API 地址/Token、统计项目、展示设置）由 Setting 扩展
 * （resources/extensions/settings.yaml）声明，Halo 控制台会自动生成设置表单。
 *
 * @author BG8LNG
 */
@Component
public class QsoStatsPlugin extends BasePlugin {

    public QsoStatsPlugin(PluginContext pluginContext) {
        super(pluginContext);
    }

    @Override
    public void start() {
        // 无需额外初始化，配置与资源均由 Halo 托管
    }

    @Override
    public void stop() {
        // 清理工作由 Halo 完成
    }
}
