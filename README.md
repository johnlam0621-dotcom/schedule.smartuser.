# Schedule SmartUser

Schedule SmartUser is an inspection scheduling and field-work application built with Vue 3 and Spring Boot. It imports inspection availability, recommends booking slots, presents daily inspector routes, collects inspection media, and provides manager/admin review workflows.

## Main features

- Role-based access for administrators, managers, schedulers, sales users, and inspectors
- Inspection schedule import and automatic Google Sheet week import
- Booking recommendations with location, inspector availability, and travel-time checks
- Inspector route map, customer details, notes, completion, and unavailable status
- Product-specific inspection media requirements for air conditioning, solar/battery, and heat pump jobs
- Mobile camera/file selection, photo compression, drawing, remarks, and deletion
- Manager/admin photo and video review and weekly inspection confirmation
- Up to four inspection videos, with a maximum of 3 GB per video

## Background video uploads

When an inspector selects or records a video, the upload is added to a shared queue instead of blocking the inspection page.

1. The video starts automatically and uploads sequentially to avoid several large files competing for the same mobile connection.
2. A fixed **Background video uploads** panel shows waiting, progress, completion, and failure states.
3. The inspector can navigate to other pages inside SmartUser while the upload continues.
4. A failed upload remains in the panel and can be retried.
5. The browser warns the inspector if they try to close or reload while an upload is active.

Web browsers cannot reliably continue a multi-gigabyte HTTP upload after the browser is fully closed. Inspectors may leave the upload page and use other SmartUser pages, but must keep the browser open until the panel says **Uploaded**. True closed-browser uploads would require a native mobile application or a resumable object-storage upload service.

## Project structure

- `frontend/` — Vue 3 and Vite client
- `backend/` — Spring Boot API
- `database/schema.sql` — database schema
- `scripts/` — local startup, verification, and integration helpers
- `docs/` — architecture, API, deployment, and operating guides

## Local development

Frontend:

```powershell
cd frontend
pnpm install
pnpm run dev
```

Backend:

```powershell
cd backend
mvn spring-boot:run
```

The frontend defaults to `http://127.0.0.1:5173` and the API defaults to port `8088`. Configure database, Redis, Google Sheet integration, and bootstrap credentials through environment variables. Do not commit real credentials or customer exports.

## Production build

```powershell
cd frontend
pnpm run build
```

The generated `frontend/dist/` directory and deployment packages are intentionally excluded from Git. Production deployment should build from a reviewed commit and keep server-side configuration in environment variables.
