package br.com.engenheiro.api.identityaccess;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.type.filter.AnnotationTypeFilter;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Todo endpoint dos controllers está no contrato publicado e vice-versa (sem rota fantasma). */
class OpenApiContractTest {
    @Test
    void contratoCobreExatamenteOsEndpointsDosControllers() throws Exception {
        JsonNode doc = JsonMapper.builder().build()
                .readTree(Files.readString(Path.of("../../packages/contracts/openapi.json")));
        Set<String> documented = new HashSet<>();
        doc.path("paths").properties().forEach(p -> p.getValue().properties()
                .forEach(m -> documented.add(m.getKey().toUpperCase() + " " + p.getKey())));

        Set<String> implemented = new HashSet<>();
        var scanner = new ClassPathScanningCandidateComponentProvider(false);
        scanner.addIncludeFilter(new AnnotationTypeFilter(RestController.class));
        for (var bd : scanner.findCandidateComponents("br.com.engenheiro.api")) {
            Class<?> c = Class.forName(bd.getBeanClassName());
            RequestMapping base = c.getAnnotation(RequestMapping.class);
            String prefix = base == null || base.value().length == 0 ? "" : base.value()[0];
            for (Method m : c.getDeclaredMethods()) {
                GetMapping g = m.getAnnotation(GetMapping.class);
                PostMapping p = m.getAnnotation(PostMapping.class);
                if (g != null) {
                    implemented.add("GET " + prefix + (g.value().length == 0 ? "" : g.value()[0]));
                }
                if (p != null) {
                    implemented.add("POST " + prefix + (p.value().length == 0 ? "" : p.value()[0]));
                }
                PutMapping u = m.getAnnotation(PutMapping.class);
                if (u != null) {
                    implemented.add("PUT " + prefix + (u.value().length == 0 ? "" : u.value()[0]));
                }
            }
        }
        assertThat(documented).containsAll(implemented);
        Set<String> framework = Set.of("GET /oauth2/authorization/engenheiro", "POST /bff/logout",
                "GET /actuator/health/readiness");
        Set<String> extra = new HashSet<>(documented);
        extra.removeAll(implemented);
        assertThat(extra).isEqualTo(framework);
    }
}
