package com.ledgerflow.merchant.internal;

import com.ledgerflow.merchant.MerchantId;
import com.ledgerflow.shared.ApiPrincipal;
import com.ledgerflow.shared.DomainException;
import java.util.List;
import java.util.UUID;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.MethodParameter;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration(proxyBeanMethods = false)
class MerchantArgumentConfiguration implements WebMvcConfigurer {
    @Override
    public void addArgumentResolvers(List<HandlerMethodArgumentResolver> resolvers) {
        resolvers.add(new MerchantArgumentResolver());
    }

    private static class MerchantArgumentResolver implements HandlerMethodArgumentResolver {
        @Override
        public boolean supportsParameter(MethodParameter parameter) {
            return parameter.hasParameterAnnotation(MerchantId.class) && parameter.getParameterType() == UUID.class;
        }
        @Override
        public Object resolveArgument(MethodParameter parameter, ModelAndViewContainer container,
                NativeWebRequest request, WebDataBinderFactory binder) {
            String header = request.getHeader("X-Merchant-Id");
            if (header != null) {
                try { return UUID.fromString(header); }
                catch (IllegalArgumentException invalid) { throw new DomainException(400, "invalid-merchant", "X-Merchant-Id must be a UUID."); }
            }
            var authentication = SecurityContextHolder.getContext().getAuthentication();
            if (authentication != null && authentication.getPrincipal() instanceof ApiPrincipal key) return key.merchantId();
            throw new DomainException(400, "merchant-required", "Select a merchant using X-Merchant-Id.");
        }
    }
}
