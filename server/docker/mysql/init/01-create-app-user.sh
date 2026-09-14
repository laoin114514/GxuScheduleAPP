#!/bin/sh
# 首次初始化（数据卷为空）时由 mysql 官方 entrypoint 执行。
#
# 注意：挂载进容器的脚本通常没有可执行位，此时 entrypoint 是用 `.` 加载本脚本的，
# 所以这里不能用 `exit` / `set -e`，否则会提前终止 entrypoint 让 mysqld 起不来。
#
# MYSQL_DATABASE 已建好库，这里只负责按 .env 的 DB_USER 建业务账号：
#   - DB_USER=root：官方镜像已建 root@'%'，直接跳过
#   - DB_USER=其他：建同名账号并授权该库（对应 .env.example 的 schedule 用户）
if [ -z "$DB_USER" ] || [ "$DB_USER" = "root" ]; then
  echo "[init] DB_USER 为空或 root，沿用官方镜像创建的 root 账号"
else
  echo "[init] 创建账号 ${DB_USER} 并授权 ${DB_NAME}.*"
  mysql --protocol=socket -uroot -p"$MYSQL_ROOT_PASSWORD" <<SQL
CREATE USER IF NOT EXISTS '$DB_USER'@'%' IDENTIFIED BY '$DB_PASSWORD';
GRANT ALL PRIVILEGES ON \`$DB_NAME\`.* TO '$DB_USER'@'%';
FLUSH PRIVILEGES;
SQL
fi
