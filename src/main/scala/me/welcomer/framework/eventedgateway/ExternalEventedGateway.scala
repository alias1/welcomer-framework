/*  Copyright 2015 White Label Personal Clouds Pty Ltd
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */
package me.welcomer.framework.eventedgateway

import scala.concurrent.ExecutionContext
import scala.concurrent.Future
import scala.concurrent.duration._
import scala.util.control.NonFatal

import java.text.SimpleDateFormat
import java.util.concurrent.TimeUnit

import akka.actor.ActorPath
import akka.actor.ActorRef
import akka.actor.Props
import akka.io.IO
import akka.pattern.AskTimeoutException
import akka.util.Timeout

import play.api.libs.json._

import me.welcomer.framework.Settings
import me.welcomer.framework.actors.WelcomerFrameworkActor
import me.welcomer.framework.pico.EventedEvent
import me.welcomer.framework.pico.EventedFailure
import me.welcomer.framework.pico.EventedFunction
import me.welcomer.framework.pico.EventedModule
import me.welcomer.framework.pico.EventedResult
import me.welcomer.framework.pico.EventedSuccess
import me.welcomer.framework.pico.PermissionDenied

import spray.can.Http
import spray.http._
import spray.http.MediaTypes._
import spray.httpx.PlayJsonSupport
import spray.routing._
import spray.util.LoggingContext

private[framework] object ExternalEventedGateway {
  /**
   * Create Props for an actor of this type.
   * @param settings Framework settings
   * @param eventedGatewayPath Path to the eventGateway actor
   * @return a Props for creating this actor, which can then be further configured
   *         (e.g. calling `.withDispatcher()` on it)
   */
  def props(
    settings: Settings,
    eventedGatewayPath: ActorPath): Props = {
    Props(classOf[ExternalEventGateway], settings, eventedGatewayPath)
  }
}

private[framework] class ExternalEventGateway(
  protected val settings: Settings,
  eventedGatewayPath: ActorPath) extends WelcomerFrameworkActor with ExternalEventedEventService {

  import akka.pattern.ask
  //  import context._
  //  implicit val rSettings = RoutingSettings.default(context) // This is needed if you `import context._`, otherwise things break

  implicit val timeout = Timeout(5.seconds)

  def actorRefFactory = context

  def overlord = context.parent
  def eventedGateway = context.actorSelection(eventedGatewayPath)

  var httpListener: Future[ActorRef] = _

  override def insidePreStart(implicit ec: ExecutionContext): Unit = {
    // Start a new HTTP server with self as the handler
    val r = IO(Http)(context.system) ? Http.Bind(
      self,
      interface = settings.ExternalEventGateway.Bind.interface,
      port = settings.ExternalEventGateway.Bind.port)

    httpListener = r map {
      case listenerRef: ActorRef => listenerRef
    }
  }

  override def insidePostStop(implicit ec: ExecutionContext): Unit = {
    httpListener map { _ ? Http.Unbind }
  }

  def receive = runRoute(routes)
}

