package com.ruoyi.web.controller.qicai;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import javax.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import com.ruoyi.common.annotation.Anonymous;
import com.ruoyi.common.config.RuoYiConfig;
import com.ruoyi.common.core.domain.AjaxResult;
import com.ruoyi.common.utils.file.FileUploadUtils;
import com.ruoyi.common.utils.file.MimeTypeUtils;
import com.ruoyi.framework.config.ServerConfig;
import com.ruoyi.qicai.QicaiApiException;
import com.ruoyi.qicai.QicaiAuthService;
import com.ruoyi.qicai.QicaiAuthService.Principal;
import com.ruoyi.qicai.QicaiService;
import com.ruoyi.qicai.QicaiHealthService;
import com.ruoyi.qicai.QicaiWechatAuthService;
import com.ruoyi.qicai.QicaiOrderRequest;

@Anonymous
@RestController
@RequestMapping("/qicai/mobile")
public class QicaiMobileController
{
    private final QicaiAuthService auth;
    private final QicaiService service;
    private final ServerConfig serverConfig;
    private final QicaiHealthService health;
    private final QicaiWechatAuthService wechat;

    @Value("${qicai.upload.max-bytes:5242880}")
    private long maxUploadBytes;

    public QicaiMobileController(QicaiAuthService auth, QicaiService service, ServerConfig serverConfig, QicaiHealthService health,
            QicaiWechatAuthService wechat)
    {
        this.auth = auth;
        this.service = service;
        this.serverConfig = serverConfig;
        this.health = health;
        this.wechat = wechat;
    }

    @GetMapping("/health")
    public ResponseEntity<AjaxResult> health()
    {
        Map<String, Object> data = health.readiness();
        return ResponseEntity.status("UP".equals(data.get("status")) ? HttpStatus.OK : HttpStatus.SERVICE_UNAVAILABLE)
                .body(AjaxResult.success(data));
    }

    @PostMapping("/auth/sms")
    public ResponseEntity<AjaxResult> sms(@RequestBody Map<String, Object> body, HttpServletRequest request)
    {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(AjaxResult.success(auth.requestSms(body, request.getRemoteAddr())));
    }

    @PostMapping("/auth/register")
    public ResponseEntity<AjaxResult> register(@RequestBody Map<String, Object> body)
    {
        return ResponseEntity.status(HttpStatus.CREATED).body(AjaxResult.success(auth.registerCustomer(body)));
    }

    @PostMapping("/auth/login")
    public AjaxResult login(@RequestBody Map<String, Object> body)
    {
        return AjaxResult.success(auth.loginCustomer(body));
    }

    @PostMapping("/auth/wechat/login")
    public AjaxResult wechatLogin(@RequestBody Map<String, Object> body, HttpServletRequest request)
    {
        return AjaxResult.success(wechat.login(body, request.getRemoteAddr()));
    }

    @PostMapping("/auth/wechat/business-register")
    public ResponseEntity<AjaxResult> wechatBusinessRegister(@RequestBody Map<String, Object> body, HttpServletRequest request)
    {
        return ResponseEntity.status(HttpStatus.CREATED).body(AjaxResult.success(wechat.registerBusiness(body, request.getRemoteAddr())));
    }

    @PostMapping("/courier/login")
    public AjaxResult courierLogin(@RequestBody Map<String, Object> body)
    {
        return AjaxResult.success(auth.loginCourier(body));
    }

    @DeleteMapping("/auth/session")
    public AjaxResult logout(HttpServletRequest request)
    {
        auth.requireAny(request);
        auth.logout(request);
        return AjaxResult.success("已安全退出");
    }

    @GetMapping("/profile")
    public AjaxResult profile(HttpServletRequest request)
    {
        Principal principal = auth.requireCustomer(request);
        return AjaxResult.success(auth.customerProfile(principal.getId()));
    }

    @PutMapping("/profile")
    public AjaxResult updateProfile(@RequestBody Map<String, Object> body, HttpServletRequest request)
    {
        Principal principal = auth.requireCustomer(request);
        return AjaxResult.success(auth.updateCustomer(principal.getId(), body));
    }

