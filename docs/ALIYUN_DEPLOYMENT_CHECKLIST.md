# Aliyun deployment checklist

## Required runtime

- JDK 17
- MySQL 8 with a dedicated database user
- Nginx (or another reverse proxy)
- Redis is recommended for shared login sessions
- A writable, persistent directory for inspector photo uploads

## Required backend environment

Set these outside the project files. Do not commit their real values.

```text
MYSQL_URL
MYSQL_USERNAME
MYSQL_PASSWORD
REDIS_HOST
REDIS_PORT
REDIS_PASSWORD
GOOGLE_SHEET_WEB_APP_URL
GOOGLE_SHEET_WEB_APP_SECRET
GOOGLE_SHEET_SPREADSHEET_ID
GOOGLE_SHEET_GID
GOOGLE_SHEET_SYNC_ENABLED=false
SERVER_PORT=8088
```

Leave `SCHEDULE_BOOTSTRAP_ADMIN_PASSWORD` unset after the initial administrator is created.

## Frontend and Google Maps

Build the frontend with the production Google Maps browser key in `VITE_GOOGLE_MAPS_KEY`.
Restrict that key to the production HTTPS origin, for example:

```text
https://schedule.example.com/*
```

Enable the Maps JavaScript API, Places API and Routes API for the key/project. Configure Nginx to:

- serve `frontend/dist`;
- return `index.html` for SPA routes;
- proxy `/api/` to `http://127.0.0.1:8088/api/`;
- preserve the original host and HTTPS forwarding headers.

## Persistent data

- Back up MySQL before deployment and before each schema/data migration.
- Store inspector uploads outside temporary deployment directories.
- Back up the upload directory with the database.
- Google Sheets is read-only; bookings, cancellations and reschedules stay in MySQL.

## Pre-release checks

1. Run all backend tests and the frontend production build.
2. Confirm `GET /api/health/status` returns `UP`.
3. Sign in as Admin, Manager, Scheduler/Sales and one inspector account.
4. Verify role menus and direct-URL restrictions.
5. Import the required current/future Google Sheet week using **Replace existing week**.
6. Verify Search, Routes map, five-day Booking recommendations and Inspector Route/Shift.
7. Upload and preview one standard photo and one Additional/Product photo.
8. Confirm Google Maps accepts the production domain referrer.

