#!/bin/sh
set -eu

mysql --protocol=socket -uroot -p"${MYSQL_ROOT_PASSWORD}" <<SQL
CREATE USER IF NOT EXISTS 'queryweaver_reader'@'%' IDENTIFIED BY '${QUERYWEAVER_DEMO_MYSQL_READER_PASSWORD}';
REVOKE ALL PRIVILEGES, GRANT OPTION FROM 'queryweaver_reader'@'%';
GRANT SELECT, SHOW VIEW ON product_db.* TO 'queryweaver_reader'@'%';
ALTER USER 'queryweaver_reader'@'%' WITH MAX_USER_CONNECTIONS 20;
FLUSH PRIVILEGES;
SQL
