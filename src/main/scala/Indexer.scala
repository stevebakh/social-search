import akka.actor.ActorSystem
import akka.stream.{ActorMaterializer, scaladsl}
import play.api.libs.json._
import play.api.libs.ws.ahc.AhcWSClient
import play.api.mvc.MultipartFormData.DataPart
import wabisabi._

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future
import scala.io.Source
import scala.util.{Failure, Success}

object Indexer {

  val ApiKey: String = "<api_key>"

  implicit val actorSystem = ActorSystem()
  implicit val materializer = ActorMaterializer()

  lazy val httpClient = AhcWSClient()
  lazy val esClient = new Client("http://localhost:9200")

  def terminateAll() = {
    Client.shutdown()
    httpClient.close()
    actorSystem.terminate()
  }

  def main(args: Array[String]) {
    import SlackTypeImplicits._
    val sourcedata: JsValue = Json.parse(Source.fromFile(this.getClass.getResource("/data.json").toURI).getLines().mkString)
    val slackTeam = Json.fromJson[SlackTeam](sourcedata).get

    /*esClient.deleteIndex("messages")
    esClient.refresh("messages")
    terminateAll()*/

    val futureResponses = for {
      user <- slackTeam.users.filter(u => !u.is_bot && u.name != "slackbot")
      message <- user.messages.filter(m => m.`type` == "message" && m.sub_type.isEmpty)
    } yield {
      // argh! the concept extraction API we're using has a nasty rate limit set...
      Thread.sleep(1000)

      httpClient.url("http://api.meaningcloud.com/topics-2.0")
        .post(scaladsl.Source(
          DataPart("key", ApiKey) ::
          DataPart("tt", "ec") ::
          DataPart("lang", "en") ::
          DataPart("txt", message.text) :: List()))
        .flatMap {
          res =>
            val json = Json.parse(res.body)
            val entities = (json \ "entity_list" \\ "form").map(_.toString)
            val concepts = (json \ "concept_list" \\ "form").map(_.toString)

            println(s"Indexing message ${message.text}")

            esClient.index(
              index = "messages",
              `type` = "slack",
              id = Some(message.id),
              data = Json.obj(
                "content" -> message.text,
                "user_id" -> user.id,
                "user_name" -> user.name,
                "concepts" -> concepts,
                "entities" -> entities).toString)
        }
    }

    Future.sequence(futureResponses).onComplete {
      case Failure(e) =>
        System.out.println(s"Uhh, error! ${e.getMessage}")
        terminateAll()
      case Success(responses) =>
        System.out.println("failed: " + responses.map(_.getStatusCode).count(_ != 201))
        System.out.println("succeeded: " + responses.map(_.getStatusCode).count(_ == 201))
        esClient.refresh("messages").onComplete(r => terminateAll())
    }
  }
}
