package maker.task

import maker.project.Project
import akka.actor.Actor._
import akka.actor.ActorRef
import akka.routing.{CyclicIterator, Routing}
import akka.actor.{UntypedChannel, Actor}
import maker.utils.{Stopwatch, Log}
import akka.event.EventHandler

case class TaskError(reason : String, exception : Option[Throwable]) {
  override def toString = "Task error, reason: " + reason + exception.map(e => ", exception " + e.getMessage).getOrElse("")
}

case class ProjectAndTask(project : Project, task : Task) {

  private var lastRunTimeMs_ = 0L
  private var lastError_ : Option[TaskError] = None

  def lastRunTimeMs = lastRunTimeMs_
  def lastError = lastError_
  val properDependencies : Set[ProjectAndTask] = project.taskDependencies(task).map(ProjectAndTask(project, _)) ++ project.dependentProjects.flatMap(ProjectAndTask(_, task).allDependencies)
  def allDependencies = properDependencies + this

  def exec(acc : Map[Task, List[AnyRef]]) = {
    val taskAndProject = task + ", for project " + project.name
    Log.debug("Executing task " + taskAndProject)
    val sw = new Stopwatch()
    val taskResult = try {
      task.exec(project, acc.getOrElse(task, Nil)) match {
        case res @ Left(err) =>
          lastError_ = Some(TaskError(err.reason, None))
          res
        case res => res
      }
    } catch {
      case e =>
        Log.info("Error occured when executing task " + taskAndProject)
        lastError_ = Some(TaskError("Internal Exception", Some(e)))
        e.printStackTrace
        Left(TaskFailed(task, e.getMessage))
    }
    val totalTime = sw.ms()
    lastRunTimeMs_ = totalTime
    Log.info("Task %s completed in %dms".format(taskAndProject, totalTime))
    taskResult
  }

  override def toString = "Task[" + project.name + ":" + task + "]"

  def runStats =
    toString + " took " + lastRunTimeMs + "ms"
  
  def allStats = "%s took %d, status %s".format(
    toString, lastRunTimeMs, lastError.map(_.toString).getOrElse("OK"))
}

sealed trait BuildMessage
case class ExecTaskMessage(projectTask : ProjectAndTask, acc : Map[Task, List[AnyRef]]) extends BuildMessage
case class TaskResultMessage(projectTask : ProjectAndTask, result : Either[TaskFailed, AnyRef]) extends BuildMessage
case object StartBuild extends BuildMessage
class Worker() extends Actor{
  def receive = {
    case ExecTaskMessage(projectTask : ProjectAndTask, acc : Map[Task, List[AnyRef]]) => self reply TaskResultMessage(projectTask, projectTask.exec(acc))
  }
}
case class BuildResult(projectAndTasks : Set[ProjectAndTask], res : Either[TaskFailed, AnyRef]) extends BuildMessage {
  def stats = projectAndTasks.map(_.allStats).mkString("\n")
}

class QueueManager(projectTasks : Set[ProjectAndTask], router : ActorRef) extends Actor{

  var accumuland : Map[Task, List[AnyRef]] = Map[Task, List[AnyRef]]()
  var remainingProjectTasks = projectTasks
  var completedProjectTasks : Set[ProjectAndTask] = Set()
  var originalCaller : UntypedChannel = _
  private def execNextLevel{
    Log.debug("Remaining tasks are " + remainingProjectTasks)
    Log.debug("Completed tasks are " + completedProjectTasks)
    val (canBeProcessed, mustWait) = remainingProjectTasks.partition(
      _.properDependencies.filterNot(completedProjectTasks).filter(projectTasks).isEmpty
    )
    Log.debug("Can be processed = " + canBeProcessed)
    Log.debug("must wait " + mustWait)
    remainingProjectTasks = mustWait
    canBeProcessed.foreach(router ! ExecTaskMessage(_, accumuland))
  }
  def receive = {
    case TaskResultMessage(_, Left(taskFailure)) => {
      Log.debug("Task failed " + taskFailure)
      router.stop
      originalCaller ! BuildResult(projectTasks, Left(taskFailure))
    }
    case TaskResultMessage(projectTask, Right(result)) => {
      accumuland = accumuland + (projectTask.task -> (result :: accumuland.getOrElse(projectTask.task, Nil)))
      completedProjectTasks += projectTask
      if (completedProjectTasks  == projectTasks)
        originalCaller ! BuildResult(projectTasks, Right("OK"))
      else {
        remainingProjectTasks = remainingProjectTasks.filterNot(_ == projectTask)
        if (router.isRunning)
          execNextLevel
      }
    }
    case StartBuild => {
      originalCaller = self.channel
      execNextLevel
    }
  }
}

object QueueManager{
  def apply(projects : Set[Project], task : Task) = {
    Log.info("About to do " + task + " for projects " + projects.toList.mkString(","))
    val projectTasks = projects.flatMap{p => p.allTaskDependencies(task).map(ProjectAndTask(p, _))}
    implicit val timeout = Timeout(1000000)
    val sw = Stopwatch()
    val nWorkers = (Runtime.getRuntime.availableProcessors / 2) max 1
    Log.info("Running with " + nWorkers + " workers")
    val workers = (1 to nWorkers).map{i => actorOf(new Worker()).start}
    val router = Routing.loadBalancerActor(CyclicIterator(workers)).start()
    val qm = actorOf(new QueueManager(projectTasks, router)).start 
    val future = qm ? StartBuild
    val result = future.get.asInstanceOf[BuildResult]
    Log.info("Stats: \n" + projectTasks.map(_.runStats).mkString("\n"))
    qm.stop
    workers.foreach(_.stop)
    router.stop
    EventHandler.shutdown()
    Log.info("Completed, took" + sw)
    result
  }
}
