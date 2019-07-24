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
./gradlew -p typesafe-builder-checker build publishToMavenLocal
git clone https://github.com/mernst/returnsrecv-checker.git
./gradlew -p returnsrecv-checker build publishToMavenLocal
```


2. Add the [org.checkerframework](https://plugins.gradle.org/plugin/org.checkerframework) Gradle plugin to your `plugins` block in your `build.gradle` script:

```groovy
plugins {
 	id "io.freefair.lombok" version "3.6.6"
	id "org.checkerframework" version "0.3.9"
}
```

3. Add a `checkerFramework` block that tells the plugin to run the Typesafe Builder Checker to your `build.gradle` script:

```groovy
checkerFramework {
 	checkers = ['org.checkerframework.checker.builder.TypesafeBuilderChecker']
}
```

4. Add a `checkerFramework` dependency on the Typesafe Builder Checker to your `dependencies` block (this is a compile-time only dependency):

```groovy
dependencies {
        checkerFramework 'org.checkerframework:typesafe-builder:0.1-SNAPSHOT'
}
```

5. Add an `implementation` dependency on the annotations used by the Typesafe Builder Checker (the checker needs to be able to place annotations in your code as it compiles, so the annotations must be on the compile classpath):

```groovy
dependencies {
	implementation 'org.checkerframework:typesafe-builder-qual:0.1-SNAPSHOT'
}
```

Once this is done, building your program will run the checker and alert you at compile time if any required properties might not be set.

