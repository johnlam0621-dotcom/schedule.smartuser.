package com.smartuser.schedule.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartuser.schedule.common.BadRequestException;
import com.smartuser.schedule.config.GoogleSheetProperties;
import com.smartuser.schedule.model.GoogleSheetSyncResult;
import com.smartuser.schedule.model.InspectionRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.util.StringUtils;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriUtils;

import java.io.File;
import java.io.IOException;
import java.net.ConnectException;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.Signature;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Comparator;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Google Sheet 同步服务。
 *
 * 功能作用：
 * 1. 只读访问 Google Sheet：列出 Week 标签、预览并读取排班数据到本地数据库。
 * 2. 支持 Apps Script Web App 或服务账号读取连接状态，不对源 Sheet 执行写入。
 * 3. 提供连接状态检查、网络异常中文提示和访问 token 缓存。
 *
 * 安全说明：
 * - Google Sheets API 不能使用邮箱密码直接登录，必须使用服务账号 JSON 或 Apps Script Web App secret。
 * - 日志只输出配置状态和行数，不输出 secret、private_key、access_token 等敏感信息。
 */
@Service
public class GoogleSheetSyncService {
  private static final Logger LOG = LoggerFactory.getLogger(GoogleSheetSyncService.class);
  private static final String SHEETS_API = "https://sheets.googleapis.com/v4/spreadsheets";
  private static final String DEFAULT_TOKEN_URI = "https://oauth2.googleapis.com/token";
  // 服务账号只申请只读权限，从 OAuth 层面阻止任何 Google Sheet 写入。
  private static final String SHEETS_SCOPE = "https://www.googleapis.com/auth/spreadsheets.readonly";
  private final GoogleSheetProperties properties;
  private final ObjectMapper objectMapper;
  private final RestTemplate restTemplate;
  private final RestTemplate webAppRestTemplate;
  private volatile String accessToken;
  private volatile long accessTokenExpiresAtMillis;
  private volatile String cachedSheetName;

  public GoogleSheetSyncService(GoogleSheetProperties properties, ObjectMapper objectMapper) {
    this.properties = properties;
    this.objectMapper = objectMapper;
    this.restTemplate = buildRestTemplate(properties, true);
    // Apps Script 对 POST 请求先返回 302；必须保留 Location，再以 GET 读取最终 JSON。
    // HttpURLConnection 自动跟随时可能继续使用错误的方法，最终得到空响应或 404。
    this.webAppRestTemplate = buildRestTemplate(properties, false);
  }

  public List<Map<String, Object>> listImportSheets() {
    // Prefer the service account whenever it is configured. Status checks already use
    // this order; keeping list/read on the same connection prevents a healthy status
    // check followed by a failed Apps Script import when both settings are present.
    if (hasServiceAccountCredentials()) {
      return listImportSheetsWithServiceAccount();
    }
    Map response = callImportWebApp("listSheets", null);
    Object sheets = response.get("sheets");
    if (!(sheets instanceof List)) {
      throw new BadRequestException("Google Sheet tab list is empty.");
    }
    List<Map<String, Object>> result = new ArrayList<Map<String, Object>>();
    for (Object item : (List<?>) sheets) {
      if (item instanceof Map) {
        result.add(new LinkedHashMap<String, Object>((Map<String, Object>) item));
      }
    }
    return result;
  }

  public Map<String, Object> previewImportSheet(Long sheetGid) {
    if (hasServiceAccountCredentials()) {
      return readImportSheetWithServiceAccount(sheetGid, false);
    }
    return callImportWebApp("preview", sheetGid);
  }

  public Map<String, Object> readImportSheet(Long sheetGid) {
    if (hasServiceAccountCredentials()) {
      return readImportSheetWithServiceAccount(sheetGid, true);
    }
    return callImportWebApp("readSheet", sheetGid);
  }

  /**
   * 使用只读 OAuth scope 直接列出工作表，不要求生产服务器保存 Apps Script secret。
   */
  private List<Map<String, Object>> listImportSheetsWithServiceAccount() {
    try {
      String token = getAccessToken();
      List<Map<String, Object>> sheets = readSheetMetadata(token);
      List<Map<String, Object>> result = new ArrayList<Map<String, Object>>();
      for (Map<String, Object> sheet : sheets) {
        String name = string(sheet.get("name"));
        if (Boolean.TRUE.equals(sheet.get("hidden")) || !name.matches("(?i)^Week\\s+\\d+.*")) {
          continue;
        }
        result.add(sheet);
      }
      result.sort(Comparator.comparingInt(
          (Map<String, Object> item) -> weekNumber(string(item.get("name")))).reversed());
      return result;
    } catch (BadRequestException ex) {
      throw ex;
    } catch (Exception ex) {
      throw new BadRequestException("Google Sheet import failed: " + googleSheetErrorMessage(ex));
    }
  }

