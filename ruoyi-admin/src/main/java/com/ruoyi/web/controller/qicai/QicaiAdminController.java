package com.ruoyi.web.controller.qicai;

import java.util.Map;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import com.ruoyi.common.core.domain.AjaxResult;
import com.ruoyi.qicai.QicaiService;

@RestController
@RequestMapping("/qicai/admin")
@PreAuthorize("@ss.hasRole('admin')")
public class QicaiAdminController
{
    private final QicaiService service;
    private final com.ruoyi.qicai.payment.WechatRefundService refunds;

    public QicaiAdminController(QicaiService service, com.ruoyi.qicai.payment.WechatRefundService refunds)
    {
        this.service = service;
        this.refunds = refunds;
    }

    @GetMapping("/dashboard")
    public AjaxResult dashboard()
    {
        return AjaxResult.success(service.dashboard());
    }

    @GetMapping("/audit")
    public AjaxResult audit(@RequestParam(required=false) String objectType, @RequestParam(required=false) Long objectId,
            @RequestParam(defaultValue="1") int pageNum,@RequestParam(defaultValue="20") int pageSize)
    {
        return AjaxResult.success(service.adminAudit(objectType,objectId,pageNum,pageSize));
    }

    @GetMapping("/customers")
    public AjaxResult customers(@RequestParam(required = false) String keyword,
            @RequestParam(required = false) String audience, @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "1") int pageNum, @RequestParam(defaultValue = "20") int pageSize)
    {
        return AjaxResult.success(service.adminCustomers(keyword, audience, status, pageNum, pageSize));
    }

    @PutMapping("/customers/{id}/review")
    public AjaxResult reviewCustomer(@PathVariable long id, @RequestBody Map<String, Object> body)
    {
        return AjaxResult.success(service.reviewCustomer(id, body));
    }

    @GetMapping("/products")
    public AjaxResult products(@RequestParam(required = false) String keyword,
            @RequestParam(required = false) String status, @RequestParam(defaultValue = "1") int pageNum,
            @RequestParam(defaultValue = "20") int pageSize)
    {
        return AjaxResult.success(service.adminProducts(keyword, status, pageNum, pageSize));
    }

    @PostMapping("/products")
    public AjaxResult createProduct(@RequestBody Map<String, Object> body)
    {
        return AjaxResult.success(service.createProduct(body));
    }

    @PutMapping("/products/{id}")
    public AjaxResult updateProduct(@PathVariable long id, @RequestBody Map<String, Object> body)
    {
        return AjaxResult.success(service.updateProduct(id, body));
    }

    @DeleteMapping("/products/{id}")
    public AjaxResult deleteProduct(@PathVariable long id)
    {
        service.deleteProduct(id);
        return AjaxResult.success("商品已停用并归档");
    }

    @GetMapping("/orders")
    public AjaxResult orders(@RequestParam(required = false) String keyword,
            @RequestParam(required = false) String status, @RequestParam(defaultValue = "1") int pageNum,
            @RequestParam(defaultValue = "20") int pageSize,@RequestParam(required=false) String audience,@RequestParam(required=false) Long forwardWarehouseId)
    {
        return AjaxResult.success(service.adminOrders(keyword, status, pageNum, pageSize,audience,forwardWarehouseId));
    }

    @GetMapping("/order-warehouses")
    public AjaxResult orderWarehouses(){return AjaxResult.success(service.orderWarehouses());}

    @GetMapping("/orders/{id}")
    public AjaxResult order(@PathVariable long id)
    {
        return AjaxResult.success(service.adminOrder(id));
    }

    @PostMapping("/orders/{id}/cancel")
    public AjaxResult cancelOrder(@PathVariable long id)
    {
        return AjaxResult.success(service.cancelOrder(id));
    }

    @GetMapping("/orders/{id}/refund")
    public AjaxResult refund(@PathVariable long id)
    {
        service.adminOrder(id);
        return AjaxResult.success(refunds.detail(id));
    }

    @PostMapping("/orders/{id}/refund")
    public AjaxResult requestRefund(@PathVariable long id, @RequestBody Map<String,Object> body)
    {
        return AjaxResult.success(refunds.request(id, com.ruoyi.common.utils.SecurityUtils.getUserId(),
                com.ruoyi.common.utils.SecurityUtils.getUsername(), body));
    }

    @PostMapping("/orders/{id}/refund/review")
    public AjaxResult reviewRefund(@PathVariable long id, @RequestBody Map<String,Object> body)
    {
        return AjaxResult.success(refunds.review(id, com.ruoyi.common.utils.SecurityUtils.getUserId(),
                com.ruoyi.common.utils.SecurityUtils.getUsername(), body));
    }

    @PostMapping("/orders/{id}/refund/refresh")
    public AjaxResult refreshRefund(@PathVariable long id)
    {
        return AjaxResult.success(refunds.refresh(id));
    }

    @GetMapping("/couriers")
    public AjaxResult couriers()
    {
        return AjaxResult.success(service.adminCouriers());
    }

    @PostMapping("/couriers")
    public AjaxResult createCourier(@RequestBody Map<String, Object> body)
    {
        return AjaxResult.success(service.createCourier(body));
    }

    @PutMapping("/couriers/{id}")
    public AjaxResult updateCourier(@PathVariable long id, @RequestBody Map<String, Object> body)
    {
        return AjaxResult.success(service.updateCourier(id, body));
    }

    @DeleteMapping("/couriers/{id}")
    public AjaxResult deleteCourier(@PathVariable long id)
    {
        service.deleteCourier(id);
        return AjaxResult.success("配送员已停用并归档");
    }

    @GetMapping("/deliveries")
    public AjaxResult deliveries(@RequestParam(required = false) String status,
            @RequestParam(defaultValue = "1") int pageNum, @RequestParam(defaultValue = "20") int pageSize)
    {
        return AjaxResult.success(service.adminDeliveries(status, pageNum, pageSize));
    }

    @PostMapping("/deliveries/assign")
    public AjaxResult assignDelivery(@RequestBody Map<String, Object> body)
    {
        return AjaxResult.success(service.assignDelivery(body));
    }

    @GetMapping("/coupons")
    public AjaxResult coupons(@RequestParam(required = false) String status,
            @RequestParam(defaultValue = "1") int pageNum, @RequestParam(defaultValue = "20") int pageSize)
    {
        return AjaxResult.success(service.adminCoupons(status, pageNum, pageSize));
    }

    @PostMapping("/coupons")
    public AjaxResult createCoupon(@RequestBody Map<String, Object> body)
    {
        return AjaxResult.success(service.createCoupon(body));
    }

    @PutMapping("/coupons/{id}")
    public AjaxResult updateCoupon(@PathVariable long id, @RequestBody Map<String, Object> body)
    {
        return AjaxResult.success(service.updateCoupon(id, body));
    }

    @DeleteMapping("/coupons/{id}")
    public AjaxResult deleteCoupon(@PathVariable long id, @RequestParam int version)
    {
        service.deleteCoupon(id,version);
        return AjaxResult.success("优惠券已停用并归档");
    }

    @PostMapping("/coupons/{couponId}/grant/{customerId}")
    public AjaxResult grantCoupon(@PathVariable long couponId, @PathVariable long customerId)
    {
        return AjaxResult.success(service.grantCoupon(couponId, customerId));
    }
}
