@echo off
set JAVA_HOME=C:\Users\Usuario\jdk-17
set ANDROID_HOME=C:\Users\Usuario\AppData\Local\Android\Sdk
set ANDROID_SDK_ROOT=C:\Users\Usuario\AppData\Local\Android\Sdk
set PATH=C:\Users\Usuario\jdk-17\bin;%PATH%
cd /d C:\Users\Usuario\Documents\GPS_CLIENTES
call gradlew.bat :app:assembleDebug --no-daemon 1> build_gps_log.txt 2>&1
echo EXITCODE=%ERRORLEVEL% >> build_gps_log.txt