  /**
   * 通过服务账号以只读方式预览或读取指定 gid；本方法不会调用任何写入 API。
   */
  private Map<String, Object> readImportSheetWithServiceAccount(Long sheetGid, boolean includeValues) {
    if (sheetGid == null) {
      throw new BadRequestException("A Google Sheet tab must be selected.");
    }
    try {
      String token = getAccessToken();
      Map<String, Object> selected = null;
      for (Map<String, Object> sheet : readSheetMetadata(token)) {
        if (String.valueOf(sheetGid).equals(String.valueOf(sheet.get("gid")))) {
          selected = sheet;
          break;
        }
      }
      if (selected == null) {
        throw new BadRequestException("Sheet gid was not found.");
      }
      String sheetName = string(selected.get("name"));
      List<List<Object>> values = readSheetValues(token, sheetName);
      List<List<Object>> businessRows = new ArrayList<List<Object>>();
      List<String> dates = new ArrayList<String>();
      for (int index = 1; index < values.size(); index++) {
        List<Object> row = values.get(index);
        if (!isImportableRow(row)) {
          continue;
        }
        businessRows.add(row);
        String date = cell(row, 11);
        if (StringUtils.hasText(date) && !dates.contains(date)) {
          dates.add(date);
        }
      }
      Map<String, Object> response = new LinkedHashMap<String, Object>();
      response.put("ok", true);
      response.put("sheetName", sheetName);
      response.put("sheetGid", sheetGid);
      response.put("scannedRows", Math.max(0, values.size() - 1));
      response.put("businessRows", businessRows.size());
      response.put("dates", dates);
      if (includeValues) {
        response.put("values", values);
      }
      return response;
    } catch (BadRequestException ex) {
      throw ex;
    } catch (Exception ex) {
      throw new BadRequestException("Google Sheet import failed: " + googleSheetErrorMessage(ex));
    }
  }

  private List<Map<String, Object>> readSheetMetadata(String token) {
    String url = SHEETS_API + "/" + path(properties.getSpreadsheetId())
        + "?fields=sheets.properties(sheetId,title,hidden,gridProperties(rowCount,columnCount))";
    ResponseEntity<Map> response = restTemplate.exchange(url, HttpMethod.GET,
        new HttpEntity<Void>(authHeaders(token)), Map.class);
    Object sheetsValue = response.getBody() == null ? null : response.getBody().get("sheets");
    if (!(sheetsValue instanceof List)) {
      throw new IllegalStateException("Google spreadsheet metadata response is empty.");
    }
    List<Map<String, Object>> result = new ArrayList<Map<String, Object>>();
    for (Object sheetValue : (List<?>) sheetsValue) {
      if (!(sheetValue instanceof Map)) {
        continue;
      }
      Object propertiesValue = ((Map<?, ?>) sheetValue).get("properties");
      if (!(propertiesValue instanceof Map)) {
        continue;
      }
      Map<?, ?> sheetProperties = (Map<?, ?>) propertiesValue;
      Object gridValue = sheetProperties.get("gridProperties");
      Map<?, ?> grid = gridValue instanceof Map ? (Map<?, ?>) gridValue : new LinkedHashMap<Object, Object>();
      Map<String, Object> item = new LinkedHashMap<String, Object>();
      item.put("gid", sheetProperties.get("sheetId"));
      item.put("name", string(sheetProperties.get("title")).trim());
      item.put("hidden", Boolean.TRUE.equals(sheetProperties.get("hidden")));
      item.put("rowCount", number(grid.get("rowCount")));
      item.put("columnCount", number(grid.get("columnCount")));
      result.add(item);
    }
    return result;
  }

