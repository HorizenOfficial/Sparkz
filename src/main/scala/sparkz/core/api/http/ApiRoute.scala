package sparkz.core.api.http

import akka.actor.ActorRefFactory
import akka.http.scaladsl.server.Route
import akka.http.scaladsl.unmarshalling.PredefinedFromEntityUnmarshallers
import akka.util.Timeout
import de.heikoseeberger.akkahttpcirce.FailFastCirceSupport
import io.circe.Printer
import sparkz.core.utils.ActorHelper
import sparkz.util.SparkzLogging

import scala.language.implicitConversions

trait ApiRoute
  extends ApiDirectives
    with ActorHelper
    with FailFastCirceSupport
    with PredefinedFromEntityUnmarshallers
    with SparkzLogging {

  def context: ActorRefFactory
  def route: Route

  //TODO: should we move it to the settings?
  override val apiKeyHeaderName: String = "api_key"

  implicit val printer: Printer = Printer.spaces2.copy(dropNullValues = true)
  implicit lazy val timeout: Timeout = Timeout(settings.timeout)

}
