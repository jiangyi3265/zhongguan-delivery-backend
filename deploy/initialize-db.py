"""Initialize an empty qicai_delivery database with the current schema."""
import argparse
import getpass
from pathlib import Path
import secrets

import bcrypt
import pymysql
from pymysql.constants import CLIENT


MIGRATIONS = [
    "qicai_delivery_upgrade_20260926.sql",
    "qicai_payment_upgrade_20261007.sql",
    "qicai_workflow_upgrade_20261015.sql",
    "qicai_storefront_upgrade_20261017.sql",
    "qicai_commercial_remediation_upgrade_20261017.sql",
    "qicai_extensions_upgrade_20261018.sql",
    "qicai_brand_upgrade_20261019.sql",
    "qicai_product_unit_upgrade_20261031.sql",
    "qicai_forward_warehouse_upgrade_20261101.sql",
    "qicai_sales_names_upgrade_20261102.sql",
]


def execute_script(connection, sql):
    with connection.cursor() as cursor:
        cursor.execute(sql)
        while cursor.nextset():
            pass


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--host", default="127.0.0.1")
    parser.add_argument("--port", type=int, default=3306)
    parser.add_argument("--user", required=True)
    args = parser.parse_args()
    database_password = getpass.getpass("Database password: ")
    admin_password = getpass.getpass("New admin password (8-20 characters): ")
    if not 8 <= len(admin_password) <= 20:
        raise SystemExit("Admin password must contain 8-20 characters")
    if admin_password != getpass.getpass("Confirm admin password: "):
        raise SystemExit("Admin passwords do not match")

    sql_dir = Path(__file__).resolve().parent.parent / "sql"
    scripts = [(sql_dir / "ry_20250522.sql").read_text(encoding="utf-8-sig")]
    # The original file also serves the historical acceptance fixtures.
    schema = (sql_dir / "qicai_delivery.sql").read_text(encoding="utf-8-sig")
    scripts.append(schema.split("INSERT INTO qc_product", 1)[0])
    scripts.extend((sql_dir / name).read_text(encoding="utf-8-sig") for name in MIGRATIONS)

    connection = pymysql.connect(
        host=args.host, port=args.port, user=args.user, password=database_password,
        database="qicai_delivery", charset="utf8mb4", autocommit=True,
        client_flag=CLIENT.MULTI_STATEMENTS,
    )
    try:
        with connection.cursor() as cursor:
            cursor.execute("SELECT GET_LOCK('qicai_delivery_bootstrap', 0)")
            if cursor.fetchone()[0] != 1:
                raise SystemExit("Another initialization is running")
            cursor.execute("SHOW TABLES")
            if cursor.fetchall():
                raise SystemExit("Database must be empty. Use incremental migrations for an existing database.")
        for script in scripts:
            execute_script(connection, script)
        password_hash = bcrypt.hashpw(admin_password.encode(), bcrypt.gensalt()).decode()
        with connection.cursor() as cursor:
            cursor.execute(
                "UPDATE sys_user SET password=%s,pwd_update_date=NOW() WHERE user_id=1",
                (password_hash,),
            )
            cursor.execute(
                "UPDATE sys_config SET config_value=%s WHERE config_key='sys.user.initPassword'",
                (secrets.token_urlsafe(12),),
            )
        print("Initialized qicai_delivery. Admin login is ready. Product and order tables are empty.")
    finally:
        connection.close()


if __name__ == "__main__":
    main()
