package com.ruoyi.qicai;

import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Lazy;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/** Apply proof authorization to static resources and the legacy download alias. */
@Configuration
public class QicaiProofAccessConfig implements WebMvcConfigurer
{
    private final QicaiProofAccessInterceptor interceptor;

    public QicaiProofAccessConfig(@Lazy QicaiProofAccessInterceptor interceptor)
    {
        this.interceptor = interceptor;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry)
    {
        registry.addInterceptor(interceptor).addPathPatterns("/profile/**", "/common/download/resource", "/common/download/resource/**").order(-100);
    }
}
