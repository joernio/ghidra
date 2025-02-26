<img src="Ghidra/Features/Base/src/main/resources/images/GHIDRA_3.png" width="400">

# Notes about this fork

This fork exists for two reasons: 
1) The NSA doesn't publish the ghidra jar to maven central, but we want to depend on it via regular maven coordinates in our java/scala builds. 
2) We want to be able to make our own decision about patches, e.g. [this one](https://github.com/NationalSecurityAgency/ghidra/pull/5256) which works fine but for some reason hasn't been merged upstream - this fork [has it](https://github.com/joernio/ghidra/pull/3).

Note: we want to keep things simple, one of them being the git history. If you look at this repository you'll see that we take upstream ghidra and apply our changes on top. Which brings us to...

## Howto: update to the latest ghidra upstream
As mentioned above we want a simple git history and always have our changes _on top_, i.e. we never want to merge upstream changes into this repository. Instead, we rebase and force-push the changes to our fork's master. Some people might call that sinning, but simplicity is king, this is just a fork, and then there's yolo ¯\_(ツ)_/¯

Prerequisite: remote 'upstream' is already set up, like so:
```bash
git remote add upstream git@github.com:NationalSecurityAgency/ghidra.git
```

Then:
```bash
git pull origin
git fetch upstream
git rebase upstream/master
git push -f
```

## How can I run a release?
Prerequisite: you need to have joern's sonatype credentials configured in your `~/.m2/settings.xml`, i.e. there should be an entry like this, which you can get from our sonatype central account. 
```xml
<server>
  <!-- joern-io@central.sonatype.com -->
  <id>sonatype-central-joern</id>
  <username>MXQnCFgb</username>
  <password>SECRET_TOKEN</password>
</server>
```
Context: [pom.xml.template](https://github.com/joernio/ghidra/blob/40346937b37889112cd4515e0535bf9e37f69a9a/pom.xml.template#L50) references the sonatype central server as `sonatype-central-joern`

Then you should be able to run `./ghidra-publish.sh` which will build ghidra, create a temporary maven project and publish it to sonatype central.


# Ghidra Software Reverse Engineering Framework
Ghidra is a software reverse engineering (SRE) framework created and maintained by the 
[National Security Agency][nsa] Research Directorate. This framework includes a suite of 
full-featured, high-end software analysis tools that enable users to analyze compiled code on a 
variety of platforms including Windows, macOS, and Linux. Capabilities include disassembly, 
assembly, decompilation, graphing, and scripting, along with hundreds of other features. Ghidra 
supports a wide variety of processor instruction sets and executable formats and can be run in both 
user-interactive and automated modes. Users may also develop their own Ghidra extension components 
and/or scripts using Java or Python.

In support of NSA's Cybersecurity mission, Ghidra was built to solve scaling and teaming problems 
on complex SRE efforts, and to provide a customizable and extensible SRE research platform. NSA has 
applied Ghidra SRE capabilities to a variety of problems that involve analyzing malicious code and 
generating deep insights for SRE analysts who seek a better understanding of potential 
vulnerabilities in networks and systems.

If you are a U.S. citizen interested in projects like this, to develop Ghidra and other 
cybersecurity tools for NSA to help protect our nation and its allies, consider applying for a 
[career with us][career].

## Security Warning
**WARNING:** There are known security vulnerabilities within certain versions of Ghidra.  Before 
proceeding, please read through Ghidra's [Security Advisories][security] for a better understanding 
of how you might be impacted.

## Install
To install an official pre-built multi-platform Ghidra release:  
* Install [JDK 21 64-bit][jdk]
* Download a Ghidra [release file][releases]
  - **NOTE:** The official multi-platform release file is named 
    `ghidra_<version>_<release>_<date>.zip` which can be found under the "Assets" drop-down.
    Downloading either of the files named "Source Code" is not correct for this step.
* Extract the Ghidra release file
* Launch Ghidra: `./ghidraRun` (`ghidraRun.bat` for Windows)
  - or launch [PyGhidra][pyghidra]: `./support/pyGhidraRun` (`support\pyGhidraRun.bat` for Windows)

For additional information and troubleshooting tips about installing and running a Ghidra release, 
please refer to the [Getting Started][gettingstarted] document which can be found at the root of a 
Ghidra installation directory. 

## Build
To create the latest development build for your platform from this source repository:

##### Install build tools:
* [JDK 21 64-bit][jdk]
* [Gradle 8.5+][gradle] (or provided Gradle wrapper if Internet connection is available)
* [Python3][python3] (version 3.9 to 3.13) with bundled pip
* make, gcc/g++ or clang (Linux/macOS-only)
* [Microsoft Visual Studio][vs] 2017+ or [Microsoft C++ Build Tools][vcbuildtools] with the
  following components installed (Windows-only):
  - MSVC
  - Windows SDK
  - C++ ATL

##### Download and extract the source:
[Download from GitHub][master]
```
unzip ghidra-master
cd ghidra-master
```
**NOTE:** Instead of downloading the compressed source, you may instead want to clone the GitHub 
repository: `git clone https://github.com/NationalSecurityAgency/ghidra.git`

##### Download additional build dependencies into source repository:
**NOTE:** If an Internet connection is available and you did not install Gradle, the following 
`gradle` commands may be replaced with `./gradlew(.bat)`.
```
gradle -I gradle/support/fetchDependencies.gradle
```

##### Create development build: 
```
gradle buildGhidra
```
The compressed development build will be located at `build/dist/`.

For more detailed information on building Ghidra, please read the [Developer Guide][devguide].

For issues building, please check the [Known Issues][known-issues] section for possible solutions.

## Develop

### User Scripts and Extensions
Ghidra installations support users writing custom scripts and extensions via the *GhidraDev* plugin 
for Eclipse.  The plugin and its corresponding instructions can be found within a Ghidra release at
`Extensions/Eclipse/GhidraDev/` or at [this link][ghidradev].  Alternatively, Visual Studio Code may
be used to edit scripts by clicking the Visual Studio Code icon in the Script Manager.
Fully-featured Visual Studio Code projects can be created from a Ghidra CodeBrowser window at 
_Tools -> Create VSCode Module project_.

**NOTE:** Both the *GhidraDev* plugin for Eclipse and Visual Studio Code integrations only support 
developing against fully built Ghidra installations which can be downloaded from the
[Releases][releases] page.

### Advanced Development
To develop the Ghidra tool itself, it is highly recommended to use Eclipse, which the Ghidra 
development process has been highly customized for.

##### Install build and development tools:
* Follow the above [build instructions](#build) so the build completes without errors
* Install [Eclipse IDE for Java Developers][eclipse]

##### Prepare the development environment:
``` 
gradle prepdev eclipse buildNatives
```

##### Import Ghidra projects into Eclipse:
* *File* -> *Import...*
* *General* | *Existing Projects into Workspace*
* Select root directory to be your downloaded or cloned ghidra source repository
* Check *Search for nested projects*
* Click *Finish*

When Eclipse finishes building the projects, Ghidra can be launched and debugged with the provided
**Ghidra** Eclipse *run configuration*.

For more detailed information on developing Ghidra, please read the [Developer Guide][devguide].

## Contribute
If you would like to contribute bug fixes, improvements, and new features back to Ghidra, please 
take a look at our [Contributor Guide][contrib] to see how you can participate in this open 
source project.


[nsa]: https://www.nsa.gov
[contrib]: CONTRIBUTING.md
[devguide]: DevGuide.md
[gettingstarted]: GhidraDocs/GettingStarted.md
[known-issues]: DevGuide.md#known-issues
[career]: https://www.intelligencecareers.gov/nsa
[releases]: https://github.com/NationalSecurityAgency/ghidra/releases
[jdk]: https://adoptium.net/temurin/releases
[gradle]: https://gradle.org/releases/
[python3]: https://www.python.org/downloads/
[vs]: https://visualstudio.microsoft.com/vs/community/
[vcbuildtools]: https://visualstudio.microsoft.com/visual-cpp-build-tools/
[eclipse]: https://www.eclipse.org/downloads/packages/
[master]: https://github.com/NationalSecurityAgency/ghidra/archive/refs/heads/master.zip
[security]: https://github.com/NationalSecurityAgency/ghidra/security/advisories
[ghidradev]: GhidraBuild/EclipsePlugins/GhidraDev/GhidraDevPlugin/README.md
[pyghidra]: Ghidra/Features/PyGhidra/README.md