// TODO: Refactor this into a proper cake pattern?
trait ExternalEventedEventService
  extends HttpService
  with PlayJsonSupport
  with CORSSupport { this: ExternalEventGateway =>

  import akka.pattern.ask

  implicit def executionContext = actorRefFactory.dispatcher

  implicit def exceptionHandler(implicit log: LoggingContext) =
    ExceptionHandler {
      case e: AskTimeoutException =>
        requestUri { uri =>
          log.error(e, "AskTimeoutException: uri={}", uri)

          complete(
            StatusCodes.GatewayTimeout,
            Json.obj("errorType" -> "AskTimeoutException"))
        }
      case NonFatal(e) => {
        requestUri { uri =>
          log.error(e, "InternalServerError: uri={}", uri)

          complete(
            StatusCodes.InternalServerError,
            Json.obj("errorType" -> "InternalServerError"))
        }
      }
    }

  val v1 = pathPrefix("v1")
  val event = pathPrefix("event")
  val func = pathPrefix("func")

  def TODO(msg: String) = failWith(new RuntimeException(s"TODO: $msg"))

  val routes =
    cors {
      v1 {
        event {
          path(Segment) { eci =>
            post {
              entity(as[EventedEvent]) { event =>
                raiseEvent(event.withEntityId(eci))
              }
            } ~
              get {
                parameterMap { params =>
                  raiseEvent(eci, params)
                }
              }
          }
        } ~
          func {
            pathPrefix(Segment) { moduleId =>
              path(Segment) { funcName =>
                get {
                  headerValueByName("Authorization") { eci =>
                    parameterMap { params =>
                      val call = callFunction(eci, moduleId, funcName, params)
                      onSuccess(call) {
                        handleEventedResult(_)
                      }
                    }
                  }
                } ~
                  post {
                    headerValueByName("Authorization") { eci =>
                      // TODO: Make a proper case class for this?
                      entity(as[JsObject]) { json =>
                        val config = (json \ "config").asOpt[JsObject].getOrElse(Json.obj())
                        val args = (json \ "args").asOpt[JsObject].getOrElse(Json.obj())
                        val call = callFunction(eci, moduleId, funcName, args, config)
                        onSuccess(call) {
                          handleEventedResult(_)
                        }
                      }
                    }
                  }
              }
            }
          }
      } ~
        //      path("fail") {
        //        get {
        //          failWith(new RuntimeException("testing exception failureness"))
        //        }
        //      } ~
        path("") {
          get {
            complete {
              Json.arr(
                Json.obj("link" -> "POST /v1/event/:eci"),
                Json.obj("link" -> "GET /v1/event/:eci?_domain=myDomain&_type=myType&attrKeyN=attrValueN"),
                Json.obj("link" -> "POST /v1/func/:moduleId/:funcName"),
                Json.obj("link" -> "GET /v1/func/:moduleId/:funcName?paramKeyN=paramValueN"))
            }
          }
        }
    }

  def raiseEvent(event: EventedEvent): Route = { ctx =>
    log.info("[raiseEvent] event={}", event)

    eventedGateway ! event

    ctx.complete(Json.obj("msg" -> "Event raised asynchronously"))
  }

  def raiseEvent(eci: String, params: Map[String, String]): Route = {
    val eventDomain = params.get("_domain")
    val eventType = params.get("_type")
    val timestamp = params.get("_timestamp")
    val attr = params - ("_domain", "_type", "_timestamp")

    if (eventDomain.isEmpty | eventType.isEmpty) {
      val errorJson = Json.obj(
        "type" -> "error",
        "desc" -> "Event _domain and _type are required.")

      complete(StatusCodes.BadRequest, errorJson)
    } else {
      val event = EventedEvent(
        eventDomain.get,
        eventType.get,
        timestamp = timestamp map { new SimpleDateFormat().parse(_) },
        attributes = Json.toJson(attr).as[JsObject],
        entityId = Some(eci))

      raiseEvent(event)
    }
  }

  def callFunction(
    eci: String,
    moduleId: String,
    funcName: String,
    params: Map[String, String]): Future[EventedResult[_]] = {
    val moduleConfig = Json.obj()
    val funcParams = Json.toJson(params).as[JsObject]

    callFunction(eci, moduleId, funcName, funcParams, moduleConfig)
  }

  def callFunction(
    eci: String,
    moduleId: String,
    funcName: String,
    params: JsObject = Json.obj(),
    moduleConfig: JsObject = Json.obj()): Future[EventedResult[_]] = {
    val timeoutDuration = (moduleConfig \ "timeout").asOpt[Int] map { timeout =>
      val confTimeout = FiniteDuration(timeout, TimeUnit.MILLISECONDS)
      val maxTimeout = settings.ExternalEventGateway.EventedFunction.maxTimeout

      (confTimeout < maxTimeout) match {
        case true => confTimeout
        case false => {
          log.debug("[callFunction] confTimeout > maxTimeout, using maxTimeout={}", maxTimeout)
          maxTimeout
        }
      }
    } getOrElse { settings.ExternalEventGateway.EventedFunction.defaultTimeout }

    val m = EventedModule(moduleId, moduleConfig, Some(eci), timeoutDuration)
    val f = EventedFunction(m, funcName, params)

    implicit val timeout = Timeout(timeoutDuration)
    (eventedGateway ? EventedGateway.SenderAsReplyTo(f)).mapTo[EventedResult[_]]
  }

  def handleEventedResult(result: EventedResult[_]): Route = {
    log.debug("[ExternalEventedEventService] Function call result: {}", result)

    result match {
      case EventedSuccess(s) => {
        s match {
          case s: JsObject => complete(s)
          case s: JsArray  => complete(s)
          case s: String   => complete(s)
          case _ => {
            log.error("[ExternalEventedEventService] Unhandled EventedResult: {}", s)

            failWith(new Error(s"Unhandled EventedResult: $s"))
          }
        }
      }
      case EventedFailure(errors) => {
        log.error("[ExternalEventedEventService] Failure: {}", errors);

        val errorsArr = errors.foldLeft(Json.arr()) { _ :+ _.asJson }
        val errorJson = Json.obj(
          "type" -> "error",
          "errors" -> errorsArr)

        if (errors.exists(_.isInstanceOf[PermissionDenied])) {
          complete(StatusCodes.Unauthorized, errorJson)
        } else {
          complete(StatusCodes.BadRequest, errorJson)
        }
      }
    }
  }
}
