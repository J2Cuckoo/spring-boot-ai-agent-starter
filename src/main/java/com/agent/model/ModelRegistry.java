package com.agent.model;

import java.util.Collection;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * (provider, modelName) 二维注册表。引用语法 {@code provider:model} 以第一个
 * {@code :} 分割，模型别名可再含 {@code :}（如 Ollama 标签 {@code llama3:8b}）。
 */
public final class ModelRegistry {

    private final Map<String, ModelConfig> models = new ConcurrentHashMap<>();
    private final ProviderRegistry providers = new ProviderRegistry();

    public void registerProvider(Provider provider) {
        providers.register(provider);
    }

    public void registerModel(ModelConfig model) {
        providers.find(model.provider())
                .orElseThrow(() -> new IllegalArgumentException(
                        "模型 " + model.ref() + " 引用了未注册的 provider: " + model.provider()));
        models.put(model.provider() + "\u0000" + model.name(), model);
    }

    public ModelConfig resolve(String provider, String model) {
        ModelConfig m = models.get(provider + "\u0000" + model);
        if (m == null) {
            throw new IllegalArgumentException(
                    "未注册的模型: " + provider + ":" + model + "，已注册: " + models().keySet());
        }
        return m;
    }

    /** {@code provider:model} 引用解析。 */
    public ModelConfig resolve(String providerModel) {
        if (providerModel == null || providerModel.isBlank()) {
            throw new IllegalArgumentException("模型引用不能为空，格式 provider:model");
        }
        int idx = providerModel.indexOf(':');
        if (idx <= 0) {
            throw new IllegalArgumentException(
                    "非法模型引用: " + providerModel + "，应为 provider:model（如 openai:your-model）");
        }
        return resolve(providerModel.substring(0, idx), providerModel.substring(idx + 1));
    }

    public Optional<ModelConfig> tryResolve(String providerModel) {
        int idx = providerModel == null ? -1 : providerModel.indexOf(':');
        if (idx <= 0) {
            return Optional.empty();
        }
        return Optional.ofNullable(models.get(
                providerModel.substring(0, idx) + "\u0000" + providerModel.substring(idx + 1)));
    }

    public Map<String, ModelConfig> models(String provider) {
        return models.values().stream()
                .filter(m -> m.provider().equals(provider))
                .collect(java.util.stream.Collectors.toMap(ModelConfig::name, m -> m));
    }

    public Map<String, ModelConfig> models() {
        return models.values().stream()
                .collect(java.util.stream.Collectors.toMap(ModelConfig::ref, m -> m));
    }

    public Collection<ModelConfig> all() {
        return models.values();
    }

    public ProviderRegistry providers() {
        return providers;
    }
}
