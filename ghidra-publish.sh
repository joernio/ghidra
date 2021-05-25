#!/usr/bin/env bash

set -e #stop on error
set -o pipefail

# this script downloads a ghidra release from ghidra-sre and publishes it to
# sonatype, so that we can promote it to maven central:
# https://repo1.maven.org/maven2/io/joern/ghidra/
# see also https://github.com/NationalSecurityAgency/ghidra/issues/799

VERSION=11.3.1_PUBLIC_20250219
VERSION_SHORTER=11.3.1
VERSION_SHORT=${VERSION_SHORTER}_PUBLIC
CUSTOM_RELEASE_VERSION=${VERSION}-0

DISTRO_URL=https://github.com/NationalSecurityAgency/ghidra/releases/download/Ghidra_${VERSION_SHORTER}_build/ghidra_${VERSION}.zip
echo "download and unzip ghidra distribution from $DISTRO_URL"
wget $DISTRO_URL
rm -rf ghidra_${VERSION_SHORT}
unzip ghidra_$VERSION.zip
rm ghidra_$VERSION.zip
cd ghidra_${VERSION_SHORT}
support/buildGhidraJar

# create custom-made maven build just for deploying to maven central
rm -rf build
mkdir build
sed s/__VERSION__/$CUSTOM_RELEASE_VERSION/ ../pom.xml.template > build/pom.xml
mkdir -p build/src/main/resources
unzip -d build/src/main/resources ghidra.jar

# add classes from ByteViewer.jar - those are looked up at runtime via reflection
# context: lookup happens transitively by loading classes from _Root/Ghidra/EXTENSION_POINT_CLASSES
# unzip flag `-o` to override and update files without prompting the user
unzip -o -d build/src/main/resources Ghidra/Features/ByteViewer/lib/ByteViewer.jar

# add an empty dummy class in order to generate sources and javadoc jars
mkdir -p build/src/main/java
echo '/** just an empty placeholder to trigger javadoc generation */
public interface Empty {}' > build/src/main/java/Empty.java

# deploy to sonatype central
pushd build
mvn javadoc:jar source:jar package gpg:sign deploy
popd

echo "release is now published to sonatype central and should get promoted to maven central automatically. For more context go to https://central.sonatype.com/publishing/deployments"
echo "once it's synchronised to maven central (https://repo1.maven.org/maven2/io/joern/ghidra/), update the ghidra version in 'joern/project/Versions.scala' to $CUSTOM_RELEASE_VERSION"
echo "don't forget to commit and push the local changes in this repo to https://github.com/joernio/ghidra"
