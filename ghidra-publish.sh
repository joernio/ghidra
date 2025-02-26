#!/usr/bin/env bash

set -e #stop on error
set -o pipefail

echo "Starting ghidra maven central release."
echo "Every release must have a unique version - please adapt the 'application.release.name' property\
 in the file we're about to open"
echo "Note: it must be unique for the current 'application.version' - e.g. if the major version is\
 10.4 and we have already released JOERN-DEV-0, then increment it to JOERN-DEV-1"
echo "Makes sense? Press ENTER to open Ghidra/application.properties"
read CONFIRM
vim Ghidra/application.properties

MAJOR_VERSION=$(grep application.version      Ghidra/application.properties | cut -d= -f2)
MINOR_VERSION=$(grep application.release.name Ghidra/application.properties | cut -d= -f2)
FULL_VERSION=${MAJOR_VERSION}_${MINOR_VERSION}
echo "Ok so you want to build and release ghidra version '${FULL_VERSION}'? We'll remove the build/dist directory in the process, so files from old releases will be deleted."
echo "Press ENTER to proceed."
read CONFIRM

# the resulting zipfile name also includes the current date in YMD format, e.g. 20250226
YEAR_MONTH_DAY=$(date +"%Y%m%d")

# clean everything to be on the safe side
rm -rf build

# kick off the build
gradle -I gradle/support/fetchDependencies.gradle buildGhidra

PROJECT_ROOT=$(pwd)

pushd build/dist
  unzip "ghidra_${FULL_VERSION}_${YEAR_MONTH_DAY}_linux_x86_64.zip"
  pushd "ghidra_${FULL_VERSION}"
    support/buildGhidraJar
  popd

  DIST_DIR=$(pwd)/ghidra_${FULL_VERSION}

  mkdir -p maven-build
  pushd maven-build
    # create custom-made maven build just for deploying to maven central
    sed s/__VERSION__/${FULL_VERSION}/ ${PROJECT_ROOT}/pom.xml.template > pom.xml
    mkdir -p src/main/resources
    unzip -d src/main/resources ${DIST_DIR}/ghidra.jar

    # add classes from ByteViewer.jar - those are looked up at runtime via reflection
    # context: lookup happens transitively by loading classes from _Root/Ghidra/EXTENSION_POINT_CLASSES
    # unzip flag `-o` to override and update files without prompting the user
    unzip -o -d src/main/resources ${DIST_DIR}/Ghidra/Features/ByteViewer/lib/ByteViewer.jar

    # add an empty dummy class in order to generate sources and javadoc jars
    mkdir -p src/main/java
    echo '/** just an empty placeholder to trigger javadoc generation */
    public interface empty {}' > src/main/java/empty.java

    # deploy to sonatype central
    mvn clean javadoc:jar source:jar deploy
  popd
popd

echo "release is now published to sonatype central and should get promoted to maven central automatically. For more context go to https://central.sonatype.com/publishing/deployments"
echo "once it's synchronised to maven central (https://repo1.maven.org/maven2/io/joern/ghidra/), update the ghidra version in 'joern/project/Versions.scala' to $CUSTOM_RELEASE_VERSION"
echo "don't forget to commit and push the local changes in this repo to https://github.com/joernio/ghidra"
