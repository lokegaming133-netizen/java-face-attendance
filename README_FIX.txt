Animated login page 404 + CSRF fix

This patch includes only:
  src/main/java/local/attendance/LoginPageController.java
  src/main/resources/static/login.html

It assumes you installed the earlier Animated Login Upgrade (which updated Application.java).

Cause: Spring Security custom loginPage("/login") does not itself create an HTML page route. Spring Security also expects a valid CSRF hidden field on POST /login.

Steps:
1. Stop the old server (Ctrl+C).
2. Extract this zip and copy its src folder into your existing face-attendance project, replacing files when prompted.
3. From the original project folder, run: mvn package
4. Run: powershell -ExecutionPolicy Bypass -File .\start.ps1
5. Visit: http://localhost:8080/login

No changes to face recognition, attendance data, database, or startup password.
