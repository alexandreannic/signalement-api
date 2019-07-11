package controllers

import com.hhandoko.play.pdf.PdfGenerator
import com.mohiva.play.silhouette.api.Silhouette
import com.mohiva.play.silhouette.api.util.Credentials
import com.mohiva.play.silhouette.impl.providers.CredentialsProvider
import javax.inject.{Inject, Singleton}
import models.{PasswordChange, User, UserPermission, UserRoles}
import play.api._
import play.api.libs.json.JsError
import repositories.{ReportFilter, ReportRepository, UserRepository}
import utils.Constants.StatusPro
import utils.silhouette.{AuthEnv, WithPermission}

import scala.concurrent.{ExecutionContext, Future}

@Singleton
class AccountController @Inject()(
                                val silhouette: Silhouette[AuthEnv],
                                userRepository: UserRepository,
                                reportRepository: ReportRepository,
                                credentialsProvider: CredentialsProvider,
                                configuration: Configuration,
                                pdfGenerator: PdfGenerator
                              )(implicit ec: ExecutionContext)
 extends BaseController {

  val logger: Logger = Logger(this.getClass())

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

  def activateAccount = SecuredAction(WithPermission(UserPermission.activateAccount)).async(parse.json) { implicit request =>

    logger.debug("activateAccount")

    request.body.validate[User].fold(
      errors => {
        Future.successful(BadRequest(JsError.toJson(errors)))
      },
      user => {
        for {
          _ <- userRepository.update(user)
          _ <- userRepository.updateAccountActivation(request.identity.id, None, UserRoles.Pro)
          _ <- userRepository.updatePassword(request.identity.id, user.password)
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

  def getActivationDocument(siret: String) = SecuredAction(WithPermission(UserPermission.editDocuments)).async { implicit request =>

    for {
      user <- userRepository.findByLogin(siret)
      paginatedReports <- reportRepository.getReports(0, 1, ReportFilter(siret = Some(siret), statusPro = Some(StatusPro.A_TRAITER.value)))
      report <- paginatedReports.entities match {
        case report :: otherReports => Future(Some(report))
        case Nil => Future(None)
      }
    } yield {
      (report, user) match {
        case (Some(report), Some(user)) if user.activationKey.isDefined =>
          pdfGenerator.ok(
            views.html.pdfs.accountActivation(
              report.companyAddress.split("-").filter(_.trim != "FRANCE").toList,
              report.creationDate.map(_.toLocalDate).get,
              "1232456"
            ), configuration.get[String]("play.application.url")
        )
        case (Some(report), Some(user)) => NotFound("Il n'y a pas de code d'activation associé à ce Siret")
        case (Some(report), None) => NotFound("Il n'y a pas d'utilisateur associé à ce Siret")
        case (None, _) => NotFound("Il n'y a pas de signalement à traiter associé à ce Siret")
      }
    }
  }

}