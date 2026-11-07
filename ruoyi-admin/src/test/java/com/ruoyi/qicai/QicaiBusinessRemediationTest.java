package com.ruoyi.qicai;

import java.math.BigDecimal;
import java.util.Arrays;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class QicaiBusinessRemediationTest
{
    @Test void inventoryAcceptsExactSafeIntegersWithoutNarrowingToInt()
    {
        assertEquals(0,QicaiInventory.input(0));assertEquals(2147483648L,QicaiInventory.input(2147483648L));
        assertEquals(QicaiInventory.MAX_STOCK,QicaiInventory.input(new BigDecimal("9007199254740991")));
        assertEquals(12,QicaiInventory.input("12"));assertEquals(12,QicaiInventory.input(new BigDecimal("12.0")));
    }

    @Test void inventoryRejectsOverflowFractionAndNonFiniteValuesInsteadOfClamping()
    {
        for(Object invalid:Arrays.asList(null,true,-1,1.1d,1.0d,1.0f,9007199254740990.5d,9007199254740991.1d,Double.NaN,Double.POSITIVE_INFINITY,
                new BigDecimal("9007199254740992"),"999999999999999999999999","1e3","1.1"))
            assertEquals(org.springframework.http.HttpStatus.BAD_REQUEST,
                    assertThrows(QicaiApiException.class,()->QicaiInventory.input(invalid)).getStatus());
    }

    @Test void orderRequestPreservesExactCoordinateTokensWithoutChangingOtherValueTypes() throws Exception
    {
        ObjectMapper mapper=new ObjectMapper();
        QicaiOrderRequest request=mapper.readValue("{\"deliveryLatitude\":90.000000000000001,\"deliveryLongitude\":180.000000000000001,\"quantity\":2,\"otherDecimal\":1.25,\"deliveryLocationName\":\"地点\"}",QicaiOrderRequest.class);
        assertEquals(new BigDecimal("90.000000000000001"),request.get("deliveryLatitude"));
        assertEquals(new BigDecimal("180.000000000000001"),request.get("deliveryLongitude"));
        assertEquals(2,request.get("quantity"));assertEquals(1.25d,request.get("otherDecimal"));assertEquals("地点",request.get("deliveryLocationName"));
        QicaiOrderRequest invalid=mapper.readValue("{\"deliveryLatitude\":\"22.5\",\"deliveryLongitude\":null,\"nested\":{\"value\":1}}",QicaiOrderRequest.class);
        assertEquals("22.5",invalid.get("deliveryLatitude"));assertNull(invalid.get("deliveryLongitude"));
        assertThrows(com.fasterxml.jackson.databind.JsonMappingException.class,()->mapper.readValue("[]",QicaiOrderRequest.class));
    }

    @Test void exactCoordinatesRejectLargeExponentsBeforeScalingAndAcceptCanonicalZero() throws Exception
    {
        java.lang.reflect.Method coordinate=QicaiService.class.getDeclaredMethod("coordinate",Object.class,int.class);coordinate.setAccessible(true);
        QicaiService service=new QicaiService(null,null,null,null);
        for(String raw:Arrays.asList("1e10000","-1e10000","1e-10000","-1e-10000"))
        {
            java.lang.reflect.InvocationTargetException failure=assertThrows(java.lang.reflect.InvocationTargetException.class,()->coordinate.invoke(service,new BigDecimal(raw),90));
            assertTrue(failure.getCause() instanceof QicaiApiException);assertEquals(org.springframework.http.HttpStatus.BAD_REQUEST,((QicaiApiException)failure.getCause()).getStatus());
        }
        for(String raw:Arrays.asList("0e10000","0e-10000"))assertEquals(new BigDecimal("0.0000000"),coordinate.invoke(service,new BigDecimal(raw),90));
    }
}
