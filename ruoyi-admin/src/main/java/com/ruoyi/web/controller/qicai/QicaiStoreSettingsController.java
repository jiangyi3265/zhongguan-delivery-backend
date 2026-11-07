package com.ruoyi.web.controller.qicai;
import com.ruoyi.common.annotation.Anonymous;
import com.ruoyi.common.core.domain.AjaxResult;
import com.ruoyi.qicai.QicaiStoreSettings;
import java.util.Map;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController
public class QicaiStoreSettingsController {
    private final QicaiStoreSettings settings;
    public QicaiStoreSettingsController(QicaiStoreSettings settings) { this.settings=settings; }
    @Anonymous @GetMapping("/qicai/mobile/catalog/features")
    public AjaxResult features() { return AjaxResult.success(settings.features()); }
    @PreAuthorize("@ss.hasRole('admin')") @GetMapping("/qicai/admin/store-settings")
    public AjaxResult read() { return AjaxResult.success(settings.read()); }
    @PreAuthorize("@ss.hasRole('admin')") @PutMapping("/qicai/admin/store-settings")
    public AjaxResult save(@RequestBody Map<String,Object> body) { return AjaxResult.success(settings.save(body)); }
}
