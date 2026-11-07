package com.ruoyi.web.controller.qicai;
import com.ruoyi.common.annotation.Anonymous;
import com.ruoyi.common.core.domain.AjaxResult;
import com.ruoyi.qicai.QicaiBrandService;
import java.util.Map;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController
public class QicaiBrandController {
    private final QicaiBrandService brands;
    public QicaiBrandController(QicaiBrandService brands) { this.brands=brands; }
    @Anonymous @GetMapping("/qicai/mobile/catalog/brands")
    public AjaxResult publicBrands() { return AjaxResult.success(brands.list(false)); }
    @PreAuthorize("@ss.hasRole('admin')") @GetMapping("/qicai/admin/brands")
    public AjaxResult adminBrands() { return AjaxResult.success(brands.list(true)); }
    @PreAuthorize("@ss.hasRole('admin')") @PostMapping("/qicai/admin/brands")
    public AjaxResult create(@RequestBody Map<String,Object> body) { return AjaxResult.success(brands.save(null,body)); }
    @PreAuthorize("@ss.hasRole('admin')") @PutMapping("/qicai/admin/brands/{id}")
    public AjaxResult update(@PathVariable long id,@RequestBody Map<String,Object> body) { return AjaxResult.success(brands.save(id,body)); }
}
