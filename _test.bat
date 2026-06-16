@echo off
set JAVA_HOME=C:\tools\jdk17\jdk-17.0.19+10
set ANDROID_HOME=C:\tools\android-sdk
set PATH=%JAVA_HOME%\bin;%PATH%
call gradlew.bat :app:testDebugUnitTest --no-daemon --console=plain