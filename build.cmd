@echo off
REM ============================================================
REM SentinelPulse Build Script
REM Compiles to Java 21 bytecode, runs on Java 25 JVM.
REM Maven is expected in %TEMP%\apache-maven-3.9.7\
REM ============================================================

SET MVN=%TEMP%\apache-maven-3.9.7\bin\mvn.cmd

IF NOT EXIST "%MVN%" (
    echo [BUILD] Downloading Apache Maven 3.9.7...
    powershell -Command "Invoke-WebRequest -Uri 'https://archive.apache.org/dist/maven/maven-3/3.9.7/binaries/apache-maven-3.9.7-bin.zip' -OutFile '%TEMP%\apache-maven-3.9.7-bin.zip' -UseBasicParsing"
    powershell -Command "Expand-Archive -Path '%TEMP%\apache-maven-3.9.7-bin.zip' -DestinationPath '%TEMP%' -Force"
    echo [BUILD] Maven downloaded.
)

echo [BUILD] Building SentinelPulse (compile + test + package)...
"%MVN%" clean package

IF %ERRORLEVEL% NEQ 0 (
    echo [BUILD] FAILED — check errors above.
    exit /b 1
)

echo.
echo [BUILD] SUCCESS! JAR: target\sentinelpulse-1.0.0.jar
echo [BUILD] Start with: java -jar target\sentinelpulse-1.0.0.jar
