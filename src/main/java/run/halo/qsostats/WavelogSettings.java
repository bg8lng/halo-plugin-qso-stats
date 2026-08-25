package run.halo.qsostats;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.List;
import org.apache.commons.lang3.StringUtils;

/**
 * 插件设置模型，对应 resources/extensions/settings.yaml 中的分组：
 * api（Wavelog API 配置）、stats（统计项目）、display（展示设置）、layout（页面布局）。
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

    /** search 分组：呼号查询与 OQRS */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Search(Boolean enabled, Integer maxResults) {

        public boolean enabledOrDefault() {
            return enabled == null || enabled;
        }

        public int maxResultsOrDefault() {
            return maxResults != null && maxResults > 0 ? maxResults : 50;
        }
    }

    /** display 分组：展示设置 */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Display(Boolean showSectionTitle, String sectionTitle,
                          Boolean showUpdatedAt, String fallbackText) {

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