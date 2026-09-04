# 复制为 backend-env.local.ps1 后填写真实值；local 文件已被 .gitignore 排除。
$env:MYSQL_URL = "jdbc:mysql://HOST:3306/schedule_smartuser?useUnicode=true&characterEncoding=UTF-8&useSSL=false"
$env:MYSQL_USERNAME = "REPLACE_WITH_DATABASE_USER"
$env:MYSQL_PASSWORD = "REPLACE_WITH_DATABASE_PASSWORD"
$env:GOOGLE_SHEET_WEB_APP_URL = "https://script.google.com/macros/s/REPLACE_WITH_DEPLOYMENT_ID/exec"
$env:GOOGLE_SHEET_WEB_APP_SECRET = "REPLACE_WITH_RANDOM_SECRET"
$env:GOOGLE_SHEET_SPREADSHEET_ID = "REPLACE_WITH_SPREADSHEET_ID"
$env:GOOGLE_SHEET_GID = "REPLACE_WITH_SHEET_GID"
# 仅首次创建或恢复管理员时临时取消下一行注释；成功启动后立即删除该设置。
# $env:SCHEDULE_BOOTSTRAP_ADMIN_PASSWORD = "REPLACE_WITH_A_STRONG_UNIQUE_PASSWORD"
