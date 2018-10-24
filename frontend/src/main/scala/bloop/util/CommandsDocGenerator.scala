package bloop.util

import bloop.Cli
import bloop.cli.{CliParsers, Commands, CommonOptions}
import bloop.engine.Run
import caseapp.{Name, ValueDescription}
import caseapp.core.{Arg, Messages, NameOps, ValueDescriptionOps}

object CommandsDocGenerator {
  def main(args: Array[String]): Unit = {
    def generateHTML(commandExamples: Map[String, Seq[String]]): Seq[String] = {
      CliParsers.CommandsMessages.messages.map {
        case (commandName, messages) =>
          val examples = commandExamples.getOrElse(commandName, Nil).reverse.map { example =>
            s"  * <samp>$example</samp>"
          }

          val argsOption = messages.argsNameOption.map(" <" + _ + ">").mkString
          val progName = "bloop"
          val b = new StringBuilder
          b ++= Messages.NL
          b ++= s"## `$progName $commandName$argsOption`"
          b ++= Messages.NL
          b ++= Messages.NL
          b ++= "### Usage"
          b ++= Messages.NL
          b ++= Messages.NL
          b ++= s"<dl>"
          b ++= Messages.NL
          b ++= optionsMessage(messages.args)
          b ++= Messages.NL
          b ++= s"</dl>"
          b ++= Messages.NL
          if (examples.nonEmpty) {
            b ++= Messages.NL
            b ++= s"### Examples${Messages.NL}"
            b ++= Messages.NL
            b ++= examples.mkString(Messages.NL)
          }
          b.result()
      }
    }

    parseExamples match {
      case Left(msg) => println(s"Error: $msg")
      case Right(commandExamples) if (args.headOption.contains("--test")) =>
        val generation = generateHTML(commandExamples)
        assert(!generation.isEmpty, s"Generation of HTML yielded empty map! ${generation}")
      case Right(commandExamples) =>
        println(generateHTML(commandExamples).mkString(Messages.NL))
    }
  }

  def optionsMessage(args: Seq[Arg]): String = {
    val options = args.collect {
      case arg if !arg.noHelp =>
        val names = (Name(arg.name) +: arg.extraNames).distinct
        val valueDescription =
          arg.valueDescription.orElse(if (!arg.isFlag) Some(ValueDescription("value")) else None)

        val message = arg.helpMessage.map(_.message).getOrElse("")
        val cmdNames = names.map("<code>" + _.option + "</code>").mkString(" or ")
        val usage = s"${Messages.WW}$cmdNames  ${valueDescription.map(_.message).mkString}"
        val description =
          if (message.isEmpty) message
          else s"${Messages.NL}  <dd><p>${message}</p></dd>"
        s"  <dt>$cmdNames (type: <code>${arg.hintDescription}</code>)</dt>$description"
    }

    options.mkString(Messages.NL)
  }

  private val ExampleProjectName: String = "foobar"
  private val ExampleProjectName2: String = "baz"
  private val ExampleMainClass: String = "com.acme.Main"
  private final val CommandExamples = {
    val tmp = java.nio.file.Files.createTempDirectory("tmp")
    List(
      "bloop projects",
      "bloop projects --dot-graph",
      "bloop clean",
      s"bloop clean $ExampleProjectName",
      s"bloop clean $ExampleProjectName $ExampleProjectName2",
      s"bloop clean $ExampleProjectName --propagate",
      s"bloop bsp --protocol local --socket ${tmp.resolve("socket").toString} --pipe-name windows-name-pipe",
      "bloop bsp --protocol tcp --host 127.0.0.1 --port 5101",
      s"bloop compile $ExampleProjectName",
      s"bloop compile $ExampleProjectName -w",
      s"bloop compile $ExampleProjectName --reporter bloop",
      s"bloop compile $ExampleProjectName --reporter scalac",
      s"bloop test $ExampleProjectName",
      s"bloop test $ExampleProjectName -w",
      s"bloop test $ExampleProjectName --propagate",
      s"bloop test $ExampleProjectName --propagate -w",
      s"bloop test $ExampleProjectName --only com.acme.StringSpecification",
      s"bloop console $ExampleProjectName",
      s"bloop console $ExampleProjectName --exclude-root",
      s"bloop run $ExampleProjectName",
      s"bloop run $ExampleProjectName -m $ExampleMainClass -- arg1 arg2",
      s"bloop run $ExampleProjectName -O debug -- arg1",
      s"bloop run $ExampleProjectName -m $ExampleMainClass -O release -w",
      s"bloop link $ExampleProjectName",
      s"bloop link $ExampleProjectName --main $ExampleMainClass",
      s"bloop link $ExampleProjectName -O debug -w",
      s"bloop link $ExampleProjectName -O release -w",
      s"bloop link $ExampleProjectName --main $ExampleMainClass -w",
    )
  }

  private def commandName(cmd: Commands.Command): Option[String] = {
    cmd match {
      case _: Commands.About => Some("about")
      case _: Commands.Projects => Some("projects")
      case _: Commands.Clean => Some("clean")
      case _: Commands.Bsp => Some("bsp")
      case _: Commands.Compile => Some("compile")
      case _: Commands.Test => Some("test")
      case _: Commands.Console => Some("console")
      case _: Commands.Run => Some("run")
      case _: Commands.Link => Some("link")
      case _ => None
    }
  }

  case class Example(projectName: String, examples: List[String])
  def parseExamples: Either[String, Map[String, List[String]]] = {
    val parsed = CommandExamples.foldLeft[Either[String, List[Example]]](Right(Nil)) {
      case (l @ Left(_), _) => l
      case (Right(prevExamples), example) =>
        Cli.parse(example.split(" ").tail, CommonOptions.default) match {
          case Run(cmd: Commands.Command, _) =>
            commandName(cmd) match {
              case Some(name) =>
                Right(Example(name, List(example)) :: prevExamples)
              case None => Right(prevExamples)
            }

          case a => Left(s"Example $example yielded unrecognized action $a")
        }
    }

    parsed.map(_.groupBy(_.projectName).mapValues(_.flatMap(_.examples)))
  }
}
