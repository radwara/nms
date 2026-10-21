package com.example.nms.security;

import java.util.Map;
import org.springframework.core.MethodParameter;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;

/**
 * Resolves {@link SourceSystem} and {@link WebhookProvider} controller parameters from the result
 * of the API-key and webhook-token filters.
 */
class PrincipalArgumentResolver implements HandlerMethodArgumentResolver {

  private static final Map<Class<?>, String> ATTRIBUTES =
      Map.of(
          SourceSystem.class, SourceSystem.REQUEST_ATTRIBUTE,
          WebhookProvider.class, WebhookProvider.REQUEST_ATTRIBUTE);

  @Override
  public boolean supportsParameter(MethodParameter parameter) {
    return ATTRIBUTES.containsKey(parameter.getParameterType());
  }

  @Override
  public Object resolveArgument(
      MethodParameter parameter,
      ModelAndViewContainer mavContainer,
      NativeWebRequest webRequest,
      WebDataBinderFactory binderFactory) {
    Class<?> type = parameter.getParameterType();
    Object principal =
        webRequest.getAttribute(ATTRIBUTES.get(type), RequestAttributes.SCOPE_REQUEST);
    if (type.isInstance(principal)) {
      return principal;
    }
    // Programming error: the endpoint is not behind the matching filter.
    throw new IllegalStateException("No authenticated " + type.getSimpleName() + " for request");
  }
}
