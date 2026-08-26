@echo off
set "DIR=%~dp0"
set "MVN_BIN=%DIR%.tools\apache-maven-3.9.6\bin\mvn.cmd"
if exist "%MVN_BIN%" (
    call "%MVN_BIN%" %*
) else (
    mvn %*
)
