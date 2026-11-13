@echo off
rem Runs the Gradle wrapper with this PC's toolchain on D:\Android.
set "JAVA_HOME=D:\Android\jdk-21"
set "ANDROID_HOME=D:\Android\sdk"
set "GRADLE_USER_HOME=D:\Android\gradle-home"
set "ANDROID_AVD_HOME=D:\Android\avd"
call "%~dp0gradlew.bat" %*
