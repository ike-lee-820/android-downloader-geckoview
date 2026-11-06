@echo off
setlocal
set APP_HOME=%~dp0
set JAR=%APP_HOME%gradle\wrapper\gradle-wrapper.jar
if not exist "%JAR%" (
    echo 缺少 gradle-wrapper.jar，请先运行 gradle wrapper 生成
    exit /b 1
)
java -classpath "%JAR%" org.gradle.wrapper.GradleWrapperMain %*
endlocal
