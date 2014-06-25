package mesosphere.marathon.upgrade

import akka.actor._
import org.apache.mesos.SchedulerDriver
import mesosphere.marathon.SchedulerActions
import mesosphere.marathon.tasks.{ TaskQueue, TaskTracker }
import akka.event.EventStream
import mesosphere.marathon.event.DeploymentSuccess
import scala.concurrent.{ Promise, Future }
import mesosphere.marathon.api.v1.AppDefinition
import mesosphere.marathon.Protos.MarathonTask

class AppRestartActor() extends Actor {
  def receive = {
    case _ =>
  }
}

class DeploymentActor(
    parent: ActorRef,
    receiver: ActorRef,
    driver: SchedulerDriver,
    scheduler: SchedulerActions,
    plan: DeploymentPlan,
    taskTracker: TaskTracker,
    taskQueue: TaskQueue,
    eventBus: EventStream) extends Actor {

  import context.dispatcher

  import DeploymentActor._

  val steps = plan.steps.iterator

  override def preStart(): Unit = {
    self ! NextStep
  }

  def receive = {
    case NextStep if steps.hasNext => performStep(steps.next())
    case NextStep =>
      // no more steps, we're done
      eventBus.publish(DeploymentSuccess(plan.target.id))
      context.stop(self)
  }

  def performStep(step: DeploymentStep): Future[Unit] = {
    val futures = step.actions.map {
      case StartApplication(app, scaleTo) => startApp(app, scaleTo)
      case ScaleApplication(app, scaleTo) => scaleApp(app, scaleTo)
      case StopApplication(app)           => stopApp(app)
      case KillAllOldTasksOf(app) =>
        val runningTasks = taskTracker.fetchApp(app.id).tasks.toSeq
        killTasks(runningTasks.filterNot(_.getVersion == app.version.toString))

      case RestartApplication(app, scaleOldTo, scaleNewTo) => restartApp(app, scaleOldTo, scaleNewTo)
    }

    Future.sequence(futures).map(_ => ())
  }

  def startApp(app: AppDefinition, scaleTo: Int): Future[Unit] = {
    val promise = Promise[Unit]()
    context.actorOf(Props(classOf[AppStartActor], driver, scheduler, eventBus, app, scaleTo, promise))
    promise.future
  }

  def scaleApp(app: AppDefinition, scaleTo: Int): Future[Unit] = {
    val runningTasks = taskTracker.fetchApp(app.id).tasks
    if (scaleTo == runningTasks.size) {
      Future.successful(())
    }
    else if (scaleTo > runningTasks.size) {
      val promise = Promise[Boolean]()
      context.actorOf(Props(new TaskStartActor(taskQueue, eventBus, app, scaleTo - runningTasks.size, promise)))
      promise.future.map(_ => ())
    }
    else {
      killTasks(runningTasks.toSeq.sortBy(_.getStartedAt).drop(scaleTo))
    }
  }

  def killTasks(tasks: Seq[MarathonTask]): Future[Unit] = {
    val promise = Promise[Boolean]()
    context.actorOf(Props(new TaskKillActor(driver, eventBus, tasks.toSet, promise)))
    promise.future.map(_ => ())
  }

  def stopApp(app: AppDefinition): Future[Unit] = {
    val promise = Promise[Unit]()
    context.actorOf(Props(new AppStopActor(driver, scheduler, taskTracker, eventBus, app, promise)))
    promise.future
  }

  def restartApp(app: AppDefinition, scaleOldTo: Int, scaleNewTo: Int): Future[Unit] = {
    val promise = Promise[Unit]()
    val runningTasks = taskTracker.fetchApp(app.id).tasks.toSeq
    val tasksToKill = runningTasks.filterNot(_.getVersion == app.version.toString)

    promise.future
  }

}

object DeploymentActor {
  case object NextStep
  case object Failed
}
