package com.ruoyi.qicai.payment;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@EnableScheduling
@ConditionalOnProperty(name="qicai.wechat-pay.maintenance-enabled",havingValue="true",matchIfMissing=true)
public class PaymentMaintenanceJob
{
    private static final Logger LOG=LoggerFactory.getLogger(PaymentMaintenanceJob.class);
    private final WechatPaymentService payments;
    private final WechatRefundService refunds;
    public PaymentMaintenanceJob(WechatPaymentService payments,WechatRefundService refunds){this.payments=payments;this.refunds=refunds;}
    @Scheduled(initialDelay=60000,fixedDelay=60000)
    public void reconcile()
    {
        try {int retries=payments.expireDueOrders();if(retries>0)LOG.warn("Payment expiry has {} pending retries; see qc_payment_maintenance",retries);}
        catch(RuntimeException ex){LOG.error("Payment expiry job failed; preserved orders require inspection (type={})",ex.getClass().getSimpleName());}
        try {int retries=refunds.refreshDueRefunds();if(retries>0)LOG.warn("Refund reconciliation has {} pending retries; see qc_refund",retries);}
        catch(RuntimeException ex){LOG.error("Refund reconciliation job failed; preserved refunds require inspection (type={})",ex.getClass().getSimpleName());}
    }
}
