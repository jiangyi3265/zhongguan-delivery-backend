package com.ruoyi.qicai;

import java.math.BigDecimal;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;

/** Exact inventory arithmetic shared by cancellation and confirmed refund recovery. */
public final class QicaiInventory
{
    public static final long MAX_STOCK = 9007199254740991L;
    private QicaiInventory() { }

    public static long input(Object raw)
    {
        try
        {
            // Untyped JSON decimals arrive as Double and may already have rounded
            // a fractional or out-of-range input to a safe integer. Never trust
            // that lossy representation for a discrete inventory quantity.
            if (raw instanceof Float || raw instanceof Double) throw new ArithmeticException();
            if (!(raw instanceof Number) && !(raw instanceof String && ((String) raw).matches("[0-9]+")))
                throw new ArithmeticException();
            long value = new BigDecimal(raw.toString()).longValueExact();
            if (value < 0 || value > MAX_STOCK) throw new ArithmeticException();
            return value;
        }
        catch (RuntimeException ex)
        {
            throw new QicaiApiException(HttpStatus.BAD_REQUEST, "库存必须为0至9007199254740991之间的整数");
        }
    }

    /** Caller holds the product row in a READ_COMMITTED transaction. Do not lock orders here. */
    public static void requireRecoveryCapacity(JdbcTemplate jdbc, long productId, long available)
    {
        BigDecimal reserved = jdbc.queryForObject(
                "SELECT COALESCE(SUM(i.quantity),0) FROM qc_order_item i JOIN qc_order o ON o.id=i.order_id WHERE i.product_id=? AND o.status IN ('PENDING_PAYMENT','PENDING_SHIPMENT')",
                BigDecimal.class, productId);
        if (reserved == null || reserved.signum() < 0
                || reserved.add(BigDecimal.valueOf(available)).compareTo(BigDecimal.valueOf(MAX_STOCK)) > 0)
            throw new QicaiApiException(HttpStatus.CONFLICT, "当前未完成订单仍占用库存，请预留取消或退款的回补空间");
    }

    /** Caller holds the order lock in its business transaction. No clamp or partial commit. */
    public static void restoreOrder(JdbcTemplate jdbc, long orderId)
    {
        if (!org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive())
            throw new IllegalStateException("Inventory recovery requires the order transaction");
        for (Map<String, Object> item : jdbc.queryForList(
                "SELECT product_id,SUM(quantity) AS quantity FROM qc_order_item WHERE order_id=? GROUP BY product_id ORDER BY product_id", orderId))
        {
            long quantity;
            try { quantity = new BigDecimal(item.get("quantity").toString()).longValueExact(); }
            catch (RuntimeException ex) { throw new QicaiApiException(HttpStatus.CONFLICT, "订单库存数量异常，请联系管理员核查"); }
            if (quantity <= 0 || quantity > MAX_STOCK || jdbc.update(
                    "UPDATE qc_product SET stock=stock+?,version=version+1 WHERE id=? AND stock>=0 AND stock<=?",
                    quantity, item.get("product_id"), MAX_STOCK - quantity) != 1)
                throw new QicaiApiException(HttpStatus.CONFLICT, "库存回补超出安全范围，请联系管理员核查");
        }
    }
}
