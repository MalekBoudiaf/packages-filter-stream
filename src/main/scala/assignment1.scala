import akka.{Done, NotUsed}
import akka.actor.ActorSystem
import akka.stream.{FlowShape, Graph, IOResult, OverflowStrategy, SinkShape}
import akka.stream.scaladsl.{Balance, Broadcast, Compression, FileIO, Flow, Framing, GraphDSL, Keep, Merge, RunnableGraph, Sink, Source}
import akka.util.ByteString

import concurrent.duration.DurationInt
import java.nio.file.Paths
import scala.concurrent.{ExecutionContextExecutor, Future}

// Requests a package to the API and returns the information about that package

def apiRequest(packageName: String): ujson.Value =
  // println(s"Analysing ${packageName}")

  val url = s"https://api.npms.io/v2/package/${packageName}"
  val response = requests.get(url)
  val json : ujson.Value = ujson.read(response.data.toString)
  json

case class Package(name:String, stars:Int, tests:Double, downloads:Int, releaseFrequency:Double)


object assignment1 extends App:


  implicit val actorSystem: ActorSystem = ActorSystem("Exercise4")
  implicit val executionContext: ExecutionContextExecutor = actorSystem.dispatcher

  val path = Paths.get("src/main/resources/packages.txt.gz")


  // composite source dealing with reading, decompressing and converting input data
  val compositeSource: Source[String, Future[IOResult]] = FileIO.fromPath(path)
    // decompressing the input .gz file
    .via(Compression.gunzip())
    // splitting the decompresses byteStrings by line
    .via(Framing.delimiter(ByteString("\n"), 1024, true))
    // converting each line to a string
    .map(_.utf8String)
    .named("source")


  val packageBuffer = Flow[String].buffer(20, OverflowStrategy.backpressure)
  val limiter = Flow[String].throttle(1,3.second)
  // a flow that will query the json data of each package name and only return the relevant json objects
  val apiReqFlow : Flow[String,(String,ujson.Value,ujson.Value),NotUsed] = Flow[String].map(packageName => {
    // apiRequest() function is modified to take the name of the package as a string
    val jsonResponse = apiRequest(packageName)
    // get the Github metrics from the json response
    val githubJSON = jsonResponse("collected")("github")
    // get the evaluation metrics from the json response
    val evaluationJSON = jsonResponse("evaluation")
    (packageName,githubJSON,evaluationJSON)
  })

  val packageObjectifyFlow : Flow[(String,ujson.Value,ujson.Value),Package,NotUsed] =
    Flow[(String,ujson.Value,ujson.Value)]
    .map((packageName,githubJSON,evaluationJSON) => {
      Package(name = packageName,
        stars = githubJSON.asInstanceOf[ujson.Value]("starsCount").num.toInt,
        tests = evaluationJSON.asInstanceOf[ujson.Value]("quality")("tests").num,
        downloads = evaluationJSON.asInstanceOf[ujson.Value]("popularity")("downloadsCount").num.toInt,
        releaseFrequency = evaluationJSON.asInstanceOf[ujson.Value]("maintenance")("releasesFrequency").num
      )
    }
  )

  val flowFilterPackages: Graph[FlowShape[Package, Package], NotUsed] = Flow.fromGraph(
    GraphDSL.create() {
      implicit builder =>
        import GraphDSL.Implicits._

        val balancer = builder.add(Balance[Package](3))
        val merge = builder.add(Merge[Package](3))


        val starsFilter: Flow[Package, Package, NotUsed] = Flow[Package].filter(p => p.stars > 20)
        val testsFilter: Flow[Package, Package, NotUsed] = Flow[Package].filter(p => p.tests > 0.5)
        val downloadsFilter: Flow[Package, Package, NotUsed] = Flow[Package].filter(p => p.downloads > 100)
        val releaseFreqFilter: Flow[Package, Package, NotUsed] = Flow[Package].filter(p => p.releaseFrequency > 0.2)

        balancer ~> starsFilter ~> testsFilter ~> downloadsFilter ~> releaseFreqFilter ~> merge
        balancer ~> starsFilter ~> testsFilter ~> downloadsFilter ~> releaseFreqFilter ~> merge
        balancer ~> starsFilter ~> testsFilter ~> downloadsFilter ~> releaseFreqFilter ~> merge


        FlowShape(balancer.in, merge.out)
    }
  )



  // constructing a sink from a partial graph
  val combinedSink : Graph[SinkShape[Package], NotUsed] = Sink.fromGraph(
    GraphDSL.create() {
      implicit builder =>
        import GraphDSL.Implicits._

        val broadcast = builder.add(Broadcast[Package](2))

        // simple sink that will print result to terminal
        val terminalSink: Sink[Package, Future[Done]] = Sink.foreach(pack => {
          println(s"Name = ${pack.name}, stars = ${pack.stars}, " +
            s"tests = ${pack.tests}, downloads = ${pack.downloads}, " +
            s"freq = ${pack.releaseFrequency}")
        })

        // path to the file where output will be saved
        val outfile = Paths.get("src/main/resources/output.txt")

        // creating a nested sink to save the output to a file
        // a flow that converts our objectified packages into ByteStrings which the sink expects
        val outputFlow : Flow[Package, ByteString, NotUsed] = Flow[Package].map(pack =>
          ByteString.fromString(s"Name = ${pack.name}," +
            s" stars = ${pack.stars}, tests = ${pack.tests}," +
            s" downloads = ${pack.downloads}, freq = ${pack.releaseFrequency}\n"))

        // a sink that takes ByteStrings and saves them into a file
        val sinkToFile: Sink[ByteString, Future[IOResult]] = FileIO.toPath(outfile)

        // nested sink
        val nestedOutputSink: Sink[Package, Future[IOResult]]  = outputFlow
          .toMat(sinkToFile)(Keep.right)
          .named("saveOutputSink")

        // broadcast packages to both the sinks
        broadcast ~> terminalSink
        broadcast ~> nestedOutputSink

        // return a sinK shape with the input to the broadcast exposed
        SinkShape(broadcast.in)
    }
  )


  val runnableGraphNormal: RunnableGraph[NotUsed] = compositeSource.
    via(packageBuffer)
    .via(limiter)
    .via(apiReqFlow)
    .via(packageObjectifyFlow)
    .via(flowFilterPackages)
    .toMat(combinedSink)(Keep.right)

  runnableGraphNormal.run()


  Thread.sleep(180000)
  sys.exit(0)