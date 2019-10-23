package controllers.report

import java.time.OffsetDateTime
import java.util.UUID

import com.google.inject.AbstractModule
import com.mohiva.play.silhouette.api.{Environment, LoginInfo}
import com.mohiva.play.silhouette.impl.providers.CredentialsProvider
import com.mohiva.play.silhouette.test.{FakeEnvironment, _}
import controllers.ReportController
import models._
import org.specs2.Specification
import org.specs2.concurrent.ExecutionEnv
import org.specs2.matcher.FutureMatchers
import play.api.libs.json.Json
import play.api.libs.mailer.{Attachment, AttachmentFile}
import play.api.mvc.Result
import play.api.test._
import play.mvc.Http.Status
import repositories.{EventRepository, ReportRepository, UserRepository}
import services.MailerService
import utils.AppSpec
import utils.Constants.ActionEvent.ActionEventValue
import utils.Constants.ReportStatus.ReportStatusValue
import utils.Constants.{ActionEvent, Departments, ReportStatus}
import utils.silhouette.auth.AuthEnv

import scala.concurrent.Await
import scala.concurrent.duration.Duration


class ReportResponseByUnauthenticatedUser(implicit ee: ExecutionEnv) extends ReportResponseSpec  {
  override def is =
    s2"""
         Given an unauthenticated user                                ${step(someLoginInfo = None)}
         When post a response                                         ${step(someResult = Some(postReportResponse(reportReponseAccepted)))}
         Then result status is not authorized                         ${resultStatusMustBe(Status.UNAUTHORIZED)}
    """
}

class ReportResponseByNotConcernedProUser(implicit ee: ExecutionEnv) extends ReportResponseSpec  {
  override def is =
    s2"""
         Given an authenticated pro user which is not concerned by the report   ${step(someLoginInfo = Some(notConcernedProLoginInfo))}
         When post a response                                                   ${step(someResult = Some(postReportResponse(reportReponseAccepted)))}
         Then result status is not found                                        ${resultStatusMustBe(Status.NOT_FOUND)}
    """
}

class ReportResponseProAnswer(implicit ee: ExecutionEnv) extends ReportResponseSpec {
  override def is =
    s2"""
        Given an authenticated pro user which is concerned by the report         ${step(someLoginInfo = Some(concernedProLoginInfo))}
        When post a response with type "ACCEPTED"                                ${step(someResult = Some(postReportResponse(reportReponseAccepted)))}
        Then an event "REPONSE_PRO_SIGNALEMENT" is created                       ${eventMustHaveBeenCreatedWithAction(ActionEvent.REPONSE_PRO_SIGNALEMENT)}
        And the report reportStatusList is updated to "PROMESSE_ACTION"          ${reportMustHaveBeenUpdatedWithStatus(reportUUID, ReportStatus.PROMESSE_ACTION)}
        And an acknowledgment email is sent to the consumer                      ${mailMustHaveBeenSent(reportFixture.email,"Le professionnel a répondu à votre signalement", views.html.mails.consumer.reportToConsumerAcknowledgmentPro(report, reportReponseAccepted).toString, Seq(AttachmentFile("logo-signal-conso.png", app.environment.getFile("/appfiles/logo-signal-conso.png"), contentId = Some("logo"))))}
        And an acknowledgment email is sent to the professional                  ${mailMustHaveBeenSent(concernedProUser.email.get,"Votre réponse au signalement", views.html.mails.professional.reportAcknowledgmentPro(reportReponseAccepted, concernedProUser).toString, Seq(AttachmentFile("logo-signal-conso.png", app.environment.getFile("/appfiles/logo-signal-conso.png"), contentId = Some("logo"))))}
        And an acknowledgment email is sent to admins                            ${mailMustHaveBeenSent(contactEmail,"Un professionnel a répondu à un signalement", views.html.mails.admin.reportToAdminAcknowledgmentPro(report, reportReponseAccepted).toString)}
    """
}

