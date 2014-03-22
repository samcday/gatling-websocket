package com.giltgroupe.util.gatling.websocket

import java.io.IOException

import io.gatling.core.action.{ Action, BaseActor }
import io.gatling.core.action.builder.ActionBuilder
import io.gatling.core.action.system
import io.gatling.core.config.GatlingConfiguration._
import io.gatling.core.result.message.{ KO, OK, Status}
import io.gatling.core.result.writer.DataWriter
import io.gatling.core.session._
import io.gatling.core.validation.{ FailureWrapper, SuccessWrapper, ValidationList }
import io.gatling.core.util.TimeHelper.nowMillis
import io.gatling.http.ahc.{ConnectionPoolKeyStrategy, HttpClient}
import io.gatling.core.util.StringHelper.eol
import io.gatling.http.config.HttpProtocol
import io.gatling.http.cookie.CookieHandling
import io.gatling.http.referer.RefererHandling
import com.ning.http.client.{ Realm, RequestBuilder }
import com.ning.http.client.websocket.{
  WebSocket, WebSocketListener, WebSocketTextListener, WebSocketUpgradeHandler }

import akka.actor.{ ActorRef, Props }
import com.typesafe.scalalogging.slf4j.Logging
import scala.Some
import io.gatling.core.session.Session
import io.gatling.core.validation.Validation
import io.gatling.core.config.ProtocolRegistry
import io.gatling.http.util.HttpHelper

object Predef {
  /**
   * References a web socket that can have actions performed on it.
   *
   * @param attributeName The name of the session attribute that stores the socket
   */
  def websocket(attributeName: Expression[String]) = new WebSocketBaseBuilder(attributeName)

  /** The default AsyncHttpClient WebSocket client. */
  implicit object WebSocketClient extends WebSocketClient with Logging {
    def open(
        actionBuilder: OpenWebSocketActionBuilder,
        session: Session,
        protocolConfiguration: HttpProtocol,
        listener: WebSocketListener) {
      val request = actionBuilder.getAHCRequestBuilder(session, protocolConfiguration).map(_.build)
      request.map (request => {
        HttpClient.newClient(session).prepareRequest(request).execute(new WebSocketUpgradeHandler.Builder().addWebSocketListener(listener).build())
      })
    }
  }

  /** The default Gatling request logger. */
  implicit object RequestLogger extends  {
    def logRequest(
        session: Session,
        actionName: String,
        requestStatus: Status,
        started: Long,
        ended: Long,
        errorMessage: Option[String]) {
      DataWriter.tell(
        session.scenarioName, session.userId, actionName, started, ended, ended, ended,
        requestStatus, errorMessage)
    }
  }
}

trait WebSocketClient {
  @throws(classOf[IOException])
  def open(
    actionBuilder: OpenWebSocketActionBuilder,
    session: Session,
    protocolConfiguration: HttpProtocol,
    listener: WebSocketListener)
}

trait RequestLogger {
  def logRequest(
    session: Session,
    actionName: String,
    requestStatus: Status,
    started: Long,
    ended: Long,
    errorMessage: Option[String] = None)
}

class WebSocketBaseBuilder(val attributeName: Expression[String]) {
  /**
   * Opens a web socket and stores it in the session.
   *
   * @param fUrl The socket URL
   * @param actionName The action name in the log
   */
  def open(fUrl: Expression[String], actionName: Expression[String])
          (implicit webSocketClient: WebSocketClient, requestLogger: RequestLogger) =
    new OpenWebSocketActionBuilder(
      attributeName,
      OpenWebSocketAttributes(actionName, fUrl, Map.empty, None),
      webSocketClient,
      requestLogger)

  /**
   * Sends a message on the given socket.
   *
   * @param fMessage The message
   * @param actionName The action name in the log
   */
  def sendMessage(
      fMessage: Expression[String],
      actionName: Expression[String]) =
    new SendWebSocketMessageActionBuilder(attributeName, actionName, fMessage)

  /**
   * Closes a web socket.
   *
   * @param actionName The action name in the log
   */
  def close(actionName: Expression[String]) = new CloseWebSocketActionBuilder(attributeName, actionName)
}

case class OpenWebSocketAttributes(
  actionName: Expression[String],
  fUrl: Expression[String],
  headers: Map[String, Expression[String]],
  realm: Option[Expression[Realm]])

