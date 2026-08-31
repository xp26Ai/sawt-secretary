@echo off
rem ينسخ مصادر SIP من التطبيق للاختبار — شغّله بعد أي تعديل عليها
copy /Y "..\app\src\main\java\com\osaid\sawtsecretary\sip\*.java" "src\com\osaid\sawtsecretary\sip\" >nul
echo تم التحديث
