package com.agent.model;

import java.util.Collection;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Provider 注册表。
 */
public final class ProviderRegistry {

    private final Map<String, Provider> providers = new ConcurrentHashMap<>();

    public void register(Provider provider) {
        providers.put(provider.name(), provider);
    }

    public Optional<Provider> find(String name) {
        return Optional.ofNullable(providers.get(name));
    }

    /** 不存在时抛 {@link IllegalArgumentException}。 */
    public Provider require(String name) {
        Provider p = providers.get(name);
        if (p == null) {
            throw new IllegalArgumentException("未注册的 provider: " + name + "，已注册: " + providers.keySet());
        }
        return p;
    }

    public Collection<Provider> all() {
        return providers.values();
    }

    public Map<String, Provider> asMap() {
        return Map.copyOf(providers);
    }
}