class ReportResponseProRejectedAnswer(implicit ee: ExecutionEnv) extends ReportResponseSpec {
  override def is =
    s2"""
        Given an authenticated pro user which is concerned by the report         ${step(someLoginInfo = Some(concernedProLoginInfo))}
        When post a response with type "REJECTED"                                ${step(someResult = Some(postReportResponse(reportReponseRejected)))}
        Then an event "REPONSE_PRO_SIGNALEMENT" is created                       ${eventMustHaveBeenCreatedWithAction(ActionEvent.REPONSE_PRO_SIGNALEMENT)}
        And the report reportStatusList is updated to "SIGNALEMENT_INFONDE"      ${reportMustHaveBeenUpdatedWithStatus(reportUUID, ReportStatus.SIGNALEMENT_INFONDE)}
        And an acknowledgment email is sent to the consumer                      ${mailMustHaveBeenSent(reportFixture.email,"Le professionnel a répondu à votre signalement", views.html.mails.consumer.reportToConsumerAcknowledgmentPro(report, reportReponseRejected).toString, Seq(AttachmentFile("logo-signal-conso.png", app.environment.getFile("/appfiles/logo-signal-conso.png"), contentId = Some("logo"))))}
        And an acknowledgment email is sent to the professional                  ${mailMustHaveBeenSent(concernedProUser.email.get,"Votre réponse au signalement", views.html.mails.professional.reportAcknowledgmentPro(reportReponseRejected, concernedProUser).toString, Seq(AttachmentFile("logo-signal-conso.png", app.environment.getFile("/appfiles/logo-signal-conso.png"), contentId = Some("logo"))))}
        And an acknowledgment email is sent to admins                            ${mailMustHaveBeenSent(contactEmail,"Un professionnel a répondu à un signalement", views.html.mails.admin.reportToAdminAcknowledgmentPro(report, reportReponseRejected).toString)}
    """
}

class ReportResponseProNotConcernedAnswer(implicit ee: ExecutionEnv) extends ReportResponseSpec {
  override def is =
    s2"""
        Given an authenticated pro user which is concerned by the report         ${step(someLoginInfo = Some(concernedProLoginInfo))}
        When post a response with type "NOT_CONCERNED"                           ${step(someResult = Some(postReportResponse(reportReponseNotConcerned)))}
        Then an event "REPONSE_PRO_SIGNALEMENT" is created                       ${eventMustHaveBeenCreatedWithAction(ActionEvent.REPONSE_PRO_SIGNALEMENT)}
        And the report reportStatusList is updated to "MAL_ATTRIBUE"             ${reportMustHaveBeenUpdatedWithStatus(reportUUID, ReportStatus.SIGNALEMENT_MAL_ATTRIBUE)}
        And an acknowledgment email is sent to the consumer                      ${mailMustHaveBeenSent(reportFixture.email,"Le professionnel a répondu à votre signalement", views.html.mails.consumer.reportToConsumerAcknowledgmentPro(report, reportReponseNotConcerned).toString, Seq(AttachmentFile("logo-signal-conso.png", app.environment.getFile("/appfiles/logo-signal-conso.png"), contentId = Some("logo"))))}
        And an acknowledgment email is sent to the professional                  ${mailMustHaveBeenSent(concernedProUser.email.get,"Votre réponse au signalement", views.html.mails.professional.reportAcknowledgmentPro(reportReponseNotConcerned, concernedProUser).toString, Seq(AttachmentFile("logo-signal-conso.png", app.environment.getFile("/appfiles/logo-signal-conso.png"), contentId = Some("logo"))))}
        And an acknowledgment email is sent to admins                            ${mailMustHaveBeenSent(contactEmail,"Un professionnel a répondu à un signalement", views.html.mails.admin.reportToAdminAcknowledgmentPro(report, reportReponseNotConcerned).toString)}
    """
}

abstract class ReportResponseSpec(implicit ee: ExecutionEnv) extends Specification with AppSpec with FutureMatchers {

  import org.specs2.matcher.MatchersImplicits._

  lazy val reportRepository = app.injector.instanceOf[ReportRepository]
  lazy val userRepository = app.injector.instanceOf[UserRepository]
  lazy val eventRepository = app.injector.instanceOf[EventRepository]

  val contactEmail = "contact@signalconso.beta.gouv.fr"

