package web.filter;

import org.springframework.web.filter.OncePerRequestFilter;
import web.WebContext;

import javax.servlet.FilterChain;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;

/**
 * fuquanemail@gmail.com 2015/11/2 17:20
 * description:
 * 1.0.0
 */
public class MyOncePerRequestFilter extends OncePerRequestFilter {
    @Override
    protected void doFilterInternal(HttpServletRequest httpServletRequest, HttpServletResponse httpServletResponse, FilterChain filterChain) throws ServletException, IOException {
        System.err.println("MyOncePerRequestFilter .... ");
        HttpServletRequest request = WebContext.currentRequest();
        System.err.println("request is NULL ?:" + (request == null));
        filterChain.doFilter(httpServletRequest, httpServletResponse);
    }
}
