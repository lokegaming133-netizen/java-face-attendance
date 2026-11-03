SUNSHINE SCRAPBOOK — CENTERED ANIMATED LOGIN ONLY

Files changed in this patch:
  src/main/resources/static/login.html

Requirements:
  Your already installed previous animated-login 404 fix, including LoginPageController.java,
  which injects the <!--LOGIN_CSRF_FIELD--> placeholder for secure authentication.

Install:
  1. Stop Spring Boot server with Ctrl+C.
  2. Back up the existing project.
  3. Copy this ZIP's src folder into the existing face-attendance project and replace login.html.
  4. Run: mvn package
  5. Run: powershell -ExecutionPolicy Bypass -File .\start.ps1
  6. Visit: http://localhost:8080/login and hard-refresh (Ctrl+F5).

This upgrade does NOT modify Application.java, LoginPageController.java, APIs, DB, images,
model files, attendance records, or recognition logic.
