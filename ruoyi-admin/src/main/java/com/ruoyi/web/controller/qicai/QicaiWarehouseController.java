package com.ruoyi.web.controller.qicai;
import com.ruoyi.common.annotation.Anonymous;
import com.ruoyi.common.core.domain.AjaxResult;
import com.ruoyi.qicai.QicaiWarehouseService;
import com.ruoyi.qicai.QicaiOrderRequest;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
@RestController public class QicaiWarehouseController {
 private final QicaiWarehouseService service;
 public QicaiWarehouseController(QicaiWarehouseService service){this.service=service;}
 @Anonymous @GetMapping("/qicai/mobile/catalog/forward-warehouses") public AjaxResult visible(){return AjaxResult.success(service.list(false));}
 @PreAuthorize("@ss.hasRole('admin')") @GetMapping("/qicai/admin/forward-warehouses") public AjaxResult list(){return AjaxResult.success(service.list(true));}
 @PreAuthorize("@ss.hasRole('admin')") @PostMapping("/qicai/admin/forward-warehouses") public AjaxResult create(@RequestBody QicaiOrderRequest body){return AjaxResult.success(service.save(null,body));}
 @PreAuthorize("@ss.hasRole('admin')") @PutMapping("/qicai/admin/forward-warehouses/{id}") public AjaxResult update(@PathVariable long id,@RequestBody QicaiOrderRequest body){return AjaxResult.success(service.save(id,body));}
}
