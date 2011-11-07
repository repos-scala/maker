import maker.project.Project
import java.io.File

System.setProperty("scala.usejavacp", "false")
def standardProject(name : String) = Project(
  name, 
  new File("./" + name), 
  List(new File(name + "/src")), 
  List(new File("./lib")),
  new File(name + "/out"),
  new File(name + "/package")
)

val utils = standardProject("utils")
val maker = standardProject("maker")
val plugin = standardProject("plugin")

import utils._
import scala.tools.nsc.{Settings, Global}
import scala.tools.util._