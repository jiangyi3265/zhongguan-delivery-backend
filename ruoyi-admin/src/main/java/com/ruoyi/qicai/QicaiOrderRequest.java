package com.ruoyi.qicai;

import java.io.IOException;
import java.util.LinkedHashMap;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;

/** Only order coordinates need exact decimal tokens before range/scale validation. */
@JsonDeserialize(using=QicaiOrderRequest.Deserializer.class)
public class QicaiOrderRequest extends LinkedHashMap<String,Object>
{
    private static final long serialVersionUID = 1L;

    public static class Deserializer extends JsonDeserializer<QicaiOrderRequest>
    {
        @Override public QicaiOrderRequest deserialize(JsonParser parser, DeserializationContext context) throws IOException
        {
            if (!parser.isExpectedStartObjectToken())
                throw JsonMappingException.from(parser,"订单请求必须为JSON对象");
            QicaiOrderRequest result=new QicaiOrderRequest();
            while (parser.nextToken()!=JsonToken.END_OBJECT)
            {
                String field=parser.currentName();
                JsonToken value=parser.nextToken();
                if (("deliveryLatitude".equals(field)||"deliveryLongitude".equals(field)||"latitude".equals(field)||"longitude".equals(field))
                        && value!=null && value.isNumeric())
                    result.put(field,parser.getDecimalValue());
                else
                    result.put(field,parser.readValueAs(Object.class));
            }
            return result;
        }
    }
}
