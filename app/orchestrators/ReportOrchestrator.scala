package orchestrators

import javax.inject.Inject
import java.time.OffsetDateTime
import java.util.UUID
import play.api.{Configuration, Environment, Logger}
import play.api.libs.mailer.AttachmentFile
import scala.concurrent.{ExecutionContext, Future}
import scala.util.Random

import models._
import repositories._
import services.{MailerService, S3Service}
import utils.Constants
import utils.Constants.ActionEvent._
import utils.Constants.StatusPro._


class ReportOrchestrator @Inject()(reportRepository: ReportRepository,
                                   eventRepository: EventRepository,
                                   userRepository: UserRepository,
                                   mailerService: MailerService,
                                   s3Service: S3Service,
                                   configuration: Configuration,
                                   environment: Environment)
                                   (implicit val executionContext: ExecutionContext) {

  val logger = Logger(this.getClass)
  val bucketName = configuration.get[String]("play.buckets.report")
  val mailFrom = configuration.get[String]("play.mail.from")

  private def notifyProfessionalOfNewReport(report: Report): Future[_] = {
    userRepository.findByLogin(report.companySiret.get).flatMap{
      case Some(user) if user.email.filter(_ != "").isDefined => {
        mailerService.sendEmail(
          from = mailFrom,
          recipients = user.email.get)(
          subject = "Nouveau signalement",
          bodyHtml = views.html.mails.professional.reportNotification(report).toString,
          attachments = Seq(
            AttachmentFile("logo-signal-conso.png", environment.getFile("/appfiles/logo-signal-conso.png"), contentId = Some("logo"))
          )
        )
        eventRepository.createEvent(
          Event(
            Some(UUID.randomUUID()),
            report.id,
            user.id,
            Some(OffsetDateTime.now()),
            Constants.EventType.PRO,
            Constants.ActionEvent.CONTACT_EMAIL,
            None,
            Some(s"Notification du professionnel par mail de la réception d'un nouveau signalement ( ${user.email.getOrElse("") } )")
          )
        ).map(event =>
          reportRepository.update(report.copy(statusPro = Some(TRAITEMENT_EN_COURS)))
        )
      }
      case None => {
        val activationKey = f"${Random.nextInt(1000000)}%06d"
        userRepository.create(
          User(
            UUID.randomUUID(),
            report.companySiret.get,
            activationKey,
            Some(activationKey),
            None,
            None,
            None,
            UserRoles.ToActivate
          )
        )
      }
      case _ => Future(Unit)
    }
  }

  def newReport(draftReport: Report)(implicit request: play.api.mvc.Request[Any]) =
    for {
      report <- reportRepository.create(
        draftReport.copy(
          id = Some(UUID.randomUUID()),
          creationDate = Some(OffsetDateTime.now()),
          statusPro = Some(if (draftReport.isEligible) Constants.StatusPro.A_TRAITER else Constants.StatusPro.NA)
        )
      )
      _ <- reportRepository.attachFilesToReport(report.files.map(_.id), report.id.get)
      files <- reportRepository.retrieveReportFiles(report.id.get)
    } yield {
      mailerService.sendEmail(
        from = mailFrom,
        recipients = configuration.get[String]("play.mail.contactRecipient"))(
        subject = "Nouveau signalement",
        bodyHtml = views.html.mails.admin.reportNotification(report, files).toString
      )
      mailerService.sendEmail(
        from = mailFrom,
        recipients = report.email)(
        subject = "Votre signalement",
        bodyHtml = views.html.mails.consumer.reportAcknowledgment(report, files).toString,
        attachments = Seq(
          AttachmentFile("logo-signal-conso.png", environment.getFile("/appfiles/logo-signal-conso.png"), contentId = Some("logo"))
        )
      )
      if (report.isEligible && report.companySiret.isDefined) notifyProfessionalOfNewReport(report)
      report
    }
  
  def updateReport(id: UUID, reportData: Report): Future[Option[Report]] =
    for {
      existingReport <- reportRepository.getReport(id)
      updatedReport <- existingReport match {
        case Some(report) => reportRepository.update(report.copy(
          firstName = report.firstName,
          lastName = report.lastName,
          email = report.email,
          contactAgreement = report.contactAgreement,
          companyName = report.companyName,
          companyAddress = report.companyAddress,
          companyPostalCode = report.companyPostalCode,
          companySiret = report.companySiret
        )).map(Some(_))
        case _ => Future(None)
      }
    } yield {
      updatedReport
        .filter(_.isEligible)
        .filter(_.companySiret.isDefined)
        .flatMap(r => existingReport.filter(_.companySiret != r.companySiret))
        .foreach(notifyProfessionalOfNewReport)
      updatedReport
    }

  def handleReportView(report: Report, user: User): Future[Report] = {
    if (user.userRole == UserRoles.Pro) {
      eventRepository.getEvents(report.id.get, EventFilter(None)).map(events =>
        if(!events.exists(_.action == Constants.ActionEvent.ENVOI_SIGNALEMENT))
        {
          return manageFirstViewOfReportByPro(report, user.id)
        }
      )
    }
    Future(report)
  }

  def addReportFile(id: UUID, filename: String) =
    reportRepository.createFile(ReportFile(id, None, OffsetDateTime.now(), filename))

  def removeReportFile(id: UUID) =
    for {
      repositoryDelete <- reportRepository.deleteFile(id)
      s3Delete <- s3Service.delete(bucketName, id.toString)
    } yield ()

  def deleteReport(id: UUID) =
    for {
      report <- reportRepository.getReport(id)
      _ <- eventRepository.deleteEvents(id)
      _ <- reportRepository.delete(id)
    } yield {
      report.isDefined
    }

  private def manageFirstViewOfReportByPro(report: Report, userUUID: UUID) = {
    for {
      event <- eventRepository.createEvent(
        Event(
          Some(UUID.randomUUID()),
          report.id,
          userUUID,
          Some(OffsetDateTime.now()),
          Constants.EventType.PRO,
          Constants.ActionEvent.ENVOI_SIGNALEMENT,
          None,
          Some("Première consultation du détail du signalement par le professionnel")
        )
      )
      updatedReport <- report.statusPro match {
        case Some(status) if status.isFinal => Future(report)
        case _ => notifyConsumerOfReportTransmission(report, userUUID)
      }
    } yield updatedReport
  }

  private def notifyConsumerOfReportTransmission(report: Report, userUUID: UUID): Future[Report] = {
    logger.debug(report.statusPro.get.toString)
    mailerService.sendEmail(
      from = configuration.get[String]("play.mail.from"),
      recipients = report.email)(
      subject = "Votre signalement",
      bodyHtml = views.html.mails.consumer.reportTransmission(report).toString,
      attachments = Seq(
        AttachmentFile("logo-signal-conso.png", environment.getFile("/appfiles/logo-signal-conso.png"), contentId = Some("logo"))
      )
    )
    for {
      event <- eventRepository.createEvent(
        Event(
          Some(UUID.randomUUID()),
          report.id,
          userUUID,
          Some(OffsetDateTime.now()),
          Constants.EventType.CONSO,
          Constants.ActionEvent.EMAIL_TRANSMISSION,
          None,
          Some("Envoi email au consommateur d'information de transmission")
        )
      )
      newReport <- reportRepository.update(report.copy(statusPro = Some(SIGNALEMENT_TRANSMIS)))
    } yield newReport
  }

  private def sendMailsAfterProAcknowledgment(report: Report, event: Event, user: User) = {
    user.email.filter(_ != "").foreach(email =>
      mailerService.sendEmail(
        from = mailFrom,
        recipients = email)(
        subject = "Votre réponse au signalement",
        bodyHtml = views.html.mails.professional.reportAcknowledgmentPro(event, user).toString,
        attachments = Seq(
          AttachmentFile("logo-signal-conso.png", environment.getFile("/appfiles/logo-signal-conso.png"), contentId = Some("logo"))
        )
      )
    )
    mailerService.sendEmail(
      from = mailFrom,
      recipients = report.email)(
      subject = "Le professionnel a répondu à votre signalement",
      bodyHtml = views.html.mails.consumer.reportToConsumerAcknowledgmentPro(report, event).toString,
      attachments = Seq(
        AttachmentFile("logo-signal-conso.png", environment.getFile("/appfiles/logo-signal-conso.png"), contentId = Some("logo"))
      )
    )
  }

  private def sendMailClosedByNoReading(report: Report) = {
    mailerService.sendEmail(
      from = configuration.get[String]("play.mail.from"),
      recipients = report.email)(
      subject = "Le professionnel n’a pas souhaité consulter votre signalement",
      bodyHtml = views.html.mails.consumer.reportClosedByNoReading(report).toString,
      attachments = Seq(
        AttachmentFile("logo-signal-conso.png", environment.getFile("/appfiles/logo-signal-conso.png"), contentId = Some("logo"))
      )
    )
  }

  private def sendMailClosedByNoAction(report: Report) = {
    mailerService.sendEmail(
      from = configuration.get[String]("play.mail.from"),
      recipients = report.email)(
      subject = "Le professionnel n’a pas répondu au signalement",
      bodyHtml = views.html.mails.consumer.reportClosedByNoAction(report).toString,
      attachments = Seq(
        AttachmentFile("logo-signal-conso.png", environment.getFile("/appfiles/logo-signal-conso.png"), contentId = Some("logo"))
      )
    )
  }

  def newEvent(reportId: UUID, draftEvent: Event, user: User): Future[Option[Event]] =
    for {
      report <- reportRepository.getReport(reportId)
      newEvent <- report match {
          case Some(r) => eventRepository.createEvent(
            draftEvent.copy(
              id = Some(UUID.randomUUID()),
              creationDate = Some(OffsetDateTime.now()),
              reportId = r.id,
              userId = user.id
            )).map(Some(_))
          case _ => Future(None)
      }
      updatedReport: Option[Report] <- (report, newEvent) match {
        case (Some(r), Some(event)) => reportRepository.update(
          r.copy(
            statusPro = (event.action, event.resultAction) match {
              case (CONTACT_COURRIER, _)                 => Some(TRAITEMENT_EN_COURS)
              case (REPONSE_PRO_SIGNALEMENT, Some(true)) => Some(PROMESSE_ACTION)
              case (REPONSE_PRO_SIGNALEMENT, _)          => Some(SIGNALEMENT_INFONDE)
              case (_, _)                                => r.statusPro
            })
        ).map(Some(_))
        case _ => Future(None)
      }
    } yield {
      newEvent.foreach(event => event.action match {
        case ENVOI_SIGNALEMENT => notifyConsumerOfReportTransmission(report.get, user.id)
        case REPONSE_PRO_SIGNALEMENT => sendMailsAfterProAcknowledgment(report.get, event, user)
        case NON_CONSULTE => sendMailClosedByNoReading(report.get)
        case CONSULTE_IGNORE => sendMailClosedByNoAction(report.get)
        case _ => ()
      })
      newEvent
    }

}