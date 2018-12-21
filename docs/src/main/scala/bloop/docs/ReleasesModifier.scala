package bloop.docs

import mdoc.Reporter
import mdoc.StringModifier

import scala.meta.inputs.Input

class ReleasesModifier extends StringModifier {
  override val name: String = "releases"
  override def process(info: String, code: Input, reporter: Reporter): String = {
    val xml = <table>
      <thead>
        <th>Version</th>
        <th>Published</th>
        <th>Resolver</th>
      </thead>
      <tbody>
        <tr>
          <td>{Sonatype.releaseBloop.version}</td>
          <td>{Sonatype.releaseBloop.date}</td>
          <td><code>-r sonatype:releases</code></td>
        </tr>
        <tr>
          <td>{Sonatype.current.version}</td>
          <td>{Sonatype.current.date}</td>
          <td><code>-r bintray:scalacenter/releases</code></td>
        </tr>
      </tbody>
    </table>
    xml.toString
  }
}

class LauncherReleasesModifier extends StringModifier {
  override val name: String = "launcher-releases"
  override def process(info: String, code: Input, reporter: Reporter): String = {
    val xml = {
      try {
        <table>
          <thead>
            <th>Version</th>
            <th>Published</th>
            <th>Resolver</th>
          </thead>
          <tbody>
            <tr>
              <td>{Sonatype.releaseLauncher.version}</td>
              <td>{Sonatype.releaseLauncher.date}</td>
              <td><code>-r sonatype:releases</code></td>
            </tr>
            <tr>
              <td>{Sonatype.current.version}</td>
              <td>{Sonatype.current.date}</td>
              <td><code>-r bintray:scalacenter/releases</code></td>
            </tr>
          </tbody>
        </table>
      } catch {
        // The given artifact could not be released
        case t: org.jsoup.HttpStatusException =>
          <blockquote>
            The launcher module has not yet hit Maven Central. This section will
            be updated next time there is a change in the bloop docs.
          </blockquote>
      }
    }
    xml.toString
  }
}
