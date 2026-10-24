package dev.learning.gateway;

import jakarta.servlet.*;
import jakarta.servlet.http.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/** Bound JSON before Jackson allocates password/username strings, including chunked requests. */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class AuthBodyLimit extends OncePerRequestFilter {
    @Override protected boolean shouldNotFilter(HttpServletRequest request) { return !request.getRequestURI().startsWith("/auth/"); }
    @Override protected void doFilterInternal(HttpServletRequest request,HttpServletResponse response,FilterChain chain) throws IOException,ServletException {
        byte[] bytes=request.getInputStream().readNBytes(4097);
        if(bytes.length>4096) {
            response.setStatus(413); response.setContentType("application/json"); response.getWriter().write("{\"error\":\"Authentication body too large\"}"); return;
        }
        var stream=new ByteArrayInputStream(bytes);
        var input=new ServletInputStream() {
            public int read() { return stream.read(); }
            public boolean isFinished() { return stream.available()==0; }
            public boolean isReady() { return true; }
            public void setReadListener(ReadListener listener) { throw new UnsupportedOperationException("Synchronous input only"); }
        };
        chain.doFilter(new HttpServletRequestWrapper(request) {
            @Override public ServletInputStream getInputStream() { return input; }
            @Override public BufferedReader getReader() { return new BufferedReader(new InputStreamReader(input,StandardCharsets.UTF_8)); }
        },response);
    }
}
