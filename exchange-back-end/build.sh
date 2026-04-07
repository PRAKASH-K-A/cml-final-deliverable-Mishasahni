#!/usr/bin/env bash
set -e

mvnw_cmd="./mvnw"
if [[ "$OSTYPE" == "msys" || "$OSTYPE" == "cygwin" ]]; then
    mvnw_cmd="mvnw.cmd"
fi

# Check if Maven wrapper exists
if [ ! -f "$mvnw_cmd" ]; then
    echo "Maven wrapper not found. Installing..."
    mvn -N io.takari:maven:wrapper
fi

echo "Building Exchange Back-End..."
$mvnw_cmd clean package

