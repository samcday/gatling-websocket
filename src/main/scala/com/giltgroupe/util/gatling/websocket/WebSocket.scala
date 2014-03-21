package com.giltgroupe.util.gatling.websocket

import java.io.IOException

import io.gatling.core.action.{ Action, BaseActor }
import io.gatling.core.action.builder.ActionBuilder
import io.gatling.core.action.system
import io.gatling.core.config.GatlingConfiguration._
import io.gatling.core.result.message.{ KO, OK, Status}
import io.gatling.core.result.writer.DataWriter
import io.gatling.core.session._
import io.gatling.core.util.TimeHelper.nowMillis
import io.gatling.http.ahc.{HttpClient}
import io.gatling.core.util.StringHelper.eol
import io.gatling.http.config.HttpProtocol
import io.gatling.http.cookie.CookieHandling
import io.gatling.http.referer.RefererHandling
import com.ning.http.client.{ FluentCaseInsensitiveStringsMap, Realm, RequestBuilder }
import com.ning.http.client.Realm.AuthScheme
import com.ning.http.client.websocket.{
  WebSocket, WebSocketListener, WebSocketTextListener, WebSocketUpgradeHandler }

import akka.actor.{ ActorRef, Props }
import com.typesafe.scalalogging.slf4j.Logging
import com.giltgroupe.util.gatling.websocket.Close
import scala.Some
import com.giltgroupe.util.gatling.websocket.OpenWebSocketAttributes
import com.giltgroupe.util.gatling.websocket.OnError
import com.giltgroupe.util.gatling.websocket.SendMessage
import com.giltgroupe.util.gatling.websocket.OnFailedOpen
import io.gatling.core.session.Session
import com.giltgroupe.util.gatling.websocket.OnOpen
import com.giltgroupe.util.gatling.websocket.OnMessage
import io.gatling.core.validation.Validation

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
      val request = actionBuilder.getAHCRequestBuilder(session, protocolConfiguration).build
      HttpClient.newClient(session).prepareRequest(request).execute(
        new WebSocketUpgradeHandler.Builder().addWebSocketListener(listener).build())
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
      DataWriter.logRequest(
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
  def open(fUrl: Expression[String], actionName: Expression[String] = _ => attributeName)
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
      actionName: Expression[String] = _ => attributeName) =
    new SendWebSocketMessageActionBuilder(attributeName, actionName, fMessage)

  /**
   * Closes a web socket.
   *
   * @param actionName The action name in the log
   */
  def close(actionName: Expression[String] = _ => attributeName) =
    new CloseWebSocketActionBuilder(attributeName, actionName)
}

case class OpenWebSocketAttributes(
  actionName: Expression[String],
  fUrl: Expression[String],
  headers: Map[String, Expression[String]],
  realm: Option[Session => Realm])

