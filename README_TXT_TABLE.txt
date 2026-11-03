JAVA FACE ATTENDANCE - ONE TXT TABLE UPGRADE

WHAT IT DOES
- Saves ONE persistent human-readable table: face-attendance/exports/attendance_register.txt
- Rebuilds the same file at app startup and after attendance is added or corrected (removed).
- On student deletion, refreshes after the H2 transaction commits.
- One table row per attendance record. Each student/session pair remains unique in H2.
- Adds Download TXT Table beside the existing Download CSV.
- Preserves the YuNet/SFace code, student data, face embeddings, and CSV endpoint.
- Creates no extra CSV file per new attendance record.

INSTALL (on the current YuNet/SFace project)
1. Stop the Java server with Ctrl+C.
2. Back up your entire face-attendance folder, especially data/.
3. Extract this ZIP and copy its src folder into your existing face-attendance folder;
   select Replace files in destination. Do NOT replace your data folder.
4. In the project folder, run: mvn package
5. After BUILD SUCCESS, run: powershell -ExecutionPolicy Bypass -File .\start.ps1
6. Visit http://localhost:8080, login, Ctrl+F5, click Download TXT Table.
7. In File Explorer open: face-attendance\exports\attendance_register.txt

TEST
- Mark a NEW student in a session, then open the TXT file: one row must be added.
- Correct/remove an attendance record: TXT row should disappear.
- Restart server: existing database records should still be in TXT.

IMPORTANT
- This is an untested source patch; compile/runtime on your Windows PC still needs verifying.
- Do not delete data/, attendance.mv.db, or your existing face samples.
- The text file contains student personal details; keep exports/ private.
- H2 is the authoritative store. The TXT file is an automatically synchronized report.
