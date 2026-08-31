@echo off
chcp 65001 >nul
cd /d "%~dp0"

rem يحتاج: Android SDK في ANDROID_HOME، و Gradle 8.x في PATH، و JDK 17.
rem Needs: Android SDK in ANDROID_HOME, Gradle 8.x on PATH, JDK 17.

if not defined ANDROID_HOME (
    echo [x] ANDROID_HOME غير معرّف — عرّفه على مسار Android SDK عندك.
    echo [x] ANDROID_HOME is not set. Point it at your Android SDK.
    exit /b 1
)

gradle assembleRelease
if errorlevel 1 exit /b 1

echo.
echo APK: app\build\outputs\apk\release\app-release-unsigned.apk
echo وقّعه بمخزن مفاتيحك أنت — لا مفتاح توقيع في هذا المستودع.
echo Sign it with your own keystore; no signing key ships in this repo.
