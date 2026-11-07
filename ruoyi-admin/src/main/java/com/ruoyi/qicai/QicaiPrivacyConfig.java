package com.ruoyi.qicai;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Lazy;
import org.springframework.http.HttpStatus;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class QicaiPrivacyConfig implements WebMvcConfigurer {
    private final QicaiAuthService auth;
    private final QicaiPrivacyService privacy;
    public QicaiPrivacyConfig(@Lazy QicaiAuthService auth,@Lazy QicaiPrivacyService privacy) { this.auth=auth;this.privacy=privacy; }
    @Override public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new HandlerInterceptor() {
            @Override public boolean preHandle(HttpServletRequest request,HttpServletResponse response,Object handler) {
                String method=request.getMethod(),path=request.getRequestURI().substring(request.getContextPath().length());
                if(!("POST".equals(method)||"PUT".equals(method))) return true;
                boolean collecting=path.startsWith("/qicai/mobile/auth/") || path.equals("/qicai/mobile/courier/login") || path.equals("/qicai/mobile/profile") || path.equals("/qicai/mobile/orders") || path.equals("/qicai/mobile/upload") || path.matches("/qicai/mobile/orders/[0-9]+/(receipt|location)") || path.matches("/qicai/mobile/courier/tasks/[0-9]+/(status|resume)") || path.equals("/qicai/mobile/payments/wechat/identity");
                if(!collecting) return true;
                boolean anonymous=path.startsWith("/qicai/mobile/auth/") || path.equals("/qicai/mobile/courier/login");
                QicaiAuthService.Principal principal=anonymous?null:auth.requireAny(request);
                if(!"1".equals(request.getHeader("X-Qicai-Privacy-Accepted")) || !QicaiPrivacyService.VERSION.equals(request.getHeader("X-Qicai-Privacy-Version"))) throw new QicaiApiException(HttpStatus.PRECONDITION_REQUIRED,"请更新小程序，阅读并主动同意用户服务协议和隐私政策后继续");
                privacy.record(principal,path);return true;
            }
        }).addPathPatterns("/qicai/mobile/**").order(-90);
    }
}
