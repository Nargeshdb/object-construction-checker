# Using the Object Construction Checker with Lombok

This document describes how to use the Object Construction Checker with [Lombok](https://projectlombok.org).
The Object Construction Checker guarantees, at compile time, that there will be no run-time exceptions due to the programmer failing
to set a required fields in a call to a builder.

What you’ll need:
* A project that uses Lombok via the [io.freefair.lombok](https://plugins.gradle.org/plugin/io.freefair.lombok) Gradle plugin.

What to do:

1. [Todo: remove this requirement]
Build the Object Construction Checker:
```
git clone https://github.com/kelloggm/typesafe-builder-checker.git
(cd typesafe-builder-checker && ./gradlew build && ./gradlew publishToMavenLocal)
git clone https://github.com/mernst/returnsrecv-checker.git
(cd returnsrecv-checker && ./gradlew build && ./gradlew publishToMavenLocal)
```

2. Add the [org.checkerframework](https://plugins.gradle.org/plugin/org.checkerframework) Gradle plugin to the `plugins` block of your `build.gradle` file:

```groovy
plugins {
 	id "io.freefair.lombok" version "3.6.6"
	id "org.checkerframework" version "0.3.9"
}
```

3. Add the following to your `build.gradle` file:

```
repositories {
    mavenLocal()
}
checkerFramework {
    checkers = ['org.checkerframework.checker.builder.TypesafeBuilderChecker']
extraJavacArgs = [
    '-AsuppressWarnings=type.anno.before',
  ]
}
dependencies {
    checkerFramework 'org.checkerframework:typesafe-builder:0.1-SNAPSHOT'
    implementation 'org.checkerframework:typesafe-builder-qual:0.1-SNAPSHOT'
}
```


After these three steps, building your program will run the checker and alert you at compile time if any required properties might not be set.

