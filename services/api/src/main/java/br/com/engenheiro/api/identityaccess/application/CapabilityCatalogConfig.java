package br.com.engenheiro.api.identityaccess.application;

import br.com.engenheiro.api.identityaccess.domain.CapabilityCatalog;
import java.io.IOException;
import java.io.InputStream;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Catálogo carregado do contrato versionado `contracts/permissoes.json` (cópia de docs/especificacao). */
@Configuration
public class CapabilityCatalogConfig {
    @Bean
    CapabilityCatalog capabilityCatalog() throws IOException {
        try (InputStream in = new ClassPathResource("contracts/permissoes.json").getInputStream()) {
            JsonNode root = JsonMapper.builder().build().readTree(in);
            if (!"deny".equals(root.path("default").asString())) {
                throw new IllegalStateException("Contrato de permissões precisa ter default=deny");
            }
            Map<String, Set<String>> map = new LinkedHashMap<>();
            root.path("resources").properties().forEach(entry -> {
                Set<String> actions = new LinkedHashSet<>();
                entry.getValue().forEach(a -> actions.add(a.asString()));
                map.put(entry.getKey(), actions);
            });
            return new CapabilityCatalog(map);
        }
    }
}
