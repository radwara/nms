package com.example.nms.security;

import org.springframework.core.MethodParameter;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;

/** Resolves {@link SourceSystem} controller parameters from the API-key filter's result. */
class SourceSystemArgumentResolver implements HandlerMethodArgumentResolver {

  @Override
  public boolean supportsParameter(MethodParameter parameter) {
    return parameter.getParameterType() == SourceSystem.class;
  }

  @Override
  public SourceSystem resolveArgument(
      MethodParameter parameter,
      ModelAndViewContainer mavContainer,
      NativeWebRequest webRequest,
      WebDataBinderFactory binderFactory) {
    Object source =
        webRequest.getAttribute(SourceSystem.REQUEST_ATTRIBUTE, RequestAttributes.SCOPE_REQUEST);
    if (source instanceof SourceSystem sourceSystem) {
      return sourceSystem;
    }
    // Programming error: the endpoint is not behind the API-key filter.
    throw new IllegalStateException("No authenticated source system for this request");
  }
}
