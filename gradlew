#!/bin/sh
set -e
APP_HOME=$(cd "$(dirname "$0")" && pwd)
JAR="$APP_HOME/gradle/wrapper/gradle-wrapper.jar"
if [ ! -f "$JAR" ]; then
    echo "缺少 gradle-wrapper.jar，请先运行 gradle wrapper 生成，或直接使用 gradle :app:assembleDebug"
    exit 1
fi
exec java -classpath "$JAR" org.gradle.wrapper.GradleWrapperMain "$@"
