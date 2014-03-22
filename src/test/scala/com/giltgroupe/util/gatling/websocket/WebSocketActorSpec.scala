package com.giltgroupe.util.gatling.websocket

import akka.actor.{Actor, Props}
import akka.testkit.TestActorRef
import io.gatling.core.Predef._
import io.gatling.core.action.system
import io.gatling.core.config.{GatlingConfiguration, ProtocolRegistry}
import io.gatling.core.result.message.{Status, OK, KO}
import io.gatling.core.session.{Expression, Session}
import io.gatling.http.config.HttpProtocol
import com.ning.http.client.websocket.{WebSocket, WebSocketListener}
import java.net.URI
import java.io.IOException
import org.junit.runner.RunWith
import org.mockito.ArgumentMatcher
import org.mockito.Matchers.{eq => isEq}
import org.mockito.Mockito.reset
import org.specs2.mock._
import mockito.MocksCreation
import org.slf4j.LoggerFactory
import org.specs2.mutable.Specification
import org.specs2.runner.JUnitRunner
import org.specs2.specification.{AllExpectations, Scope}
import io.gatling.core.result.message.{KO, OK}

@RunWith(classOf[JUnitRunner])
class WebSocketActorSpec extends Specification with AllExpectations with Mockito {
  step {
    // initialize logging to avoid substitute logger error messages
    LoggerFactory.getLogger(classOf[WebSocketActorSpec])
    // set up configuration to avoid NPEs constructing actors
    GatlingConfiguration.setUp()
    ProtocolRegistry.setUp(protocols = Seq())
    success
  }

  "A WebSocketActor" should {
    "record a successful open and advance" in new scope {
      open(webSocketClient(_.onOpen(mock[WebSocket].smart)))

      Thread.sleep(100)
      there was one(requestLogger).logRequest(any[Session], anyString, isEq(OK), anyLong, anyLong, isEq(null))
      next.underlyingActor.session.map(_.contains("testAttributeName")) mustEqual Some(true)
      next.underlyingActor.session.map(_.status) mustEqual Some(OK)
    }

    "record a failed open and advance" in new scope {
      open(mock[WebSocketClient].open(
        any[OpenWebSocketActionBuilder],
        any[Session],
        any[HttpProtocol],
        any[WebSocketListener]
      ) throws new IOException("testErrorMessage"))

      Thread.sleep(100)
      there was one(requestLogger).logRequest(any[Session], anyString, isEq(KO), anyLong, anyLong, anArgThat(contains("testErrorMessage")))
      next.underlyingActor.session.map(_.contains("testAttributeName")) mustEqual Some(false)
      next.underlyingActor.session.map(_.status) mustEqual Some(KO)
    }

    "record an incomplete open as failed and advance" in new scope {
      open(webSocketClient(_.onError(new IOException("testException"))))

      Thread.sleep(100)
      there was one(requestLogger).logRequest(any[Session], anyString, isEq(KO), anyLong, anyLong, anArgThat(contains("testException")))
      next.underlyingActor.session.map(_.contains("testAttributeName")) mustEqual Some(false)
      next.underlyingActor.session.map(_.status) mustEqual Some(KO)
    }

    "record a close-before-open as failed and advance" in new scope {
      open(webSocketClient(_.onClose(mock[WebSocket].smart)))

      Thread.sleep(100)
      there was one(requestLogger).logRequest(any[Session], anyString, isEq(KO), anyLong, anyLong, any[Option[String]])
      next.underlyingActor.session.map(_.contains("testAttributeName")) mustEqual Some(false)
      next.underlyingActor.session.map(_.status) mustEqual Some(KO)
    }

    "record a successful send and advance" in new scope {
      val webSocket = mock[WebSocket].smart
      openSuccessfully(webSocket)
      Thread.sleep(100)
      sendMessage("testMessage")

      Thread.sleep(100)
      there was one(webSocket).sendTextMessage(isEq("testMessage"))
      there was two(requestLogger).logRequest(any[Session], anyString, isEq(OK), anyLong, anyLong, isEq(null))
      next.underlyingActor.session.map(_.status) mustEqual Some(OK)
    }

    "record a send after an error as failed and advance" in new scope {
      val webSocket = mock[WebSocket].smart
      reportError(webSocket, new IOException("testException"))
      Thread.sleep(100)
      sendMessage("testMessage")

      Thread.sleep(100)
      there was one(requestLogger).logRequest(any[Session], anyString, isEq(KO), anyLong, anyLong, anArgThat(contains("testException")))
      there was no(webSocket).sendTextMessage(anyString)
      next.underlyingActor.session.map(_.status) mustEqual Some(KO)
    }

    "record a send after an unexpected close as failed and advance" in new scope {
      val webSocket = mock[WebSocket].smart
      closeUnexpectedly(webSocket)
      Thread.sleep(100)
      sendMessage("testMessage")
      Thread.sleep(100)

      there was one(requestLogger).logRequest(any[Session], anyString, isEq(KO), anyLong, anyLong, any[Option[String]])
      there was no(webSocket).sendTextMessage(anyString)
      next.underlyingActor.session.map(_.status) mustEqual Some(KO)
    }

    "record a successful close and advance" in new scope {
      val webSocket = mock[WebSocket].smart
      openSuccessfully(webSocket)
      Thread.sleep(100)
      close()
      Thread.sleep(100)

      there was one(webSocket).close()
      there was two(requestLogger).logRequest(any[Session], anyString, isEq(OK), anyLong, anyLong, isEq(null))
      next.underlyingActor.session.map(_.status) mustEqual Some(OK)
    }

    "record a close after an error as failed and advance" in new scope {
      val webSocket = mock[WebSocket].smart
      reportError(webSocket, new IOException("testException"))
      Thread.sleep(100)
      close()
      Thread.sleep(100)

      there was one(requestLogger).logRequest(any[Session], anyString, isEq(KO), anyLong, anyLong, anArgThat(contains("testException")))
      there was no(webSocket).sendTextMessage(anyString)
      next.underlyingActor.session.map(_.status) mustEqual Some(KO)
    }

    "record a close after an unexpected close as failed and advance" in new scope {
      val webSocket = mock[WebSocket].smart
      closeUnexpectedly(webSocket)
      Thread.sleep(100)
      close()
      Thread.sleep(100)

      there was one(requestLogger).logRequest(any[Session], anyString, isEq(KO), anyLong, anyLong, any[Option[String]])
      there was no(webSocket).sendTextMessage(anyString)
      next.underlyingActor.session.map(_.status) mustEqual Some(KO)
    }
  }

