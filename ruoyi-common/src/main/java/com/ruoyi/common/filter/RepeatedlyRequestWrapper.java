package com.ruoyi.common.filter;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import javax.servlet.ReadListener;
import javax.servlet.ServletInputStream;
import javax.servlet.ServletResponse;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletRequestWrapper;
import org.springframework.util.StreamUtils;
import com.ruoyi.common.constant.Constants;

/**
 * 构建可重复读取inputStream的request
 * 
 * @author ruoyi
 */
public class RepeatedlyRequestWrapper extends HttpServletRequestWrapper
{
    private final byte[] body;

    public RepeatedlyRequestWrapper(HttpServletRequest request, ServletResponse response) throws IOException
    {
        super(request);
        request.setCharacterEncoding(Constants.UTF8);
        response.setCharacterEncoding(Constants.UTF8);

        // Signatures cover exact bytes, including indentation and trailing newlines.
        String path = request.getServletPath();
        if ("POST".equals(request.getMethod()) && "/qicai/payment/wechat/notify".equals(path))
        {
            java.io.ByteArrayOutputStream limited = new java.io.ByteArrayOutputStream();
            byte[] buffer = new byte[4096];
            int count;
            while ((count = request.getInputStream().read(buffer)) != -1)
            {
                if (limited.size() + count > 65536) throw new BodyLimitException();
                limited.write(buffer, 0, count);
            }
            body = limited.toByteArray();
        }
        else body = StreamUtils.copyToByteArray(request.getInputStream());
    }

    public static class BodyLimitException extends IOException { private static final long serialVersionUID = 1L; }

    @Override
    public BufferedReader getReader() throws IOException
    {
        return new BufferedReader(new InputStreamReader(getInputStream(), Constants.UTF8));
    }

    @Override
    public ServletInputStream getInputStream() throws IOException
    {
        final ByteArrayInputStream bais = new ByteArrayInputStream(body);
        return new ServletInputStream()
        {
            @Override
            public int read() throws IOException
            {
                return bais.read();
            }

            @Override
            public int available() throws IOException
            {
                return bais.available();
            }

            @Override
            public boolean isFinished()
            {
                return bais.available() == 0;
            }

            @Override
            public boolean isReady()
            {
                return true;
            }

            @Override
            public void setReadListener(ReadListener readListener)
            {

            }
        };
    }
}
