package sbt

import sbt.internal.util.complete.{DefaultParsers, Parser}
import DefaultParsers._
import sbt.Keys._
import sbt.internal.CommandStrings._
import Cross.{requireSession, spacedFirst}
import sbt.internal.SettingCompletions

object WorkingPluginCross {
  final val oldPluginSwitch = sbt.PluginCross.pluginSwitch
  lazy val pluginSwitch: Command = {
    def switchParser(state: State): Parser[(String, String)] = {
      val knownVersions = Nil
      lazy val switchArgs = token(NotSpace.examples(knownVersions: _*)) ~ (token(
        Space ~> matched(state.combinedParser)) ?? "")
      lazy val nextSpaced = spacedFirst(PluginSwitchCommand)
      token(PluginSwitchCommand ~ OptSpace) flatMap { _ =>
        switchArgs & nextSpaced
      }
    }

    def crossExclude(s: Def.Setting[_]): Boolean =
      s.key match {
        case Def.ScopedKey(Scope(_, _, pluginCrossBuild.key, _), sbtVersion.key) => true
        case _ => false
      }

    Command.arb(requireSession(switchParser), pluginSwitchHelp) {
      case (state, (version, command)) =>
        val x = Project.extract(state)
        import x._
        state.log.info(s"Setting `sbtVersion in pluginCrossBuild` to $version")
        val add = List(sbtVersion in GlobalScope in pluginCrossBuild :== version) ++
          List(scalaVersion := PluginCross.scalaVersionSetting.value) ++
          inScope(GlobalScope.copy(project = Select(currentRef)))(
            Seq(scalaVersion := PluginCross.scalaVersionSetting.value)
          )
        val session = SettingCompletions.setThis(state, x, add, "").session
        BuiltinCommands.reapply(session, structure, command :: state)
    }
  }
}
