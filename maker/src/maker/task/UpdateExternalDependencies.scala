package maker.task

import maker.project.Project
import maker.os.Command
import maker.utils.Log

case object UpdateExternalDependencies extends Task{

  def exec(project : Project, acc : List[AnyRef]) = {
    import project._
    managedLibDir.mkdirs
    Log.info("Updating " + name)
    def commands() : List[Command] = {
      val java = props.JavaHome().getAbsolutePath + "/bin/java"
      val ivyJar = props.IvyJar().getAbsolutePath
      def proxyParameter(parName : String, property : Option[String]) : List[String] = {
        property match {
          case Some(thing) => (parName + "=" + thing) :: Nil
          case None => Nil
        }
      }
        
      val parameters = List(java) ::: 
        proxyParameter("-Dhttp.proxyHost", project.props.HttpProxyHost()) :::
        proxyParameter("-Dhttp.proxyPort", project.props.HttpProxyPort()) :::
        proxyParameter("-Dhttp.nonProxyHosts", project.props.HttpNonProxyHosts()) :::
        ("-jar"::ivyJar::"-settings"::ivySettingsFile.getPath::"-ivy"::ivyFile.getPath::"-retrieve"::Nil) 

      List(
        Command(parameters ::: ((managedLibDir.getPath + "/[artifact]-[revision](-[classifier]).[ext]")::"-sync"::"-types"::"jar"::Nil) : _*),
        Command(parameters ::: ((managedLibDir.getPath + "/[artifact]-[revision](-[classifier]).[ext]")::"-types"::"bundle"::Nil) : _*),
        Command(parameters ::: ((managedLibDir.getPath + "/[artifact]-[revision]-source(-[classifier]).[ext]")::"-types"::"source"::Nil) : _*)
      )
    }
    if (ivyFile.exists){
      def rec(commands : List[Command]) : Either[TaskFailed, AnyRef] = 
        commands match {
          case Nil => Right("OK")
          case c :: rest => {
            c.exec() match {
              case (0, _) => rec(rest)
              case (_, error) => {
                Log.info("Command failed\n" + c)
                Left(TaskFailed(ProjectAndTask(project, this), error))
              }
            }
          }
        }
      rec(commands())
    } else {
      Log.info("Nothing to update")
      Right("OK")
    }
  }

}

