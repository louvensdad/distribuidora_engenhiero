package br.com.engenheiro.api.identityaccess.domain;

/** Par recurso.ação, por exemplo {@code orders.pickup.create}. */
public record Capability(String resource, String action) {
    public String key() {
        return resource + "." + action;
    }

    @Override
    public String toString() {
        return key();
    }
}
