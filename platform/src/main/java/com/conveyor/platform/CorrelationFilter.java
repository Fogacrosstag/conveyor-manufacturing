package com.conveyor.platform;

import jakarta.servlet.*;
import jakarta.servlet.http.*;
import java.io.IOException;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
public class CorrelationFilter extends OncePerRequestFilter {
  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain chain)
      throws ServletException, IOException {
    Context.set(request.getHeader("X-Correlation-ID"));
    response.setHeader("X-Correlation-ID", Context.id());
    try {
      chain.doFilter(request, response);
    } finally {
      Context.clear();
    }
  }
}
