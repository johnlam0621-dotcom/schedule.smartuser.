# Schedule SmartUser

Schedule SmartUser is an inspection scheduling and field-work application built with Vue 3 and Spring Boot. It imports inspection availability, recommends booking slots, presents daily inspector routes, collects inspection media, and provides manager/admin review workflows.

## Main features

- Role-based access for administrators, managers, schedulers, sales users, inspectors, and the quotation team
- Inspection schedule import and automatic Google Sheet week import
- Booking recommendations with location, inspector availability, and travel-time checks
- Inspector route map, customer details, notes, completion, and unavailable status
- Product-specific inspection media requirements for air conditioning, solar/battery, and heat pump jobs
- Mobile camera/file selection, photo compression, drawing, remarks, and deletion
- Inspection Done search by MACID or phone number, with photo and video review
- MAC and DAC photo requirements, including dedicated floor plan and measurements uploads
- Up to nine inspection video slots for DAC and four for other products, with a maximum of 3 GB per video

## Background video uploads

When an inspector selects or records a video, the upload is added to a shared queue instead of blocking the inspection page.

1. Photos and videos use independent upload queues: two photos and one video can upload concurrently.
2. The inspector can continue selecting photos while a video uploads.
3. Uploads continue when navigating between pages inside SmartUser.
4. Completed queue entries clear automatically; the large floating upload panel has been removed.
5. The browser warns before closing or reloading while an upload is active.

Keep the browser open until uploads finish. Uploads cannot reliably continue after the browser is fully closed.

## Video playback

The server prepares an H.264/AAC MP4 copy after upload using FFmpeg. Configure the FFmpeg executable for the deployment environment. Playback and downloads use authenticated byte-range streaming, allowing browsers to request portions of a video. The original upload remains available for download.

The September 2026 playback fix uses explicit `StreamingResponseBody` endpoint types and a bounded streaming executor. Regression tests exercise the MVC playback route and verify HTTP 206 headers and the returned bytes.

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
