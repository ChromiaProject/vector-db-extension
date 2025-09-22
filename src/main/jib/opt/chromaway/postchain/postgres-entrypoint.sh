#!/bin/bash
# Copyright (c) 2017 ChromaWay Inc. See README for license information.

set -e

DEFAULT_PG_VERSION=16   # Initializes new versions

echo "Detecting Postgres version"
if [ -f "$PGDATA/PG_VERSION" ]; then
  pgdata_version=$(cat "$PGDATA/PG_VERSION")
  echo "Existing PGDATA version: $pgdata_version"
fi

PG_VERSION="${pgdata_version:-$DEFAULT_PG_VERSION}"
pg_bin_var="PG_BIN_${PG_VERSION}"
if [[ ! -v "$pg_bin_var" ]]; then
  echo "Variable $pg_bin_var not found"
  exit 1
fi
PG_BIN="${!pg_bin_var}"

if [ ! -d "$PG_BIN" ]; then
  echo "Error: $PG_BIN directory does not exist."
  exit 1
fi

echo "Will use PG version $PG_VERSION with binaries in $PG_BIN"
echo

# usage: file_env VAR [DEFAULT]
#    ie: file_env 'XYZ_DB_PASSWORD' 'example'
# (will allow for "$XYZ_DB_PASSWORD_FILE" to fill in the value of
#  "$XYZ_DB_PASSWORD" from a file, especially for Docker's secrets feature)
file_env() {
	local var="$1"
	local fileVar="${var}_FILE"
	local def="${2:-}"
	if [ "${!var:-}" ] && [ "${!fileVar:-}" ]; then
		echo >&2 "error: both $var and $fileVar are set (but are exclusive)"
		exit 1
	fi
	local val="$def"
	if [ "${!var:-}" ]; then
		val="${!var}"
	elif [ "${!fileVar:-}" ]; then
		val="$(< "${!fileVar}")"
	fi
	export "$var"="$val"
	unset "$fileVar"
}

configure_allow_all_hosts() {
  # check password first so we can output the warning before postgres
  # messes it up
  file_env 'POSTGRES_PASSWORD'
  if [ "$POSTGRES_PASSWORD" ]; then
    pass="PASSWORD '$POSTGRES_PASSWORD'"
    authMethod=md5
  else
    # The - option suppresses leading tabs but *not* spaces. :)
    cat >&2 <<-'EOWARN'
      ****************************************************
      WARNING: No password has been set for the database.
               This will allow anyone with access to the
               Postgres port to access your database. In
               Docker's default configuration, this is
               effectively any other container on the same
               system.
               Use "-e POSTGRES_PASSWORD=password" to set
               it in "docker run".
      ****************************************************
EOWARN

    pass=
    authMethod=trust
  fi
  pg_hba_rule="host all all all $authMethod"

  if ! egrep -q "^$pg_hba_rule" $PGDATA/pg_hba.conf; then
    echo "Adding rule to pg_hba.conf: $pg_hba_rule"
    {
      echo
      echo "$pg_hba_rule"
    } >> "$PGDATA/pg_hba.conf"
  fi
}

update_file_if_changed() {
  file="$1"
  content="$2"
  if [ "$content" != "$(cat "$file")" ]; then
    echo
    echo "Updating $file:"
    diff "$file" <(echo "$content")  | grep -E "^[<>+-][^+-]"
    echo "$content" > "$file"
  fi
}

