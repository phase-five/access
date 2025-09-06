#!/bin/bash
# Copyright 2023-2025 Phase Five LLC.  For license terms, see LICENSE.txt in the repository root.

# Make paths relative to this script's directory, independent of where it's run from.
cd `dirname "$0"`/..

# Allowing over 32GB of heap will disable compressed object pointers and consume more memory.
# Starting at Java 24 we can enable compact object headers (8 bytes instead of 12).

export MAVEN_OPTS="-Xmx30G --enable-preview -XX:+UnlockExperimentalVMOptions -XX:+UseCompactObjectHeaders -Dlogback.configurationFile=conf/logback.xml"
nohup mvn package exec:java &
