#!/bin/zsh
# 启动 Debug Menu 客户端（macOS）
# 用法: ./run-client.sh [mc版本]   例如 ./run-client.sh 1.20.1
# 不带参数时使用 gradle.properties 里的 default_mc
set -e

export JAVA_HOME=/opt/homebrew/opt/openjdk@17

# 本地已解压的 Gradle 8.8（避开 ./gradlew 引导下载在当前网络下的失败）
GRADLE=~/gradle-dist/gradle-8.8/bin/gradle

MC_ARG=""
if [ -n "$1" ]; then
  MC_ARG="-Pmc=$1"
fi

# --no-daemon 避免复用带旧代理配置的守护进程；清空代理属性走直连
exec "$GRADLE" runClient $MC_ARG --no-daemon \
  -Dhttp.proxyHost= -Dhttps.proxyHost= -Dhttp.proxyPort= -Dhttps.proxyPort=
