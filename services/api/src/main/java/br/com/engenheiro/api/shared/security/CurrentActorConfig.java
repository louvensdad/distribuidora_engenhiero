package br.com.engenheiro.api.shared.security;

import br.com.engenheiro.api.identityaccess.domain.Actor;
import br.com.engenheiro.api.shared.web.ApiException;
import br.com.engenheiro.api.shared.web.ErrorCode;
import java.util.List;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.MethodParameter;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/** Permite declarar {@code Actor actor} em controllers. Ator vem só do token+banco. */
@Configuration
public class CurrentActorConfig implements WebMvcConfigurer {
    @Override
    public void addArgumentResolvers(List<HandlerMethodArgumentResolver> resolvers) {
        resolvers.add(new HandlerMethodArgumentResolver() {
            @Override
            public boolean supportsParameter(MethodParameter parameter) {
                return parameter.getParameterType().equals(Actor.class);
            }

            @Override
            public Object resolveArgument(MethodParameter parameter, ModelAndViewContainer mav,
                                          NativeWebRequest request, WebDataBinderFactory binderFactory) {
                Object actor = request.getAttribute(ActorFilter.ACTOR_ATTRIBUTE, RequestAttributes.SCOPE_REQUEST);
                if (actor == null) {
                    throw new ApiException(ErrorCode.UNAUTHENTICATED, "Credencial ausente ou inválida.");
                }
                return actor;
            }
        });
    }
}
