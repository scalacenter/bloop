---
id: gradle
title: Gradle
sidebar_label: Gradle
---

Gradle 4.8.x uses a three years old version of Zinc (Scala's incremental compiler) which [misses out
on a lot of improvements with regards to performance and correctness in
1.x](https://www.scala-lang.org/blog/2017/11/03/zinc-blog-1.0.html). Installing bloop makes a big
difference in Gradle builds.

## Export the build

Install and learn how to export the build in the Installation Guide:

<a class="button" href="/bloop/setup">Follow the Installation Guide</a>

<br>

## Disambiguate projects with same name

Gradle project names are disambiguate by the path in which they are defined, even if the build
contains projects with the same simple name. In the bloop gradle plugin, simple project names have a
one-to-one equivalence with bloop project names. As a results, some name conflicts can occur when
there are two projects named `web` defined in `a/web` and `b/web`.

For the moment, there is not an official solution to this problem. If you want to help out fix it,
there is an open pull request proposing a solution that needs to be rebased and changed according to
feedback. If you feel like helping out, please comment in the ticket.
