// 密钥保存在 Apps Script 的 Script Properties 中，不能写进代码或导出的项目文件。
const SYNC_SECRET = PropertiesService.getScriptProperties().getProperty('SYNC_SECRET');

// Google Sheet Web App 只读入口：仅允许状态检查、标签列表、预览和读取。

function doPost(e) {
  try {
    logInfo('收到同步请求', { hasBody: Boolean(e && e.postData && e.postData.contents) });
    const payload = JSON.parse(e.postData.contents || '{}');
    if (!SYNC_SECRET) {
      logError('同步密钥尚未配置', { action: payload.action });
      return json({ ok: false, message: 'SYNC_SECRET is not configured in Script Properties.' });
    }
    if (payload.secret !== SYNC_SECRET) {
      logError('同步请求鉴权失败', { action: payload.action });
      return json({ ok: false, message: 'Unauthorized.' });
    }
    logInfo('同步请求鉴权成功', {
      action: payload.action,
      spreadsheetId: payload.spreadsheetId,
      sheetGid: payload.sheetGid,
      rows: Array.isArray(payload.rows) ? payload.rows.length : 0,
    });
    const spreadsheet = SpreadsheetApp.openById(payload.spreadsheetId);
    if (payload.action === 'listSheets') {
      const sheets = spreadsheet.getSheets()
        .filter((item) => !item.isSheetHidden() && /^Week\s+\d+/i.test(item.getName().trim()))
        .map((item) => ({
          gid: item.getSheetId(),
          name: item.getName().trim(),
          rowCount: Math.max(0, item.getLastRow() - 1),
          columnCount: item.getLastColumn(),
        }))
        .sort((left, right) => weekNumber(right.name) - weekNumber(left.name));
      return json({ ok: true, sheets: sheets });
    }
    const sheet = getSheetByGid(spreadsheet, payload.sheetGid);
    if (!sheet) {
      logError('未找到目标 Sheet', { spreadsheetId: payload.spreadsheetId, sheetGid: payload.sheetGid });
      return json({ ok: false, message: 'Sheet gid was not found.' });
    }
    if (payload.action === 'append') {
      // 排班 Sheet 的 A-Q 列有固定业务布局，禁止数据库导出格式追加到表尾。
      return json({ ok: false, message: 'Bulk append is disabled for the operational schedule Sheet.' });
    }
    if (payload.action === 'status') {
      logInfo('状态检查完成', { sheetName: sheet.getName() });
      return json({ ok: true, sheetName: sheet.getName() });
    }
    if (payload.action === 'preview' || payload.action === 'readSheet') {
      const values = readSheetValues(sheet);
      const businessRows = values.slice(1).filter(isBusinessRow);
      const dates = businessRows
        .map((row) => text(row[11]))
        .filter((value, index, items) => value && items.indexOf(value) === index);
      const response = {
        ok: true,
        sheetName: sheet.getName(),
        sheetGid: sheet.getSheetId(),
        scannedRows: Math.max(0, values.length - 1),
        businessRows: businessRows.length,
        dates: dates,
      };
      if (payload.action === 'readSheet') {
        response.values = values;
      }
      return json(response);
    }
    if (payload.action === 'updateBookingRow') {
      return json({ ok: false, message: 'Google Sheet is read-only for this application.' });
    }
    return json({ ok: false, message: 'Unsupported action. This Web App is read-only.' });
  } catch (error) {
    logError('Google Sheet 同步异常', {
      message: String(error && error.message ? error.message : error),
      stack: error && error.stack ? error.stack : '',
    });
    return json({ ok: false, message: String(error && error.message ? error.message : error) });
  }
}

function readSheetValues(sheet) {
  const lastRow = sheet.getLastRow();
  const lastColumn = Math.min(Math.max(sheet.getLastColumn(), 16), 20);
  if (lastRow < 1) {
    return [];
  }
  return sheet.getRange(1, 1, lastRow, lastColumn).getDisplayValues();
}

function isBusinessRow(row) {
  return [row[2], row[4], row[5], row[6], row[7]].some((value) => text(value));
}

function weekNumber(name) {
  const match = String(name || '').match(/Week\s+(\d+)/i);
  return match ? Number(match[1]) : 0;
}

function text(value) {
  return value == null ? '' : String(value).trim();
}

function getSheetByGid(spreadsheet, gid) {
  // 通过 gid 精确定位目标工作表，避免 sheet 名称变更导致同步到错误页签。
  const numericGid = Number(gid);
  const sheets = spreadsheet.getSheets();
  for (let i = 0; i < sheets.length; i += 1) {
    if (sheets[i].getSheetId() === numericGid) {
      return sheets[i];
    }
  }
  return null;
}

function json(data) {
  return ContentService
    .createTextOutput(JSON.stringify(data))
    .setMimeType(ContentService.MimeType.JSON);
}

function logInfo(message, detail) {
  console.info('[Schedule SmartUser GoogleSheet] INFO ' + message + ' ' + JSON.stringify(detail || {}));
}

function logError(message, detail) {
  console.error('[Schedule SmartUser GoogleSheet] ERROR ' + message + ' ' + JSON.stringify(detail || {}));
}
