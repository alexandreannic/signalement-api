package controllers

import java.time.{LocalDateTime, YearMonth}
import java.util.UUID

import akka.stream.alpakka.s3.scaladsl.MultipartUploadResult
import javax.inject.Inject
import models.{File, Report, Statistics}
import play.api.libs.json.{JsError, Json}
import play.api.libs.mailer.AttachmentFile
import play.api.libs.streams.Accumulator
import play.api.mvc.MultipartFormData.FilePart
import play.api.{Configuration, Environment, Logger}
import play.core.parsers.Multipart
import play.core.parsers.Multipart.FileInfo
import repositories.{FileRepository, ReportRepository, ReportFilter}
import services.{MailerService, S3Service}
import scala.collection.mutable.ListBuffer

import scala.concurrent.{ExecutionContext, Future}

class ReportController @Inject()(reportRepository: ReportRepository,
                                 fileRepository: FileRepository,
                                 mailerService: MailerService,
                                 s3Service: S3Service,
                                 configuration: Configuration,
                                 environment: Environment)
                                (implicit val executionContext: ExecutionContext) extends BaseController {

  val logger: Logger = Logger(this.getClass)

  val BucketName = configuration.get[String]("play.buckets.report")

  def createReport = Action.async(parse.json) { implicit request =>

    logger.debug("createReport")

    request.body.validate[Report].fold(
      errors => Future.successful(BadRequest(JsError.toJson(errors))),
      report => {
        for {
          report <- reportRepository.create(
            report.copy(
              id = Some(UUID.randomUUID()),
              creationDate = Some(LocalDateTime.now())
            )
          )
          attachFilesToReport <- fileRepository.attachFilesToReport(report.fileIds, report.id.get)
          files <- fileRepository.retrieveReportFiles(report.id.get)
          mailNotification <- sendReportNotificationByMail(report, files)
          mailAcknowledgment <- sendReportAcknowledgmentByMail(report, files)
        } yield {
          Ok(Json.toJson(report))
        }
      }
    )
  }

  def uploadReportFile = Action.async(parse.multipartFormData(handleFilePartAwsUploadResult)) { request =>
    val maybeUploadResult =
      request.body.file("reportFile").map {
        case FilePart(key, filename, contentType, multipartUploadResult, _, _) =>
          (multipartUploadResult, filename)
      }

    maybeUploadResult.fold(Future(InternalServerError("Echec de l'upload"))) {
      maybeUploadResult =>
        fileRepository.create(
          File(UUID.fromString(maybeUploadResult._1.key), None, LocalDateTime.now(), maybeUploadResult._2)
        ).map(file => Ok(Json.toJson(file)))
    }
  }

  private def handleFilePartAwsUploadResult: Multipart.FilePartHandler[MultipartUploadResult] = {
    case FileInfo(partName, filename, contentType, _) =>
      val accumulator = Accumulator(s3Service.upload(BucketName, UUID.randomUUID.toString))

      accumulator map { multipartUploadResult =>
        FilePart(partName, filename, contentType, multipartUploadResult)
      }
  }

  def sendReportNotificationByMail(report: Report, files: List[File])(implicit request: play.api.mvc.Request[Any]) = {
    Future(mailerService.sendEmail(
      from = configuration.get[String]("play.mail.from"),
      recipients = configuration.get[String]("play.mail.contactRecipient"))(
      subject = "Nouveau signalement",
      bodyHtml = views.html.mails.reportNotification(report, files).toString
    ))
  }

  def sendReportAcknowledgmentByMail(report: Report, files: List[File]) = {
    report.category match {
      case "Intoxication alimentaire" => Future(())
      case _ =>
        Future(mailerService.sendEmail(
          from = configuration.get[String]("play.mail.from"),
          recipients = report.email)(
          subject = "Votre signalement",
          bodyHtml = views.html.mails.reportAcknowledgment(report, configuration.get[String]("play.mail.contactRecipient"), files).toString,
          attachments = Seq(
            AttachmentFile("logo-marianne.png", environment.getFile("/appfiles/logo-marianne.png"), contentId = Some("logo"))
          )
        ))
    }
  }

  def downloadReportFile(uuid: String, filename: String) = Action.async { implicit request =>
    fileRepository.get(UUID.fromString(uuid)).flatMap(_ match {
      case Some(file) if file.filename == filename =>
        s3Service.download(BucketName, uuid).flatMap(
          file => {
            val dest: Array[Byte] = new Array[Byte](file.asByteBuffer.capacity())
            file.asByteBuffer.get(dest)
            Future(Ok(dest))
          }
        )
      case _ => Future(NotFound)
    })
  }

  def deleteReportFile(uuid: String, filename: String) = Action.async { implicit request =>
    fileRepository.get(UUID.fromString(uuid)).flatMap(_ match {
      case Some(file) if file.filename == filename =>
        for {
          repositoryDelete <- fileRepository.delete(UUID.fromString(uuid))
          s3Delete <- s3Service.delete(BucketName, uuid)
        } yield Ok
      case _ => Future(NotFound)
    })
  }

  def getStatistics = Action.async { implicit request =>

    for {
      reportsCount <- reportRepository.count
      reportsPerMonth <- reportRepository.countPerMonth
    } yield {
      Ok(Json.toJson(
        Statistics(
          reportsCount,
          reportsPerMonth.filter(stat => stat.yearMonth.isAfter(YearMonth.now().minusYears(1)))
        )
      ))
    }
  }

  private def getSortList(input: Option[String]): List[String] = {

    val DIRECTIONS = List("asc", "desc")

    var res = new ListBuffer[String]()
  
    if (input.isDefined) {
      var fields = input.get.split(',').toList

      for (elt <- fields) {
        val parts = elt.split('.').toList

        if (parts.length > 0) {
          
          val index = reportRepository.getFieldsClassName.map(_.toLowerCase).indexOf(parts(0).toLowerCase)

          if (index > 0) {
            if (parts.length > 1) {
              if (DIRECTIONS.contains(parts(1))) {
                res += reportRepository.getFieldsClassName(index) + '.' + parts(1)
              } else {
                res += reportRepository.getFieldsClassName(index)
              }
            } else {
              res += reportRepository.getFieldsClassName(index)
            }
          }
          
        }
      }
    }

    //res.toList.map(println)

    return res.toList
  }
 
  def getReports(offset: Option[Long], limit: Option[Int], sort: Option[String], codePostal: Option[String]) = Action.async { implicit request => 

    // valeurs par défaut
    val OFFSET_DEFAULT = 0
    val LIMIT_DEFAULT = 25
    val LIMIT_MAX = 250

    // normalisation des entrées
    var offsetNormalized: Long = offset.map(Math.max(_, OFFSET_DEFAULT)).getOrElse(OFFSET_DEFAULT)
    var limitNormalized = limit.map(limit => Math.min(Math.max(limit, LIMIT_DEFAULT), LIMIT_MAX)).getOrElse(LIMIT_DEFAULT)

    // var sortList: List[String] = getSortList(sort)
    // println(">>>res") 
    // sortList.map(println)

    val filter = ReportFilter(codePostal = codePostal)
    
    reportRepository.getReports(offsetNormalized, limitNormalized, filter).flatMap( reports => {

      Future(Ok(Json.toJson(reports)))
    })

  }
}
