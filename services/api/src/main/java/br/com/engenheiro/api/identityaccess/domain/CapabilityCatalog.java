package br.com.engenheiro.api.identityaccess.domain;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Catálogo fechado de recursos/ações. Capacidade fora do catálogo é sempre negada. */
public final class CapabilityCatalog {
    private final Map<String, Set<String>> actionsByResource;

    public CapabilityCatalog(Map<String, Set<String>> actionsByResource) {
        this.actionsByResource = Collections.unmodifiableMap(new LinkedHashMap<>(actionsByResource));
    }

    public boolean contains(String resource, String action) {
        Set<String> actions = actionsByResource.get(resource);
        return actions != null && actions.contains(action);
    }

    public Map<String, Set<String>> asMap() {
        return actionsByResource;
    }

    public List<Capability> all() {
        List<Capability> list = new ArrayList<>();
        actionsByResource.forEach((r, actions) -> actions.forEach(a -> list.add(new Capability(r, a))));
        return list;
    }
}
