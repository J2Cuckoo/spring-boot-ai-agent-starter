package com.agent.config;

import org.springframework.core.env.EnumerablePropertySource;
import org.springframework.core.env.Environment;
import org.springframework.core.env.PropertySource;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 从 Environment 读取 {@code spring.agent.providers} 列表结构的原始键值并还原为对象树。
 *
 * <p>Spring binder 无法把"字符串与对象混合的 models 列表"绑进 {@code Map<String, Object>}，
 * 此处按扁平键（{@code spring.agent.providers[0].<name>.<path>}）手动聚合，
 * YAML 与 properties 两种来源的最终键形态一致。</p>
 */
final class ProviderConfigReader {

    private static final Pattern KEY = Pattern.compile(
            "^spring\\.agent\\.providers\\[(\\d+)]\\.([^.]+)\\.(.+)$");

    /** 旧 map 形式（providers.<name>.<path>，无索引），兼容宿主历史配置。 */
    private static final Pattern LEGACY_KEY = Pattern.compile(
            "^spring\\.agent\\.providers\\.([^.]+)\\.(.+)$");

    private ProviderConfigReader() {
    }

    /** 返回 provider 名 -&gt; 配置树（含聚合好的 models 列表）。 */
    static Map<String, Map<String, Object>> readProviders(Environment env) {
        // providerName -> path -> value（先列表形式，后旧 map 形式补齐）
        Map<String, Map<String, String>> merged = new LinkedHashMap<>();
        for (PropertySource<?> source : ((org.springframework.core.env.AbstractEnvironment) env)
                .getPropertySources()) {
            if (!(source instanceof EnumerablePropertySource<?> eps)) {
                continue;
            }
            for (String name : eps.getPropertyNames()) {
                Matcher m = KEY.matcher(name);
                String providerName;
                String path;
                if (m.matches()) {
                    providerName = m.group(2);
                    path = m.group(3);
                } else {
                    Matcher legacy = LEGACY_KEY.matcher(name);
                    if (!legacy.matches()) {
                        continue;
                    }
                    providerName = legacy.group(1);
                    path = legacy.group(2);
                }
                Object raw = eps.getProperty(name);
                if (raw == null) {
                    continue;
                }
                merged.computeIfAbsent(providerName, n -> new LinkedHashMap<>())
                        .putIfAbsent(path, raw.toString());
            }
        }

        Map<String, Map<String, Object>> out = new LinkedHashMap<>();
        for (Map.Entry<String, Map<String, String>> e : merged.entrySet()) {
            out.put(e.getKey(), buildProviderConfig(e.getValue()));
        }
        return out;
    }

    /** path 扁平键还原为配置树：直接叶子进 map，models[n] 聚合为列表。 */
    private static Map<String, Object> buildProviderConfig(Map<String, String> paths) {
        Map<String, Object> config = new LinkedHashMap<>();
        Map<Integer, Map<String, String>> models = new TreeMap<>();
        for (Map.Entry<String, String> e : paths.entrySet()) {
            String path = e.getKey();
            if (path.startsWith("models[")) {
                int close = path.indexOf(']');
                if (close > 7) {   // "models[" 长度 7，数字从索引 7 开始
                    try {
                        int idx = Integer.parseInt(path.substring(7, close));
                        String rest = path.length() > close + 1
                                ? path.substring(close + 2) : "";   // 去掉 "]."
                        models.computeIfAbsent(idx, i -> new LinkedHashMap<>())
                                .putIfAbsent(rest.isEmpty() ? "" : rest, e.getValue());
                    } catch (NumberFormatException ignored) {
                        // 非法索引忽略
                    }
                }
            } else {
                config.put(path, e.getValue());
            }
        }
        if (!models.isEmpty()) {
            List<Object> modelList = new ArrayList<>();
            for (Map<String, String> model : models.values()) {
                if (model.containsKey("")) {
                    // 字符串形式：别名等于厂商真实模型名
                    modelList.add(model.get(""));
                } else {
                    modelList.add(new LinkedHashMap<>(model));
                }
            }
            config.put("models", modelList);
        }
        return config;
    }
}
