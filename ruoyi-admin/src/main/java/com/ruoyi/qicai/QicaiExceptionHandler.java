package com.ruoyi.qicai;

import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.BindException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.multipart.support.MissingServletRequestPartException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.security.access.AccessDeniedException;
import com.ruoyi.common.core.domain.AjaxResult;
import com.ruoyi.common.exception.file.InvalidExtensionException;
import com.ruoyi.common.exception.file.FileNameLengthLimitExceededException;
import com.ruoyi.common.exception.file.FileSizeLimitExceededException;

@Order(Ordered.HIGHEST_PRECEDENCE)
@RestControllerAdvice(basePackages = "com.ruoyi.web.controller.qicai")
public class QicaiExceptionHandler
{
    private static final Logger log = LoggerFactory.getLogger(QicaiExceptionHandler.class);

    @ExceptionHandler(QicaiApiException.class)
    public ResponseEntity<AjaxResult> handleBusiness(QicaiApiException ex)
    {
        return ResponseEntity.status(ex.getStatus())
                .body(AjaxResult.error(ex.getStatus().value(), ex.getMessage()));
    }

    @ExceptionHandler({ MethodArgumentNotValidException.class, BindException.class })
    public ResponseEntity<AjaxResult> handleValidation(Exception ex)
    {
        String message;
        if (ex instanceof MethodArgumentNotValidException)
        {
            message = ((MethodArgumentNotValidException) ex).getBindingResult().getFieldErrors().stream()
                    .map(error -> error.getDefaultMessage()).collect(Collectors.joining("；"));
        }
        else
        {
            message = ((BindException) ex).getBindingResult().getFieldErrors().stream()
                    .map(error -> error.getDefaultMessage()).collect(Collectors.joining("；"));
        }
        return ResponseEntity.badRequest().body(AjaxResult.error(HttpStatus.BAD_REQUEST.value(), message));
    }

    @ExceptionHandler({ MethodArgumentTypeMismatchException.class, HttpMessageNotReadableException.class,
            MissingServletRequestParameterException.class, MissingServletRequestPartException.class })
    public ResponseEntity<AjaxResult> handleBadRequest(Exception ex)
    {
        return ResponseEntity.badRequest()
                .body(AjaxResult.error(HttpStatus.BAD_REQUEST.value(), "请求参数格式不正确"));
    }

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<AjaxResult> handleUploadSize(MaxUploadSizeExceededException ex)
    {
        return ResponseEntity.status(HttpStatus.PAYLOAD_TOO_LARGE).body(AjaxResult.error(413, "图片大小不能超过5MB"));
    }

    @ExceptionHandler(InvalidExtensionException.class)
    public ResponseEntity<AjaxResult> handleImageExtension(InvalidExtensionException ex)
    {
        return ResponseEntity.status(HttpStatus.UNSUPPORTED_MEDIA_TYPE)
                .body(AjaxResult.error(415, "图片文件扩展名不受支持"));
    }

    @ExceptionHandler(FileNameLengthLimitExceededException.class)
    public ResponseEntity<AjaxResult> handleFileNameLength(FileNameLengthLimitExceededException ex)
    {
        return ResponseEntity.badRequest().body(AjaxResult.error(400, "图片文件名过长"));
    }

    @ExceptionHandler(FileSizeLimitExceededException.class)
    public ResponseEntity<AjaxResult> handleFileSize(FileSizeLimitExceededException ex)
    {
        return ResponseEntity.status(HttpStatus.PAYLOAD_TOO_LARGE)
                .body(AjaxResult.error(413, "图片文件过大"));
    }

    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<AjaxResult> handleForbidden(AccessDeniedException ex)
    {
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(AjaxResult.error(403, "没有权限访问此功能"));
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<AjaxResult> handleConflict(DataIntegrityViolationException ex)
    {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(AjaxResult.error(HttpStatus.CONFLICT.value(), "数据已存在或当前操作违反业务约束"));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<AjaxResult> handleUnexpected(Exception ex)
    {
        log.error("企采通接口发生未处理异常", ex);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(AjaxResult.error(HttpStatus.INTERNAL_SERVER_ERROR.value(), "服务暂时不可用，请稍后重试"));
    }
}
