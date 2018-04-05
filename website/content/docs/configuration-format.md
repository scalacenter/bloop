+++
weight = 10
title = "The configuration format"
date = "2018-02-09T10:15:00+01:00"
description = "What makes the bloop configuration file? Check out the schema."
bref = "Learn what makes a bloop configuration file and how to generate it from other tools"
toc = true
draft = false
+++

The bloop configuration file is a JSON file that describes a project.

The configuration file aims to be an strict subset of all the information required to compile,
test and run your project, and therefore any build tool can quickly generate one.

The configuration file is machine-dependent and it's not to be shared by different machines or users.
Every time you re-generate resources, change a source directory or add a dependency in your stock
build tool, you need to regenerate the configuration file for bloop to pick up the changes.

## JSON Schema

<script src="../../docson/widget.js" data-schema="bloop-schema.json">
</script>

Download the JSON schema from [this file](../../docson/bloop-schema.json).

## Generate bloop configuration files

The `bloop-config` artifact can be used as a library dependency to generate bloop configurations
from any build tool. These are the instructions that you need to follow:

1. Add a dependency to the json configuration module in your `build.sbt`:
   <pre><code class="language-scala hljs scala">addSbtPlugin(<span class="hljs-string">"ch.epfl.scala"</span> % <span class="hljs-string">"bloop-config"</span> % <span class="hljs-string">"<span class="latest-version">1.0.0-M8</span>"</span>)</code></pre>

2. Create an instance `config` of `bloop.config.Config.File`.
3. Write the json file to a `target` with `bloop.config.Config.File.write(config, target)`.

<script type="text/javascript">
  
  $.get("https://cors-anywhere.herokuapp.com/repo1.maven.org/maven2/ch/epfl/scala/bloop-frontend_2.12/maven-metadata.xml", function(xml) {
  
  var versions = 
    xml.getElementsByTagName("metadata")[0]
       .getElementsByTagName("versioning")[0]
       .getElementsByTagName("versions")[0]
       .getElementsByTagName("version")

  var latest = null;
  for (var i = versions.length - 1; i >= 0; i--) {
    var text = versions[i].innerHTML;
    if(text.indexOf(".") != -1 ) {
      latest = text;
      break
    }
  }

  $(".latest-version").html(latest);

}, "xml")
</script>
