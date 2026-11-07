package com.ruoyi.web.controller.qicai;

import com.ruoyi.common.annotation.Anonymous;
import com.ruoyi.common.core.domain.AjaxResult;
import com.ruoyi.qicai.QicaiProductMedia;
import java.util.concurrent.TimeUnit;
import org.springframework.http.*;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

@RestController
public class QicaiProductMediaController {
    private final QicaiProductMedia media;
    public QicaiProductMediaController(QicaiProductMedia media) { this.media=media; }
    @PostMapping("/qicai/admin/product-images") @PreAuthorize("@ss.hasRole('admin')")
    public AjaxResult upload(@RequestParam("file") MultipartFile file) throws Exception { return AjaxResult.success(media.upload(file)); }
    @Anonymous @GetMapping("/qicai/mobile/catalog/images/{name}")
    public ResponseEntity<byte[]> image(@PathVariable String name) throws Exception {
        return ResponseEntity.ok().contentType(MediaType.IMAGE_JPEG).header("X-Content-Type-Options","nosniff")
            .header("Cache-Control","public, max-age=31536000, immutable").body(media.read(name));
    }
}
