#!/usr/bin/env bash

# on github actions we want to bypass user confirmation via --non-interactive
NON_INTERACTIVE_OPTION=$1

# before we start: ensure that everything is committed and the git index is clean:
# exit code is 1 if there are changes
git diff-files --quiet
if [[ $? -ne 0 ]]; then
  echo "There are uncommitted changes in this git repository, please fix that!"
  exit 1
fi

UNTRACKED_FILES_COUNT=$(git status --porcelain 2>/dev/null| grep "^??" | wc -l)
if [[ $UNTRACKED_FILES_COUNT -ne 0 ]]; then
  echo "There is/are $UNTRACKED_FILES_COUNT untracked file(s) in this git repository, please fix that!"
  exit 1
fi

MAJOR_VERSION=$(grep application.version      Ghidra/application.properties | cut -d= -f2)
GIT_SHA=$(git log -1 --pretty=format:%h)
DATE_TIME=$(date +"%Y%m%d%H%M")
MINOR_VERSION=${GIT_SHA}-${DATE_TIME}
FULL_VERSION=${MAJOR_VERSION}_${MINOR_VERSION}
bold=$(tput bold)
normal=$(tput sgr0)
echo "Ready to start ghidra build and maven central release for version ${bold}${FULL_VERSION}${normal}"
echo "We'll remove the build directory, i.e. files from old releases will be deleted."
if [ "$NON_INTERACTIVE_OPTION" != "--non-interactive" ]
then
  echo "Press ${bold}ENTER${normal} to proceed."
  read CONFIRM
fi

# clean everything to be on the safe side
rm -rf build

# setting the release.name in application.properties, a.k.a. minor version to include a git sha and the current date - we will revert this after the build
sed -i s/__RELEASE_NAME__/${MINOR_VERSION}/ Ghidra/application.properties

echo 'building ghidra now - the console output is very noisy, so we redirect it to buildGhidra.out'
gradle -I gradle/support/fetchDependencies.gradle buildGhidra > buildGhidra.out
BUILD_EXIT_CODE=$?

# revert the change we made above in application.properties: back to the placeholder
git restore Ghidra/application.properties

if [[ $BUILD_EXIT_CODE -ne 0 ]]; then
  echo "The ghidra build failed, please check the console output above."
  exit 1
fi

# from now on we want to stop on errors - note that prior to this line, we wanted to handle the exit codes explicitly!
set -e #stop on error
set -o pipefail

PROJECT_ROOT=$(pwd)

pushd build/dist
  # the resulting zipfile name also includes the current date in YMD format, e.g. 20250226
  YEAR_MONTH_DAY=$(date +"%Y%m%d")
  unzip "ghidra_${FULL_VERSION}_${YEAR_MONTH_DAY}_linux_x86_64.zip"
  pushd "ghidra_${FULL_VERSION}"
    echo 'building ghidra jar now - the console output is very noisy, so we redirect it to buildGhidraJar.out'
    support/buildGhidraJar > buildGhidraJar.out
  popd

  DIST_DIR=$(pwd)/ghidra_${FULL_VERSION}

  mkdir -p maven-build
  pushd maven-build
    # create custom-made maven build just for deploying to maven central
    sed s/__VERSION__/${FULL_VERSION}/ ${PROJECT_ROOT}/pom.xml.template > pom.xml
    mkdir -p src/main/resources
    unzip -d src/main/resources ${DIST_DIR}/ghidra.jar

    # add native libraries for windows/mac - these are built on github actions in the respective 'build_natives' jobs
    for arch in mac_arm_64 mac_x86_64 win_x86_64
    do
      cp -rpv ${PROJECT_ROOT}/Ghidra/Features/Decompiler/build/os/$arch src/main/resources/_Root/Ghidra/Features/Decompiler/os/
      cp -rpv ${PROJECT_ROOT}/Ghidra/Features/FileFormats/build/os/$arch src/main/resources/_Root/Ghidra/Features/FileFormats/os/
      cp -rpv ${PROJECT_ROOT}/GPL/DemanglerGnu/build/os/$arch src/main/resources/_Root/Ghidra/DemanglerGnu/os/
    done

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

echo "success! release is now published to sonatype central and should get promoted to maven central automatically. For more context go to https://central.sonatype.com/publishing/deployments"
echo "once it's synchronised to maven central (https://repo1.maven.org/maven2/io/joern/ghidra/), update the ghidra version in 'joern/project/Versions.scala' to:"
echo "${bold}${FULL_VERSION}${normal}"