  private List<List<Object>> readSheetValues(String token, String sheetName) {
    String range = quoteSheetName(sheetName) + "!A:T";
    String url = SHEETS_API + "/" + path(properties.getSpreadsheetId()) + "/values/"
        + UriUtils.encodePathSegment(range, StandardCharsets.UTF_8)
        + "?valueRenderOption=FORMATTED_VALUE&dateTimeRenderOption=FORMATTED_STRING";
    // 使用 URI 重载，避免 RestTemplate 把已编码的 %20 再编码成 %2520，导致 Google 返回 400。
    ResponseEntity<Map> response = restTemplate.exchange(URI.create(url), HttpMethod.GET,
        new HttpEntity<Void>(authHeaders(token)), Map.class);
    Object valuesValue = response.getBody() == null ? null : response.getBody().get("values");
    List<List<Object>> result = new ArrayList<List<Object>>();
    if (!(valuesValue instanceof List)) {
      return result;
    }
    for (Object rowValue : (List<?>) valuesValue) {
      if (rowValue instanceof List) {
        result.add(new ArrayList<Object>((List<?>) rowValue));
      }
    }
    return result;
  }

  private boolean isBusinessRow(List<?> row) {
    return StringUtils.hasText(cell(row, 2)) || StringUtils.hasText(cell(row, 4))
        || StringUtils.hasText(cell(row, 5)) || StringUtils.hasText(cell(row, 6))
        || StringUtils.hasText(cell(row, 7));
  }

  private boolean isImportableRow(List<?> row) {
    // A blank customer row with a date and time is a real bookable Sheet space.
    // Include it in previews and date discovery even though it has no CRM fields.
    return isBusinessRow(row)
        || (StringUtils.hasText(cell(row, 11)) && StringUtils.hasText(cell(row, 12)));
  }

  private String cell(List<?> row, int index) {
    return row != null && index >= 0 && index < row.size() ? string(row.get(index)).trim() : "";
  }

  private int weekNumber(String name) {
    Matcher matcher = Pattern.compile("(?i)Week\\s+(\\d+)").matcher(name == null ? "" : name);
    return matcher.find() ? Integer.parseInt(matcher.group(1)) : 0;
  }

  private int number(Object value) {
    return value instanceof Number ? ((Number) value).intValue() : 0;
  }

  public GoogleSheetSyncResult updateBookingRow(InspectionRecord record) {
    // Google Sheet 是只读来源；预约、取消、改期全部只保存在应用数据库。
    return GoogleSheetSyncResult.skipped("Google Sheet is read-only; booking changes are stored in the application only.");
  }

  private Map<String, Object> callImportWebApp(String action, Long sheetGid) {
    if (!StringUtils.hasText(properties.getSpreadsheetId()) || !hasWebAppConfig()) {
      throw new BadRequestException("Google Sheet import requires the Apps Script Web App URL and secret.");
    }
    Map<String, Object> body = new LinkedHashMap<String, Object>();
    body.put("secret", properties.getWebAppSecret());
    body.put("action", action);
    body.put("spreadsheetId", properties.getSpreadsheetId());
    if (sheetGid != null) {
      body.put("sheetGid", sheetGid);
    }
    try {
      Map response = postWebApp(body);
      if (!Boolean.TRUE.equals(response.get("ok"))) {
        throw new BadRequestException(string(response.get("message")));
      }
      return new LinkedHashMap<String, Object>(response);
    } catch (BadRequestException ex) {
      throw ex;
    } catch (Exception ex) {
      throw new BadRequestException("Google Sheet import failed: " + googleSheetErrorMessage(ex));
    }
  }

  public GoogleSheetSyncResult syncImport(List<InspectionRecord> records) {
    // 排班 Sheet 是业务源表，禁止把数据库导出格式追加到表尾，否则会破坏原有 A-Q 列布局。
    // 所有导入与预约写操作都被禁用；Google Sheet 仅用于读取排班数据。
    LOG.info("Skipping Google Sheet write rows={}: Google Sheet is a read-only data source",
        records == null ? 0 : records.size());
    return GoogleSheetSyncResult.skipped(
        "Google Sheet is read-only; imported data and booking changes stay in the application database.");
  }

