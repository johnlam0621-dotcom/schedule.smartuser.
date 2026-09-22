# Schedule + CRM API integration preparation

Updated: 22 September 2026

## Goal and confirmed requirements

Prepare the scheduling system and CRM to work together through an API. The user will supply the CRM link and access details. Target preparation window: the next two weeks; this is a planning target, not a confirmed launch date or an automated monitoring task.

- Scheduling system: https://schedule.smartuser.com.au
- Confirmed CRM workspace supplied by the user: https://www.smartusercrm.com.au/platform/workSpace
- Browser check on 22 September 2026: the SmartUser Platform dashboard opened in the existing signed-in session. This confirms workspace access only; API availability, authentication, endpoints, and status IDs have not been verified. No CRM records were changed.
- Confirmed business rule: when an inspection booking succeeds in Schedule, the corresponding CRM lead should move from **Ready to Inspection** to **Inspection Booked**. Exact CRM API status IDs and spelling remain to be verified.
- Photos and videos must remain accessible from Inspection Done and Inspector Photos after schedule refreshes and system updates.
- No live CRM integration, migration, or deployment is authorized by these notes alone.

## Information needed from the CRM team

- CRM URL, API documentation, developer contact, and sandbox/test account.
- API base URL/version, authentication method, required scopes, rate limits, and webhook support.
- Stable lead/customer/job identifiers; confirm whether MACID is unique and how repeat inspections are represented.
- Exact status IDs and permitted transitions, including cancelled, rescheduled, done, and unable to inspect.
- Supported attachment upload/download APIs, file-size limits, retention rules, and expiring-link behavior.
- Which fields each system owns and which direction each field should sync.
- How API credentials will be supplied securely; do not commit secrets or place them in these notes.

## Proposed mapping — confirm before implementation

| Schedule data/event | CRM destination/action | Decision still needed |
| --- | --- | --- |
| MACID and internal inspection ID | Lead/job association | Stable CRM ID; repeat-visit handling |
| Successful booking | Ready to Inspection → Inspection Booked | Exact status IDs; booking event definition |
| Date, time, inspector | Appointment details | Time zone and staff ID mapping |
| Reschedule/cancellation | Update existing appointment | Conflict rules and status transitions |
| Inspection done/unable | Inspection result | CRM statuses; whether review is required first |
| Inspector remarks | Inspection notes | Visibility and edit ownership |
| Photos/videos | Attachments or authenticated media references | Storage ownership, permissions, retention |

Only the booking-status business rule above is confirmed. The remaining mappings are proposals, not established CRM capabilities.

## Reliability and data protection

- Use stable IDs, not spreadsheet row numbers or phone numbers alone, for associations.
- Keep an explicit CRM-to-Schedule ID mapping; do not guess associations for historical media.
- Save bookings locally before sending CRM updates. Queue failed syncs with visible status, bounded retries, and an audit trail.
- Make repeated events safe: use idempotency keys/event IDs and prevent duplicate appointments or attachments.
- Define conflict handling and prevent webhook/update loops. Validate webhook signatures if supported.
- Keep API credentials on the server; enforce role permissions for every media request.
- Keep durable media identifiers; refresh expiring download URLs rather than storing them as permanent references.
- Preserve original media and inspection associations through imports, deployments, and migration.
- Back up the database and upload storage together, and test restoration before launch.

## Existing media issue and current work

Code inspection found that replacing a Google Sheet week deletes old inspection records and creates new IDs, potentially leaving media associated with deleted records.

Local safeguards now reject replacement/deletion when affected inspections have media. The backend suite passed 73 tests. As last verified, these changes are not deployed to Aliyun or pushed to GitHub. This safeguard does not recover already-orphaned media and is not a complete redesign of import identity handling.

Next checks:

- Read-only live audit: compare media inspection IDs with existing inspection records and files on disk.
- Use a verified affected MACID and backups/audit history to establish the original association.
- Prepare a reviewed recovery plan; never reassign files based on an uncertain customer match.
- Test concurrent uploads and refresh/deletion, not only sequential operations.
- Verify older media remains searchable and playable/downloadable on both review pages.

## Proposed two-week preparation plan

### Week 1: establish the contract and protect data

1. Obtain CRM documentation and sandbox access.
2. Confirm identifiers, field ownership, status mappings, and attachment strategy.
3. Back up and audit existing media; validate and deploy the retention safeguard through the normal release process.
4. Define API request/response examples and failure behavior from the real CRM documentation.

### Week 2: sandbox integration and release readiness

1. Implement and test booking/status sync in the sandbox.
2. Test remarks/media integration if confirmed in scope.
3. Test retries, duplicates, expired credentials, permissions, rescheduling, and outages.
4. Reconcile record/file counts, test rollback, and run a small approved pilot before broad release.

## Acceptance checklist

- [ ] One successful booking updates the correct CRM lead exactly once.
- [ ] Failed CRM sync does not lose the local booking and can be retried safely.
- [ ] Repeat visits, reschedules, and cancellations do not overwrite unrelated inspections.
- [ ] Only authorized roles can access inspection media and notes.
- [ ] Existing and new photos/videos remain available after refresh, restart, deployment, and migration.
- [ ] Upload/playback/download tested on desktop Chrome and mobile Safari.
- [ ] Historical media counts and associations reconciled; unresolved items documented.
- [ ] Backup restoration and rollback tested.
- [ ] User approves pilot results and production cutover.

## Next handoff

CRM link received and dashboard access verified. Next obtain API documentation or a developer contact, fill in the pending decisions above, then agree the initial integration scope before writing to the live CRM.

Suggested request to the CRM developer:

> We are preparing to connect Schedule SmartUser with SmartUser CRM. Please provide API documentation and sandbox access for looking up leads by MACID, reading/updating inspection booking status, appointment details, inspection results, notes, and photo/video attachments. Please include authentication requirements, exact status IDs for Ready to Inspection and Inspection Booked, webhook support, rate limits, and attachment retention/download-link rules. Please supply credentials through a secure channel, not in the project notes.
