package com.supplierconsumer.security;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.core.MethodParameter;
import org.springframework.core.ResolvableType;
import org.springframework.stereotype.Component;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;

import java.util.Optional;

/**
 * Lets a controller ask for the identity it needs by declaring it as a parameter:
 *
 * <pre>{@code
 * public ApiResponse listProducts(Principals.Company user)              // company token required
 * public ApiResponse cart(Principals.Consumer consumer)                 // consumer token required
 * public ApiResponse conversations(Principals.Chat user)                // either identity
 * public ApiResponse companies(Optional<Principals.Consumer> consumer)  // anonymous allowed
 * public ApiResponse approve(@AdminOnly Principals.Company admin)       // administrator only
 * }</pre>
 *
 * <p>This is the counterpart of attaching a middleware to a route in Express, and is done per
 * handler rather than per URL prefix because the paths overlap -- {@code /api/orders/pending} and
 * {@code /api/orders/consumer} sit under one prefix but need different identities.
 */
@Component
public class PrincipalArgumentResolver implements HandlerMethodArgumentResolver {

    private final Authenticator authenticator;

    public PrincipalArgumentResolver(Authenticator authenticator) {
        this.authenticator = authenticator;
    }

    @Override
    public boolean supportsParameter(MethodParameter parameter) {
        Class<?> type = parameter.getParameterType();
        if (type == Principals.Company.class
                || type == Principals.Consumer.class
                || type == Principals.Chat.class) {
            return true;
        }
        return type == Optional.class && optionalOf(parameter) == Principals.Consumer.class;
    }

    @Override
    public Object resolveArgument(MethodParameter parameter, ModelAndViewContainer mav,
                                  NativeWebRequest webRequest, WebDataBinderFactory binderFactory) {
        HttpServletRequest request = webRequest.getNativeRequest(HttpServletRequest.class);
        Class<?> type = parameter.getParameterType();

        if (type == Principals.Company.class) {
            return parameter.hasParameterAnnotation(AdminOnly.class)
                    ? authenticator.admin(request)
                    : authenticator.company(request);
        }
        if (type == Principals.Consumer.class) {
            return authenticator.consumer(request);
        }
        if (type == Principals.Chat.class) {
            return authenticator.chat(request);
        }
        return authenticator.optionalConsumer(request);
    }

    private Class<?> optionalOf(MethodParameter parameter) {
        ResolvableType generic = ResolvableType.forMethodParameter(parameter).getGeneric(0);
        return generic.resolve();
    }
}
