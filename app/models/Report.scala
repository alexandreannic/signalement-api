package models

import java.time.OffsetDateTime
import java.util.UUID

import com.github.tminglei.slickpg.composite.Struct
import play.api.libs.json.{Json, OFormat, Writes}
import utils.Constants.ReportStatus._
import utils.Constants.Departments
import utils.EmailsHelpers._

case class Report(
                   id: Option[UUID],
                   category: String,
                   subcategories: List[String],
                   details: List[DetailInputValue],
                   companyId: Option[UUID],
                   companyName: String,
                   companyAddress: String,
                   companyPostalCode: Option[String],
                   companySiret: Option[String],
                   creationDate: Option[OffsetDateTime],
                   firstName: String,
                   lastName: String,
                   email: Email,
                   contactAgreement: Boolean,
                   files: List[ReportFile],
                   status: Option[ReportStatusValue]
                 ) {
  def isEligible = {
    companyPostalCode.map(postalCode => Departments.AUTHORIZED.contains(postalCode.slice(0, 2))).getOrElse(false);
  }
}

object Report {

  implicit val reportWriter = Json.writes[Report]
  implicit val reportReader = Json.reads[Report]

  val reportProWriter = new Writes[Report] {
    def writes(report: Report) =
      Json.obj(
      "id" -> report.id,
      "category" -> report.category,
      "subcategories" -> report.subcategories,
      "details" -> report.details,
      "creationDate" -> report.creationDate,
      "companyName" -> report.companyName,
      "companyAddress" -> report.companyAddress,
      "companyPostalCode" -> report.companyPostalCode,
      "companySiret" -> report.companySiret,
      "files" -> report.files,
      "contactAgreement" -> report.contactAgreement,
      "status" -> report.status
    ) ++ (report.contactAgreement match {
        case true => Json.obj(
          "firstName" -> report.firstName,
          "lastName" -> report.lastName,
          "email" -> normalizeEmail(report.email)
        )
        case _ => Json.obj()
      })
  }
}


case class  DetailInputValue (
                           label: String,
                           value: String
                 ) extends Struct

object DetailInputValue {
  implicit val detailInputValueFormat: OFormat[DetailInputValue] = Json.format[DetailInputValue]

  implicit def string2detailInputValue(input: String): DetailInputValue = {
    input match {
      case input if input.contains(':') => DetailInputValue(input.substring(0, input.indexOf(':') + 1), input.substring(input.indexOf(':') + 1).trim)
      case input => DetailInputValue("Précision :", input)
    }
  }
}

case class CompanyWithNbReports(companySiret: String, companyPostalCode: String, companyName: String, companyAddress: String, count: Int)

object CompanyWithNbReports {

  implicit val companyWithNbReportsWrites = new Writes[CompanyWithNbReports] {
    def writes(company: CompanyWithNbReports) = Json.obj(
      "companyPostalCode" -> company.companyPostalCode,
      "companySiret" -> company.companySiret,
      "companyName" -> company.companyName,
      "companyAddress" -> company.companyAddress,
      "count" -> company.count
    )
  }
}
