SUNSHINE SCRAPBOOK LIVE CAMERA UPGRADE
======================================

Adds moving face bounding boxes and an enrolled student's NAME/ROLL/GENDER
next to their face in the live webcam. It does NOT automatically mark attendance.
Gender is student-provided at registration; the app does NOT infer gender from appearance.
Live frames go to this same localhost Java server and are NOT stored by /api/live.

SAFE UPGRADE OF EXISTING WINDOWS PROJECT
----------------------------------------
1. Stop the currently running server in its CMD window with Ctrl+C.
2. Back up your original project folder, particularly the data/ directory.
3. From this ZIP, copy the "src" directory into your existing
   face-attendance project folder (where pom.xml lives), allowing file replacement.
   Keep your existing models/, data/, pom.xml, setup.ps1 and start.ps1 files.
4. In CMD opened inside the EXISTING project folder, run:
       mvn package
   It should show BUILD SUCCESS. If not, save the last 30 error lines.
5. Start it using:
       powershell -ExecutionPolicy Bypass -File .\start.ps1
6. Open http://localhost:8080 and sign in as teacher. Press Ctrl+F5.
7. Click Start camera. A box should move around a detected face. With enrolled
   samples, it may show the student name and student-provided gender.
8. Existing students show gender "Not specified" until you choose their
   profile in Enrollment & manual attendance, select the student-provided
   gender and click Save gender. Keep their face samples; no need to re-enroll.

NOTE: Startup adds the gender column to older H2/MySQL databases when absent.
No attendance, enrollment, or database files are bundled in this ZIP.
The existing threshold 55 is only a starting point, not an accuracy guarantee.
No liveness/anti-spoofing is implemented. Teacher must verify then click
Find student and Confirm attendance. The overlay is not proof of identity.

IF YOU SEE A LOCKED H2 DATABASE ERROR
-------------------------------------
Do not delete data/attendance.mv.db. Shut down the old Java server gracefully
and verify it is stopped before starting a new one.
