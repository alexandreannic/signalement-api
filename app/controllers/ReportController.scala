package controllers

import java.net.URI
import java.nio.file.Paths
import java.util.UUID

import com.mohiva.play.silhouette.api.Silhouette
import javax.inject.Inject
import models._
import orchestrators.ReportOrchestrator
import play.api.libs.json.{JsError, Json, Writes}
import play.api.{Configuration, Logger}
import repositories._
import services.{PDFService, S3Service}
import utils.Constants.ActionEvent._
import utils.Constants.{ActionEvent, EventType}
import utils.SIRET
import utils.silhouette.api.APIKeyEnv
import utils.silhouette.auth.{AuthEnv, WithPermission, WithRole}

import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Failure, Success, Try}

class ReportController @Inject()(reportOrchestrator: ReportOrchestrator,
                                 companyRepository: CompanyRepository,
                                 reportRepository: ReportRepository,
                                 eventRepository: EventRepository,
                                 s3Service: S3Service,
                                 pdfService: PDFService,
                                 val silhouette: Silhouette[AuthEnv],
                                 val silhouetteAPIKey: Silhouette[APIKeyEnv],
                                 configuration: Configuration)
                                (implicit val executionContext: ExecutionContext) extends BaseController {

  val logger: Logger = Logger(this.getClass)

  val BucketName = configuration.get[String]("play.buckets.report")
  implicit val websiteUrl = configuration.get[URI]("play.application.url")
  val tmpDirectory = configuration.get[String]("play.tmpDirectory")
  val allowedExtensions = configuration.get[Seq[String]]("play.upload.allowedExtensions")

  private def getProLevel(user: User, report: Option[Report]) =
    report
      .filter(_.status.getValueWithUserRole(user.userRole).isDefined)
      .flatMap(_.companyId).map(companyRepository.getUserLevel(_, user))
      .getOrElse(Future(AccessLevel.NONE))

  def createReport = UnsecuredAction.async(parse.json) { implicit request =>
    request.body.validate[DraftReport].fold(
      errors => Future.successful(BadRequest(JsError.toJson(errors))),
      report => reportOrchestrator.newReport(report).map(report => Ok(Json.toJson(report)))
    )
  }

  def updateReportCompany(uuid: String) = SecuredAction(WithPermission(UserPermission.updateReport)).async(parse.json) { implicit request =>
    request.body.validate[ReportCompany].fold(
      errors => Future.successful(BadRequest(JsError.toJson(errors))),
      reportCompany => reportOrchestrator.updateReportCompany(UUID.fromString(uuid), reportCompany, request.identity.id).map{
            case Some(report) => Ok(Json.toJson(report))
            case None => NotFound
          }
    )
  }

  def updateReportConsumer(uuid: String) = SecuredAction(WithPermission(UserPermission.updateReport)).async(parse.json) { implicit request =>
    request.body.validate[ReportConsumer].fold(
      errors => Future.successful(BadRequest(JsError.toJson(errors))),
      reportConsumer => reportOrchestrator.updateReportConsumer(UUID.fromString(uuid), reportConsumer, request.identity.id).map{
            case Some(_) => Ok
            case None => NotFound
          }
    )
  }

  def reportResponse(uuid: String) = SecuredAction(WithRole(UserRoles.Pro)).async(parse.json) { implicit request =>
    logger.debug(s"reportResponse ${uuid}")
    request.body.validate[ReportResponse].fold(
      errors => Future.successful(BadRequest(JsError.toJson(errors))),
      reportResponse => {
        for {
          report <- reportRepository.getReport(UUID.fromString(uuid))
          level <-  getProLevel(request.identity, report)
          updatedReport <- report.filter(_ => level != AccessLevel.NONE)
            .map(reportOrchestrator.handleReportResponse(_, reportResponse, request.identity).map(Some(_))).getOrElse(Future(None))
        } yield updatedReport
          .map(r => Ok(Json.toJson(r)))
          .getOrElse(NotFound)
        }
      )
  }

  def createReportAction(uuid: String) = SecuredAction(WithPermission(UserPermission.createReportAction)).async(parse.json) { implicit request =>
    request.body.validate[ReportAction].fold(
      errors => Future.successful(BadRequest(JsError.toJson(errors))),
      reportAction =>
        for {
          report <- reportRepository.getReport(UUID.fromString(uuid))
          newEvent <- report.filter(_ => actionsForUserRole(request.identity.userRole).contains(reportAction.actionType))
            .map(reportOrchestrator.handleReportAction(_, reportAction, request.identity).map(Some(_))).getOrElse(Future(None))
        } yield newEvent
          .map(e => Ok(Json.toJson(e)))
          .getOrElse(NotFound)
    )
  }

  def reviewOnReportResponse(uuid: String) = UnsecuredAction.async(parse.json) { implicit request =>
    request.body.validate[ReviewOnReportResponse].fold(
      errors => Future.successful(BadRequest(JsError.toJson(errors))),
      review => for {
          events <- eventRepository.getEvents(UUID.fromString(uuid), EventFilter())
          result <- if (!events.exists(_.action == ActionEvent.REPORT_PRO_RESPONSE)) {
            Future(Forbidden)
          } else if (events.exists(_.action == ActionEvent.REPORT_REVIEW_ON_RESPONSE)) {
            Future(Conflict)
          } else {
            reportOrchestrator.handleReviewOnReportResponse(UUID.fromString(uuid), review).map(_ => Ok)
          }
        } yield result
    )
  }

  def uploadReportFile = UnsecuredAction.async(parse.multipartFormData) { request =>
    request.body
      .file("reportFile")
      .filter(f => allowedExtensions.contains(f.filename.toLowerCase.toString.split("\\.").last))
      .map { reportFile =>
        val filename = Paths.get(reportFile.filename).getFileName
        val tmpFile = new java.io.File(s"$tmpDirectory/${UUID.randomUUID}_${filename}")
        reportFile.ref.copyTo(tmpFile)
        reportOrchestrator.saveReportFile(
          filename.toString,
          tmpFile,
          request.body.dataParts.get("reportFileOrigin").map(o => ReportFileOrigin(o.head)).getOrElse(ReportFileOrigin.CONSUMER)
        ).map(file => Ok(Json.toJson(file)))
      }
      .getOrElse(Future(InternalServerError("Echec de l'upload")))
  }

  def downloadReportFile(uuid: String, filename: String) = UnsecuredAction.async { implicit request =>
    reportRepository.getFile(UUID.fromString(uuid)).map(_ match {
      case Some(file) if file.avOutput.isEmpty => Conflict("Analyse antivirus en cours, veuillez réessayer d'ici 30 secondes")  // HTTP 409
      case Some(file) if (file.filename == filename && file.avOutput.isDefined) => Redirect(s3Service.getSignedUrl(BucketName, file.storageFilename))
      case _ => NotFound
    })
  }

  def deleteReportFile(id: String, filename: String) = UserAwareAction.async { implicit request =>
    val uuid = UUID.fromString(id)
    reportRepository.getFile(uuid).flatMap(_ match {
      case Some(file) if file.filename == filename =>
        (file.reportId, request.identity) match {
          case (None, _) =>
            reportOrchestrator.removeReportFile(uuid).map(_ => NoContent)
          case (Some(reportId), Some(identity)) if identity.userRole.permissions.contains(UserPermission.deleteFile) =>
            reportOrchestrator.removeReportFile(uuid).map(_ => NoContent)
          case (_, _) => Future(Forbidden)
        }
      case _ => Future(NotFound)
    })
  }

  def getReport(uuid: String) = SecuredAction(WithPermission(UserPermission.listReports)).async { implicit request =>
    Try(UUID.fromString(uuid)) match {
      case Failure(_) => Future.successful(PreconditionFailed)
      case Success(id) => for {
        report        <- reportRepository.getReport(id)
        proLevel      <- getProLevel(request.identity, report)
        updatedReport <- report
                          .filter(_ =>
                                  request.identity.userRole == UserRoles.DGCCRF
                              ||  request.identity.userRole == UserRoles.Admin
                              ||  proLevel != AccessLevel.NONE)
                          match {
                            case Some(r) => reportOrchestrator.handleReportView(r, request.identity).map(Some(_))
                            case _ => Future(None)
                          }
        reportFiles <- report.map(r => reportRepository.retrieveReportFiles(r.id)).getOrElse(Future(List.empty))
      } yield updatedReport
              .map(report => Ok(Json.toJson(ReportWithFiles(report, reportFiles))))
              .getOrElse(NotFound)
    }
  }

  def getReportToExternal(uuid: String) = silhouetteAPIKey.SecuredAction.async {
    implicit def reportFilewriter = new Writes[ReportFile] {
      def writes(reportFile: ReportFile) =
        Json.obj(
          "id" -> reportFile.id,
          "filename"-> reportFile.filename
        )
    }
    implicit def reportWriter = new Writes[Report] {
      def writes(report: Report) =
        Json.obj(
          "id" -> report.id,
          "category" -> report.category,
          "subcategories" -> report.subcategories,
          "siret" -> report.companySiret,
          "postalCode" -> report.companyPostalCode,
          "websiteURL" -> report.websiteURL,
          "firstName" -> report.firstName,
          "lastName" -> report.lastName,
          "email" -> report.email,
          "contactAgreement" -> report.contactAgreement,
          "effectiveDate" -> report.details.filter(d => d.label.matches("Date .* (constat|contrat|rendez-vous|course) .*")).map(_.value).headOption,
      )
    }
    implicit def writer = Json.writes[ReportWithFiles]
    Try(UUID.fromString(uuid)) match {
      case Failure(_) => Future.successful(PreconditionFailed)
      case Success(id) =>
        for {
          report        <- reportRepository.getReport(id)
          reportFiles <- report.map(r => reportRepository.retrieveReportFiles(r.id)).getOrElse(Future(List.empty))
        } yield report
          .map(report => Ok(Json.toJson(ReportWithFiles(report, reportFiles.filter(_.origin == ReportFileOrigin.CONSUMER)))))
          .getOrElse(NotFound)
    }
  }

  def reportAsPDF(uuid: String) = SecuredAction(WithPermission(UserPermission.listReports)).async { implicit request =>

    Try(UUID.fromString(uuid)) match {
      case Failure(_) => Future.successful(PreconditionFailed)
      case Success(id) => for {
        report        <- reportRepository.getReport(id)
        events        <- eventRepository.getEventsWithUsers(id, EventFilter())
        companyEvents <- report.map(_.companyId).flatten.map(companyId => eventRepository.getCompanyEventsWithUsers(companyId, EventFilter())).getOrElse(Future(List.empty))
        reportFiles   <- reportRepository.retrieveReportFiles(id)
        proLevel      <- getProLevel(request.identity, report)
      } yield report
              .filter(_ =>
                              request.identity.userRole == UserRoles.DGCCRF
                          ||  request.identity.userRole == UserRoles.Admin
                          ||  proLevel != AccessLevel.NONE)
              .map(report =>
                  pdfService.Ok(
                    List(views.html.pdfs.report(report, events, companyEvents, reportFiles))
                  )
              )
              .getOrElse(NotFound)
    }
  }

  def deleteReport(uuid: String) = SecuredAction(WithPermission(UserPermission.deleteReport)).async {
    Try(UUID.fromString(uuid)) match {
      case Failure(_) => Future.successful(PreconditionFailed)
      case Success(id) => reportOrchestrator.deleteReport(id).map(if (_) NoContent else NotFound)
    }
  }

  def getReportCountBySiret(siret: String) = silhouetteAPIKey.SecuredAction.async {
    reportRepository.count(Some(SIRET(siret))).map(count => Ok(Json.obj("siret" -> siret, "count" -> count)))
  }

  def getEvents(reportId: String, eventType: Option[String]) = SecuredAction(WithPermission(UserPermission.listReports)).async { implicit request =>
    val filter = eventType match {
      case Some(_) => EventFilter(eventType = Some(EventType.fromValue(eventType.get)))
      case None => EventFilter()
    }

    Try(UUID.fromString(reportId)) match {
      case Failure(_) => Future.successful(PreconditionFailed)
      case Success(id) => {
        for {
          report <- reportRepository.getReport(id)
          events <- eventRepository.getEventsWithUsers(id, filter)
        } yield {
          report match {
            case Some(_) => Ok(Json.toJson(
              events.filter(event =>
                request.identity.userRole match {
                  case UserRoles.Pro => List(REPORT_PRO_RESPONSE, REPORT_READING_BY_PRO) contains event._1.action
                  case _ => true
                }
              )
              .map { case (event, user) => Json.obj(
                "data" -> event,
                "user"  -> user.map(u => Json.obj(
                  "firstName" -> u.firstName,
                  "lastName"  -> u.lastName,
                  "role"      -> u.userRole.name
                ))
              )}
            ))
            case None => NotFound
          }
        }
      }}
  }

  def getCompanyEvents(siret: String, eventType: Option[String]) = SecuredAction(WithPermission(UserPermission.listReports)).async { implicit request =>
    val filter = eventType match {
      case Some(_) => EventFilter(eventType = Some(EventType.fromValue(eventType.get)))
      case None => EventFilter()
    }
    for {
      company <- companyRepository.findBySiret(SIRET(siret))
      events <- company.map(_.id).map(id => eventRepository.getCompanyEventsWithUsers(id, filter).map(Some(_))).getOrElse(Future(None))
    } yield {
      company match {
        case Some(_) => Ok(Json.toJson(
          events.get.filter(event =>
            request.identity.userRole match {
              case UserRoles.Pro => List(REPORT_PRO_RESPONSE, REPORT_READING_BY_PRO) contains event._1.action
              case _ => true
            }
          )
          .map { case (event, user) => Json.obj(
            "data" -> event,
            "user"  -> user.map(u => Json.obj(
              "firstName" -> u.firstName,
              "lastName"  -> u.lastName,
              "role"      -> u.userRole.name
            ))
          )}
        ))
        case None => NotFound
      }
    }
  }

  def getNbReportsGroupByCompany(offset: Option[Long], limit: Option[Int]) = SecuredAction.async { implicit request =>
    implicit val paginatedReportWriter = PaginatedResult.paginatedCompanyWithNbReportsWriter

    // valeurs par défaut
    val LIMIT_DEFAULT = 25
    val LIMIT_MAX = 250

    // normalisation des entrées
    val offsetNormalized: Long = offset.map(Math.max(_, 0)).getOrElse(0)
    val limitNormalized = limit.map(Math.max(_, 0)).map(Math.min(_, LIMIT_MAX)).getOrElse(LIMIT_DEFAULT)

    reportRepository.getNbReportsGroupByCompany(offsetNormalized, limitNormalized).map( paginatedReports => {
      Ok(Json.toJson(paginatedReports))
    })

  }


}