class OpenWebSocketActionBuilder(
    val attributeName: Expression[String],
    val owsAttributes: OpenWebSocketAttributes,
    val webSocketClient: WebSocketClient,
    val requestLogger: RequestLogger,
    val next: ActorRef = null) extends ActionBuilder {
  private def newInstance(newOwsAttributes: OpenWebSocketAttributes, newNext: ActorRef = next) = {
    new OpenWebSocketActionBuilder(
      attributeName, newOwsAttributes, webSocketClient, requestLogger, newNext)
  }

  /**
   * Adds a header to the request
   *
   * @param header the header to add, eg: ("Content-Type", "application/json")
   */
  def header(header: (String, String)): OpenWebSocketActionBuilder = {
    newInstance(owsAttributes.copy(
      headers = owsAttributes.headers + (header._1 -> parseEL(header._2))))
  }

  /**
   * Adds several headers to the request at the same time
   *
   * @param givenHeaders a scala map containing the headers to add
   */
  def headers(givenHeaders: Map[String, String]): OpenWebSocketActionBuilder = {
    newInstance(owsAttributes.copy(
      headers = owsAttributes.headers ++ givenHeaders.mapValues(parseEL)))
  }

  /**
   * Adds BASIC authentication to the request
   *
   * @param username the username needed
   * @param password the password needed
   */
  def basicAuth(
      username: EvaluatableString,
      password: EvaluatableString): OpenWebSocketActionBuilder = {
    val buildRealm = {session: Session =>
      new Realm.RealmBuilder().
        setPrincipal(username(session)).
        setPassword(password(session)).
        setUsePreemptiveAuth(true).
        setScheme(AuthScheme.BASIC).
        build
    }
    newInstance(owsAttributes.copy(realm = Some(buildRealm)))
  }

  private[websocket] def getAHCRequestBuilder(
      session: Session,
      protocolConfiguration: HttpProtocolConfiguration): RequestBuilder = {
    val requestBuilder = new RequestBuilder("GET", configuration.http.useRawUrl)

    val isWss = configureURLAndCookies(requestBuilder, session, protocolConfiguration)
    configureProxy(requestBuilder, session, isWss, protocolConfiguration)
    configureHeaders(requestBuilder, owsAttributes.headers, session, protocolConfiguration)
    configureRealm(requestBuilder, owsAttributes.realm, session)

    requestBuilder
  }

  private[this] def configureURLAndCookies(
      requestBuilder: RequestBuilder,
      session: Session,
      protocolConfiguration: HttpProtocolConfiguration) = {
    val providedUrl = owsAttributes.fUrl(session)

    // baseUrl implementation
    val resolvedUrl = if (providedUrl.startsWith("ws"))
      providedUrl
    else
      protocolConfiguration.baseURL().getOrElse(throw new IllegalArgumentException(
        "No protocolConfiguration.baseURL defined but provided url is relative : " + providedUrl
      )) + providedUrl

    requestBuilder.setUrl(resolvedUrl)

    for (cookie <- CookieHandling.getStoredCookies(session, resolvedUrl))
      requestBuilder.addCookie(cookie)

    resolvedUrl.startsWith("wss")
  }

  private[this] def configureProxy(
      requestBuilder: RequestBuilder,
      session: Session,
      isWss: Boolean,
      protocolConfiguration: HttpProtocolConfiguration) = {
    (if (isWss)
      protocolConfiguration.securedProxy
    else
      protocolConfiguration.proxy).map(requestBuilder.setProxyServer)
  }

  private[this] def configureHeaders(
      requestBuilder: RequestBuilder,
      headers: Map[String, EvaluatableString],
      session: Session,
      protocolConfiguration: HttpProtocolConfiguration) {
    requestBuilder.setHeaders(new FluentCaseInsensitiveStringsMap)

    val baseHeaders = protocolConfiguration.baseHeaders
    val resolvedRequestHeaders = headers.map {
      case (headerName, headerValue) => headerName -> headerValue(session)
    }

    val newHeaders = RefererHandling.addStoredRefererHeader(
      baseHeaders ++ resolvedRequestHeaders, session, protocolConfiguration
    )

    newHeaders.foreach {
      case (headerName, headerValue) => requestBuilder.addHeader(headerName, headerValue)
    }
  }

  private[this] def configureRealm(
      requestBuilder: RequestBuilder,
      realm: Option[Session => Realm],
      session: Session) {
    realm.map { realm => requestBuilder.setRealm(realm(session)) }
  }

  def withNext(next: ActorRef): ActionBuilder = newInstance(owsAttributes, next)

  def build(registry: ProtocolConfigurationRegistry): ActorRef =
    system.actorOf(Props(new OpenWebSocketAction(
      attributeName,
      this,
      webSocketClient,
      requestLogger,
      next,
      registry.getProtocolConfiguration(HttpProtocolConfiguration.DEFAULT_HTTP_PROTOCOL_CONFIG))))
}

class SendWebSocketMessageActionBuilder(
    val attributeName: Expression[String],
    val actionName: Expression[String],
    val fMessage: Expression[String],
    val next: ActorRef = null) extends ActionBuilder {
  def withNext(next: ActorRef): ActionBuilder =
    new SendWebSocketMessageActionBuilder(attributeName, actionName, fMessage, next)

  def build(registry: ProtocolConfigurationRegistry): ActorRef =
    system.actorOf(Props(new SendWebSocketMessageAction(
      attributeName, actionName, fMessage, next, registry)))
}

