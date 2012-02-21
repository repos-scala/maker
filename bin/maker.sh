#!/bin/bash

MAKER_DIR="$( cd "$(dirname $( dirname "${BASH_SOURCE[0]}" ))" && pwd )"
SAVED_DIR=`pwd`

set -e

MAKER_LIB_DIR=$MAKER_DIR/.maker/lib
MAKER_PROJECT_SCALA_LIB_DIR=.maker/scala-lib

main() {
  process_options $*

  if [ $MAKER_DOWNLOAD_PROJECT_LIB ] || [ ! -e $MAKER_PROJECT_SCALA_LIB_DIR ];
  then
    download_scala_library_and_compiler
  fi

  if [ $MAKER_IVY_UPDATE ] || [ ! -e $MAKER_LIB_DIR ];
  then
    ivy_update
  fi
  
  if [ $MAKER_BOOTSTRAP ] || [ ! -e $MAKER_DIR/maker.jar ];
  then
    bootstrap
  fi

  if [ -z $MAKER_SKIP_LAUNCH ];
  then
    export JAVA_OPTS="-Xmx$(($MAKER_HEAP_SPACE))m -Xms$(($MAKER_HEAP_SPACE / 10))m $JREBEL_OPTS"
    export CLASSPATH="$MAKER_DIR/maker.jar:$(external_jars)"
    $(scala_home)/bin/scala -Yrepl-sync -nc -i $(project_file)
  fi

}

run_command(){
  command=$1
  $command || (echo "failed to run $command " && exit -1)
}

external_jars() {
  echo `ls $MAKER_DIR/.maker/lib/*.jar | xargs | sed 's/ /:/g'`
}

scala_home(){
  if [ -z $SCALA_HOME ];
  then
    echo "SCALA_HOME not defined"
    exit -1
  else
    echo $SCALA_HOME
  fi
}

project_file(){
  if [ -z $MAKER_PROJECT_FILE ];
  then
    declare -a arr
    i=0
    for file in `ls *.scala`; do
      arr[$i]=$file
      ((i++))
    done
    if [ ${#arr[@]} != 1 ];
    then
      error "No project file found"
    fi
    MAKER_PROJECT_FILE="${arr[0]}"
  fi
  echo $MAKER_PROJECT_FILE
}

java_home(){
  if [ -z $JAVA_HOME ];
  then
    echo "JAVA_HOME not defined"
    exit -1
  else
    echo $JAVA_HOME
  fi
}

bootstrap() {

  pushd $MAKER_DIR  # Shouldn't be necessary to change dir, but get weird compilation errors otherwise
  rm -rf out
  mkdir out
  for module in utils plugin maker; do
    for src_dir in src tests; do
      SRC_FILES="$SRC_FILES $(find $MAKER_DIR/$module/$src_dir -name '*.scala' | xargs)"
    done
  done

  echo "Compiling"
  run_command "$(scala_home)/bin/fsc -classpath $(external_jars) -d out $SRC_FILES"
  echo "Building jar"
  run_command "$(java_home)/bin/jar cf maker.jar -C out/ ."
  popd

}

process_options() {
  set_default_options

  while true; do
    case "$1" in
      -h | --help ) display_usage; exit 0;;
      -p | --project-file ) MAKER_PROJECT_FILE=$2; shift 2;;
      -j | --use-jrebel ) set_jrebel_options; shift;;
      -m | --mem-heap-space ) MAKER_HEAP_SPACE=$2; shift 2;;
      -y | --do-ivy-update ) MAKER_IVY_UPDATE=true; shift;;
      -b | --boostrap ) MAKER_BOOTSTRAP=true; shift;;
      -d | --download-project-scala-lib ) $MAKER_DOWNLOAD_PROJECT_LIB=true; shift;;
      --ivy-proxy-host ) MAKER_IVY_PROXY_HOST=$2; shift 2;;
      --ivy-proxy-port ) MAKER_IVY_PROXY_PORT=$2; shift 2;;
      --ivy-non-proxy-hosts ) MAKER_IVY_NON_PROXY_HOSTS=$2; shift 2;; 
      --ivy-jar ) MAKER_IVY_JAR=$2; shift 2;; 
      --ivy-file ) MAKER_IVY_FILE=$2; shift 2;; 
      --ivy-settings ) MAKER_IVY_SETTINGS_FILE=$2; shift 2;; 
      -- ) shift; break;;
      *  ) break;;
    esac
  done

  REMAINING_ARGS=$*
}

