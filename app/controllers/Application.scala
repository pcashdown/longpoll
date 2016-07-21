package controllers

import play.api._
import scala.concurrent.{Await, Future, Promise}
import libs.concurrent.Akka
import libs.json._
import play.api.mvc._
import java.util.concurrent.atomic.AtomicInteger
import play.api.Play.current  // Needed for Akka.system to compile
import scala.util.Random
import akka.actor.{Actor, Props}
import akka.pattern.ask
import scala.concurrent.duration._
import models.Stats
import scala.concurrent.ExecutionContext.Implicits.global

object Application extends Controller {

  /** Displays our html chatting page which includes javascript etc */
  def index = Action {
    Stats.countRequest()

    Ok(views.html.index())
  }

  /** To send a message */
  def sendMessage(text: String) = Action {
    Stats.countRequest()

    messagingActor ! SendMessage(text)
    Ok
  }

  /** To poll for sent messages */
  def poll(lastMessageId: Int) = Action.async {
    Stats.countRequest()

    val messagesFuture = waitForNewMessages(lastMessageId)
    val timeoutFuture = play.api.libs.concurrent.Promise.timeout("Timeout", 60 seconds)
    Future.firstCompletedOf(Seq(messagesFuture, timeoutFuture)).map {
      case messages: List[Message] => Ok(messagesToJson(messages))
      case timeout: String => Ok(messagesToJson(List.empty))
    }
  }

  private def waitForNewMessages(lastMessageId: Int): Future[List[Message]] = {
    implicit val timeout = akka.util.Timeout(60 seconds) // needed for ask below
    Await.result(messagingActor.ask(ListenForMessages(rndClientId, lastMessageId)).mapTo[Future[List[Message]]], 60 seconds)
  }

  private def rndClientId = Random.nextInt(999999).toString()

  private def messagesToJson(messages: List[Message]): JsObject = {
    val jsObs = messages.map( msg => JsObject(Seq("seqId" -> JsNumber(msg.seqId), "msg" -> JsString(msg.text))) )
    JsObject(Seq("result" -> JsArray(jsObs)))
  }

  lazy val messagingActor = {
    val actor = Akka.system.actorOf(Props[MessagingActor])

    // Tell the actor to broadcast messages every 1 second
    Akka.system.scheduler.schedule(0 seconds, 1 seconds, actor, BroadcastMessages())

    // Broadcast some jvm stats
    Akka.system.scheduler.schedule(0 seconds, 90 seconds, actor, SendMessage(Stats.getAll()))

    actor
  }
}

case class Message(seqId: Int, text: String)

case class SendMessage(text: String)
case class ListenForMessages(clientId: String, seqId: Int)
case class BroadcastMessages()

class MessagingActor extends Actor {
  case class Member(seqId: Int, promise: Promise[List[Message]])

  val seqCnt = new AtomicInteger()
  var messages = List[Message]()
  var members = Map.empty[String, Member]

  override def receive = {
    case BroadcastMessages() => {
      members.foreach {
        case (key, member) => {
          val newMessagesForMember = messages.filter(msg => msg.seqId > member.seqId)
          if (newMessagesForMember.size > 0) {
            member.promise.success(newMessagesForMember)
            members -= key
            Logger.info("Broadcasting "+newMessagesForMember.size+" msgs to " + key)
          }
        }
      }
    }

    case SendMessage(text) => {
      val msg = Message(seqCnt.incrementAndGet(), text)
      messages = (msg :: messages).sortBy( msg => msg.seqId )
      Logger.info("Added "+text+", seqId=="+seqCnt.get())
    }

    case ListenForMessages(clientId, seqId) => {
      val member = Member(seqId, Promise[List[Message]]())
      members = members + (clientId -> member)

      Logger.info("Messages requested by clientId="+clientId+" starting from seqId="+seqId)
      sender ! member.promise
    }

  }

}
