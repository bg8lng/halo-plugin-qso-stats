package com.bg8lng.qsostats;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.List;
import org.apache.commons.lang3.StringUtils;

/**
 * 插件设置模型，对应 resources/extensions/settings.yaml 中的分组：
 * api（Wavelog API 配置）、stats（统计项目）、display（展示与交互）、layout（页面布局）。
 *
 * <p>「呼号查询与 OQRS」已并入 display（展示与交互）分组，字段为 searchEnabled /
 * searchMaxResults；display 分组另含数据展示样式 displayStyle（modern / classic）
 * 与默认主题 defaultTheme（auto / light / dark）。
 *
 * <p>由 {@link run.halo.app.plugin.ReactiveSettingFetcher} 按分组反序列化，
 * 字段名与表单 name 一致。
 */
public final class WavelogSettings {

    private WavelogSettings() {
    }

    /** api 分组：Wavelog API 配置 */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Api(String baseUrl, String apiToken, Integer cacheSeconds,
                      Integer timeoutSeconds, String pageTitle) {

        public String baseUrlOrDefault() {
            return StringUtils.defaultString(baseUrl).trim();
        }

        public String apiTokenOrDefault() {
            return StringUtils.defaultString(apiToken).trim();
        }

        public int cacheSecondsOrDefault() {
            return cacheSeconds != null && cacheSeconds > 0 ? cacheSeconds : 300;
        }

        public int timeoutSecondsOrDefault() {
            return timeoutSeconds != null && timeoutSeconds > 0 ? timeoutSeconds : 10;
        }

        public String pageTitleOrDefault() {
            return StringUtils.defaultIfBlank(pageTitle, "通联统计");
        }

        public boolean isConfigured() {
            return StringUtils.isNotBlank(baseUrlOrDefault())
                && StringUtils.isNotBlank(apiTokenOrDefault());
        }
    }

    /** stats 分组：展示的统计项目 */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Stats(List<Item> items) {

        public List<Item> itemsOrDefault() {
            return items == null ? List.of() : items;
        }
    }

    /** display 分组：展示与交互（含数据展示样式、默认主题、呼号查询与 OQRS 开关） */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Display(Boolean searchEnabled, Integer searchMaxResults,
                          Boolean showSectionTitle, String sectionTitle,
                          Boolean showUpdatedAt, String fallbackText,
                          String displayStyle, String defaultTheme,
                          Boolean oqrsEnabled) {

        public boolean searchEnabledOrDefault() {
            return searchEnabled == null || searchEnabled;
        }

        /**
         * OQRS 写操作是否启用。
         *
         * <p>OQRS 依附于呼号查询：总开关关闭时 OQRS 一并关闭，
         * 服务端据此拒绝 {@code POST /qso-stats/api/oqrs}。
         */
        public boolean oqrsEnabledOrDefault() {
            return searchEnabledOrDefault() && (oqrsEnabled == null || oqrsEnabled);
        }

        public int searchMaxResultsOrDefault() {
            return searchMaxResults != null && searchMaxResults > 0 ? searchMaxResults : 50;
        }

        public boolean showSectionTitleOrDefault() {
            return showSectionTitle == null || showSectionTitle;
        }

        public String sectionTitleOrDefault() {
            return StringUtils.defaultIfBlank(sectionTitle, "通联统计");
        }

        public boolean showUpdatedAtOrDefault() {
            return showUpdatedAt == null || showUpdatedAt;
        }

        public String fallbackTextOrDefault() {
            return StringUtils.defaultIfBlank(fallbackText, "统计数据暂不可用，请稍后再试");
        }

        public String displayStyleOrDefault() {
            return StringUtils.defaultIfBlank(displayStyle, "modern");
        }

        public String defaultThemeOrDefault() {
            return StringUtils.defaultIfBlank(defaultTheme, "auto");
        }
    }

    /**
     * security 分组：公开接口的防滥用控制。
     *
     * <p>呼号查询与 OQRS 是无需认证的公开端点，其中 OQRS 为写操作，
     * 这些参数用于限制单个来源的调用频率、单次提交规模与重复提交窗口。
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Security(Integer searchRateLimit, Integer oqrsRateLimit,
                           Integer oqrsMaxQsos, Integer oqrsDuplicateWindowHours) {

        /** 单 IP 每分钟允许的呼号查询次数；0 表示不限制 */
        public int searchRateLimitOrDefault() {
            return searchRateLimit != null && searchRateLimit >= 0 ? searchRateLimit : 20;
        }

        /** 单 IP 每小时允许的 OQRS 提交次数；0 表示不限制 */
        public int oqrsRateLimitOrDefault() {
            return oqrsRateLimit != null && oqrsRateLimit >= 0 ? oqrsRateLimit : 5;
        }

        /** 单次 OQRS 申请允许携带的最大通联条数 */
        public int oqrsMaxQsosOrDefault() {
            return oqrsMaxQsos != null && oqrsMaxQsos > 0 ? oqrsMaxQsos : 50;
        }

        /** 相同内容的重复提交拦截窗口（小时）；0 表示不去重 */
        public int oqrsDuplicateWindowHoursOrDefault() {
            return oqrsDuplicateWindowHours != null && oqrsDuplicateWindowHours >= 0
                ? oqrsDuplicateWindowHours : 24;
        }
    }

    /** layout 分组：统计页面面板顺序与显隐 */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Layout(List<Panel> panels) {

        public List<Panel> panelsOrDefault() {
            return panels == null ? List.of() : panels;
        }
    }

    /** 单个布局面板 */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Panel(String key, Boolean enabled, Integer span) {

        public boolean enabledOrDefault() {
            return enabled == null || enabled;
        }

        /** 1 = 半行（并排），2 = 整行；非法值回落为 1 */
        public int spanOrDefault() {
            return span != null && span >= 1 && span <= 2 ? span : 1;
        }
    }

    /** 单个统计项目（array 表单的每一项） */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Item(String key, String title, Boolean enabled, Integer limit) {

        public boolean enabledOrDefault() {
            return enabled == null || enabled;
        }

        public String titleOrDefault(String fallback) {
            return StringUtils.defaultIfBlank(title, fallback);
        }

        public int limitOrDefault(int def) {
            return limit != null && limit > 0 ? limit : def;
        }
    }
}
