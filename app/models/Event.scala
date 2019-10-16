package models

import java.time.OffsetDateTime
import java.util.UUID

import play.api.libs.json._
import utils.Constants.ActionEvent.ActionEventValue
import utils.Constants.EventType.EventTypeValue


case class Event(
                  id: Option[UUID],
                  reportId: Option[UUID],
                  userId: Option[UUID],
                  creationDate: Option[OffsetDateTime],
                  eventType: EventTypeValue,
                  action: ActionEventValue,
                  resultAction: Option[Boolean],
                  details: Option[JsValue]
                )
                 
object Event {

  implicit val eventFormat: OFormat[Event] = Json.format[Event]
  implicit def stringToDetailsJsValue(value: String): JsValue = Json.obj("description" -> value)
  implicit def jsValueToString(jsValue: Option[JsValue]) = jsValue.flatMap(_.as[JsObject].value.get("description").map(_.toString))

}