display_usage() {
cat << EOF

  usage
    maker.sh <option>*

  options
    -h, --help
    -p, --project-file <project-file>
    -j, --use-jrebel (requires JREBEL_HOME to be set)
    -m, --mem-heap-space <heap space in MB> 
      default is one quarter of available RAM
    -y, --do-ivy-update 
      update will always be done if <maker-dir>/.maker/lib doesn't exist
    -b, --boostrap 
      builds maker.jar from scratch
    -d, --download-project-scala-lib 
      downloads scala compiler and library to <project-dir>/.maker/scala-lib
      download is automatic if this directory does not exist
    --ivy-proxy-host <host>
    --ivy-proxy-port <port>
    --ivy-non-proxy-hosts <host,host,...>
    --ivy-jar <file>        
      defaults to /usr/share/java/ivy.jar
    --ivy-file <file>       
      defaults to <maker-dir>/ivy.xml
    --ivy-settings  <file>  
      defaults to <maker-dir>/ivysettings.xml

EOF
}

ivy_jar(){
  if [ ! -z $MAKER_IVY_JAR ];
  then
    echo $MAKER_IVY_JAR
  elif [ -e /usr/share/java/ivy.jar ];
  then
    echo "/usr/share/java/ivy.jar"
  else
    error "Ivy jar not found"
  fi
}

error(){
  echo $1
  cd $SAVED_DIR
  exit -1
}

ivy_settings(){
  if [ ! -z $MAKER_IVY_SETTINGS_FILE ];
  then
    echo " -settings $MAKER_IVY_SETTINGS_FILE "
  elif [ -e "$MAKER_DIR/ivysettings.xml" ]
  then
    echo " -settings $MAKER_DIR/ivysettings.xml "
  fi
}

ivy_command(){
  ivy_file=$1
  lib_dir=$2
  if [ ! -e $lib_dir ];
  then
    mkdir -p $lib_dir
  fi
  command="java "
  if [ ! -z $MAKER_IVY_PROXY_HOST ];
  then
    command="$command -Dhttp.proxyHost=$MAKER_IVY_PROXY_HOST"
  fi
  if [ ! -z $MAKER_IVY_PROXY_PORT ];
  then
    command="$command -Dhttp.proxyPort=$MAKER_IVY_PROXY_PORT"
  fi
  if [ ! -z $MAKER_IVY_NON_PROXY_HOSTS ];
  then
    command="$command -Dhttp.nonProxyHosts=$MAKER_IVY_NON_PROXY_HOSTS"
  fi
  command="$command -jar $(ivy_jar) -ivy $ivy_file"
  command="$command $(ivy_settings) "
  command="$command -retrieve $lib_dir/[artifact]-[revision](-[classifier]).[ext] "
  echo $command
}


ivy_update() {
  echo "Updating ivy"
  run_command "$(ivy_command $MAKER_IVY_FILE $MAKER_LIB_DIR) -types jar -sync"
  run_command "$(ivy_command $MAKER_IVY_FILE $MAKER_LIB_DIR) -types bundle"
  run_command "$(ivy_command $MAKER_IVY_FILE $MAKER_LIB_DIR) -types source "
}

download_scala_library_and_compiler(){
  ivy_file=.maker/scala-lib-ivy.xml
  rm -f $ivy_file
  if [ ! -e $ivy_file ];
  then
cat > $ivy_file << EOF
<ivy-module version="1.0" xmlns:e="http://ant.apache.org/ivy/extra">
  <info organisation="maker" module="maker"/>
  <configurations>
    <conf name="default" transitive="false"/>
  </configurations>
  <dependencies defaultconfmapping="*->default,sources">
    <dependency org="org.scala-lang" name="scala-compiler" rev="2.9.1"/>
    <dependency org="org.scala-lang" name="scala-library" rev="2.9.1"/>
  </dependencies>
</ivy-module>
EOF
  command="$(ivy_command $ivy_file $MAKER_PROJECT_SCALA_LIB_DIR ) -types jar -sync"
  run_command "$command"
  command="$(ivy_command $ivy_file $MAKER_PROJECT_SCALA_LIB_DIR ) -types source"
  run_command "$command"
  fi
}

set_default_options() {
  MAKER_PROJECT_FILE="$MAKER_DIR/Maker.scala"
  JREBEL_OPTS=""
  MAKER_IVY_FILE="$MAKER_DIR/ivy.xml"

  # Set java heap size to something nice and big
  if [ -z $MAKER_HEAP_SPACE ];
  then
    if [ "$os" = "darwin" ];
    then
      totalMem=$(sysctl hw.memsize | awk '/[:s]/ {print $2}')
      totalMem=$(($totalMem/1024))
    else
      totalMem=$(cat /proc/meminfo | head -n 1 | awk '/[0-9]/ {print $2}')
    fi
    MAKER_HEAP_SPACE=$(($totalMem/1024/4))
  fi
}


set_jrebel_options() {
  if [ ! -f $JREBEL_HOME/jrebel.jar ];
  then
    echo "Can't find jrebel.jar, set JREBEL_HOME"
    exit 1
  fi
  JREBEL_OPTS=" -javaagent:$JREBEL_HOME/jrebel.jar -noverify"
}


main $*