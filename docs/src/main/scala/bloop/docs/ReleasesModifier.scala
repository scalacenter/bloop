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
          <td>{Sonatype.release.version}</td>
          <td>{Sonatype.release.date}</td>
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
