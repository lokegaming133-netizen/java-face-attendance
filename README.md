# Present — Java localhost face attendance

Teacher-supervised local application. Source delivery, not a prevalidated production biometric product.

## Windows setup
1. Install JDK 17 and Apache Maven 3.9+. Check `java -version` and `mvn -version` in a NEW terminal.
2. Extract this entire folder. Open PowerShell inside it.
3. Run `powershell -ExecutionPolicy Bypass -File .\setup.ps1`. This applies to this process only. Internet is needed for Maven dependencies (large native OpenCV downloads) and detector XML.
4. Run `powershell -ExecutionPolicy Bypass -File .\start.ps1`. Enter a password of at least 12 characters.
5. Keep terminal open. Visit http://localhost:8080 and log in as `teacher` with your password.
6. Allow camera access. Register consenting students and capture 5–10 samples each, with modest pose variation.
7. Enter a unique session such as `2026-11-02-Java-AM`. Find student, visually verify the candidate, then confirm attendance. Unknown people must not be confirmed.
8. Refresh attendance or download CSV. Stop camera when finished; Ctrl+C stops Java.

No Docker is required. The application binds only to 127.0.0.1. It will not be accessible from a friend's device. Your password is chosen each launch, not saved in source.

## Database
The default is persistent H2 in `data/attendance.mv.db`, making first launch independent of MySQL installation. For MySQL, create database `face_attendance`, set DB_USER and DB_PASSWORD in the terminal, set ADMIN_PASSWORD and run:
`java -jar target/attendance-1.0.0.jar --spring.profiles.active=mysql`
Existing H2 records do not migrate automatically. Back up the data folder only after stopping Java. Do not upload face samples or the data folder to GitHub.

## Implemented
Spring Security login and CSRF; student registration with consent; server-side one-face detection; grayscale normalization; OpenCV LBPH nearest-match identification with unknown rejection; teacher-confirmed and manual attendance; database unique student/session constraint; deletion/correction; CSV export; student and biometric deletion.

## Recognition limits
Uses Haar face detection + LBPH, a classical baseline. Threshold 55 is an initial tuning value, NOT a measured accuracy guarantee. It returns distance, not probability. Training currently happens per recognition request: suited to small classroom experiments, not large deployment. A clear well-lit frontal face is needed. Enroll at least 5 samples and test unknown people before use. There is no liveness detection; photos and videos can fool this system. Teacher review is deliberately required. This version is NOT unattended automatic attendance. Data is stored locally without application-level encryption; protect the laptop account and disk.

## Validation checklist on your laptop
- Wrong password cannot access dashboard/API.
- Camera denied produces an error and manual attendance still works.
- Zero or two faces cannot be enrolled.
- Duplicate roll is rejected.
- Registered person matches in varied light; unrelated people are rejected. Record false accepts/rejects; tune threshold only against separate held-out captures.
- Same student/session saves once; different session saves separately.
- Restart Java: students, face samples, attendance persist.
- Remove mistaken attendance, verify CSV; delete test student and verify removal.
- Try a printed photo: expect spoofing vulnerability, hence mandatory teacher supervision.

## Source guide
Application.java: boot + authentication; Api.java: HTTP endpoints, validation and database operations; FaceService.java: OpenCV detection, normalization, training and prediction; static/app.js: webcam capture and browser workflow; schema.sql: relational constraints.

## Verification status
Java 17 is present in the creation environment. Compilation and native-camera testing must be completed on the target laptop; the package is not claimed to have passed those checks. See BUILD_STATUS.txt for the actual environment result.

References: https://bytedeco.org/javacpp-presets/opencv/apidocs/org/bytedeco/opencv/opencv_face/LBPHFaceRecognizer.html ; detector from OpenCV 4.10.0 distribution (Apache-2.0): https://github.com/opencv/opencv . Dependency licenses remain with their respective projects.
