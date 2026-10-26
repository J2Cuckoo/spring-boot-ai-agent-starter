package com.agent.model;

/**
 * 模型引用配置对象：类型安全地表达 {@code provider:model}，替代硬编码字符串。
 * 供 {@code conversation.using(...)} 与工作流 {@code modelInference(...)} 直接使用。
 */
public record ModelRef(String provider, String model) {

    /** 从 {@code provider:model} 解析，以第一个 {@code :} 分割（模型别名可再含冒号）。 */
    public static ModelRef of(String providerModel) {
        if (providerModel == null || providerModel.isBlank()) {
            throw new IllegalArgumentException("模型引用不能为空，格式 provider:model");
        }
        int idx = providerModel.indexOf(':');
        if (idx <= 0) {
            throw new IllegalArgumentException("非法模型引用: " + providerModel + "，应为 provider:model");
        }
        return new ModelRef(providerModel.substring(0, idx), providerModel.substring(idx + 1));
    }

    /** 用 provider 与 model 分别构造。 */
    public static ModelRef of(String provider, String model) {
        if (provider == null || provider.isBlank() || model == null || model.isBlank()) {
            throw new IllegalArgumentException("provider 与 model 均不能为空");
        }
        return new ModelRef(provider, model);
    }

    /** 拼接回 {@code provider:model} 字符串。 */
    public String ref() {
        return provider + ":" + model;
    }

    @Override
    public String toString() {
        return ref();
    }
}
