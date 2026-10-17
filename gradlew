#!/usr/bin/env sh

DIRNAME=$(dirname $0)
PROGNAME=$(basename $0)

APP_BASE_NAME=`dirname "$0"`
cd "$APP_BASE_NAME"

exec gradle "$@"