  val siretForConcernedPro = "000000000000000"
  val siretForNotConcernedPro = "11111111111111"

  val reportUUID = UUID.randomUUID()
  val reportFixture = Report(
    Some(reportUUID), "category", List("subcategory"), List(), None, "companyName", "companyAddress", Some(Departments.AUTHORIZED(0)), Some(siretForConcernedPro), Some(OffsetDateTime.now()),
    "firstName", "lastName", "email", true, List(), None
  )

  var report = reportFixture

  val concernedProUser = User(UUID.randomUUID(), siretForConcernedPro, "password", None, Some("Prénom"), Some("Nom"), Some("pro@signalconso.beta.gouv.fr"), UserRoles.Pro)
  val concernedProLoginInfo = LoginInfo(CredentialsProvider.ID, concernedProUser.login)

  val notConcernedProUser = User(UUID.randomUUID(), siretForNotConcernedPro, "password", None, Some("Prénom"), Some("Nom"), Some("pro@signalconso.beta.gouv.fr"), UserRoles.Pro)
  val notConcernedProLoginInfo = LoginInfo(CredentialsProvider.ID, notConcernedProUser.login)

  var someLoginInfo: Option[LoginInfo] = None
  var someResult: Option[Result] = None

  val reportReponseAccepted = ReportResponse(ReportResponseType.ACCEPTED, "details for consumer", Some("details for dgccrf"))
  val reportReponseRejected = ReportResponse(ReportResponseType.REJECTED, "details for consumer", Some("details for dgccrf"))
  val reportReponseNotConcerned = ReportResponse(ReportResponseType.NOT_CONCERNED, "details for consumer", Some("details for dgccrf"))

  override def setupData = {
    userRepository.create(concernedProUser)
    userRepository.create(notConcernedProUser)
    reportRepository.create(reportFixture)
  }

  override def configureFakeModule(): AbstractModule = {
    new FakeModule
  }

  class FakeModule extends AppFakeModule {
    override def configure() = {
      super.configure
      bind[Environment[AuthEnv]].toInstance(env)
    }
  }

  implicit val env: Environment[AuthEnv] = new FakeEnvironment[AuthEnv](Seq(
    concernedProLoginInfo -> concernedProUser,
    notConcernedProLoginInfo -> notConcernedProUser
  ))

  def postReportResponse(reportResponse: ReportResponse) =  {
    Await.result(
      app.injector.instanceOf[ReportController].reportResponse(reportUUID.toString)
        .apply(someLoginInfo.map(FakeRequest().withAuthenticator[AuthEnv](_)).getOrElse(FakeRequest("POST", s"/api/reports/${reportUUID}/response")).withBody(Json.toJson(reportResponse))),
      Duration.Inf)
  }

  def resultStatusMustBe(status: Int) = {
    someResult must beSome and someResult.get.header.status === status
  }

  def mailMustHaveBeenSent(recipient: String, subject: String, bodyHtml: String, attachments: Seq[Attachment] = null) = {
    there was one(app.injector.instanceOf[MailerService])
      .sendEmail(
        app.configuration.get[String]("play.mail.from"),
        recipient
      )(
        subject,
        bodyHtml,
        attachments
      )
  }

  def eventMustHaveBeenCreatedWithAction(action: ActionEventValue) = {
    val events = Await.result(eventRepository.list, Duration.Inf).toList
    events.length must beEqualTo(1)
    events.head.action must beEqualTo(action)
  }

  def eventActionMatcher(action: ActionEventValue): org.specs2.matcher.Matcher[Event] = { event: Event =>
    (action == event.action, s"action doesn't match ${action}")
  }

  def reportMustHaveBeenUpdatedWithStatus(reportUUID: UUID, status: ReportStatusValue) = {
    report = Await.result(reportRepository.getReport(reportUUID), Duration.Inf).get
    report must reportStatusMatcher(Some(status))

  }

  def reportStatusMatcher(status: Option[ReportStatusValue]): org.specs2.matcher.Matcher[Report] = { report: Report =>
    (status == report.status, s"status doesn't match ${status} - ${report}")
  }

}