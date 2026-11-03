JAVA FACE ATTENDANCE - CLOUD PATCH
==================================
Adds optional Spring profile 'cloud' for Render + Neon PostgreSQL.
Does not include passwords or user data. No local H2 data is migrated.

Apply: Back up your current project first. Merge this patch's files into
face-attendance (not into face-attendance/src). Replace pom.xml only after
preserving a backup of your current pom.xml.

Local mode: start.ps1 unchanged, local H2 still default.
Cloud mode: Render Docker service; PORT supplied automatically; env:
- ADMIN_PASSWORD  (new strong secret, >=12 chars)
- DB_HOST         (Neon hostname only, preferably pooler hostname)
- DB_NAME         (Neon database name, generally neondb)
- DB_USER         (Neon database role)
- DB_PASSWORD     (Neon newly reset password)
- SPRING_PROFILES_ACTIVE=cloud (already set in Dockerfile)
Do not paste credentials in chat, screenshots, GitHub, or .env committed to Git.

Database notes: schema-postgresql.sql creates 4 tables only if absent.
Student data, SFace embeddings, and attendance remain only in LOCAL H2
until a separate consent-based migration. The cloud DB starts empty.

Pre-publish: do not push data/, exports/, models/, .env or backups to Git.
Use a PRIVATE GitHub repo for first deployment. Deploy only with dummy
student/teacher test data until privacy and security have been reviewed.

Free limitations: Render Free may sleep after 15 mins, has ephemeral disk and
low RAM. OpenCV 4.10/Bytedeco may not fit; test and do NOT claim success
until actual Render logs, model loading, login, enrollment, persistence
and attendance flows are verified. TXT file is regenerated from Neon DB
when app runs; it is not persistently stored on Render free disk.

Notes: Maven/JDK 17 compile must be tested on user's current source. This
patch has only static structural checks in our environment.