  public GoogleSheetSyncResult checkStatus() {
    // 前端状态检查入口：只验证连接和目标 Sheet 可访问，不写入业务数据。
    LOG.info("Checking Google Sheet connection status");
    GoogleSheetSyncResult readiness = validateConfiguration();
    if (!"success".equals(readiness.getStatus())) {
      LOG.info("Google Sheet status-check configuration is not ready status={} message={}", readiness.getStatus(), readiness.getMessage());
      return readiness;
    }
    try {
      if (hasWebAppConfig() && !hasServiceAccountCredentials()) {
        LOG.info("Checking Google Sheet status through the Apps Script Web App");
        return checkWebAppStatus();
      }
      LOG.info("Checking Google Sheet status through the service account");
      String token = getAccessToken();
      String sheetName = resolveSheetName(token);
      return GoogleSheetSyncResult.success(0, "Google Sheet connection ok: " + sheetName);
    } catch (Exception ex) {
      return failGoogleSheet("check status", ex);
    }
  }

  private RestTemplate buildRestTemplate(GoogleSheetProperties properties, boolean followRedirects) {
    // Google 请求统一设置连接和读取超时，避免网络不可达时导入流程长时间挂起。
    SimpleClientHttpRequestFactory factory = followRedirects
        ? new SimpleClientHttpRequestFactory()
        : new NoRedirectClientHttpRequestFactory();
    factory.setConnectTimeout(positiveOrDefault(properties.getConnectTimeoutMillis(), 10000));
    factory.setReadTimeout(positiveOrDefault(properties.getReadTimeoutMillis(), 60000));
    return new RestTemplate(factory);
  }

  private int positiveOrDefault(int value, int defaultValue) {
    // 配置值小于等于 0 时使用默认超时，避免 RestTemplate 出现无效 timeout。
    return value > 0 ? value : defaultValue;
  }

  private GoogleSheetSyncResult failGoogleSheet(String action, Exception ex) {
    // 所有 Google 同步异常统一转换为前端可读的中文消息，同时保留 error 堆栈日志。
    String message = googleSheetErrorMessage(ex);
    LOG.error("Google Sheet {} failed: {}", action, message, ex);
    return GoogleSheetSyncResult.failed(message);
  }

  private String googleSheetErrorMessage(Throwable throwable) {
    // 根据异常链识别连接超时、DNS、HTTP 状态等常见问题，减少前端展示底层英文堆栈。
    if (hasCause(throwable, ConnectException.class) || hasCause(throwable, SocketTimeoutException.class)) {
      return "The Google Sheet connection timed out. Check access to script.google.com or try again later.";
    }
    if (hasCause(throwable, UnknownHostException.class)) {
      return "Google Sheet DNS resolution failed. Check the network or DNS configuration.";
    }
    if (hasCause(throwable, ResourceAccessException.class)) {
      return "The Google Sheet connection failed. Check network access to Google Apps Script.";
    }
    HttpStatusCodeException statusException = findCause(throwable, HttpStatusCodeException.class);
    if (statusException != null) {
      HttpStatus status = statusException.getStatusCode();
      return "The Google Sheet request failed: HTTP " + status.value() + " " + status.getReasonPhrase() + ".";
    }
    String message = throwable == null ? "" : throwable.getMessage();
    return StringUtils.hasText(message) ? message : "Google Sheet synchronization failed.";
  }

  private boolean hasCause(Throwable throwable, Class<? extends Throwable> causeType) {
    return findCause(throwable, causeType) != null;
  }

  private <T extends Throwable> T findCause(Throwable throwable, Class<T> causeType) {
    Throwable current = throwable;
    while (current != null) {
      if (causeType.isInstance(current)) {
        return causeType.cast(current);
      }
      current = current.getCause();
    }
    return null;
  }

  private GoogleSheetSyncResult validateConfiguration() {
    // 同步前必须具备 spreadsheetId，并至少配置一种凭据方式。
    if (!properties.isEnabled()) {
      return GoogleSheetSyncResult.skipped("Google Sheet sync is disabled.");
    }
    if (!StringUtils.hasText(properties.getSpreadsheetId())) {
      return GoogleSheetSyncResult.failed("Google spreadsheet id is not configured.");
    }
    if (!hasServiceAccountCredentials() && !hasWebAppConfig()) {
      return GoogleSheetSyncResult.failed("Google synchronization credentials are not configured. Set the service-account JSON or configure GOOGLE_SHEET_WEB_APP_URL and GOOGLE_SHEET_WEB_APP_SECRET. The Google Sheets API cannot sign in with an email address and password.");
    }
    return GoogleSheetSyncResult.success(0, "Google Sheet sync is configured.");
  }

