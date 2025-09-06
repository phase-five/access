#!/bin/bash
# Copyright 2023-2025 Phase Five LLC.  For license terms, see LICENSE.txt in the repository root.

# Make paths relative to this script's directory, independent of where it's run from.
cd `dirname "$0"`/..

export MAVEN_OPTS="-Xmx100M --enable-preview -XX:+UnlockExperimentalVMOptions -XX:+UseCompactObjectHeaders"
mvn package exec:java -Dexec.mainClass=io.pfive.access.authentication.UserCLI
