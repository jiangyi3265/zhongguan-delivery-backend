package com.ruoyi.web.controller.qicai;
import com.ruoyi.common.annotation.Anonymous;
import com.ruoyi.common.core.domain.AjaxResult;
import com.ruoyi.common.utils.SecurityUtils;
import com.ruoyi.qicai.*;
import javax.servlet.http.HttpServletRequest;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController
public class QicaiNoticeController {
    private final QicaiNoticeService service;private final QicaiAuthService auth;
    public QicaiNoticeController(QicaiNoticeService service,QicaiAuthService auth) {this.service=service;this.auth=auth;}
    @Anonymous @DeleteMapping("/qicai/mobile/notices/{id}")
    public AjaxResult mobileDelete(HttpServletRequest request,@PathVariable long id) {QicaiAuthService.Principal p=auth.requireAny(request);service.hide(p.getType(),p.getId(),id);return AjaxResult.success();}
    @PreAuthorize("@ss.hasRole('admin')") @DeleteMapping("/qicai/admin/notices/{id}")
    public AjaxResult adminDelete(@PathVariable long id) {service.hide("admin",SecurityUtils.getUserId(),id);return AjaxResult.success();}
    @Anonymous @GetMapping("/qicai/mobile/notices/summary")
    public AjaxResult mobileSummary(HttpServletRequest request) {QicaiAuthService.Principal p=auth.requireAny(request);return AjaxResult.success(service.summary(p.getType(),p.getId()));}
    @Anonymous @GetMapping("/qicai/mobile/notices")
    public AjaxResult mobileList(HttpServletRequest request,@RequestParam(defaultValue="false") boolean unread,@RequestParam(defaultValue="1") int pageNum,@RequestParam(defaultValue="20") int pageSize,@RequestParam(required=false) Long beforeId) {QicaiAuthService.Principal p=auth.requireAny(request);return AjaxResult.success(service.list(p.getType(),p.getId(),unread,pageNum,pageSize,beforeId));}
    @Anonymous @PostMapping("/qicai/mobile/notices/{id}/read")
    public AjaxResult mobileRead(HttpServletRequest request,@PathVariable long id) {QicaiAuthService.Principal p=auth.requireAny(request);service.read(p.getType(),p.getId(),id);return AjaxResult.success();}
    @Anonymous @PostMapping("/qicai/mobile/notices/read-all")
    public AjaxResult mobileReadAll(HttpServletRequest request,@RequestBody java.util.Map<String,Object> body) {QicaiAuthService.Principal p=auth.requireAny(request);service.readAll(p.getType(),p.getId(),through(body));return AjaxResult.success();}
    @PreAuthorize("@ss.hasRole('admin')") @GetMapping("/qicai/admin/notices/summary")
    public AjaxResult adminSummary() {return AjaxResult.success(service.summary("admin",SecurityUtils.getUserId()));}
    @PreAuthorize("@ss.hasRole('admin')") @GetMapping("/qicai/admin/notices")
    public AjaxResult adminList(@RequestParam(defaultValue="false") boolean unread,@RequestParam(defaultValue="1") int pageNum,@RequestParam(defaultValue="20") int pageSize) {return AjaxResult.success(service.list("admin",SecurityUtils.getUserId(),unread,pageNum,pageSize));}
    @PreAuthorize("@ss.hasRole('admin')") @PostMapping("/qicai/admin/notices/{id}/read")
    public AjaxResult adminRead(@PathVariable long id) {service.read("admin",SecurityUtils.getUserId(),id);return AjaxResult.success();}
    @PreAuthorize("@ss.hasRole('admin')") @PostMapping("/qicai/admin/notices/read-all")
    public AjaxResult adminReadAll(@RequestBody java.util.Map<String,Object> body) {service.readAll("admin",SecurityUtils.getUserId(),through(body));return AjaxResult.success();}
    private long through(java.util.Map<String,Object> body) {try {long id=Long.parseLong(String.valueOf(body.get("throughId")));if(id<1)throw new NumberFormatException();return id;}catch(RuntimeException ex){throw new QicaiApiException(org.springframework.http.HttpStatus.BAD_REQUEST,"消息编号无效");}}
}
