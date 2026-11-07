package com.ruoyi.web.controller.qicai;
import java.util.Map;
import org.springframework.web.bind.annotation.*;
import org.springframework.security.access.prepost.PreAuthorize;
import com.ruoyi.common.core.domain.AjaxResult;
import com.ruoyi.qicai.QicaiSalesService;

@RestController
public class QicaiSalesController {
    private final QicaiSalesService service;
    public QicaiSalesController(QicaiSalesService service){this.service=service;}
    @PreAuthorize("@ss.hasRole('admin')") @GetMapping("/qicai/admin/sales")
    public AjaxResult list(){return AjaxResult.success(service.accounts());}
    @PreAuthorize("@ss.hasRole('admin')") @PostMapping("/qicai/admin/sales")
    public AjaxResult create(@RequestBody Map<String,Object> body){return AjaxResult.success(service.saveAccount(null,body));}
    @PreAuthorize("@ss.hasRole('admin')") @PutMapping("/qicai/admin/sales/{id}")
    public AjaxResult update(@PathVariable long id,@RequestBody Map<String,Object> body){return AjaxResult.success(service.saveAccount(id,body));}
    @PreAuthorize("@ss.hasRole('admin')") @PutMapping("/qicai/admin/customers/{id}/sales")
    public AjaxResult assign(@PathVariable long id,@RequestBody Map<String,Object> body){service.assign(id,body);return AjaxResult.success();}
    @PreAuthorize("@ss.hasRole('qicai_sales')") @GetMapping("/qicai/sales/customers")
    public AjaxResult mine(@RequestParam(required=false) String keyword,@RequestParam(defaultValue="1") int pageNum,@RequestParam(defaultValue="20") int pageSize){return AjaxResult.success(service.mine(keyword,pageNum,pageSize));}
    @PreAuthorize("@ss.hasRole('qicai_sales')") @PutMapping("/qicai/sales/customers/{id}/approve")
    public AjaxResult approve(@PathVariable long id,@RequestBody Map<String,Object> body){service.approve(id,body);return AjaxResult.success();}
}
