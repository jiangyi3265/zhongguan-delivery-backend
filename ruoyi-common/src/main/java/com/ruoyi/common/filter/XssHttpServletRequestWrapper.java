package com.ruoyi.common.filter;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import javax.servlet.ReadListener;
import javax.servlet.ServletInputStream;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletRequestWrapper;
import org.apache.commons.io.IOUtils;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.ruoyi.common.utils.StringUtils;
import com.ruoyi.common.utils.html.EscapeUtil;

/**
 * XSS过滤处理
 * 
 * @author ruoyi
 */
public class XssHttpServletRequestWrapper extends HttpServletRequestWrapper
{
    private static final ObjectMapper JSON_MAPPER = new ObjectMapper()
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);

    private static final String[] PASSWORD_FIELDS = { "password", "oldPassword", "newPassword" };

    /**
     * @param request
     */
    public XssHttpServletRequestWrapper(HttpServletRequest request)
    {
        super(request);
    }

    @Override
    public String[] getParameterValues(String name)
    {
        String[] values = super.getParameterValues(name);
        if (values != null)
        {
            int length = values.length;
            String[] escapesValues = new String[length];
            for (int i = 0; i < length; i++)
            {
                // 防xss攻击和过滤前后空格
                escapesValues[i] = EscapeUtil.clean(values[i]).trim();
            }
            return escapesValues;
        }
        return super.getParameterValues(name);
    }

    @Override
    public ServletInputStream getInputStream() throws IOException
    {
        // 非json类型，直接返回
        if (!isJsonRequest())
        {
            return super.getInputStream();
        }

        // 为空，直接返回
        String json = IOUtils.toString(super.getInputStream(), "utf-8");
        if (StringUtils.isEmpty(json))
        {
            return super.getInputStream();
        }

        // xss过滤
        json = cleanJson(json);
        byte[] jsonBytes = json.getBytes("utf-8");
        final ByteArrayInputStream bis = new ByteArrayInputStream(jsonBytes);
        return new ServletInputStream()
        {
            @Override
            public boolean isFinished()
            {
                return true;
            }

            @Override
            public boolean isReady()
            {
                return true;
            }

            @Override
            public int available() throws IOException
            {
                return jsonBytes.length;
            }

            @Override
            public void setReadListener(ReadListener readListener)
            {
            }

            @Override
            public int read() throws IOException
            {
                return bis.read();
            }
        };
    }

    /**
     * 密码是供认证和规则校验使用的原始值，不能被HTML过滤器悄悄改写。
     * 只在既有密码写入入口保留对象顶层凭证字符串；其他内容继续使用既有XSS过滤。
     */
    private String cleanJson(String json) throws IOException
    {
        String path = getServletPath();
        if (path != null && path.endsWith("/"))
        {
            path = path.substring(0, path.length() - 1);
        }
        boolean passwordRequest = ("POST".equals(getMethod()) && "/system/user".equals(path))
                || ("PUT".equals(getMethod()) && ("/system/user/resetPwd".equals(path)
                        || "/system/user/profile/updatePwd".equals(path)));
        if (!passwordRequest)
        {
            return EscapeUtil.clean(json).trim();
        }

        JsonNode parsed;
        try
        {
            parsed = JSON_MAPPER.readTree(json);
        }
        catch (JsonProcessingException e)
        {
            // 不泄漏正文，也不让HTML清理把格式错误的JSON修复为有效请求。
            throw new InvalidJsonRequestException();
        }
        if (parsed == null || !parsed.isObject())
        {
            return EscapeUtil.clean(json).trim();
        }

        ObjectNode body = (ObjectNode) parsed;
        Map<String, JsonNode> credentials = new LinkedHashMap<>();
        for (String name : PASSWORD_FIELDS)
        {
            JsonNode value = body.get(name);
            if (value != null && value.isTextual())
            {
                credentials.put(name, value);
                body.putNull(name);
            }
        }
        if (credentials.isEmpty())
        {
            return EscapeUtil.clean(json).trim();
        }

        String cleaned = EscapeUtil.clean(JSON_MAPPER.writeValueAsString(body)).trim();
        JsonNode filtered;
        try
        {
            filtered = JSON_MAPPER.readTree(cleaned);
        }
        catch (JsonProcessingException e)
        {
            // 不回退到未过滤正文，也不允许绑定器忽略尾随内容。
            throw new InvalidJsonRequestException();
        }
        if (filtered == null || !filtered.isObject())
        {
            return cleaned;
        }
        ObjectNode result = (ObjectNode) filtered;
        credentials.forEach(result::set);
        return JSON_MAPPER.writeValueAsString(result);
    }

    public static class InvalidJsonRequestException extends IOException
    {
        private static final long serialVersionUID = 1L;
        public InvalidJsonRequestException() { super("Invalid JSON request"); }
    }

    /**
     * 是否是Json请求
     * 
     * @param request
     */
    public boolean isJsonRequest()
    {
        String header = super.getHeader(HttpHeaders.CONTENT_TYPE);
        return StringUtils.startsWithIgnoreCase(header, MediaType.APPLICATION_JSON_VALUE);
    }
}