class CloseWebSocketActionBuilder(
    val attributeName: Expression[String],
    val actionName: Expression[String],
    val next: ActorRef = null) extends ActionBuilder {
  def withNext(next: ActorRef): ActionBuilder =
    new CloseWebSocketActionBuilder(attributeName, actionName, next)

  def build(registry: ProtocolConfigurationRegistry): ActorRef =
    system.actorOf(Props(new CloseWebSocketAction(attributeName, actionName, next, registry)))
}

private[websocket] abstract class WebSocketAction(
    actionName: Expression[String]) extends Action() with Bypass {
  def resolvedActionName(session: Session): Validation[String] = {
    try {
      actionName(session)
    } catch {
      case e: Exception => error("Action name resolution crashed", e); "no-name"
    }
  }
}

private[websocket] class OpenWebSocketAction(
    attributeName: String,
    actionBuilder: OpenWebSocketActionBuilder,
    webSocketClient: WebSocketClient,
    requestLogger: RequestLogger,
    val next: ActorRef,
    httpConfig: HttpProtocolConfiguration
) extends WebSocketAction(actionBuilder.owsAttributes.actionName) {
  def execute(session: Session) {
    val rActionName = resolvedActionName(session)

    info("Opening websocket '" + attributeName + "': Scenario '" +
      session.scenarioName + "', UserId #" + session.userId)

    val actor = context.actorOf(Props(new WebSocketActor(attributeName, requestLogger)))

    val started = nowMillis
    try {
      webSocketClient.open(actionBuilder, session, httpConfig, new WebSocketTextListener {
        var opened = false

        def onOpen(webSocket: WebSocket) {
          opened = true
          actor ! OnOpen(rActionName, webSocket, started, nowMillis, next, session)
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
            actor ! OnFailedOpen(rActionName, "closed", started, nowMillis, next, session)
          }
        }

        def onError(t: Throwable) {
          if (opened) {
            actor ! OnError(t)
          } else {
            actor ! OnFailedOpen(rActionName, t.getMessage, started, nowMillis, next, session)
          }
        }
      })
    } catch {
      case e: IOException =>
        actor ! OnFailedOpen(rActionName, e.getMessage, started, nowMillis, next, session)
    }
  }
}

private[websocket] class SendWebSocketMessageAction(
    attributeName: String,
    actionName: Expression[String],
    fMessage: Expression[String],
    val next: ActorRef,
    registry: ProtocolConfigurationRegistry) extends WebSocketAction(actionName) {
  def execute(session: Session) {
    session(attributeName).asOption[(ActorRef, _)] foreach {
      _._1 ! SendMessage(resolvedActionName(session), fMessage(session), next, session)
    }
  }
}

private[websocket] class CloseWebSocketAction(
    attributeName: String,
    actionName: Expression[String],
    val next: ActorRef,
    registry: ProtocolConfigurationRegistry) extends WebSocketAction(actionName) {
  def execute(session: Session) {
    val rActionName = resolvedActionName(session)
    info("Closing websocket '" + attributeName + "': Scenario '" +
      session.scenarioName + "', UserId #" + session.userId)
    session(attributeName).asOption[(ActorRef, _)] foreach {
      _._1 ! Close(rActionName, next, session)
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
      warn("Websocket '" + attributeName + "' failed to open: " + message)
      requestLogger.logRequest(session, actionName, KO, started, ended, Some(message))
      next ! session.markAsFailed
      context.stop(self)

    case OnMessage(message) =>
      debug("Received message on websocket '" + attributeName + "':" + eol + message)

    case OnClose =>
      errorMessage = Some("Websocket '" + attributeName + "' was unexpectedly closed")
      warn(errorMessage.get)

    case OnError(t) =>
      errorMessage = Some("Websocket '" + attributeName + "' gave an error: '" + t.getMessage + "'")
      warn(errorMessage.get)

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