  private boolean hasServiceAccountCredentials() {
    // 服务账号支持文件路径、JSON 原文、Base64 三种配置方式，便于本地和部署环境复用。
    return StringUtils.hasText(properties.getServiceAccountFile())
        || StringUtils.hasText(properties.getServiceAccountJson())
        || StringUtils.hasText(properties.getServiceAccountBase64());
  }

  private boolean hasWebAppConfig() {
    // Apps Script Web App 方式只需要 URL 和 secret，适合没有服务账号 JSON 的环境。
    return StringUtils.hasText(properties.getWebAppUrl()) && StringUtils.hasText(properties.getWebAppSecret());
  }

  private GoogleSheetSyncResult checkWebAppStatus() {
    // Web App 状态检查使用 action=status，只确认脚本可访问和目标 sheet 可定位。
    LOG.info("Preparing the Apps Script Web App status check");
    Map<String, Object> body = new LinkedHashMap<String, Object>();
    body.put("secret", properties.getWebAppSecret());
    body.put("action", "status");
    body.put("spreadsheetId", properties.getSpreadsheetId());
    body.put("sheetGid", properties.getSheetGid());
    Map response = postWebApp(body);
    Object ok = response.get("ok");
    if (Boolean.TRUE.equals(ok)) {
      LOG.info("Apps Script Web App status check succeeded sheetName={}", string(response.get("sheetName")));
      return GoogleSheetSyncResult.success(0, "Apps Script Web App connection ok: " + string(response.get("sheetName")));
    }
    LOG.error("Apps Script Web App status check failed message={}", string(response.get("message")));
    return GoogleSheetSyncResult.failed(string(response.get("message")));
  }

  private Map postWebApp(Map<String, Object> body) {
    // Google Apps Script 通常先返回 302 Location；后端必须手动 GET 跳转地址读取 JSON。
    LOG.info("Sending Apps Script Web App request action={} spreadsheetId={} sheetGid={}",
        string(body.get("action")), properties.getSpreadsheetId(), properties.getSheetGid());
    ResponseEntity<String> response = postWebAppResponse(properties.getWebAppUrl(), body);
    String responseBody = response.getBody();
    if (response.getStatusCode().is3xxRedirection() && response.getHeaders().getLocation() != null) {
      LOG.info("Apps Script Web App returned an HTTP redirect; reading the googleusercontent JSON response");
      responseBody = getWebAppBody(response.getHeaders().getLocation().toString());
    }
    if (!StringUtils.hasText(responseBody)) {
      throw new IllegalStateException("Google Apps Script Web App response is empty.");
    }
    if (!looksLikeJson(responseBody)) {
      String redirectUrl = extractRedirectUrl(responseBody);
      if (StringUtils.hasText(redirectUrl)) {
        LOG.info("Apps Script Web App returned a redirect page; requesting the googleusercontent response");
        responseBody = getWebAppBody(redirectUrl);
      }
    }
    try {
      return objectMapper.readValue(responseBody, new TypeReference<Map<String, Object>>() {});
    } catch (Exception ex) {
      throw new IllegalStateException("Google Apps Script Web App response is not JSON: " + safePreview(responseBody));
    }
  }

  private ResponseEntity<String> postWebAppResponse(String url, Map<String, Object> body) {
    // Web App 请求统一使用 JSON body，secret 放在 body 中由 Apps Script 校验。
    HttpHeaders headers = new HttpHeaders();
    headers.setContentType(MediaType.APPLICATION_JSON);
    return webAppRestTemplate.postForEntity(url,
        new HttpEntity<Map<String, Object>>(body, headers), String.class);
  }

  private String getWebAppBody(String url) {
    // 处理 Apps Script 跳转后的 googleusercontent 响应体。
    ResponseEntity<String> response = restTemplate.getForEntity(url, String.class);
    return response.getBody();
  }

  private boolean looksLikeJson(String value) {
    return StringUtils.hasText(value) && value.trim().startsWith("{");
  }

  private String extractRedirectUrl(String responseBody) {
    // 从 Apps Script 返回的 HTML 中提取 HREF 跳转地址，兼容大小写 href。
    if (!StringUtils.hasText(responseBody)) {
      return "";
    }
    String marker = "HREF=\"";
    int start = responseBody.indexOf(marker);
    if (start < 0) {
      marker = "href=\"";
      start = responseBody.indexOf(marker);
    }
    if (start < 0) {
      return "";
    }
    start += marker.length();
    int end = responseBody.indexOf('"', start);
    if (end <= start) {
      return "";
    }
    return responseBody.substring(start, end).replace("&amp;", "&");
  }

