#!/bin/bash

# Broker Back-End build
cd broker-back-end

if [ ! -f "mvnw" ]; then
    echo "Maven wrapper not found. Installing..."
    mvn -N io.takari:maven:wrapper -Dmaven=3.6.3
fi

echo "Building Broker Back-End..."
./mvnw clean package -DskipTests

if [ $? -eq 0 ]; then
    echo "Broker Back-End build successful"
else
    echo "Broker Back-End build failed"
    exit 1
fi

cd ..
