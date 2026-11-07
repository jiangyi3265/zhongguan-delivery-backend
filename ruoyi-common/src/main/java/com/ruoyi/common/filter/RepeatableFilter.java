package com.ruoyi.common.filter;

import java.io.IOException;
import javax.servlet.Filter;
import javax.servlet.FilterChain;
import javax.servlet.FilterConfig;
import javax.servlet.ServletException;
import javax.servlet.ServletRequest;
import javax.servlet.ServletResponse;
import javax.servlet.http.HttpServletRequest;
import org.springframework.http.MediaType;
import com.ruoyi.common.utils.StringUtils;

/**
 * Repeatable 过滤器
 * 
 * @author ruoyi
 */
public class RepeatableFilter implements Filter
{
    @Override
    public void init(FilterConfig filterConfig) throws ServletException
    {

    }

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException, ServletException
    {
        ServletRequest requestWrapper = null;
        if (request instanceof HttpServletRequest
                && StringUtils.startsWithIgnoreCase(request.getContentType(), MediaType.APPLICATION_JSON_VALUE))
        {
            try { requestWrapper = new RepeatedlyRequestWrapper((HttpServletRequest) request, response); }
            catch (RepeatedlyRequestWrapper.BodyLimitException ex)
            {
                javax.servlet.http.HttpServletResponse http = (javax.servlet.http.HttpServletResponse) response;
                http.setStatus(413); http.setContentType("application/json;charset=UTF-8");
                http.getWriter().write("{\"code\":413,\"msg\":\"支付通知过大\"}");
                return;
            }
        }
        if (null == requestWrapper)
        {
            chain.doFilter(request, response);
        }
        else
        {
            chain.doFilter(requestWrapper, response);
        }
    }

    @Override
    public void destroy()
    {

    }
}
