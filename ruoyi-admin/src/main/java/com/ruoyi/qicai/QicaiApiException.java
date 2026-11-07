package com.ruoyi.qicai;

import org.springframework.http.HttpStatus;

public class QicaiApiException extends RuntimeException
{
    private static final long serialVersionUID = 1L;

    private final HttpStatus status;

    public QicaiApiException(HttpStatus status, String message)
    {
        super(message);
        this.status = status;
    }

    public HttpStatus getStatus()
    {
        return status;
    }
}

