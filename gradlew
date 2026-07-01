#!/usr/bin/env sh
##############################################################################
# Gradle wrapper script for Unix
##############################################################################
APP_NAME="Gradle"
APP_BASE_NAME=`basename "$0"`
APP_HOME=`pwd -P`
MAX_FD="maximum"
warn() { echo "$*"; }
die() { echo; echo "$*"; echo; exit 1; }
if [ "$APP_HOME" = "" ]; then APP_HOME="."; fi
CLASSPATH=$APP_HOME/gradle/wrapper/gradle-wrapper.jar

# OS specific support
cygwin=false; darwin=false; nonstop=false
case "`uname`" in CYGWIN*) cygwin=true;; Darwin*) darwin=true;; NONSTOP*) nonstop=true;; esac

JAVACMD="java"
if [ -n "$JAVA_HOME" ]; then
  if [ -x "$JAVA_HOME/jre/sh/java" ]; then JAVACMD="$JAVA_HOME/jre/sh/java"
  else JAVACMD="$JAVA_HOME/bin/java"; fi
fi

exec "$JAVACMD" \
  -classpath "$CLASSPATH" \
  org.gradle.wrapper.GradleWrapperMain \
  "$@"