class OpenWebSocketActionBuilder(
    val attributeName: Expression[String],
    val owsAttributes: OpenWebSocketAttributes,
    val webSocketClient: WebSocketClient,
    val requestLogger: RequestLogger) extends ActionBuilder {
  private def newInstance(newOwsAttributes: OpenWebSocketAttributes) = {
    new OpenWebSocketActionBuilder(
      attributeName, newOwsAttributes, webSocketClient, requestLogger)
  }

  /**
   * Adds a header to the request
   *
   * @param header the header to add, eg: ("Content-Type", "application/json")
   */
  def header(header: (String, Expression[String])): OpenWebSocketActionBuilder = {
    newInstance(owsAttributes.copy(
      headers = owsAttributes.headers + (header._1 -> header._2)))
  }

  /**
   * Adds several headers to the request at the same time
   *
   * @param givenHeaders a scala map containing the headers to add
   */
  def headers(givenHeaders: Map[String, String]): OpenWebSocketActionBuilder = {
    newInstance(owsAttributes.copy(
      headers = owsAttributes.headers ++ givenHeaders.mapValues(EL.compile[String])))
  }

  /**
   * Adds BASIC authentication to the request
   *
   * @param username the username needed
   * @param password the password needed
   */
  def basicAuth(
      username: Expression[String],
      password: Expression[String]): OpenWebSocketActionBuilder = {
    newInstance(owsAttributes.copy(realm = Some(HttpHelper.buildRealm(username, password))))
  }

  private[websocket] def getAHCRequestBuilder(
      session: Session,
      protocol: HttpProtocol): Validation[RequestBuilder] = {
    val requestBuilder = new RequestBuilder("GET", configuration.http.ahc.useRawUrl)
    if (!protocol.shareConnections) requestBuilder.setConnectionPoolKeyStrategy(new ConnectionPoolKeyStrategy(session))

    // baseUrl implementation
    def makeAbsolute(url: String): Validation[String] =
      if (url.startsWith("ws"))
        url.success
      else
        protocol.baseURL.map(baseURL => (baseURL + url).success).getOrElse(s"No protocol.baseURL defined but provided url is relative : $url".failure)

    def configureCookiesAndProxy(url: String): Validation[RequestBuilder] = {
      (if (url.startsWith("wss"))
        protocol.securedProxy
      else
        protocol.proxy).map(requestBuilder.setProxyServer)

      CookieHandling.getStoredCookies(session, url).foreach(requestBuilder.addCookie)

      requestBuilder.setUrl(url).success
    }

    def configureHeaders(requestBuilder: RequestBuilder): Validation[RequestBuilder] = {
      val baseHeaders = protocol.baseHeaders

      val resolvedHeaders = owsAttributes.headers.map {
        case (key, value) =>
          for {
            resolvedValue <- value(session)
          } yield key -> resolvedValue
      }
        .toList
        .sequence

      resolvedHeaders.map { headers =>
        val newHeaders = RefererHandling.addStoredRefererHeader(protocol.baseHeaders ++ headers, session, protocol)
        newHeaders.foreach { case (headerName, headerValue) => requestBuilder.addHeader(headerName, headerValue) }
        requestBuilder
      }
    }

    def configureRealm(requestBuilder: RequestBuilder): Validation[RequestBuilder] = {

      val realm = owsAttributes.realm.orElse(protocol.basicAuth)

      realm match {
        case Some(realm) => realm(session).map(requestBuilder.setRealm)
        case None => requestBuilder.success
      }
    }

    owsAttributes.fUrl(session)
      .flatMap(makeAbsolute)
      .flatMap(configureCookiesAndProxy)
      .flatMap(configureHeaders)
      .flatMap(configureRealm)
  }

  def withNext(next: ActorRef): ActionBuilder = newInstance(owsAttributes)

  def build(next: ActorRef): ActorRef =
    system.actorOf(Props(new OpenWebSocketAction(
      attributeName,
      this,
      webSocketClient,
      requestLogger,
      next,
      ProtocolRegistry.registry.getProtocol(HttpProtocol.default))))
}

class SendWebSocketMessageActionBuilder(
    val attributeName: Expression[String],
    val actionName: Expression[String],
    val fMessage: Expression[String]) extends ActionBuilder {
  def withNext(next: ActorRef): ActionBuilder =
    new SendWebSocketMessageActionBuilder(attributeName, actionName, fMessage)

  def build(next: ActorRef): ActorRef =
    system.actorOf(Props(new SendWebSocketMessageAction(
      attributeName, actionName, fMessage, next)))
}

class CloseWebSocketActionBuilder(
    val attributeName: Expression[String],
    val actionName: Expression[String]) extends ActionBuilder {
  def withNext(next: ActorRef): ActionBuilder =
    new CloseWebSocketActionBuilder(attributeName, actionName)

  def build(next: ActorRef): ActorRef =
    system.actorOf(Props(new CloseWebSocketAction(attributeName, actionName, next)))
}