    @GetMapping("/catalog/products")
    public AjaxResult catalogProducts(@RequestParam(required = false) String keyword,
            @RequestParam(required = false) String category, @RequestParam(required = false) Integer pageNum,
            @RequestParam(required = false) Integer pageSize, @RequestParam(required = false) Long brandId)
    {
        return AjaxResult.success(service.catalogProducts(keyword, category, pageNum, pageSize, brandId));
    }

    @GetMapping("/catalog/categories")
    public AjaxResult catalogCategories(@RequestParam(required = false) Long brandId)
    {
        return AjaxResult.success(service.catalogCategories(brandId));
    }

    @GetMapping("/catalog/products/{id}")
    public AjaxResult catalogProduct(@PathVariable long id)
    {
        return AjaxResult.success(service.catalogProduct(id));
    }

    @GetMapping("/products")
    public AjaxResult products(@RequestParam(required = false) String keyword,
            @RequestParam(required = false) String category, @RequestParam(required = false) Integer pageNum,
            @RequestParam(required = false) Integer pageSize, @RequestParam(required = false) Long brandId, HttpServletRequest request)
    {
        return AjaxResult.success(service.mobileProducts(auth.requireCustomer(request), keyword, category, pageNum, pageSize, brandId));
    }

    @GetMapping("/products/{id}")
    public AjaxResult product(@PathVariable long id, HttpServletRequest request)
    {
        return AjaxResult.success(service.mobileProduct(auth.requireCustomer(request), id));
    }

    @PostMapping("/orders")
    public ResponseEntity<AjaxResult> createOrder(@RequestBody QicaiOrderRequest body, HttpServletRequest request)
    {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(AjaxResult.success(service.createOrder(auth.requireCustomer(request), body)));
    }

    @GetMapping("/orders")
    public AjaxResult orders(@RequestParam(required = false) String status,
            @RequestParam(defaultValue = "1") int pageNum, @RequestParam(defaultValue = "20") int pageSize,
            HttpServletRequest request)
    {
        return AjaxResult.success(service.mobileOrders(auth.requireCustomer(request), status, pageNum, pageSize));
    }

    @GetMapping("/orders/submission")
    public AjaxResult submittedOrder(@RequestParam String idempotencyKey, HttpServletRequest request)
    {
        return AjaxResult.success(service.submittedOrder(auth.requireCustomer(request), idempotencyKey));
    }

    @GetMapping("/orders/{id}")
    public AjaxResult order(@PathVariable long id, HttpServletRequest request)
    {
        return AjaxResult.success(service.mobileOrder(auth.requireCustomer(request), id));
    }

    @PutMapping("/orders/{id}/location")
    public AjaxResult location(@PathVariable long id, @RequestBody QicaiOrderRequest body, HttpServletRequest request)
    {
        return AjaxResult.success(service.updateOrderLocation(auth.requireCustomer(request),id,body));
    }

    @PostMapping("/orders/{id}/receipt")
    public AjaxResult receipt(@PathVariable long id, @RequestBody Map<String, Object> body,
            HttpServletRequest request)
    {
        return AjaxResult.success(service.confirmReceipt(auth.requireCustomer(request), id, body));
    }

    @GetMapping("/coupons")
    public AjaxResult coupons(HttpServletRequest request)
    {
        return AjaxResult.success(service.mobileCoupons(auth.requireCustomer(request)));
    }

    @PostMapping("/coupons/{id}/claim")
    public ResponseEntity<AjaxResult> claimCoupon(@PathVariable long id, HttpServletRequest request)
    {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(AjaxResult.success(service.claimCoupon(auth.requireCustomer(request), id)));
    }

    @GetMapping("/courier/tasks")
    public AjaxResult courierTasks(@RequestParam(required = false) String status, @RequestParam(required = false) Integer pageNum,
            @RequestParam(required = false) Integer pageSize, HttpServletRequest request)
    {
        return AjaxResult.success(service.courierTasks(auth.requireCourier(request), status, pageNum, pageSize));
    }

    @GetMapping("/courier/tasks/{id}")
    public AjaxResult courierTask(@PathVariable long id, HttpServletRequest request)
    {
        return AjaxResult.success(service.courierTask(auth.requireCourier(request), id));
    }

    @PutMapping("/courier/tasks/{id}/status")
    public AjaxResult courierTaskStatus(@PathVariable long id, @RequestBody Map<String, Object> body,
            HttpServletRequest request)
    {
        return AjaxResult.success(service.updateCourierTask(auth.requireCourier(request), id, body));
    }

