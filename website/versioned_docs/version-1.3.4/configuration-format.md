---
id: version-1.3.4-configuration-format
title: JSON Configuration Reference
sidebar_label: JSON Configuration
original_id: configuration-format
---

A bloop configuration file:

1. Is a JSON object, usually stored in a JSON file below `$WORKSPACE/.bloop/`
1. Defines a single Bloop project, its build inputs and its build outputs
1. Has a well-specified format evolved in a binary compatible way
1. Contains machine-dependent data such as user paths (cannot be shared across machines)

To use bloop you first need to generate Bloop configuration files from your build tool. 
Next is an example of the simplest bloop configuration file possible.

```json
{
    "version" : "1.0.0",
    "project" : {
        "name" : "foo",
        "directory" : "/disk/foo",
        "sources" : ["/disk/foo/src/main/scala"],
        "dependencies" : [],
        "classpath" : ["/disk/foo/library/scala-library.jar"],
        "out" : "/disk/foo/target",
        "classesDir" : "/disk/foo/target/classes"
    }
}
```

## Evolution and compatibility guarantees

The data in a bloop configuration file powers the build server functionality so it is common that
with the release of new bloop versions more fields are added to the configuration file format.

However, to avoid breaking any existing clients, changes in the Bloop configuration file are done
such that backwards compatibility is always guaranteed. For example, a `1.1.0` Bloop release will
understand data stored in a `1.0.0` configuration file.

## JSON Schema

The configuration file has a clear specification in the following [JSON
schema](assets/bloop-schema.json). The JSON schema file specifies the type of every field, its
description and content examples for every field.

If you find yourself editing bloop configuration files by hand, you can also use the JSON schema to
provide autocompletions and simple type error detection.

## Generating configuration files

Install the `bloop-config` dependency in your `build.sbt`:

```scala
libraryDependencies += "ch.epfl.scala" %% "bloop-config" % "1.1.0"
```

The `bloop-config` module is published to Maven Central and implements encoders and decoders to read
and write configuration files. Once the library is added in your build:

1. Create an instance of `bloop.config.Config.File` and populate all its fields.
2. Write the json file to a `target` path with `bloop.config.Config.File.write(config, target)`.
