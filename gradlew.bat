@echo off
setlocal
set "APP_HOME=%~dp0"
set "WRAPPER=%APP_HOME%gradle\wrapper\gradle-wrapper.jar"
if not exist "%WRAPPER%" (
  echo Fetching official Gradle 8.13 wrapper JAR and checking its SHA-256...
  powershell.exe -NoProfile -ExecutionPolicy Bypass -Command "$ErrorActionPreference='Stop'; $url='https://services.gradle.org/distributions/gradle-8.13-wrapper.jar'; $file='%WRAPPER%'; $tmp=$file+'.tmp'; try { Invoke-WebRequest -UseBasicParsing -Uri $url -OutFile $tmp; $sha=(Get-FileHash -Algorithm SHA256 $tmp).Hash.ToLowerInvariant(); if ($sha -ne '81a82aaea5abcc8ff68b3dfcb58b3c3c429378efd98e7433460610fecd7ae45f') { throw 'Unexpected Gradle wrapper hash' }; Move-Item -Force $tmp $file } finally { Remove-Item -ErrorAction SilentlyContinue $tmp }"
  if errorlevel 1 exit /b 1
)
set "JAVA_EXE=java.exe"
if defined JAVA_HOME set "JAVA_EXE=%JAVA_HOME%\bin\java.exe"
"%JAVA_EXE%" -classpath "%WRAPPER%" org.gradle.wrapper.GradleWrapperMain %*
exit /b %ERRORLEVEL%
