package com.ruoyi.framework.interceptor;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import org.springframework.web.servlet.HandlerInterceptor;

/** Applies to existing as well as newly uploaded files; authorization remains separate. */
public class UploadResponseSecurityInterceptor implements HandlerInterceptor
{
    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler)
    {
        response.setHeader("X-Content-Type-Options", "nosniff");
        response.setHeader("Content-Security-Policy", "sandbox; default-src 'none'");
        response.setHeader("Referrer-Policy", "same-origin");
        // SVG, HTML and all untrusted documents are downloads. Raster images and
        // passive media retain inline previews; CSP also protects legacy mislabeled files.
        if (!request.getRequestURI().matches("(?i).+\\.(png|jpe?g|gif|bmp|webp|ico|mp4|avi|rmvb)$"))
            response.setHeader("Content-Disposition", "attachment");
        return true;
    }
}