  private String safePreview(String value) {
    // JSON 解析失败时只截取前 200 字用于日志/异常，避免输出完整 HTML 或敏感内容。
    if (value == null) {
      return "";
    }
    String normalized = value.replace('\r', ' ').replace('\n', ' ').trim();
    return normalized.length() > 200 ? normalized.substring(0, 200) + "..." : normalized;
  }

  /**
   * 禁止 HttpURLConnection 自动跟随 POST 重定向，避免 Apps Script 的 302 被错误转换。
   */
  private static class NoRedirectClientHttpRequestFactory extends SimpleClientHttpRequestFactory {
    @Override
    protected void prepareConnection(HttpURLConnection connection, String httpMethod) throws IOException {
      super.prepareConnection(connection, httpMethod);
      connection.setInstanceFollowRedirects(false);
    }
  }

  private String getAccessToken() throws Exception {
    // 服务账号 access_token 做内存缓存，过期前 60 秒刷新，减少 OAuth 请求次数。
    long now = System.currentTimeMillis();
    if (StringUtils.hasText(accessToken) && now < accessTokenExpiresAtMillis - 60000L) {
      return accessToken;
    }
    synchronized (this) {
      now = System.currentTimeMillis();
      if (StringUtils.hasText(accessToken) && now < accessTokenExpiresAtMillis - 60000L) {
        return accessToken;
      }
      Map<String, Object> credentials = readServiceAccount();
      String tokenUri = string(credentials.get("token_uri"));
      if (!StringUtils.hasText(tokenUri)) {
        tokenUri = DEFAULT_TOKEN_URI;
      }
      String assertion = buildJwt(credentials, tokenUri);
      HttpHeaders headers = new HttpHeaders();
      headers.setContentType(MediaType.APPLICATION_FORM_URLENCODED);
      MultiValueMap<String, String> body = new LinkedMultiValueMap<String, String>();
      body.add("grant_type", "urn:ietf:params:oauth:grant-type:jwt-bearer");
      body.add("assertion", assertion);
      ResponseEntity<Map> response = restTemplate.postForEntity(tokenUri, new HttpEntity<MultiValueMap<String, String>>(body, headers), Map.class);
      Map responseBody = response.getBody();
      Object tokenValue = responseBody == null ? null : responseBody.get("access_token");
      if (tokenValue == null) {
        throw new IllegalStateException("Google access token response is empty.");
      }
      Object expiresIn = responseBody.get("expires_in");
      long expiresSeconds = expiresIn instanceof Number ? ((Number) expiresIn).longValue() : 3600L;
      accessToken = tokenValue.toString();
      accessTokenExpiresAtMillis = now + expiresSeconds * 1000L;
      return accessToken;
    }
  }

  private Map<String, Object> readServiceAccount() throws Exception {
    // 按优先级读取服务账号凭据：JSON 原文、Base64、文件路径。
    if (StringUtils.hasText(properties.getServiceAccountJson())) {
      return objectMapper.readValue(properties.getServiceAccountJson(), new TypeReference<Map<String, Object>>() {});
    }
    if (StringUtils.hasText(properties.getServiceAccountBase64())) {
      byte[] jsonBytes = Base64.getDecoder().decode(properties.getServiceAccountBase64().trim());
      return objectMapper.readValue(jsonBytes, new TypeReference<Map<String, Object>>() {});
    }
    File file = new File(properties.getServiceAccountFile());
    if (!file.isFile()) {
      throw new IllegalStateException("Google service account file does not exist.");
    }
    return objectMapper.readValue(file, new TypeReference<Map<String, Object>>() {});
  }

