# Google Sheet 同步部署说明

目标表格：

```text
https://docs.google.com/spreadsheets/d/REPLACE_WITH_SPREADSHEET_ID/edit?gid=REPLACE_WITH_SHEET_GID
```

## 当前实现方式

后端导入成功后会优先使用 Google 服务账号 JSON 同步。如果未配置服务账号，但配置了 Apps Script Web App，则调用 Web App 把本次导入数据追加到目标 Sheet。

当前推荐走 Apps Script Web App 路线，因为它可以使用已登录的 `sucrmprogram@gmail.com` 拥有者权限授权脚本写表。

## 需要部署的脚本

脚本模板：

```text
D:\DOC\07_众方CRM\04_schedule\02_schedule-smartuser\scripts\google-sheet-sync-webapp.gs
```

Webhook 密钥必须通过服务器环境变量提供，不要写入代码或文档：

```text
REPLACE_WITH_RANDOM_SECRET
```

## Google Apps Script 部署步骤

1. 打开目标 Google Sheet。
2. 菜单进入 `扩展程序` -> `Apps Script`。
3. 删除默认代码，把 `scripts\google-sheet-sync-webapp.gs` 的内容粘贴进去。
4. 保存项目，项目名可用 `Schedule SmartUser Sync`。
5. 点击 `部署` -> `新建部署`。
6. 类型选择 `Web 应用`。
7. `执行身份` 选择 `我`。
8. `谁有权访问` 选择 `任何人`。
9. 点击部署，按提示授权。
10. 复制部署结果里的 `/exec` Web App URL。

## 后端环境变量

拿到 Web App URL 后，启动后端前设置：

```powershell
$env:GOOGLE_SHEET_SYNC_ENABLED="true"
$env:GOOGLE_SHEET_WEB_APP_URL="https://script.google.com/macros/s/REPLACE_WITH_DEPLOYMENT_ID/exec"
$env:GOOGLE_SHEET_WEB_APP_SECRET="REPLACE_WITH_RANDOM_SECRET"
```

也可以直接使用启动脚本：

```powershell
cd D:\DOC\07_众方CRM\04_schedule\02_schedule-smartuser
.\scripts\start-backend-google-webapp.ps1 -GoogleSheetWebAppUrl "https://script.google.com/macros/s/REPLACE_WITH_DEPLOYMENT_ID/exec"
```

## 验证同步配置

后端启动后运行：

```powershell
cd D:\DOC\07_众方CRM\04_schedule\02_schedule-smartuser
.\scripts\verify-google-sheet-sync.ps1
```

期望结果：

```text
GoogleSheetStatus  success
GoogleSheetMessage Apps Script Web App connection ok: ...
```

## 前端验证

1. 打开 `Inspection Import` 页面。
2. 点击 `Check Google Sheet`。
3. 页面应显示 Google Sheet 状态成功。
4. 上传 CSV/Excel 文件。
5. 导入成功后页面应显示 `Google Sheet 同步成功`。

## 注意事项

- 不要把 Google 普通账号密码写入后端配置。
- Web App URL 和 Webhook 密钥需要同时配置，后端才会调用 Web App。
- Web App 的访问权限设为 `任何人` 是为了让后端服务器可以调用；实际写入仍由 `SYNC_SECRET` 校验保护。
- 如果重新部署 Apps Script，请使用最新部署的 `/exec` URL。
