/*
 * Copyright 2015 Dennis Vriend
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.test.week5

import akka.actor.Status.Failure
import akka.actor._
import akka.event.LoggingReceive
import akka.pattern._
import com.github.dnvriend.{ HttpUtils, HttpClient }
import com.github.dnvriend.HttpClient._
import com.test.TestSpec

import scala.concurrent.Future
import scala.concurrent.duration._

class LinkCheckerTest extends TestSpec {
  object Getter {
    case object Done
    case object Abort
  }
  class Getter(url: String, depth: Int) extends Actor with ActorLogging {
    import Getter._
    implicit val ec = context.dispatcher

    HttpClient() get url pipeTo self

    override def receive: Receive = LoggingReceive {
      case body: String ⇒
        HttpUtils.findLinks(body).foreach { links ⇒
          for (link ← links) {
            context.parent ! Controller.Check(link, depth)
          }
        }
        stop()
      case Failure(t) ⇒
        stop()
      case Abort ⇒
        stop()
    }

    def stop(): Unit = {
      context.parent ! Getter.Done
      context.stop(self)
    }

    override def preStart(): Unit =
      log.info("Starting: {}", self.path)

    override def postStop(): Unit =
      log.info("Stopping: {}", self.path)

  }

  object Controller {
    case class Check(link: String, depth: Int)
    case class Result(cache: Set[String])
  }

  class Controller extends Actor with ActorLogging {
    import Controller._

    var cache = Set.empty[String] // stores the result url
    var children = Set.empty[ActorRef]

    context.system.scheduler.scheduleOnce(10.seconds, self, ReceiveTimeout)

    override def receive = LoggingReceive {
      case Check(url, depth) ⇒
        if (!cache(url) && depth > 0)
          children += context.actorOf(Props(new Getter(url, depth - 1)), s"getter-$randomId")
        cache += url
      case Getter.Done ⇒
        children -= sender
        if (children.isEmpty) {
          context.parent ! Result(cache)
        }
      case ReceiveTimeout ⇒
        children.foreach { _ ! Getter.Abort }
    }

    override def preStart(): Unit =
      log.info("Starting: {}", self.path)

    override def postStop(): Unit =
      log.info("Stopping: {}", self.path)
  }

  object Receptionist {
    case class Failed(url: String)
    case class Get(url: String)
    case class Result(url: String, links: Set[String])
  }

  class Receptionist extends Actor with ActorLogging {
    import Receptionist._
    case class Job(client: ActorRef, url: String)

    var reqNo = 0

    def runNext(queue: Vector[Job]): Receive = LoggingReceive {
      reqNo += 1
      if (queue.isEmpty) {
        log.info("Queue is empty, waiting for jobs")
        waiting
      } else {
        val controller = context.actorOf(Props(new Controller), s"c$reqNo")
        controller ! Controller.Check(queue.head.url, 2)
        log.info("Running job: {}", queue.head)
        running(queue)
      }
    }

    def enqueueJob(queue: Vector[Job], job: Job): Receive = LoggingReceive {
      if (queue.size > 3) {
        log.info("Cannot accept any more jobs: {}", job)
        sender ! Failed(job.url) // cannot accept any more jobs
        running(queue)
      } else {
        log.info("Enqueue job: {}", job)
        running(queue :+ job)
      }
    }

    val waiting: Receive = LoggingReceive {
      case Get(url) ⇒
        context.become(runNext(Vector(Job(sender(), url))))
    }

    def running(queue: Vector[Job]): Receive = LoggingReceive {
      case Controller.Result(links) ⇒
        val job = queue.head
        job.client ! Result(job.url, links)
        context.stop(sender())
        context.become(runNext(queue.tail))
      case Get(url) ⇒
        context.become(enqueueJob(queue, Job(sender(), url)))
    }

    override def receive = waiting

    override def preStart(): Unit =
      log.info("Starting: {}", self.path)

    override def postStop(): Unit =
      log.info("Stopping: {}", self.path)
  }

  "Receptionist" should "place a request and get a respond" in {
    import Receptionist._
    val receptionist = system.actorOf(Props(new Receptionist), "receptionist")
    (receptionist ? Get("http://www.google.com")).futureValue match {
      case Result(url, set) ⇒
        println(set.toVector.sorted.mkString(s"Results for '$url:'\n", "\n", "\n"))
      case Failed(url) ⇒
        println(s"Failed to fetch '$url'")
    }
    cleanup(receptionist)
  }

  it should "handle more work" in {
    import Receptionist._
    val receptionist = system.actorOf(Props(new Receptionist), "receptionist")
    Future.sequence((1 to 10).map(_ ⇒ receptionist ? Get("http://www.google.com")).toList).futureValue
    cleanup(receptionist)
  }
}