configure_resource_limits() {
  # Parse cgroup info to find memory limit
  if [ -f /sys/fs/cgroup/cgroup.controllers ]; then
    TOTALMEM=$(cat /sys/fs/cgroup/memory.max)
  elif [ -f /sys/fs/cgroup/memory/memory.limit_in_bytes ]; then
    TOTALMEM=$(cat /sys/fs/cgroup/memory/memory.limit_in_bytes)
  fi

  # Unlimited memory is denoted as "max" for cgroup v2
  if [ -z "$TOTALMEM" ] || [ "$TOTALMEM" = "max" ] || [ ${#TOTALMEM} -gt 15 ]; then
    echo "Container is not restricted on memory, leaving postgres memory settings as default"
  else
    # Not exact but to avoid overflow in calculations below
    TOTALMEM_MB=${TOTALMEM%??????}

    PSQL_MEMORY_SHARE_MB=$((TOTALMEM_MB * PSQL_MEMORY_SHARE / 100))

    echo "Setting the following limits (in MB):"
    # Formula: 25%
    SHARED_BUFFERS_LIMIT=$((PSQL_MEMORY_SHARE_MB / 4))
    echo "shared_buffers = $SHARED_BUFFERS_LIMIT"

    DB_CONNECTIONS=$((POSTCHAIN_DB_READ_CONCURRENCY + POSTCHAIN_DB_BLOCK_BUILDER_WRITE_CONCURRENCY + POSTCHAIN_DB_SHARED_WRITE_CONCURRENCY))
    MAX_DB_CONNECTIONS=$((DB_CONNECTIONS > 100 ? DB_CONNECTIONS : 100))
    echo "max_connections = $MAX_DB_CONNECTIONS"

    # Formula: 25% / max_connections, where max_connections is POSTCHAIN_DB_READ_CONCURRENCY + POSTCHAIN_DB_BLOCK_BUILDER_WRITE_CONCURRENCY + POSTCHAIN_DB_SHARED_WRITE_CONCURRENCY
    WORK_MEM_LIMIT=$((PSQL_MEMORY_SHARE_MB / 4 / DB_CONNECTIONS))
    WORK_MEM_LIMIT=$((WORK_MEM_LIMIT > 0 ? WORK_MEM_LIMIT : 1)) # Min 1MB
    echo "work_mem = $WORK_MEM_LIMIT"

    # Formula: 50%
    EFFECTIVE_CACHE_SIZE_LIMIT=$((PSQL_MEMORY_SHARE_MB / 2))
    echo "effective_cache_size = $EFFECTIVE_CACHE_SIZE_LIMIT"

    MAX_LOCKS_PER_TRANSACTION=${POSTGRES_MAX_LOCKS_PER_TRANSACTION:-1024}
    echo "max_locks_per_transaction = $MAX_LOCKS_PER_TRANSACTION"

    update_file_if_changed "$PGDATA/postgresql.conf" "$(sed -E "/^shared_buffers =/ s/= .*/= ${SHARED_BUFFERS_LIMIT}MB/" $PGDATA/postgresql.conf)"
    update_file_if_changed "$PGDATA/postgresql.conf" "$(sed -E "/^#?max_connections =/ s/.*/max_connections = ${MAX_DB_CONNECTIONS}/" $PGDATA/postgresql.conf)"
    update_file_if_changed "$PGDATA/postgresql.conf" "$(sed -E "/^#?work_mem =/ s/.*/work_mem = ${WORK_MEM_LIMIT}MB/" $PGDATA/postgresql.conf)"
    update_file_if_changed "$PGDATA/postgresql.conf" "$(sed -E "/^#?effective_cache_size =/ s/.*/effective_cache_size = ${EFFECTIVE_CACHE_SIZE_LIMIT}MB/" $PGDATA/postgresql.conf)"
    update_file_if_changed "$PGDATA/postgresql.conf" "$(sed -E "/^#?max_locks_per_transaction =/ s/.*/max_locks_per_transaction = ${MAX_LOCKS_PER_TRANSACTION}/" $PGDATA/postgresql.conf)"
  fi
}

if [ "${1:0:1}" = '-' ]; then
	set -- postgres "$@"
fi

if [ "$1" = 'postgres' ]; then

	mkdir -p "$PGDATA"
	chown -R "$(id -u)" "$PGDATA" 2>/dev/null || :
	chmod 700 "$PGDATA" 2>/dev/null || :

	# Look to see if we marked the db as initialized
  ALREADY_INITED=$PGDATA/.db-initialized
  if [ ! -e "$ALREADY_INITED" ]; then

    echo "Initializing a new database"

		file_env 'POSTGRES_INITDB_ARGS'
		# Clean up data directory if there is anything in it
		rm -rf $PGDATA/*
		if [ "$POSTGRES_INITDB_XLOGDIR" ]; then
			export POSTGRES_INITDB_ARGS="$POSTGRES_INITDB_ARGS --xlogdir $POSTGRES_INITDB_XLOGDIR"
		fi
		eval "$PG_BIN/initdb --username=postgres $POSTGRES_INITDB_ARGS"

    configure_allow_all_hosts
		configure_resource_limits

		# starting the database server
		PGUSER="${PGUSER:-postgres}" \
    "$PG_BIN/pg_ctl" -D "$PGDATA" -w start

		file_env 'POSTGRES_USER' 'postgres'
		file_env 'POSTGRES_DB' "$POSTGRES_USER"

    psql=("$PG_BIN/psql" -v ON_ERROR_STOP=1)

		if [ "$POSTGRES_DB" != 'postgres' ]; then
			"${psql[@]}" --username postgres <<-EOSQL
				CREATE DATABASE "$POSTGRES_DB" ;
			EOSQL
			echo
		fi

		if [ "$POSTGRES_USER" = 'postgres' ]; then
			op='ALTER'
		else
			op='CREATE'
		fi
		"${psql[@]}" --username postgres <<-EOSQL
			$op USER "$POSTGRES_USER" WITH SUPERUSER $pass ;
		EOSQL
		echo

		psql+=( --username "$POSTGRES_USER" --dbname "$POSTGRES_DB" )

		echo
		for f in /docker-entrypoint-initdb.d/*; do
			case "$f" in
				*.sh)     echo "$0: running $f"; . "$f" ;;
				*.sql)    echo "$0: running $f"; "${psql[@]}" -f "$f"; echo ;;
				*.sql.gz) echo "$0: running $f"; gunzip -c "$f" | "${psql[@]}"; echo ;;
				*)        echo "$0: ignoring $f" ;;
			esac
			echo
		done

		touch $ALREADY_INITED

		echo
		echo 'PostgreSQL init process complete; ready for start up.'
		echo
  else
    # Ensure desired configuration is up to date
    configure_allow_all_hosts
    configure_resource_limits

    # starting the database server
    PGUSER="${PGUSER:-postgres}" \
    "$PG_BIN/pg_ctl" -D "$PGDATA" -w start

    file_env 'POSTGRES_USER' 'postgres'
    file_env 'POSTGRES_DB' "$POSTGRES_USER"

    psql=( "$PG_BIN/psql" -v ON_ERROR_STOP=1 )
    psql+=( --username "$POSTGRES_USER" --dbname "$POSTGRES_DB" )

    echo
    for f in /docker-entrypoint-initdb.d/*; do
            case "$f" in
                    *.sh)     echo "$0: running $f"; . "$f" ;;
                    *.sql)    echo "$0: running $f"; "${psql[@]}" -f "$f"; echo ;;
                    *.sql.gz) echo "$0: running $f"; gunzip -c "$f" | "${psql[@]}"; echo ;;
                    *)        echo "$0: ignoring $f" ;;
            esac
            echo
    done

    echo
    echo 'PostgreSQL REinit process complete; ready for start up.'
    echo
  fi
fi

if [ -f /circleconfig/postgres/customizations ] && [ -s /circleconfig/postgres/customizations ]
then
	. /circleconfig/postgres/customizations
fi
