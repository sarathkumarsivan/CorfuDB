#!/usr/bin/env bash

if [ "$JAVA_HOME" != "" ]; then
  JAVA="$JAVA_HOME/bin/java"
else
  JAVA=java #java
fi
echo $JAVA

CORFUDBBINDIR="${CORFUDBBINDIR:-/usr/bin}" #/usr/bin
CORFUDB_PREFIX="${CORFUDBBINDIR}/.." #/usr/bin/..

SOURCE="${BASH_SOURCE[0]}" #./bin/corfu_server
while [ -h "$SOURCE" ]; do # resolve $SOURCE until the file is no longer a symlink get rid of soft link
  DIR="$( cd -P "$( dirname "$SOURCE" )" && pwd )" #/Users/lidong/vmware_proj/CorfuDB/bin
  SOURCE="$(readlink "$SOURCE")"
  [[ $SOURCE != /* ]] && SOURCE="$DIR/$SOURCE" # if $SOURCE was a relative symlink, we need to resolve it relative to the path where the symlink file was located
done
DIR="$( cd -P "$( dirname "$SOURCE" )" && pwd )" #/Users/lidong/vmware_proj/CorfuDB/scripts

if ls "${DIR}"/../target/*.jar > /dev/null 2>&1; then
  # echo "Running from development source"
  CLASSPATH=("${DIR}"/../../infrastructure/target/infrastructure-*-shaded.jar)
else
  CLASSPATH=("${CORFUDB_PREFIX}"/share/corfu/lib/*.jar)
fi
echo $CLASSPATH

# Windows (cygwin) support
case "`uname`" in
    CYGWIN*) cygwin=true ;;
    *) cygwin=false ;;
esac

if $cygwin
then
    CLASSPATH=`cygpath -wp "$CLASSPATH"`
fi


# default heap for corfudb
CORFUDB_HEAP="${CORFUDB_HEAP:-2000}"
export JVMFLAGS="-Xmx${CORFUDB_HEAP}m $SERVER_JVMFLAGS"
export METRICS="-Dcorfu.local.metrics.collection=True -Dcorfu.metrics.csv.interval=1"

if [[ $* == *--agent* ]]
then
      byteman="-javaagent:"${BYTEMAN_HOME}"/lib/byteman.jar=listener:true"
else
      byteman=""
fi

"$JAVA" -cp "$CLASSPATH" $JVMFLAGS $byteman org.corfudb.infrastructure.CorfuServer $*

