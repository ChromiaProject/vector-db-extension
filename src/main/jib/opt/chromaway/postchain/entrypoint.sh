#!/bin/sh
# Copyright (c) 2022 ChromaWay Inc. See README for license information.

set -eu

# Percentage of total memory to dedicate to java/psql (30% buffer with current setup)
JAVA_MEMORY_SHARE=35
export PSQL_MEMORY_SHARE=35

# Exit container if run as root
if [ "$(id -u)" = '0' ]; then
  echo "Do not run this container as root"
  exit 1
fi

trap 'echo "Shutting down..." ; kill ${POSTCHAIN_PID} ; pg_ctl stop -m smart' TERM INT

echo "Configuring and starting Postgres"
bash postgres-entrypoint.sh postgres

java -Duser.language=en -Duser.country=US -XX:+UnlockDiagnosticVMOptions -XX:AbortVMOnException=java.lang.OutOfMemoryError -XX:MaxRAMPercentage=$JAVA_MEMORY_SHARE -classpath "$POSTCHAIN_DIR/libs/*:$POSTCHAIN_DIR/classpath/*" net.postchain.server.AppKt run-subnode &
POSTCHAIN_PID="$!"
echo "Started Postchain node with PID ${POSTCHAIN_PID}"
wait ${POSTCHAIN_PID}