  private trait scope extends Scope with MocksCreation {
    val requestLogger = mock[RequestLogger]
    var next: TestActorRef[DummyAction] = _

    def webSocketClient(open: (WebSocketListener) => Unit) = {
      mock[WebSocketClient].open(
        any[OpenWebSocketActionBuilder],
        any[Session],
        any[HttpProtocol],
        any[WebSocketListener]
      ) answers {(params, _) =>
        open(params.asInstanceOf[Array[_]](3).asInstanceOf[WebSocketListener])
      }
    }

    def open(webSocketClient: WebSocketClient) {
      next = TestActorRef[DummyAction](Props(new DummyAction))(system)
      val action = Predef.websocket("testAttributeName")
        .open("ws://dummy/", "testRequestName")(webSocketClient, requestLogger)
        .build(next)

      action ! new Session("test", 0)
    }

    def openSuccessfully(webSocket: WebSocket) {
      open(webSocketClient(_.onOpen(webSocket)))
      reset(requestLogger)
    }

    def reportError(webSocket: WebSocket, t: Throwable) {
      open(webSocketClient{s => s.onOpen(webSocket); s.onError(t)})
      reset(requestLogger)
    }

    def closeUnexpectedly(webSocket: WebSocket) {
      open(webSocketClient{s => s.onOpen(webSocket); s.onClose(webSocket)})
      reset(requestLogger)
    }

    def sendMessage(message: Expression[String]) {
      val session = next.underlyingActor.session.get
      next = TestActorRef[DummyAction](Props(new DummyAction))(system)
      val action = Predef.websocket("testAttributeName")
        .sendMessage(message, "testRequestName")
        .build(next)

      action ! session
    }

    def close() {
      val session = next.underlyingActor.session.get
      next = TestActorRef[DummyAction](Props(new DummyAction))(system)
      val action = Predef.websocket("testAttributeName")
        .close("testRequestName")
        .build(next)

      action ! session
    }
  }

  private case class contains(str: String) extends ArgumentMatcher[Option[String]] {
    def matches(argument: Any) = argument.asInstanceOf[Option[String]].map(_.contains(str)).getOrElse(false)
  }
}

class DummyAction extends Actor {
  var session = Option.empty[Session]

  def receive = {
    case s: Session => session = Some(s)
  }
}
