"""Read-only QA/live consistency audit. Emits counts, never credentials or PII."""
import json, pathlib, subprocess
MYSQL=['/www/server/mysql/bin/mysql','--defaults-extra-file=/etc/qicai/mysql-root.cnf','--batch','--skip-column-names']
def sql(query, database='qicai_verify_20260926'):
    return subprocess.check_output(MYSQL+[database],input=query,text=True).strip()
checks={
 '订单金额等于明细且应付等于商品额减优惠': "SELECT COUNT(*) FROM qc_order o WHERE o.subtotal_amount<>(SELECT COALESCE(SUM(line_amount),0) FROM qc_order_item WHERE order_id=o.id) OR o.payable_amount<>o.subtotal_amount-o.discount_amount OR o.discount_amount>o.subtotal_amount",
 '库存非负': 'SELECT COUNT(*) FROM qc_product WHERE stock<0',
 '基础商品库存与全部未取消订单相符': "SELECT COUNT(*) FROM qc_product p WHERE p.id IN (1,2,3) AND p.stock<>(CASE p.id WHEN 1 THEN 328 WHEN 2 THEN 510 ELSE 86 END)-(SELECT COALESCE(SUM(i.quantity),0) FROM qc_order_item i JOIN qc_order o ON o.id=i.order_id WHERE i.product_id=p.id AND o.status<>'CANCELLED')",
 '订单幂等唯一': 'SELECT COUNT(*) FROM (SELECT customer_id,idempotency_key FROM qc_order GROUP BY customer_id,idempotency_key HAVING COUNT(*)>1) t',
 '订单与配送状态一致': "SELECT COUNT(*) FROM qc_delivery_task d JOIN qc_order o ON o.id=d.order_id WHERE (d.status='DELIVERED' AND o.status<>'COMPLETED') OR (d.status IN ('ASSIGNED','PICKED_UP','IN_DELIVERY') AND d.status<>o.status)",
 '已使用优惠券归属和订单一致': "SELECT COUNT(*) FROM qc_customer_coupon c LEFT JOIN qc_order o ON o.id=c.used_order_id WHERE c.status='USED' AND (o.id IS NULL OR o.customer_id<>c.customer_id OR o.customer_coupon_id<>c.id OR o.status='CANCELLED')",
 '已取消订单优惠券已退回': "SELECT COUNT(*) FROM qc_customer_coupon c JOIN qc_order o ON o.id=c.used_order_id WHERE o.status='CANCELLED' AND c.status='USED'",
 '签收订单具备姓名及上传凭证': "SELECT COUNT(*) FROM qc_delivery_task WHERE status='DELIVERED' AND (signed_name IS NULL OR signed_name='' OR delivery_proof_url IS NULL OR delivery_proof_url='')",
 '所有凭证有真实上传登记': 'SELECT COUNT(*) FROM qc_delivery_attachment a LEFT JOIN qc_upload u ON u.file_name=a.file_name WHERE u.file_name IS NULL',
 '凭证上传者是该订单客户或配送员': "SELECT COUNT(*) FROM qc_delivery_attachment a JOIN qc_upload u ON u.file_name=a.file_name JOIN qc_delivery_task d ON d.id=a.delivery_id JOIN qc_order o ON o.id=d.order_id WHERE NOT ((u.principal_type='courier' AND u.principal_id=d.courier_id) OR (u.principal_type='customer' AND u.principal_id=o.customer_id))",
 '会话只储存Token哈希': 'SELECT COUNT(*) FROM qc_session WHERE LENGTH(token_hash)<>64',
 '停用删除账号没有有效会话': "SELECT COUNT(*) FROM qc_session s LEFT JOIN qc_customer c ON s.principal_type='customer' AND c.id=s.principal_id LEFT JOIN qc_courier d ON s.principal_type='courier' AND d.id=s.principal_id WHERE (s.principal_type='customer' AND (c.deleted=1 OR c.status IN ('DISABLED','REJECTED'))) OR (s.principal_type='courier' AND (d.deleted=1 OR d.status='DISABLED'))",
}
results=[{'name':name,'violations':int(sql(query))} for name,query in checks.items()]
paths=sql('SELECT DISTINCT file_name FROM qc_delivery_attachment').splitlines()
missing=[]
for value in paths:
    if not value.startswith('/profile/'): missing.append(value); continue
    physical=pathlib.Path('/www/qicai/verification-20260926/uploads')/value[len('/profile/'):]
    if not physical.is_file(): missing.append(value)
results.append({'name':'凭证实际文件存在','violations':len(missing)})
report={'checks':results,'passed':sum(r['violations']==0 for r in results),'total':len(results),'qaOrders':int(sql('SELECT COUNT(*) FROM qc_order')),'qaAttachments':len(paths),'liveCustomers':int(sql('SELECT COUNT(*) FROM qc_customer','qicai_delivery')),'liveOrders':int(sql('SELECT COUNT(*) FROM qc_order','qicai_delivery'))}
pathlib.Path('/www/qicai/verification-20260926/db-results.json').write_text(json.dumps(report,ensure_ascii=False,indent=2))
print(json.dumps(report,ensure_ascii=False))
if report['passed']!=report['total']: raise SystemExit(1)
