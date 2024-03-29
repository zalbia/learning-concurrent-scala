package org.learningconcurrency.ch4

import java.util.concurrent.ConcurrentHashMap

import scala.async.Async.{async, await}
import scala.collection.mutable
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration._
import scala.concurrent.{Await, Future, Promise, blocking}
import scala.io.Source
import scala.io.StdIn.readLine
import scala.util.Success

object Exercise1 extends App {
  print("Enter your URL: ")
  val userUrl = readLine()
  val donePromise = Promise[Unit]
  val dotFuture = Future {
    while (!donePromise.isCompleted) {
      Thread.sleep(50)
      print(".")
    }
  }
  val timeoutDuration = 2000
  val timeoutFuture = timeout(timeoutDuration).map { _ =>
    donePromise.trySuccess(())
    println("Request timed out!")
    Iterator.empty
  }
  val urlHtmlFuture = Future {
    val value = Source.fromURL(userUrl).getLines
    donePromise.success(())
    value
  }

  Await.ready(for {
    _ <- dotFuture
    it <- urlHtmlFuture.or(timeoutFuture)
    _ <- donePromise.future
  } yield {
    println("\n")
    it.foreach(println)
  }, Duration.Inf)
}

object Exercise2 extends App {

  class IVar[T] {
    private val promise = Promise[T]

    def apply(): T =
      if (promise.isCompleted)
        Await.result(promise.future, Duration.Inf)
      else throw new IllegalStateException

    def :=(x: T): Unit =
      promise.success(x)
  }

  val a = new IVar[Int]
  a := 4
  println(a())
}

object Exercise3 {

  implicit class FutureOps[T](f: Future[T]) {
    def exists(p: T => Boolean): Future[Boolean] = f.map(p)
  }

}

object Exercise4 {

  implicit class FutureOps[T](f: Future[T]) {
    def exists(pred: T => Boolean): Future[Boolean] = {
      val p = Promise[Boolean]
      f.onComplete { case Success(t) => p.success(pred(t)) }
      p.future
    }
  }

}

object Exercise5 {

  implicit class FutureOps[T](f: Future[T]) {
    def exists(pred: T => Boolean): Future[Boolean] = async {
      pred(await(f))
    }
  }

}

object Exercise6 {

  import sys.process._

  def spawn(command: String): Future[Int] = Future {
    blocking {
      command.!
    }
  }
}

object Exercise7 {

  class IMap[K, V] {

    import scala.collection.concurrent
    import scala.jdk.CollectionConverters.ConcurrentMapHasAsScala

    val map: concurrent.Map[K, Promise[V]] = new ConcurrentHashMap[K, Promise[V]]().asScala

    def update(k: K, v: V): Unit = map.get(k).fold {
      map(k) = Promise.successful(v)
    } {
      _.success(v)
    }

    def apply(k: K): Future[V] = map.get(k).fold {
      map(k) = Promise[V]()
      map(k).future
    } {
      _.future
    }
  }

}

object Exercise8 {

  import concurrent.ExecutionContext.Implicits.global

  implicit class PromiseOps[T](promise: Promise[T]) {
    def compose[S](f: S => T): Promise[S] = {
      val np = Promise[S]
      np.future.map(f.andThen(promise.success))
      np
    }
  }

}

object Exercise9 extends App {

  def scatterGather[T](tasks: Seq[() => T]): Future[Seq[T]] = {
    tasks.map(t => Future {
      t()
    }).foldRight(Future(List.empty[T])) { (fs, f) =>
      fs.zip(f).map { case (t, ts) => t :: ts }
    }
  }

  def task(i: Int, range: Range, f: (Int, Int) => Int): () => Int = () => {
    println(s"starting task $i")
    val res = range.reduce(f)
    println(s"task $i done")
    res
  }

  println(Await.result(scatterGather(List(
    task(1, 1 to 1000, _ - _),
    task(2, 1001 to 2000, math.max),
    task(3, 2001 to 3000, math.min),
    task(4, 3001 to 4000, _ % _)
  )), Duration.Inf))
}

object Exercise11 extends App {

  class DAG[T](val value: T) {
    val edges = mutable.Set[DAG[T]]()
  }

  def fold[T, S](g: DAG[T], f: (T, Seq[S]) => S): Future[S] =
    Future
      .sequence(g.edges.toSeq.map { g => fold(g, f) })
      .map(ss => f(g.value, ss))

  val a = new DAG("a")
  val b = new DAG("b")
  val c = new DAG("c")
  val d = new DAG("d")
  val e = new DAG("e")
  a.edges += b
  b.edges += c
  b.edges += d
  c.edges += e
  d.edges += e

  println(
    Await.result(
      fold[String, String](a, (t, ss) => {
        println(s"t = $t")
        println(s"ss = $ss")
        t ++ ss.mkString
      }),
      Duration.Inf
    )
  )
}
