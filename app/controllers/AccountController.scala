package controllers

import java.io.{ByteArrayInputStream, File}
import java.util.UUID

import com.itextpdf.html2pdf.resolver.font.DefaultFontProvider
import com.itextpdf.html2pdf.{ConverterProperties, HtmlConverter}
import com.itextpdf.kernel.pdf.{PdfDocument, PdfWriter}
import com.mohiva.play.silhouette.api.Silhouette
import com.mohiva.play.silhouette.api.util.Credentials
import com.mohiva.play.silhouette.impl.providers.CredentialsProvider
import javax.inject.{Inject, Singleton}
import models._
import play.api._
import play.api.libs.json.JsError
import repositories._
import utils.Constants.{ActionEvent, ReportStatus}
import utils.silhouette.auth.{AuthEnv, WithPermission}

import scala.concurrent.{ExecutionContext, Future}

@Singleton
class AccountController @Inject()(
                                   val silhouette: Silhouette[AuthEnv],
                                   userRepository: UserRepository,
                                   companyRepository: CompanyRepository,
                                   companyAccessRepository: CompanyAccessRepository,
                                   reportRepository: ReportRepository,
                                   eventRepository: EventRepository,
                                   credentialsProvider: CredentialsProvider,
                                   configuration: Configuration
                              )(implicit ec: ExecutionContext)
 extends BaseController {

  val logger: Logger = Logger(this.getClass())
  val reportExpirationDelay = java.time.Period.parse(configuration.get[String]("play.reports.expirationDelay"))

  def changePassword = SecuredAction.async(parse.json) { implicit request =>

    logger.debug("changePassword")

    request.body.validate[PasswordChange].fold(
      errors => {
        Future.successful(BadRequest(JsError.toJson(errors)))
      },
      passwordChange => {
        for {
          identLogin <- credentialsProvider.authenticate(Credentials(request.identity.login, passwordChange.oldPassword))
          _ <- userRepository.updatePassword(request.identity.id, passwordChange.newPassword)
        } yield {
          NoContent
        }
      }.recover {
        case e => {
          Unauthorized
        }
      }
    )
  }

  def activateAccount = UnsecuredAction.async(parse.json) { implicit request =>

    logger.debug("activateAccount")

    request.body.validate[ActivationRequest].fold(
      errors => {
        Future.successful(BadRequest(JsError.toJson(errors)))
      },
      // FIXME: Move part of the logic in a new AccessOrchestrator
      {case ActivationRequest(draftUser, tokenInfo) => {
        for {
          company     <- companyRepository.findBySiret(tokenInfo.companySiret)
          token       <- company.map(companyAccessRepository.findToken(_, tokenInfo.token)).getOrElse(Future(None))
          applied     <- token.map(t =>
                          userRepository.create(User(
                            // FIXME: Remove login field
                            UUID.randomUUID(), draftUser.email, draftUser.password, None,
                            Some(draftUser.email), Some(draftUser.firstName), Some(draftUser.lastName), UserRoles.Pro
                          )).map(companyAccessRepository.applyToken(t, _)))
                          .getOrElse(Future(false))
        } yield NoContent
      }.recover {
        case e => Unauthorized
      }}
    )
  }

  // This route is maintained for backward compatibility until front is updated
  def activateAccountDeprecated = SecuredAction(WithPermission(UserPermission.activateAccount)).async(parse.json) { implicit request =>

    logger.debug("activateAccountDeprecated")

    request.body.validate[User].fold(
      errors => {
        Future.successful(BadRequest(JsError.toJson(errors)))
      },
      user => {
        for {
          activationKey <- userRepository.get(user.id).map(_.flatMap(_.activationKey))
          _ <- userRepository.update(user)
          _ <- userRepository.updateAccountActivation(request.identity.id, None, UserRoles.Pro)
          _ <- userRepository.updatePassword(request.identity.id, user.password)
          // Forward compatibility with new access model
          company <- companyRepository.findBySiret(request.identity.login)
          token <- activationKey
                    .flatMap(key =>
                      company.map(c => companyAccessRepository.findToken(c, key)))
                    .getOrElse(Future(None))
          _ <- token.map(companyAccessRepository.applyToken(_, user)).getOrElse(Future(None))
        } yield NoContent
      }.recover {
        case e => Unauthorized
      }
    )
  }

  def getActivationDocument(siret: String) = SecuredAction(WithPermission(UserPermission.editDocuments)).async { implicit request =>

    for {
      user <- userRepository.findByLogin(siret)
      paginatedReports <- reportRepository.getReports(0, 1, ReportFilter(siret = Some(siret), statusList = Seq(ReportStatus.A_TRAITER.defaultValue)))
      report <- paginatedReports.entities match {
        case report :: otherReports => Future(Some(report))
        case Nil => Future(None)
      }
      events <- report match {
        case Some(report) => eventRepository.getEvents(report.id.get, EventFilter(action = None))
        case _ => Future(List())
      }
    } yield {
      (report, user) match {
        case (Some(report), Some(user)) if user.activationKey.isDefined =>

          val tmpFileName = s"${configuration.get[String]("play.tmpDirectory")}/activation_${siret}.pdf";
          val pdf = new PdfDocument(new PdfWriter(tmpFileName))

          val converterProperties = new ConverterProperties
          val dfp = new DefaultFontProvider(true, true, true)
          converterProperties.setFontProvider(dfp)
          converterProperties.setBaseUri(configuration.get[String]("play.application.url"))
          val creationDate = events
                            .filter(_.action == ActionEvent.CONTACT_COURRIER)
                            .headOption
                            .flatMap(_.creationDate)
                            .getOrElse(report.creationDate.get)
                            .toLocalDate
          val pdfString = if (events.exists(_.action == ActionEvent.RELANCE)) {
              views.html.pdfs.accountActivationReminder(
                report.companyAddress,
                creationDate,
                creationDate.plus(reportExpirationDelay),
                user.activationKey.get
              )
            } else {
              views.html.pdfs.accountActivation(
                report.companyAddress,
                report.creationDate.map(_.toLocalDate).get,
                user.activationKey.get
              )
            }

          HtmlConverter.convertToPdf(new ByteArrayInputStream(pdfString.body.getBytes()), pdf, converterProperties)

          Ok.sendFile(new File(tmpFileName), onClose = () => new File(tmpFileName).delete)

        case (Some(report), Some(user)) => NotFound("Il n'y a pas de code d'activation associé à ce Siret")
        case (Some(report), None) => NotFound("Il n'y a pas d'utilisateur associé à ce Siret")
        case (None, _) => NotFound("Il n'y a pas de signalement à traiter associé à ce Siret")
      }
    }
  }

}