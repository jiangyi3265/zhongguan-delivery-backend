package com.ruoyi.web.controller.qicai;
import com.ruoyi.common.core.domain.AjaxResult;
import com.ruoyi.qicai.QicaiReconciliationService;
import org.springframework.http.*;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

@RestController @RequestMapping("/qicai/admin/reconciliation") @PreAuthorize("@ss.hasRole('admin')")
public class QicaiReconciliationController {
    private final QicaiReconciliationService service;
    public QicaiReconciliationController(QicaiReconciliationService service) {this.service=service;}
    @GetMapping public AjaxResult list(@RequestParam(defaultValue="1") int pageNum,@RequestParam(defaultValue="20") int pageSize) {return AjaxResult.success(service.runs(pageNum,pageSize));}
    @PostMapping("/import") public AjaxResult upload(@RequestParam("file") MultipartFile file,@RequestParam String billDate) throws Exception {return AjaxResult.success(service.upload(file,billDate));}
    @PostMapping("/bills/{id}/rerun") public AjaxResult rerun(@PathVariable long id) throws Exception {return AjaxResult.success(service.rerun(id));}
    @GetMapping("/{id}") public AjaxResult detail(@PathVariable long id) {return AjaxResult.success(service.detail(id));}
    @GetMapping("/{id}/items") public AjaxResult items(@PathVariable long id,@RequestParam(required=false) String outcome,@RequestParam(defaultValue="1") int pageNum,@RequestParam(defaultValue="20") int pageSize) {return AjaxResult.success(service.items(id,outcome,pageNum,pageSize));}
    @GetMapping("/{id}/export") public ResponseEntity<byte[]> export(@PathVariable long id) {return ResponseEntity.ok().header("Content-Disposition","attachment; filename=reconciliation-"+id+".csv").header("Cache-Control","no-store").contentType(new MediaType("text","csv",java.nio.charset.StandardCharsets.UTF_8)).body(service.export(id));}
}