    @PostMapping("/courier/tasks/{id}/resume")
    public AjaxResult resumeCourierTask(@PathVariable long id, @RequestBody Map<String, Object> body,
            HttpServletRequest request)
    {
        return AjaxResult.success(service.resumeCourierTask(auth.requireCourier(request), id, body));
    }

    @GetMapping("/courier/availability")
    public AjaxResult availability(HttpServletRequest request)
    {
        return AjaxResult.success(service.courierAvailability(auth.requireCourier(request)));
    }

    @PutMapping("/courier/availability")
    public AjaxResult availability(@RequestBody Map<String, Object> body, HttpServletRequest request)
    {
        Object raw = body.get("available");
        if (!(raw instanceof Boolean))
        {
            throw new QicaiApiException(HttpStatus.BAD_REQUEST, "available必须为布尔值");
        }
        return AjaxResult.success(service.updateCourierAvailability(auth.requireCourier(request), (Boolean) raw));
    }

    @PostMapping("/upload")
    public ResponseEntity<AjaxResult> upload(@RequestParam("file") MultipartFile file, HttpServletRequest request)
            throws Exception
    {
        Principal principal = auth.requireAny(request);
        validateImage(file);
        String fileName = FileUploadUtils.upload(RuoYiConfig.getProfile() + "/qicai-proof", file, MimeTypeUtils.IMAGE_EXTENSION, true);
        auth.recordUpload(principal, fileName);
        Map<String, Object> data = new LinkedHashMap<String, Object>();
        data.put("url", serverConfig.getUrl() + fileName);
        data.put("fileName", fileName);
        return ResponseEntity.status(HttpStatus.CREATED).body(AjaxResult.success(data));
    }

    private void validateImage(MultipartFile file) throws IOException
    {
        if (file == null || file.isEmpty())
        {
            throw new QicaiApiException(HttpStatus.BAD_REQUEST, "请选择图片文件");
        }
        if (file.getSize() > maxUploadBytes)
        {
            throw new QicaiApiException(HttpStatus.PAYLOAD_TOO_LARGE, "图片大小不能超过5MB");
        }
        String contentType = file.getContentType();
        if (contentType == null || !(contentType.equals("image/png") || contentType.equals("image/jpeg")
                || contentType.equals("image/gif") || contentType.equals("image/bmp")))
        {
            throw new QicaiApiException(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "仅支持PNG、JPG、GIF或BMP图片");
        }
        byte[] bytes = file.getBytes();
        boolean png = bytes.length >= 8 && bytes[0] == (byte) 0x89 && bytes[1] == 0x50 && bytes[2] == 0x4e && bytes[3] == 0x47;
        boolean jpeg = bytes.length >= 3 && bytes[0] == (byte) 0xff && bytes[1] == (byte) 0xd8 && bytes[2] == (byte) 0xff;
        boolean gif = bytes.length >= 6 && bytes[0] == 0x47 && bytes[1] == 0x49 && bytes[2] == 0x46;
        boolean bmp = bytes.length >= 2 && bytes[0] == 0x42 && bytes[1] == 0x4d;
        if (!(png || jpeg || gif || bmp))
        {
            throw new QicaiApiException(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "文件内容不是有效图片");
        }
        // A file signature alone is not enough: parse dimensions and decode the image.
        try (javax.imageio.stream.ImageInputStream input = javax.imageio.ImageIO.createImageInputStream(new java.io.ByteArrayInputStream(bytes)))
        {
            java.util.Iterator<javax.imageio.ImageReader> readers = javax.imageio.ImageIO.getImageReaders(input);
            if (!readers.hasNext()) throw new IOException("Invalid image");
            javax.imageio.ImageReader reader = readers.next();
            try
            {
                reader.setInput(input, true, true);
                int width = reader.getWidth(0), height = reader.getHeight(0);
                if (width <= 0 || height <= 0 || (long) width * height > 25000000L)
                    throw new IOException("Image dimensions exceed limit");
                if (reader.read(0) == null) throw new IOException("Corrupt image");
            }
            finally { reader.dispose(); }
        }
        catch (IOException | RuntimeException ex)
        {
            throw new QicaiApiException(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "图片损坏或尺寸过大，请重新选择");
        }
    }
}