private[websocket] class OpenWebSocketAction(
    attributeName: Expression[String],
    actionBuilder: OpenWebSocketActionBuilder,
    webSocketClient: WebSocketClient,
    requestLogger: RequestLogger,
    val next: ActorRef,
    protocol: HttpProtocol
) extends Action() {
  def execute(session: Session) {
    for {
      resolvedActionName <- actionBuilder.owsAttributes.actionName(session)
      resolvedAttributeName <- attributeName(session)
    } yield {
      logger.info("Opening websocket '" + attributeName + "': Scenario '" +
        session.scenarioName + "', UserId #" + session.userId)

      val actor = context.actorOf(Props(new WebSocketActor(resolvedAttributeName, requestLogger)))

      val started = nowMillis
      try {
        webSocketClient.open(actionBuilder, session, protocol, new WebSocketTextListener {
          var opened = false

          def onOpen(webSocket: WebSocket) {
            opened = true
            actor ! OnOpen(resolvedActionName, webSocket, started, nowMillis, next, session)
          }

          def onMessage(message: String) {
            actor ! OnMessage(message)
          }

          def onFragment(fragment: String, last: Boolean) {
          }

          def onClose(webSocket: WebSocket) {
            if (opened) {
              actor ! OnClose
            } else {
              actor ! OnFailedOpen(resolvedActionName, "closed", started, nowMillis, next, session)
            }
          }

          def onError(t: Throwable) {
            if (opened) {
              actor ! OnError(t)
            } else {
              actor ! OnFailedOpen(resolvedActionName, t.getMessage, started, nowMillis, next, session)
            }
          }
        })
      } catch {
        case e: IOException =>
          actor ! OnFailedOpen(resolvedActionName, e.getMessage, started, nowMillis, next, session)
      }
    }
  }
}

private[websocket] class SendWebSocketMessageAction(
    attributeName: Expression[String],
    actionName: Expression[String],
    fMessage: Expression[String],
    val next: ActorRef) extends Action() {
  def execute(session: Session) {
    for {
      resolvedActionName <- actionName(session)
      resolvedAttributeName <- attributeName(session)
      resolvedFMessage <- fMessage(session)
    } yield {
      session(resolvedAttributeName).asOption[(ActorRef, _)] foreach {
        _._1 ! SendMessage(resolvedActionName, resolvedFMessage, next, session)
      }
    }
  }
}

private[websocket] class CloseWebSocketAction(
    attributeName: Expression[String],
    actionName: Expression[String],
    val next: ActorRef) extends Action() {
  def execute(session: Session) {
    for {
      resolvedActionName <- actionName(session)
      resolvedAttributeName <- attributeName(session)
    } yield {
      logger.info("Closing websocket '" + attributeName + "': Scenario '" +
        session.scenarioName + "', UserId #" + session.userId)
      session(resolvedAttributeName).asOption[(ActorRef, _)] foreach {
        _._1 ! Close(resolvedActionName, next, session)
      }
    }
  }
}

private[websocket] class WebSocketActor(
    val attributeName: String,
    requestLogger: RequestLogger) extends BaseActor {
  var webSocket: Option[WebSocket] = None
  var errorMessage: Option[String] = None

  def receive = {
    case OnOpen(actionName, openedWebSocket, started, ended, next, session) =>
      requestLogger.logRequest(session, actionName, OK, started, ended)
      webSocket = Some(openedWebSocket)
      next ! session.set(attributeName, (self, openedWebSocket))

    case OnFailedOpen(actionName, message, started, ended, next, session) =>
      logger.warn("Websocket '" + attributeName + "' failed to open: " + message)
      requestLogger.logRequest(session, actionName, KO, started, ended, Some(message))
      next ! session.markAsFailed
      context.stop(self)

    case OnMessage(message) =>
      logger.debug("Received message on websocket '" + attributeName + "':" + eol + message)

    case OnClose =>
      errorMessage = Some("Websocket '" + attributeName + "' was unexpectedly closed")
      logger.warn(errorMessage.get)

    case OnError(t) =>
      errorMessage = Some("Websocket '" + attributeName + "' gave an error: '" + t.getMessage + "'")
      logger.warn(errorMessage.get)

    case SendMessage(actionName, message, next, session) =>
      if (!handleEarlierError(actionName, next, session)) {
        val started = nowMillis
        webSocket.foreach(_.sendTextMessage(message))
        requestLogger.logRequest(session, actionName, OK, started, nowMillis)
        next ! session
      }

    case Close(actionName, next, session) =>
      if (!handleEarlierError(actionName, next, session)) {
        val started = nowMillis
        webSocket.foreach(_.close)
        requestLogger.logRequest(session, actionName, OK, started, nowMillis)
        next ! session
        context.stop(self)
      }
  }

  def handleEarlierError(actionName: String, next: ActorRef, session: Session) = {
    if (errorMessage.isDefined) {
      val now = nowMillis
      requestLogger.logRequest(session, actionName, KO, now, now, errorMessage)
      errorMessage = None
      next ! session.markAsFailed
      context.stop(self)
      true
    } else {
      false
    }
  }
}

private[websocket] case class OnOpen(
  actionName: String,
  webSocket: WebSocket,
  started: Long,
  ended: Long,
  next: ActorRef,
  session: Session)
private[websocket] case class OnFailedOpen(
  actionName: String,
  message: String,
  started: Long,
  ended: Long,
  next: ActorRef,
  session: Session)
private[websocket] case class OnMessage(message: String)
private[websocket] case object OnClose
private[websocket] case class OnError(t: Throwable)

private[websocket] case class SendMessage(
  actionName: String, message: String, next: ActorRef, session: Session)
private[websocket] case class Close(actionName: String, next: ActorRef, session: Session)