  private String buildJwt(Map<String, Object> credentials, String tokenUri) throws Exception {
    // Google 服务账号使用 JWT Bearer 流程换取 Sheets API access_token。
    String clientEmail = string(credentials.get("client_email"));
    String privateKey = string(credentials.get("private_key"));
    if (!StringUtils.hasText(clientEmail) || !StringUtils.hasText(privateKey)) {
      throw new IllegalStateException("Google service account file must include client_email and private_key.");
    }
    long nowSeconds = System.currentTimeMillis() / 1000L;
    Map<String, Object> header = new LinkedHashMap<String, Object>();
    header.put("alg", "RS256");
    header.put("typ", "JWT");
    Map<String, Object> claim = new LinkedHashMap<String, Object>();
    claim.put("iss", clientEmail);
    claim.put("scope", SHEETS_SCOPE);
    claim.put("aud", tokenUri);
    claim.put("iat", nowSeconds);
    claim.put("exp", nowSeconds + 3600L);
    String unsigned = base64Url(objectMapper.writeValueAsBytes(header)) + "." + base64Url(objectMapper.writeValueAsBytes(claim));
    Signature signature = Signature.getInstance("SHA256withRSA");
    signature.initSign(readPrivateKey(privateKey));
    signature.update(unsigned.getBytes(StandardCharsets.UTF_8));
    return unsigned + "." + base64Url(signature.sign());
  }

  private PrivateKey readPrivateKey(String privateKey) throws Exception {
    // 服务账号 private_key 是 PEM 格式，调用签名 API 前需要去掉头尾并转成 PKCS8 私钥。
    String normalized = privateKey
        .replace("-----BEGIN PRIVATE KEY-----", "")
        .replace("-----END PRIVATE KEY-----", "")
        .replaceAll("\\s", "");
    byte[] keyBytes = Base64.getDecoder().decode(normalized);
    return KeyFactory.getInstance("RSA").generatePrivate(new PKCS8EncodedKeySpec(keyBytes));
  }

  private String resolveSheetName(String token) {
    // Sheets API 追加数据需要 sheet 名称；优先使用配置名，否则根据 gid 解析并缓存。
    if (StringUtils.hasText(properties.getSheetName())) {
      return properties.getSheetName();
    }
    if (StringUtils.hasText(cachedSheetName)) {
      return cachedSheetName;
    }
    String url = SHEETS_API + "/" + path(properties.getSpreadsheetId()) + "?fields=sheets.properties(sheetId,title)";
    ResponseEntity<Map> response = restTemplate.exchange(url, HttpMethod.GET, new HttpEntity<Void>(authHeaders(token)), Map.class);
    Map body = response.getBody();
    Object sheetsValue = body == null ? null : body.get("sheets");
    if (!(sheetsValue instanceof List)) {
      throw new IllegalStateException("Google spreadsheet metadata response is empty.");
    }
    List sheets = (List) sheetsValue;
    String firstTitle = "";
    for (Object sheetValue : sheets) {
      if (!(sheetValue instanceof Map)) {
        continue;
      }
      Object propertiesValue = ((Map) sheetValue).get("properties");
      if (!(propertiesValue instanceof Map)) {
        continue;
      }
      Map sheetProperties = (Map) propertiesValue;
      String title = string(sheetProperties.get("title"));
      if (!StringUtils.hasText(firstTitle)) {
        firstTitle = title;
      }
      Object sheetId = sheetProperties.get("sheetId");
      if (properties.getSheetGid() != null && sheetId != null
          && properties.getSheetGid().toString().equals(String.valueOf(((Number) sheetId).longValue()))) {
        cachedSheetName = title;
        return cachedSheetName;
      }
    }
    if (properties.getSheetGid() != null) {
      throw new IllegalStateException("Google sheet gid was not found in the spreadsheet.");
    }
    if (!StringUtils.hasText(firstTitle)) {
      throw new IllegalStateException("Google spreadsheet has no sheets.");
    }
    cachedSheetName = firstTitle;
    return cachedSheetName;
  }

  private HttpHeaders authHeaders(String token) {
    // 服务账号方式调用 Sheets API 需要 Bearer access_token。
    HttpHeaders headers = new HttpHeaders();
    headers.setContentType(MediaType.APPLICATION_JSON);
    headers.set(HttpHeaders.AUTHORIZATION, "Bearer " + token);
    return headers;
  }

  private String quoteSheetName(String sheetName) {
    return "'" + sheetName.replace("'", "''") + "'";
  }

  private String path(String value) {
    return UriUtils.encodePathSegment(value, StandardCharsets.UTF_8);
  }

  private String base64Url(byte[] data) {
    return Base64.getUrlEncoder().withoutPadding().encodeToString(data);
  }

  private String string(Object value) {
    return value == null ? "" : String.valueOf(value);
  }

  private String value(Object value) {
    return value == null ? "" : String.valueOf(value);
  }
}
